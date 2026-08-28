package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}

/** Runs the REAL wrapper against a script that calls a local HTTP server, and
  * asserts the per-run platform token is attached as `x-api-key` — for both
  * urllib and requests, only for the platform host, and never overriding a
  * header the script set itself. Skips when python3 is not on PATH. */
class TapWrapperPlatformTokenSpec extends AnyFunSuite {

    private lazy val pythonAvailable: Boolean =
        try new ProcessBuilder("python3", "--version").start().waitFor() == 0
        catch { case _: Exception => false }

    private lazy val pythonInfo: String =
        try {
            val p = new ProcessBuilder("python3", "-c", "import sys; print(sys.executable, sys.version.split()[0])").start()
            new String(p.getInputStream.readAllBytes(), "UTF-8").trim
        } catch { case e: Exception => e.toString }

    private lazy val requestsAvailable: Boolean =
        try new ProcessBuilder("python3", "-c", "import requests").start().waitFor() == 0
        catch { case _: Exception => false }

    /** Script preamble: a one-shot HTTP server on 127.0.0.1 that records the
      * x-api-key header of each request into a list the script returns. */
    private val serverPreamble =
        """import json, os, threading, urllib.request
          |from http.server import BaseHTTPRequestHandler, HTTPServer
          |SEEN = []
          |class H(BaseHTTPRequestHandler):
          |    def do_POST(self):
          |        # Consume the body: closing a socket with unread bytes sends a TCP
          |        # reset, which the client's read() then trips over.
          |        self.rfile.read(int(self.headers.get("Content-Length") or 0))
          |        SEEN.append(self.headers.get("x-api-key"))
          |        body = b'{"ok":true}'
          |        self.send_response(200); self.send_header("Content-Type", "application/json")
          |        self.send_header("Content-Length", str(len(body))); self.end_headers()
          |        self.wfile.write(body)
          |    def log_message(self, *a): pass
          |srv = HTTPServer(("127.0.0.1", 0), H)
          |threading.Thread(target=srv.serve_forever, daemon=True).start()
          |PORT = srv.server_address[1]
          |""".stripMargin

    private def runWrapper(scriptBody: String, env: Map[String, String]): (Int, String, String) = {
        val scriptFile: Path = Files.createTempFile("tap_tok_script_", ".py")
        val wrapperFile: Path = Files.createTempFile("tap_tok_wrapper_", ".py")
        try {
            Files.write(scriptFile, scriptBody.getBytes("UTF-8"))
            Files.write(wrapperFile, TapScriptRunner.WRAPPER_TEMPLATE.getBytes("UTF-8"))
            val pb = new ProcessBuilder("python3", wrapperFile.toString, scriptFile.toString)
            env.foreach { case (k, v) => pb.environment().put(k, v) }
            val proc = pb.start()
            proc.getOutputStream.close()
            // Drain stderr concurrently so a chatty script can't deadlock on a full pipe.
            val errBuf = new java.io.ByteArrayOutputStream()
            val errThread = new Thread(() => proc.getErrorStream.transferTo(errBuf))
            errThread.start()
            val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
            val code = proc.waitFor()
            errThread.join(5000)
            val err = new String(errBuf.toByteArray, "UTF-8")
            (code, out, err + "\n[python: " + pythonInfo + "]")
        } finally {
            Files.deleteIfExists(scriptFile)
            Files.deleteIfExists(wrapperFile)
        }
    }

    private def seen(stdout: String): List[String] = {
        val env = com.google.gson.JsonParser.parseString(stdout.trim).getAsJsonObject
        val data = env.getAsJsonArray("data")
        (0 until data.size()).map(i => if (data.get(i).isJsonNull) null else data.get(i).getAsString).toList
    }

    test("urllib call to the platform host carries the token; a host-set header wins") {
        assume(pythonAvailable, "python3 not available")
        val script = serverPreamble +
            """def fetch():
              |    url = f"http://127.0.0.1:{PORT}/api/v1/query/postgres"
              |    body = json.dumps({"sql": "select 1"}).encode()
              |    urllib.request.urlopen(urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})).read()
              |    urllib.request.urlopen(urllib.request.Request(url, data=body, headers={"x-api-key": "mine"})).read()
              |    return SEEN
              |""".stripMargin
        val (code, out, err) = runWrapper(script, Map("DATRIS_PLATFORM_HOST" -> "127.0.0.1", "DATRIS_PLATFORM_TOKEN" -> "trt_test"))
        assert(code == 0, err)
        assert(seen(out) == List("trt_test", "mine"))
    }

    test("requests call to the platform host carries the token; other hosts do not") {
        assume(pythonAvailable && requestsAvailable, "python3 + requests not available")
        val script = serverPreamble +
            """import requests
              |def fetch():
              |    requests.post(f"http://127.0.0.1:{PORT}/api/v1/query/postgres", json={"sql": "select 1"})
              |    requests.post(f"http://localhost:{PORT}/api/v1/query/postgres", json={"sql": "select 1"})  # not the platform host string
              |    requests.post(f"http://127.0.0.1:{PORT}/x", headers={"X-API-Key": "mine"})
              |    return SEEN
              |""".stripMargin
        val (code, out, err) = runWrapper(script, Map("DATRIS_PLATFORM_HOST" -> "127.0.0.1", "DATRIS_PLATFORM_TOKEN" -> "trt_test"))
        assert(code == 0, err)
        assert(seen(out) == List("trt_test", null, "mine"))
    }

    test("without a token the wrapper installs no hooks and calls are unchanged") {
        assume(pythonAvailable, "python3 not available")
        val script = serverPreamble +
            """def fetch():
              |    urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}/q", data=b"{}")).read()
              |    return SEEN
              |""".stripMargin
        val (code, out, err) = runWrapper(script, Map("DATRIS_PLATFORM_HOST" -> "127.0.0.1"))
        assert(code == 0, err)
        assert(seen(out) == List(null))
    }
}
