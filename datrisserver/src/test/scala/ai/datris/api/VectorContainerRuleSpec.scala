package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, VectorSearchRegistry}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

/** Story: One vector container per pipeline
  * (plans/stories/vector-container-one-pipeline.md), Acceptance, VectorContainerRuleSpec.
  *
  * The save, version-restore and delete entry points read and write Mongo and
  * the stores, so this spec pins the pure seams they run on the loaded configs
  * (mock-request fixtures as in RestrictedSessionVisibilitySpec). Assumed:
  * {{{
  * object PipelineAPIController {
  *   // POST /pipeline (savePipelineWithBody, right after authorityConflict):
  *   // Some(400 {"error": ...}) built from VectorContainers.saveConflicts; other
  *   // pipelines named only when visiblePipelines(request, colliding) keeps all of them (D3).
  *   def vectorContainerRefusal(request: HttpServletRequest, candidate: PipelineConfig,
  *                              stored: PipelineConfig, others: Seq[PipelineConfig]): Option[ResponseEntity[String]]
  *   // DELETE /pipeline (deletePipelineInternal -> warnings): one
  *   // VectorContainers.keptOnDeleteMessage per key in sharedOnDelete(config, others), names per D3.
  *   def vectorDeleteWarnings(request: HttpServletRequest, config: PipelineConfig,
  *                            others: Seq[PipelineConfig]): Seq[String]
  * }
  * object EntityVersionAPIController {
  *   // POST /pipeline/version/restore, after catalogForSave: the refusal
  *   // restorePipelineVersion returns before writeVersioned (live = stored config or null).
  *   def pipelineRestoreRefusal(request: HttpServletRequest, snap: PipelineConfig,
  *                              live: PipelineConfig, others: Seq[PipelineConfig]): Option[ResponseEntity[String]]
  * }
  * object SearchAPIController {
  *   def containerVisible(request: HttpServletRequest, store: String, container: String,
  *                        schema: Option[String],
  *                        find: (String, String, Option[String]) => Seq[PipelineConfig]): Boolean
  * }
  * object VectorSearchRegistry {
  *   def pipelinesForContainer(store: String, container: String, configs: Seq[PipelineConfig],
  *                             schema: Option[String] = None): Seq[PipelineConfig]
  * }
  * }}}
  */
class VectorContainerRuleSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey, method: String = "POST", path: String = "/api/v1/pipeline"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def restrictedSession(g: java.util.Map[String, String]): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g))

    private val editorSession: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))

    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)

    private val salesKey: ResolvedKey = ResolvedKey(
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

    private val chunking = ChunkingConfig()

    private def pg(name: String, catalog: String, table: String, schema: String = "public", chunkSize: Int = 500): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(pgvector = PGVectorConfig(table, schema, ChunkingConfig(chunkSize = chunkSize), null, null, "oss/pgvector"))
        )

    private def qd(name: String, catalog: String, coll: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(qdrant = QdrantConfig(coll, chunking, null, null, null))
        )

    private def errorOf(r: ResponseEntity[String]): String =
        JsonParser.parseString(r.getBody).getAsJsonObject.get("error").getAsString

    private def assert400(r: Option[ResponseEntity[String]]): String = {
        assert(r.isDefined, "expected a refusal")
        assert(r.get.getStatusCode.value == 400, r.get.getBody)
        errorOf(r.get)
    }

    // ---------------------------------------------------------------- save

    test("an unrestricted caller's refusal is 400 with an error naming the container and the other pipeline") {
        val others = Seq(pg("vc_a", "Sales", "vc_x"), pg("other_pipe", "HR", "elsewhere"))
        for (rk <- Seq(fullKey, editorSession)) {
            val err = assert400(PipelineAPIController.vectorContainerRefusal(req(rk), pg("vc_b", "Sales", "vc_x"), null, others))
            assert(err.contains("public.vc_x"), err)
            assert(err.contains("vc_a"), err)
            assert(!err.contains("other_pipe"), err)
        }
        // a changed container onto another pipeline's is refused the same way
        val stored = pg("vc_b", "Sales", "vc_x", schema = "other")
        val moved = assert400(PipelineAPIController.vectorContainerRefusal(req(fullKey), pg("vc_b", "Sales", "vc_x"), stored, others :+ stored))
        assert(moved.contains("vc_a"), moved)
        // a different schema is a different table
        assert(PipelineAPIController.vectorContainerRefusal(req(fullKey), pg("vc_b", "Sales", "vc_x", schema = "other"), null, others).isEmpty)
    }

    test("a restricted session's refusal for a container held by a hidden pipeline is 400 and never contains that pipeline's name") {
        val bob = req(restrictedSession(grants("Sales" -> "edit")))
        val others = Seq(pg("mkt_pipeline", "Marketing", "mkt_chunks"))
        val r = PipelineAPIController.vectorContainerRefusal(bob, pg("bob_probe", "Sales", "mkt_chunks"), null, others)
        val err = assert400(r)
        assert(err.contains("already in use by another pipeline"), err)
        assert(!r.get.getBody.contains("mkt_pipeline"), r.get.getBody)
        assert(!r.get.getBody.contains("Marketing"), r.get.getBody)

        // one visible and one hidden namer: no pipeline name is printed at all (D3)
        val mixed = Seq(pg("sales_sharer", "Sales", "shared_x"), pg("hr_sharer", "HR", "shared_x"))
        val r2 = PipelineAPIController.vectorContainerRefusal(bob, pg("bob_probe", "Sales", "shared_x"), null, mixed)
        assert400(r2)
        assert(!r2.get.getBody.contains("hr_sharer"), r2.get.getBody)
        assert(!r2.get.getBody.contains("sales_sharer"), r2.get.getBody)

        // all namers visible: they are named
        val visible =
            assert400(PipelineAPIController.vectorContainerRefusal(bob, pg("bob_probe", "Sales", "s_x"), null, Seq(pg("sales_owner", "Sales", "s_x"))))
        assert(visible.contains("sales_owner"), visible)
    }

    test("a catalog-scoped API key gets the name-free message for a pipeline outside its scope") {
        val key = req(salesKey)
        val others = Seq(qd("payroll", "HR", "docs"))
        val r = PipelineAPIController.vectorContainerRefusal(key, qd("probe", "Sales", "docs"), null, others)
        val err = assert400(r)
        assert(err.contains("already in use by another pipeline"), err)
        assert(err.contains("docs"), err)
        assert(!r.get.getBody.contains("payroll"), r.get.getBody)

        // in scope: named
        val inScope = assert400(PipelineAPIController.vectorContainerRefusal(key, qd("probe", "Sales", "docs"), null, Seq(qd("orders", "Sales", "docs"))))
        assert(inScope.contains("orders"), inScope)
    }

    test("no refusal when the container is unchanged from the stored config") {
        val stored = pg("vc_seed", "Sales", "vc_x")
        val others = Seq(pg("vc_a", "Sales", "vc_x"), stored, pg("hr_sharer", "HR", "vc_x"))
        val edited = pg("vc_seed", "Sales", "vc_x", chunkSize = 900)
        for (rk <- Seq(fullKey, editorSession, salesKey, restrictedSession(grants("Sales" -> "edit"))))
            assert(PipelineAPIController.vectorContainerRefusal(req(rk), edited, stored, others).isEmpty, rk.label)
        // a pipeline with no vector destination is never refused
        val plain = PipelineConfig(name = "plain", catalog = "Sales", destination = Destination())
        assert(PipelineAPIController.vectorContainerRefusal(req(fullKey), plain, null, others).isEmpty)
    }

    // ------------------------------------------------------- version restore

    test("version restore onto a container another pipeline names is refused; a restore that keeps the live container is not") {
        val admin = req(fullKey, path = "/api/v1/pipeline/version/restore")
        val live = pg("vc_b", "Sales", "vc_y")
        val others = Seq(pg("vc_a", "Sales", "vc_x"), live)

        val snapOnto = pg("vc_b", "Sales", "vc_x")
        val err = assert400(EntityVersionAPIController.pipelineRestoreRefusal(admin, snapOnto, live, others))
        assert(err.contains("public.vc_x") && err.contains("vc_a"), err)

        // the live pipeline is gone: restoring its snapshot is a new pipeline on that container
        assert400(EntityVersionAPIController.pipelineRestoreRefusal(admin, snapOnto, null, Seq(pg("vc_a", "Sales", "vc_x"))))

        // keeps the live container (even a grandfathered shared one): allowed
        val sharedLive = pg("vc_b", "Sales", "vc_x")
        val keep = pg("vc_b", "Sales", "vc_x", chunkSize = 900)
        assert(EntityVersionAPIController.pipelineRestoreRefusal(admin, keep, sharedLive, Seq(pg("vc_a", "Sales", "vc_x"), sharedLive)).isEmpty)
        assert(EntityVersionAPIController.pipelineRestoreRefusal(admin, pg("vc_b", "Sales", "vc_y"), live, others).isEmpty)
    }

    // ---------------------------------------------------------------- delete

    test("the delete warning for a restricted session omits a hidden sharer's name") {
        val config = pg("vc_a", "Sales", "vc_x")
        val others = Seq(config, pg("hr_sharer", "HR", "vc_x"), pg("lonely", "HR", "lonely_t"))

        val bob = req(restrictedSession(grants("Sales" -> "edit")), method = "DELETE")
        val hidden = PipelineAPIController.vectorDeleteWarnings(bob, config, others)
        assert(hidden.size == 1, hidden)
        assert(hidden.head.contains("public.vc_x"), hidden.head)
        assert(!hidden.head.contains("hr_sharer"), hidden.head)
        assert(!hidden.head.contains("HR"), hidden.head)

        val admin = PipelineAPIController.vectorDeleteWarnings(req(fullKey, method = "DELETE"), config, others)
        assert(admin.size == 1, admin)
        assert(admin.head.contains("hr_sharer"), admin.head)

        val scoped = PipelineAPIController.vectorDeleteWarnings(req(salesKey, method = "DELETE"), config, others)
        assert(scoped.size == 1 && !scoped.head.contains("hr_sharer"), scoped)

        // unshared container: no warning
        assert(PipelineAPIController.vectorDeleteWarnings(req(fullKey, method = "DELETE"), pg("lonely", "HR", "lonely_t"), others).isEmpty)
    }

    // ---------------------------------------------------------------- search

    test("containerVisible is false for a restricted session when one namer is hidden and true when all are visible") {
        val bob = req(restrictedSession(grants("Sales" -> "view")), path = "/api/v1/search/qdrant")
        def finder(namers: Seq[PipelineConfig]): (String, String, Option[String]) => Seq[PipelineConfig] = (_, _, _) => namers

        val mixed = Seq(qd("a", "Sales", "docs"), qd("b", "HR", "docs"))
        assert(!SearchAPIController.containerVisible(request = bob, store = "qdrant", container = "docs", schema = None, find = finder(mixed)))

        val allVisible = Seq(qd("a", "Sales", "docs"), qd("c", "Sales", "docs"))
        assert(SearchAPIController.containerVisible(request = bob, store = "qdrant", container = "docs", schema = None, find = finder(allVisible)))

        // no pipeline names the container: hidden (as before)
        assert(!SearchAPIController.containerVisible(request = bob, store = "qdrant", container = "docs", schema = None, find = finder(Nil)))
    }

    test("containerVisible for pgvector matches the request schema, defaulting to public") {
        val bob = req(restrictedSession(grants("Sales" -> "view")), path = "/api/v1/search/pgvector")
        val configs = Seq(pg("sales_other", "Sales", "vc_x", schema = "other"), pg("hr_public", "HR", "vc_x", schema = "public"))
        val find: (String, String, Option[String]) => Seq[PipelineConfig] =
            (store, container, schema) => VectorSearchRegistry.pipelinesForContainer(store, container, configs, schema = schema)

        assert(VectorSearchRegistry.pipelinesForContainer("pgvector", "vc_x", configs, schema = Some("other")).map(_.name) == Seq("sales_other"))
        assert(VectorSearchRegistry.pipelinesForContainer("pgvector", "vc_x", configs, schema = None).map(_.name) == Seq("hr_public"))

        assert(SearchAPIController.containerVisible(request = bob, store = "pgvector", container = "vc_x", schema = Some("other"), find = find))
        assert(!SearchAPIController.containerVisible(request = bob, store = "pgvector", container = "vc_x", schema = None, find = find))
        assert(!SearchAPIController.containerVisible(request = bob, store = "pgvector", container = "vc_x", schema = Some(""), find = find))
        assert(!SearchAPIController.containerVisible(request = bob, store = "pgvector", container = "vc_x", schema = Some("public"), find = find))
    }

    test("containerVisible stays true for unrestricted sessions and API keys without reading configs") {
        val boom: (String, String, Option[String]) => Seq[PipelineConfig] =
            (_, _, _) => throw new AssertionError("configs must not be read for an unrestricted caller")
        for (rk <- Seq(editorSession, fullKey, salesKey)) {
            val r = req(rk, path = "/api/v1/search/qdrant")
            assert(SearchAPIController.containerVisible(request = r, store = "qdrant", container = "docs", schema = None, find = boom), rk.label)
            assert(SearchAPIController.containerVisible(request = r, store = "pgvector", container = "vc_x", schema = Some("other"), find = boom), rk.label)
        }
    }
}
