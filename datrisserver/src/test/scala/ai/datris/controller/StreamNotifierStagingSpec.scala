package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.{PayloadStager, StagingArea}
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

        // A 0-byte payload must fail the same way, before schema evolution can
        // mistake the missing header for a new "" column and rewrite the config.
        val empty = intercept[DatrisException](stage("", csvConfig))
        assert(empty.getMessage.contains("No data rows found in uploaded file for pipeline: orders"), s"got: ${empty.getMessage}")
        assert(csvConfig.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", "amount"), "schema untouched")
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

    // ---- Phase 4 (plans/stories/streaming-pipeline-phase4.md): the staged overload ----
    //
    //  {{{
    //  def process(staged: StagedPayload, sizeHint: Long, filename: String, pipeline: String, publisherToken: String, tapFeed: TapFeedInfo): JobContext
    //  private[controller] def stageData(staged: StagedPayload, sizeHint: Long, config: PipelineConfig): (Data, PipelineConfig)
    //  }}}
    //
    //  A tap run has already staged and counted its payload (NDJSON / XML /
    //  text). The overload ADOPTS that file into the run's token directory —
    //  no re-parse, no re-count — preserving `arraySource` and `rowCount`, so
    //  the JobRunner cleanup reclaims it with the rest of the run.

    private val xmlConfig = config(FileAttributes(xmlAttributes = XmlAttributes()))

    /** Stage a payload under a "tap" token, the way TapScriptRunner does before handing over. */
    private def tapStaged(stage: StagedPayload => StagedPayload): StagedPayload = StagingArea.withToken("tap-token-1")(stage(null))

    private def adopt(staged: StagedPayload, cfg: PipelineConfig, runToken: String): (Data, PipelineConfig) =
        StagingArea.withToken(runToken)(new StreamNotifier().stageData(staged, staged.bytes, cfg))

    test("staged overload: a tap's NDJSON record list is adopted into the run dir with arraySource and rowCount intact") {
        val staged =
            tapStaged(_ => PayloadStager.stageJson("tap", new java.io.StringReader("""[{"id":1,"amount":5},{"id":2,"amount":9},{"id":3,"amount":11}]""")))
        assert(staged.arraySource && staged.rowCount == 3L)
        val (data, resolved) = adopt(staged, jsonConfig, "run-token-json")

        assert(data.staged.format == StagedFormat.NdJson)
        assert(data.staged.arraySource, "arraySource is preserved, not re-derived")
        assert(data.rowCount == 3L)
        assert(data.size == staged.bytes)
        assert(data.header == null && data.rawBytes == null)
        assert(Paths.get(data.staged.path).startsWith(StagingArea.forToken("run-token-json")), s"adopted into the run's token dir, got ${data.staged.path}")
        assert(stagedLines(data).map(l => JsonParser.parseString(l).getAsJsonObject.get("id").getAsInt) == List(1, 2, 3))
        assert(JobRunner.deriveCountAndType(data) == ((3, "record")))
        assert(resolved eq jsonConfig, "no schema evolution on the JSON path")
        StagingArea.delete("run-token-json")
        StagingArea.delete("tap-token-1")
    }

    test("staged overload: a single-object NDJSON payload keeps arraySource == false and rowCount 1") {
        val staged = tapStaged(_ => PayloadStager.stageJson("tap", new java.io.StringReader("""{"answer":42}""")))
        assert(!staged.arraySource && staged.rowCount == 1L)
        val (data, _) = adopt(staged, jsonConfig, "run-token-single")
        assert(!data.staged.arraySource)
        assert(data.rowCount == 1L)
        assert(JobRunner.deriveCountAndType(data) == ((1, "record")))
        StagingArea.delete("run-token-single")
        StagingArea.delete("tap-token-1")
    }

    // ---- Phase 5 (plans/stories/streaming-pipeline-phase5.md), Step 3: the payload budget on the upload lane ----
    //
    //  `stageData(InputStream, …)` enforces `StagingArea.overBudget` on the bytes
    //  it writes and fails with `StagingArea.budgetExceededMessage(bytes)`.
    //  Before Phase 5 only the tap lanes honoured PIPELINE_MAX_PAYLOAD_MB.

    private def budgetEnv(pipelineMaxPayloadMB: Int): DatrisEnvironment = testEnv.copy(pipelineMaxPayloadMB = pipelineMaxPayloadMB)

    test("stageData: a CSV payload above PIPELINE_MAX_PAYLOAD_MB fails with the disk-budget message; unlimited (0) stages it") {
        val row = "1,\"" + ("x" * 60) + "\"\n"
        val big = new StringBuilder("id,amount\n")
        while (big.length < 1536 * 1024) big.append(row)
        val bytes = big.toString.getBytes(StandardCharsets.UTF_8)

        TenantContext.set(budgetEnv(1))
        try {
            val e = intercept[DatrisException](StagingArea.withToken("budget-run-csv")(stage(bytes, csvConfig)))
            assert(e.getMessage.contains(StagingArea.PayloadBudgetEnvVar + " = 1 MB"), s"got: ${e.getMessage}")
            assert(e.getMessage.contains("disk budget"), s"got: ${e.getMessage}")
        } finally {
            StagingArea.delete("budget-run-csv")
            TenantContext.set(testEnv)
        }

        TenantContext.set(budgetEnv(0))
        try {
            val (data, _) = StagingArea.withToken("budget-run-unlimited")(stage(bytes, csvConfig))
            assert(data.rowCount > 20000L)
        } finally {
            StagingArea.delete("budget-run-unlimited")
            TenantContext.set(testEnv)
        }
    }

    test("stageData: a JSON payload above PIPELINE_MAX_PAYLOAD_MB fails with the disk-budget message") {
        val sb = new StringBuilder("[")
        var i = 0
        while (sb.length < 1536 * 1024) {
            if (i > 0) sb.append(",")
            sb.append("{\"id\":").append(i).append(",\"pad\":\"").append("p" * 80).append("\"}")
            i += 1
        }
        sb.append("]")
        TenantContext.set(budgetEnv(1))
        try {
            val e = intercept[DatrisException](StagingArea.withToken("budget-run-json")(stage(sb.toString, jsonConfig)))
            assert(e.getMessage.contains(StagingArea.PayloadBudgetEnvVar), s"got: ${e.getMessage}")
        } finally {
            StagingArea.delete("budget-run-json")
            TenantContext.set(testEnv)
        }
    }

    // ---- Phase 5, Step 5: the FileNotifier read (`DataUtil.read`) streams into the staging area ----
    //
    //  Seam this spec relies on (the object-store client is a package-level
    //  `lazy val` and cannot be faked, so the stream-level body of `read` is
    //  exposed with the file opener injected):
    //
    //  {{{
    //  object DataUtil {
    //      def read(bucket, key, config, metadata, statusUtil): (Data, PipelineConfig)   // unchanged: resolves files + size, then
    //      def read(files: List[String], open: String => InputStream, size: Long, config: PipelineConfig, statusUtil: StatusUtil): (Data, PipelineConfig)
    //          // CSV: header read off file 1 (header=true), evolveSchema as before, then EVERY file streams through
    //          //      CSVReader.readToWriter into ONE Delimited staged file (removeHeader for files 2+), files in list order;
    //          //      0 rows -> the existing "No data rows found" DatrisException.
    //          // JSON: files.head staged as NDJSON (PayloadStager.stageJson: values verbatim, one record per line);
    //          // XML: files.head copied verbatim; unstructured: files.head copied verbatim + rawBytes.
    //  }
    //  }}}

    /** Captures status events instead of writing to Mongo. */
    private class CapturingStatusUtil extends ai.datris.util.StatusUtil {
        val events = scala.collection.mutable.ListBuffer[(String, String)]()
        override def info(state: String, description: String): Unit = events += ((state, description))
        override def warn(state: String, description: String): Unit = events += ((state, description))
        override def error(state: String, description: String): Unit = events += ((state, description))
    }

    private def opener(files: Map[String, String]): String => java.io.InputStream =
        name => new ByteArrayInputStream(files(name).getBytes(StandardCharsets.UTF_8))

    test("DataUtil.read: a two-file object-store CSV pickup stages header-once with the same row count as before") {
        val files = Map(
            "s3://raw/orders/part-1.csv" -> "ID,Amount\n1,5\n2,9\n",
            "s3://raw/orders/part-2.csv" -> "ID,Amount\n3,\"x,y\"\n4,11"
        )
        val su = new CapturingStatusUtil
        val (data, resolved) = StagingArea.withToken("pickup-csv") {
            ai.datris.util.DataUtil.read(List("s3://raw/orders/part-1.csv", "s3://raw/orders/part-2.csv"), opener(files), 55L, csvConfig, su)
        }
        try {
            assert(data.isDelimited)
            assert(data.staged.format == StagedFormat.Delimited(","))
            assert(data.rowCount == 4L, "2 + 2 data rows, the header counted once")
            assert(data.header == List("id", "amount"))
            assert(data.headerWithSchema.map(_.name) == List("id", "amount"))
            assert(data.size == 55L)
            assert(data.rowIterator().toList == List("1,5", "2,9", "3,\"x,y\"", "4,11"), "files in order, later headers dropped")
            assert(stagedLines(data) == List("1,5", "2,9", "3,\"x,y\"", "4,11"), "one staged file holds every file's rows and nothing else")
            assert(Paths.get(data.staged.path).startsWith(StagingArea.forToken("pickup-csv")), s"staged in the run's dir, got ${data.staged.path}")
            assert(resolved.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", "amount"))
            assert(JobRunner.deriveCountAndType(data) == ((4, "record")))
        } finally StagingArea.delete("pickup-csv")
    }

    test("DataUtil.read: a JSON object pickup stages verbatim as one record") {
        val json = "{\n  \"id\": 1,\n  \"note\": null,\n  \"html\": \"<a & b>\"\n}"
        val su = new CapturingStatusUtil
        val (data, resolved) = StagingArea.withToken("pickup-json") {
            ai.datris.util.DataUtil.read(List("s3://raw/orders/one.json"), opener(Map("s3://raw/orders/one.json" -> json)), json.length.toLong, jsonConfig, su)
        }
        try {
            assert(data.isNdJson, "JSON pickups stage as NDJSON like uploads do, so every downstream stage dispatches the same way")
            assert(data.rowCount == 1L)
            assert(data.header == null && data.rawBytes == null)
            val lines = stagedLines(data)
            assert(lines.size == 1, s"got $lines")
            val obj = JsonParser.parseString(lines.head).getAsJsonObject
            assert(obj.get("id").getAsInt == 1)
            assert(obj.has("note") && obj.get("note").isJsonNull, "explicit null kept: " + lines.head)
            assert(obj.get("html").getAsString == "<a & b>")
            assert(lines.head.contains("<a & b>"), "not HTML-escaped: " + lines.head)
            assert(JobRunner.deriveCountAndType(data) == ((1, "record")))
            assert(resolved eq jsonConfig)
            assert(Paths.get(data.staged.path).startsWith(StagingArea.forToken("pickup-json")))
        } finally StagingArea.delete("pickup-json")
    }

    test("DataUtil.read: an XML pickup stages verbatim with rowCount 1") {
        val xml = "<?xml version=\"1.0\"?><orders><order id=\"1\">a &amp; b</order></orders>"
        val (data, _) = StagingArea.withToken("pickup-xml") {
            ai.datris.util.DataUtil.read(
                List("s3://raw/orders/one.xml"),
                opener(Map("s3://raw/orders/one.xml" -> xml)),
                xml.length.toLong,
                xmlConfig,
                new CapturingStatusUtil
            )
        }
        try {
            assert(data.staged.format == StagedFormat.Xml)
            assert(data.rowCount == 1L)
            assert(new String(Files.readAllBytes(Paths.get(data.staged.path)), StandardCharsets.UTF_8) == xml)
        } finally StagingArea.delete("pickup-xml")
    }

    test("DataUtil.read: header-only CSV input still raises 'No data rows found'") {
        val su = new CapturingStatusUtil
        val e = intercept[DatrisException] {
            StagingArea.withToken("pickup-empty") {
                ai.datris.util.DataUtil.read(List("s3://raw/orders/empty.csv"), opener(Map("s3://raw/orders/empty.csv" -> "id,amount\n")), 10L, csvConfig, su)
            }
        }
        StagingArea.delete("pickup-empty")
        assert(e.getMessage.contains("No data rows found in uploaded file for pipeline: orders"), s"got: ${e.getMessage}")
        assert(e.getMessage.contains("The file may be empty or contain only a header row."))

        // Two header-only files are still zero rows.
        val two = intercept[DatrisException] {
            StagingArea.withToken("pickup-empty-2") {
                ai.datris.util.DataUtil.read(
                    List("s3://raw/orders/e1.csv", "s3://raw/orders/e2.csv"),
                    opener(Map("s3://raw/orders/e1.csv" -> "id,amount\n", "s3://raw/orders/e2.csv" -> "id,amount\n")),
                    20L,
                    csvConfig,
                    su
                )
            }
        }
        StagingArea.delete("pickup-empty-2")
        assert(two.getMessage.contains("No data rows found in uploaded file for pipeline: orders"), s"got: ${two.getMessage}")
    }

    test("staged overload: an XML payload is adopted verbatim with rowCount 1") {
        val xml = "<?xml version=\"1.0\"?><orders><order id=\"1\"/></orders>"
        val staged = tapStaged(_ => PayloadStager.stageText("tap", StagedFormat.Xml, xml))
        val (data, _) = adopt(staged, xmlConfig, "run-token-xml")
        assert(data.staged.format == StagedFormat.Xml)
        assert(data.rowCount == 1L)
        assert(new String(Files.readAllBytes(Paths.get(data.staged.path)), StandardCharsets.UTF_8) == xml)
        assert(Paths.get(data.staged.path).startsWith(StagingArea.forToken("run-token-xml")))
        StagingArea.delete("run-token-xml")
        StagingArea.delete("tap-token-1")
    }
}
