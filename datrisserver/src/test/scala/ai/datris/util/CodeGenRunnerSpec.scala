package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import com.google.gson.JsonParser
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, InputStream}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/** Generated DQ / transformation scripts run in the datris-codegen-runner
  * sidecar (plans/stories/codegen-script-isolation.md), the server half.
  *
  * The client is exercised against an in-test HTTP server speaking the
  * `/execute-file` framing: request = one JSON metadata line
  * `{"script","timeoutSec","inputName","outputName"?}` + `\n` + raw input bytes
  * (known Content-Length, not chunked); response = one JSON result line
  * `{"stdout","stderr","exitCode","timedOut","outputBytes"}` + `\n` + the output
  * file bytes when an output was requested.
  *
  * Pinned seam (the story fixes `run(script, input, output, timeoutSec)` and
  * `health()`, reading `USE_CODEGEN_RUNNER` / `CODEGEN_RUNNER_URL` / the tap
  * runner token from the environment; a spec cannot set env vars, so the
  * settings are passed explicitly through an overload the env-reading form
  * delegates to):
  *
  * {{{
  * object CodeGenRunner {
  *     case class Settings(enabled: Boolean, url: String, token: String)
  *     object Settings { def fromEnv: Settings }   // USE_CODEGEN_RUNNER, CODEGEN_RUNNER_URL, TapScriptRunner.resolvedTapRunnerToken
  *     def enabled: Boolean
  *     def url: String
  *     def run(script: Path, input: Path, output: Option[Path], timeoutSec: Int): SandboxedPython.Result
  *     private[util] def run(script: Path, input: Path, output: Option[Path], timeoutSec: Int, settings: Settings): SandboxedPython.Result
  *     def health(): Either[String, Unit]
  *     private[util] def health(settings: Settings): Either[String, Unit]
  *     def assertConfig(): Unit
  *     def warnInProcess(where: String): Unit
  * }
  * }}}
  *
  * Contract pinned by the assertions below:
  *  - a script that ran and exited (zero or not) is a returned `Result`, as
  *    `SandboxedPython.run` does today; the caller decides what non-zero means;
  *  - every transport failure (refused, 404, 401, 5xx, 507) throws a
  *    `DatrisException` whose message contains "CodeGen runner" and is NEVER
  *    followed by an in-process run;
  *  - a 404 (an older runner image without `/execute-file`) names the image
  *    `datris-tap-runner` so the operator knows to pull it;
  *  - a 507, or a script whose stderr says "No space left on device", carries
  *    the volume name `codegen-scratch` (in the exception message, or appended
  *    to `Result.stderr`).
  */
class CodeGenRunnerSpec extends AnyFunSuite with BeforeAndAfterAll {

    import CodeGenRunnerSpec._

    private val root: Path = Files.createTempDirectory("codegen-runner-spec")
    private val TOKEN = "spec-runner-token"

    private def pythonAvailable: Boolean =
        scala.util.Try(new ProcessBuilder("python3", "--version").start().waitFor() == 0).getOrElse(false)

    // ---------------------------------------------------------------- fake runner

    case class Received(meta: String, input: Array[Byte], contentLength: Option[String], transferEncoding: Option[String], auth: Option[String])

    private val hits = new AtomicInteger(0)
    private val received = new AtomicReference[Received](null)

    /** (status, result line or error body, output bytes) the fake answers with. */
    private val reply = new AtomicReference[(Int, String, Array[Byte])]((200, "{}", Array.emptyByteArray))

    private var server: HttpServer = _

    private def port: Int = server.getAddress.getPort
    private def url: String = "http://127.0.0.1:" + port
    private def settings: CodeGenRunner.Settings = CodeGenRunner.Settings(enabled = true, url = url, token = TOKEN)

