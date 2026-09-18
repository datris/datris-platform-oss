package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}

/** File-backed `Data` (plans/stories/streaming-pipeline.md, Phase 1).
  *
  *  {{{
  *  case class Data(size, header, headerWithSchema, staged: StagedPayload, rawBytes)
  *      def rowCount: Long
  *      def rowIterator(): Iterator[String]
  *      @deprecated def rows: List[String]      // materializes; refuses above the cap
  *      def withRows(rows: List[String]): Data  // writes a NEW staged file
  *  object Data {
  *      // old positional shape, stages its input so existing call sites compile
  *      def apply(size, header, headerWithSchema, rows, rawData, rawBytes = null): Data
  *  }
  *  case class StagedPayload(path, format: StagedFormat, rowCount, bytes)
  *  }}}
  *
  *  The staging root and the materialize cap come from `DatrisEnvironment`
  *  (`tempDir` / `pipelineMaterializeMaxMB`, i.e. `DATRIS_TEMP_DIR` /
  *  `PIPELINE_MATERIALIZE_MAX_MB`). `DatrisEnvironment.current` is null in unit
  *  tests, so each test installs a per-thread environment via `TenantContext`
  *  pointing at a private temp directory.
  */
class DataStagingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("data-staging-spec")

    private def env(capMB: Int): DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = null,
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false,
        tempDir = root.toString,
        pipelineMaterializeMaxMB = capMB
    )

    private def withCap[T](capMB: Int)(body: => T): T = {
        TenantContext.set(env(capMB))
        try body
        finally TenantContext.clear()
    }

    override def afterAll(): Unit = TenantContext.clear()

    private val fields = List(SchemaField("id", "string"), SchemaField("v", "string"))

    private def stagedPath(d: Data): Path = Paths.get(d.staged.path.toString)

    private def delimited(rows: List[String]): Data =
        Data(rows.map(_.length.toLong + 1L).sum, List("id", "v"), fields, rows, null)

    // Acceptance 1
    test("a Data built from 100k rows exposes rowCount and rowIterator without retaining a List[String]") {
        withCap(256) {
            val rows = (1 to 100000).map(i => i + ",v" + i).toList
            val data = delimited(rows)

            assert(data.rowCount == 100000, "rowCount comes from the staged payload")
            assert(data.staged.rowCount == 100000)
            assert(data.rowIterator().length == 100000, "rowIterator streams every staged row")

            // Same rows, same order, header stripped.
            val it = data.rowIterator()
            assert(it.next() == "1,v1")
            assert(data.rowIterator().drop(99999).next() == "100000,v100000")

            // The payload lives on disk under DATRIS_TEMP_DIR, not in the case class.
            assert(Files.isRegularFile(stagedPath(data)), "staged file must exist while the Data is alive")
            assert(stagedPath(data).startsWith(root), s"staged file ${stagedPath(data)} must be under the configured tempDir $root")
            assert(data.staged.bytes > 0L)
            val retainedLists = data.productIterator.collect { case l: List[_] => l.size }.toList
            assert(retainedLists.forall(_ < 100000), s"no field of Data may hold the 100k rows as a List, got list sizes $retainedLists")
        }
    }

    // Acceptance 2
    test("data.rows above pipelineMaterializeMaxMB throws a DatrisException naming the cap env var") {
        withCap(1) {
            // ~2.2 MB of rows, comfortably over a 1 MB cap.
            val rows = (1 to 40000).map(i => i + "," + ("x" * 50)).toList
            val data = delimited(rows)
            assert(data.staged.bytes > 1L * 1024 * 1024, "fixture must exceed the 1 MB cap")

            val e = intercept[DatrisException](data.rows)
            assert(e.getMessage.contains("PIPELINE_MATERIALIZE_MAX_MB"), s"message must name the cap env var, got: ${e.getMessage}")
            // The iterator path is not capped — only whole-payload materialization is.
            assert(data.rowIterator().length == 40000)
        }
    }

    // Acceptance 2
    test("data.rows below pipelineMaterializeMaxMB returns the exact rows") {
        withCap(1) {
            val rows = List("1,a", "2,\"b,c\"", "3,", "4,d")
            val data = delimited(rows)
            assert(data.staged.bytes < 1L * 1024 * 1024)
            assert(data.rows == rows)
            // Materializing does not consume or remove the staged file.
            assert(data.rows == rows)
            assert(Files.isRegularFile(stagedPath(data)))
        }
    }

    // Acceptance 3
    test("Data.withRows writes a new staged file and leaves the previous file on disk") {
        withCap(256) {
            val original = delimited(List("1,a", "2,b", "3,c"))
            val before = stagedPath(original)

            val replaced = original.withRows(List("9,z"))

            assert(stagedPath(replaced) != before, "withRows must stage into a new file")
            assert(Files.isRegularFile(before), "the previous staged file must not be deleted mid-run")
            assert(Files.isRegularFile(stagedPath(replaced)))
            assert(replaced.rowCount == 1)
            assert(replaced.rows == List("9,z"))
            // The original Data is untouched.
            assert(original.rowCount == 3)
            assert(original.rows == List("1,a", "2,b", "3,c"))
            // Non-payload fields carry over.
            assert(replaced.header == original.header)
            assert(replaced.headerWithSchema == original.headerWithSchema)
        }
    }
}
