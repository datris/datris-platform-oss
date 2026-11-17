package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, LineageService, PipelineAliases}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import scala.collection.JavaConverters._

/** Review follow-up to the pipeline rename (previous names): a name freed by
  * renaming a pipeline the caller cannot see is not handed to the caller,
  * neither by taking the name (save, version restore, rename: refused as a
  * hidden pipeline of that name would be) nor at the record level (a reused
  * name's stored records need every pipeline it may belong to visible). */
class PipelineAliasReuseSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def session(g: java.util.Map[String, String]): HttpServletRequest =
        req(APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g)))

    private def key(label: String, caps: String*): HttpServletRequest =
        req(ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false))

    private val bobMine = session(grants("mine" -> "edit"))
    private val bobBoth = session(grants("mine" -> "edit", "secret" -> "view"))
    private val bobOwn = session(grants("mine" -> "own"))
    private val mineKey = key("mine-builder", "pipeline:read:catalog=mine", "pipeline:create:catalog=mine", "pipeline:update:catalog=mine")
    private val full = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private val editor = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))

    private def pg(name: String, catalog: String, prev: Seq[String] = null, owner: String = "someone"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = name + "_t", usePostgres = true)),
            previousNames = if (prev == null) null else new java.util.ArrayList[String](prev.asJava)
        )

    // hidden `sales` (catalog secret) renamed to sales_v2
    private val hiddenRenamed = pg("sales_v2", "secret", prev = Seq("sales"))
    private val mineSales = pg("sales", "mine")

    private def same(a: Option[ResponseEntity[String]], b: Option[ResponseEntity[String]]): Unit = {
        assert(a.isDefined && b.isDefined, s"$a / $b")
        assert(a.get.getStatusCode == b.get.getStatusCode && a.get.getBody == b.get.getBody, s"${a.get} vs ${b.get}")
    }

    // ------------------------------------------------------------ pure rule

    test("listersOf / claimantsOf / contested") {
        val all = Seq(hiddenRenamed, mineSales)
        assert(PipelineAliases.listersOf("sales", all) == Seq(hiddenRenamed))
        assert(PipelineAliases.claimantsOf("sales", all) == Seq(mineSales, hiddenRenamed))
        assert(PipelineAliases.contested("sales", all))
        assert(!PipelineAliases.contested("sales", Seq(hiddenRenamed)), "a free old name is not contested")
        assert(!PipelineAliases.contested("sales_v2", all))
        assert(PipelineAliases.listersOf(null, all).isEmpty && !PipelineAliases.contested(null, all))
    }

    test("recordShown: a reused name needs every claimant visible; other names follow ownerOf") {
        val all = Seq(hiddenRenamed, mineSales)
        val mineOnly: PipelineConfig => Boolean = _.catalog == "mine"
        assert(!PipelineAliases.recordShown("sales", all, mineOnly))
        assert(PipelineAliases.recordShown("sales", all, _ => true))
        assert(!PipelineAliases.recordShown("sales", all, _.catalog == "secret"), "the lister alone does not see the new pipeline's records")
        // not reused: the single owner
        assert(PipelineAliases.recordShown("sales", Seq(hiddenRenamed), _.catalog == "secret"))
        assert(!PipelineAliases.recordShown("sales", Seq(hiddenRenamed), mineOnly))
        assert(PipelineAliases.recordShown("sales_v2", all, _.catalog == "secret"))
        assert(!PipelineAliases.recordShown("nobody", all, _ => true))
        assert(!PipelineAliases.recordShown(null, all, _ => true))
    }

    test("shownRecordNames drops a reused name unless every claimant is visible") {
        val all = Seq(hiddenRenamed, mineSales)
        assert(PipelineAliases.shownRecordNames(Seq(mineSales), all) == Set.empty[String])
        assert(PipelineAliases.shownRecordNames(Seq(mineSales, hiddenRenamed), all) == Set("sales", "sales_v2"))
        // recordNames (the alias resolution) is unchanged
        assert(PipelineAliases.recordNames(Seq(mineSales), all) == Set("sales"))
        val v = pg("v", "mine", prev = Seq("v_old"))
        assert(PipelineAliases.shownRecordNames(Seq(v), Seq(v, hiddenRenamed)) == Set("v", "v_old"))
    }

    // --------------------------------------------- status payload (restricted)

    private def summary(p: String): PipelineStatusSummary =
        PipelineStatusSummary("ts", 1L, 1L, p, "tok-" + p, "p", "s", "e", "1s", "success")

    private def shownSummaries(r: HttpServletRequest, names: Seq[String], configs: Seq[PipelineConfig]): Seq[String] = {
        val data = new java.util.ArrayList[PipelineStatusSummary](names.map(summary).asJava)
        PipelineStatusAPIController.restrictedViewWith(r, data, configs, Nil) match {
            case Right(l: java.util.List[_]) => l.asScala.toSeq.collect { case s: PipelineStatusSummary => s.pipeline }
            case other => fail(s"unexpected $other")
        }
    }

    test("restrictedViewWith: a visible pipeline's previous-name records are shown, a hidden one's are filtered") {
        val v = pg("v", "mine", prev = Seq("v_old"))
        val h = pg("h", "secret", prev = Seq("h_old"))
        assert(shownSummaries(bobMine, Seq("v", "v_old", "h", "h_old"), Seq(v, h)) == Seq("v", "v_old"))
    }

    test("restrictedViewWith: a name reused after a hidden pipeline's rename hides its records unless both are visible") {
        val configs = Seq(hiddenRenamed, mineSales)
        assert(shownSummaries(bobMine, Seq("sales", "sales_v2"), configs).isEmpty)
        assert(shownSummaries(bobBoth, Seq("sales", "sales_v2"), configs) == Seq("sales", "sales_v2"))
        // a token query touching the reused name reads as unknown
        val events = new java.util.ArrayList[PipelineStatus]()
        events.add(PipelineStatus(0, "t", "sales", "JobRunner", "", "tok", "f.csv", "end", "ok", "done", 1L))
        val r = PipelineStatusAPIController.restrictedViewWith(bobMine, events, configs, Nil)
        assert(r.isLeft && r.left.get.getStatusCode.value == 404, s"$r")
        // unrestricted callers: payload unchanged
        val data = new java.util.ArrayList[PipelineStatusSummary](Seq(summary("sales")).asJava)
        assert(PipelineStatusAPIController.restrictedViewWith(full, data, configs, Nil).toOption.get eq data)
    }

    test("scratch result gate, provenance owner, lineage runs and activity follow the same rule") {
        val configs = Seq(hiddenRenamed, mineSales)
        assert(PipelineStatusAPIController.resultHidden(bobMine, "sales", configs))
        assert(!PipelineStatusAPIController.resultHidden(bobBoth, "sales", configs))
        assert(!PipelineStatusAPIController.resultHidden(full, "sales", configs), "unrestricted: never hidden here")
        assert(!PipelineStatusAPIController.resultHidden(bobMine, "sales", Seq(mineSales)), "not reused: the caller's own pipeline")

        assert(ProvenanceAPIController.recordOwner(bobMine, "sales", configs) == null)
        assert(ProvenanceAPIController.recordOwner(bobBoth, "sales", configs) eq mineSales)
        val resolved = new JsonObject
        resolved.addProperty("pipeline", "sales")
        val refused = ProvenanceAPIController.provenanceRefusal(bobMine, "run-1", resolved, n => ProvenanceAPIController.recordOwner(bobMine, n, configs))
        assert(refused.exists(_.getStatusCode.value == 404))
        assert(ProvenanceAPIController.provenanceRefusal(bobBoth, "run-1", resolved, n => ProvenanceAPIController.recordOwner(bobBoth, n, configs)).isEmpty)

        val mineGraph: String => Boolean = Set("pipeline:sales")
        assert(!LineageService.runShown("sales", configs, mineGraph))
        assert(LineageService.runShown("sales", configs, Set("pipeline:sales", "pipeline:sales_v2")))
        assert(LineageService.runShown("gone", configs, Set("pipeline:gone")), "a historical node no pipeline claims is kept")
        assert(!LineageService.runShown("gone", configs, mineGraph))
        assert(!LineageService.runShown("sales", Seq(hiddenRenamed), mineGraph), "an old name of a pipeline outside the graph")

        assert(!ActivityAPIController.recordReadable("sales", configs, _.catalog == "mine"))
        assert(ActivityAPIController.recordReadable("sales", configs, _ => true))
        assert(ActivityAPIController.recordReadable("sales", Seq(mineSales), _.catalog == "mine"))
    }

    test("search writers: a reused name is dropped unless every claimant is visible") {
        def vec(name: String, catalog: String, prev: Seq[String] = null): PipelineConfig =
            pg(name, catalog, prev).copy(destination = Destination(pgvector = PGVectorConfig("chunks", "public", ChunkingConfig(), null, null, "oss/pgvector")))
        val hiddenVec = vec("docs_v2", "secret", prev = Seq("docs"))
        val mineVec = vec("docs", "mine").copy(destination =
            Destination(pgvector = PGVectorConfig("mine_chunks", "public", ChunkingConfig(), null, null, "oss/pgvector"))
        )
        val w = SearchAPIController.searchWriters(bobMine, "pgvector", "mine_chunks", None, () => Seq(hiddenVec, mineVec))
        assert(w.isRight && w.right.get.get.isEmpty, s"$w")
        val w2 = SearchAPIController.searchWriters(bobBoth, "pgvector", "mine_chunks", None, () => Seq(hiddenVec, mineVec))
        assert(w2.right.get.get.contains("docs"), s"$w2")
    }

    // ------------------------------------------------ taking the name: save

    test("hiddenAliasClaimant: only a hidden lister, only for limited-visibility callers") {
        val all = Seq(hiddenRenamed)
        assert(PipelineAPIController.hiddenAliasClaimant(bobMine, "sales", all).contains(hiddenRenamed))
        assert(PipelineAPIController.hiddenAliasClaimant(mineKey, "sales", all).contains(hiddenRenamed))
        assert(PipelineAPIController.hiddenAliasClaimant(bobBoth, "sales", all).isEmpty, "the caller can see the lister")
        assert(PipelineAPIController.hiddenAliasClaimant(full, "sales", all).isEmpty)
        assert(PipelineAPIController.hiddenAliasClaimant(editor, "sales", all).isEmpty)
        assert(PipelineAPIController.hiddenAliasClaimant(bobMine, "other", all).isEmpty)
        assert(PipelineAPIController.hiddenAliasClaimant(bobMine, null, all).isEmpty)
    }

    test("save: a restricted session taking a hidden pipeline's old name gets the hidden-live-pipeline refusal") {
        val candidate = pg("sales", "mine")
        val res = PipelineAPIController.hiddenAliasSaveRefusal(bobMine, candidate, Seq(hiddenRenamed), PipelineAPIController.SaveOverwriteActions)
        // what a hidden live pipeline named `sales` in catalog secret returns today
        val hiddenLive = pg("sales", "secret")
        same(res, PipelineAPIController.catalogForSave(bobMine, candidate, hiddenLive).left.toOption)
        assert(res.get.getStatusCode.value == 404)
        // unrelated name, visible lister, unrestricted: nothing
        assert(
            PipelineAPIController.hiddenAliasSaveRefusal(bobMine, pg("fresh", "mine"), Seq(hiddenRenamed), PipelineAPIController.SaveOverwriteActions).isEmpty
        )
        assert(PipelineAPIController.hiddenAliasSaveRefusal(bobBoth, candidate, Seq(hiddenRenamed), PipelineAPIController.SaveOverwriteActions).isEmpty)
        assert(PipelineAPIController.hiddenAliasSaveRefusal(full, candidate, Seq(hiddenRenamed), PipelineAPIController.SaveOverwriteActions).isEmpty)
        assert(PipelineAPIController.hiddenAliasSaveRefusal(editor, candidate, Seq(hiddenRenamed), PipelineAPIController.SaveOverwriteActions).isEmpty)
    }

    test("save: a scoped key taking a hidden pipeline's old name gets the same 403 as overwriting a hidden pipeline") {
        val candidate = pg("sales", "mine", owner = "mine-builder")
        val res = PipelineAPIController.hiddenAliasSaveRefusal(mineKey, candidate, Seq(hiddenRenamed), PipelineAPIController.SaveOverwriteActions)
        val hiddenLive = pg("sales", "secret")
        same(res, PipelineAPIController.overwriteRefusal(mineKey, hiddenLive, PipelineAPIController.SaveOverwriteActions))
        assert(res.get.getStatusCode.value == 403)
        val body = JsonParser.parseString(res.get.getBody).getAsJsonObject
        assert(body.get("errorKind").getAsString == "capability_denied")
    }

    test("aliasCheckUnavailable: 500, nothing saved") {
        val r = PipelineAPIController.aliasCheckUnavailable("saved")
        assert(r.getStatusCode.value == 500 && r.getBody.contains("nothing was saved"), r.getBody)
    }

    // ------------------------------------------------ restore

    test("restore of a deleted pipeline under a hidden pipeline's old name is refused as a hidden live pipeline would be") {
        val snap = pg("sales", "mine", owner = "mine-builder")
        val notFound = ResponseEntity.status(404).body[String]("{\"error\": \"No version 1 for pipeline 'sales'\"}")
        val restricted = EntityVersionAPIController.pipelineRestoreAliasRefusal(bobMine, snap, Seq(hiddenRenamed), notFound)
        assert(restricted.exists(_ eq notFound))
        val keyed = EntityVersionAPIController.pipelineRestoreAliasRefusal(mineKey, snap, Seq(hiddenRenamed), notFound)
        same(keyed, EntityVersionAPIController.pipelineRestoreScopeRefusal(mineKey, snap, pg("sales", "secret")))
        assert(EntityVersionAPIController.pipelineRestoreAliasRefusal(full, snap, Seq(hiddenRenamed), notFound).isEmpty)
        assert(EntityVersionAPIController.pipelineRestoreAliasRefusal(bobBoth, snap, Seq(hiddenRenamed), notFound).isEmpty)
        assert(EntityVersionAPIController.pipelineRestoreAliasRefusal(mineKey, pg("fresh", "mine"), Seq(hiddenRenamed), notFound).isEmpty)
    }

    // ------------------------------------------------ rename

    test("rename to a hidden pipeline's old name is the taken-name 409; the claim is read only for a free name") {
        val own = pg("mine_p", "mine")
        val r =
            PipelineAPIController.renameRefusal(bobOwn, "mine_p", "sales", own, null, Nil, ucStateExists = false, priorAttempt = false, hiddenAliasClaim = true)
        assert(r.isDefined && r.get.getStatusCode.value == 409, s"$r")
        assert(JsonParser.parseString(r.get.getBody).getAsJsonObject.get("error").getAsString == "Pipeline 'sales' already exists. Choose another name.")
        assert(PipelineAPIController.renameRefusal(bobOwn, "mine_p", "sales", own, null, Nil, ucStateExists = false, priorAttempt = false).isEmpty)
        // earlier refusals come first and never evaluate the claim
        var evaluated = false
        def claim: Boolean = { evaluated = true; true }
        val bad = PipelineAPIController.renameRefusal(
            bobOwn,
            "mine_p",
            "mine_p",
            own,
            null,
            Nil,
            ucStateExists = false,
            priorAttempt = false,
            hiddenAliasClaim = claim
        )
        assert(bad.exists(_.getStatusCode.value == 400) && !evaluated)
        // a taken name keeps its own 409 without evaluating the claim
        val taken = PipelineAPIController.renameRefusal(
            bobOwn,
            "mine_p",
            "sales",
            own,
            pg("sales", "mine"),
            Nil,
            ucStateExists = false,
            priorAttempt = false,
            hiddenAliasClaim = claim
        )
        assert(taken.exists(_.getStatusCode.value == 409) && !evaluated)
    }
}
