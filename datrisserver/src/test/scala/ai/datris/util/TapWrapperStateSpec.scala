package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

/** Executes the REAL wrapper (TapScriptRunner.WRAPPER_TEMPLATE) against fixture
  * scripts with a live python3, asserting on the envelope printed to stdout.
  * The wrapper is the tap wire format — every tap shape crosses it — so the
  * incremental-state addition is verified against actual execution, not a
  * re-implementation. Skips (via assume) when python3 is not on PATH.
  */
class TapWrapperStateSpec extends AnyFunSuite {

    private lazy val pythonAvailable: Boolean =
        try {
            val p = new ProcessBuilder("python3", "--version").start()
            p.waitFor() == 0
        } catch { case _: Exception => false }

    /** Run the wrapper against a script body. Returns (exitCode, stdout, stderr). */
    private def runWrapper(scriptBody: String, env: Map[String, String] = Map.empty): (Int, String, String) = {
        val scriptFile: Path = Files.createTempFile("tap_spec_script_", ".py")
        val wrapperFile: Path = Files.createTempFile("tap_spec_wrapper_", ".py")
        try {
            Files.write(scriptFile, scriptBody.getBytes("UTF-8"))
            Files.write(wrapperFile, TapScriptRunner.WRAPPER_TEMPLATE.getBytes("UTF-8"))
            val pb = new ProcessBuilder("python3", wrapperFile.toString, scriptFile.toString)
            env.foreach { case (k, v) => pb.environment().put(k, v) }
            val proc = pb.start()
            proc.getOutputStream.close()
            val out = new String(proc.getInputStream.readAllBytes(), "UTF-8")
            val err = new String(proc.getErrorStream.readAllBytes(), "UTF-8")
            val code = proc.waitFor()
            (code, out.trim, err)
        } finally {
            Files.deleteIfExists(scriptFile)
            Files.deleteIfExists(wrapperFile)
        }
    }

    private def envelope(stdout: String) = JsonParser.parseString(stdout).getAsJsonObject

    test("script that sets a dict DATRIS_STATE gets it onto the envelope") {
        assume(pythonAvailable)
        val (code, out, _) = runWrapper(
            """def fetch():
              |    global DATRIS_STATE
              |    DATRIS_STATE = {"max_id": 42, "cursor": "abc"}
              |    return [{"id": 1}, {"id": 2}]
              |""".stripMargin
        )
        assert(code == 0)
        val env = envelope(out)
        assert(env.get("type").getAsString == "json")
        assert(env.has("state"))
        assert(env.getAsJsonObject("state").get("max_id").getAsInt == 42)
        assert(env.getAsJsonObject("state").get("cursor").getAsString == "abc")
    }

    test("script with no DATRIS_STATE produces an envelope without a state key") {
        assume(pythonAvailable)
        val (code, out, _) = runWrapper(
            """def fetch():
              |    return [{"id": 1}]
              |""".stripMargin
        )
        assert(code == 0)
        assert(!envelope(out).has("state"))
    }

    test("non-dict DATRIS_STATE is ignored with a stderr note, not a failure") {
        assume(pythonAvailable)
        val (code, out, err) = runWrapper(
            """def fetch():
              |    global DATRIS_STATE
              |    DATRIS_STATE = "not-a-dict"
              |    return [{"id": 1}]
              |""".stripMargin
        )
        assert(code == 0)
        assert(!envelope(out).has("state"))
        assert(err.contains("DATRIS_STATE ignored"))
    }

    test("non-JSON-serializable state values are coerced via default=str") {
        assume(pythonAvailable)
        val (code, out, _) = runWrapper(
            """import datetime
              |def fetch():
              |    global DATRIS_STATE
              |    DATRIS_STATE = {"as_of": datetime.date(2026, 8, 3)}
              |    return [{"id": 1}]
              |""".stripMargin
        )
        assert(code == 0)
        assert(envelope(out).getAsJsonObject("state").get("as_of").getAsString == "2026-08-03")
    }