    override def beforeAll(): Unit = {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
            new HttpHandler {
                override def handle(ex: HttpExchange): Unit = {
                    hits.incrementAndGet()
                    val in = ex.getRequestBody
                    val meta = readLine(in)
                    val body = in.readAllBytes()
                    val h = ex.getRequestHeaders
                    received.set(
                        Received(
                            meta,
                            body,
                            Option(h.getFirst("Content-Length")),
                            Option(h.getFirst("Transfer-Encoding")),
                            Option(h.getFirst("Authorization"))
                        )
                    )
                    val (status, line, out) = reply.get()
                    val head = line.getBytes(StandardCharsets.UTF_8)
                    val payload = if (status == 200) head ++ "\n".getBytes ++ out else head
                    ex.getResponseHeaders.set("Content-Type", if (status == 200) "application/octet-stream" else "application/json")
                    ex.sendResponseHeaders(status, payload.length.toLong)
                    ex.getResponseBody.write(payload)
                    ex.close()
                }
            }
        )
        server.createContext("/big/execute-file", new BigHandler)
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
        server.start()
    }

    override def afterAll(): Unit = {
        if (server != null) server.stop(0)
        scala.util.Try(Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p)))
    }

    private def write(name: String, content: String): Path = {
        val p = root.resolve(name)
        Files.write(p, content.getBytes(StandardCharsets.UTF_8))
        p
    }

    private def resultLine(stdout: String, stderr: String, exit: Int, timedOut: Boolean, outputBytes: Long): String = {
        val o = new com.google.gson.JsonObject()
        o.addProperty("stdout", stdout)
        o.addProperty("stderr", stderr)
        o.addProperty("exitCode", Integer.valueOf(exit))
        o.addProperty("timedOut", java.lang.Boolean.valueOf(timedOut))
        o.addProperty("outputBytes", java.lang.Long.valueOf(outputBytes))
        o.toString
    }

    private def reset(r: (Int, String, Array[Byte])): Unit = {
        hits.set(0)
        received.set(null)
        reply.set(r)
    }

    private def mentionsRunner(msg: String): Boolean = msg != null && msg.contains("CodeGen runner")

    // ---------------------------------------------------------------- 1

    test("sends the script and input and returns the result") {
        val script = write("rule.py", "import sys\nprint('[]')\n")
        val input = write("rule-in.csv", "id,name\n1,a\n2,b\n")
        reset((200, resultLine("[{\"row\": 2}]", "warn: x", 0, timedOut = false, 0L), Array.emptyByteArray))

        val r = CodeGenRunner.run(script, input, None, 45, settings)

        assert(r.exitCode == 0)
        assert(r.stdout.trim == "[{\"row\": 2}]")
        assert(r.stderr.trim == "warn: x")
        assert(hits.get() == 1)
        val got = received.get()
        val meta = JsonParser.parseString(got.meta).getAsJsonObject
        assert(meta.get("script").getAsString == "import sys\nprint('[]')\n")
        assert(meta.get("timeoutSec").getAsInt == 45)
        assert(meta.has("inputName") && meta.get("inputName").getAsString.nonEmpty)
        assert(!meta.has("outputName") || meta.get("outputName").isJsonNull, "no output requested for a DQ rule")
        assert(new String(got.input, StandardCharsets.UTF_8) == "id,name\n1,a\n2,b\n", "input bytes follow the metadata line verbatim")
        assert(got.auth.contains("Bearer " + TOKEN))
        assert(got.contentLength.isDefined, "request must carry a known Content-Length")
        assert(!got.transferEncoding.exists(_.toLowerCase.contains("chunked")), "the stdlib runner does not decode chunked bodies")
    }

    test("a non-zero exit is a returned Result, not a transport failure") {
        val script = write("bad.py", "raise SystemExit(4)\n")
        val input = write("bad-in.csv", "a\n")
        reset((200, resultLine("", "Traceback: boom", 4, timedOut = false, 0L), Array.emptyByteArray))
        val r = CodeGenRunner.run(script, input, None, 30, settings)
        assert(r.exitCode == 4)
        assert(r.stderr.contains("boom"))
    }

    // ---------------------------------------------------------------- 2

    test("writes the returned bytes to the output path") {
        val script = write("t.py", "import sys\n")
        val input = write("t-in.csv", "a,b\n1,2\n")
        val output = root.resolve("t-out.csv")
        val bytes = ("A,B\n" + (1 to 20000).map(i => s"$i,${i * 2}").mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)
        reset((200, resultLine("", "", 0, timedOut = false, bytes.length.toLong), bytes))

        val r = CodeGenRunner.run(script, input, Some(output), 30, settings)

        assert(r.exitCode == 0)
        assert(Files.exists(output))
        assert(java.util.Arrays.equals(Files.readAllBytes(output), bytes))
        val meta = JsonParser.parseString(received.get().meta).getAsJsonObject
        assert(meta.has("outputName") && meta.get("outputName").getAsString.nonEmpty, "a transformation asks for an output file")
    }

    // ---------------------------------------------------------------- 3

    test("a 404 from an older runner fails and names the image") {
        val script = write("old.py", "print('[]')\n")
        val input = write("old-in.csv", "a\n")
        reset((404, "{\"error\": \"not found\"}", Array.emptyByteArray))
        val e = intercept[DatrisException](CodeGenRunner.run(script, input, None, 30, settings))
        assert(mentionsRunner(e.getMessage), e.getMessage)
        assert(e.getMessage.contains("datris-tap-runner"), "name the image to pull: " + e.getMessage)
    }

    test("a 401 (token rejected) fails naming the runner") {
        val script = write("auth.py", "print('[]')\n")
        val input = write("auth-in.csv", "a\n")
        reset((401, "{\"error\": \"unauthorized\"}", Array.emptyByteArray))
        val e = intercept[DatrisException](CodeGenRunner.run(script, input, None, 30, settings))
        assert(mentionsRunner(e.getMessage), e.getMessage)
    }

    // ---------------------------------------------------------------- 4

    test("an unreachable runner fails and does not run in-process") {
        assume(pythonAvailable, "python3 not available")
        val marker = root.resolve("ran-in-process.marker")
        Files.deleteIfExists(marker)
        val script = write("unreach.py", s"open(${pyStr(marker.toString)}, 'w').write('ran')\nprint('[]')\n")
        val input = write("unreach-in.csv", "a\n")
        val closed = {
            val s = new java.net.ServerSocket(0)
            val p = s.getLocalPort
            s.close()
            p
        }
        val dead = CodeGenRunner.Settings(enabled = true, url = "http://127.0.0.1:" + closed, token = TOKEN)
        val e = intercept[DatrisException](CodeGenRunner.run(script, input, None, 10, dead))
        assert(mentionsRunner(e.getMessage), e.getMessage)
        assert(!Files.exists(marker), "the script must never fall back to in-process when the runner is required")
    }

    // ---------------------------------------------------------------- 5

    test("not enabled runs through SandboxedPython") {
        assume(pythonAvailable, "python3 not available")
        val script = write(
            "local.py",
            "import sys, os\n" +
                "with open(sys.argv[1]) as i, open(sys.argv[2], 'w') as o:\n" +
                "    o.write(i.read().upper())\n" +
                "print('VAULT_TOKEN' in os.environ)\n"
        )
        val input = write("local-in.csv", "a,b\nx,y\n")
        val output = root.resolve("local-out.csv")
        reset((500, "{\"error\": \"must not be called\"}", Array.emptyByteArray))
        val off = CodeGenRunner.Settings(enabled = false, url = url, token = TOKEN)

        val r = CodeGenRunner.run(script, input, Some(output), 30, off)

        assert(hits.get() == 0, "in-process lane must not call the runner")
        assert(r.exitCode == 0, r.stderr)
        assert(r.stdout.trim == "False", "in-process lane keeps SandboxedPython's scrubbed env")
        assert(new String(Files.readAllBytes(output), StandardCharsets.UTF_8) == "A,B\nX,Y\n")
    }

    // ---------------------------------------------------------------- 6

    test("a 507 or a no-space stderr names the codegen-scratch volume") {
        val script = write("full.py", "print('[]')\n")
        val input = write("full-in.csv", "a\n")

        reset((507, "{\"error\": \"No space left on device\"}", Array.emptyByteArray))
        val e = intercept[DatrisException](CodeGenRunner.run(script, input, None, 30, settings))
        assert(e.getMessage.contains("codegen-scratch"), e.getMessage)

        reset((200, resultLine("", "OSError: [Errno 28] No space left on device", 1, timedOut = false, 0L), Array.emptyByteArray))
        val text =
            try CodeGenRunner.run(script, input, Some(root.resolve("full-out.csv")), 30, settings).stderr
            catch { case d: DatrisException => d.getMessage }
        assert(text.contains("No space left on device"), text)
        assert(text.contains("codegen-scratch"), "a no-space script failure names the volume: " + text)
    }

    // ---------------------------------------------------------------- 7

    test("the input and output are streamed, never held whole") {
        // A child JVM with a heap (128 MB) smaller than the payload sends a 256 MB
        // input and receives a 256 MB output. StringEntity, EntityUtils.toString
        // or Files.readAllBytes on either side would OutOfMemoryError.
        val size = BigBytes
        val input = root.resolve("big-in.csv")
        val chunk = Array.fill[Byte](1 << 20)('x'.toByte)
        val os = Files.newOutputStream(input)
        try { var left = size; while (left > 0) { val n = math.min(left, chunk.length.toLong).toInt; os.write(chunk, 0, n); left -= n } }
        finally os.close()
        val script = write("big.py", "import shutil, sys\nshutil.copyfile(sys.argv[1], sys.argv[2])\n")
        val output = root.resolve("big-out.csv")
        bigReceived.set(-1L)

        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString
        val cmd = java.util.Arrays.asList(
            javaBin,
            "-Xmx" + ChildHeap,
            "-cp",
            System.getProperty("java.class.path"),
            "ai.datris.util.CodeGenRunnerStreamingChild",
            url + "/big",
            TOKEN,
            script.toString,
            input.toString,
            output.toString
        )
        val pb = new ProcessBuilder(cmd).redirectErrorStream(true)
        val proc = pb.start()
        val log = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        val exit = proc.waitFor()
        try {
            assert(exit == 0, "child JVM (-Xmx" + ChildHeap + ") failed:\n" + log.takeRight(4000))
            assert(log.contains("CHILD-OK exit=0"), log.takeRight(4000))
            assert(bigReceived.get() == size, "runner received " + bigReceived.get() + " input bytes, expected " + size)
            assert(Files.size(output) == size, "output file size")
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }

    // ---------------------------------------------------------------- runner overload protection
    //
    // Story: plans/stories/runner-overload-protection.md. A busy runner answers
    // 503 BEFORE reading the upload and resets the socket. Depending on timing the
    // client either reads the 503 or its upload dies with a broken pipe / reset and
    // the 503 is lost (the IOException path); then it asks GET /health, which now
    // carries `active` and `maxConcurrent`. Both mean busy: wait and retry until a
    // slot frees or the queue timeout passes, then fail with
    // "busy for N s (CODEGEN_RUNNER_MAX_CONCURRENT=M); raise it or run fewer AI rules at once".
    //
    // Pinned seam: Settings gains the queue timeout (TAP_RUNNER_QUEUE_TIMEOUT_SECONDS,
    // default 600, in fromEnv) as a defaulted field, so existing three-argument
    // constructions keep compiling:
    //   case class Settings(enabled: Boolean, url: String, token: String, queueTimeoutSec: Int = 600)

    test("a 503 before the upload is read, with /health at active == maxConcurrent, is retried and succeeds once a slot frees") {
        val busy = new BusyRunner(busyForMs = 1500)
        try {
            val input = bigInput("busy-in.csv", 32L * 1024 * 1024)
            val script = write("busy.py", "print('[]')\n")
            val s = CodeGenRunner.Settings(enabled = true, url = busy.url, token = TOKEN)
            val r = CodeGenRunner.run(script, input, None, 30, s)
            assert(r.exitCode == 0, r.stderr)
            assert(r.stdout.trim == "[]", r.stdout)
            assert(busy.rejected.get() >= 1, "the first upload must have hit the busy runner")
            assert(busy.received.get() == Files.size(input), "the retried upload carries the whole input: " + busy.received.get())
            Files.deleteIfExists(input)
        } finally busy.close()
    }

    test("a permanently busy runner fails with the busy-for message naming CODEGEN_RUNNER_MAX_CONCURRENT, not a broken-pipe trace") {
        val busy = new BusyRunner(busyForMs = Long.MaxValue)
        try {
            val input = bigInput("busy-forever-in.csv", 32L * 1024 * 1024)
            val script = write("busy-forever.py", "print('[]')\n")
            val s = CodeGenRunner.Settings(enabled = true, url = busy.url, token = TOKEN, queueTimeoutSec = 3)
            val start = System.currentTimeMillis()
            val e = intercept[DatrisException](CodeGenRunner.run(script, input, None, 30, s))
            val elapsed = System.currentTimeMillis() - start
            val msg = e.getMessage
            assert(mentionsRunner(msg), msg)
            assert(msg.contains("busy for"), msg)
            assert(msg.contains("CODEGEN_RUNNER_MAX_CONCURRENT=2"), msg)
            assert(!msg.contains("Broken pipe") && !msg.contains("Connection reset") && !msg.contains("SocketException"), msg)
            assert(busy.received.get() == -1L, "a busy runner never ran the script")
            assert(elapsed < 60000, "bounded by the 3 s queue timeout, gave up after " + elapsed + " ms")
            Files.deleteIfExists(input)
        } finally busy.close()
    }

    private def bigInput(name: String, size: Long): Path = {
        val p = root.resolve(name)
        val chunk = Array.fill[Byte](1 << 20)('z'.toByte)
        val os = Files.newOutputStream(p)
        try { var left = size; while (left > 0) { val n = math.min(left, chunk.length.toLong).toInt; os.write(chunk, 0, n); left -= n } }
        finally os.close()
        p
    }
}

