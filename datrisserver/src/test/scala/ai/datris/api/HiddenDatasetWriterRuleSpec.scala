package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, SharedDatasets}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Story: Hidden dataset writes
  * (plans/stories/restricted-hidden-dataset-writes.md), Acceptance, HiddenDatasetWriterRuleSpec.
  *
  * The save, version-restore and delete entry points read Mongo and the
  * stores, so this spec pins the companion seams they run on (mock-request
  * fixtures as in AuthorityRefusalVisibilitySpec). Assumed:
  * {{{
  * object PipelineAPIController {
  *   def datasetEnv(): SharedDatasets.Env
  *   // 400 {"error": SharedDatasets.refusalMessage(<candidate's LineageService.datasets(...).name>)}
  *   def hiddenWriterRefusal(request: HttpServletRequest, candidate: PipelineConfig, stored: PipelineConfig,
  *                           others: Seq[PipelineConfig], env: SharedDatasets.Env): Option[ResponseEntity[String]]
  *   def limitedVisibility(request: HttpServletRequest): Boolean
  *   // D6: Some(500 "... nothing was saved ...") for a limited caller whose candidate adds a key
  *   def hiddenWriterCheckUnavailable(request: HttpServletRequest, candidate: PipelineConfig, stored: PipelineConfig,
  *                                    readFailed: Boolean, env: SharedDatasets.Env): Option[ResponseEntity[String]]
  *   def othersForDelete(config: PipelineConfig, deleteData: Boolean, env: SharedDatasets.Env,
  *                       read: () => Seq[PipelineConfig]): Seq[PipelineConfig]
  *   def structuredDeleteWarnings(request: HttpServletRequest, config: PipelineConfig,
  *                                others: Seq[PipelineConfig], env: SharedDatasets.Env): Seq[String]
  * }
  * object EntityVersionAPIController {
  *   // the 4-argument form VectorContainerRuleSpec calls stays
  *   def pipelineRestoreRefusal(request: HttpServletRequest, snap: PipelineConfig, live: PipelineConfig,
  *                              others: Seq[PipelineConfig], env: SharedDatasets.Env): Option[ResponseEntity[String]]
  * }
  * }}}
  */
class HiddenDatasetWriterRuleSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey, method: String = "POST", path: String = "/api/v1/pipeline"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private val bob = req(
        APIKeyValidator.resolveFromSession(
            User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
        )
    )
    private val editor = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private val full = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private val noKey = req(null)
    private val salesKey = req(
        ResolvedKey(
            None,
            "sales-builder",
            Capability.parseList(Seq(
                "pipeline:read:catalog=Sales",
                "pipeline:create:catalog=Sales",
                "pipeline:update:catalog=Sales",
                "pipeline:delete:catalog=Sales"
            )),
            isLegacyFullAccess = false
        )
    )
    private val ownKey = req(
        ResolvedKey(
            None,
            "builder",
            Capability.parseList(Seq("pipeline:read:owner=self", "pipeline:create:owner=self", "pipeline:update:owner=self")),
            isLegacyFullAccess = false
        )
    )

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def pg(name: String, catalog: String, table: String, authoritative: java.lang.Boolean = null, owner: String = "someone"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            authoritative = authoritative,
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
        )

    private def pgv(name: String, catalog: String, table: String, authoritative: java.lang.Boolean = null): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            authoritative = authoritative,
            destination = Destination(pgvector = PGVectorConfig(table, "public", ChunkingConfig(), null, null, null))
        )

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def assertStatus(r: Option[ResponseEntity[String]], status: Int): String = {
        assert(r.isDefined, "expected a refusal")
        assert(r.get.getStatusCode.value == status, r.get.getBody)
        errorOf(r.get)
    }

    private def refuse(
        r: HttpServletRequest,
        c: PipelineConfig,
        stored: PipelineConfig,
        others: Seq[PipelineConfig],
        e: SharedDatasets.Env = env
    ): Option[ResponseEntity[String]] =
        PipelineAPIController.hiddenWriterRefusal(r, c, stored, others, e)

    private val mktTbl = "e2e_mkt_tbl"
    private val sentence = "The destination postgres:datris.public.e2e_mkt_tbl is already in use by another pipeline. Choose another destination."
    private val hiddenOthers = Seq(pg("mkt_p", "Marketing", mktTbl))

    test("a restricted session and a catalog-scoped key are refused with 400 for a dataset a hidden pipeline writes") {
        for (r <- Seq(bob, salesKey, ownKey)) {
            assert(PipelineAPIController.limitedVisibility(r))
            val err = assertStatus(refuse(r, pg("bob_f", "Sales", mktTbl, java.lang.Boolean.FALSE, owner = "builder"), null, hiddenOthers), 400)
            assert(err == sentence, err)
        }
        assert(SharedDatasets.refusalMessage("postgres:datris.public.e2e_mkt_tbl") == sentence)
        // a free table is not refused
        assert(refuse(bob, pg("bob_f", "Sales", "bob_own_tbl", java.lang.Boolean.FALSE), null, hiddenOthers).isEmpty)
    }

    test("the refusal body is identical for authoritative true, false and omitted, and contains no pipeline or catalog name and not the word authoritative") {
        for (r <- Seq(bob, salesKey)) {
            val bodies: Seq[String] = Seq(
                pg("bob_f", "Sales", mktTbl, java.lang.Boolean.FALSE),
                pg("bob_t", "Sales", mktTbl, java.lang.Boolean.TRUE),
                pg("bob_d", "Sales", mktTbl)
            ).map { c =>
                val res = refuse(r, c, null, hiddenOthers)
                assertStatus(res, 400)
                res.get.getBody
            }
            assert(bodies.distinct.size == 1, bodies)
            val body = bodies.head
            for (bad <- Seq("mkt_p", "Marketing", "authoritative", "bob_", "Sales"))
                assert(!body.contains(bad), bad + " in " + body)
        }
    }

    test("unrestricted sessions and full keys get no refusal, and sharing with authoritative false passes the authority check as before") {
        for (r <- Seq(editor, full, noKey)) {
            assert(!PipelineAPIController.limitedVisibility(r))
            for (auth <- Seq(java.lang.Boolean.FALSE, java.lang.Boolean.TRUE, null))
                assert(refuse(r, pg("share", "Sales", mktTbl, auth), null, hiddenOthers).isEmpty)
            assert(PipelineAPIController.authorityRefusal(r, pg("share", "Sales", mktTbl, java.lang.Boolean.FALSE), hiddenOthers).isEmpty)
            // default authority: today's text, naming the other pipeline
            val err = assertStatus(PipelineAPIController.authorityRefusal(r, pg("share", "Sales", mktTbl), hiddenOthers), 400)
            assert(err.contains("mkt_p"), err)
            // a pgvector on the same table is not refused for them either (D4: no all-user save refusal)
            assert(refuse(r, pgv("share_v", "Sales", mktTbl), null, hiddenOthers).isEmpty)
        }
    }

    test("a limited caller sharing a dataset with a visible pipeline is not refused") {
        val others = Seq(pg("sales_a", "Sales", "e2e_sales_tbl"), pg("mkt_p", "Marketing", mktTbl))
        for (r <- Seq(bob, salesKey)) {
            assert(refuse(r, pg("sales_b", "Sales", "e2e_sales_tbl", java.lang.Boolean.FALSE), null, others).isEmpty)
            assert(PipelineAPIController.authorityRefusal(r, pg("sales_b", "Sales", "e2e_sales_tbl", java.lang.Boolean.FALSE), others).isEmpty)
            // default authority: not this rule; the visible authority text names sales_a as today
            assert(refuse(r, pg("sales_c", "Sales", "e2e_sales_tbl"), null, others).isEmpty)
            val err = assertStatus(PipelineAPIController.authorityRefusal(r, pg("sales_c", "Sales", "e2e_sales_tbl"), others), 400)
            assert(err.contains("sales_a"), err)
        }
        // one visible and one hidden writer: refused, name-free
        val mixed = Seq(pg("sales_a", "Sales", mktTbl), pg("mkt_p", "Marketing", mktTbl))
        val res = refuse(bob, pg("bob_f", "Sales", mktTbl, java.lang.Boolean.FALSE), null, mixed)
        assert(assertStatus(res, 400) == sentence)
        assert(!res.get.getBody.contains("sales_a"), res.get.getBody)
    }

    test("a grandfathered sharer re-saves, and is refused when it moves onto another hidden dataset") {
        val stored = pg("share_erin", "Sales", mktTbl, java.lang.Boolean.FALSE)
        val others = Seq(pg("mkt_p", "Marketing", mktTbl), stored, pg("hr_p", "HR", "hr_tbl"))
        val retagged = stored.copy(tags = java.util.Arrays.asList("changed"))
        for (r <- Seq(bob, salesKey)) {
            assert(refuse(r, retagged, stored, others).isEmpty)
            val moved = pg("share_erin", "Sales", "hr_tbl", java.lang.Boolean.FALSE)
            val res = refuse(r, moved, stored, others)
            val err = assertStatus(res, 400)
            assert(err == "The destination postgres:datris.public.hr_tbl is already in use by another pipeline. Choose another destination.", err)
            assert(!res.get.getBody.contains("hr_p") && !res.get.getBody.contains("HR"), res.get.getBody)
        }
    }

    test("a pgvector destination on a hidden pipeline's postgres table is refused, and the reverse") {
        val res = refuse(bob, pgv("bob_v", "Sales", mktTbl, java.lang.Boolean.FALSE), null, hiddenOthers)
        val err = assertStatus(res, 400)
        assert(err == "The destination pgvector:public.e2e_mkt_tbl is already in use by another pipeline. Choose another destination.", err)
        assert(!res.get.getBody.contains("mkt_p") && !res.get.getBody.contains("Marketing"))
        // unknown pgvector database: still one object
        assertStatus(refuse(bob, pgv("bob_v", "Sales", mktTbl), null, hiddenOthers, env.copy(pgvectorDatabase = None)), 400)
        // a different pgvector database: distinct
        assert(refuse(bob, pgv("bob_v", "Sales", mktTbl), null, hiddenOthers, env.copy(pgvectorDatabase = Some("vectors"))).isEmpty)

        // reverse: a postgres table on a hidden pipeline's pgvector table
        val hiddenVec = Seq(pgv("mkt_vec", "Marketing", "v_t"))
        val rev = refuse(salesKey, pg("bob_t", "Sales", "v_t", java.lang.Boolean.FALSE), null, hiddenVec)
        assert(assertStatus(rev, 400) == "The destination postgres:datris.public.v_t is already in use by another pipeline. Choose another destination.")
        assert(!rev.get.getBody.contains("mkt_vec"))
        assertStatus(refuse(bob, pg("bob_t", "Sales", "v_t"), null, hiddenVec), 400)
    }

    test("version restore onto a hidden pipeline's dataset is refused; a restore that keeps the live dataset is not") {
        val bobRestore = req(
            APIKeyValidator.resolveFromSession(
                User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
            ),
            path = "/api/v1/pipeline/version/restore"
        )
        val live = pg("bob_p", "Sales", "bob_tbl")
        val others = Seq(pg("mkt_p", "Marketing", mktTbl), live)
        val snapOnto = pg("bob_p", "Sales", mktTbl)

        val res = EntityVersionAPIController.pipelineRestoreRefusal(bobRestore, snapOnto, live, others, env)
        assert(assertStatus(res, 400) == sentence)
        assert(!res.get.getBody.contains("mkt_p"))
        // live pipeline deleted: the snapshot is a new pipeline on that table
        assertStatus(EntityVersionAPIController.pipelineRestoreRefusal(bobRestore, snapOnto, null, Seq(pg("mkt_p", "Marketing", mktTbl)), env), 400)
        // catalog-scoped key too
        assertStatus(EntityVersionAPIController.pipelineRestoreRefusal(salesKey, snapOnto, live, others, env), 400)

        // keeps the live dataset (a grandfathered share with the hidden pipeline): allowed
        val sharedLive = pg("bob_p", "Sales", mktTbl, java.lang.Boolean.FALSE)
        val keep = sharedLive.copy(tags = java.util.Arrays.asList("v1"))
        assert(EntityVersionAPIController.pipelineRestoreRefusal(bobRestore, keep, sharedLive, Seq(pg("mkt_p", "Marketing", mktTbl), sharedLive), env).isEmpty)
        assert(EntityVersionAPIController.pipelineRestoreRefusal(bobRestore, pg("bob_p", "Sales", "bob_tbl"), live, others, env).isEmpty)
        // unrestricted caller: not this rule
        assert(EntityVersionAPIController.pipelineRestoreRefusal(full, snapOnto, live, others, env).isEmpty)
    }

    test("an unreadable config list refuses a limited caller's new dataset with 500 and leaves everyone else fail-open") {
        val fresh = pg("bob_f", "Sales", mktTbl)
        for (r <- Seq(bob, salesKey, ownKey)) {
            val err = assertStatus(PipelineAPIController.hiddenWriterCheckUnavailable(r, fresh, null, true, env), 500)
            assert(err.contains("nothing was saved"), err)
            // changed dataset
            assertStatus(PipelineAPIController.hiddenWriterCheckUnavailable(r, fresh, pg("bob_f", "Sales", "old_tbl"), true, env), 500)
            // unchanged dataset, no shared-dataset key, or a successful read: no refusal
            assert(PipelineAPIController.hiddenWriterCheckUnavailable(r, fresh, pg("bob_f", "Sales", mktTbl), true, env).isEmpty)
            assert(
                PipelineAPIController
                    .hiddenWriterCheckUnavailable(r, PipelineConfig(name = "plain", catalog = "Sales", destination = Destination()), null, true, env)
                    .isEmpty
            )
            assert(PipelineAPIController.hiddenWriterCheckUnavailable(r, fresh, null, false, env).isEmpty)
        }
        for (r <- Seq(editor, full, noKey))
            assert(PipelineAPIController.hiddenWriterCheckUnavailable(r, fresh, null, true, env).isEmpty)
        // othersForSave's signature and fail-open result for a structured save are unchanged
        val failing: () => Seq[PipelineConfig] = () => throw new RuntimeException("config db down")
        assert(PipelineAPIController.othersForSave(fresh, null, failing) == Right(Nil))
    }

    test("the delete warning names a visible sharer and omits a hidden one") {
        val config = pg("sales_a", "Sales", mktTbl)
        val others = Seq(config, pg("mkt_p", "Marketing", mktTbl), pg("lonely", "HR", "lonely_tbl"))
        val bobDel = req(
            APIKeyValidator.resolveFromSession(
                User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "own"))
            ),
            method = "DELETE"
        )

        val hidden = PipelineAPIController.structuredDeleteWarnings(bobDel, config, others, env)
        assert(hidden == Seq("Kept postgres table datris.public.e2e_mkt_tbl: another pipeline also writes to it."), hidden)
        assert(SharedDatasets.keptOnDeleteMessage("postgres table datris.public.e2e_mkt_tbl", None) == hidden.head)

        val scoped = PipelineAPIController.structuredDeleteWarnings(salesKey, config, others, env)
        assert(scoped.size == 1 && !scoped.head.contains("mkt_p") && !scoped.head.contains("Marketing"), scoped)

        val admin = PipelineAPIController.structuredDeleteWarnings(
            req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true), method = "DELETE"),
            config,
            others,
            env
        )
        assert(admin.size == 1 && admin.head.contains("mkt_p"), admin)

        // visible sharer is named for the limited caller
        val visibleOthers = Seq(config, pg("sales_b", "Sales", mktTbl))
        val named = PipelineAPIController.structuredDeleteWarnings(bobDel, config, visibleOthers, env)
        assert(named.size == 1 && named.head.contains("sales_b") && named.head.contains("datris.public.e2e_mkt_tbl"), named)
        val kept = SharedDatasets.keptOnDeleteMessage("postgres table x", Some(Seq("sales_b")))
        assert(kept.startsWith("Kept postgres table x") && kept.contains("sales_b"), kept)

        // cross-kind sharer is reported too
        val crossKind = PipelineAPIController.structuredDeleteWarnings(full, pgv("vec", "Sales", mktTbl), Seq(config), env)
        assert(crossKind.size == 1 && crossKind.head.contains("sales_a"), crossKind)

        // unshared: no warning
        assert(PipelineAPIController.structuredDeleteWarnings(full, pg("lonely", "HR", "lonely_tbl"), others, env).isEmpty)
    }

    test("othersForDelete reads configs for a structured destination and not for a destination with no shared-dataset key") {
        val boom: () => Seq[PipelineConfig] = () => throw new AssertionError("configs must not be read")
        val failing: () => Seq[PipelineConfig] = () => throw new RuntimeException("config db down")
        val other = pg("mkt_p", "Marketing", mktTbl)

        assert(PipelineAPIController.othersForDelete(pg("a", "Sales", mktTbl), deleteData = true, env, () => Seq(other, null)) == Seq(other))
        val mongo = PipelineConfig(name = "m", catalog = "Sales", destination = Destination(database = Database(table = "events", useMongoDB = true)))
        assert(PipelineAPIController.othersForDelete(mongo, deleteData = true, env, () => Seq(other)) == Seq(other))
        // a vector destination still reads (one read serves both rules)
        val qd = PipelineConfig(name = "q", catalog = "Sales", destination = Destination(qdrant = QdrantConfig("docs", ChunkingConfig(), null, null, null)))
        assert(PipelineAPIController.othersForDelete(qd, deleteData = true, env, () => Seq(other)) == Seq(other))

        // no shared-dataset key, or no data deleted: no read
        val plain = PipelineConfig(name = "plain", catalog = "Sales", destination = Destination())
        val rest = PipelineConfig(name = "rest", catalog = "Sales", destination = Destination(restEndpoint = RestEndpoint("http://localhost:9/x")))
        assert(PipelineAPIController.othersForDelete(plain, deleteData = true, env, boom).isEmpty)
        assert(PipelineAPIController.othersForDelete(rest, deleteData = true, env, boom).isEmpty)
        assert(PipelineAPIController.othersForDelete(pg("a", "Sales", mktTbl), deleteData = false, env, boom).isEmpty)

        // a failed read fails the delete before anything is destroyed
        val e = intercept[IllegalStateException](PipelineAPIController.othersForDelete(pg("a", "Sales", mktTbl), deleteData = true, env, failing))
        assert(e.getMessage.contains("nothing was deleted"), e.getMessage)
    }
}