    test("round trip: script reads DATRIS_TAP_STATE env var and advances it") {
        assume(pythonAvailable)
        val (code, out, _) = runWrapper(
            """import os, json
              |def fetch():
              |    global DATRIS_STATE
              |    state = json.loads(os.environ.get("DATRIS_TAP_STATE") or "{}")
              |    last = state.get("max_id", 0)
              |    rows = [{"id": i} for i in range(last + 1, last + 3)]
              |    DATRIS_STATE = {"max_id": max(r["id"] for r in rows)}
              |    return rows
              |""".stripMargin,
            Map("DATRIS_TAP_STATE" -> """{"max_id": 10}""")
        )
        assert(code == 0)
        val env = envelope(out)
        val ids = env.getAsJsonArray("data").asScala.map(_.getAsJsonObject.get("id").getAsInt).toList
        assert(ids == List(11, 12))
        assert(env.getAsJsonObject("state").get("max_id").getAsInt == 12)
    }

    test("state emission does not disturb csv (list of lists) type detection") {
        assume(pythonAvailable)
        val (code, out, _) = runWrapper(
            """def fetch():
              |    global DATRIS_STATE
              |    DATRIS_STATE = {"page": 3}
              |    return [["a", "b"], [1, 2]]
              |""".stripMargin
        )
        assert(code == 0)
        val env = envelope(out)
        assert(env.get("type").getAsString == "csv")
        assert(env.getAsJsonObject("state").get("page").getAsInt == 3)
    }

    test("dict return {'records': [...], 'state': {...}} is normalized, not silently 0 records") {
        assume(pythonAvailable)
        // The natural-but-wrong shape code generators produce. Found live in the
        // first smoke test: USGS tap returned this and every run logged a clean
        // no_records while the source had 1000+ events.
        val (code, out, err) = runWrapper(
            """def fetch():
              |    rows = [{"id": 1}, {"id": 2}, {"id": 3}]
              |    return {"records": rows, "state": {"updatedafter": "2026-08-03T19:00:00"}}
              |""".stripMargin
        )
        assert(code == 0)
        val env = envelope(out)
        assert(env.get("type").getAsString == "json")
        assert(env.getAsJsonArray("data").size() == 3)
        assert(env.getAsJsonObject("state").get("updatedafter").getAsString == "2026-08-03T19:00:00")
        assert(err.contains("normalized"))
    }

    test("explicit DATRIS_STATE wins over the state key of a dict return") {
        assume(pythonAvailable)
        val (code, out, _) = runWrapper(
            """def fetch():
              |    global DATRIS_STATE
              |    DATRIS_STATE = {"max_id": 99}
              |    return {"records": [{"id": 1}], "state": {"max_id": 1}}
              |""".stripMargin
        )
        assert(code == 0)
        assert(envelope(out).getAsJsonObject("state").get("max_id").getAsInt == 99)
    }

    test("dict return without a records list stays a plain payload (no normalization)") {
        assume(pythonAvailable)
        val (code, out, _) = runWrapper(
            """def fetch():
              |    return {"answer": 42}
              |""".stripMargin
        )
        assert(code == 0)
        val env = envelope(out)
        assert(env.get("type").getAsString == "json")
        assert(!env.has("state"))
        assert(env.getAsJsonObject("data").get("answer").getAsInt == 42)
    }

    test("extractStateJson preserves integer cursors verbatim (no Double .0 corruption)") {
        // Found live: the gson-Map envelope path re-serialized last_updated
        // 1785850779844 as 1785850779844.0 — a script interpolating that into a
        // source URL would send a malformed cursor.
        val raw = """{"type":"json","data":[{"id":1}],"state":{"last_updated":1785850779844,"tag":"abc"}}"""
        assert(TapScriptRunner.extractStateJson(raw) == """{"last_updated":1785850779844,"tag":"abc"}""")
    }

    test("extractStateJson returns null for absent, non-object, or unparseable state") {
        assert(TapScriptRunner.extractStateJson("""{"type":"json","data":[]}""") == null)
        assert(TapScriptRunner.extractStateJson("""{"type":"json","data":[],"state":"not-an-object"}""") == null)
        assert(TapScriptRunner.extractStateJson("not json at all") == null)
    }

    test("state emission works alongside document-shaped results") {
        assume(pythonAvailable)
        val (code, out, _) = runWrapper(
            """def fetch():
              |    global DATRIS_STATE
              |    DATRIS_STATE = {"listed_through": "2026-08-01"}
              |    return [{"uri": "s3://b/k.pdf", "filename": "k.pdf", "content": "aGk="}]
              |""".stripMargin
        )
        assert(code == 0)
        val env = envelope(out)
        assert(env.get("type").getAsString == "document")
        assert(env.getAsJsonObject("state").get("listed_through").getAsString == "2026-08-01")
    }
}
