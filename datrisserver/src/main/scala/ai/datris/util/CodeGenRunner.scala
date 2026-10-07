package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import com.google.gson.{JsonObject, JsonParser}
import org.apache.http.HttpHeaders
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.{ContentType, InputStreamEntity}
import org.apache.http.impl.client.HttpClients
import org.slf4j.{Logger, LoggerFactory}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, FilterInputStream, InputStream, SequenceInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Where generated data-quality and transformation scripts run
  * (plans/stories/codegen-script-isolation.md).
  *
  * `USE_CODEGEN_RUNNER=true` (the compose default): the script and its staged
  * input are pushed to the `datris-codegen-runner` sidecar (`POST
  * /execute-file`), which holds no platform secrets, cannot see this
  * container's filesystem and sits on the internal-only `codegen-net`. The
  * runner is then REQUIRED: unreachable, an older image (404) or a rejected
  * token fails the rule or transformation with a message naming the runner;
  * there is never an in-process fallback.
  *
  * Not enabled (sbt / IDE, or an install still on an older compose file): the
  * script runs in-process through [[SandboxedPython]] as before, with a warning.
  *
  * Wire format of `/execute-file`: request = one JSON line
  * `{"script","timeoutSec","inputName","outputName"?}` + `\n` + the raw input
  * bytes, with a known Content-Length; response = one JSON line
  * `{"stdout","stderr","exitCode","timedOut","outputBytes"}` + `\n` + the
  * output file bytes. Both directions are streamed in 64 KB chunks; neither
  * the input nor the output is ever held whole in memory. */
object CodeGenRunner {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    val DefaultUrl = "http://datris-codegen-runner:8090"
    val ServiceName = "datris-codegen-runner"
    val ScratchVolume = "codegen-scratch"
    private val Chunk = 64 * 1024
    private val ErrorBodyLimit = 4096
    private val NoSpace = "No space left on device"

    case class Settings(enabled: Boolean, url: String, token: String)

    object Settings {
        def fromEnv: Settings =
            Settings(
                enabled = sys.env.getOrElse("USE_CODEGEN_RUNNER", "false").trim.equalsIgnoreCase("true"),
                url = sys.env.get("CODEGEN_RUNNER_URL").map(_.trim).filter(_.nonEmpty).getOrElse(DefaultUrl),
                token = Option(TapScriptRunner.resolvedTapRunnerToken).getOrElse("")
            )
    }

    def enabled: Boolean = Settings.fromEnv.enabled
    def url: String = Settings.fromEnv.url

    /** Run `script` with `input` as `sys.argv[1]` (and `output`, when given, as
      * `sys.argv[2]`). A script that ran and exited, zero or not, is a returned
      * Result; the caller decides what non-zero means. A timeout throws, as
      * `SandboxedPython.run` does. Every transport failure throws a
      * DatrisException whose message starts with "CodeGen runner". */
    def run(script: Path, input: Path, output: Option[Path], timeoutSec: Int): SandboxedPython.Result =
        run(script, input, output, timeoutSec, Settings.fromEnv)

    private[util] def run(script: Path, input: Path, output: Option[Path], timeoutSec: Int, settings: Settings): SandboxedPython.Result =
        if (!settings.enabled) {
            warnInProcess("run")
            val start = System.currentTimeMillis()
            val r = SandboxedPython.run(Seq("python3", script.toString, input.toString) ++ output.map(_.toString).toSeq, timeoutSec)
            logger.info("CodeGen script ran in-process in " + (System.currentTimeMillis() - start) + " ms")
            r
        } else viaRunner(script, input, output, timeoutSec, settings)

    def health(): Either[String, Unit] = health(Settings.fromEnv)

