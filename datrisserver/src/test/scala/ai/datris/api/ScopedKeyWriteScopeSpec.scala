package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.auth.{CapabilityDeniedException, ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.model._
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.{HttpStatus, ResponseEntity}

import scala.collection.JavaConverters._

/** Story: Scoped API keys, `catalog=` / `owner=self` scope holds on every
  * route (plans/stories/scoped-key-read-routes.md), Part A (writes and named
  * reads), Acceptance `ScopedKeyWriteScopeSpec`. Keys: `sales` = pipeline and
  * tap read, create, update `catalog=Sales`; `own` = the same with
  * `owner=self`.
  *
  * Every entry point below reads Mongo, the secret store or the MCP server
  * before it answers, so this spec pins the pure decision each one runs, with
  * the stored item given (the style of `saveGuardDecision` in
  * PipelineSaveGuardSpec and `uploadScopeRefusal` in CollectionScopeSpec).
  * A key's 403 is `PipelineAPIController.capabilityDenied` of the
  * `CapabilityCheck.assertScope` exception, so its body and wording are the
  * shared ones in [[ScopeDenialWording]]; a restricted session keeps today's
  * not-found answer for the route.
  *
  * Existing seams whose answer changes for a key (D0):
  * {{{
  * PipelineAPIController.saveGuardDecision(request, config, stored, readAll)   // + keyTargetRefusal, unreadable stored 403 (read)
  * PipelineAPIController.refusalFor(request, name, config, action)             // key outside scope: 403
  * EntityVersionAPIController.hiddenFromRestricted(request, resource, action, live) // true for a key outside scope
  * FieldProtectionAPIController.targetRefusal(request, pipeline, config, None) // key outside scope: throws CapabilityDeniedException
  * }}}
  * Pinned seams (do not exist at 68c7316d; the implementation adds them):
  * {{{
  * object TapAPIController {
  *   // POST /tap (saveTapInternal), every caller, decided on `stored` (the tap of that name or null):
  *   // key: the stored tap must be in scope (tap twin of overwriteRefusal) and the tap as it
  *   // would be saved must be in scope (grants tap create|update on scopeContext(target catalog,
  *   // stored owner or the caller's label)); restricted session: catalogForSave as today.
  *   def saveGuardDecision(request: HttpServletRequest, config: TapConfig, stored: TapConfig): Either[ResponseEntity[String], TapConfig]
  *   // restrictedRefusal / restrictedRefusalIfExists with the tap read given
  *   // (the two-arity forms read TapConfigIO): session 404 tapNotFound(name), key 403.
  *   def restrictedRefusal(request: HttpServletRequest, name: String, action: String, read: String => TapConfig): Option[ResponseEntity[String]]
  *   def restrictedRefusalIfExists(request: HttpServletRequest, name: String, action: String, read: String => TapConfig): Option[ResponseEntity[String]]
  * }
  * object EntityVersionAPIController {
  *   // GET /pipeline|tap/versions, /version, /version/diff and POST /tap/version/restore,
  *   // decided on the live item's (catalog, owner) (None: no live item). None: answer as today.
  *   // Some(sessionAnswer) for a restricted session hidden from it; Some(403) for a key outside scope.
  *   def versionRefusal(request: HttpServletRequest, resource: String, action: String,
  *                      live: Option[(String, String)], sessionAnswer: => ResponseEntity[String]): Option[ResponseEntity[String]]
  * }
  * object DestSchemaAPIController {
  *   // GET (action "read") and POST (action "update") /pipeline/dest-types on the stored config:
  *   // restricted session: today's 400 {"error": "Pipeline not found: <name>"}; key outside scope: 403.
  *   def scopeRefusal(request: HttpServletRequest, pipeline: String, config: PipelineConfig, action: String): Option[ResponseEntity[String]]
  * }
  * object FileUploadAPIController {
  *   // POST /pipeline/upload, after the config load: the pipeline-scope check (pipeline:run when the
  *   // key holds any pipeline:run capability, else pipeline:read; session: refusalFor(.., "run"))
  *   // and then the existing uploadScopeRefusal (collection=). Both must pass.
  *   def uploadRefusal(request: HttpServletRequest, name: String, config: PipelineConfig): Option[ResponseEntity[String]]
  * }
  * object QueryAPIController {
  *   // POST /job/kill before GlobalJobContext.killJob: the token resolved to its pipeline
  *   // (GlobalJobContext.findJobContext; `readPipeline` gives the stored config). None: kill as today
  *   // (an unknown token included). Some: refused, the same answer for a key and a session (D2).
  *   def killRefusal(request: HttpServletRequest, pipelineToken: String, readPipeline: String => PipelineConfig): Option[ResponseEntity[String]]
  * }
  * object PipelineStatusAPIController {
  *   // DELETE /pipeline/status: 403 for a limited caller (scope-limited key or restricted session).
  *   def clearAllRefusal(request: HttpServletRequest): Option[ResponseEntity[String]]
  * }
  * object MCPActivityAPIController {
  *   // GET and DELETE /mcp/activity: 403 for a limited caller, sessions included (D4).
  *   def activityRefusal(request: HttpServletRequest): Option[ResponseEntity[String]]
  * }
  * }}}
  */
class ScopedKeyWriteScopeSpec extends AnyFunSuite {

    // ------------------------------------------------------------------ fixtures

    private val salesCaps = Seq(
        "pipeline:read:catalog=Sales",
        "pipeline:create:catalog=Sales",
        "pipeline:update:catalog=Sales",
        "tap:read:catalog=Sales",
        "tap:create:catalog=Sales",
        "tap:update:catalog=Sales"
    )
    private val ownCaps = Seq(
        "pipeline:read:owner=self",
        "pipeline:create:owner=self",
        "pipeline:update:owner=self",
        "tap:read:owner=self",
        "tap:create:owner=self",
        "tap:update:owner=self"
    )
    private val sales: ResolvedKey = ScopeDenialFixtures.key("sales", salesCaps: _*)
    private val own: ResolvedKey = ScopeDenialFixtures.key("own", ownCaps: _*)
    private val full: ResolvedKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val wildcard: ResolvedKey = ScopeDenialFixtures.key("full", "*:*")

    private def restricted(g: (String, String)*): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants(g: _*)))

    private val editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val viewer: ResolvedKey = APIKeyValidator.resolveFromSession(User("vi", "h", User.RoleViewer, null, null, null))

    /** A fresh request per call, so the audit note is that call's. */
    private def req(rk: ResolvedKey, method: String = "POST", uri: String = "/api/v1/pipeline"): HttpServletRequest =
        ScopeDenialFixtures.request(rk, method, uri)._1

    private def pg(name: String, catalog: String, owner: String = "someone", d: Destination = null): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = owner, destination = d)

    private def tap(name: String, catalog: String, owner: String = "someone"): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = null, catalog = catalog, createdByKeyLabel = owner)

    private def qdrant(coll: String): Destination = Destination(qdrant = QdrantConfig(coll, ChunkingConfig(), null, null, null))

    private def status(r: ResponseEntity[String]): Int = r.getStatusCode.value

    /** A key's scope refusal: 403, the shared body, a `message` for one of
      * `actions` on `resource` (the story fixes the resource; where it does
      * not fix the action, every action it allows is accepted), nothing of
      * `hidden`, and the denial noted for the audit log when `noted` is the
      * request it was answered on. */
    private def assertDenied(
        r: ResponseEntity[String],
        label: String,
        resource: String,
        actions: Seq[String],
        caps: Seq[String],
        hidden: Seq[String],
        noted: HttpServletRequest = null
    ): Unit = {
        assert(r != null, "expected a refusal")
        assert(status(r) == 403, r.getBody)
        val message = JsonParser.parseString(r.getBody).getAsJsonObject.get("message").getAsString
        val action = actions.find(a => ScopeDenialWording.message(label, resource, a, caps) == message).getOrElse(actions.head)
        ScopeDenialWording.assertBody(r.getBody, ScopeDenialWording.message(label, resource, action, caps), hidden)
        if (noted != null) assert(AuditLog.scopeDenials(noted).nonEmpty, "the scope denial is not noted for the audit log")
    }

    private def left(d: Either[ResponseEntity[String], _]): ResponseEntity[String] = {
        assert(d.isLeft, "expected a refusal, got " + d)
        d.left.get
    }

    // ------------------------------------------------------------------ POST /pipeline

    test("a catalog-scoped key creating a pipeline with a blank catalog or in Marketing is 403; in Sales it saves") {
        for (catalog <- Seq(null, "", "Marketing")) {
            val r = req(sales)
            val d = PipelineAPIController.saveGuardDecision(r, pg("sales_new", catalog, owner = "sales"), null, () => Nil)
            assertDenied(left(d), "sales", "pipeline", Seq("create", "update"), salesCaps, Nil, noted = r)
        }
        // in Sales it saves, unchanged (no catalog defaulting for keys)
        val candidate = pg("sales_new", "Sales", owner = "sales")
        val ok = PipelineAPIController.saveGuardDecision(req(sales), candidate, null, () => Nil)
        assert(ok.isRight && ok.right.get.catalog == "Sales", ok.toString)
    }

    test("a catalog-scoped key moving its Sales pipeline to Marketing through the body is 403") {
        val stored = pg("sales_p", "Sales", owner = "sales")
        val r = req(sales)
        val d = PipelineAPIController.saveGuardDecision(r, stored.copy(catalog = "Marketing"), stored, () => Nil)
        assertDenied(left(d), "sales", "pipeline", Seq("create", "update"), salesCaps, Nil, noted = r)
        // a re-save in place still saves
        val same = PipelineAPIController.saveGuardDecision(req(sales), stored, stored, () => Nil)
        assert(same.isRight, same.toString)
    }

    test("a key with unscoped pipeline:create and scoped read cannot overwrite a pipeline it cannot read (403), and can create a new name") {
        val caps = Seq("pipeline:create", "pipeline:read:catalog=Sales")
        val k = ScopeDenialFixtures.key("creator", caps: _*)
        val hr = pg("payroll", "HR", owner = "someone")
        val r = req(k)
        // unreadable stored config: the read refusal (readScopeDenied), naming nothing of it
        val d = PipelineAPIController.saveGuardDecision(r, pg("payroll", "Sales", owner = "creator"), hr, () => Seq(hr))
        assertDenied(left(d), "creator", "pipeline", Seq("read"), caps, Seq("HR", "someone"), noted = r)
        // a new name saves
        val fresh = PipelineAPIController.saveGuardDecision(req(k), pg("fresh_p", "Sales", owner = "creator"), null, () => Seq(hr))
        assert(fresh.isRight, fresh.toString)
        // and a pipeline it can read may be overwritten
        val mine = pg("orders", "Sales")
        assert(PipelineAPIController.saveGuardDecision(req(k), mine, mine, () => Seq(mine)).isRight)
    }

    test("an owner=self key creates in any catalog and cannot save over another key's tap (403)") {
        // pipelines: any catalog, blank included; the config is saved as given
        for (catalog <- Seq("Marketing", "HR", null)) {
            val c = pg("own_new", catalog, owner = "own")
            val d = PipelineAPIController.saveGuardDecision(req(own), c, null, () => Nil)
            assert(d.isRight && d.right.get.catalog == catalog, catalog + ": " + d)
        }
        // taps: a new tap in any catalog saves
        for (catalog <- Seq("Marketing", "HR", null)) {
            val d = TapAPIController.saveGuardDecision(req(own, uri = "/api/v1/tap"), tap("own_feed", catalog, owner = "own"), null)
            assert(d.isRight && d.right.get.catalog == catalog, catalog + ": " + d)
        }
        // another key's tap: 403, naming neither its owner nor its catalog
        val theirs = tap("hr_feed", "HR", owner = "other-key")
        val r = req(own, uri = "/api/v1/tap")
        val d = TapAPIController.saveGuardDecision(r, tap("hr_feed", "Marketing", owner = "own"), theirs)
        assertDenied(left(d), "own", "tap", Seq("create", "update", "read"), ownCaps, Seq("other-key", "HR"), noted = r)
        // its own tap: saves
        val mine = tap("own_feed", "HR", owner = "own")
        assert(TapAPIController.saveGuardDecision(req(own, uri = "/api/v1/tap"), mine, mine).isRight)
    }

    // ------------------------------------------------------------------ tap sub-routes

    test(
        "tap script, state, ledger, generate, fix, optimize, pull, migrate and version restore on a tap outside scope are 403 for a key and 404 for a restricted session"
    ) {
        val hr = tap("hr_feed", "HR", owner = "someone")
        val mine = tap("s_feed", "Sales", owner = "sales")
        val read: String => TapConfig = Map("hr_feed" -> hr, "s_feed" -> mine).getOrElse(_, null)
        val session = restricted("Sales" -> "edit")
        val hidden = Seq("HR", "someone")

        // /tap/script, POST+DELETE /tap/state, DELETE /tap/ledger, /code-repo pull and
        // migrate (update); GET /tap/state and /tap/ledger (read): restrictedRefusal.
        // /tap/generate, /fix, /optimize (update): restrictedRefusalIfExists.
        type Refusal = (HttpServletRequest, String, String, String => TapConfig) => Option[ResponseEntity[String]]
        val forms: Seq[(String, Refusal, Seq[String])] = Seq(
            ("restrictedRefusal", (r, n, a, rd) => TapAPIController.restrictedRefusal(r, n, a, rd), Seq("update", "read")),
            ("restrictedRefusalIfExists", (r, n, a, rd) => TapAPIController.restrictedRefusalIfExists(r, n, a, rd), Seq("update"))
        )
        for ((form, refusal, actions) <- forms; action <- actions) {
            val kr = req(sales, uri = "/api/v1/tap/script")
            val k = refusal(kr, "hr_feed", action, read)
            assert(k.isDefined, form + " " + action + ": a key outside scope must be refused")
            assertDenied(k.get, "sales", "tap", Seq(action), salesCaps, hidden, noted = kr)

            val s = refusal(req(session, uri = "/api/v1/tap/script"), "hr_feed", action, read)
            assert(s.exists(x => status(x) == 404), form + " " + action + ": " + s.map(_.getBody))
            assert(s.get.getBody == TapAPIController.tapNotFound("hr_feed").getBody, "hidden must read as missing: " + s.get.getBody)
            hidden.foreach(h => assert(!s.get.getBody.contains(h), s.get.getBody))

            // in scope: neither is refused
            assert(refusal(req(sales), "s_feed", action, read).isEmpty, form + " " + action + " in scope (key)")
            assert(refusal(req(session), "s_feed", action, read).isEmpty, form + " " + action + " in scope (session)")
            // unscoped callers: never refused
            for (rk <- Seq(full, wildcard, editor)) assert(refusal(req(rk), "hr_feed", action, read).isEmpty, form + " " + rk.label)
        }
        // owner=self: another key's tap is 403, its own passes
        val theirs = tap("t_feed", "Sales", owner = "other-key")
        val ownTap = tap("o_feed", "HR", owner = "own")
        val readOwn: String => TapConfig = Map("t_feed" -> theirs, "o_feed" -> ownTap).getOrElse(_, null)
        assertDenied(TapAPIController.restrictedRefusal(req(own), "t_feed", "update", readOwn).orNull, "own", "tap", Seq("update"), ownCaps, Seq("other-key"))
        assert(TapAPIController.restrictedRefusal(req(own), "o_feed", "update", readOwn).isEmpty)
        // generate/fix/optimize for a tap not saved yet: nobody is refused
        for (rk <- Seq(sales, own, session)) assert(TapAPIController.restrictedRefusalIfExists(req(rk), "brand_new", "update", read).isEmpty, rk.label)

        // POST /tap/version/restore (update on the live tap)
        val notFound = ResponseEntity.status(HttpStatus.NOT_FOUND).body[String]("{\"error\": \"No version 3 for tap 'hr_feed'\"}")
        val vr = req(sales, uri = "/api/v1/tap/version/restore")
        val kv = EntityVersionAPIController.versionRefusal(vr, "tap", "update", Some(("HR", "someone")), notFound)
        assert(kv.isDefined, "tap version restore by a key outside scope must be refused")
        assertDenied(kv.get, "sales", "tap", Seq("update"), salesCaps, hidden, noted = vr)
        val sv = EntityVersionAPIController.versionRefusal(req(session), "tap", "update", Some(("HR", "someone")), notFound)
        assert(sv.exists(x => status(x) == 404 && x.getBody == notFound.getBody), sv.map(_.getBody))
        assert(EntityVersionAPIController.versionRefusal(req(sales), "tap", "update", Some(("Sales", "x")), notFound).isEmpty)
        assert(EntityVersionAPIController.versionRefusal(req(full), "tap", "update", Some(("HR", "x")), notFound).isEmpty)
    }

    // ------------------------------------------------------------------ named pipeline reads

    test("pipeline versions, dest-types (read and apply) and field-protection suggest outside scope are 403 for a key") {
        val hr = pg("payroll", "HR", owner = "someone")
        val orders = pg("orders", "Sales")
        val hidden = Seq("HR", "someone")
        val session = restricted("Sales" -> "edit")

        // GET /pipeline/versions, /pipeline/version, /pipeline/version/diff (read)
        val emptyList = new ResponseEntity[String]("[]", HttpStatus.OK)
        val vr = req(sales, "GET", "/api/v1/pipeline/versions")
        val kv = EntityVersionAPIController.versionRefusal(vr, "pipeline", "read", Some(("HR", "someone")), emptyList)
        assert(kv.isDefined, "version history of a pipeline outside scope must be refused for a key")
        assertDenied(kv.get, "sales", "pipeline", Seq("read"), salesCaps, hidden, noted = vr)
        // a restricted session keeps the answer the route gives it today (the empty list here)
        val sv = EntityVersionAPIController.versionRefusal(req(session, "GET"), "pipeline", "read", Some(("HR", "someone")), emptyList)
        assert(sv.exists(x => status(x) == 200 && x.getBody == "[]"), sv.map(_.getBody))
        assert(EntityVersionAPIController.versionRefusal(req(sales, "GET"), "pipeline", "read", Some(("Sales", "x")), emptyList).isEmpty)
        for (rk <- Seq(full, wildcard, editor))
            assert(EntityVersionAPIController.versionRefusal(req(rk, "GET"), "pipeline", "read", Some(("HR", "x")), emptyList).isEmpty, rk.label)
        // the decision behind it flips for a scoped key (RestrictedSessionVisibilitySpec L286)
        assert(EntityVersionAPIController.hiddenFromRestricted(req(sales), "pipeline", "read", Some(("HR", "someone"))))
        assert(!EntityVersionAPIController.hiddenFromRestricted(req(sales), "pipeline", "read", Some(("Sales", "someone"))))
        // POST /pipeline/version/restore over a pipeline outside scope stays a 403 for a key
        val rr = req(sales, uri = "/api/v1/pipeline/version/restore")
        val restore = EntityVersionAPIController.restoreGuardDecision(
            rr,
            EntityVersionAPIController.pipelineRestoreCandidate("payroll", hr, hr),
            hr,
            () => Seq(hr),
            ResponseEntity.status(HttpStatus.NOT_FOUND).body[String]("{\"error\": \"No version 2 for pipeline 'payroll'\"}")
        )
        assertDenied(left(restore), "sales", "pipeline", Seq("update"), salesCaps, hidden, noted = rr)

        // GET /pipeline/dest-types (read) and POST /pipeline/dest-types (update)
        for (action <- Seq("read", "update")) {
            val dr = req(sales, if (action == "read") "GET" else "POST", "/api/v1/pipeline/dest-types")
            val k = DestSchemaAPIController.scopeRefusal(dr, "payroll", hr, action)
            assert(k.isDefined, "dest-types " + action + " by a key outside scope must be refused")
            assertDenied(k.get, "sales", "pipeline", Seq(action), salesCaps, hidden, noted = dr)
            // restricted session: today's answer, unchanged
            val s = DestSchemaAPIController.scopeRefusal(req(session), "payroll", hr, action)
            assert(s.exists(x => status(x) == 400), action + ": " + s.map(_.getBody))
            assert(JsonParser.parseString(s.get.getBody).getAsJsonObject.get("error").getAsString == "Pipeline not found: payroll", s.get.getBody)
            // in scope and unscoped callers: not refused
            assert(DestSchemaAPIController.scopeRefusal(req(sales), "orders", orders, action).isEmpty, action + " in scope")
            for (rk <- Seq(full, wildcard, editor)) assert(DestSchemaAPIController.scopeRefusal(req(rk), "payroll", hr, action).isEmpty, rk.label)
        }

        // POST /pipeline/protect/suggest (and preset): pipeline read in scope
        val fr = req(sales, uri = "/api/v1/pipeline/protect/suggest")
        val e = intercept[CapabilityDeniedException](FieldProtectionAPIController.targetRefusal(fr, "payroll", hr, None))
        assert(e.getMessage == ScopeDenialWording.message("sales", "pipeline", "read", salesCaps), e.getMessage)
        ScopeDenialWording.assertHides(e.getMessage, hidden)
        assert(AuditLog.scopeDenials(fr).nonEmpty)
        FieldProtectionAPIController.targetRefusal(req(sales), "orders", orders, None)
        FieldProtectionAPIController.targetRefusal(req(full), "payroll", hr, None)
        // and the route answers it as a 403, not the DatrisException 400
        val suggest = methodBody(source("src/main/scala/ai/datris/api/FieldProtectionAPIController.scala"), "def suggest(")
        val denied = "case \\w+: CapabilityDeniedException".r.findFirstMatchIn(suggest).map(_.start)
        val datris = "case \\w+: DatrisException".r.findFirstMatchIn(suggest).map(_.start)
        assert(denied.isDefined && datris.forall(_ > denied.get), "suggest must map CapabilityDeniedException to its 403 before the DatrisException 400")
    }

    // ------------------------------------------------------------------ POST /pipeline/upload

    test("upload: rag-builder into another key's pipeline is 403, into its own passes; a key with no pipeline:run capability needs pipeline read in scope") {
        val ragCaps = KeyTemplatesForSpecs.capabilities("rag-builder")
        val rag = ScopeDenialFixtures.key("rb", ragCaps: _*)
        val theirs = pg("docs_theirs", "Sales", owner = "other-key", d = qdrant("ik_docs"))
        val mine = pg("docs_mine", "Sales", owner = "rb", d = qdrant("ik_docs"))
        val ur = req(rag, uri = "/api/v1/pipeline/upload")
        val k = FileUploadAPIController.uploadRefusal(ur, "docs_theirs", theirs)
        assert(k.isDefined, "rag-builder uploading into another key's pipeline must be refused")
        assertDenied(k.get, "rb", "pipeline", Seq("run"), ragCaps, Seq("other-key"), noted = ur)
        assert(FileUploadAPIController.uploadRefusal(req(rag), "docs_mine", mine).isEmpty)

        // no pipeline:run at all: pipeline read in scope decides
        val readerCaps = Seq("pipeline:read:catalog=Sales", "document:upload")
        val reader = ScopeDenialFixtures.key("uploader", readerCaps: _*)
        assert(FileUploadAPIController.uploadRefusal(req(reader), "orders", pg("orders", "Sales")).isEmpty)
        val rr = req(reader, uri = "/api/v1/pipeline/upload")
        val r = FileUploadAPIController.uploadRefusal(rr, "payroll", pg("payroll", "HR"))
        assert(r.isDefined, "a key without pipeline:run uploading into a pipeline it cannot read must be refused")
        assertDenied(r.get, "uploader", "pipeline", Seq("read"), readerCaps, Seq("HR", "someone"), noted = rr)

        // restricted session: today's 404 (refusalFor .. "run"), unrestricted callers pass
        val s = FileUploadAPIController.uploadRefusal(req(restricted("Sales" -> "edit")), "payroll", pg("payroll", "HR"))
        assert(s.exists(x => status(x) == 404 && x.getBody == PipelineAPIController.pipelineNotFound("payroll").getBody), s.map(_.getBody))
        for (rk <- Seq(full, wildcard, editor)) assert(FileUploadAPIController.uploadRefusal(req(rk), "payroll", pg("payroll", "HR")).isEmpty, rk.label)
        // the shared pure core refuses the scoped key too (RestrictedSessionVisibilitySpec L332)
        assert(PipelineAPIController.refusalFor(req(reader), "payroll", pg("payroll", "HR"), "run").exists(x => status(x) == 403))
    }

    test("upload: the pipeline-scope check and the collection-scope check both apply; neither replaces the other") {
        val caps = Seq("pipeline:read:catalog=Sales", "pipeline:run:catalog=Sales", "document:upload:collection=ik_docs")
        val k = ScopeDenialFixtures.key("combo", caps: _*)
        def up(name: String, catalog: String, coll: String): (HttpServletRequest, Option[ResponseEntity[String]]) = {
            val r = req(k, uri = "/api/v1/pipeline/upload")
            (r, FileUploadAPIController.uploadRefusal(r, name, pg(name, catalog, d = qdrant(coll))))
        }
        // both pass
        assert(up("s_docs", "Sales", "ik_docs")._2.isEmpty)
        // pipeline in scope, collection not: the existing collection refusal still applies
        val (r1, c1) = up("s_other", "Sales", "ik_other")
        assert(c1.isDefined, "the collection= check must still refuse")
        assertDenied(c1.get, "combo", "document", Seq("upload"), caps, Seq("ik_other"), noted = r1)
        // collection in scope, pipeline not: the new pipeline refusal applies
        val (r2, c2) = up("h_docs", "HR", "ik_docs")
        assert(c2.isDefined, "the pipeline scope check must refuse although the collection is granted")
        assertDenied(c2.get, "combo", "pipeline", Seq("run"), caps, Seq("HR", "someone"), noted = r2)
        // the two agree with the existing uploadScopeRefusal on its own
        assert(FileUploadAPIController.uploadScopeRefusal(req(k), pg("h_docs", "HR", d = qdrant("ik_docs"))).isEmpty)
        assert(FileUploadAPIController.uploadScopeRefusal(req(k), pg("s_other", "Sales", d = qdrant("ik_other"))).isDefined)
    }

    test("upload, OPEN QUESTION reading: when both checks would refuse, the caller sees the pipeline refusal (it runs first)") {
        val caps = Seq("pipeline:read:catalog=Sales", "pipeline:run:catalog=Sales", "document:upload:collection=ik_docs")
        val k = ScopeDenialFixtures.key("combo", caps: _*)
        val r = req(k, uri = "/api/v1/pipeline/upload")
        val both = FileUploadAPIController.uploadRefusal(r, "h_other", pg("h_other", "HR", d = qdrant("ik_other")))
        assert(both.isDefined)
        assertDenied(both.get, "combo", "pipeline", Seq("run"), caps, Seq("HR", "someone", "ik_other"), noted = r)
    }

    // ------------------------------------------------------------------ POST /job/kill

    test("kill_job on a token of a pipeline outside scope is refused for a scoped key and for a restricted session; an unknown token answers as today") {
        val hr = pg("payroll", "HR", owner = "someone")
        val orders = pg("orders", "Sales", owner = "sales")
        val read: String => PipelineConfig = Map("payroll" -> hr, "orders" -> orders).getOrElse(_, null)
        val jobs = Seq(
            JobContext("tok-hr-1", null, null, hr, null, PROCESSING, new Thread(), null),
            JobContext("tok-sales-1", null, null, orders, null, PROCESSING, new Thread(), null)
        )
        jobs.foreach(GlobalJobContext.addJobContext)
        try {
            val killer = ScopeDenialFixtures.key("sales-kill", (salesCaps :+ "job:kill"): _*)
            val session = restricted("Sales" -> "edit")
            val k = QueryAPIController.killRefusal(req(killer, uri = "/api/v1/job/kill"), "tok-hr-1", read)
            val s = QueryAPIController.killRefusal(req(session, uri = "/api/v1/job/kill"), "tok-hr-1", read)
            assert(k.isDefined, "a scoped key must not kill a run of a pipeline outside its scope")
            assert(s.isDefined, "a restricted session must not kill a run of a pipeline outside its grants")
            // D2: a token lookup answers a key as it answers the session (no new oracle)
            assert(status(k.get) == status(s.get) && k.get.getBody == s.get.getBody, k.get.getBody + " vs " + s.get.getBody)
            // OPEN QUESTION reading: the session's 404, or exactly the unknown-token answer
            val unknown = QueryAPIController.errorBody(new DatrisException("Job not found for pipeline token: tok-hr-1"))
            assert(status(k.get) == 404 || (status(k.get) == 500 && k.get.getBody == unknown), status(k.get) + " " + k.get.getBody)
            ScopeDenialWording.assertHides(k.get.getBody, Seq("payroll", "HR", "someone"))
            assert(GlobalJobContext.findJobContext("tok-hr-1").state == PROCESSING)

            // an unknown token: no refusal, the kill answers as today
            for (rk <- Seq(killer, session)) assert(QueryAPIController.killRefusal(req(rk), "tok-none-1", read).isEmpty, rk.label)
            val e = intercept[DatrisException](GlobalJobContext.killJob("tok-none-1"))
            assert(e.getMessage == "Job not found for pipeline token: tok-none-1")

            // in scope, and unscoped callers: not refused
            val salesRunner = ScopeDenialFixtures.key("sales-run", "pipeline:read:catalog=Sales", "pipeline:run:catalog=Sales", "job:kill")
            assert(QueryAPIController.killRefusal(req(salesRunner), "tok-sales-1", read).isEmpty)
            for (rk <- Seq(full, wildcard, editor)) assert(QueryAPIController.killRefusal(req(rk), "tok-hr-1", read).isEmpty, rk.label)
        } finally GlobalJobContext.synchronized {
                jobs.foreach(j => GlobalJobContext.getAll -= j)
            }
    }

    // ------------------------------------------------------------------ install-wide history / MCP activity

    test("clear-all-history and MCP activity are 403 for a scoped key and a restricted session, 200 for an unrestricted viewer") {
        val killer = ScopeDenialFixtures.key("sales-kill", (salesCaps :+ "job:kill"): _*)
        val limited = Seq(killer, sales, own, restricted("Sales" -> "own"), restricted("Sales" -> "view"))
        def capabilityDeniedShape(r: Option[ResponseEntity[String]], what: String): Unit = {
            assert(r.isDefined, what + ": expected a 403")
            assert(status(r.get) == 403, what + ": " + r.get.getBody)
            val o = JsonParser.parseString(r.get.getBody).getAsJsonObject
            assert(o.keySet().asScala.toSet == ScopeDenialWording.BodyKeys, r.get.getBody)
            assert(o.get("error").getAsString == ScopeDenialWording.Error && o.get("errorKind").getAsString == ScopeDenialWording.ErrorKind, r.get.getBody)
            ScopeDenialWording.assertHides(r.get.getBody, Nil)
        }
        for (rk <- limited) {
            capabilityDeniedShape(PipelineStatusAPIController.clearAllRefusal(req(rk, "DELETE", "/api/v1/pipeline/status")), rk.label + " clear-all")
            capabilityDeniedShape(MCPActivityAPIController.activityRefusal(req(rk, "GET", "/api/v1/mcp/activity")), rk.label + " GET activity")
            capabilityDeniedShape(MCPActivityAPIController.activityRefusal(req(rk, "DELETE", "/api/v1/mcp/activity")), rk.label + " DELETE activity")
        }
        // the session's clear-all refusal text is unchanged
        val sessionClear = PipelineStatusAPIController.clearAllRefusal(req(restricted("Sales" -> "own"), "DELETE", "/api/v1/pipeline/status"))
        assert(sessionClear.get.getBody.contains("only an unrestricted user can clear all ingestion history"), sessionClear.get.getBody)

        // unrestricted viewer (and every other unlimited caller): not refused, the route answers 200
        val unlimited = Seq(viewer, editor, full, wildcard, ScopeDenialFixtures.key("monitor", "job:read", "job:kill"))
        for (rk <- unlimited) {
            assert(PipelineStatusAPIController.clearAllRefusal(req(rk, "DELETE", "/api/v1/pipeline/status")).isEmpty, rk.label)
            assert(MCPActivityAPIController.activityRefusal(req(rk, "GET", "/api/v1/mcp/activity")).isEmpty, rk.label)
            assert(MCPActivityAPIController.activityRefusal(req(rk, "DELETE", "/api/v1/mcp/activity")).isEmpty, rk.label)
        }
        assert(PipelineStatusAPIController.clearAllRefusal(req(null, "DELETE")).isEmpty, "auth off")
        assert(MCPActivityAPIController.activityRefusal(req(null, "GET")).isEmpty, "auth off")
    }

    // ------------------------------------------------------------------ helpers

    private def source(rel: String): String = {
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get("datrisserver", rel))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
    }

    /** From `signature` to the next method of the same indentation. */
    private def methodBody(src: String, signature: String): String = {
        val i = src.indexOf(signature)
        assert(i >= 0, signature)
        val j = src.indexOf("\n    def ", i + signature.length)
        if (j < 0) src.substring(i) else src.substring(i, j)
    }
}
