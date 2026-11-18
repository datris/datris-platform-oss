package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.{PipelineDeletePlan, UnityCatalogDeleteAdvice}
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import scala.jdk.CollectionConverters._

/** Story: Pipeline delete: an explicit `deleteData=false` keeps the
  * destination data (plans/stories/pipeline-delete-keep-data.md), Acceptance,
  * PipelineDeleteKeepDataSpec. Fixtures in the style of
  * VectorContainerFailSafeSpec (no I/O: readers that throw prove nothing is read).
  *
  * Signatures assumed (in addition to PipelineDeletePlanSpec's):
  * {{{
  * object PipelineAPIController {
  *   // DELETE /pipeline 200 body: {"warnings":[...]} and, in keep mode only, "kept":[...]
  *   def deleteResponseBody(warnings: Seq[String], kept: Option[Seq[String]]): String
  *   // 400 ApiErrors.errorBody(PipelineDeletePlan.KeepDataVectorRefusal) when
  *   // PipelineDeletePlan.refusal(plan, config) is defined; None otherwise
  *   def keepDataRefusal(plan: PipelineDeletePlan.Plan, config: PipelineConfig): Option[ResponseEntity[String]]
  *   // whether the delete clears document-tap ledgers for this plan
  *   // (false for a keep plan; otherwise clearTapLedgersOnDelete(plan.deleteConfig, keptSharedVector))
  *   def clearTapLedgersForPlan(plan: PipelineDeletePlan.Plan, keptSharedVector: Boolean): Boolean
  * }
  * }}}
  * The existing othersForDelete / vectorOthersForDelete / deleteDatasetEnv /
  * clearTapLedgersOnDelete signatures are unchanged.
  */
class PipelineDeleteKeepDataSpec extends AnyFunSuite {

    private val keep = PipelineDeletePlan.of("false", "true")
    private val full = PipelineDeletePlan.of("true", "true")
    private val reset = PipelineDeletePlan.of("true", "false")
    private val none = PipelineDeletePlan.of("false", "false")

    private val chunking = ChunkingConfig()

    private def p(name: String, d: Destination): PipelineConfig =
        PipelineConfig(name = name, catalog = "Sales", destination = d, createdByKeyLabel = "someone")

