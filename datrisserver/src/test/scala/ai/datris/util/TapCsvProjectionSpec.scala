package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

/** Phase 4 E2E finding: a real tap feed into a CSV pipeline left its
  *  `jsonToCsv` projection under `_unscoped/` because `feedPipeline` runs
  *  after `TapScriptRunner.run` has left its staging-token scope, and nothing
  *  ever deleted it. The projection must be written inside the tap run's own
  *  token directory so `TapRunner.release` reclaims it, on success and on the
  *  jsonToCsv fallback alike; the csv-shaped HTTP lane and the test-mode
  *  preview must not leak either. */
class TapCsvProjectionSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("tap-csv-projection-spec")
    private var server: HttpServer = _

    private val env: DatrisEnvironment = DatrisEnvironment(
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
        mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "datris"),
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
        tapScriptTimeoutSeconds = 30,
        tempDir = root.toString
    )

    override def beforeAll(): Unit = TenantContext.set(env)

    override def afterAll(): Unit = {
        TenantContext.clear()
        System.clearProperty("datris.allowPrivateEgress")
        if (server != null) server.stop(0)
    }

    private def filesUnder(dir: Path): List[Path] = {
        if (!Files.isDirectory(dir)) return Nil
        val s = Files.walk(dir)
        try s.iterator().asScala.filter(Files.isRegularFile(_)).toList
        finally s.close()
    }

    private def unscoped: List[Path] = filesUnder(root.resolve("_unscoped"))

    /** A staged record list under its own tap token, the way TapScriptRunner leaves it. */
    private def stagedUnder(token: String, json: String, dataType: String = "json"): TapScriptResult = {
        val staged = StagingArea.withToken(token)(PayloadStager.stageJson("tap", new java.io.StringReader(json)))
        TapScriptResult(staged, staged.rowCount.toInt, null, dataType = dataType)
    }

    private def linesOf(feed: StagedPayload): List[String] = Files.readAllLines(Paths.get(feed.path), StandardCharsets.UTF_8).asScala.toList

    test("the CSV projection is written inside the tap token dir and release() removes it with the dir") {
        val result = stagedUnder("tap-proj-ok", """[{"a":1,"b":"x"},{"a":2}]""")
        val (feed, filename) = TapRunner.projectForCsv(result, ",", "tap-t")
        assert(filename == "tap-t.csv")
        assert(feed.format == StagedFormat.Delimited(","))
        assert(Paths.get(feed.path).startsWith(StagingArea.forToken("tap-proj-ok")), "projection must live in the tap token dir, got " + feed.path)
        assert(unscoped.isEmpty, "nothing may land under _unscoped: " + unscoped)
        assert(Files.readAllLines(Paths.get(feed.path), StandardCharsets.UTF_8).asScala.toList == List("a,b", "1,x", "2,"))

        TapRunner.release(result)
        assert(!Files.exists(Paths.get(feed.path)), "release() must remove the projection")
        assert(!Files.exists(root.resolve("tap-proj-ok")), "release() must remove the tap token dir")
        assert(filesUnder(root).isEmpty, "staging root must be empty after the run: " + filesUnder(root))
    }

    // ---- Story: taps survive large sources (plans/stories/tap-large-sources.md), Steps 4-5 ----
    //
    //  `projectForCsv` no longer falls back to feeding the raw NDJSON as
    //  `<base>.json` (that is what put 21 garbage columns on a pipeline schema
    //  in the field). A csv-typed list-of-lists is projected with row 1 as the
    //  header — each cell through `TapScriptRunner.normalizeColumnName` — and
    //  the remaining arrays as rows; short rows pad with empty, long rows are
    //  an error; a payload that cannot be projected throws
    //  `DatrisException("tap returned <shape>, which cannot be projected into CSV pipeline <name>: <reason>")`
    //  before any StreamNotifier call. The token dir is still released.

    test("a csv-typed list-of-lists projects with row 1 as the normalized header") {
        val result = stagedUnder(
            "tap-proj-arrays",
            """[["EPS Estimate","Surprise(%)","Later Key"],[1.5,3,"a,b"],[2],[null,"q\"x","line1\nline2"]]""",
            dataType = "csv"
        )
        val feed =
            try {
                val (feed, filename) = TapRunner.projectForCsv(result, ",", "tap-t")
                assert(filename == "tap-t.csv", "an array payload is projected, never fed raw as .json: " + filename)
                assert(feed.format == StagedFormat.Delimited(","))
                assert(Paths.get(feed.path).startsWith(StagingArea.forToken("tap-proj-arrays")), "projection must live in the tap token dir, got " + feed.path)
                assert(unscoped.isEmpty, "nothing may land under _unscoped: " + unscoped)

                val records = {
                    val it = StagedRows.delimited(feed.path, ",")
                    try it.toList
                    finally it.close()
                }
                assert(records.size == 4, "header + 3 rows, got: " + records)
                assert(records.head == "eps_estimate,surprise_percent,later_key", "row 1 is the header, each cell normalized: " + records.head)
                assert(records(1) == "1.5,3,\"a,b\"", "numbers verbatim, a value holding the delimiter quoted: " + records(1))
                assert(records(2) == "2,,", "a short row pads with empty cells: " + records(2))
                assert(records(3) == ",\"q\"\"x\",\"line1\nline2\"", "null → empty; quotes doubled; an embedded newline is one quoted record: " + records(3))
                assert(feed.rowCount == 3L, "rowCount is the number of DATA rows (the header is not a record), got " + feed.rowCount)
                feed
            } finally TapRunner.release(result)

        assert(!Files.exists(Paths.get(feed.path)), "release() must remove the projection")
        assert(filesUnder(root).isEmpty, "staging root must be empty after the run: " + filesUnder(root))
    }

    test("a long row in a csv-typed list-of-lists fails with the named projection error") {
        val result = stagedUnder("tap-proj-long", """[["a","b"],[1,2],[3,4,5]]""", dataType = "csv")
        try {
            val e = intercept[DatrisException](TapRunner.projectForCsv(result, ",", "tap-t"))
            assert(e.getMessage.contains("cannot be projected into CSV pipeline tap-t"), e.getMessage)
        } finally TapRunner.release(result)
        assert(filesUnder(root).isEmpty, "the token dir must still release after a failed projection: " + filesUnder(root))
    }

    test("header cells that normalize to the same name fail with the named projection error") {
        // "Amount" and "amount" both normalize to `amount`; without the guard the
        // projection writes a CSV with two identically-named columns.
        val result = stagedUnder("tap-proj-dupe", """[["Amount","amount"],[1,2]]""", dataType = "csv")
        try {
            val e = intercept[DatrisException](TapRunner.projectForCsv(result, ",", "tap-t"))
            assert(e.getMessage.contains("duplicate column names after normalization: amount"), e.getMessage)
            assert(e.getMessage.contains("cannot be projected into CSV pipeline tap-t"), e.getMessage)
        } finally TapRunner.release(result)
        assert(filesUnder(root).isEmpty, "the token dir must still release after a failed projection: " + filesUnder(root))
    }

    test("a mixed object/array payload fails with the named projection error") {
        // First line an object (so the wrapper typed it json), then an array —
        // today's jsonToCsv threw on line 2 and the catch fed the raw file as .json.
        val mixed = stagedUnder("tap-proj-mixed", """[{"a":1,"b":2},[3,4],{"a":5}]""")
        try {
            val e = intercept[DatrisException](TapRunner.projectForCsv(mixed, ",", "tap-t"))
            assert(e.getMessage.startsWith("tap returned "), "message opens with the shape it saw: " + e.getMessage)
            assert(e.getMessage.contains("cannot be projected into CSV pipeline tap-t"), e.getMessage)
            assert(e.getMessage.contains(": "), "message ends with the reason: " + e.getMessage)
        } finally TapRunner.release(mixed)
        assert(filesUnder(root).isEmpty, filesUnder(root).toString)

        // The other order: arrays first (typed csv), then an object.
        val mixed2 = stagedUnder("tap-proj-mixed-2", """[["a","b"],[1,2],{"a":3}]""", dataType = "csv")
        try {
            val e2 = intercept[DatrisException](TapRunner.projectForCsv(mixed2, ",", "tap-t"))
            assert(e2.getMessage.contains("cannot be projected into CSV pipeline tap-t"), e2.getMessage)
        } finally TapRunner.release(mixed2)
        assert(filesUnder(root).isEmpty, filesUnder(root).toString)
    }

    test("projectForCsv never returns a .json filename") {
        // Every shape either projects to .csv or throws — the raw-NDJSON fallback is gone.
        val objects = stagedUnder("tap-proj-never-json-1", """[{"a":1},{"a":2}]""")
        try {
            val (f1, n1) = TapRunner.projectForCsv(objects, ",", "tap-t")
            assert(n1 == "tap-t.csv" && f1.format == StagedFormat.Delimited(","))
        } finally TapRunner.release(objects)

        val arrays = stagedUnder("tap-proj-never-json-2", """[["h1","h2"],[1,2]]""", dataType = "csv")
        try {
            val (f2, n2) = TapRunner.projectForCsv(arrays, ",", "tap-t")
            assert(n2 == "tap-t.csv" && f2.format == StagedFormat.Delimited(","), "got " + n2)
            assert(linesOf(f2).head == "h1,h2")
        } finally TapRunner.release(arrays)

        // A list of scalars is not a record list of either shape.
        val scalars = stagedUnder("tap-proj-never-json-3", """[1,2,3]""")
        try {
            val e = intercept[DatrisException](TapRunner.projectForCsv(scalars, ",", "tap-t"))
            assert(e.getMessage.contains("cannot be projected into CSV pipeline tap-t"), e.getMessage)
        } finally TapRunner.release(scalars)

        // An array payload whose dataType was NOT sniffed as csv (e.g. an HTTP tap
        // declaring json with array rows) still projects by shape, never .json.
        val declaredJson = stagedUnder("tap-proj-never-json-4", """[["x"],[1]]""")
        try {
            val (f4, n4) = TapRunner.projectForCsv(declaredJson, ",", "tap-t")
            assert(n4 == "tap-t.csv", "an array payload under a json label still projects: " + n4)
            assert(linesOf(f4) == List("x", "1"))
        } finally TapRunner.release(declaredJson)

        assert(unscoped.isEmpty)
        assert(filesUnder(root).isEmpty, filesUnder(root).toString)
    }

    test("the test-mode preview reads the staged file and release() empties the root") {
        val result = stagedUnder("tap-proj-preview", """[{"i":1},{"i":2},{"i":3}]""")
        val (preview, truncated) = TapRunner.preview(result.staged, 2)
        assert(preview.getAsJsonArray.size() == 2 && truncated)
        TapRunner.release(result)
        assert(filesUnder(root).isEmpty)
    }

    private def httpPort(body: String): Int = {
        if (server == null) {
            System.setProperty("datris.allowPrivateEgress", "true")
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
            server.start()
        }
        server.createContext(
            "/tap",
            new HttpHandler {
                override def handle(exchange: HttpExchange): Unit = {
                    exchange.getRequestBody.readAllBytes()
                    val bytes = body.getBytes(StandardCharsets.UTF_8)
                    exchange.getResponseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.length.toLong)
                    exchange.getResponseBody.write(bytes)
                    exchange.close()
                }
            }
        )
        server.getAddress.getPort
    }

    test("the csv-shaped HTTP lane normalizes inside its own token dir; the projection and release() leave the root empty") {
        val port = httpPort("""{"type": "csv", "data": [{"EPS Estimate": 1.5}, {"Later Key": "v"}]}""")
        val tap = TapConfig(
            name = "spec-http-csv",
            description = "spec",
            targetPipeline = null,
            scriptKind = "http",
            endpointUrl = "http://127.0.0.1:" + port + "/tap"
        )
        val result = TapScriptRunner.run(tap)
        assert(result.error == null, String.valueOf(result.error))
        assert(result.columns.asScala.toList == List("eps_estimate", "later_key"))
        assert(unscoped.isEmpty, "column normalization must not write under _unscoped: " + unscoped)
        val token = TapScriptRunner.stagingTokenOf(result.staged)
        assert(token != null && token.startsWith("tap-") && Paths.get(result.staged.path).startsWith(StagingArea.forToken(token)))
        assert(filesUnder(StagingArea.forToken(token)).size == 1, "the pre-normalization file is replaced, not kept")

        val (feed, _) = TapRunner.projectForCsv(result, ",", "tap-http")
        assert(Paths.get(feed.path).startsWith(StagingArea.forToken(token)))
        assert(unscoped.isEmpty)
        TapRunner.release(result)
        assert(filesUnder(root).isEmpty, "staging root must be empty after the run: " + filesUnder(root))
    }
}
