package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{Gson, JsonObject, JsonParser}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger

/** Story: Runner overload protection (plans/stories/runner-overload-protection.md),
  * the tap half of the server.
  *
  * The tap runner answers `503 {"error":"runner busy","retryAfterSec":N,"active":k,
  * "maxConcurrent":M}` + `Retry-After: N` when all its slots are taken. The server
  * waits its turn: on 503 it sleeps `busyWait(attempt, retryAfterSec)` and retries,
  * until TAP_RUNNER_QUEUE_TIMEOUT_SECONDS (default 600) elapses, then fails with
  * `Tap runner busy for N s (TAP_RUNNER_MAX_CONCURRENT=M on datris-tap-runner);
  * raise TAP_RUNNER_MAX_CONCURRENT or stagger schedules`. The time spent waiting
  * is recorded on the result and the run log.
  *
  * Pinned seams (from the story's Files section):
  * {{{
  * case class TapScriptResult(..., queuedMs: Long = 0)
  * case class TapRunLog(..., queuedMs: Long = 0)          // Gson: absent -> 0
  * object TapScriptRunner {
  *     // Milliseconds to sleep before retry `attempt` (0-based); honours the
  *     // runner's retryAfterSec, backs off, never more than 30 s.
  *     private[util] def busyWait(attempt: Int, retryAfterSec: Int): Long
  * }
  * }}}
  *
  * `executeViaRunner` reads USE_TAP_RUNNER / TAP_RUNNER_URL / TAP_RUNNER_TOKEN /
  * TAP_RUNNER_QUEUE_TIMEOUT_SECONDS from the process environment, which a spec
  * cannot set, so the end-to-end cases run `TapScriptRunner.runScript` (the
  * entry point TapRunner uses) in a child JVM with those variables set, against
  * an in-test stub runner speaking the real streaming protocol.
  */
class TapRunnerBusySpec extends AnyFunSuite with BeforeAndAfterAll {

    import TapRunnerBusySpec._

    private val root: Path = Files.createTempDirectory("tap-runner-busy-spec")
    private val TOKEN = "spec-busy-token-0123456789abcdef"
    private val RetryAfterSec = 1

    private def pythonAvailable: Boolean =
        scala.util.Try(new ProcessBuilder("python3", "--version").start().waitFor() == 0).getOrElse(false)

    // ------------------------------------------------------------ stub runner

    private val posts = new AtomicInteger(0)

    /** POSTs answered 503 before the stub starts answering 200 (Int.MaxValue = always busy). */
    @volatile private var busyFor: Int = 0

