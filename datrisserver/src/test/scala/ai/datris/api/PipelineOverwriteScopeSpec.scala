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

/** Review follow-up to the hidden-dataset-writes story: a scoped API key
  * cannot overwrite (POST /pipeline) or restore (version restore) a pipeline
  * outside its scope, and a stored config the caller cannot see grandfathers
  * nothing under the hidden-writer rule. Mock-request fixtures as in
  * HiddenDatasetWriterRuleSpec. */
class PipelineOverwriteScopeSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("POST")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def key(label: String, caps: String*): HttpServletRequest =
        req(ResolvedKey(None, label, Capability.parseList(caps), isLegacyFullAccess = false))

    private val salesKey = key(
        "sales-builder",
        "pipeline:read:catalog=Sales",
        "pipeline:create:catalog=Sales",
        "pipeline:update:catalog=Sales",
        "pipeline:delete:catalog=Sales"
    )
    private val salesCreateOnly = key("sales-create", "pipeline:read:catalog=Sales", "pipeline:create:catalog=Sales")
    private val ownKey = key("builder", "pipeline:read:owner=self", "pipeline:create:owner=self", "pipeline:update:owner=self")
    private val ragBuilder = key("rag", "pipeline:read", "pipeline:create")
    private val full = req(ResolvedKey(None, "full", Nil, isLegacyFullAccess = true))
    private val noKey = req(null)
    private val editor = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private val bob = req(
        APIKeyValidator.resolveFromSession(
            User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit"))
        )
    )

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def pg(name: String, catalog: String, table: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
        )

    private def status(r: Option[ResponseEntity[String]]): Int = {
        assert(r.isDefined, "expected a refusal")
        r.get.getStatusCode.value
    }

    private val hiddenMkt = pg("mkt_p", "Marketing", "e2e_mkt_tbl")

    test("a catalog-scoped key reusing a hidden pipeline's name is refused by the hidden-writer rule, not grandfathered") {
        val candidate = pg("mkt_p", "Sales", "e2e_mkt_tbl", owner = "sales-builder")
        val res = PipelineAPIController.hiddenWriterRefusal(salesKey, candidate, hiddenMkt, Seq(hiddenMkt), env)
        assert(status(res) == 400)
        val err = JsonParser.parseString(res.get.getBody).getAsJsonObject.get("error").getAsString
        assert(err == "The destination postgres:datris.public.e2e_mkt_tbl is already in use by another pipeline. Choose another destination.", err)
        assert(!res.get.getBody.contains("Marketing"))
        // owner=self key, someone else's pipeline
        assert(status(PipelineAPIController.hiddenWriterRefusal(ownKey, candidate, hiddenMkt, Seq(hiddenMkt), env)) == 400)
        // a visible stored config still grandfathers its dataset
        val mine = pg("sales_p", "Sales", "e2e_mkt_tbl")
        assert(PipelineAPIController.hiddenWriterRefusal(salesKey, mine, mine, Seq(hiddenMkt, mine), env).isEmpty)
        // pure core
        val visible: PipelineConfig => Boolean = _.catalog == "Sales"
        assert(SharedDatasets.hiddenWriterConflicts(candidate, hiddenMkt, Nil, visible, env, storedVisible = false).nonEmpty)
        assert(SharedDatasets.hiddenWriterConflicts(candidate, hiddenMkt, Nil, visible, env).isEmpty)
        assert(SharedDatasets.hiddenWriterConflicts(pg("mkt_p", "Sales", "free_tbl"), hiddenMkt, Nil, visible, env, storedVisible = false).isEmpty)
    }

    test("a scoped key overwriting a pipeline outside its scope is 403; in scope, a new name and unscoped callers are not refused") {
        val save = PipelineAPIController.SaveOverwriteActions
        val res = PipelineAPIController.overwriteRefusal(salesKey, hiddenMkt, save)
        assert(status(res) == 403)
        assert(res.get.getBody.contains("capability_denied"), res.get.getBody)
        // in scope: by catalog, and with create alone
        assert(PipelineAPIController.overwriteRefusal(salesKey, pg("s", "Sales", "t"), save).isEmpty)
        assert(PipelineAPIController.overwriteRefusal(salesCreateOnly, pg("s", "Sales", "t"), save).isEmpty)
        assert(status(PipelineAPIController.overwriteRefusal(salesCreateOnly, hiddenMkt, save)) == 403)
        // owner=self: own pipeline passes, someone else's is 403
        assert(PipelineAPIController.overwriteRefusal(ownKey, pg("o", "Any", "t", owner = "builder"), save).isEmpty)
        assert(status(PipelineAPIController.overwriteRefusal(ownKey, pg("o", "Any", "t", owner = "someone"), save)) == 403)
        // a new name
        assert(PipelineAPIController.overwriteRefusal(salesKey, null, save).isEmpty)
        // unscoped callers never take the branch
        for (r <- Seq(full, noKey, editor, ragBuilder)) {
            assert(!PipelineAPIController.overwriteCheckNeeded(r, save))
            assert(PipelineAPIController.overwriteRefusal(r, hiddenMkt, save).isEmpty)
        }
        // a restricted session keeps its own 404/403 rules (catalogForSave)
        assert(!PipelineAPIController.overwriteCheckNeeded(bob, save))
        assert(PipelineAPIController.overwriteRefusal(bob, hiddenMkt, save).isEmpty)
        for (r <- Seq(salesKey, salesCreateOnly, ownKey)) assert(PipelineAPIController.overwriteCheckNeeded(r, save))
    }

    test("a scoped key cannot restore a version of a pipeline outside its scope") {
        val snap = pg("mkt_p", "Marketing", "e2e_mkt_tbl")
        assert(status(EntityVersionAPIController.pipelineRestoreScopeRefusal(salesKey, snap, hiddenMkt)) == 403)
        // deleted pipeline: the snapshot's scope applies
        assert(status(EntityVersionAPIController.pipelineRestoreScopeRefusal(salesKey, snap, null)) == 403)
        assert(status(EntityVersionAPIController.pipelineRestoreScopeRefusal(ownKey, snap, null)) == 403)
        // in scope: update on the live pipeline's catalog
        val live = pg("s", "Sales", "t")
        assert(EntityVersionAPIController.pipelineRestoreScopeRefusal(salesKey, live.copy(catalog = "Sales"), live).isEmpty)
        assert(EntityVersionAPIController.pipelineRestoreScopeRefusal(ownKey, snap, pg("mkt_p", "Any", "t", owner = "builder")).isEmpty)
        // restore needs update: create alone is not enough
        assert(status(EntityVersionAPIController.pipelineRestoreScopeRefusal(salesCreateOnly, live, live)) == 403)
        for (r <- Seq(full, noKey, editor, bob))
            assert(EntityVersionAPIController.pipelineRestoreScopeRefusal(r, snap, hiddenMkt).isEmpty)
    }

    test("a delete reads the dataset environment only for a database or pgvector destination with data deleted") {
        val unknown = SharedDatasets.Env("", None, None)
        val qd = PipelineConfig(name = "q", catalog = "Sales", destination = Destination(qdrant = QdrantConfig("docs", ChunkingConfig(), null, null, null)))
        val os = PipelineConfig(name = "o", catalog = "Sales", destination = Destination(objectStore = ObjectStore(prefixKey = "hr")))
        assert(PipelineAPIController.deleteDatasetEnv(qd, deleteData = true) == unknown)
        assert(PipelineAPIController.deleteDatasetEnv(os, deleteData = true) == unknown)
        assert(PipelineAPIController.deleteDatasetEnv(PipelineConfig(name = "n", destination = null), deleteData = true) == unknown)
        assert(PipelineAPIController.deleteDatasetEnv(pg("a", "Sales", "t"), deleteData = false) == unknown)
        // no structured key comes out of the all-unknown env for those configs
        assert(SharedDatasets.sharedOnDelete(qd, Seq(qd.copy(name = "q2")), unknown).isEmpty)
        // a database destination reads it (never throws without an environment)
        PipelineAPIController.deleteDatasetEnv(pg("a", "Sales", "t"), deleteData = true)
    }
}
