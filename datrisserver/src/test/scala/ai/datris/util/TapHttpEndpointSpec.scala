package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TapConfig, TenantContext}
import com.google.gson.JsonParser
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** Exercises the HTTP-tap lane of TapScriptRunner against a real in-process
  * HTTP server: request-body shape, envelope parsing, contract failures,
  * output/state caps, timeout, and two-run state passthrough. The HTTP branch
  * shares everything downstream of the envelope with script taps, so these
  * tests pin the wire contract third-party endpoints build against.
  */
class TapHttpEndpointSpec extends AnyFunSuite with BeforeAndAfterAll {

    private var server: HttpServer = _
    private def port: Int = server.getAddress.getPort

    // Handler plumbing: each test installs a response and reads back the
    // request the runner sent.
    private val lastRequestBody = new AtomicReference[String](null)
    private val lastRequestHeaders = new AtomicReference[Map[String, String]](Map.empty)
    private val responseStatus = new AtomicReference[Int](200)
    private val responseBody = new AtomicReference[String]("{}")
    private val responseDelayMs = new AtomicReference[Long](0L)

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/tap",
            new HttpHandler {
                override def handle(exchange: HttpExchange): Unit = {
                    val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
                    lastRequestBody.set(body)
                    val headers = exchange.getRequestHeaders
                    val keys = headers.keySet().toArray(Array.empty[String])
                    lastRequestHeaders.set(keys.map(k => k -> headers.getFirst(k)).toMap)
                    if (responseDelayMs.get() > 0) Thread.sleep(responseDelayMs.get())
                    val bytes = responseBody.get().getBytes(StandardCharsets.UTF_8)
                    exchange.getResponseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(responseStatus.get(), bytes.length.toLong)
                    exchange.getResponseBody.write(bytes)
                    exchange.close()
                }
            }
        )
        // Real thread pool: the timeout test leaves a handler sleeping past the
        // client's deadline, and the default calling-thread executor would
        // serialize the NEXT test's request behind it — a cross-test flake.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
        server.start()
        // Small caps/timeouts so failure paths are exercisable in test time.
        TenantContext.set(testEnv)
    }

    override def afterAll(): Unit = {
        TenantContext.clear()
        if (server != null) server.stop(0)
    }

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
        tapScriptTimeoutSeconds = 2,
        tapMaxOutputMB = 1
    )

    private def httpTap(url: String = null): TapConfig = TapConfig(
        name = "spec-http-tap",
        description = "spec",
        targetPipeline = null,
        scriptKind = "http",
        endpointUrl = if (url != null) url else "http://127.0.0.1:" + port + "/tap"
    )

    private def install(status: Int, body: String, delayMs: Long = 0L): Unit = {
        responseStatus.set(status)
        responseBody.set(body)
        responseDelayMs.set(delayMs)
        lastRequestBody.set(null)
    }

    test("request body carries tap name, params, state, and testLimit; auth header absent without a secret") {
        install(200, """{"type": "json", "data": [{"id": 1}]}""")
        val result = TapScriptRunner.run(
            httpTap(),
            testLimit = 25,
            params = Map("start_date" -> "2026-08-01"),
            previousState = """{"cursor": 42}"""
        )
        assert(result.error == null)
        val req = JsonParser.parseString(lastRequestBody.get()).getAsJsonObject
        assert(req.get("tap").getAsString == "spec-http-tap")
        assert(req.getAsJsonObject("params").get("start_date").getAsString == "2026-08-01")
        assert(req.getAsJsonObject("state").get("cursor").getAsInt == 42)
        assert(req.get("testLimit").getAsInt == 25)
        val headers = lastRequestHeaders.get()
        assert(!headers.contains("Authorization"))
        assert(headers.get("X-datris-tap").orElse(headers.get("X-Datris-Tap")).contains("spec-http-tap"))
    }

    test("real run sends null state and null testLimit on first run") {
        install(200, """{"type": "json", "data": []}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error == null)
        assert(result.recordCount == 0)
        val req = JsonParser.parseString(lastRequestBody.get()).getAsJsonObject
        assert(req.get("state").isJsonNull)
        assert(req.get("testLimit").isJsonNull)
    }

    test("invalid param key fails before the call") {
        install(200, """{"type": "json", "data": []}""")
        val result = TapScriptRunner.run(httpTap(), params = Map("bad-key" -> "x"))
        assert(result.error != null)
        assert(result.error.contains("Invalid tap param key"))
    }

    test("json envelope: records, count, and state passthrough with integers preserved") {
        install(200, """{"type": "json", "data": [{"id": 1}, {"id": 2}], "state": {"cursor": 1785850779844}}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error == null)
        assert(result.recordCount == 2)
        assert(result.dataType == "json")
        // Integer cursor survives verbatim — never re-serialized as a Double.
        assert(result.newState.contains("1785850779844"))
        assert(!result.newState.contains("1785850779844.0"))
    }

    test("second run feeds the first run's state back to the endpoint") {
        install(200, """{"type": "json", "data": [{"id": 1}], "state": {"cursor": "abc"}}""")
        val first = TapScriptRunner.run(httpTap())
        assert(first.newState != null)
        install(200, """{"type": "json", "data": []}""")
        val second = TapScriptRunner.run(httpTap(), previousState = first.newState)
        assert(second.error == null)
        val req = JsonParser.parseString(lastRequestBody.get()).getAsJsonObject
        assert(req.getAsJsonObject("state").get("cursor").getAsString == "abc")
    }

    test("csv envelope: columns normalized and record keys rewritten") {
        install(200, """{"type": "csv", "data": [{"EPS Estimate": 1.5, "Surprise(%)": 3}]}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error == null)
        assert(result.dataType == "csv")
        assert(result.columns != null)
        assert(result.columns.contains("eps_estimate"))
        assert(result.columns.contains("surprise_percent"))
    }

    test("logs field is carried into the result") {
        install(200, """{"type": "json", "data": [], "logs": "fetched page 1 of 1"}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error == null)
        assert(result.logs == "fetched page 1 of 1")
    }

    test("missing type field is a contract failure, not a silent default") {
        install(200, """{"data": [{"id": 1}]}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error != null)
        assert(result.error.contains("type"))
        assert(result.error.contains("json|csv|xml|text|document"))
    }

    test("unknown type value is rejected") {
        install(200, """{"type": "parquet", "data": []}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error != null)
        assert(result.error.contains("parquet"))
    }

    test("missing data field is a contract failure") {
        install(200, """{"type": "json"}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error != null)
        assert(result.error.contains("data"))
    }

    test("non-JSON response body is a contract failure with a truncated echo") {
        install(200, "<html>gateway error</html>")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error != null)
        assert(result.error.contains("not a JSON envelope"))
        assert(result.error.contains("gateway error"))
    }

    test("non-200 status is a failed run carrying the response body") {
        install(503, """{"error": "upstream down"}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error != null)
        assert(result.error.contains("503"))
        assert(result.error.contains("upstream down"))
    }

    test("405 leads with the accept-POST hint") {
        install(405, "<html>Method Not Allowed</html>")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error != null)
        assert(result.error.contains("POST"))
        assert(result.error.contains("405"))
    }

    test("timeout produces a failed run mentioning chunked runs") {
        install(200, """{"type": "json", "data": []}""", delayMs = 4000L)
        val result = TapScriptRunner.run(httpTap())
        assert(result.error != null)
        assert(result.error.contains("did not respond within 2 seconds"))
        assert(result.error.toLowerCase.contains("chunk"))
    }

    test("connection refused produces a failed run with the container-localhost hint") {
        // Port 1 on localhost is essentially guaranteed closed.
        val result = TapScriptRunner.run(httpTap(url = "http://127.0.0.1:1/tap"))
        assert(result.error != null)
        assert(result.error.contains("host.docker.internal"))
    }

    test("oversized response aborts with the output-cap message") {
        // tapMaxOutputMB=1 in testEnv; send ~1.2MB.
        val bigRecord = "\"" + ("x" * 1024) + "\""
        val records = Seq.fill(1200)(bigRecord).mkString("[", ",", "]")
        install(200, s"""{"type": "json", "data": $records}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error != null)
        assert(result.error.contains("MB limit"))
    }

    test("oversized state blob is rejected with the state-cap message") {
        val bigState = "\"" + ("s" * (70 * 1024)) + "\""
        install(200, s"""{"type": "json", "data": [], "state": {"blob": $bigState}}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error != null)
        assert(result.error.contains("state blob"))
    }

    test("xml type yields a single-record payload") {
        install(200, """{"type": "xml", "data": "<?xml version=\"1.0\"?><root/>"}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error == null)
        assert(result.recordCount == 1)
        assert(result.dataType == "xml")
    }

    test("document type keeps the document record shape") {
        install(200, """{"type": "document", "data": [{"uri": "https://x/a.pdf", "filename": "a.pdf", "content": "aGk="}]}""")
        val result = TapScriptRunner.run(httpTap())
        assert(result.error == null)
        assert(result.recordCount == 1)
        assert(result.dataType == "document")
        assert(result.records.contains("a.pdf"))
    }
}