    private var server: HttpServer = _
    private def url: String = "http://127.0.0.1:" + server.getAddress.getPort

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/execute",
            new HttpHandler {
                override def handle(ex: HttpExchange): Unit = {
                    val n = posts.incrementAndGet()
                    ex.getRequestBody.readAllBytes()
                    if (n <= busyFor) {
                        val body = ("{\"error\":\"runner busy\",\"retryAfterSec\":" + RetryAfterSec + ",\"active\":4,\"maxConcurrent\":4}")
                            .getBytes(StandardCharsets.UTF_8)
                        ex.getResponseHeaders.set("Content-Type", "application/json")
                        ex.getResponseHeaders.set("Retry-After", RetryAfterSec.toString)
                        ex.sendResponseHeaders(503, body.length.toLong)
                        ex.getResponseBody.write(body)
                    } else {
                        // Streaming protocol: record bytes, 0x1E, trailer JSON.
                        val envelope = new JsonObject()
                        envelope.addProperty("type", "json")
                        envelope.addProperty("count", Integer.valueOf(1))
                        val cols = new com.google.gson.JsonArray()
                        cols.add("id")
                        envelope.add("columns", cols)
                        val trailer = new JsonObject()
                        trailer.addProperty("stdout", envelope.toString)
                        trailer.addProperty("stderr", "")
                        trailer.addProperty("exitCode", Integer.valueOf(0))
                        trailer.addProperty("timedOut", java.lang.Boolean.FALSE)
                        ex.getResponseHeaders.set("Content-Type", "application/octet-stream")
                        ex.sendResponseHeaders(200, 0)
                        val out = ex.getResponseBody
                        out.write("{\"id\": 1}\n".getBytes(StandardCharsets.UTF_8))
                        out.write(0x1e)
                        out.write((trailer.toString + "\n").getBytes(StandardCharsets.UTF_8))
                    }
                    ex.close()
                }
            }
        )
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
        server.start()
    }

    override def afterAll(): Unit = {
        if (server != null) server.stop(0)
        scala.util.Try(Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p)))
    }

    /** Run TapScriptRunner.runScript in a child JVM with `env` set; returns (result json, elapsed ms, log). */
    private def runChild(script: String, env: Map[String, String]): (JsonObject, Long, String) = {
        val scriptFile = root.resolve("script-" + System.nanoTime() + ".py")
        Files.write(scriptFile, script.getBytes(StandardCharsets.UTF_8))
        val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString
        val pb = new ProcessBuilder(
            javaBin,
            "-Xmx512m",
            "-cp",
            System.getProperty("java.class.path"),
            "ai.datris.util.TapRunnerBusyChild",
            scriptFile.toString,
            root.toString
        ).redirectErrorStream(true)
        val penv = pb.environment()
        Seq("USE_TAP_RUNNER", "TAP_RUNNER_URL", "TAP_RUNNER_TOKEN", "TAP_RUNNER_QUEUE_TIMEOUT_SECONDS").foreach(penv.remove)
        penv.put("TAP_RUNNER_TOKEN_FILE", "/nonexistent/tap-runner-token")
        env.foreach { case (k, v) => penv.put(k, v) }
        val start = System.currentTimeMillis()
        val proc = pb.start()
        val log = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        val exit = proc.waitFor()
        val elapsed = System.currentTimeMillis() - start
        val line = log.linesIterator.find(_.startsWith(ResultPrefix))
        assert(exit == 0 && line.isDefined, "child JVM failed (exit " + exit + "):\n" + log.takeRight(4000))
        (JsonParser.parseString(line.get.stripPrefix(ResultPrefix)).getAsJsonObject, elapsed, log)
    }

    private def markerScript(marker: Path): String =
        "def fetch():\n" +
            "    open(" + pyStr(marker.toString) + ", 'w').write('ran')\n" +
            "    yield {'id': 1}\n"

    private def str(o: JsonObject, k: String): String = if (o.has(k) && !o.get(k).isJsonNull) o.get(k).getAsString else null

    // ------------------------------------------------- Acceptance bullet 3

    test("a runner answering 503 twice then 200 yields a success with queuedMs >= 2 * retryAfterSec * 1000 and no extra process") {
        posts.set(0)
        busyFor = 2
        val marker = root.resolve("ran-in-process-1.marker")
        val (r, _, log) = runChild(
            markerScript(marker),
            Map("USE_TAP_RUNNER" -> "true", "TAP_RUNNER_URL" -> url, "TAP_RUNNER_TOKEN" -> TOKEN, "TAP_RUNNER_QUEUE_TIMEOUT_SECONDS" -> "60")
        )
        assert(str(r, "error") == null, "the run must succeed once a slot frees: " + str(r, "error") + "\n" + log.takeRight(2000))
        assert(r.get("recordCount").getAsInt == 1, r.toString)
        assert(posts.get() == 3, "two busy answers then one run: " + posts.get() + " POSTs")
        assert(
            r.get("queuedMs").getAsLong >= 2L * RetryAfterSec * 1000L,
            "queuedMs must cover both waits: " + r.get("queuedMs")
        )
        assert(!Files.exists(marker), "a busy runner must never lead to an in-process run")
    }

    test("a runner that only answers 503 fails after TAP_RUNNER_QUEUE_TIMEOUT_SECONDS with one sentence naming TAP_RUNNER_MAX_CONCURRENT") {
        posts.set(0)
        busyFor = Int.MaxValue
        val marker = root.resolve("ran-in-process-2.marker")
        val (r, elapsed, _) = runChild(
            markerScript(marker),
            Map("USE_TAP_RUNNER" -> "true", "TAP_RUNNER_URL" -> url, "TAP_RUNNER_TOKEN" -> TOKEN, "TAP_RUNNER_QUEUE_TIMEOUT_SECONDS" -> "3")
        )
        val error = str(r, "error")
        assert(error != null, "permanent busy must fail the run")
        assert(error.startsWith("Tap runner busy for"), error)
        assert(error.contains("TAP_RUNNER_MAX_CONCURRENT"), error)
        assert(!error.contains("\tat ") && !error.contains("Exception"), "no stack trace in the run log error: " + error)
        assert(!error.contains("Tap runner returned 503"), "the generic transport failure must not surface: " + error)
        assert(posts.get() >= 2, "the server must have retried at least once: " + posts.get())
        // Bounded by the 3 s queue timeout (plus one capped wait and JVM start), not the 600 s default.
        assert(elapsed < 60000, "gave up after " + elapsed + " ms")
        assert(!Files.exists(marker), "a busy runner must never lead to an in-process run")
    }

    test("busyWait honours retryAfterSec and never exceeds 30 s per attempt") {
        for (attempt <- 0 to 40; retryAfter <- Seq(0, 1, 5, 29, 30, 31, 120, 3600)) {
            val w = TapScriptRunner.busyWait(attempt, retryAfter)
            assert(w <= 30000L, s"busyWait($attempt, $retryAfter) = $w ms exceeds 30 s")
            assert(w > 0L, s"busyWait($attempt, $retryAfter) = $w ms: must actually wait")
        }
        assert(TapScriptRunner.busyWait(0, 5) >= 5000L, "the first wait honours the runner's retryAfterSec (ms)")
        assert(TapScriptRunner.busyWait(0, 120) >= 25000L, "a long retryAfterSec is honoured up to the 30 s cap")
    }

    test("USE_TAP_RUNNER=false never calls the runner or the wait loop") {
        assume(pythonAvailable, "python3 not available")
        posts.set(0)
        busyFor = Int.MaxValue
        val (r, _, log) = runChild(
            "def fetch():\n    yield {'id': 1}\n",
            Map("USE_TAP_RUNNER" -> "false", "TAP_RUNNER_URL" -> url, "TAP_RUNNER_TOKEN" -> TOKEN, "TAP_RUNNER_QUEUE_TIMEOUT_SECONDS" -> "3")
        )
        assert(str(r, "error") == null, str(r, "error") + "\n" + log.takeRight(2000))
        assert(posts.get() == 0, "in-process mode must not call the runner")
        assert(r.get("queuedMs").getAsLong == 0L, r.toString)
    }

    test("a stored run log without queuedMs reads back as 0") {
        val old = "{\"tapName\":\"t\",\"runTime\":\"2026-10-01T00:00:00Z\",\"status\":\"success\",\"recordCount\":3,\"durationMs\":1200}"
        val log = new Gson().fromJson(old, classOf[TapRunLog])
        assert(log.queuedMs == 0L)
        assert(log.durationMs == 1200L)
    }
}

