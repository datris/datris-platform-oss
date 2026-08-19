package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/** Runs a Python subprocess with a SCRUBBED environment so LLM-generated or
  * otherwise-untrusted scripts cannot read the server's secret-bearing
  * environment — VAULT_TOKEN, ANTHROPIC_API_KEY / OPENAI_API_KEY, AWS_*, DB
  * passwords, and every other var the JVM container holds.
  *
  * `scala.sys.process.Process` cannot provide this: it ADDS any extra env onto
  * the inherited JVM environment, so `os.environ` in the child would still
  * contain every secret. `java.lang.ProcessBuilder` lets us start from an EMPTY
  * environment and add back only a minimal allowlist of benign system vars the
  * Python runtime and TLS need. This is the same isolation TapScriptRunner
  * applies to tap scripts; CodeGen DQ / transformation scripts run through here
  * for the same reason. */
object SandboxedPython {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Benign, non-secret system vars restored into the otherwise-empty child
      * environment. Deliberately excludes anything credential-bearing. Kept in
      * sync with `TapScriptRunner.NonSecretEnvVars`. */
    private val NonSecretEnvVars: Set[String] = Set(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "OLDPWD",
        "LANG", "TERM", "TZ", "TMPDIR", "TMP", "TEMP", "HOSTNAME",
        "PYTHONPATH", "PYTHONHOME", "PYTHONUNBUFFERED", "VIRTUAL_ENV",
        "LD_LIBRARY_PATH", "SSL_CERT_FILE", "SSL_CERT_DIR",
        "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE"
    )

    case class Result(exitCode: Int, stdout: String, stderr: String)

    /** Run `command` (e.g. `Seq("python3", scriptPath, dataPath)`) with a
      * scrubbed environment and a wall-clock timeout. The child never inherits
      * the JVM's secret environment. Throws `DatrisException` on timeout (after
      * force-killing the process); returns the captured streams and exit code
      * otherwise — the caller decides how to treat a non-zero exit. */
    def run(command: Seq[String], timeoutSec: Int): Result = {
        val stdout = new StringBuilder
        val stderr = new StringBuilder

        val pb = new java.lang.ProcessBuilder(command: _*)
        val childEnv = pb.environment()
        childEnv.clear()
        NonSecretEnvVars.foreach { k => sys.env.get(k).foreach(v => childEnv.put(k, v)) }

        val process = pb.start()
        process.getOutputStream.close() // script reads no stdin; signal EOF

        // Drain stdout and stderr on separate daemon threads — a full pipe
        // buffer would otherwise deadlock the child. The main thread reads the
        // buffers only after join(), which establishes happens-before.
        def pump(in: java.io.InputStream, sb: StringBuilder): Thread = {
            val t = new Thread(new Runnable {
                override def run(): Unit = {
                    val reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)
                    )
                    try {
                        var line = reader.readLine()
                        while (line != null) { sb.append(line).append("\n"); line = reader.readLine() }
                    } catch { case _: java.io.IOException => () }
                    finally reader.close()
                }
            })
            t.setDaemon(true)
            t.start()
            t
        }
        val outThread = pump(process.getInputStream, stdout)
        val errThread = pump(process.getErrorStream, stderr)

        val future = Future { process.waitFor() }
        try {
            val exitCode = Await.result(future, timeoutSec.seconds)
            outThread.join(5000)
            errThread.join(5000)
            Result(exitCode, stdout.toString.trim, stderr.toString.trim)
        } catch {
            case _: java.util.concurrent.TimeoutException =>
                process.destroyForcibly()
                throw new DatrisException("Sandboxed script timed out after " + timeoutSec + " seconds")
        }
    }
}
