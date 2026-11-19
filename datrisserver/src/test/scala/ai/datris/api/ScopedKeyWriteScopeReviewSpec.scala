package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.auth.{CallerVisibility, CapabilityScopes, ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.incident.RecoveryKey
import ai.datris.model._
import ai.datris.util.APIKeyValidator
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.{HttpStatus, ResponseEntity}

/** Story: Scoped API keys (plans/stories/scoped-key-read-routes.md), Part A,
  * review round 1. Pins the additions made for the review findings:
  *   - one stored-item write rule (`CallerVisibility.storedWriteDenial`,
  *     `TapAPIController.storedTapWriteRefusal`) shared by `POST /tap` and
  *     every tap write sub-route, tap and pipeline version restore and
  *     dest-types apply (findings 1 and 2);
  *   - inert scoped capabilities make a key limited in both modes (finding 4);
  *   - `/tap/test`, the key branch of `targetPipelineRefusal`, the version
  *     scope of a deleted item and the tap restore target (finding 6). */
class ScopedKeyWriteScopeReviewSpec extends AnyFunSuite {

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private def req(rk: ResolvedKey, method: String = "POST", uri: String = "/api/v1/tap/generate"): HttpServletRequest =
        ScopeDenialFixtures.request(rk, method, uri)._1
    private def restricted(g: (String, String)*): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants(g: _*)))
    private val editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val full: ResolvedKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    private def tap(name: String, catalog: String, owner: String = "someone", target: String = null): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = catalog, createdByKeyLabel = owner)
    private def pg(name: String, catalog: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = owner)

    private val failRead: String => TapConfig = n => fail("the tap must not be read for this caller: " + n)
    private val failPipeline: String => PipelineConfig = n => fail("the pipeline must not be read for this caller: " + n)

    private def message(r: ResponseEntity[String]): String = JsonParser.parseString(r.getBody).getAsJsonObject.get("message").getAsString

    private def assertDenied(
        r: Option[ResponseEntity[String]],
        label: String,
        resource: String,
        action: String,
        caps: Seq[String],
        hidden: Seq[String]
    ): Unit = {
        assert(r.isDefined, "expected a refusal")
        assert(r.get.getStatusCode.value == 403, r.get.getBody)
        ScopeDenialWording.assertBody(r.get.getBody, ScopeDenialWording.message(label, resource, action, caps), hidden)
    }

    // ------------------------------------------------------------ finding 1

    test("finding 1: a key with scoped tap:create, unscoped tap:read and no tap:update is refused on every tap write sub-route as on POST /tap") {
        val hr = tap("hr_feed", "HR")
        val read: String => TapConfig = Map("hr_feed" -> hr).getOrElse(_, null)
        for (caps <- Seq(Seq("tap:create:catalog=Sales", "tap:read"), Seq("tap:create:owner=self", "tap:read"))) {
            val k = key("creator", caps: _*)
            val sub = Seq(
                TapAPIController.restrictedRefusalIfExists(req(k), "hr_feed", "update", read), // generate, fix, optimize, review
                TapAPIController.restrictedRefusal(req(k), "hr_feed", "update", read) // script, state, ledger, pull, migrate
            )
            sub.foreach(r => assertDenied(r, "creator", "tap", "update", caps, Seq("HR", "someone")))
            val save = TapAPIController.saveGuardDecision(req(k, uri = "/api/v1/tap"), tap("hr_feed", "HR", owner = "creator"), hr)
            assert(save.isLeft, save.toString)
            assert(message(save.left.get) == message(sub.head.get), "POST /tap and the sub-routes reach the same decision")
            assertDenied(
                EntityVersionAPIController.versionRefusal(req(k), "tap", "update", Some(("HR", "someone")), null),
                "creator",
                "tap",
                "update",
                caps,
                Nil
            )
        }
        val salesCreator = key("creator", "tap:create:catalog=Sales", "tap:read")
        assert(TapAPIController.restrictedRefusalIfExists(req(salesCreator), "s_feed", "update", _ => tap("s_feed", "Sales")).isEmpty)
        val selfCreator = key("creator", "tap:create:owner=self", "tap:read")
        assert(TapAPIController.restrictedRefusal(req(selfCreator), "o_feed", "update", _ => tap("o_feed", "HR", owner = "creator")).isEmpty)
    }

    test("finding 1: rag-builder, reporting, ops and unrestricted callers are unchanged on tap write sub-routes and read nothing") {
        val unchanged = Seq("rag-builder", "reporting", "ops", "read-only").map(t => key(t, KeyTemplatesForSpecs.capabilities(t): _*)) ++
            Seq(full, editor, key("full", "*:*"), key(RecoveryKey.Label, RecoveryKey.Capabilities: _*))
        for (rk <- unchanged; action <- Seq("update", "read")) {
            assert(TapAPIController.restrictedRefusalIfExists(req(rk), "hr_feed", action, failRead).isEmpty, rk.label + " " + action)
            assert(TapAPIController.restrictedRefusal(req(rk), "hr_feed", action, failRead).isEmpty, rk.label + " " + action)
        }
        // rag-builder saving over another key's tap: unchanged (unscoped tap:create and tap:read)
        val rag = key("rb", KeyTemplatesForSpecs.capabilities("rag-builder"): _*)
        assert(TapAPIController.saveGuardDecision(req(rag), tap("hr_feed", "HR"), tap("hr_feed", "HR")).isRight)
        assert(!TapAPIController.saveGuardNeeded(req(rag)), "rag-builder reads nothing before a tap save")
    }

    // ------------------------------------------------------------ finding 2

    test("finding 2: a key that may write but cannot read a stored tap or pipeline is refused on read, everywhere it writes") {
        val tapCaps = Seq("tap:update", "tap:read:catalog=Sales")
        val tk = key("upd", tapCaps: _*)
        val hr = tap("hr_feed", "HR")
        val read: String => TapConfig = Map("hr_feed" -> hr).getOrElse(_, null)
        val hidden = Seq("HR", "someone")
        assertDenied(TapAPIController.restrictedRefusal(req(tk), "hr_feed", "update", read), "upd", "tap", "read", tapCaps, hidden)
        assertDenied(TapAPIController.restrictedRefusalIfExists(req(tk), "hr_feed", "update", read), "upd", "tap", "read", tapCaps, hidden)
        val save = TapAPIController.saveGuardDecision(req(tk), tap("hr_feed", "HR"), hr)
        assertDenied(save.left.toOption, "upd", "tap", "read", tapCaps, hidden)
        assertDenied(EntityVersionAPIController.versionRefusal(req(tk), "tap", "update", Some(("HR", "someone")), null), "upd", "tap", "read", tapCaps, hidden)
        // in scope: allowed
        assert(TapAPIController.restrictedRefusal(req(tk), "s_feed", "update", _ => tap("s_feed", "Sales")).isEmpty)

        val pCaps = Seq("pipeline:update", "pipeline:read:catalog=Sales")
        val pk = key("pupd", pCaps: _*)
        val payroll = pg("payroll", "HR")
        assertDenied(DestSchemaAPIController.scopeRefusal(req(pk), "payroll", payroll, "update"), "pupd", "pipeline", "read", pCaps, hidden)
        assert(DestSchemaAPIController.scopeRefusal(req(pk), "orders", pg("orders", "Sales"), "update").isEmpty)
        assertDenied(
            EntityVersionAPIController.versionRefusal(req(pk), "pipeline", "update", Some(("HR", "someone")), null),
            "pupd",
            "pipeline",
            "read",
            pCaps,
            hidden
        )
        // pipeline version restore (earlier story's guard): live and deleted
        assertDenied(EntityVersionAPIController.pipelineRestoreScopeRefusal(req(pk), payroll, payroll), "pupd", "pipeline", "read", pCaps, hidden)
        assertDenied(EntityVersionAPIController.pipelineRestoreScopeRefusal(req(pk), payroll, null), "pupd", "pipeline", "read", pCaps, hidden)
        val guard = EntityVersionAPIController.restoreGuardDecision(
            req(pk),
            payroll,
            payroll,
            () => Seq(payroll),
            ResponseEntity.status(HttpStatus.NOT_FOUND).body[String]("{}")
        )
        assertDenied(guard.left.toOption, "pupd", "pipeline", "read", pCaps, hidden)
        assert(EntityVersionAPIController.pipelineRestoreScopeRefusal(req(pk), pg("orders", "Sales"), pg("orders", "Sales")).isEmpty)
        // unscoped callers: unchanged
        for (rk <- Seq(full, editor)) {
            assert(DestSchemaAPIController.scopeRefusal(req(rk), "payroll", payroll, "update").isEmpty)
            assert(EntityVersionAPIController.pipelineRestoreScopeRefusal(req(rk), payroll, payroll).isEmpty)
        }
    }

    // ------------------------------------------------------------ finding 4

    test("finding 4: a key whose scoped pipeline capability is inert is limited in both modes; the unchanged callers stay unlimited") {
        val raw = Seq("job:read", "pipeline:read:catalog=Sales,collection=docs")
        val (applied, inert) = CapabilityScopes.partition(Capability.parseList(raw))
        assert(inert.map(_.raw) == Seq("pipeline:read:catalog=Sales,collection=docs"))
        val ik = ResolvedKey(None, "ik", applied, isLegacyFullAccess = false, inertCapabilities = inert)
        def withMode[T](allow: Boolean)(f: => T): T = {
            val prior = sys.props.get(CapabilityScopes.InertScopesProperty)
            if (allow) sys.props(CapabilityScopes.InertScopesProperty) = CapabilityScopes.InertScopesAllow
            else sys.props -= CapabilityScopes.InertScopesProperty
            try f
            finally prior match {
                    case Some(v) => sys.props(CapabilityScopes.InertScopesProperty) = v
                    case None => sys.props -= CapabilityScopes.InertScopesProperty
                }
        }
        for (allow <- Seq(false, true)) withMode(allow) {
            assert(CapabilityScopes.refuseInert == !allow)
            assert(CallerVisibility.limitedPipelines(ik), "allow=" + allow)
            assert(CallerVisibility.limitedTaps(ik), "allow=" + allow)
            assert(MCPActivityAPIController.activityRefusal(req(ik, "GET", "/api/v1/mcp/activity")).isDefined, "allow=" + allow)
            // the story's unchanged callers
            val unchanged = Seq("read-only", "reporting", "ops", "rag-builder", "full-access").map(t => key(t, KeyTemplatesForSpecs.capabilities(t): _*)) ++
                Seq(key(RecoveryKey.Label, RecoveryKey.Capabilities: _*), key("monitor", "job:read"), full, editor)
            unchanged.foreach(rk => assert(!CallerVisibility.limited(rk), rk.label + " allow=" + allow))
        }
    }

    // ------------------------------------------------------------ finding 6: /tap/test

    test("/tap/test: a stored tap outside scope is refused (session 404, key 403); the secret owner is checked as the story words it") {
        val hr = tap("hr_feed", "HR")
        val read: String => TapConfig = Map("hr_feed" -> hr).getOrElse(_, null)
        val noSecret: String => Option[Option[String]] = _ => None
        val body = (n: String, cat: String, secret: String) => TapConfig(name = n, description = "d", targetPipeline = null, catalog = cat, secretName = secret)

        // restricted session (D4): hidden stored tap -> the tap's 404; granted -> passes
        val session = restricted("Sales" -> "edit")
        val s = TapAPIController.testRefusal(req(session), body("hr_feed", "Sales", null), read, noSecret)
        assert(s.exists(r => r.getStatusCode.value == 404 && r.getBody == TapAPIController.tapNotFound("hr_feed").getBody), s.map(_.getBody))
        assert(TapAPIController.testRefusal(req(session), body("new_feed", "Sales", null), read, noSecret).isEmpty)
        // session, secret: the tap as tested must be in its grants
        assert(TapAPIController.testRefusal(req(session), body("new_feed", "HR", "creds"), read, _ => Some(Some("x"))).exists(_.getStatusCode.value == 404))

        // catalog-scoped key: stored tap outside scope -> 403 on tap:read
        val caps = Seq("tap:read:catalog=Sales", "tap:run:catalog=Sales")
        val k = key("tester", caps: _*)
        val kr = req(k, uri = "/api/v1/tap/test")
        assertDenied(TapAPIController.testRefusal(kr, body("hr_feed", "Sales", null), read, noSecret), "tester", "tap", "read", caps, Seq("HR", "someone"))
        assert(AuditLog.scopeDenials(kr).nonEmpty)
        assert(TapAPIController.testRefusal(req(k), body("new_feed", "Sales", "creds"), read, _ => Some(Some("anyone"))).isEmpty)

        // owner=self key: a secret another key created is refused; its own, or no such secret, passes
        val ownCaps = Seq("tap:read:owner=self", "tap:run:owner=self")
        val own = key("own", ownCaps: _*)
        assertDenied(
            TapAPIController.testRefusal(req(own), body("new_feed", null, "creds"), read, _ => Some(Some("other-key"))),
            "own",
            "tap",
            "read",
            ownCaps,
            Seq("other-key")
        )
        assert(TapAPIController.testRefusal(req(own), body("new_feed", null, "creds"), read, _ => Some(Some("own"))).isEmpty)
        assert(TapAPIController.testRefusal(req(own), body("new_feed", null, "creds"), read, noSecret).isEmpty)

        // a failed secret-store read refuses (the exception reaches the handler's 500), never passes
        intercept[IllegalStateException](
            TapAPIController.testRefusal(req(own), body("new_feed", null, "creds"), read, _ => throw new IllegalStateException("vault down"))
        )

        // unchanged callers: nothing read, no refusal
        val unchanged =
            Seq(full, editor, key("rb", KeyTemplatesForSpecs.capabilities("rag-builder"): _*), key("ops", KeyTemplatesForSpecs.capabilities("ops"): _*))
        unchanged.foreach(rk =>
            assert(TapAPIController.testRefusal(req(rk), body("hr_feed", "HR", "creds"), failRead, _ => fail("secret read")).isEmpty, rk.label)
        )
        assert(TapAPIController.testRefusal(null, body("hr_feed", "HR", "creds"), failRead, _ => fail("secret read")).isEmpty, "no request")
    }

    // ------------------------------------------------------------ finding 6: targetPipelineRefusal key branch

    test("targetPipelineRefusal, key branch as it is now: pipeline-limited keys holding a pipeline capability are checked on the stored target") {
        val pipelines: String => PipelineConfig =
            Map("hr_payroll" -> pg("hr_payroll", "HR"), "orders" -> pg("orders", "Sales", owner = "sales")).getOrElse(_, null)
        val readerCaps = Seq("pipeline:read:catalog=Sales", "tap:create:catalog=Sales", "tap:read:catalog=Sales")
        val reader = key("sales", readerCaps: _*)
        // no pipeline:run held: read in scope decides
        assertDenied(
            TapAPIController.targetPipelineRefusal(req(reader), tap("t", "Sales", target = "hr_payroll"), pipelines),
            "sales",
            "pipeline",
            "read",
            readerCaps,
            Seq("HR")
        )
        assert(TapAPIController.targetPipelineRefusal(req(reader), tap("t", "Sales", target = "orders"), pipelines).isEmpty)
        // a pipeline:run capability held: run in scope decides
        val runCaps = Seq("pipeline:read:catalog=Sales", "pipeline:run:owner=self", "tap:read:catalog=Sales")
        val runner = key("sales", runCaps: _*)
        assertDenied(
            TapAPIController.targetPipelineRefusal(req(runner), tap("t", "Sales", target = "hr_payroll"), pipelines),
            "sales",
            "pipeline",
            "run",
            runCaps,
            Seq("HR")
        )
        assert(TapAPIController.targetPipelineRefusal(req(runner), tap("t", "Sales", target = "orders"), pipelines).isEmpty)
        // no target, a target not stored yet: as today
        assert(TapAPIController.targetPipelineRefusal(req(reader), tap("t", "Sales"), failPipeline).isEmpty)
        assert(TapAPIController.targetPipelineRefusal(req(reader), tap("t", "Sales", target = "not_yet"), pipelines).isEmpty)
        // exempt today (finding 3 is with the owner): a key holding only tap capabilities, and keys not limited for pipelines
        val tapOnly = key("own", "tap:read:owner=self", "tap:create:owner=self", "tap:update:owner=self", "tap:run:owner=self", "document:upload")
        val rag = key("rb", KeyTemplatesForSpecs.capabilities("rag-builder"): _*)
        for (rk <- Seq(tapOnly, rag, full, editor, key("ops", KeyTemplatesForSpecs.capabilities("ops"): _*)))
            assert(TapAPIController.targetPipelineRefusal(req(rk), tap("t", "Sales", target = "hr_payroll"), failPipeline).isEmpty, rk.label)
        // no request (scheduler, enable job, tap runs): never refused
        assert(TapAPIController.targetPipelineRefusal(null, tap("t", "Sales", target = "hr_payroll"), failPipeline).isEmpty)
    }

    // ------------------------------------------------------------ finding 6: version scope of a deleted item

    test("version routes: a key's deleted item is scoped from its newest readable version; nothing establishing scope reads as never existed") {
        val salesCaps = Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "tap:update:catalog=Sales")
        val k = key("sales", salesCaps: _*)
        def v(n: Int, cfg: String) = EntityVersion("k" + n, "x", n, cfg)
        val hrNewest = Seq(v(1, """{"name":"x","catalog":"Sales"}"""), v(2, """{"name":"x","catalog":"HR","createdByKeyLabel":"someone"}"""))
        val salesNewest = Seq(v(1, """{"name":"x","catalog":"HR"}"""), v(2, """{"name":"x","catalog":"Sales"}"""))
        val never = ResponseEntity.ok().body[String]("[]")
        for (resource <- Seq("pipeline", "tap")) {
            val r = EntityVersionAPIController.versionScopeRefusalWith(req(k, "GET"), resource, "read", () => None, () => hrNewest, never)
            assertDenied(r, "sales", resource, "read", salesCaps, Seq("HR", "someone"))
            assert(EntityVersionAPIController.versionScopeRefusalWith(req(k, "GET"), resource, "read", () => None, () => salesNewest, never).isEmpty)
            // nothing establishes scope: the never-existed answer
            for (vs <- Seq(Nil, Seq(v(1, "not json {")))) {
                val n = EntityVersionAPIController.versionScopeRefusalWith(req(k, "GET"), resource, "read", () => None, () => vs, never)
                assert(n.exists(_.getBody == "[]"), resource + " " + vs)
            }
            // a live item decides; versions are not read
            assert(EntityVersionAPIController.versionScopeRefusalWith(
                req(k, "GET"),
                resource,
                "read",
                () => Some(("Sales", "x")),
                () => fail("versions"),
                never
            ).isEmpty)
            // restricted session with no live item: its hidden answer, versions not read
            val s = EntityVersionAPIController.versionScopeRefusalWith(
                req(restricted("Sales" -> "edit"), "GET"),
                resource,
                "read",
                () => None,
                () => fail("versions"),
                never
            )
            assert(s.exists(_.getBody == "[]"))
            // unscoped callers read nothing
            for (rk <- Seq(full, editor))
                assert(
                    EntityVersionAPIController.versionScopeRefusalWith(req(rk, "GET"), resource, "read", () => fail("live"), () => fail("versions"), never).isEmpty
                )
        }
    }

    // ------------------------------------------------------------ finding 6: tap restore target

    test("tap version restore: the tap as it would be restored (snapshot catalog, live owner) must be in a key's scope") {
        val caps = Seq("tap:read:catalog=Sales", "tap:update:catalog=Sales")
        val k = key("sales", caps: _*)
        val live = tap("s_feed", "Sales", owner = "sales")
        assertDenied(EntityVersionAPIController.tapRestoreRefusal(req(k), tap("s_feed", "HR"), live), "sales", "tap", "update", caps, Seq("HR"))
        assert(EntityVersionAPIController.tapRestoreRefusal(req(k), tap("s_feed", "Sales", owner = "old"), live).isEmpty)
        // deleted tap: the snapshot's own scope
        assertDenied(EntityVersionAPIController.tapRestoreRefusal(req(k), tap("s_feed", "HR"), null), "sales", "tap", "update", caps, Seq("HR"))
        assert(EntityVersionAPIController.tapRestoreRefusal(req(k), tap("s_feed", "Sales"), null).isEmpty)
        // owner=self: the live owner is kept, so its own tap restores in any catalog
        val own = key("own", "tap:read:owner=self", "tap:update:owner=self")
        assert(EntityVersionAPIController.tapRestoreRefusal(req(own), tap("o", "HR", owner = "x"), tap("o", "Sales", owner = "own")).isEmpty)
        for (rk <- Seq(full, editor, restricted("Sales" -> "edit")))
            assert(EntityVersionAPIController.tapRestoreRefusal(req(rk), tap("s_feed", "HR"), live).isEmpty, rk.label)
    }
}