object CodeGenRunnerSpec {
    val BigBytes: Long = 256L * 1024 * 1024
    val ChildHeap = "128m"
    val bigReceived = new AtomicReference[java.lang.Long](-1L)

    def pyStr(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    def readLine(in: InputStream): String = {
        val buf = new ByteArrayOutputStream()
        var b = in.read()
        while (b != -1 && b != '\n') { buf.write(b); b = in.read() }
        new String(buf.toByteArray, StandardCharsets.UTF_8)
    }

    /** Fake runner for the big case: counts the input without keeping it,
      * answers with a generated output of the same size. */
    class BigHandler extends HttpHandler {
        override def handle(ex: HttpExchange): Unit = {
            val in = ex.getRequestBody
            readLine(in)
            val buf = new Array[Byte](64 * 1024)
            var total = 0L
            var n = in.read(buf)
            while (n != -1) { total += n; n = in.read(buf) }
            bigReceived.set(total)
            val line = ("{\"stdout\":\"\",\"stderr\":\"\",\"exitCode\":0,\"timedOut\":false,\"outputBytes\":" + total + "}\n")
                .getBytes(StandardCharsets.UTF_8)
            ex.getResponseHeaders.set("Content-Type", "application/octet-stream")
            ex.sendResponseHeaders(200, line.length + total)
            val out = ex.getResponseBody
            out.write(line)
            java.util.Arrays.fill(buf, 'y'.toByte)
            var left = total
            while (left > 0) { val k = math.min(left, buf.length.toLong).toInt; out.write(buf, 0, k); left -= k }
            out.close()
            ex.close()
        }
    }
}

/** A raw-socket runner (com.sun's HttpServer drains request bodies, so it cannot
  * answer before the upload is read). While busy: POST /execute-file gets a 503
  * written right after the request headers, then the socket is reset without
  * reading the body; GET /health reports active == maxConcurrent (2). Afterwards
  * POST reads the whole upload and answers a `[]` result; /health reports 0 of 2. */
class BusyRunner(busyForMs: Long) {
    private val server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val busyUntil: Long = if (busyForMs == Long.MaxValue) Long.MaxValue else System.currentTimeMillis() + busyForMs
    val rejected = new AtomicInteger(0)
    val healthBusy = new AtomicInteger(0)
    val received = new AtomicReference[java.lang.Long](-1L)
    def url: String = "http://127.0.0.1:" + server.getLocalPort
    private def busy: Boolean = System.currentTimeMillis() < busyUntil

