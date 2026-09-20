package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.JsonParser
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.JavaConverters._

/** Story: taps survive large sources (plans/stories/tap-large-sources.md) —
  *  the iterator lane of the REAL `WRAPPER_TEMPLATE`, executed under python3
  *  exactly as TapStagingSpec does.
  *
  *  Contract pinned here (story Steps 1-3):
  *
  *  {{{
  *  // fetch() may `yield` records, or return any iterator / generator.
  *  // DATRIS_TAP_OUTPUT set (file path):
  *  //   one-item lookahead sniffs the type from the first item exactly like the
  *  //   list branch — {uri, content} dict → document, dict → json, list/tuple → csv;
  *  //   rows stream to the file in ONE pass; envelope {type, count, columns} is
  *  //   byte-for-byte what the equivalent list produces; no "data" key.
  *  //   empty iterator → type json, count 0, columns [] (file created, 0 bytes).
  *  //   DATRIS_TAP_TEST_LIMIT=N caps the ITERATOR lane at N records and close()s
  *  //   the generator; a list is NEVER truncated.
  *  // DATRIS_TAP_OUTPUT absent (inline legacy path):
  *  //   the iterator is materialised (`list(result)`) with a stderr note naming
  *  //   DATRIS_TAP_OUTPUT, then today's inline envelope {type, data} is printed.
  *  // DATRIS_STATE assigned inside a generator body is committed: the wrapper
  *  //   reads the module global only after the iterator is drained.
  *  }}}
  *
  *  Today (v1.34.0) a generator falls into the "single value" branch: the file
  *  gets one line `"<generator object ...>"`, count 1, columns null; the
  *  inline path dies with `TypeError: Object of type generator is not JSON
  *  serializable`. Every test below fails for one of those two reasons.
  *
  *  The 500,000-row generator case is meant to run with `DATRIS_TEST_XMX=512m`
  *  (build.sbt forwards it as -Xmx). Wrapper tests skip (assume) when python3
  *  is absent.
  */
class TapWrapperStreamingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("tap-wrapper-streaming-spec")

    private lazy val pythonAvailable: Boolean =
        try new ProcessBuilder("python3", "--version").start().waitFor() == 0
        catch { case _: Exception => false }

    private def env(): DatrisEnvironment = DatrisEnvironment(
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
        tapScriptTimeoutSeconds = 300,
        tempDir = root.toString
    )

    private def withEnv[T](e: DatrisEnvironment)(body: => T): T = {
        TenantContext.set(e)
        try body
        finally TenantContext.clear()
    }

    override def afterAll(): Unit = TenantContext.clear()

    // ---- wrapper harness (real WRAPPER_TEMPLATE, real python3) --------------

    /** Run the wrapper. With `filePath = true`, DATRIS_TAP_OUTPUT points at a
      * fresh file; otherwise the variable is absent (inline legacy path).
      * Returns (exitCode, stdout, stderr, outputFile). */
    private def runWrapper(
        scriptBody: String,
        extraEnv: Map[String, String] = Map.empty,
        filePath: Boolean = true
    ): (Int, String, String, Path) = {
        val scriptFile = Files.createTempFile("tap_stream_script_", ".py")
        val wrapperFile = Files.createTempFile("tap_stream_wrapper_", ".py")
        val outputFile = Files.createTempFile("tap_stream_out_", ".ndjson")
        Files.deleteIfExists(outputFile) // the wrapper creates it
        try {
            Files.write(scriptFile, scriptBody.getBytes(StandardCharsets.UTF_8))
            Files.write(wrapperFile, TapScriptRunner.WRAPPER_TEMPLATE.getBytes(StandardCharsets.UTF_8))
            val pb = new ProcessBuilder("python3", wrapperFile.toString, scriptFile.toString)
            pb.environment().remove("DATRIS_TAP_OUTPUT")
            pb.environment().remove("DATRIS_TAP_TEST_LIMIT")
            if (filePath) pb.environment().put("DATRIS_TAP_OUTPUT", outputFile.toString)
            extraEnv.foreach { case (k, v) => pb.environment().put(k, v) }
            val proc = pb.start()
            proc.getOutputStream.close()
            val errBuf = new java.io.ByteArrayOutputStream()
            val errThread = new Thread(() => proc.getErrorStream.transferTo(errBuf))
            errThread.start()
            val out = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
            val code = proc.waitFor()
            errThread.join(5000)
            (code, out.trim, new String(errBuf.toByteArray, StandardCharsets.UTF_8), outputFile)
        } finally {
            Files.deleteIfExists(scriptFile)
            Files.deleteIfExists(wrapperFile)
        }
    }

    private def envelope(stdout: String) = JsonParser.parseString(stdout).getAsJsonObject

    private def fileLines(p: Path): List[String] = Files.readAllLines(p, StandardCharsets.UTF_8).asScala.toList

    private def columnsOf(stdout: String): List[String] = envelope(stdout).getAsJsonArray("columns").asScala.map(_.getAsString).toList

    private def dropStaged(result: TapScriptResult): Unit =
        if (result != null && result.staged != null && result.staged.path != null) {
            val p = Paths.get(result.staged.path)
            Files.deleteIfExists(p)
            StagingArea.delete(p.getParent.getFileName.toString)
        }

    private def scriptTap(name: String = "spec-stream-tap"): TapConfig =
        TapConfig(name = name, description = "spec", targetPipeline = null, scriptPath = "tap-scripts/unused.py")

    // The same five variable-shape rows, once as a generator and once as a list.
    private val RowsBody =
        """def rows():
          |    yield {"id": 1, "name": "a"}
          |    yield {"id": 2, "name": "b", "extra": True}
          |    yield {"id": 3, "name": "c"}
          |    yield {"late": "x", "id": 4}
          |    yield {"id": 5, "name": "e", "extra": None}
          |""".stripMargin

    // ================================================================
    // Acceptance 1 — generator of dicts == equivalent list
    // ================================================================

    test("a generator of dicts streams to the output file with the same envelope as the equivalent list") {
        assume(pythonAvailable, "python3 not available")
        val (listCode, listOut, _, listFile) = runWrapper(RowsBody + "def fetch():\n    return list(rows())\n")
        val (genCode, genOut, _, genFile) = runWrapper(RowsBody + "def fetch():\n    return rows()\n")
        // fetch() itself may be the generator function.
        val (yieldCode, yieldOut, _, yieldFile) = runWrapper(
            """def fetch():
              |    yield {"id": 1, "name": "a"}
              |    yield {"id": 2, "name": "b", "extra": True}
              |    yield {"id": 3, "name": "c"}
              |    yield {"late": "x", "id": 4}
              |    yield {"id": 5, "name": "e", "extra": None}
              |""".stripMargin
        )
        try {
            assert(listCode == 0, listOut)
            assert(genCode == 0, genOut)
            assert(yieldCode == 0, yieldOut)

            val listEnv = envelope(listOut)
            assert(listEnv.get("type").getAsString == "json" && listEnv.get("count").getAsLong == 5L, "list baseline: " + listOut)
            assert(columnsOf(listOut) == List("id", "name", "extra", "late"), "list baseline columns: " + listOut)

            for ((label, out, file) <- Seq(("generator", genOut, genFile), ("yield-in-fetch", yieldOut, yieldFile))) {
                val e = envelope(out)
                assert(e.get("type").getAsString == "json", s"$label: type must be json (sniffed from the first item), got: $out")
                assert(e.get("count").getAsLong == 5L, s"$label: count must be 5 (one per yielded record), got: $out")
                assert(!e.has("data"), s"$label: records must not ride on stdout: $out")
                assert(columnsOf(out) == columnsOf(listOut), s"$label: columns must be the union in first-seen order, identical to the list: $out")
                assert(fileLines(file) == fileLines(listFile), s"$label: the staged NDJSON must be identical to the list's")
            }
            val lines = fileLines(genFile).map(l => JsonParser.parseString(l).getAsJsonObject)
            assert(lines.map(_.get("id").getAsInt) == List(1, 2, 3, 4, 5))
            assert(lines(3).get("late").getAsString == "x", "a key that first appears in record 4 is present")
            assert(lines.forall(l => !l.toString.contains("generator object")), "no repr of the generator may reach the file")
        } finally Seq(listFile, genFile, yieldFile).foreach(Files.deleteIfExists(_))
    }

    test("a generator streams non-string dict keys stringified per row, like the list lane") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """import datetime
              |def fetch():
              |    for i in range(3):
              |        yield {1: i, datetime.date(2026, 9, 20): "d"}
              |""".stripMargin
        )
        try {
            assert(code == 0, out)
            assert(envelope(out).get("count").getAsLong == 3L, out)
            assert(columnsOf(out) == List("1", "2026-09-20"), "keys are stringified for the columns union too: " + out)
            val first = JsonParser.parseString(fileLines(file).head).getAsJsonObject
            assert(first.has("1") && first.has("2026-09-20"), "row keys are stringified on write: " + first)
        } finally Files.deleteIfExists(file)
    }

    test("DATRIS_STATE assigned inside a generator body is committed after the iterator is drained") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    global DATRIS_STATE
              |    for i in range(1, 4):
              |        yield {"id": i}
              |    DATRIS_STATE = {"max_id": 3}
              |""".stripMargin
        )
        try {
            assert(code == 0, out)
            val e = envelope(out)
            assert(e.get("count").getAsLong == 3L, out)
            assert(e.has("state") && e.getAsJsonObject("state").get("max_id").getAsInt == 3, "state set by the generator body must be on the envelope: " + out)
        } finally Files.deleteIfExists(file)
    }

    test("a bare print() inside a generator body goes to stderr, never ahead of the envelope on stdout") {
        assume(pythonAvailable, "python3 not available")
        val script =
            """def fetch():
              |    print("progress: page 1")
              |    yield {"a": 1}
              |    print("progress: page 2")
              |    yield {"a": 2}
              |""".stripMargin
        val (code, out, err, file) = runWrapper(script)
        try {
            assert(code == 0, out + "\n" + err)
            val e = envelope(out) // parses only if stdout is exactly the envelope
            assert(e.get("count").getAsLong == 2L, out)
            assert(!out.contains("progress"), "script prints must not reach stdout: " + out)
            assert(err.contains("progress: page 1") && err.contains("progress: page 2"), "script prints go to stderr: " + err)
        } finally Files.deleteIfExists(file)

        val (c2, o2, e2, _) = runWrapper(script, filePath = false)
        assert(c2 == 0, o2 + "\n" + e2)
        assert(envelope(o2).getAsJsonArray("data").size() == 2, o2)
        assert(!o2.contains("progress") && e2.contains("progress: page 2"), o2 + "\n" + e2)
    }

    // ================================================================
    // Acceptance 2 — type sniff from the first item; empty iterator
    // ================================================================

    test("a generator of lists is typed csv") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    yield ["a", "b"]
              |    yield [1, 2]
              |    yield (3, 4)
              |""".stripMargin
        )
        try {
            assert(code == 0, out)
            val e = envelope(out)
            assert(e.get("type").getAsString == "csv", "list/tuple first item → csv, as for a list of lists: " + out)
            assert(e.get("count").getAsLong == 3L, out)
            assert(!e.has("data"), out)
            assert(
                e.has("columns") && e.get("columns").isJsonArray && e.getAsJsonArray("columns").size() == 0,
                "columns is [] for array rows, as today: " + out
            )
            val lines = fileLines(file)
            assert(lines.size == 3, lines.toString)
            assert(lines.forall(l => JsonParser.parseString(l).isJsonArray), "one JSON array per line: " + lines)
        } finally Files.deleteIfExists(file)
    }

    test("a generator of {uri,content} dicts is typed document") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    global DATRIS_STATE
              |    yield {"uri": "s3://b/k.pdf", "filename": "k.pdf", "content": "aGk="}
              |    yield {"uri": "s3://b/j.pdf", "filename": "j.pdf", "content": "aGk="}
              |    DATRIS_STATE = {"listed_through": "2026-08-01"}
              |""".stripMargin
        )
        try {
            assert(code == 0, out)
            val e = envelope(out)
            assert(e.get("type").getAsString == "document", out)
            assert(e.get("count").getAsLong == 2L, out)
            assert(!e.has("data"), out)
            assert(e.getAsJsonObject("state").get("listed_through").getAsString == "2026-08-01")
            assert(fileLines(file).map(l => JsonParser.parseString(l).getAsJsonObject.get("filename").getAsString) == List("k.pdf", "j.pdf"))
        } finally Files.deleteIfExists(file)
    }

    test("an empty generator is json, count 0, columns []") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    if False:
              |        yield {}
              |""".stripMargin
        )
        try {
            assert(code == 0, out)
            val e = envelope(out)
            assert(e.get("type").getAsString == "json", out)
            assert(e.get("count").getAsLong == 0L, out)
            assert(
                e.has("columns") && e.get("columns").isJsonArray && e.getAsJsonArray("columns").size() == 0,
                "columns must be [] (a record list, empty), not null: " + out
            )
            assert(!e.has("data"), out)
            assert(Files.exists(file), "the wrapper creates the output file even for 0 records")
            assert(Files.size(file) == 0L, "0 records → 0 bytes")
        } finally Files.deleteIfExists(file)
    }

    test("a plain iterator (not a generator) streams too; a str / dict / list keep today's branches") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    return iter([{"id": 1}, {"id": 2}])
              |""".stripMargin
        )
        try {
            assert(code == 0, out)
            assert(envelope(out).get("type").getAsString == "json" && envelope(out).get("count").getAsLong == 2L, out)
            assert(fileLines(file).size == 2)
        } finally Files.deleteIfExists(file)

        // A string is iterable but must never be treated as an iterator of characters.
        val (c2, o2, _, f2) = runWrapper("def fetch():\n    return \"hello world\"\n")
        try {
            assert(c2 == 0, o2)
            assert(envelope(o2).get("type").getAsString == "text" && envelope(o2).get("count").getAsLong == 1L, o2)
        } finally Files.deleteIfExists(f2)

        // A dict is iterable (over keys) but stays a single json payload.
        val (c3, o3, _, f3) = runWrapper("def fetch():\n    return {\"answer\": 42}\n")
        try {
            assert(c3 == 0, o3)
            assert(envelope(o3).get("type").getAsString == "json" && envelope(o3).get("count").getAsLong == 1L, o3)
            assert(envelope(o3).get("columns").isJsonNull, "a single dict keeps columns null: " + o3)
        } finally Files.deleteIfExists(f3)
    }

    // ================================================================
    // Acceptance 3 — DATRIS_TAP_TEST_LIMIT
    // ================================================================

    test("DATRIS_TAP_TEST_LIMIT caps a generator at N records and never truncates a list") {
        assume(pythonAvailable, "python3 not available")
        // The generator reports how many items were pulled and whether it was
        // closed, so the cap is observable from the script side.
        val genScript =
            """import sys
              |def fetch():
              |    pulled = 0
              |    try:
              |        for i in range(1000):
              |            pulled += 1
              |            yield {"i": i}
              |    finally:
              |        print("[script] generator closed pulled=%d" % pulled, file=sys.stderr, flush=True)
              |""".stripMargin
        val (code, out, err, file) = runWrapper(genScript, Map("DATRIS_TAP_TEST_LIMIT" -> "7"))
        try {
            assert(code == 0, out)
            assert(envelope(out).get("count").getAsLong == 7L, "the iterator lane stops at N: " + out)
            assert(fileLines(file).size == 7, fileLines(file).toString)
            assert(err.contains("generator closed"), "the wrapper must close() the generator so its finally runs: " + err)
            assert(err.contains("pulled=7"), "exactly N items are pulled — no run-ahead past the cap: " + err)
        } finally Files.deleteIfExists(file)

        // No limit → the whole generator.
        val (c2, o2, _, f2) = runWrapper(genScript)
        try {
            assert(c2 == 0, o2)
            assert(envelope(o2).get("count").getAsLong == 1000L, o2)
        } finally Files.deleteIfExists(f2)

        // A list is never truncated by the wrapper (TapStagingSpec's test-limit
        // case relies on the SCRIPT honouring the variable).
        val (c3, o3, _, f3) = runWrapper(
            """def fetch():
              |    return [{"i": i} for i in range(20)]
              |""".stripMargin,
            Map("DATRIS_TAP_TEST_LIMIT" -> "7")
        )
        try {
            assert(c3 == 0, o3)
            assert(envelope(o3).get("count").getAsLong == 20L, "a list must never be truncated by the wrapper: " + o3)
            assert(fileLines(f3).size == 20)
        } finally Files.deleteIfExists(f3)
    }

    // ================================================================
    // Acceptance 4 — inline legacy path (no DATRIS_TAP_OUTPUT)
    // ================================================================

    test("the inline path (no DATRIS_TAP_OUTPUT) materialises a generator and emits today's envelope") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, err, _) = runWrapper(RowsBody + "def fetch():\n    return rows()\n", filePath = false)
        assert(code == 0, "inline path must not die on a generator: " + out + "\n" + err)
        val e = envelope(out)
        assert(e.get("type").getAsString == "json", out)
        assert(e.has("data") && e.get("data").isJsonArray, "today's inline envelope carries the records as `data`: " + out)
        val data = e.getAsJsonArray("data")
        assert(data.size() == 5, out)
        assert(data.asScala.map(_.getAsJsonObject.get("id").getAsInt).toList == List(1, 2, 3, 4, 5))
        assert(!e.has("count") && !e.has("columns"), "inline envelope shape is unchanged (no count/columns): " + out)
        assert(err.contains("DATRIS_TAP_OUTPUT"), "the stderr note must name the missing DATRIS_TAP_OUTPUT so the materialisation is explicable: " + err)
        assert(err.contains("fetch() returned 5 json record(s)"), err)

        // csv-shaped generator on the inline path.
        val (c2, o2, _, _) = runWrapper("def fetch():\n    yield [\"a\", \"b\"]\n    yield [1, 2]\n", filePath = false)
        assert(c2 == 0, o2)
        assert(envelope(o2).get("type").getAsString == "csv" && envelope(o2).getAsJsonArray("data").size() == 2, o2)
    }

    // ================================================================
    // Server side — the streamed generator stages through runScript
    // ================================================================

    test("a generator of 500,000 rows stages through runScript with recordCount == 500000 without holding the payload in heap") {
        assume(pythonAvailable, "python3 not available")
        sys.env.get("DATRIS_TEST_XMX").foreach { xmx =>
            val max = Runtime.getRuntime.maxMemory
            assert(
                max <= 600L * 1024 * 1024,
                s"DATRIS_TEST_XMX=$xmx was set but the forked test JVM has maxMemory=$max — build.sbt must forward it as -Xmx"
            )
        }
        val script =
            """def fetch():
              |    pad = "p" * 300
              |    for i in range(500000):
              |        yield {"id": i, "pad": pad}
              |""".stripMargin
        val result = withEnv(env())(TapScriptRunner.runScript(scriptTap(), script))
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.dataType == "json")
            assert(result.recordCount == 500000, "every yielded record is counted, got " + result.recordCount)
            assert(result.staged != null && !result.staged.isEmpty)
            assert(result.staged.format == StagedFormat.NdJson)
            assert(result.staged.arraySource, "a streamed record list keeps arraySource so JSON pipelines see the array shape")
            assert(result.staged.rowCount == 500000L)
            assert(result.staged.bytes > 140L * 1024 * 1024, s"fixture must not fit twice in 512 MB, got ${result.staged.bytes} bytes")
            val it = StagedRows.lines(result.staged.path)
            var n = 0L
            try while (it.hasNext) { it.next(); n += 1 }
            finally it.close()
            assert(n == 500000L)
        } finally dropStaged(result)
    }

    test("a generator of lists stages through runScript as csv with recordCount == the number of yielded rows") {
        assume(pythonAvailable, "python3 not available")
        val result = withEnv(env())(
            TapScriptRunner.runScript(
                scriptTap(),
                """def fetch():
                  |    yield ["EPS Estimate", "Surprise(%)"]
                  |    yield [1.5, 3]
                  |    yield [2, 4]
                  |""".stripMargin
            )
        )
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.dataType == "csv", "csv from the first yielded item, got " + result.dataType)
            assert(result.recordCount == 3, "got " + result.recordCount)
            assert(result.staged.rowCount == 3L)
            val lines = Files.readAllLines(Paths.get(result.staged.path), StandardCharsets.UTF_8).asScala.toList
            assert(lines.size == 3 && lines.forall(l => JsonParser.parseString(l).isJsonArray), lines.toString)
        } finally dropStaged(result)
    }
}
