package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.JsonParser
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._

/** Tap staging (plans/stories/streaming-pipeline-phase4.md): a tap run no
  *  longer buffers its payload in JVM heap. The Python wrapper writes one JSON
  *  value per line to the file named in `DATRIS_TAP_OUTPUT` and prints only a
  *  small envelope; the server stages that file and hands it downstream.
  *
  *  Seams this spec relies on:
  *
  *  {{{
  *  // wrapper (executed for real, like TapWrapperStateSpec):
  *  //   env DATRIS_TAP_OUTPUT=<path>  -> the file gets the records; stdout gets
  *  //   {"type": ..., "count": N, "columns": [...] | null, "state": {...}?}  and NO "data" key.
  *  //   list of dicts -> one compact JSON object per line, `columns` = union of keys, first-seen order
  *  //   xml / text    -> the raw string verbatim (not JSON-quoted), count 1
  *  //   []            -> empty file, count 0
  *  //   absent var    -> today's inline envelope (TapWrapperStateSpec, unmodified)
  *
  *  case class TapScriptResult(staged: StagedPayload, recordCount: Int, error: String, ..., columns, ..., newState)
  *      // staged: NdJson with arraySource = true for record lists; Xml / Text verbatim; null on error
  *
  *  object TapScriptRunner {
  *      // The body of `run` after the storage read: everything from secret/env
  *      // assembly through execution, envelope parsing and staging. Exposed so
  *      // the script lane is testable without MinIO / a code repository.
  *      private[util] def runScript(tapConfig: TapConfig, scriptContent: String, testLimit: Int = 0,
  *                                  params: Map[String, String] = Map.empty, previousState: String = null): TapScriptResult
  *  }
  *
  *  object TapRunner {
  *      // Streaming projection of a staged NDJSON record list into a Delimited
  *      // staged file: header line = union of keys (first-seen order), then one
  *      // row per record with today's quoting rules; null / absent -> empty.
  *      private[util] def jsonToCsv(staged: StagedPayload, delimiter: String): StagedPayload
  *  }
  *
  *  object StagingArea {
  *      val PayloadBudgetEnvVar: String = "PIPELINE_MAX_PAYLOAD_MB"
  *      val LegacyPayloadBudgetEnvVar: String = "TAP_MAX_OUTPUT_MB"
  *      val DefaultPayloadBudgetMB: Int = 4096
  *      // Effective per-run disk budget for the current environment, MB, 0 = unlimited.
  *      // Precedence: pipelineMaxPayloadMB when >= 0; else tapMaxOutputMB when >= 0; else 4096.
  *      def payloadBudgetMB: Int
  *  }
  *  case class DatrisEnvironment(..., tapMaxOutputMB: Int = -1, ..., pipelineMaxPayloadMB: Int = -1, ...)
  *      // both carry "unset" as -1 so an explicit 0 keeps meaning unlimited
  *  }}}
  *
  *  Budget failure wording (story Step 1):
  *  `The payload exceeded the configured disk budget for one run (PIPELINE_MAX_PAYLOAD_MB = <n> MB, got ~<m> MB).
  *   Raise it, or split the source (for a tap, chunk the source range via run_tap params).`
  *
  *  The 500,000-record case is meant to run with `DATRIS_TEST_XMX=512m`
  *  (build.sbt forwards it as -Xmx); it asserts the cap took effect whenever
  *  the variable is set. Wrapper tests skip (assume) when python3 is absent.
  */
class TapStagingSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("tap-staging-spec")

    private lazy val pythonAvailable: Boolean =
        try new ProcessBuilder("python3", "--version").start().waitFor() == 0
        catch { case _: Exception => false }

    private def env(pipelineMaxPayloadMB: Int = -1, tapMaxOutputMB: Int = -1): DatrisEnvironment = DatrisEnvironment(
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
        // The script lane reads mongoDbConfig.database for DATRIS_MONGODB_DATABASE.
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
        tapScriptTimeoutSeconds = 120,
        tapMaxOutputMB = tapMaxOutputMB,
        tempDir = root.toString,
        pipelineMaxPayloadMB = pipelineMaxPayloadMB
    )

    private def withEnv[T](e: DatrisEnvironment)(body: => T): T = {
        TenantContext.set(e)
        try body
        finally TenantContext.clear()
    }

    override def afterAll(): Unit = {
        TenantContext.clear()
        System.clearProperty("datris.allowPrivateEgress")
        if (server != null) server.stop(0)
    }

    // ---- wrapper harness (real WRAPPER_TEMPLATE, real python3) --------------

    /** Run the wrapper with DATRIS_TAP_OUTPUT pointed at a fresh file.
      * Returns (exitCode, stdout, stderr, outputFile). */
    private def runWrapper(scriptBody: String, extraEnv: Map[String, String] = Map.empty): (Int, String, String, Path) = {
        val scriptFile = Files.createTempFile("tap_staging_script_", ".py")
        val wrapperFile = Files.createTempFile("tap_staging_wrapper_", ".py")
        val outputFile = Files.createTempFile("tap_staging_out_", ".ndjson")
        Files.deleteIfExists(outputFile) // the wrapper creates it
        try {
            Files.write(scriptFile, scriptBody.getBytes(StandardCharsets.UTF_8))
            Files.write(wrapperFile, TapScriptRunner.WRAPPER_TEMPLATE.getBytes(StandardCharsets.UTF_8))
            val pb = new ProcessBuilder("python3", wrapperFile.toString, scriptFile.toString)
            pb.environment().put("DATRIS_TAP_OUTPUT", outputFile.toString)
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

    private def fileText(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

    private def stagedLines(staged: StagedPayload): List[String] = fileLines(Paths.get(staged.path))

    private def countLines(staged: StagedPayload): Long = {
        val it = StagedRows.lines(staged.path)
        var n = 0L
        try while (it.hasNext) { it.next(); n += 1 }
        finally it.close()
        n
    }

    private def regularFilesUnder(dir: Path): List[Path] = {
        if (!Files.isDirectory(dir)) return Nil
        val s = Files.walk(dir)
        try s.iterator().asScala.filter(Files.isRegularFile(_)).toList
        finally s.close()
    }

    private def dropStaged(result: TapScriptResult): Unit =
        if (result != null && result.staged != null && result.staged.path != null) {
            val p = Paths.get(result.staged.path)
            Files.deleteIfExists(p)
            StagingArea.delete(p.getParent.getFileName.toString)
        }

    private def scriptTap(name: String = "spec-script-tap"): TapConfig =
        TapConfig(name = name, description = "spec", targetPipeline = null, scriptPath = "tap-scripts/unused.py")

    // ================================================================
    // Acceptance 2 — the real wrapper with DATRIS_TAP_OUTPUT set, per shape
    // ================================================================

    test("wrapper: structured JSON rows go to the file as NDJSON; envelope has type/count/columns/state and no data") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    global DATRIS_STATE
              |    DATRIS_STATE = {"last_updated": 1785850779844, "tag": "abc"}
              |    return [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}, {"id": 3, "name": "c"}]
              |""".stripMargin
        )
        assert(code == 0, out)
        val env = envelope(out)
        assert(env.get("type").getAsString == "json")
        assert(env.get("count").getAsLong == 3L)
        assert(!env.has("data"), s"records must not ride on stdout any more: $out")
        assert(env.has("columns"))
        assert(env.getAsJsonArray("columns").asScala.map(_.getAsString).toList == List("id", "name"))
        // State stays verbatim on the small stdout envelope; integer cursors survive.
        assert(TapScriptRunner.extractStateJson(out) == """{"last_updated":1785850779844,"tag":"abc"}""")

        val lines = fileLines(file)
        assert(lines.size == 3, s"expected 3 NDJSON lines, got: $lines")
        assert(lines.map(l => JsonParser.parseString(l).getAsJsonObject.get("id").getAsInt) == List(1, 2, 3))
        assert(lines.forall(l => !l.contains("\n")), "one compact JSON value per line")
        Files.deleteIfExists(file)
    }

    test("wrapper: rows with variable keys keep the union in first-seen order and later-only keys are present") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    return [{"a": 1}, {"a": 2, "b": "x"}, {"c": 3, "a": 3}]
              |""".stripMargin
        )
        assert(code == 0, out)
        val env = envelope(out)
        assert(env.get("type").getAsString == "json", "type sniffing is unchanged: list of dicts is json")
        assert(env.get("count").getAsLong == 3L)
        assert(env.getAsJsonArray("columns").asScala.map(_.getAsString).toList == List("a", "b", "c"))
        assert(!env.has("data"))

        val lines = fileLines(file).map(l => JsonParser.parseString(l).getAsJsonObject)
        assert(lines.size == 3)
        assert(lines(1).get("b").getAsString == "x")
        assert(lines(2).get("c").getAsInt == 3, "a key that first appears in the last record is still there")
        assert(!lines(0).has("b") || lines(0).get("b").isJsonNull, "the wrapper does not invent values for missing keys")
        Files.deleteIfExists(file)
    }

    test("wrapper: csv (list of lists) rows go to the file one JSON array per line") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    return [["a", "b"], [1, 2], [3, 4]]
              |""".stripMargin
        )
        assert(code == 0, out)
        val env = envelope(out)
        assert(env.get("type").getAsString == "csv")
        assert(env.get("count").getAsLong == 3L)
        assert(!env.has("data"))
        assert(env.has("columns"))
        val lines = fileLines(file)
        assert(lines.size == 3)
        assert(lines.forall(l => JsonParser.parseString(l).isJsonArray))
        Files.deleteIfExists(file)
    }

    test("wrapper: an XML string is written verbatim (not JSON-quoted) with count 1") {
        assume(pythonAvailable, "python3 not available")
        val xml = "<?xml version=\"1.0\"?><root><a x=\"1\">t &amp; u</a></root>"
        val (code, out, _, file) = runWrapper(
            "def fetch():\n    return '" + xml.replace("\"", "\\\"") + "'\n"
        )
        assert(code == 0, out)
        val env = envelope(out)
        assert(env.get("type").getAsString == "xml")
        assert(env.get("count").getAsLong == 1L)
        assert(!env.has("data"))
        assert(fileText(file) == xml, s"verbatim, got: ${fileText(file)}")
        Files.deleteIfExists(file)
    }

    test("wrapper: a plain text string is written verbatim with count 1") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    return "hello world\nsecond line\ttab \"quoted\""
              |""".stripMargin
        )
        assert(code == 0, out)
        val env = envelope(out)
        assert(env.get("type").getAsString == "text")
        assert(env.get("count").getAsLong == 1L)
        assert(!env.has("data"))
        assert(fileText(file) == "hello world\nsecond line\ttab \"quoted\"")
        Files.deleteIfExists(file)
    }

    test("wrapper: document records go to the file one per line with type document") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    global DATRIS_STATE
              |    DATRIS_STATE = {"listed_through": "2026-08-01"}
              |    return [{"uri": "s3://b/k.pdf", "filename": "k.pdf", "content": "aGk="},
              |            {"uri": "s3://b/j.pdf", "filename": "j.pdf", "content": "aGk="}]
              |""".stripMargin
        )
        assert(code == 0, out)
        val env = envelope(out)
        assert(env.get("type").getAsString == "document")
        assert(env.get("count").getAsLong == 2L)
        assert(!env.has("data"))
        assert(env.getAsJsonObject("state").get("listed_through").getAsString == "2026-08-01")
        val lines = fileLines(file).map(l => JsonParser.parseString(l).getAsJsonObject)
        assert(lines.map(_.get("filename").getAsString) == List("k.pdf", "j.pdf"))
        Files.deleteIfExists(file)
    }

    test("wrapper: 0 records leaves an empty file and an envelope with count 0") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, _, file) = runWrapper(
            """def fetch():
              |    return []
              |""".stripMargin
        )
        assert(code == 0, out)
        val env = envelope(out)
        assert(env.get("type").getAsString == "json")
        assert(env.get("count").getAsLong == 0L)
        assert(!env.has("data"))
        assert(Files.exists(file), "the wrapper creates the output file even for 0 records")
        assert(Files.size(file) == 0L, "0 records → 0 bytes")
        Files.deleteIfExists(file)
    }

    test("wrapper: the {'records': [...], 'state': {...}} normalization still applies on the file path") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, err, file) = runWrapper(
            """def fetch():
              |    return {"records": [{"id": 1}, {"id": 2}, {"id": 3}], "state": {"updatedafter": "2026-08-03T19:00:00"}}
              |""".stripMargin
        )
        assert(code == 0, out)
        val env = envelope(out)
        assert(env.get("type").getAsString == "json")
        assert(env.get("count").getAsLong == 3L)
        assert(env.getAsJsonObject("state").get("updatedafter").getAsString == "2026-08-03T19:00:00")
        assert(err.contains("normalized"))
        assert(fileLines(file).size == 3)
        Files.deleteIfExists(file)
    }

    test("wrapper: a single dict is one line with count 1; stderr lifecycle lines are unchanged") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, err, file) = runWrapper(
            """def fetch():
              |    return {"answer": 42}
              |""".stripMargin
        )
        assert(code == 0, out)
        val env = envelope(out)
        assert(env.get("type").getAsString == "json")
        assert(env.get("count").getAsLong == 1L)
        assert(!env.has("data"))
        val lines = fileLines(file)
        assert(lines.size == 1)
        assert(JsonParser.parseString(lines.head).getAsJsonObject.get("answer").getAsInt == 42)
        assert(err.contains("[wrapper] loading tap script"))
        assert(err.contains("[wrapper] calling fetch()"))
        assert(err.contains("[wrapper] fetch() returned 1 json payload"))
        Files.deleteIfExists(file)
    }

    // ================================================================
    // Acceptance 3 — 500,000 records under DATRIS_TEST_XMX=512m; test limit
    // ================================================================

    test("500,000 wrapper records stage with rowCount == 500000 without holding the payload in heap") {
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
              |    return [{"id": i, "pad": pad} for i in range(500000)]
              |""".stripMargin
        val result = withEnv(env())(TapScriptRunner.runScript(scriptTap(), script))
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.dataType == "json")
            assert(result.recordCount == 500000)
            assert(result.staged != null && !result.staged.isEmpty)
            assert(result.staged.format == StagedFormat.NdJson)
            assert(result.staged.arraySource, "a record list keeps arraySource so JSON pipelines see the array shape")
            assert(result.staged.rowCount == 500000L)
            assert(result.staged.bytes > 140L * 1024 * 1024, s"fixture must not fit twice in 512 MB, got ${result.staged.bytes} bytes")
            assert(countLines(result.staged) == 500000L)
            assert(Paths.get(result.staged.path).startsWith(root), "the staged file lives under the staging root")
        } finally dropStaged(result)
    }

    test("DATRIS_TAP_TEST_LIMIT still reaches the script on the file path") {
        assume(pythonAvailable, "python3 not available")
        val script =
            """import os
              |def fetch():
              |    n = int(os.environ.get("DATRIS_TAP_TEST_LIMIT", "1000"))
              |    return [{"i": i} for i in range(n)]
              |""".stripMargin
        val result = withEnv(env())(TapScriptRunner.runScript(scriptTap(), script, testLimit = 7))
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.recordCount == 7)
            assert(result.staged.rowCount == 7L)
            assert(stagedLines(result.staged).size == 7)
        } finally dropStaged(result)
    }

    test("record lists stage as NdJson with arraySource; a single object stages without it; state is verbatim") {
        assume(pythonAvailable, "python3 not available")
        val list = withEnv(env())(
            TapScriptRunner.runScript(
                scriptTap(),
                """def fetch():
                  |    global DATRIS_STATE
                  |    DATRIS_STATE = {"cursor": 1785850779844}
                  |    return [{"id": 1}, {"id": 2}]
                  |""".stripMargin
            )
        )
        try {
            assert(list.error == null, String.valueOf(list.error))
            assert(list.staged.format == StagedFormat.NdJson && list.staged.arraySource)
            assert(list.staged.rowCount == 2L && list.recordCount == 2)
            assert(list.newState == """{"cursor":1785850779844}""")
        } finally dropStaged(list)

        val single = withEnv(env())(
            TapScriptRunner.runScript(
                scriptTap(),
                """def fetch():
                  |    return {"answer": 42}
                  |""".stripMargin
            )
        )
        try {
            assert(single.error == null, String.valueOf(single.error))
            assert(single.staged.format == StagedFormat.NdJson && !single.staged.arraySource)
            assert(single.staged.rowCount == 1L && single.recordCount == 1)
            assert(single.newState == null)
        } finally dropStaged(single)
    }

    test("xml and text results stage verbatim with rowCount 1") {
        assume(pythonAvailable, "python3 not available")
        val xml = withEnv(env())(
            TapScriptRunner.runScript(
                scriptTap(),
                """def fetch():
                  |    return '<?xml version="1.0"?><root><a>1</a></root>'
                  |""".stripMargin
            )
        )
        try {
            assert(xml.error == null, String.valueOf(xml.error))
            assert(xml.dataType == "xml" && xml.recordCount == 1)
            assert(xml.staged.format == StagedFormat.Xml && xml.staged.rowCount == 1L)
            assert(fileText(Paths.get(xml.staged.path)) == "<?xml version=\"1.0\"?><root><a>1</a></root>")
        } finally dropStaged(xml)

        val text = withEnv(env())(
            TapScriptRunner.runScript(
                scriptTap(),
                """def fetch():
                  |    return "just some text\nwith two lines"
                  |""".stripMargin
            )
        )
        try {
            assert(text.error == null, String.valueOf(text.error))
            assert(text.dataType == "text" && text.recordCount == 1)
            assert(text.staged.format == StagedFormat.Text && text.staged.rowCount == 1L)
            assert(fileText(Paths.get(text.staged.path)) == "just some text\nwith two lines")
        } finally dropStaged(text)
    }

    test("0 records: staged payload is empty, recordCount 0, no error (TapRunner records no_records)") {
        assume(pythonAvailable, "python3 not available")
        val result = withEnv(env())(
            TapScriptRunner.runScript(
                scriptTap(),
                """def fetch():
                  |    global DATRIS_STATE
                  |    DATRIS_STATE = {"checked_through": "2026-09-20"}
                  |    return []
                  |""".stripMargin
            )
        )
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.recordCount == 0)
            assert(result.staged == null || result.staged.isEmpty || result.staged.rowCount == 0L)
            // "Caught up, nothing new" still carries its cursor to the commit point.
            assert(result.newState == """{"checked_through":"2026-09-20"}""")
        } finally dropStaged(result)
    }

    // ================================================================
    // Acceptance 4 — killed mid-write, disk budget, alias precedence
    // ================================================================

    test("script killed mid-write: error set, no state, nothing left under the staging root") {
        assume(pythonAvailable, "python3 not available")
        // The 3rd record's value kills the interpreter while it is being
        // serialized, so two lines are already on disk and no envelope is ever
        // printed.
        val script =
            """import os
              |class Bomb:
              |    def __init__(self, i):
              |        self.i = i
              |    def __str__(self):
              |        if self.i == 3:
              |            os._exit(137)
              |        return "v%d" % self.i
              |def fetch():
              |    global DATRIS_STATE
              |    DATRIS_STATE = {"cursor": 99}
              |    return [{"id": i, "v": Bomb(i)} for i in range(1, 6)]
              |""".stripMargin
        val before = regularFilesUnder(root)
        val result = withEnv(env())(TapScriptRunner.runScript(scriptTap(), script))
        assert(result.error != null, "a script that dies without printing an envelope is a failed run")
        assert(result.error.contains("exit code 137") || result.error.contains("137"), result.error)
        assert(result.newState == null, "no envelope → no state commit")
        assert(result.recordCount == 0)
        assert(result.staged == null || result.staged.isEmpty)
        val after = regularFilesUnder(root)
        assert(after.diff(before).isEmpty, s"partial staged file must be dropped, found: ${after.diff(before)}")
    }

    test("oversized output fails with the disk-budget message naming PIPELINE_MAX_PAYLOAD_MB") {
        assume(pythonAvailable, "python3 not available")
        val script =
            """def fetch():
              |    return [{"x": "x" * 1024} for _ in range(1200)]
              |""".stripMargin
        val before = regularFilesUnder(root)
        val result = withEnv(env(pipelineMaxPayloadMB = 1))(TapScriptRunner.runScript(scriptTap(), script))
        assert(result.error != null, "1.2 MB over a 1 MB budget must fail")
        assert(result.error.contains("disk budget"), result.error)
        assert(result.error.contains("PIPELINE_MAX_PAYLOAD_MB = 1 MB"), result.error)
        assert(result.error.contains("got ~"), result.error)
        assert(result.error.contains("run_tap params"), result.error)
        assert(!result.error.contains("MB limit"), "old wording is gone: " + result.error)
        assert(result.newState == null)
        val after = regularFilesUnder(root)
        assert(after.diff(before).isEmpty, s"over-budget staged bytes must be dropped, found: ${after.diff(before)}")
    }

    test("TAP_MAX_OUTPUT_MB alone still caps (alias precedence); the default budget is 4096 MB") {
        assume(pythonAvailable, "python3 not available")
        val script =
            """def fetch():
              |    return [{"x": "x" * 1024} for _ in range(1200)]
              |""".stripMargin
        // Alias only: the legacy value is honoured for one release.
        val aliased = withEnv(env(pipelineMaxPayloadMB = -1, tapMaxOutputMB = 1))(TapScriptRunner.runScript(scriptTap(), script))
        assert(aliased.error != null, "TAP_MAX_OUTPUT_MB=1 with PIPELINE_MAX_PAYLOAD_MB unset must still cap at 1 MB")
        assert(aliased.error.contains("PIPELINE_MAX_PAYLOAD_MB = 1 MB"), aliased.error)

        // Explicit new var wins over the alias.
        val explicit = withEnv(env(pipelineMaxPayloadMB = 8, tapMaxOutputMB = 1))(TapScriptRunner.runScript(scriptTap(), script))
        try assert(explicit.error == null, "PIPELINE_MAX_PAYLOAD_MB=8 must win over TAP_MAX_OUTPUT_MB=1: " + explicit.error)
        finally dropStaged(explicit)

        // Neither set: 1.2 MB is nowhere near 4096 MB.
        val unset = withEnv(env(pipelineMaxPayloadMB = -1, tapMaxOutputMB = -1))(TapScriptRunner.runScript(scriptTap(), script))
        try assert(unset.error == null, "the default budget is 4096 MB, not 100: " + unset.error)
        finally dropStaged(unset)
    }

    test("payload budget resolution: explicit wins, alias next, 4096 default, 0 = unlimited") {
        assert(StagingArea.PayloadBudgetEnvVar == "PIPELINE_MAX_PAYLOAD_MB")
        assert(StagingArea.LegacyPayloadBudgetEnvVar == "TAP_MAX_OUTPUT_MB")
        assert(StagingArea.DefaultPayloadBudgetMB == 4096)
        assert(withEnv(env(pipelineMaxPayloadMB = -1, tapMaxOutputMB = -1))(StagingArea.payloadBudgetMB) == 4096)
        assert(withEnv(env(pipelineMaxPayloadMB = -1, tapMaxOutputMB = 100))(StagingArea.payloadBudgetMB) == 100)
        assert(withEnv(env(pipelineMaxPayloadMB = 8, tapMaxOutputMB = 100))(StagingArea.payloadBudgetMB) == 8)
        assert(
            withEnv(env(pipelineMaxPayloadMB = 0, tapMaxOutputMB = 100))(StagingArea.payloadBudgetMB) == 0,
            "explicit 0 = unlimited, even with the alias set"
        )
        assert(withEnv(env(pipelineMaxPayloadMB = -1, tapMaxOutputMB = 0))(StagingArea.payloadBudgetMB) == 0, "alias 0 = unlimited")
    }

    // ================================================================
    // Acceptance 5 — csv-shaped result → CSV pipeline (header + N rows, today's
    // normalizeCsvColumns names) and → JSON pipeline (arraySource preserved)
    // ================================================================

    // A loopback HTTP tap endpoint: the HTTP lane is the one where a csv-typed
    // envelope carries dict rows, so it is where normalizeCsvColumns runs today.
    private var server: HttpServer = _
    private val responseBody = new AtomicReference[String]("{}")

    private def ensureServer(): Int = {
        if (server == null) {
            System.setProperty("datris.allowPrivateEgress", "true")
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext(
                "/tap",
                new HttpHandler {
                    override def handle(exchange: HttpExchange): Unit = {
                        exchange.getRequestBody.readAllBytes()
                        val bytes = responseBody.get().getBytes(StandardCharsets.UTF_8)
                        exchange.getResponseHeaders.set("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.length.toLong)
                        exchange.getResponseBody.write(bytes)
                        exchange.close()
                    }
                }
            )
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
            server.start()
        }
        server.getAddress.getPort
    }

    private def httpTap(port: Int): TapConfig =
        TapConfig(name = "spec-http-tap", description = "spec", targetPipeline = null, scriptKind = "http", endpointUrl = "http://127.0.0.1:" + port + "/tap")

    test("csv-shaped HTTP result: normalized union columns, staged rows rewritten, jsonToCsv gives header + N rows") {
        val port = ensureServer()
        responseBody.set(
            """{"type": "csv", "data": [
              |  {"EPS Estimate": 1.5, "Surprise(%)": 3},
              |  {"EPS Estimate": 2, "Later Key": "a,b"},
              |  {"Later Key": "plain", "Surprise(%)": null}
              |]}""".stripMargin
        )
        val result = withEnv(env())(TapScriptRunner.run(httpTap(port)))
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.dataType == "csv")
            assert(result.recordCount == 3)
            assert(result.columns != null)
            // Identical to today's normalizeCsvColumns: union of keys, first-seen order, normalized.
            assert(result.columns.asScala.toList == List("eps_estimate", "surprise_percent", "later_key"))
            assert(result.staged.format == StagedFormat.NdJson)
            assert(result.staged.arraySource, "a csv-shaped record list is still an array-shaped JSON payload")
            assert(result.staged.rowCount == 3L)

            val rows = stagedLines(result.staged).map(l => JsonParser.parseString(l).getAsJsonObject)
            assert(rows.size == 3)
            // Every record carries every union key, in union order, null when absent.
            rows.foreach(r => assert(r.keySet().asScala.toList == List("eps_estimate", "surprise_percent", "later_key"), r.toString))
            assert(rows(0).get("later_key").isJsonNull)
            assert(rows(1).get("surprise_percent").isJsonNull)
            assert(rows(2).get("eps_estimate").isJsonNull)
            assert(rows(0).get("surprise_percent").getAsString == "3", "numbers survive verbatim (no 3.0)")

            val csv = withEnv(env())(TapRunner.jsonToCsv(result.staged, ","))
            try {
                assert(csv.format == StagedFormat.Delimited(","))
                val lines = fileLines(Paths.get(csv.path))
                assert(lines.size == 4, s"header + 3 rows, got: $lines")
                assert(lines.head == "eps_estimate,surprise_percent,later_key")
                assert(lines(1) == "1.5,3,")
                assert(lines(2) == "2,,\"a,b\"", "a value holding the delimiter is quoted exactly as today")
                assert(lines(3) == ",,plain")
            } finally dropStaged(TapScriptResult(csv, 0, null))
        } finally dropStaged(result)
    }

    test("json-typed script rows into a CSV pipeline: jsonToCsv keeps raw keys, union order, today's quoting") {
        assume(pythonAvailable, "python3 not available")
        val script =
            """def fetch():
              |    return [{"EPS Estimate": 1.5, "b": "q\"x"}, {"EPS Estimate": 2, "c": None, "d": "line1\nline2"}]
              |""".stripMargin
        val result = withEnv(env())(TapScriptRunner.runScript(scriptTap(), script))
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.dataType == "json")
            assert(result.staged.arraySource)
            val csv = withEnv(env())(TapRunner.jsonToCsv(result.staged, ","))
            try {
                val lines = StagedRows.delimited(csv.path, ",")
                val records =
                    try lines.toList
                    finally lines.close()
                assert(records.size == 3, s"header + 2 records, got: $records")
                assert(records.head == "EPS Estimate,b,c,d", "json-typed rows are not column-normalized (today's behaviour)")
                assert(records(1) == "1.5,\"q\"\"x\",,")
                assert(records(2) == "2,,,\"line1\nline2\"", "an embedded newline is one quoted record")
            } finally dropStaged(TapScriptResult(csv, 0, null))
        } finally dropStaged(result)
    }

    test("jsonToCsv of an empty record list yields an empty file") {
        val staged = withEnv(env())(PayloadStager.stageJson("spec-empty", new java.io.StringReader("[]")))
        try {
            assert(staged.rowCount == 0L && staged.arraySource)
            val csv = withEnv(env())(TapRunner.jsonToCsv(staged, ","))
            try assert(Files.size(Paths.get(csv.path)) == 0L)
            finally dropStaged(TapScriptResult(csv, 0, null))
        } finally dropStaged(TapScriptResult(staged, 0, null))
    }
}
