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

/** Story: taps may yield batches (plans/stories/tap-batch-yield.md) — the batch
  *  lane of the REAL `WRAPPER_TEMPLATE`, executed under python3 exactly as
  *  TapWrapperStreamingSpec does (the harness below is that spec's, copied).
  *
  *  Contract pinned here (story Steps 1-6):
  *
  *  {{{
  *  // fetch() may `yield` a BATCH as well as a row: a pandas DataFrame, a
  *  // pyarrow RecordBatch / Table, or a list whose first element is a dict
  *  // (rows) or a list/tuple (cells).
  *  //   - the envelope is unchanged: `count` is ROWS, `columns` is the
  *  //     first-seen union of column names (only batches with >= 1 row
  *  //     contribute), `type` is sniffed from the FIRST ROW of the first item;
  *  //   - the staged NDJSON is value-identical to yielding the same rows one
  *  //     at a time (df.to_dict("records") / batch.to_pylist());
  *  //   - rows and batches may be mixed in one run;
  *  //   - DATRIS_TAP_TEST_LIMIT counts ROWS: a 10,000-row batch under limit 20
  *  //     writes 20 rows, pulls ONE item and close()s the generator;
  *  //   - a tuple, a list of scalars and a yielded [] are NOT batches — they
  *  //     keep today's meaning exactly;
  *  //   - the inline path (no DATRIS_TAP_OUTPUT) flattens batches into today's
  *  //     {type, data} envelope.
  *  }}}
  *
  *  Today a yielded DataFrame / RecordBatch is written as one line holding its
  *  repr (`default=str`), so count counts BATCHES and columns is []; a yielded
  *  list of dicts is one JSON array line in the csv lane. Every batch test
  *  below fails for one of those reasons. The two backward-compat tests (tuple
  *  / list of scalars, yielded []) pin today's behaviour and must stay green
  *  through the change.
  *
  *  pandas / pyarrow are `assume`d, not required: the spec skips where they are
  *  absent (CI installs both).
  */
class TapWrapperBatchSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("tap-wrapper-batch-spec")

    private lazy val pythonAvailable: Boolean =
        try new ProcessBuilder("python3", "--version").start().waitFor() == 0
        catch { case _: Exception => false }

    private def moduleAvailable(name: String): Boolean =
        try new ProcessBuilder("python3", "-c", s"import $name").start().waitFor() == 0
        catch { case _: Exception => false }

    private lazy val pandasAvailable: Boolean = pythonAvailable && moduleAvailable("pandas")
    private lazy val pyarrowAvailable: Boolean = pythonAvailable && moduleAvailable("pyarrow")

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

    private def runWrapper(
        scriptBody: String,
        extraEnv: Map[String, String] = Map.empty,
        filePath: Boolean = true
    ): (Int, String, String, Path) = {
        val scriptFile = Files.createTempFile("tap_batch_script_", ".py")
        val wrapperFile = Files.createTempFile("tap_batch_wrapper_", ".py")
        val outputFile = Files.createTempFile("tap_batch_out_", ".ndjson")
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

    private def fileBytes(p: Path): Array[Byte] = Files.readAllBytes(p)

    private def columnsOf(stdout: String): List[String] =
        envelope(stdout).getAsJsonArray("columns").asScala.map(_.getAsString).toList

    private def dropStaged(result: TapScriptResult): Unit =
        if (result != null && result.staged != null && result.staged.path != null) {
            val p = Paths.get(result.staged.path)
            Files.deleteIfExists(p)
            StagingArea.delete(p.getParent.getFileName.toString)
        }

    private def scriptTap(name: String = "spec-batch-tap"): TapConfig =
        TapConfig(name = name, description = "spec", targetPipeline = null, scriptPath = "tap-scripts/unused.py")

    // The fixture frame: int, string, naive / microsecond / tz-aware
    // timestamps, None, Decimal, nested dict. The per-row baseline yields
    // EXACTLY the rows this frame materialises (`to_dict("records")`), so any
    // difference is the batch lane's.
    private val FrameFixture =
        """import datetime, decimal
          |import pandas as pd
          |TZ = datetime.timezone(datetime.timedelta(hours=2))
          |def records():
          |    return [
          |        {"id": 1, "name": "a",
          |         "ts": datetime.datetime(2026, 9, 20, 12, 0, 0),
          |         "ts_us": datetime.datetime(2026, 9, 20, 12, 0, 0, 123456),
          |         "ts_tz": datetime.datetime(2026, 9, 20, 12, 0, 0, tzinfo=TZ),
          |         "amount": decimal.Decimal("1.50"),
          |         "nested": {"k": 1}, "note": None},
          |        {"id": 2, "name": "b",
          |         "ts": datetime.datetime(2026, 1, 2, 3, 4, 5),
          |         "ts_us": datetime.datetime(2026, 1, 2, 3, 4, 5),
          |         "ts_tz": datetime.datetime(2026, 1, 2, 3, 4, 5, tzinfo=TZ),
          |         "amount": decimal.Decimal("0.00"),
          |         "nested": {"k": [1, 2]}, "note": "x"},
          |        {"id": 3, "name": "c",
          |         "ts": datetime.datetime(2026, 3, 4, 5, 6, 7),
          |         "ts_us": datetime.datetime(2026, 3, 4, 5, 6, 7, 1),
          |         "ts_tz": datetime.datetime(2026, 3, 4, 5, 6, 7, tzinfo=TZ),
          |         "amount": None,
          |         "nested": None, "note": None},
          |    ]
          |def frame():
          |    return pd.DataFrame(records())
          |""".stripMargin

    private val FixtureColumns = List("id", "name", "ts", "ts_us", "ts_tz", "amount", "nested", "note")

    // ================================================================
    // Acceptance 1 — a generator of DataFrames == the same rows one at a time
    // ================================================================

    test("a generator of DataFrames streams with the same envelope and NDJSON as the same rows yielded one at a time") {
        assume(pandasAvailable, "python3 with pandas not available")
        val (rowCode, rowOut, rowErr, rowFile) = runWrapper(
            FrameFixture +
                """def fetch():
                  |    for _r in frame().to_dict("records"):
                  |        yield _r
                  |""".stripMargin
        )
        val (batchCode, batchOut, batchErr, batchFile) = runWrapper(
            FrameFixture +
                """def fetch():
                  |    df = frame()
                  |    yield df.iloc[0:2]
                  |    yield df.iloc[2:3]
                  |""".stripMargin
        )
        try {
            assert(rowCode == 0, rowOut + "\n" + rowErr)
            assert(batchCode == 0, "a yielded DataFrame must not fail the wrapper: " + batchOut + "\n" + batchErr)

            val rowEnv = envelope(rowOut)
            assert(rowEnv.get("type").getAsString == "json" && rowEnv.get("count").getAsLong == 3L, "row baseline: " + rowOut)
            assert(columnsOf(rowOut) == FixtureColumns, "row baseline columns: " + rowOut)

            val e = envelope(batchOut)
            assert(e.get("type").getAsString == "json", "the type is sniffed from the first ROW of the first batch: " + batchOut)
            assert(e.get("count").getAsLong == 3L, "count is ROWS, not batches: " + batchOut)
            assert(!e.has("data"), "records must not ride on stdout: " + batchOut)
            assert(columnsOf(batchOut) == FixtureColumns, "columns is the frame's columns, in order: " + batchOut)

            assert(fileLines(batchFile).size == 3, "one NDJSON line per ROW: " + fileLines(batchFile))
            assert(
                fileLines(batchFile) == fileLines(rowFile),
                "batch NDJSON must equal the per-row NDJSON:\nbatch: " + fileLines(batchFile) + "\nrows:  " + fileLines(rowFile)
            )
            assert(
                java.util.Arrays.equals(fileBytes(batchFile), fileBytes(rowFile)),
                "the staged bytes must be identical (no float in this fixture needs the documented 15-digit divergence)"
            )
            val first = JsonParser.parseString(fileLines(batchFile).head).getAsJsonObject
            assert(first.get("ts").getAsString == "2026-09-20 12:00:00", "naive timestamp form is unchanged: " + first)
            assert(first.get("ts_us").getAsString == "2026-09-20 12:00:00.123456", "microseconds only where present: " + first)
            assert(first.get("ts_tz").getAsString == "2026-09-20 12:00:00+02:00", "tz offset keeps its colon: " + first)
            assert(first.get("amount").getAsString == "1.50", "Decimal keeps default=str semantics: " + first)
            assert(first.getAsJsonObject("nested").get("k").getAsInt == 1, "a nested dict stays a nested object: " + first)
            assert(fileLines(batchFile).forall(!_.contains("DataFrame")), "no repr of the frame may reach the file")
        } finally Seq(rowFile, batchFile).foreach(Files.deleteIfExists(_))
    }

    // ================================================================
    // Acceptance 2 — arrow batches, list[dict] batches, mixed rows+batches
    // ================================================================

    test("a generator of RecordBatches (and one Table) matches the per-row equivalent") {
        assume(pandasAvailable, "python3 with pandas not available")
        assume(pyarrowAvailable, "pyarrow not available")
        val fixture =
            """import datetime
              |import pyarrow as pa
              |def table():
              |    return pa.table({
              |        "id": pa.array([1, 2, 3, 4], type=pa.int64()),
              |        "name": pa.array(["a", "b", None, "d"], type=pa.string()),
              |        "ts": pa.array([datetime.datetime(2026, 9, 20, 12, 0, 0)] * 4, type=pa.timestamp("us")),
              |    })
              |""".stripMargin
        val (rowCode, rowOut, rowErr, rowFile) = runWrapper(
            fixture +
                """def fetch():
                  |    for _b in table().to_batches(max_chunksize=2):
                  |        for _r in _b.to_pylist():
                  |            yield _r
                  |""".stripMargin
        )
        val (batchCode, batchOut, batchErr, batchFile) = runWrapper(
            fixture +
                """def fetch():
                  |    _batches = table().to_batches(max_chunksize=2)
                  |    yield _batches[0]
                  |    yield table().slice(2, 2)
                  |""".stripMargin
        )
        try {
            assert(rowCode == 0, rowOut + "\n" + rowErr)
            assert(batchCode == 0, "a yielded RecordBatch / Table must not fail the wrapper: " + batchOut + "\n" + batchErr)
            val e = envelope(batchOut)
            assert(e.get("type").getAsString == "json", batchOut)
            assert(e.get("count").getAsLong == 4L, "count is rows across a RecordBatch and a Table: " + batchOut)
            assert(columnsOf(batchOut) == List("id", "name", "ts"), batchOut)
            assert(
                fileLines(batchFile) == fileLines(rowFile),
                "arrow batches must stage the same NDJSON as their to_pylist() rows:\nbatch: " + fileLines(batchFile) + "\nrows:  " + fileLines(rowFile)
            )
            assert(fileLines(batchFile).forall(l => !l.contains("pyarrow")), "no repr of the arrow object may reach the file")
        } finally Seq(rowFile, batchFile).foreach(Files.deleteIfExists(_))
    }

    test("a generator of list[dict] batches is byte-identical to per-row yield") {
        assume(pythonAvailable, "python3 not available")
        val rows =
            """def records():
              |    return [{"id": 1, "name": "a"}, {"id": 2, "extra": True}, {"id": 3, "name": "c"}, {"id": 4}]
              |""".stripMargin
        val (rowCode, rowOut, _, rowFile) = runWrapper(
            rows +
                """def fetch():
                  |    for _r in records():
                  |        yield _r
                  |""".stripMargin
        )
        val (batchCode, batchOut, batchErr, batchFile) = runWrapper(
            rows +
                """def fetch():
                  |    _r = records()
                  |    yield _r[0:2]
                  |    yield _r[2:4]
                  |""".stripMargin
        )
        try {
            assert(rowCode == 0, rowOut)
            assert(batchCode == 0, batchOut + "\n" + batchErr)
            val e = envelope(batchOut)
            assert(e.get("type").getAsString == "json", "a list whose first element is a dict is a BATCH of rows, not a csv row: " + batchOut)
            assert(e.get("count").getAsLong == 4L, "count is rows: " + batchOut)
            assert(columnsOf(batchOut) == List("id", "name", "extra"), "union of row keys in first-seen order: " + batchOut)
            assert(
                java.util.Arrays.equals(fileBytes(batchFile), fileBytes(rowFile)),
                "a list[dict] batch is written through the per-row encoder — byte-identical:\nbatch: " + fileLines(batchFile) + "\nrows:  " + fileLines(rowFile)
            )
        } finally Seq(rowFile, batchFile).foreach(Files.deleteIfExists(_))
    }

    test("rows and batches mixed in one run count every row and union columns in first-seen order") {
        assume(pandasAvailable, "python3 with pandas not available")
        val (code, out, err, file) = runWrapper(
            """import pandas as pd
              |def fetch():
              |    yield {"id": 1, "name": "a"}
              |    yield pd.DataFrame([{"id": 2, "name": "b", "extra": 7}])
              |    yield [{"id": 3, "late": "z"}]
              |    yield {"id": 4, "extra": 9}
              |""".stripMargin
        )
        try {
            assert(code == 0, out + "\n" + err)
            val e = envelope(out)
            assert(e.get("type").getAsString == "json", "the first item is a plain row: type json: " + out)
            assert(e.get("count").getAsLong == 4L, "two rows and two one-row batches = 4 records: " + out)
            assert(columnsOf(out) == List("id", "name", "extra", "late"), "first-seen union across rows AND batches: " + out)
            val ids = fileLines(file).map(l => JsonParser.parseString(l).getAsJsonObject.get("id").getAsInt)
            assert(ids == List(1, 2, 3, 4), "rows stay in yield order: " + fileLines(file))
        } finally Files.deleteIfExists(file)
    }

    // ================================================================
    // Acceptance 3 — the csv lane, and what is NOT a batch
    // ================================================================

    test("a list-of-lists batch in the csv lane writes one array line per inner list, and a later yielded list of cells is still one row") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, err, file) = runWrapper(
            """def fetch():
              |    yield [["h1", "h2"], [1, 2]]
              |    yield [3, 4]
              |""".stripMargin
        )
        try {
            assert(code == 0, out + "\n" + err)
            val e = envelope(out)
            assert(e.get("type").getAsString == "csv", "a list whose first element is a list is a batch of CELL rows: " + out)
            assert(e.get("count").getAsLong == 3L, "two rows from the batch plus one yielded cell row: " + out)
            assert(e.getAsJsonArray("columns").size() == 0, "columns stays [] for array rows: " + out)
            val lines = fileLines(file)
            assert(lines.size == 3, lines.toString)
            assert(lines.forall(l => JsonParser.parseString(l).isJsonArray), "one JSON array per line: " + lines)
            assert(JsonParser.parseString(lines.head).getAsJsonArray.get(0).getAsString == "h1", lines.head)
            assert(JsonParser.parseString(lines(2)).getAsJsonArray.get(1).getAsInt == 4, "the later list of scalars is ONE row: " + lines(2))
        } finally Files.deleteIfExists(file)
    }

    test("a tuple and a list of scalars are single csv rows, never batches") {
        assume(pythonAvailable, "python3 not available")
        val (code, out, err, file) = runWrapper(
            """def fetch():
              |    yield ("a", "b")
              |    yield [1, 2]
              |    yield ({"k": 1}, {"k": 2})
              |""".stripMargin
        )
        try {
            assert(code == 0, out + "\n" + err)
            val e = envelope(out)
            assert(e.get("type").getAsString == "csv", out)
            assert(e.get("count").getAsLong == 3L, "each yielded tuple / list of scalars is exactly ONE row: " + out)
            val lines = fileLines(file)
            assert(lines.size == 3 && lines.forall(l => JsonParser.parseString(l).isJsonArray), lines.toString)
            assert(JsonParser.parseString(lines(2)).getAsJsonArray.size() == 2, "a TUPLE is never a batch, even of dicts: " + lines(2))
        } finally Files.deleteIfExists(file)
    }

    test("a yielded [] keeps today's semantics in both lanes") {
        assume(pythonAvailable, "python3 not available")
        // dict lane: a non-dict row is dropped, as today.
        val (c1, o1, e1, f1) = runWrapper(
            """def fetch():
              |    yield {"id": 1}
              |    yield []
              |    yield {"id": 2}
              |""".stripMargin
        )
        try {
            assert(c1 == 0, o1 + "\n" + e1)
            assert(envelope(o1).get("type").getAsString == "json", o1)
            assert(envelope(o1).get("count").getAsLong == 2L, "[] is dropped in the dict lane, as today: " + o1)
            assert(fileLines(f1).size == 2, fileLines(f1).toString)
        } finally Files.deleteIfExists(f1)

        // csv lane: [] is an empty csv row, as today.
        val (c2, o2, e2, f2) = runWrapper(
            """def fetch():
              |    yield ["a"]
              |    yield []
              |    yield ["b"]
              |""".stripMargin
        )
        try {
            assert(c2 == 0, o2 + "\n" + e2)
            assert(envelope(o2).get("type").getAsString == "csv", o2)
            assert(envelope(o2).get("count").getAsLong == 3L, "[] is an empty csv row, as today: " + o2)
            assert(fileLines(f2)(1) == "[]", "the empty row is written verbatim: " + fileLines(f2))
        } finally Files.deleteIfExists(f2)
    }

    // ================================================================
    // Acceptance 4 — the test limit counts rows; empty batches
    // ================================================================

    test("DATRIS_TAP_TEST_LIMIT=20 over 10,000-row batches writes 20 rows, count 20, pulls one batch, closes the generator") {
        assume(pandasAvailable, "python3 with pandas not available")
        val script =
            """import sys
              |import pandas as pd
              |def fetch():
              |    pulled = 0
              |    try:
              |        for _b in range(50):
              |            pulled += 1
              |            yield pd.DataFrame({"id": range(_b * 10000, (_b + 1) * 10000)})
              |    finally:
              |        print("[script] generator closed pulled=%d" % pulled, file=sys.stderr, flush=True)
              |""".stripMargin
        val (code, out, err, file) = runWrapper(script, Map("DATRIS_TAP_TEST_LIMIT" -> "20"))
        try {
            assert(code == 0, out + "\n" + err)
            val e = envelope(out)
            assert(e.get("count").getAsLong == 20L, "the limit counts ROWS, so a 10,000-row batch is sliced to 20: " + out)
            assert(fileLines(file).size == 20, "20 NDJSON lines: " + fileLines(file).size)
            assert(columnsOf(out) == List("id"), out)
            val ids = fileLines(file).map(l => JsonParser.parseString(l).getAsJsonObject.get("id").getAsInt)
            assert(ids == (0 until 20).toList, "the first 20 rows of the first batch: " + ids)
            assert(err.contains("generator closed"), "the wrapper must close() the generator so its finally runs: " + err)
            assert(err.contains("pulled=1"), "exactly ONE batch is pulled once the limit is filled: " + err)
        } finally Files.deleteIfExists(file)
    }

    test("an empty batch adds no rows and no columns; a generator of only empty batches is json, count 0, columns []") {
        assume(pandasAvailable, "python3 with pandas not available")
        val (c1, o1, e1, f1) = runWrapper(
            """import pandas as pd
              |def fetch():
              |    yield pd.DataFrame(columns=["ghost"])
              |    yield pd.DataFrame([{"id": 1, "name": "a"}])
              |""".stripMargin
        )
        try {
            assert(c1 == 0, o1 + "\n" + e1)
            val e = envelope(o1)
            assert(e.get("type").getAsString == "json", "an empty first batch still types json and keeps pulling: " + o1)
            assert(e.get("count").getAsLong == 1L, "an empty batch adds no rows: " + o1)
            assert(columnsOf(o1) == List("id", "name"), "only batches with >= 1 row contribute columns: " + o1)
            assert(fileLines(f1).size == 1, fileLines(f1).toString)
        } finally Files.deleteIfExists(f1)

        val (c2, o2, e2, f2) = runWrapper(
            """import pandas as pd
              |def fetch():
              |    for _ in range(3):
              |        yield pd.DataFrame(columns=["ghost"])
              |""".stripMargin
        )
        try {
            assert(c2 == 0, o2 + "\n" + e2)
            val e = envelope(o2)
            assert(e.get("type").getAsString == "json", o2)
            assert(e.get("count").getAsLong == 0L, "only empty batches → 0 records: " + o2)
            assert(e.get("columns").isJsonArray && e.getAsJsonArray("columns").size() == 0, "columns must be [], not null: " + o2)
            assert(Files.exists(f2) && Files.size(f2) == 0L, "0 records → an empty file, as for an empty generator")
        } finally Files.deleteIfExists(f2)
    }

    // ================================================================
    // Acceptance 5 — the parity table
    // ================================================================

    // Each case: the python expression, and whether pyarrow can carry it.
    private val ParityCases: List[(String, String)] = List(
        "naive datetime" -> "datetime.datetime(2026, 9, 20, 12, 0, 0)",
        "microsecond datetime" -> "datetime.datetime(2026, 9, 20, 12, 0, 0, 123456)",
        "tz-aware datetime" -> "datetime.datetime(2026, 9, 20, 12, 0, 0, tzinfo=datetime.timezone(datetime.timedelta(hours=2)))",
        "date" -> "datetime.date(2026, 9, 20)",
        "pd.Timestamp" -> "pd.Timestamp(\"2026-09-20 12:00:00\")",
        "NaT" -> "pd.NaT",
        "Decimal" -> "decimal.Decimal(\"1.50\")",
        "NaN" -> "float(\"nan\")",
        "Infinity" -> "float(\"inf\")",
        "-Infinity" -> "float(\"-inf\")",
        "None" -> "None",
        "bytes" -> "b\"hi\"",
        "numpy bool" -> "numpy.bool_(True)",
        "nested dict with datetime" -> "{\"k\": datetime.datetime(2026, 9, 20, 12, 0, 0)}"
    )

    private val ParityPreamble =
        """import datetime, decimal
          |import numpy
          |import pandas as pd
          |""".stripMargin

    private def parityLine(valueExpr: String, mode: String): (Int, String, String, String) = {
        val body = mode match {
            case "row" => s"""def fetch():\n    yield {"v": $valueExpr}\n"""
            case "frame" => s"""def fetch():\n    yield pd.DataFrame([{"v": $valueExpr}])\n"""
            case "arrow" => s"""import pyarrow as pa\ndef fetch():\n    yield pa.RecordBatch.from_pylist([{"v": $valueExpr}])\n"""
        }
        val (code, out, err, file) = runWrapper(ParityPreamble + body)
        try {
            val line = if (Files.exists(file)) fileLines(file).headOption.getOrElse("") else ""
            (code, out, err, line)
        } finally Files.deleteIfExists(file)
    }

    test("every parity-table value yields an identical NDJSON line as a row and as a one-row DataFrame") {
        assume(pandasAvailable, "python3 with pandas not available")
        val failures = ParityCases.flatMap { case (label, expr) =>
            val (rowCode, rowOut, rowErr, rowLine) = parityLine(expr, "row")
            val (frameCode, frameOut, frameErr, frameLine) = parityLine(expr, "frame")
            if (rowCode != 0) Some(s"$label: the per-row baseline itself failed: $rowOut $rowErr")
            else if (frameCode != 0) Some(s"$label: the one-row DataFrame failed: $frameOut $frameErr")
            else if (rowLine != frameLine) Some(s"$label: row wrote $rowLine but the DataFrame wrote $frameLine")
            else None
        }
        assert(failures.isEmpty, "batch/row NDJSON divergence:\n" + failures.mkString("\n"))
    }

    /** True when pyarrow can BUILD a one-row batch holding this value at all —
      * probed outside the wrapper, so a value arrow itself refuses (NaT) is
      * skipped rather than blamed on the wrapper. */
    private def arrowCanCarry(expr: String): Boolean =
        try {
            val probe = ParityPreamble + "import pyarrow as pa\npa.RecordBatch.from_pylist([{\"v\": " + expr + "}])\n"
            val pb = new ProcessBuilder("python3", "-c", probe)
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.getInputStream.readAllBytes()
            p.waitFor() == 0
        } catch { case _: Exception => false }

    test("every parity-table value pyarrow can carry yields an identical NDJSON line as a row and as a one-row RecordBatch") {
        assume(pandasAvailable, "python3 with pandas not available")
        assume(pyarrowAvailable, "pyarrow not available")
        // Values arrow cannot represent at all are covered by the DataFrame
        // table above.
        val carried = ParityCases.filter { case (_, expr) => arrowCanCarry(expr) }
        assert(carried.size >= 8, "the arrow parity table lost most of its cases: " + carried.map(_._1))
        val failures = carried.flatMap { case (label, expr) =>
            val (rowCode, _, rowErr, rowLine) = parityLine(expr, "row")
            val (arrowCode, arrowOut, arrowErr, arrowLine) = parityLine(expr, "arrow")
            if (rowCode != 0) Some(s"$label: the per-row baseline itself failed: $rowErr")
            else if (arrowCode != 0) Some(s"$label: the one-row RecordBatch failed: $arrowOut $arrowErr")
            else if (rowLine != arrowLine) Some(s"$label: row wrote $rowLine but the RecordBatch wrote $arrowLine")
            else None
        }
        assert(failures.isEmpty, "batch/row NDJSON divergence (arrow):\n" + failures.mkString("\n"))
    }

    test("a float needing 17 digits parses within 1e-15 relative and keeps the same json type") {
        assume(pandasAvailable, "python3 with pandas not available")
        val expr = "0.1 + 0.2"
        val (rowCode, _, rowErr, rowLine) = parityLine(expr, "row")
        val (frameCode, _, frameErr, frameLine) = parityLine(expr, "frame")
        assert(rowCode == 0, rowErr)
        assert(frameCode == 0, frameErr)
        val rowJson = JsonParser.parseString(rowLine)
        val frameJson = JsonParser.parseString(frameLine)
        assert(rowJson.isJsonObject, "the per-row baseline must write a record: " + rowLine)
        assert(frameJson.isJsonObject, "a one-row DataFrame must write a RECORD, not its repr: " + frameLine)
        val rowVal = rowJson.getAsJsonObject.get("v")
        val frameVal = frameJson.getAsJsonObject.get("v")
        assert(rowVal.getAsJsonPrimitive.isNumber && frameVal.getAsJsonPrimitive.isNumber, s"same json type: $rowLine vs $frameLine")
        val a = rowVal.getAsDouble
        val b = frameVal.getAsDouble
        assert(math.abs(a - b) <= 1e-15 * math.abs(a), s"the documented 15-digit divergence must stay within 1e-15 relative: $rowLine vs $frameLine")
    }

    // ================================================================
    // Acceptance 6 — inline path, and staging through runScript
    // ================================================================

    test("the inline path materialises batches into today's {type, data} envelope") {
        assume(pandasAvailable, "python3 with pandas not available")
        val (code, out, err, _) = runWrapper(
            """import pandas as pd
              |def fetch():
              |    yield pd.DataFrame([{"id": 1, "name": "a"}, {"id": 2, "name": "b"}])
              |    yield {"id": 3, "name": "c"}
              |""".stripMargin,
            filePath = false
        )
        assert(code == 0, "the inline path must not die on a batch: " + out + "\n" + err)
        val e = envelope(out)
        assert(e.get("type").getAsString == "json", out)
        assert(e.has("data") && e.get("data").isJsonArray, "today's inline envelope carries the records as `data`: " + out)
        val data = e.getAsJsonArray("data")
        assert(data.size() == 3, "a batch is flattened into its rows inline too: " + out)
        assert(data.asScala.map(_.getAsJsonObject.get("id").getAsInt).toList == List(1, 2, 3), out)
        assert(!e.has("count") && !e.has("columns"), "inline envelope shape is unchanged: " + out)
        assert(err.contains("DATRIS_TAP_OUTPUT"), "the stderr note naming DATRIS_TAP_OUTPUT is unchanged: " + err)
    }

    test("a generator of 50 DataFrames of 10,000 rows stages through runScript with recordCount == 500000") {
        assume(pandasAvailable, "python3 with pandas not available")
        val script =
            """import pandas as pd
              |def fetch():
              |    for b in range(50):
              |        base = b * 10000
              |        yield pd.DataFrame({"id": range(base, base + 10000), "pad": ["p" * 40] * 10000})
              |""".stripMargin
        val result = withEnv(env())(TapScriptRunner.runScript(scriptTap(), script))
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.dataType == "json", "got " + result.dataType)
            assert(result.recordCount == 500000, "every ROW of every batch is counted, got " + result.recordCount)
            assert(result.staged != null && !result.staged.isEmpty)
            assert(result.staged.format == StagedFormat.NdJson)
            assert(result.staged.arraySource, "a streamed batch list keeps arraySource so JSON pipelines see the array shape")
            assert(result.staged.rowCount == 500000L, "got " + result.staged.rowCount)
            val it = StagedRows.lines(result.staged.path)
            var n = 0L
            try while (it.hasNext) { it.next(); n += 1 }
            finally it.close()
            assert(n == 500000L, "one NDJSON line per row, got " + n)
        } finally dropStaged(result)
    }
}
