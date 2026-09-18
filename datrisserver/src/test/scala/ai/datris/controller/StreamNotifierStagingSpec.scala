package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.JsonParser
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

/** `StreamNotifier.stageData` (plans/stories/streaming-pipeline.md, Phase 1):
  *  the former `parseData(byteArray, config)` now takes an `InputStream` plus a
  *  size hint and writes a staged file instead of building `rows` / `rawData`
  *  in memory.
  *
  *  {{{
  *  private[controller] def stageData(source: InputStream, sizeHint: Long, config: PipelineConfig): (Data, PipelineConfig)
  *  }}}
  *
  *  `StreamNotifier` needs a non-null `DatrisEnvironment.current` to build its
  *  `StatusUtil` (table name only, no Mongo). Fixtures are chosen so
  *  `DataUtil.evolveSchema` runs but never reaches a Mongo write: the CSV header
  *  differs from the schema only in case and column order.
  */
class StreamNotifierStagingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("stream-notifier-staging-spec")

    private def testEnv: DatrisEnvironment = DatrisEnvironment(
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
        tempDir = root.toString
    )

    override def beforeAll(): Unit = TenantContext.set(testEnv)
    override def afterAll(): Unit = TenantContext.clear()

    private def fields(names: String*): java.util.List[SchemaField] =
        new java.util.ArrayList[SchemaField](names.map(n => SchemaField(n, "string")).asJava)

    private def config(fileAttributes: FileAttributes): PipelineConfig =
        PipelineConfig(
            name = "orders",
            source = Source(schemaProperties = SchemaProperties("db", fields("id", "amount")), fileAttributes = fileAttributes),
            destination = Destination(schemaProperties = SchemaProperties("db", fields("id", "amount")))
        )

    private val csvConfig = config(FileAttributes(csvAttributes = CsvAttributes()))
    private val jsonConfig = config(FileAttributes(jsonAttributes = JsonAttributes()))
    private val unstructuredConfig = config(FileAttributes(unstructuredAttributes = UnstructuredAttributes()))

    private def stage(payload: String, cfg: PipelineConfig): (Data, PipelineConfig) = stage(payload.getBytes(StandardCharsets.UTF_8), cfg)

    private def stage(bytes: Array[Byte], cfg: PipelineConfig): (Data, PipelineConfig) =
        new StreamNotifier().stageData(new ByteArrayInputStream(bytes), bytes.length.toLong, cfg)

    private def stagedLines(d: Data): List[String] =
        Files.readAllLines(Paths.get(d.staged.path.toString), StandardCharsets.UTF_8).asScala.toList

    // Acceptance 4
    test("CSV with a header stages Delimited output equal to today's parseData rows (header stripped, schema applied)") {
        // Header differs from the schema in case and order; schema evolution maps it
        // back to schema order without adding or dropping columns.
        val csv = "Amount,ID\n5,1\n9,2\n\"x,y\",3\n"
        val (data, resolved) = stage(csv, csvConfig)

        assert(data.staged.format == StagedFormat.Delimited(","))
        assert(data.rowCount == 3)
        assert(data.header == List("id", "amount"), "header is the schema column list")
        assert(data.headerWithSchema.map(_.name) == List("id", "amount"))

        // Exactly what parseData produced before: header line gone, columns in
        // schema order, values re-quoted per RFC 4180.
        val expectedRows = List("1,5", "2,9", "3,\"x,y\"")
        assert(data.rowIterator().toList == expectedRows)
        assert(data.rows == expectedRows)
        assert(stagedLines(data) == expectedRows, "the staged file holds the data rows and nothing else")

        assert(data.size == csv.getBytes(StandardCharsets.UTF_8).length.toLong)
        assert(resolved.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", "amount"), "no evolution for a case/order-only difference")
        assert(data.rawBytes == null)
    }

    // Acceptance 5
    test("a JSON array of 3 objects stages as 3 NDJSON lines with rowCount == 3") {
        // Pretty-printed on purpose: the line count of the input is not the record count.
        val json =
            """[
              |  {"id": 1, "amount": 5},
              |  {"id": 2,
              |   "amount": 9},
              |  {"id": 3, "amount": 11}
              |]""".stripMargin
        val (data, _) = stage(json, jsonConfig)

        assert(data.staged.format == StagedFormat.NdJson)
        assert(data.rowCount == 3)
        val lines = stagedLines(data)
        assert(lines.size == 3, s"expected 3 NDJSON lines, got: $lines")
        val ids = lines.map(l => JsonParser.parseString(l).getAsJsonObject.get("id").getAsInt)
        assert(ids == List(1, 2, 3))
        assert(lines.forall(l => JsonParser.parseString(l).isJsonObject), "every line is one JSON object")
    }

    // Acceptance 5
    test("a single JSON object stages as 1 NDJSON line with rowCount == 1") {
        val json = "{\n  \"id\": 1,\n  \"amount\": 5\n}"
        val (data, _) = stage(json, jsonConfig)

        assert(data.staged.format == StagedFormat.NdJson)
        assert(data.rowCount == 1)
        val lines = stagedLines(data)
        assert(lines.size == 1, s"expected 1 NDJSON line, got: $lines")
        val obj = JsonParser.parseString(lines.head).getAsJsonObject
        assert(obj.get("id").getAsInt == 1 && obj.get("amount").getAsInt == 5)
    }

    // Acceptance 6
    test("an empty CSV (header only) still raises the existing 'No data rows found' error") {
        val e = intercept[DatrisException](stage("id,amount\n", csvConfig))
        assert(e.getMessage.contains("No data rows found in uploaded file for pipeline: orders"), s"got: ${e.getMessage}")
        assert(e.getMessage.contains("The file may be empty or contain only a header row."))
    }

    // Acceptance 10 (unit-level half): the run's record count and data type are
    // derived from the staged payload and match today's numbers exactly.
    test("deriveCountAndType keeps today's numbers: delimited rows, JSON array size, single object, unstructured document") {
        val (csv, _) = stage("id,amount\n1,5\n2,9\n", csvConfig)
        assert(JobRunner.deriveCountAndType(csv) == ((2, "record")))

        val (array, _) = stage("""[{"id":1},{"id":2},{"id":3}]""", jsonConfig)
        assert(JobRunner.deriveCountAndType(array) == ((3, "record")))

        val (single, _) = stage("""{"id":1}""", jsonConfig)
        assert(JobRunner.deriveCountAndType(single) == ((1, "record")))

        val bytes = Array[Byte](0x25, 0x50, 0x44, 0x46, 0x2d, 0x00, 0x7f) // "%PDF-" + binary
        val (doc, _) = stage(bytes, unstructuredConfig)
        assert(JobRunner.deriveCountAndType(doc) == ((1, "document")))
        assert(doc.rawBytes != null && doc.rawBytes.sameElements(bytes), "unstructured payloads still fill rawBytes")
    }
}