    /** GET /health, then a token-checked `/execute-file` with a one-line script
      * and a few input bytes, which proves the image is new enough, the token
      * is accepted and the scratch volume is writable. Left = why it failed. */
    private[util] def health(settings: Settings): Either[String, Unit] = {
        val base = settings.url.stripSuffix("/")
        try {
            val config = RequestConfig.custom().setConnectTimeout(3000).setConnectionRequestTimeout(3000).setSocketTimeout(5000).build()
            val client = HttpClients.custom().setDefaultRequestConfig(config).build()
            try {
                val res = client.execute(new HttpGet(base + "/health"))
                try {
                    val status = res.getStatusLine.getStatusCode
                    if (status != 200) return Left("GET " + base + "/health answered " + status)
                } finally res.close()
            } finally client.close()
        } catch {
            case e: Exception => return Left(describe(e) + ": " + base)
        }
        val dir = Files.createTempDirectory("codegen-health-")
        try {
            val script = Files.write(dir.resolve("probe.py"), "import sys\nprint(open(sys.argv[1]).read().strip())\n".getBytes(StandardCharsets.UTF_8))
            val input = Files.write(dir.resolve("probe.txt"), "ok\n".getBytes(StandardCharsets.UTF_8))
            val r = viaRunner(script, input, None, 20, settings, quiet = true)
            if (r.exitCode == 0 && r.stdout.trim == "ok") Right(())
            else Left("probe script exited " + r.exitCode + ": " + r.stderr.take(300))
        } catch {
            case e: DatrisException => Left(e.getMessage)
            case e: Exception => Left(describe(e) + ": " + base)
        } finally {
            scala.util.Try(Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p)))
        }
    }

    /** Startup: with the runner on, the shared tap-runner token must be a real one. */
    def assertConfig(): Unit = {
        val s = Settings.fromEnv
        if (s.enabled && TapScriptRunner.isWeakTapRunnerToken(s.token))
            throw new DatrisException(
                "USE_CODEGEN_RUNNER=true but TAP_RUNNER_TOKEN is empty or the changeme default. " +
                    "The CodeGen runner shares the tap runner's token; scripts/install.sh and docker/vault-init.sh mint one on first boot."
            )
    }

    def warnInProcess(where: String): Unit =
        if (where == "startup")
            logger.warn(
                "************************************************************************\n" +
                    "GENERATED DQ / TRANSFORMATION SCRIPTS RUN IN-PROCESS (USE_CODEGEN_RUNNER is not true).\n" +
                    "They share this container's filesystem and network (Vault, databases, MinIO, internet).\n" +
                    "Compose installs should isolate them in " + ServiceName + ": refresh docker-compose.yml.\n" +
                    "sbt / IDE without the sidecar is expected.\n" +
                    "************************************************************************"
            )
        else
            logger.warn(
                "CodeGen script running IN-PROCESS (" + where + "; USE_CODEGEN_RUNNER is not true) — not isolated in " + ServiceName
            )

    // ------------------------------------------------------------------ transport

    private def describe(e: Throwable): String = {
        val msg = Option(e.getMessage).getOrElse("")
        e match {
            case _: java.net.ConnectException => "Connection refused" + (if (msg.nonEmpty && !msg.contains("refused")) " (" + msg + ")" else "")
            case _: java.net.UnknownHostException => "Unknown host " + msg
            case _: java.net.SocketTimeoutException => "Timed out" + (if (msg.nonEmpty) " (" + msg + ")" else "")
            case _ => e.getClass.getSimpleName + (if (msg.nonEmpty) ": " + msg else "")
        }
    }

    private def transportFailure(settings: Settings, detail: String): DatrisException =
        new DatrisException("CodeGen runner (" + ServiceName + " at " + settings.url + ") " + detail)

    /** Records when the last of `expected` bytes has been read (the upload is
      * then done; the HTTP client stops at Content-Length and never reads EOF). */
    private class UploadTimer(in: InputStream, expected: Long) extends FilterInputStream(in) {
        @volatile var doneAt: Long = 0L
        private var sent = 0L
        private def count(n: Int): Int = {
            if (n > 0) sent += n
            if ((n < 0 || sent >= expected) && doneAt == 0L) doneAt = System.currentTimeMillis()
            n
        }
        override def read(): Int = { val b = super.read(); count(if (b < 0) -1 else 1); b }
        override def read(b: Array[Byte], off: Int, len: Int): Int = count(super.read(b, off, math.min(len, Chunk)))
    }

    private def readLine(in: InputStream, limit: Int): Array[Byte] = {
        val buf = new ByteArrayOutputStream()
        var b = in.read()
        while (b != -1 && b != '\n') {
            if (buf.size() >= limit) throw new java.io.IOException("result line longer than " + limit + " bytes")
            buf.write(b)
            b = in.read()
        }
        if (b == -1) throw new java.io.IOException("response ended before the result line")
        buf.toByteArray
    }

    private def readLimited(in: InputStream, limit: Int): String = {
        if (in == null) return ""
        val buf = new Array[Byte](limit)
        var total = 0
        var n = in.read(buf, 0, limit)
        while (n > 0 && total < limit) {
            total += n
            n = if (total < limit) in.read(buf, total, limit - total) else -1
        }
        new String(buf, 0, math.max(total, 0), StandardCharsets.UTF_8)
    }

    private def viaRunner(
        script: Path,
        input: Path,
        output: Option[Path],
        timeoutSec: Int,
        settings: Settings,
        quiet: Boolean = false
    ): SandboxedPython.Result = {
        val base = settings.url.stripSuffix("/")
        val meta = new JsonObject()
        meta.addProperty("script", new String(Files.readAllBytes(script), StandardCharsets.UTF_8)) // the script, not the payload
        meta.addProperty("timeoutSec", Integer.valueOf(timeoutSec))
        meta.addProperty("inputName", Option(input.getFileName).map(_.toString).getOrElse("input"))
        output.foreach(o => meta.addProperty("outputName", Option(o.getFileName).map(_.toString).getOrElse("output")))
        val line = (meta.toString + "\n").getBytes(StandardCharsets.UTF_8)
        val inputSize = Files.size(input)

        val requestConfig = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setConnectionRequestTimeout(10000)
            .setSocketTimeout((timeoutSec + 30) * 1000)
            .build()
        val client = HttpClients.custom().setDefaultRequestConfig(requestConfig).disableAutomaticRetries().build()
        val start = System.currentTimeMillis()
        val body = new UploadTimer(new SequenceInputStream(new ByteArrayInputStream(line), Files.newInputStream(input)), line.length.toLong + inputSize)
        try {
            val post = new HttpPost(base + "/execute-file")
            if (settings.token != null && settings.token.nonEmpty) post.addHeader("Authorization", "Bearer " + settings.token)
            post.addHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            // Known length => Content-Length, never chunked (the stdlib runner does not decode chunked bodies).
            post.setEntity(new InputStreamEntity(body, line.length.toLong + inputSize, ContentType.APPLICATION_OCTET_STREAM))

            val response =
                try client.execute(post)
                catch {
                    case e: java.io.IOException =>
                        // A runner that rejects the request (older image: 404; wrong token: 401) answers
                        // before reading a large body and closes, so the upload dies with a broken pipe
                        // and the status is lost. Re-ask with a tiny probe to name the real cause.
                        val cause = describe(e)
                        if (e.isInstanceOf[java.net.SocketTimeoutException])
                            throw transportFailure(
                                settings,
                                "did not answer within " + (timeoutSec + 30) + " s (script timeout " + timeoutSec + " s plus 30 s): " + cause
                            )
                        val diagnosis = if (quiet || e.isInstanceOf[java.net.ConnectException]) None else health(settings).left.toOption
                        throw diagnosis match {
                            case Some(msg) if msg.contains("CodeGen runner") => new DatrisException(msg)
                            case Some(msg) => transportFailure(settings, "is unreachable: " + cause + " (" + msg + ")")
                            case None => transportFailure(settings, "is unreachable: " + cause)
                        }
                }
            val headersAt = System.currentTimeMillis()
            try {
                val status = response.getStatusLine.getStatusCode
                val entity = response.getEntity
                if (status != 200) {
                    val raw =
                        try readLimited(if (entity == null) null else entity.getContent, ErrorBodyLimit)
                        catch { case _: Exception => "" }
                    throw (status match {
                        case 404 =>
                            transportFailure(
                                settings,
                                "does not support /execute-file (404): its image is older than this server. " +
                                    "Pull the current datrisai/datris-tap-runner image (`docker compose pull " + ServiceName +
                                    "`) and recreate " + ServiceName + "."
                            )
                        case 401 | 403 =>
                            transportFailure(
                                settings,
                                "rejected the token (" + status + "). It shares TAP_RUNNER_TOKEN with datris-tap-runner; " +
                                    "recreate datris and " + ServiceName + " so both read the same token."
                            )
                        case 507 =>
                            transportFailure(
                                settings,
                                "ran out of disk (507): the " + ScratchVolume + " volume is full. Free disk on the Docker host and retry. " +
                                    raw.take(300)
                            )
                        case other => transportFailure(settings, "returned " + other + ": " + raw.take(500))
                    })
                }
                if (entity == null) throw transportFailure(settings, "returned an empty response")
                val in = new java.io.BufferedInputStream(entity.getContent, Chunk)
                try {
                    val obj: JsonObject =
                        // The result line carries the script's stdout (a DQ rule's failure list) and is
                        // held in memory, so it is capped at 64 MB: a rule printing a failure per row
                        // of a huge file fails with "result line longer than ..." instead of a list.
                        try JsonParser.parseString(new String(readLine(in, 64 * 1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject
                        catch { case e: Exception => throw transportFailure(settings, "sent an unreadable result: " + describe(e)) }
                    def str(k: String) = if (obj.has(k) && !obj.get(k).isJsonNull) obj.get(k).getAsString else ""
                    val timedOut = obj.has("timedOut") && !obj.get("timedOut").isJsonNull && obj.get("timedOut").getAsBoolean
                    val exitCode = if (obj.has("exitCode") && !obj.get("exitCode").isJsonNull) obj.get("exitCode").getAsInt else -1
                    val outputBytes = if (obj.has("outputBytes") && !obj.get("outputBytes").isJsonNull) obj.get("outputBytes").getAsLong else 0L

                    var received = 0L
                    output.foreach { o =>
                        val out = Files.newOutputStream(o)
                        try {
                            val buf = new Array[Byte](Chunk)
                            while (received < outputBytes) {
                                val n = in.read(buf, 0, math.min(buf.length.toLong, outputBytes - received).toInt)
                                if (n < 0)
                                    throw transportFailure(settings, "closed the response after " + received + " of " + outputBytes + " output bytes")
                                out.write(buf, 0, n)
                                received += n
                            }
                        } catch {
                            case e: DatrisException => throw e
                            case e: java.io.IOException => throw transportFailure(settings, "output transfer failed: " + describe(e))
                        } finally out.close()
                    }
                    val doneAt = System.currentTimeMillis()
                    val uploadDone = if (body.doneAt > 0) body.doneAt else headersAt
                    if (!quiet)
                        logger.info(
                            "CodeGen runner: sent " + inputSize + " input bytes in " + (uploadDone - start) + " ms, script ran in " +
                                (headersAt - uploadDone) + " ms, received " + received + " output bytes in " + (doneAt - headersAt) +
                                " ms (total " + (doneAt - start) + " ms)"
                        )
                    if (timedOut) throw new DatrisException("Sandboxed script timed out after " + timeoutSec + " seconds")
                    val stderr = str("stderr").trim
                    val stderrOut =
                        if (stderr.contains(NoSpace))
                            stderr + "\n(" + ServiceName + ": the " + ScratchVolume + " volume is full. Free disk on the Docker host and retry.)"
                        else stderr
                    SandboxedPython.Result(exitCode, str("stdout").trim, stderrOut)
                } finally {
                    try in.close()
                    catch { case _: Exception => () }
                }
            } finally response.close()
        } catch {
            case e: DatrisException => throw e
            case e: Exception => throw transportFailure(settings, "request failed: " + describe(e))
        } finally {
            try body.close()
            catch { case _: Exception => () }
            client.close()
        }
    }
}