    private def pgT(name: String, table: String): PipelineConfig =
        p(name, Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true)))
    private def mongo(name: String, coll: String): PipelineConfig =
        p(name, Destination(database = Database(dbName = "datris", table = coll, useMongoDB = true)))
    private def pgv(name: String, table: String): PipelineConfig =
        p(name, Destination(pgvector = PGVectorConfig(table, "public", chunking, null, null, null)))
    private def qd(name: String, coll: String): PipelineConfig =
        p(name, Destination(qdrant = QdrantConfig(coll, chunking, null, null, null)))

    private val boom: () => Seq[PipelineConfig] = () => throw new AssertionError("configs must not be read")

    private def obj(body: String) = JsonParser.parseString(body).getAsJsonObject

    test("deleteResponseBody has no kept key outside keep mode and equals today's body") {
        assert(PipelineAPIController.deleteResponseBody(Nil, None) == """{"warnings":[]}""")
        assert(PipelineAPIController.deleteResponseBody(Seq("w1", "w2"), None) == """{"warnings":["w1","w2"]}""")
        assert(!obj(PipelineAPIController.deleteResponseBody(Seq("w1"), None)).has("kept"))
    }

    test("keep mode carries kept, empty array included") {
        val empty = obj(PipelineAPIController.deleteResponseBody(Nil, Some(Nil)))
        assert(empty.has("kept"), empty)
        assert(empty.getAsJsonArray("kept").size == 0)
        assert(empty.getAsJsonArray("warnings").size == 0)

        val body =
            obj(PipelineAPIController.deleteResponseBody(Seq("w1"), Some(Seq("postgres table datris.public.kd_pg", "object-store prefix dev-data.kd_os"))))
        assert(body.getAsJsonArray("warnings").iterator.asScala.map(_.getAsString).toSeq == Seq("w1"))
        assert(
            body.getAsJsonArray("kept").iterator.asScala.map(_.getAsString).toSeq ==
                Seq("postgres table datris.public.kd_pg", "object-store prefix dev-data.kd_os")
        )
        assert(body.keySet.asScala == Set("warnings", "kept"))
    }

    test("a keep plan reads no configs") {
        assert(keep.keepData && !keep.deleteData && keep.deleteConfig, keep)
        Seq(pgT("kd_pg", "kd_pg"), mongo("kd_mg", "kd_mg"), pgv("kd_pgv", "kd_chunks"), qd("kd_qd", "kd_coll")).foreach { c =>
            val env = PipelineAPIController.deleteDatasetEnv(c, keep.deleteData)
            assert(PipelineAPIController.othersForDelete(c, keep.deleteData, env, boom) == Nil, c.name)
            assert(PipelineAPIController.vectorOthersForDelete(c, keep.deleteData, boom) == Nil, c.name)
        }
    }

    test("the vector refusal is 400 with only the error key, naming no pipeline, catalog or container") {
        val c = qd("kd_hidden_pipe", "kd_hidden_coll")
        val r = PipelineAPIController.keepDataRefusal(keep, c)
        assert(r.isDefined)
        assert(r.get.getStatusCode.value == 400)
        val body = obj(r.get.getBody)
        assert(body.keySet.asScala == Set("error"), body)
        val err = body.get("error").getAsString
        assert(err == PipelineDeletePlan.KeepDataVectorRefusal, err)
        Seq("kd_hidden_pipe", "kd_hidden_coll", "Sales").foreach(s => assert(!err.contains(s), s + " in " + err))

        val pgvMixed = p(
            "kd_mixed",
            Destination(
                database = Database(dbName = "datris", schema = "public", table = "kd_rows", usePostgres = true),
                pgvector = PGVectorConfig("kd_chunks", "public", chunking, null, null, null)
            )
        )
        assert(PipelineAPIController.keepDataRefusal(keep, pgvMixed).map(_.getStatusCode.value).contains(400))

        // Not refused: other plans on a vector pipeline, or keep on a table.
        Seq(full, reset, none).foreach(plan => assert(PipelineAPIController.keepDataRefusal(plan, c).isEmpty, plan))
        assert(PipelineAPIController.keepDataRefusal(keep, pgT("kd_pg", "kd_pg")).isEmpty)
    }

    private val Root = "s3a://lake/kd_ice"

    private def iceberg(uc: UnityCatalogSync): PipelineConfig =
        PipelineConfig(
            name = "kd_ice",
            catalog = "Sales",
            destination = Destination(objectStore = ObjectStore(prefixKey = "kd_ice", fileFormat = "iceberg", destinationBucketOverride = "lake")),
            unityCatalog = uc
        )

    test("Unity Catalog: a rest-committed doc is kept and the kept-files advice is returned for a keep plan; a register-mode doc is not kept") {
        val restCfg = iceberg(UnityCatalogSync(enabled = true, catalog = "main", schema = "sales", catalogMode = "rest"))
        val committed = UnityCatalogSyncState(
            "kd_ice",
            null,
            null,
            null,
            null,
            null,
            null,
            catalogMode = "rest",
            restMetadataLocation = Root.replace("s3a://", "s3://") + "/metadata/00003-ab.metadata.json",
            lastRestCommitAt = "2026-09-29T10:00:03Z"
        )
        val advice = UnityCatalogDeleteAdvice.forPipeline(restCfg, committed, dataDeleted = keep.deleteData, configDeleted = keep.deleteConfig)
        assert(advice.exists(_.contains("can still read it")), advice)
        assert(advice.exists(_.contains("main.sales.kd_ice")), advice)
        assert(UnityCatalogDeleteAdvice.keepStateOnDelete(committed, dataDeleted = keep.deleteData, adviceGiven = advice.isDefined))

        val registerCfg = iceberg(UnityCatalogSync(enabled = true, catalog = "main", schema = "sales"))
        val registered = UnityCatalogSyncState(
            "kd_ice",
            null,
            null,
            null,
            null,
            null,
            null,
            registeredMetadataLocation = Root + "/metadata/v2.metadata.json",
            lastRegisterAt = "2026-09-29T10:00:00Z"
        )
        val regAdvice = UnityCatalogDeleteAdvice.forPipeline(registerCfg, registered, dataDeleted = keep.deleteData, configDeleted = keep.deleteConfig)
        assert(regAdvice.isEmpty, regAdvice)
        assert(!UnityCatalogDeleteAdvice.keepStateOnDelete(registered, dataDeleted = keep.deleteData, adviceGiven = regAdvice.isDefined))
    }

    test("tap ledgers are not cleared on a keep plan") {
        assert(!PipelineAPIController.clearTapLedgersForPlan(keep, keptSharedVector = false))
        assert(!PipelineAPIController.clearTapLedgersForPlan(keep, keptSharedVector = true))
        // Every other plan that deletes data: today's rule.
        assert(PipelineAPIController.clearTapLedgersForPlan(full, keptSharedVector = false))
        assert(PipelineAPIController.clearTapLedgersForPlan(full, keptSharedVector = true))
        assert(PipelineAPIController.clearTapLedgersForPlan(reset, keptSharedVector = false))
        assert(!PipelineAPIController.clearTapLedgersForPlan(reset, keptSharedVector = true))
        // The pinned two-flag rule is unchanged.
        assert(PipelineAPIController.clearTapLedgersOnDelete(deleteConfig = true, keptSharedVector = true))
        assert(!PipelineAPIController.clearTapLedgersOnDelete(deleteConfig = false, keptSharedVector = true))
    }
}