    private val acceptor = new Thread(new Runnable {
        override def run(): Unit =
            while (!server.isClosed) {
                val sock = scala.util.Try(server.accept()).toOption
                sock.foreach { s =>
                    val t = new Thread(new Runnable { override def run(): Unit = scala.util.Try(handle(s)) })
                    t.setDaemon(true)
                    t.start()
                }
            }
    })
    acceptor.setDaemon(true)
    acceptor.start()

    def close(): Unit = scala.util.Try(server.close())

    private def readHead(in: InputStream): String = {
        val buf = new ByteArrayOutputStream()
        var last4 = 0
        var b = in.read()
        while (b != -1) {
            buf.write(b)
            last4 = (last4 << 8) | b
            if (last4 == 0x0d0a0d0a) return new String(buf.toByteArray, StandardCharsets.ISO_8859_1)
            b = in.read()
        }
        new String(buf.toByteArray, StandardCharsets.ISO_8859_1)
    }

    private def respond(s: java.net.Socket, status: String, body: String, extra: String = ""): Unit = {
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length +
            "\r\nConnection: close\r\n" + extra + "\r\n"
        val out = s.getOutputStream
        out.write(head.getBytes(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private def handle(s: java.net.Socket): Unit = {
        val in = new java.io.BufferedInputStream(s.getInputStream)
        val head = readHead(in)
        val requestLine = head.takeWhile(_ != '\r')
        val length = "(?im)^content-length:\\s*(\\d+)".r.findFirstMatchIn(head).map(_.group(1).toLong).getOrElse(0L)
        if (requestLine.startsWith("GET /health")) {
            val active = if (busy) { healthBusy.incrementAndGet(); 2 }
            else 0
            respond(s, "200 OK", "{\"ok\": true, \"active\": " + active + ", \"maxConcurrent\": 2}")
            s.close()
        } else if (busy) {
            rejected.incrementAndGet()
            respond(
                s,
                "503 Service Unavailable",
                "{\"error\": \"runner busy\", \"retryAfterSec\": 1, \"active\": 2, \"maxConcurrent\": 2}",
                "Retry-After: 1\r\n"
            )
            s.setSoLinger(true, 0) // reset: the client's upload breaks, the 503 is lost
            s.close()
        } else {
            val meta = CodeGenRunnerSpec.readLine(in)
            var left = length - meta.getBytes(StandardCharsets.UTF_8).length - 1
            var total = 0L
            val buf = new Array[Byte](64 * 1024)
            while (left > 0) {
                val n = in.read(buf, 0, math.min(left, buf.length.toLong).toInt)
                if (n < 0) left = 0 else { left -= n; total += n }
            }
            received.set(total)
            val line = "{\"stdout\": \"[]\", \"stderr\": \"\", \"exitCode\": 0, \"timedOut\": false, \"outputBytes\": 0}\n"
            val bytes = line.getBytes(StandardCharsets.UTF_8)
            val out = s.getOutputStream
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1)
            )
            out.write(bytes)
            out.flush()
            s.close()
        }
    }
}

/** Runs CodeGenRunner.run in a small-heap child JVM (see the streaming test). */
object CodeGenRunnerStreamingChild {
    def main(args: Array[String]): Unit = {
        val Array(url, token, script, input, output) = args
        try {
            val r = CodeGenRunner.run(
                java.nio.file.Paths.get(script),
                java.nio.file.Paths.get(input),
                Some(java.nio.file.Paths.get(output)),
                120,
                CodeGenRunner.Settings(enabled = true, url = url, token = token)
            )
            println("CHILD-OK exit=" + r.exitCode)
            System.exit(if (r.exitCode == 0) 0 else 3)
        } catch {
            case t: Throwable =>
                t.printStackTrace()
                System.exit(2)
        }
    }
}
