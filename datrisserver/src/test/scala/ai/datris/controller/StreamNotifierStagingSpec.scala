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

    // Phase 5 review: the object-store pickup lane is bounded by the same budget.
    test("DataUtil.read: a CSV or JSON pickup above PIPELINE_MAX_PAYLOAD_MB fails with the disk-budget message and leaves no file in the token dir") {
        val row = "1,\"" + ("x" * 60) + "\"\n"
        val csv = new StringBuilder("id,amount\n")
        while (csv.length < 1536 * 1024) csv.append(row)
        val json = new StringBuilder("[")
        var i = 0
        while (json.length < 1536 * 1024) {
            if (i > 0) json.append(",")
            json.append("{\"id\":").append(i).append(",\"pad\":\"").append("p" * 80).append("\"}")
            i += 1
        }
        json.append("]")
        val su = new CapturingStatusUtil

        def filesIn(token: String): List[Path] = {
            val dir = StagingArea.forToken(token)
            val s = Files.walk(dir)
            try s.iterator().asScala.filter(Files.isRegularFile(_)).toList
            finally s.close()
        }

        TenantContext.set(budgetEnv(1))
        try {
            val c = intercept[DatrisException] {
                StagingArea.withToken("pickup-budget-csv") {
                    ai.datris.util.DataUtil.read(List("s3://raw/big.csv"), opener(Map("s3://raw/big.csv" -> csv.toString)), 1L, csvConfig, su)
                }
            }
            assert(c.getMessage.contains(StagingArea.PayloadBudgetEnvVar + " = 1 MB"), s"got: ${c.getMessage}")
            assert(c.getMessage.contains("disk budget"), s"got: ${c.getMessage}")
            StagingArea.delete("pickup-budget-csv")
            assert(filesIn("pickup-budget-csv").isEmpty, "the run dir is reclaimed like FileNotifier's catch does")

            val j = intercept[DatrisException] {
                StagingArea.withToken("pickup-budget-json") {
                    ai.datris.util.DataUtil.read(List("s3://raw/big.json"), opener(Map("s3://raw/big.json" -> json.toString)), 1L, jsonConfig, su)
                }
            }
            assert(j.getMessage.contains(StagingArea.PayloadBudgetEnvVar), s"got: ${j.getMessage}")
            assert(j.getMessage.contains("disk budget"), s"got: ${j.getMessage}")
            StagingArea.delete("pickup-budget-json")
            assert(filesIn("pickup-budget-json").isEmpty)
        } finally {
            StagingArea.delete("pickup-budget-csv")
            StagingArea.delete("pickup-budget-json")
            TenantContext.set(testEnv)
        }
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

    // ---- Story: taps survive large sources (plans/stories/tap-large-sources.md), Step 6 ----
    //
    //  The CSV branch validates the header line with commons-csv BEFORE
    //  `DataUtil.evolveSchema`: exactly one record, ≥1 column, every column
    //  non-blank after trim; otherwise the parser's message is thrown as a
    //  `DatrisException` and `PipelineConfigIO.write` is never reached. Today
    //  the raw header is split on the delimiter, evolveSchema adds the garbage
    //  as new columns (writing the config), and only then does the parser fail.
    //
    //  This spec's environment has `mongoDbConfig = null`, so any write attempt
    //  surfaces as a non-DatrisException (or a Mongo/NullPointer message) —
    //  that is how "write is never called" is observable here. The junk inputs
    //  all carry a quote-led token followed by a non-delimiter, which
    //  commons-csv rejects with "invalid char between encapsulated token and
    //  delimiter"; a junk line that happens to be valid CSV (e.g. `%PDF-1.4`)
    //  is out of the guard's reach by design.

    private def assertHeaderRejected(e: DatrisException, label: String): Unit = {
        val msg = e.getMessage
        val lower = msg.toLowerCase
        assert(!lower.contains("no data rows found"), s"$label: must fail on the header, not on the row count: $msg")
        assert(
            lower.contains("encapsulated") || lower.contains("invalid char") || lower.contains("(line 1)"),
            s"$label: the parser's message must surface: $msg"
        )
        assert(
            !lower.contains("mongo") && !lower.contains("nullpointer") && !lower.contains("schema evolution"),
            s"$label: schema evolution must not have run: $msg"
        )
    }

    test("a CSV pipeline fed a JSON array / JSON object / binary junk as line 1 fails with the parse error and PipelineConfigIO.write is never called") {
        val jsonArrayFirst = "[\"id\",\"amount\"]\n[1,5]\n[2,9]\n"
        val jsonObjectFirst = "{\"id\":1,\"amount\":5}\n{\"id\":2,\"amount\":9}\n"
        val binaryJunk = Array[Byte](0x22, 0x00, 0x01, 0x22, 0x02, 0x03, 0x7f.toByte, 0x0a, '1', ',', '2', 0x0a)

        val a = intercept[DatrisException](StagingArea.withToken("junk-array")(stage(jsonArrayFirst, csvConfig)))
        StagingArea.delete("junk-array")
        assertHeaderRejected(a, "JSON array line 1")

        val o = intercept[DatrisException](StagingArea.withToken("junk-object")(stage(jsonObjectFirst, csvConfig)))
        StagingArea.delete("junk-object")
        assertHeaderRejected(o, "JSON object line 1")

        val b = intercept[DatrisException](StagingArea.withToken("junk-binary")(stage(binaryJunk, csvConfig)))
        StagingArea.delete("junk-binary")
        assertHeaderRejected(b, "binary junk line 1")

        // The pipeline's schema and version are untouched.
        assert(csvConfig.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", "amount"), "schema untouched")
        assert(csvConfig.source.schemaProperties.schemaVersion == config(FileAttributes(csvAttributes = CsvAttributes())).source.schemaProperties.schemaVersion)
    }

    test("a CSV header with a trailing delimiter (Excel / Sheets export) still lands with the trailing empty dropped") {
        val (data, resolved) = StagingArea.withToken("trailing-delim")(stage("id,amount,\n1,5,\n2,9,\n", csvConfig))
        StagingArea.delete("trailing-delim")
        assert(data.header == List("id", "amount"), "trailing empty column is dropped as before: " + data.header)
        assert(resolved.source.schemaProperties.schemaVersion == csvConfig.source.schemaProperties.schemaVersion, "no schema evolution")
    }

    test("a CSV header with a blank column name is rejected before schema evolution") {
        // `id,,amount` parses as three columns, one of them "" — evolveSchema
        // would add "" as a field. Same guard, same outcome: no write.
        val e = intercept[DatrisException](StagingArea.withToken("junk-blank-col")(stage("id,,amount\n1,x,5\n", csvConfig)))
        StagingArea.delete("junk-blank-col")
        val lower = e.getMessage.toLowerCase
        assert(!lower.contains("no data rows found"), e.getMessage)
        assert(!lower.contains("mongo") && !lower.contains("nullpointer"), "schema evolution must not have run: " + e.getMessage)
    }

    test("DataUtil.read: an object-store CSV pickup whose line 1 is a JSON array fails with the parse error and never evolves the schema") {
        val su = new CapturingStatusUtil
        val e = intercept[DatrisException] {
            StagingArea.withToken("pickup-junk") {
                ai.datris.util.DataUtil.read(
                    List("s3://raw/orders/junk.csv"),
                    opener(Map("s3://raw/orders/junk.csv" -> "[\"id\",\"amount\"]\n[1,5]\n")),
                    24L,
                    csvConfig,
                    su
                )
            }
        }
        StagingArea.delete("pickup-junk")
        assertHeaderRejected(e, "object-store pickup")
        assert(!su.events.exists(_._2.contains("Schema evolution")), "no schema-evolution status event may be emitted: " + su.events)
    }

    // Fix: a pipeline whose csvAttributes omits `delimiter` arrives with null
    // through Spring's Jackson mapper (Scala default args ignored) and used to
    // NPE in CSVFormat$Builder.setDelimiter.
    private val nullDelimiterCsvConfig = config(FileAttributes(csvAttributes = CsvAttributes(delimiter = null)))

    test("validatedCsvHeader with null delimiter defaults to comma") {
        assert(ai.datris.util.DataUtil.validatedCsvHeader("ID,Amount", null, "orders") == List("id", "amount"))
        assert(ai.datris.util.DataUtil.validatedCsvHeader("ID,Amount", "", "orders") == List("id", "amount"))
        assert(ai.datris.util.DataUtil.validatedCsvHeader("ID|Amount", "|", "orders") == List("id", "amount"), "an explicit delimiter is still honoured")
    }

    test("read with csvAttributes lacking delimiter parses comma-separated rows") {
        val files = Map("s3://raw/orders/nodelim.csv" -> "ID,Amount\n1,5\n2,\"x,y\"\n")
        val (data, _) = StagingArea.withToken("pickup-nodelim") {
            ai.datris.util.DataUtil.read(List("s3://raw/orders/nodelim.csv"), opener(files), 25L, nullDelimiterCsvConfig, new CapturingStatusUtil)
        }
        try {
            assert(data.staged.format == StagedFormat.Delimited(","))
            assert(data.header == List("id", "amount"))
            assert(data.rowIterator().toList == List("1,5", "2,\"x,y\""))
        } finally StagingArea.delete("pickup-nodelim")
        assert(nullDelimiterCsvConfig.source.fileAttributes.csvAttributes.delimiter == null, "the stored config is not rewritten")
    }

    test("upload (stageData) with csvAttributes lacking delimiter parses comma-separated rows") {
        val (data, _) = stage("Amount,ID\n5,1\n9,2\n", nullDelimiterCsvConfig)
        assert(data.staged.format == StagedFormat.Delimited(","))
        assert(data.rowIterator().toList == List("1,5", "2,9"))
    }

    test("Jackson (ParameterNamesModule) body without delimiter: delimiter is null, effectiveDelimiter is comma") {
        // Spring Boot's @RequestBody mapper: ParameterNamesModule, no
        // DefaultScalaModule, so Scala default arguments are NOT applied.
        val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val fa = mapper.readValue("""{"csvAttributes":{"header":true}}""", classOf[FileAttributes])
        assert(fa.csvAttributes.delimiter == null, s"Jackson: ${fa.csvAttributes}")
        assert(fa.csvAttributes.effectiveDelimiter == ",")
        assert(CsvAttributes(delimiter = "|").effectiveDelimiter == "|")
        assert(CsvAttributes.delimiterOf(null) == ",")
        assert(CsvAttributes.delimiterOf(config(FileAttributes(jsonAttributes = JsonAttributes()))) == ",")
        assert(CsvAttributes.delimiterOf(config(FileAttributes(csvAttributes = CsvAttributes(delimiter = "\t")))) == "\t")
        // The resolver is not a stored property.
        assert(!mapper.writeValueAsString(fa.csvAttributes).contains("effectiveDelimiter"))
        assert(!new com.google.gson.Gson().toJson(fa.csvAttributes).contains("effectiveDelimiter"))
    }

    // ---- Field protection 10: schema evolution under the HIPAA Safe Harbor preset ----
    //  (plans/stories/field-protection-10-safe-harbor-preset-server.md, Step 4)
    //
    //  Seam this spec pins: `evolveSchema` writes the evolved config through
    //  `PipelineConfigIO.write`, which needs Mongo, so the write is injected
    //  with the production default (existing callers compile unchanged):
    //
    //  {{{
    //  def evolveSchema(sourceColumns: List[String], config: PipelineConfig, statusUtil: StatusUtil,
    //                   persist: PipelineConfig => Unit = PipelineConfigIO.write)
    //      : (PipelineConfig, List[String], List[String], List[String])
    //  }}}
    //
    //  With `protection.preset` set, each new source column goes through
    //  `ProtectionPreset.classify`; a classified one is added to the SOURCE
    //  schema WITH its (clamped) policy and an info line
    //  "Preset hipaa-safe-harbor: new column '<c>' protected as <class> (<method>)";
    //  an unclassified one is added as today with a warn line
    //  "new column '<c>' is not recognised by the preset and is not protected".
    //  The persisted config is the returned one (protect survives the write).

    private class LevelStatusUtil extends ai.datris.util.StatusUtil {
        val events = scala.collection.mutable.ListBuffer[(String, String)]()
        override def info(state: String, description: String): Unit = events += (("info", description))
        override def warn(state: String, description: String): Unit = events += (("warn", description))
        override def error(state: String, description: String): Unit = events += (("error", description))
        def lines(level: String): List[String] = events.filter(_._1 == level).map(_._2).toList
    }

    private def evolveConfig(protection: ProtectionConfig): PipelineConfig =
        PipelineConfig(
            name = "patients",
            source = Source(
                schemaProperties = SchemaProperties("db", fields("mrn", "visit_count")),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes())
            ),
            destination = Destination(schemaProperties = SchemaProperties("db", fields("mrn", "visit_count"))),
            protection = protection
        )

    private def presetProtection: ProtectionConfig = ProtectionConfig(preset = "hipaa-safe-harbor")

    private def sourceField(cfg: PipelineConfig, name: String): Option[SchemaField] =
        cfg.source.schemaProperties.fields.asScala.find(_.name.equalsIgnoreCase(name))

    test("a new identifier column on a preset pipeline is added with its policy and protected in the same run") {
        val su = new LevelStatusUtil
        val persisted = scala.collection.mutable.ListBuffer[PipelineConfig]()
        val (evolved, schemaColumns, present, _) =
            ai.datris.util.DataUtil.evolveSchema(List("mrn", "visit_count", "patient_phone"), evolveConfig(presetProtection), su, c => persisted += c)

        assert(schemaColumns.contains("patient_phone") && present.contains("patient_phone"))
        val f = sourceField(evolved, "patient_phone")
        assert(f.isDefined, s"new column added: ${evolved.source.schemaProperties.fields}")
        assert(f.get.protect != null && f.get.protect.method == "redact", s"phone → redact under the preset, got ${f.get.protect}")

        // Protected in this same run: the returned config is what FieldProtection reads.
        assert(ai.datris.util.FieldProtection.protectedFields(evolved).exists(_.name == "patient_phone"))
        assert(ai.datris.util.FieldProtection.protectValue(f.get.protect, "555-123-4567", "k".getBytes(StandardCharsets.UTF_8)) == "[REDACTED]")

        // The stored config carries protect too.
        assert(persisted.size == 1, s"one write, got ${persisted.size}")
        assert(sourceField(persisted.head, "patient_phone").exists(p => p.protect != null && p.protect.method == "redact"))
        assert(persisted.head.protection != null && persisted.head.protection.preset == "hipaa-safe-harbor", "the preset survives the write")

        val infos = su.lines("info")
        assert(
            infos.exists(l => l.contains("new column 'patient_phone' protected as") && l.contains("redact")),
            s"status lines: ${su.events}"
        )
        assert(su.lines("warn").isEmpty, s"no warning for a recognised column: ${su.events}")
    }

    test("a new unrecognised column is added unprotected with a warning") {
        val su = new LevelStatusUtil
        val persisted = scala.collection.mutable.ListBuffer[PipelineConfig]()
        val (evolved, _, _, _) =
            ai.datris.util.DataUtil.evolveSchema(List("mrn", "visit_count", "favourite_colour"), evolveConfig(presetProtection), su, c => persisted += c)

        val f = sourceField(evolved, "favourite_colour")
        assert(f.isDefined && f.get.protect == null, s"added as is: $f")
        assert(
            su.lines("warn").exists(_.contains("new column 'favourite_colour' is not recognised by the preset")),
            s"status lines: ${su.events}"
        )
        assert(persisted.size == 1)
    }

    test("pipelines without a preset evolve as before") {
        Seq(null, ProtectionConfig(purgeSource = java.lang.Boolean.FALSE)).foreach { protection =>
            val su = new LevelStatusUtil
            val persisted = scala.collection.mutable.ListBuffer[PipelineConfig]()
            val cfg = evolveConfig(protection)
            val (evolved, schemaColumns, _, _) =
                ai.datris.util.DataUtil.evolveSchema(List("mrn", "visit_count", "patient_phone"), cfg, su, c => persisted += c)

            assert(schemaColumns == List("mrn", "visit_count", "patient_phone"))
            assert(sourceField(evolved, "patient_phone").exists(_.protect == null), s"no preset → unprotected ($protection)")
            assert(evolved.destination.schemaProperties.fields.asScala.exists(_.name == "patient_phone"))
            assert(evolved.source.schemaProperties.schemaVersion == cfg.source.schemaProperties.schemaVersion + 1)
            assert(persisted.size == 1)
            assert(!su.events.exists(_._2.toLowerCase.contains("preset")), s"no preset lines: ${su.events}")
            assert(su.lines("warn").isEmpty, s"no warnings: ${su.events}")
        }
    }

    // ---- Story 10 review round 1: presetExempt and the clamp branch on evolution ----

    test("a new column listed under presetExempt is added unprotected with an info line") {
        val su = new LevelStatusUtil
        val persisted = scala.collection.mutable.ListBuffer[PipelineConfig]()
        val protection = ProtectionConfig(preset = "hipaa-safe-harbor", presetExempt = java.util.Arrays.asList("Admission_Date"))
        val (evolved, _, _, _) =
            ai.datris.util.DataUtil.evolveSchema(List("mrn", "visit_count", "admission_date"), evolveConfig(protection), su, c => persisted += c)
        assert(sourceField(evolved, "admission_date").exists(_.protect == null), "exempt → not protected")
        assert(su.lines("info").exists(l => l.contains("new column 'admission_date'") && l.contains("presetExempt")), s"lines: ${su.events}")
        assert(su.lines("warn").isEmpty, s"no warning for an exempt column: ${su.events}")
        assert(persisted.size == 1)
    }

    test("a recognised new column the clamps refuse lands as is with a warning naming the consequence") {
        val su = new LevelStatusUtil
        val persisted = scala.collection.mutable.ListBuffer[PipelineConfig]()
        val base = evolveConfig(presetProtection)
        val cfg = base.copy(destination =
            base.destination.copy(database = Database(dbName = "datris", schema = "public", table = "patients", keyFields = java.util.Arrays.asList("email")))
        )
        val (evolved, _, _, _) = ai.datris.util.DataUtil.evolveSchema(List("mrn", "visit_count", "email"), cfg, su, c => persisted += c)
        assert(sourceField(evolved, "email").exists(_.protect == null), "key column: redact is clamped away")
        val warn = su.lines("warn")
        assert(
            warn.exists(l =>
                l.contains("new column 'email' looks like email but is not protected") &&
                    l.contains(ai.datris.util.FieldProtectionAdvisor.KeyColumnReason) &&
                    l.contains(
                        "it lands as is, and the next save of this pipeline will be refused until it is protected or listed under protection.presetExempt"
                    )
            ),
            s"lines: ${su.events}"
        )
    }

    // ---- Schema evolution: a new source column the destination already declares ----
    //  (plans/stories/schema-evolution-dest-duplicate.md)
    //
    //  The column joins the SOURCE schema as usual; the destination keeps its
    //  single declared entry (type untouched) and the run logs
    //  "Schema evolution: column '<c>' is already in the destination schema (<type>); kept as declared".

    private def declaredDestConfig: PipelineConfig =
        PipelineConfig(
            name = "e2e_dup",
            source = Source(
                schemaProperties = SchemaProperties("db", fields("id", "name")),
                fileAttributes = FileAttributes(csvAttributes = CsvAttributes())
            ),
            destination = Destination(schemaProperties =
                SchemaProperties(
                    "db",
                    new java.util.ArrayList[SchemaField](
                        java.util.Arrays.asList(SchemaField("id", "string"), SchemaField("name", "string"), SchemaField("admit_date", "date"))
                    )
                )
            )
        )

    test("a new source column already declared in the destination joins the source schema only") {
        val su = new LevelStatusUtil
        val persisted = scala.collection.mutable.ListBuffer[PipelineConfig]()
        val cfg = declaredDestConfig
        val (evolved, schemaColumns, present, _) =
            ai.datris.util.DataUtil.evolveSchema(List("id", "name", "admit_date"), cfg, su, c => persisted += c)

        assert(schemaColumns == List("id", "name", "admit_date"), schemaColumns)
        assert(present.contains("admit_date"))
        assert(sourceField(evolved, "admit_date").isDefined, s"source gains admit_date: ${evolved.source.schemaProperties.fields}")

        Seq(evolved, persisted.head).foreach { c =>
            val dest = c.destination.schemaProperties.fields.asScala.toList
            assert(dest.count(_.name.equalsIgnoreCase("admit_date")) == 1, s"destination lists admit_date once: $dest")
            assert(dest.find(_.name.equalsIgnoreCase("admit_date")).get.`type` == "date", s"declared type kept: $dest")
            assert(dest.map(_.name) == List("id", "name", "admit_date"), s"destination unchanged in order: $dest")
        }
        assert(persisted.size == 1, s"one write, got ${persisted.size}")
    }

    test("the status line says the destination entry was kept") {
        val su = new LevelStatusUtil
        ai.datris.util.DataUtil.evolveSchema(List("id", "name", "Admit_Date"), declaredDestConfig, su, _ => ())
        val all = su.events.map(_._2)
        assert(
            all.exists(l => l.toLowerCase.contains("'admit_date'") && l.contains("is already in the destination schema") && l.contains("(date)") && l.contains("kept as declared")),
            s"status lines: ${su.events}"
        )
    }

    test("a pipeline without a destination schema evolves as before") {
        val base = declaredDestConfig
        Seq(base.copy(destination = Destination()), base.copy(destination = null)).foreach { cfg =>
            val su = new LevelStatusUtil
            val persisted = scala.collection.mutable.ListBuffer[PipelineConfig]()
            val (evolved, schemaColumns, _, _) =
                ai.datris.util.DataUtil.evolveSchema(List("id", "name", "admit_date"), cfg, su, c => persisted += c)
            assert(schemaColumns == List("id", "name", "admit_date"))
            assert(sourceField(evolved, "admit_date").exists(_.`type` == "string"))
            assert(evolved.source.schemaProperties.schemaVersion == cfg.source.schemaProperties.schemaVersion + 1)
            assert(evolved.destination == cfg.destination, "destination untouched")
            assert(persisted.size == 1)
            assert(!su.events.exists(_._2.contains("already in the destination schema")), s"lines: ${su.events}")
        }
        // A destination column not yet declared is still appended, as before.
        val su = new LevelStatusUtil
        val (evolved, _, _, _) = ai.datris.util.DataUtil.evolveSchema(List("id", "name", "discharge_date"), base, su, _ => ())
        assert(evolved.destination.schemaProperties.fields.asScala.map(_.name).toList == List("id", "name", "admit_date", "discharge_date"))
    }
}
