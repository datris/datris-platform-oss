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
    private def stagedUnder(token: String, json: String): TapScriptResult = {
        val staged = StagingArea.withToken(token)(PayloadStager.stageJson("tap", new java.io.StringReader(json)))
        TapScriptResult(staged, staged.rowCount.toInt, null, dataType = "json")
    }

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

    test("the jsonToCsv fallback (payload not a record list) leaves nothing behind either") {
        val result = stagedUnder("tap-proj-fallback", """[[1,2],[3,4]]""")
        val (feed, filename) = TapRunner.projectForCsv(result, ",", "tap-t")
        assert(filename == "tap-t.json", "a non-object payload falls back to feeding the raw staged file")
        assert(feed eq result.staged)
        assert(unscoped.isEmpty)
        TapRunner.release(result)
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
