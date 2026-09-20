package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.controller.RestEndpointRunner
import ai.datris.model._
import com.google.gson.{Gson, JsonParser}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** Intermediate-stage streaming (plans/stories/streaming-pipeline-phase3.md).
  *
  *  The stages between ingest and the loaders stop pulling the run payload
  *  into heap. Seams this spec relies on (Step 1 of the story):
  *
  *  {{{
  *  object PayloadStager {
  *      def stageRowIterator(stage: String, rows: Iterator[String], delimiter: String): StagedPayload  // writes + counts
  *      def adopt(stage: String, path: java.nio.file.Path, format: StagedFormat): StagedPayload      // move into the run dir, count with StagedRows
  *  }
  *  case class Data {
  *      def withStaged(staged: StagedPayload): Data
  *      def materializeFor(feature: String): <any>   // throws the named DatrisException above the cap
  *  }
  *  object CodeGenRuleEvaluator { private[util] def stageInput(data: Data): java.nio.file.Path }   // the file handed to the DQ script as sys.argv[1]
  *  object CodeGenTransformationEvaluator {
  *      private[datris] def decideHeader(first: String, inputHeader: List[String], inputRowCount: Long,
  *                                       recordCount: Long, firstRecurs: Boolean, delimiter: String): Boolean
  *  }
  *  }}}
  *
  *  The named-error wording (story Step 1, one line):
  *  `<feature> reads the whole payload into memory (<bytes> bytes) and PIPELINE_MATERIALIZE_MAX_MB is <cap> MB.
  *   Raise it for this install, or use a CodeGen rule/transformation, which streams.`
  *
  *  `SpyData` (same idea as LoaderStreamingSpec) records every call that would
  *  materialize the payload, so "no `staged payload materialized by` line" is
  *  observable without reading the log. The 1,000,000-row case is meant to run
  *  with `DATRIS_TEST_XMX=512m`.
  */
class IntermediateStageStreamingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("intermediate-streaming-spec")
    private val gson = new Gson
    private val TOKEN = "job-token-p3"

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

    /** Run `body` with the materialize cap set to `capMB` and every staged file scoped to TOKEN. */
    private def withCap[T](capMB: Int)(body: => T): T = {
        TenantContext.set(env(capMB))
        try StagingArea.withToken(TOKEN)(body)
        finally TenantContext.clear()
    }

    // ---- local REST endpoint -------------------------------------------------

    private var server: HttpServer = _
    private def url(path: String): String = "http://127.0.0.1:" + server.getAddress.getPort + path
    private val calls = new AtomicInteger(0)
    private val rowsPerCall = new ListBuffer[Int]()
    private val requestBodies = new ListBuffer[String]()

    private def reset(): Unit = { calls.set(0); rowsPerCall.clear(); requestBodies.clear() }

    /** Row-function endpoint: echoes rows back; removes any row whose id is a multiple of 10 (row mode only). */
    private val rowFunctionHandler = new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
            val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
            calls.incrementAndGet()
            requestBodies.synchronized(requestBodies += body)
            val req = JsonParser.parseString(body).getAsJsonObject
            val resp = new com.google.gson.JsonObject
            resp.addProperty("status", "success")
            if (req.has("row")) {
                rowsPerCall.synchronized(rowsPerCall += 1)
                val row = req.getAsJsonObject("row")
                if (row.get("id").getAsString.toInt % 10 == 0) resp.add("row", com.google.gson.JsonNull.INSTANCE) else resp.add("row", row)
            } else {
                val rows = req.getAsJsonArray("rows")
                rowsPerCall.synchronized(rowsPerCall += rows.size())
                resp.add("rows", rows)
            }
            reply(exchange, gson.toJson(resp))
        }
    }

    /** Preprocessor endpoint: returns `{"data":{"rows":[...]}}` with every name upper-cased. */
    private val preprocessorHandler = new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
            val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
            calls.incrementAndGet()
            requestBodies.synchronized(requestBodies += body)
            val req = JsonParser.parseString(body).getAsJsonObject
            val rows = req.getAsJsonObject("data").getAsJsonArray("rows")
            rowsPerCall.synchronized(rowsPerCall += rows.size())
            val out = new com.google.gson.JsonArray
            rows.asScala.foreach(r => out.add(r.getAsString.toUpperCase))
            val data = new com.google.gson.JsonObject
            data.add("rows", out)
            val resp = new com.google.gson.JsonObject
            resp.add("data", data)
            reply(exchange, gson.toJson(resp))
        }
    }

    /** Preprocessor endpoint that only rewrites the header: `{"data":{"header":[...]}}`, no rows. */
    private val headerOnlyHandler = new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
            val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
            calls.incrementAndGet()
            val req = JsonParser.parseString(body).getAsJsonObject
            rowsPerCall.synchronized(rowsPerCall += req.getAsJsonObject("data").getAsJsonArray("rows").size())
            val header = new com.google.gson.JsonArray
            header.add("ID"); header.add("NAME")
            val data = new com.google.gson.JsonObject
            data.add("header", header)
            val resp = new com.google.gson.JsonObject
            resp.add("data", data)
            reply(exchange, gson.toJson(resp))
        }
    }

    private def reply(exchange: HttpExchange, json: String): Unit = {
        val bytes = json.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    }

    override def beforeAll(): Unit = {
        // Loopback is blocked by SsrfGuard by default; this suite tests the row
        // function wire contract, not SSRF policy.
        System.setProperty("datris.allowPrivateEgress", "true")
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rowfn", rowFunctionHandler)
        server.createContext("/pre", preprocessorHandler)
        server.createContext("/pre-header", headerOnlyHandler)
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
        server.start()
    }

    override def afterAll(): Unit = {
        TenantContext.clear()
        System.clearProperty("datris.allowPrivateEgress")
        if (server != null) server.stop(0)
    }

    // ---- fixtures ------------------------------------------------------------

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += (("info", state, description))
        override def warn(state: String, description: String): Unit = messages += (("warning", state, description))
        override def error(state: String, description: String): Unit = messages += (("error", state, description))
        override def scratchResult(result: ScratchResult): Unit = ()
        def descriptions: List[String] = messages.map(_._3).toList
    }

    /** A Data that records every call that would read the whole payload into heap. */
    private class SpyData(base: Data) extends Data(base.size, base.header, base.headerWithSchema, base.staged, base.rawBytes) {
        val materialized = new ListBuffer[String]()
        override def rows: List[String] = {
            staged.format match {
                case StagedFormat.Delimited(_) if !staged.isEmpty => materialized += "rows"
                case _ => ()
            }
            super.rows
        }
        override def rawData: String = {
            staged.format match {
                case StagedFormat.NdJson | StagedFormat.Xml | StagedFormat.Text if !staged.isEmpty => materialized += "rawData"
                case _ => ()
            }
            super.rawData
        }
    }

    private def fields(names: String*): java.util.List[SchemaField] =
        new java.util.ArrayList[SchemaField](names.map(n => SchemaField(n, "string")).asJava)

    private def delimitedData(rows: List[String], header: List[String]): Data =
        Data(rows.map(_.length.toLong + 1L).sum, header, header.map(SchemaField(_, "string")), rows, null)

    private def jsonData(raw: String): Data = Data(raw.length.toLong, null, null, null, raw)

    private def rowsOf(data: Data): List[String] = {
        val it = data.rowIterator()
        try it.toList
        finally it.close()
    }

    private def ctx(cfg: PipelineConfig, data: Data, status: StatusUtil = new RecordingStatusUtil): JobContext =
        JobContext(
            pipelineToken = TOKEN,
            metadata = PipelineMetadata(cfg.name, "rows.csv", "/tmp/rows.csv", "pub-1", bulkUpload = false),
            data = data,
            config = cfg,
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = status
        )

    private def csvConfig(name: String, cols: List[String]): PipelineConfig =
        PipelineConfig(
            name = name,
            version = 7,
            source = Source(schemaProperties = SchemaProperties("db", fields(cols: _*)), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(schemaProperties = SchemaProperties("db", fields(cols: _*)), database = Database(usePostgres = true, table = "t"))
        )

    /** The one-line wording the story fixes for a gated feature. */
    private def assertNamedCapError(e: DatrisException, featureKeywords: String*): Unit = {
        val m = e.getMessage
        assert(m.contains("reads the whole payload into memory ("), s"message must use the story wording, got: $m")
        assert(m.contains("PIPELINE_MATERIALIZE_MAX_MB is 1 MB"), s"message must name the cap env var and its value, got: $m")
        assert(m.contains("Raise it for this install"), s"message must tell the operator what to do, got: $m")
        assert(!m.contains("ai.datris."), s"message must name the feature, not a stack frame, got: $m")
        assert(
            featureKeywords.exists(k => m.toLowerCase.contains(k.toLowerCase)),
            s"message must name the feature (${featureKeywords.mkString(" / ")}), got: $m"
        )
    }

    /** ~2.2 MB of delimited rows: comfortably over a 1 MB cap. */
    private def overCapRows: List[String] = (1 to 40000).map(i => i + "," + ("x" * 50)).toList

    // ==========================================================================
    // Step 1 seams
    // ==========================================================================

    test("PayloadStager.stageRowIterator writes the rows and counts them without a List") {
        withCap(256) {
            val rows = List("1,a", "2,\"b\nc\"", "3,d")
            val staged = PayloadStager.stageRowIterator("stamp", rows.iterator, ",")
            assert(staged.format == StagedFormat.Delimited(","))
            assert(staged.rowCount == 3)
            assert(Files.isRegularFile(java.nio.file.Paths.get(staged.path)))
            assert(java.nio.file.Paths.get(staged.path).startsWith(StagingArea.forToken(TOKEN)), "must land in the run's staging dir")
            assert(new String(Files.readAllBytes(java.nio.file.Paths.get(staged.path)), StandardCharsets.UTF_8) == rows.mkString("\n"))
            assert(staged.bytes == Files.size(java.nio.file.Paths.get(staged.path)))
            assert(PayloadStager.stageRowIterator("empty", Iterator.empty, ",").rowCount == 0)
        }
    }

    test("Data.withStaged swaps the payload and keeps header, schema and size") {
        withCap(256) {
            val original = delimitedData(List("1,a", "2,b"), List("id", "v"))
            val replacement = PayloadStager.stageRows("other", List("9,z"), ",")
            val swapped = original.withStaged(replacement)
            assert(swapped.staged eq replacement)
            assert(swapped.rowCount == 1 && rowsOf(swapped) == List("9,z"))
            assert(swapped.header == original.header && swapped.headerWithSchema == original.headerWithSchema && swapped.size == original.size)
            assert(original.rowCount == 2, "the original Data is untouched")
        }
    }

    test("Data.materializeFor names the feature and the cap with the story's one-line wording; below the cap it is silent") {
        withCap(1) {
            val big = delimitedData(overCapRows, List("id", "v"))
            assert(big.staged.bytes > 1L * 1024 * 1024)
            val e = intercept[DatrisException](big.materializeFor("deduplicate"))
            assert(
                e.getMessage ==
                    "deduplicate reads the whole payload into memory (" + big.staged.bytes + " bytes) and PIPELINE_MATERIALIZE_MAX_MB is 1 MB. " +
                    "Raise it for this install, or use a CodeGen rule/transformation, which streams.",
                s"got: ${e.getMessage}"
            )

            val small = delimitedData(List("1,a"), List("id", "v"))
            small.materializeFor("deduplicate") // must not throw
        }
    }

    // ==========================================================================
    // Acceptance 1: 1,000,000-row provenance stamp under DATRIS_TEST_XMX=512m
    // ==========================================================================

    test("a 1,000,000-row staged file stamped by ProvenanceStamper gives rowCount == 1000000 with every row carrying the suffix") {
        withCap(256) {
            sys.env.get("DATRIS_TEST_XMX").foreach { xmx =>
                val max = Runtime.getRuntime.maxMemory
                assert(max <= 600L * 1024 * 1024, s"DATRIS_TEST_XMX=$xmx was set but the forked test JVM has maxMemory=$max")
            }
            val rowCount = 1000000L
            val pad = "x" * 90
            val format = StagedFormat.Delimited(",")
            val (path, writer) = StagingArea.newWriter("million", format)
            try {
                var i = 1L
                while (i <= rowCount) {
                    if (i > 1) writer.write("\n")
                    writer.write(i.toString); writer.write(","); writer.write(pad); writer.write(","); writer.write(pad); writer.write(","); writer.write(pad)
                    i += 1
                }
            } finally writer.close()
            val bytes = Files.size(path)
            // Over the default 256 MB cap: a materializing stamper cannot even start.
            assert(bytes > 256L * 1024 * 1024, s"fixture must exceed the default materialize cap, got $bytes bytes")

            val header = List("id", "a", "b", "c")
            val cfg = csvConfig("million", header).copy(provenance = ProvenanceConfig(stamp = true))
            val data = new SpyData(new Data(bytes, header, header.map(SchemaField(_, "string")), StagedPayload(path.toString, format, rowCount, bytes), null))
            val status = new RecordingStatusUtil

            val stamped = ProvenanceStamper.stamp(ctx(cfg, data, status))

            assert(stamped.data.header == header ++ ProvenanceStamper.AllFields, s"stamping must not be skipped; status: ${status.descriptions}")
            assert(stamped.data.rowCount == rowCount)
            assert(data.materialized.isEmpty, s"ProvenanceStamper materialized the payload via ${data.materialized}")
            assert(stamped.data.staged.path != path.toString, "stamping writes a new staged file")

            val it = stamped.data.rowIterator()
            try {
                val first = it.next()
                val firstOriginal = "1," + pad + "," + pad + "," + pad
                assert(first.startsWith(firstOriginal + ","), s"row 1 must be the original row plus the suffix, got ${first.take(120)}")
                val suffix = first.substring(firstOriginal.length)
                val cols = suffix.split(",", -1)
                assert(cols.length == 1 + ProvenanceStamper.AllFields.size, s"suffix must hold the six provenance columns, got $suffix")
                assert(cols(1) == TOKEN) // _datris_run_id
                assert(cols(3) == "7") // _datris_config_version
                var n = 1L
                while (it.hasNext) {
                    val row = it.next()
                    n += 1
                    if (!row.endsWith(suffix) || !row.startsWith(n + ","))
                        fail(s"row $n does not carry the constant suffix: ${row.take(120)}")
                }
                assert(n == rowCount)
            } finally it.close()
        }
    }

    // ==========================================================================
    // Acceptance 2: CodeGen assembly — adopt, DQ input file, decideHeader
    // ==========================================================================

    test("PayloadStager.adopt on a CodeGen output whose value holds a quoted embedded newline yields one record, in the run's staging dir") {
        withCap(256) {
            // A script's output file lives outside the staging area (Files.createTempFile today).
            val out = Files.createTempFile("tx_output_", ".csv")
            Files.write(out, "1,\"line one\nline two\"".getBytes(StandardCharsets.UTF_8))
            val size = Files.size(out)

            val adopted = PayloadStager.adopt("transform", out, StagedFormat.Delimited(","))

            assert(adopted.rowCount == 1, s"a quoted embedded newline is one record, got ${adopted.rowCount}")
            assert(adopted.format == StagedFormat.Delimited(","))
            assert(adopted.bytes == size)
            val adoptedPath = java.nio.file.Paths.get(adopted.path)
            assert(adoptedPath.startsWith(StagingArea.forToken(TOKEN)), s"adopted file $adoptedPath must be in the run's staging dir")
            assert(Files.isRegularFile(adoptedPath))
            assert(!Files.exists(out), "adopt moves the script's output; the original path must be gone")
            val data = new Data(size, List("id", "v"), List(SchemaField("id", "string"), SchemaField("v", "string")), adopted, null)
            assert(rowsOf(data) == List("1,\"line one\nline two\""))

            // Three records, the middle one with the embedded newline: still three.
            val out3 = Files.createTempFile("tx_output_", ".csv")
            Files.write(out3, "1,a\n2,\"b\nc\"\n3,d".getBytes(StandardCharsets.UTF_8))
            val adopted3 = PayloadStager.adopt("transform", out3, StagedFormat.Delimited(","))
            assert(adopted3.rowCount == 3)
        }
    }

    test("the CodeGen DQ input file is byte-equal to today's (headerLine +: rows).mkString(\"\\n\") for CSV") {
        withCap(256) {
            val header = List("id", "v")
            val rows = List("1,a", "2,\"b\nc\"", "3,\"say \"\"hi\"\"\"", "4,")
            val data = new SpyData(delimitedData(rows, header))

            val input = CodeGenRuleEvaluator.stageInput(data)

            val expected = (header.mkString(",") +: rows).mkString("\n").getBytes(StandardCharsets.UTF_8)
            assert(Files.readAllBytes(input).sameElements(expected), s"input file differs:\n${new String(Files.readAllBytes(input), StandardCharsets.UTF_8)}")
            assert(input.startsWith(StagingArea.forToken(TOKEN)), s"DQ input $input must be staged in the run's dir so JobRunner's finally reclaims it")
            assert(data.materialized.isEmpty, s"the DQ input was assembled from the heap via ${data.materialized}")
        }
    }

    test("the CodeGen DQ input file is a valid JSON array for an arraySource payload") {
        withCap(256) {
            val raw = """[{"a":1,"s":"<x>&"},{"a":2,"s":null}]"""
            val data = new SpyData(jsonData(raw))
            assert(data.staged.format == StagedFormat.NdJson && data.staged.arraySource && data.rowCount == 2)

            val input = CodeGenRuleEvaluator.stageInput(data)

            val parsed = JsonParser.parseString(new String(Files.readAllBytes(input), StandardCharsets.UTF_8))
            assert(parsed.isJsonArray, "an arraySource payload must reach the script as one JSON array")
            assert(parsed.getAsJsonArray.size() == 2)
            assert(parsed == JsonParser.parseString(raw), "same elements, same order, nulls and <>& preserved")
            assert(input.startsWith(StagingArea.forToken(TOKEN)))
            assert(data.materialized.isEmpty, s"the DQ input was assembled from the heap via ${data.materialized}")
        }
    }

    test("decideHeader agrees with splitHeader on every existing CodeGenTransformationEvaluatorSpec case") {
        val in = List("id", "first_name", "last_name", "email", "salary")
        val cases: List[(List[String], List[String], Int)] = List(
            (List("id,first_name,last_name,salary,full_name", "1,Ada,Lovelace,1200.5,Ada Lovelace", "2,Alan,Turing,1300,Alan Turing"), in, 2),
            (List("1,Ada,Lovelace,ada@example.com,1200.5", "2,Alan,Turing,alan@example.com,1300"), in, 2),
            (List("Ada,Lovelace", "Ada,Lovelace", "Grace,Hopper"), List("first_name", "last_name"), 5),
            (List("id,first_name,last_name,email,salary", "1,Ada,Lovelace,ada@example.com,1200.5"), in, 3),
            // Numeric first line can never be a header.
            (List("1,2,3,4,5", "6,7,8,9,10"), in, 1),
            // Header-looking first line that recurs as data, count not matching: kept as data.
            (List("a,b", "x,y", "a,b"), List("a", "b"), 5),
            // Same as input header, case-insensitively.
            (List("ID,First_Name,Last_Name,Email,Salary", "1,Ada,Lovelace,a@e.com,1"), in, 1)
        )
        cases.foreach { case (lines, inputHeader, inputRowCount) =>
            val expected = CodeGenTransformationEvaluator.splitHeader(lines, inputHeader, inputRowCount, ",")
            val decided = CodeGenTransformationEvaluator.decideHeader(
                lines.head,
                inputHeader,
                inputRowCount.toLong,
                lines.size.toLong,
                lines.tail.contains(lines.head),
                ","
            )
            assert(decided == expected.headerFromScript, s"decideHeader disagrees with splitHeader for $lines")
        }
    }

    // ==========================================================================
    // Acceptance 3: REST row function — row mode streams, batch mode chunks
    // ==========================================================================

    private def rowFunctionConfig(params: String*): PipelineConfig =
        csvConfig("rest_rows", List("id", "name")).copy(
            transformation = ai.datris.model.Transformation(rowFunctions =
                new java.util.ArrayList[RowFunction](List(RowFunction("restEndpoint", params.toList.asJava)).asJava)
            )
        )

    private val fiveThousand: List[String] = (1 to 5000).map(i => s"$i,name$i").toList

    test("REST row function \"row\" mode over 5,000 rows makes 5,000 calls and stages 5,000 rows less the removed, without materializing") {
        withCap(256) {
            reset()
            val data = new SpyData(delimitedData(fiveThousand, List("id", "name")))
            val status = new RecordingStatusUtil
            val result = new ai.datris.util.Transformation(ctx(rowFunctionConfig(url("/rowfn"), "row"), data, status)).process()

            assert(calls.get() == 5000, s"row mode is one call per row, got ${calls.get()}")
            assert(rowsPerCall.forall(_ == 1))
            // The endpoint removed every 10th row.
            assert(result.data.rowCount == 4500, s"expected 5000 - 500 removed, got ${result.data.rowCount}")
            val rows = rowsOf(result.data)
            assert(rows.size == 4500)
            assert(rows.head == "1,name1" && rows.last == "4999,name4999")
            assert(rows.forall(r => r.split(",")(0).toInt % 10 != 0))
            assert(status.descriptions.exists(_ == "500 rows were removed during the REST endpoint transformation"), status.descriptions.mkString("\n"))
            assert(data.materialized.isEmpty, s"row mode must stream rowIterator(), not materialize via ${data.materialized}")
            // Envelope unchanged: {pipelineName, pipelineToken, row}.
            val first = JsonParser.parseString(requestBodies.head).getAsJsonObject
            assert(first.get("pipelineName").getAsString == "rest_rows" && first.get("pipelineToken").getAsString == TOKEN && first.has("row"))
        }
    }

    test("REST row function \"batch\" with parameters[5] = 500 over 5,000 rows makes 10 calls of 500, stitched in call order") {
        withCap(256) {
            reset()
            val data = new SpyData(delimitedData(fiveThousand, List("id", "name")))
            val status = new RecordingStatusUtil
            val result = new ai.datris.util.Transformation(ctx(rowFunctionConfig(url("/rowfn"), "batch", "30000", "", "", "500"), data, status)).process()

            assert(calls.get() == 10, s"5000 / 500 = 10 calls, got ${calls.get()}")
            assert(rowsPerCall.toList == List.fill(10)(500), s"got $rowsPerCall")
            assert(result.data.rowCount == 5000)
            assert(rowsOf(result.data) == fiveThousand, "responses stitched in call order")
            assert(data.materialized.isEmpty, s"batch mode with a batch size must stream rowIterator(), not materialize via ${data.materialized}")
            // Same envelope on every call: {pipelineName, pipelineToken, rows}.
            requestBodies.foreach { b =>
                val o = JsonParser.parseString(b).getAsJsonObject
                assert(
                    o.get("pipelineName").getAsString == "rest_rows" && o.get("pipelineToken").getAsString == TOKEN && o.getAsJsonArray("rows").size() == 500
                )
            }
        }
    }

    test("REST row function \"batch\" with no batch-size parameter makes exactly 1 call carrying all rows") {
        withCap(256) {
            reset()
            val data = delimitedData(fiveThousand, List("id", "name"))
            val result = new ai.datris.util.Transformation(ctx(rowFunctionConfig(url("/rowfn"), "batch"), data)).process()

            assert(calls.get() == 1, s"no batch size = today's single call, got ${calls.get()}")
            assert(rowsPerCall.toList == List(5000))
            assert(result.data.rowCount == 5000)
            assert(rowsOf(result.data) == fiveThousand)
        }
    }

    // ==========================================================================
    // Acceptance 4: in-heap features above the cap throw the named error; below it, as before
    // ==========================================================================

    test("dedup above the cap throws a DatrisException naming the feature and PIPELINE_MATERIALIZE_MAX_MB") {
        withCap(1) {
            val cfg = csvConfig("dedup", List("id", "v")).copy(transformation = ai.datris.model.Transformation(deduplicate = true))
            val data = delimitedData(overCapRows, List("id", "v"))
            val e = intercept[DatrisException](new ai.datris.util.Transformation(ctx(cfg, data)).process())
            assertNamedCapError(e, "dedup")
        }
    }

    test("dedup below the cap behaves as before") {
        withCap(1) {
            val cfg = csvConfig("dedup", List("id", "v")).copy(transformation = ai.datris.model.Transformation(deduplicate = true))
            val status = new RecordingStatusUtil
            val result = new ai.datris.util.Transformation(ctx(cfg, delimitedData(List("1,a", "2,b", "1,a", "3,c"), List("id", "v")), status)).process()
            assert(result.data.rowCount == 3)
            assert(rowsOf(result.data) == List("1,a", "2,b", "3,c"))
            assert(status.descriptions.contains("1 rows were duplicates and removed"), status.descriptions.mkString("\n"))
        }
    }

    test("a JavaScript row function above the cap throws a DatrisException naming the feature and PIPELINE_MATERIALIZE_MAX_MB") {
        withCap(1) {
            // The gate must fire before the script is fetched from the object
            // store: there is none here (minIOConfig = null), so anything but the
            // named error means the whole payload was about to be read.
            val cfg = csvConfig("js", List("id", "v")).copy(
                transformation = ai.datris.model.Transformation(rowFunctions =
                    new java.util.ArrayList[RowFunction](List(RowFunction("javascript", List("fn.js").asJava)).asJava)
                )
            )
            val data = delimitedData(overCapRows, List("id", "v"))
            val e = intercept[DatrisException](new ai.datris.util.Transformation(ctx(cfg, data)).process())
            assertNamedCapError(e, "javascript")
        }
    }

    test("JSON schema validation above the cap throws a DatrisException naming the feature and PIPELINE_MATERIALIZE_MAX_MB") {
        withCap(1) {
            val raw = (1 to 40000).map(i => s"""{"id":$i,"v":"${"x" * 40}"}""").mkString("[", ",", "]")
            val data = jsonData(raw)
            assert(data.staged.bytes > 1L * 1024 * 1024)
            val cfg = PipelineConfig(
                name = "schema",
                source = Source(schemaProperties = SchemaProperties("db", fields("_json")), fileAttributes = FileAttributes(jsonAttributes = JsonAttributes())),
                dataQuality = ai.datris.model.DataQuality(validationSchema = "orders.schema.json"),
                destination = Destination(schemaProperties = SchemaProperties("db", fields("_json")))
            )
            val e = intercept[DatrisException](new ai.datris.util.DataQuality(ctx(cfg, data)).process())
            assertNamedCapError(e, "schema validation")
        }
    }

    test("a batchSize = 0 REST preprocessor above the cap throws a DatrisException naming the feature and PIPELINE_MATERIALIZE_MAX_MB") {
        withCap(1) {
            reset()
            val endpoint = RestEndpoint(endpoint = url("/pre"), batchSize = 0)
            val cfg = csvConfig("pre", List("id", "v")).copy(preprocessor = endpoint)
            val data = delimitedData(overCapRows, List("id", "v"))
            val e = intercept[DatrisException](new RestEndpointRunner(ctx(cfg, data), endpoint).process())
            assertNamedCapError(e, "preprocessor", "REST")
            assert(calls.get() == 0, "the gate fires before any HTTP call")
        }
    }

    test("a batchSize = 0 REST preprocessor below the cap keeps today's single call and body") {
        withCap(1) {
            reset()
            val rows = List("1,alice", "2,bob", "3,carol")
            val endpoint = RestEndpoint(endpoint = url("/pre"), batchSize = 0)
            val cfg = csvConfig("pre", List("id", "name")).copy(preprocessor = endpoint)
            val data = delimitedData(rows, List("id", "name"))

            val bodies = new RestEndpointRunner(ctx(cfg, data), endpoint).requestBodies().toList
            assert(bodies.size == 1)
            val body = JsonParser.parseString(bodies.head).getAsJsonObject
            assert(!body.has("batch") && !body.has("ofBatches"))
            assert(body.getAsJsonObject("data").getAsJsonArray("rows").asScala.map(_.getAsString).toList == rows)

            val result = new RestEndpointRunner(ctx(cfg, data), endpoint).process()
            assert(calls.get() == 1)
            assert(result.data.rowCount == 3)
            assert(rowsOf(result.data) == rows.map(_.toUpperCase), "the preprocessor's response replaces the payload, as today")
        }
    }

    // Step 6 / E2E proxy: the preprocessor role honours batchSize > 0 by
    // stitching every response's data.rows into one staged file.
    test("a batchSize = 500 REST preprocessor over 5,000 rows makes 10 calls and stitches the responses into one staged payload") {
        withCap(256) {
            reset()
            val endpoint = RestEndpoint(endpoint = url("/pre"), batchSize = 500)
            val cfg = csvConfig("pre", List("id", "name")).copy(preprocessor = endpoint)
            val data = new SpyData(delimitedData(fiveThousand, List("id", "name")))

            val result = new RestEndpointRunner(ctx(cfg, data), endpoint).process()

            assert(calls.get() == 10, s"5000 / 500 = 10 calls, got ${calls.get()}")
            assert(rowsPerCall.toList == List.fill(10)(500))
            assert(result.data.rowCount == 5000)
            assert(rowsOf(result.data) == fiveThousand.map(_.toUpperCase), "every response's rows, in call order")
            assert(result.data.header == List("id", "name"))
            assert(data.materialized.isEmpty, s"the batched preprocessor must stream rowIterator(), not materialize via ${data.materialized}")
        }
    }

    // Review fix: a batched response that leaves `rows` out keeps that batch's
    // original rows, exactly as the single call does ("Any of header, rows, or
    // rawData you leave out of the response keeps its original value").
    test("a batched REST preprocessor whose responses omit data.rows keeps every batch's original rows and takes the header") {
        withCap(256) {
            reset()
            val endpoint = RestEndpoint(endpoint = url("/pre-header"), batchSize = 500)
            val cfg = csvConfig("pre", List("id", "name")).copy(preprocessor = endpoint)
            val data = new SpyData(delimitedData(fiveThousand, List("id", "name")))

            val result = new RestEndpointRunner(ctx(cfg, data), endpoint).process()

            assert(calls.get() == 10)
            assert(result.data.rowCount == 5000, s"rows left out of every response must be kept, got ${result.data.rowCount}")
            assert(rowsOf(result.data) == fiveThousand)
            assert(result.data.header == List("ID", "NAME"))
            assert(data.materialized.isEmpty)
        }
    }

    // Review fix: stageCsvOutput's two on-disk passes (header decision, empty
    // record filter, empty output) have their own cases.
    test("stageCsvOutput takes the script's header and keeps a quoted embedded newline as one row") {
        withCap(256) {
            val out = Files.createTempFile("tx_output_", ".csv")
            Files.write(out, "id,v\n1,\"a\nb\"".getBytes(StandardCharsets.UTF_8))
            val r = CodeGenTransformationEvaluator.stageCsvOutput(out, List("id", "v"), 1L, ",")
            assert(r.headerFromScript && r.header == List("id", "v"))
            assert(r.staged.rowCount == 1)
            assert(rowsOf(new Data(0L, r.header, null, r.staged, null)) == List("1,\"a\nb\""))
            assert(java.nio.file.Paths.get(r.staged.path).startsWith(StagingArea.forToken(TOKEN)))
            Files.deleteIfExists(out)
        }
    }

    test("stageCsvOutput keeps the input header when the first line is numeric, dropping empty records") {
        withCap(256) {
            val out = Files.createTempFile("tx_output_", ".csv")
            Files.write(out, "1,2\n\n3,4\n5,6\n".getBytes(StandardCharsets.UTF_8))
            val r = CodeGenTransformationEvaluator.stageCsvOutput(out, List("id", "v"), 3L, ",")
            assert(!r.headerFromScript && r.header == List("id", "v"))
            assert(r.staged.rowCount == 3)
            assert(rowsOf(new Data(0L, r.header, null, r.staged, null)) == List("1,2", "3,4", "5,6"))
            Files.deleteIfExists(out)
        }
    }

    test("stageCsvOutput on an empty output file stages zero rows and keeps the input header") {
        withCap(256) {
            val out = Files.createTempFile("tx_output_", ".csv")
            val r = CodeGenTransformationEvaluator.stageCsvOutput(out, List("id", "v"), 3L, ",")
            assert(!r.headerFromScript && r.header == List("id", "v"))
            assert(r.staged.rowCount == 0 && r.staged.format == StagedFormat.Delimited(","))
            Files.deleteIfExists(out)
        }
    }

    // Step 2: NDJSON provenance stamping streams per record.
    test("NDJSON provenance stamping stamps every record without materializing the payload") {
        withCap(256) {
            val raw = (1 to 2000).map(i => s"""{"id":$i}""").mkString("[", ",", "]")
            val data = new SpyData(jsonData(raw))
            val cfg = PipelineConfig(
                name = "nd",
                version = 7,
                source = Source(schemaProperties = SchemaProperties("db", fields("_json")), fileAttributes = FileAttributes(jsonAttributes = JsonAttributes())),
                destination = Destination(schemaProperties = SchemaProperties("db", fields("_json"))),
                provenance = ProvenanceConfig(stamp = true)
            )
            val stamped = ProvenanceStamper.stamp(ctx(cfg, data))
            assert(stamped.data.rowCount == 2000)
            val records = {
                val it = stamped.data.recordIterator()
                try it.toList
                finally it.close()
            }
            assert(records.size == 2000)
            assert(records.forall(r => JsonParser.parseString(r).getAsJsonObject.get(ProvenanceStamper.RunId).getAsString == TOKEN))
            assert(records.map(r => JsonParser.parseString(r).getAsJsonObject.get("id").getAsInt) == (1 to 2000).toList)
            assert(data.materialized.isEmpty, s"NDJSON stamping materialized the payload via ${data.materialized}")
        }
    }
}
