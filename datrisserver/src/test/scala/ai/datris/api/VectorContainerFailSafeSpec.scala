package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

/** One vector container per pipeline: the save and delete paths fail safe
  * when the pipeline configs cannot be read, a non-vector delete reads no
  * configs, and a reset that keeps a shared container keeps the tap ledgers. */
class VectorContainerFailSafeSpec extends AnyFunSuite {

    private def pg(name: String, table: String): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            destination = Destination(pgvector = PGVectorConfig(table, "public", ChunkingConfig(), null, null, null))
        )

    private val plain = PipelineConfig(name = "plain", catalog = "Sales", destination = Destination())

    private val failing: () => Seq[PipelineConfig] = () => throw new RuntimeException("config db down")
    private val boom: () => Seq[PipelineConfig] = () => throw new AssertionError("configs must not be read")

    test("a save that adds a vector container is refused with 500 when the configs cannot be read") {
        val fresh = PipelineAPIController.othersForSave(pg("vc_b", "vc_x"), null, failing)
        assert(fresh.isLeft)
        assert(fresh.left.get.getStatusCode.value == 500)
        val err = JsonParser.parseString(fresh.left.get.getBody).getAsJsonObject.get("error").getAsString
        assert(err.contains("nothing was saved"), err)

        val moved = PipelineAPIController.othersForSave(pg("vc_b", "vc_x"), pg("vc_b", "vc_y"), failing)
        assert(moved.isLeft && moved.left.get.getStatusCode.value == 500)
    }

    test("a save that adds no vector container stays fail-open when the configs cannot be read") {
        assert(PipelineAPIController.othersForSave(plain, null, failing) == Right(Nil))
        assert(PipelineAPIController.othersForSave(pg("vc_b", "vc_x"), pg("vc_b", "vc_x"), failing) == Right(Nil))
        val others = Seq(pg("vc_a", "vc_x"))
        assert(PipelineAPIController.othersForSave(pg("vc_b", "vc_x"), null, () => others) == Right(others))
    }

    test("a vector delete fails before anything is destroyed when the configs cannot be read") {
        val e = intercept[IllegalStateException](PipelineAPIController.vectorOthersForDelete(pg("vc_a", "vc_x"), deleteData = true, failing))
        assert(e.getMessage.contains("nothing was deleted"), e.getMessage)
    }

    test("a delete reads no configs for a pipeline without a vector container or when no data is deleted") {
        assert(PipelineAPIController.vectorOthersForDelete(plain, deleteData = true, boom).isEmpty)
        assert(PipelineAPIController.vectorOthersForDelete(pg("vc_a", "vc_x"), deleteData = false, boom).isEmpty)
        val read = PipelineAPIController.vectorOthersForDelete(pg("vc_a", "vc_x"), deleteData = true, () => Seq(pg("vc_seed", "vc_x"), null))
        assert(read.map(_.name) == Seq("vc_seed"))
    }

    test("a data-only reset that keeps a shared vector container keeps the document-tap ledgers") {
        assert(!PipelineAPIController.clearTapLedgersOnDelete(deleteConfig = false, keptSharedVector = true))
        assert(PipelineAPIController.clearTapLedgersOnDelete(deleteConfig = false, keptSharedVector = false))
        assert(PipelineAPIController.clearTapLedgersOnDelete(deleteConfig = true, keptSharedVector = true))
        assert(PipelineAPIController.clearTapLedgersOnDelete(deleteConfig = true, keptSharedVector = false))
    }
}