object TapRunnerBusySpec {
    val ResultPrefix = "CHILD-RESULT "
    def pyStr(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

/** Child JVM for TapRunnerBusySpec: runs one tap through TapScriptRunner.runScript
  * with the process environment the parent set, prints the result as JSON. */
object TapRunnerBusyChild {
    def main(args: Array[String]): Unit = {
        val Array(scriptPath, tempDir) = args
        val env = DatrisEnvironment(
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
            tapScriptTimeoutSeconds = 60,
            tapRunTimeoutSeconds = 60,
            tempDir = tempDir
        )
        try {
            TenantContext.set(env)
            val script = new String(Files.readAllBytes(Paths.get(scriptPath)), StandardCharsets.UTF_8)
            val tap = TapConfig(name = "spec-busy-tap", description = "spec", targetPipeline = null, scriptPath = "tap-scripts/unused.py")
            val r = TapScriptRunner.runScript(tap, script, mode = "run")
            val o = new JsonObject()
            o.addProperty("error", r.error)
            o.addProperty("recordCount", Integer.valueOf(r.recordCount))
            o.addProperty("queuedMs", java.lang.Long.valueOf(r.queuedMs))
            TapScriptRunner.release(r)
            println(TapRunnerBusySpec.ResultPrefix + o.toString)
            System.exit(0)
        } catch {
            case t: Throwable =>
                t.printStackTrace()
                System.exit(2)
        }
    }
}
