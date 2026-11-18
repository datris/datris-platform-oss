package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, PipelineDeletePlan}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonParser
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite

/** Pipeline delete keep-data review, findings 4 and 6: the handler's
  * refusal order (a hidden pipeline is 404 before the vector 400), and
  * which Unity Catalog state docs a keep-data delete keeps. */
class PipelineDeleteReviewFixesSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("DELETE")
        when(r.getRequestURI).thenReturn("/api/v1/pipeline")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def user(level: String): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> level)))
    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)

    private val keep = PipelineDeletePlan.of("false", "true")
    private val full = PipelineDeletePlan.of("true", "true")

    private def qd(name: String, catalog: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = "someone",
            destination = Destination(qdrant = QdrantConfig("kd_coll", ChunkingConfig(), null, null, null))
        )

    test("a keep-data delete of a vector pipeline the caller cannot see answers 404, not the vector 400") {
        val r = PipelineAPIController.deleteRefusal(req(user("own")), qd("fin_vec", "Finance"), keep)
        assert(r.map(_.getStatusCode.value).contains(404), r)
        assert(!r.get.getBody.contains("vector"), r.get.getBody)
    }

    test("a caller whose grant does not allow the delete gets today's 404 (not the 400); one who owns the catalog gets the vector 400") {
        // assertVisible answers a restricted session without pipeline:delete
        // on the catalog as if the pipeline did not exist, as before.
        val denied = PipelineAPIController.deleteRefusal(req(user("edit")), qd("sales_vec", "Sales"), keep)
        assert(denied.map(_.getStatusCode.value).contains(404), denied)
        val owner = PipelineAPIController.deleteRefusal(req(user("own")), qd("sales_vec", "Sales"), keep)
        assert(owner.map(_.getStatusCode.value).contains(400), owner)
        assert(JsonParser.parseString(owner.get.getBody).getAsJsonObject.get("error").getAsString == PipelineDeletePlan.KeepDataVectorRefusal)
        assert(PipelineAPIController.deleteRefusal(req(fullKey), qd("sales_vec", "Sales"), keep).map(_.getStatusCode.value).contains(400))
    }

    test("no refusal for a full delete the caller may make, or a keep-data delete of a table pipeline") {
        assert(PipelineAPIController.deleteRefusal(req(user("own")), qd("sales_vec", "Sales"), full).isEmpty)
        val pg = PipelineConfig(
            name = "sales_pg",
            catalog = "Sales",
            createdByKeyLabel = "someone",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = "t", usePostgres = true))
        )
        assert(PipelineAPIController.deleteRefusal(req(user("own")), pg, keep).isEmpty)
        assert(PipelineAPIController.deleteRefusal(req(fullKey), pg, keep).isEmpty)
    }

    private def iceberg(uc: UnityCatalogSync): PipelineConfig =
        PipelineConfig(
            name = "kd_ice",
            catalog = "Sales",
            destination = Destination(objectStore = ObjectStore(prefixKey = "kd_ice", fileFormat = "iceberg", destinationBucketOverride = "lake")),
            unityCatalog = uc
        )

    private val restCfg = iceberg(UnityCatalogSync(enabled = true, catalog = "main", schema = "sales", catalogMode = "rest"))

    test("keep-data keeps a rest-committed or managed doc, and removes a created-only, register-mode or foreign-root doc") {
        val committed = UnityCatalogSyncState(
            "kd_ice",
            null,
            null,
            null,
            null,
            null,
            null,
            catalogMode = "rest",
            restMetadataLocation = "s3://lake/kd_ice/metadata/00003-ab.metadata.json",
            lastRestCommitAt = "2026-09-29T10:00:03Z"
        )
        assert(PipelineDeletePlan.keepUnityCatalogState(restCfg, committed))

        val managed = UnityCatalogSyncState(
            "kd_ice",
            null,
            null,
            null,
            null,
            null,
            null,
            catalogMode = "managed",
            restMetadataLocation = "s3://uc/__unitystorage/t/metadata/00001.metadata.json",
            lastRestCommitAt = "2026-09-29T10:00:03Z"
        )
        assert(PipelineDeletePlan.keepUnityCatalogState(restCfg, managed))

        val createdOnly = UnityCatalogSyncState("kd_ice", null, null, null, null, null, null, catalogMode = "rest", restCreatedTable = "main.sales.kd_ice")
        assert(!PipelineDeletePlan.keepUnityCatalogState(restCfg, createdOnly))

        val registered = UnityCatalogSyncState(
            "kd_ice",
            null,
            null,
            null,
            null,
            null,
            null,
            registeredMetadataLocation = "s3a://lake/kd_ice/metadata/v2.metadata.json",
            lastRegisterAt = "2026-09-29T10:00:00Z"
        )
        assert(!PipelineDeletePlan.keepUnityCatalogState(iceberg(UnityCatalogSync(enabled = true, catalog = "main", schema = "sales")), registered))

        val elsewhere = committed.copy(restMetadataLocation = "s3://other/else/metadata/00003-ab.metadata.json")
        assert(!PipelineDeletePlan.keepUnityCatalogState(restCfg, elsewhere))
        assert(!PipelineDeletePlan.keepUnityCatalogState(restCfg, null))
    }
}
