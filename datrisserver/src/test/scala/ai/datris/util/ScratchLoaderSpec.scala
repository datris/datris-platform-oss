package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{Gson, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path => NioPath}
import java.time.{Duration, Instant}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** Scratch destination loader (plans/stories/scratch-destination-server.md).
  *
  *  `ScratchLoader(jobContext).process()` writes the post-transformation
  *  `Data` as one JSON-lines object at `_scratch/<pipeline>/<pipelineToken>.jsonl`
  *  in the `<env>-data` bucket, then records a `ScratchResult` on the status
  *  stream via `StatusUtil.scratchResult`.
  *
  *  Expected seams (no MinIO, no SparkSession, no DatrisEnvironment in unit
  *  tests — `DatrisEnvironment.current` is null here):
  *
  *  {{{
  *  object ScratchPaths {
  *      def prefix(pipeline: String): String                 // "_scratch/<pipeline>/", guarded
  *      def key(pipeline: String, pipelineToken: String): String // "_scratch/<pipeline>/<token>.jsonl"
  *  }
  *  object ScratchLoader {
  *      // rootUri: where "_scratch/..." keys are written. Production resolves
  *      // "s3a://<env>-data"; the spec passes a file:// directory, which the
  *      // Hadoop FileSystem the story names resolves with a plain Configuration.
  *      case class Settings(rootUri: String, inlineRows: Int, retentionHours: Int)
  *  }
  *  class ScratchLoader(jobContext: JobContext, settings: ScratchLoader.Settings) { def process(): Unit }
  *  case class ScratchResult(resultUri, resultRowCount, resultExpiresAt, resultPreview, resultTruncated)
  *  }}}
  *
  *  `resultPreview` is only required to be Gson-serialisable to a JSON array of
  *  the first N record objects — the concrete collection type is not pinned.
  */
class ScratchLoaderSpec extends AnyFunSuite {

    private val gson = new Gson
    private val TOKEN = "job-token-1"
    private val PIPELINE = "scratch_pipe"

    private class RecordingStatusUtil extends StatusUtil {
        // (code, state, description) in the order they were sent
        val messages = new ListBuffer[(String, String, String)]()
        var result: ScratchResult = _
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += (("info", state, description))
        override def warn(state: String, description: String): Unit = messages += (("warning", state, description))
        override def error(state: String, description: String): Unit = messages += (("error", state, description))
        override def scratchResult(result: ScratchResult): Unit = {
            this.result = result
            messages += (("result", "end", result.resultUri))
        }
    }

    private def config(source: String, schemaFields: String, extra: String = ""): PipelineConfig =
        gson.fromJson(
            s"""{"name":"$PIPELINE",
               |"source":{"fileAttributes":{$source},"schemaProperties":{"fields":[$schemaFields]}},
               |"destination":{"scratch":{}}$extra}""".stripMargin,
            classOf[PipelineConfig]
        )

    private val csvFields = """{"name":"id","type":"string"},{"name":"name","type":"string"}"""

    private def csvConfig(delimiter: String = ",", extra: String = ""): PipelineConfig =
        config(s""""csvAttributes":{"delimiter":"$delimiter"}""", csvFields, extra)

    private def jsonConfig(everyRowContainsObject: Boolean = false): PipelineConfig =
        config(s""""jsonAttributes":{"everyRowContainsObject":$everyRowContainsObject}""", """{"name":"_json","type":"string"}""")

    private def xmlConfig(): PipelineConfig =
        config(""""xmlAttributes":{}""", """{"name":"_xml","type":"string"}""")

    private def csvData(rows: List[String], header: List[String] = List("id", "name")): Data =
        Data(size = rows.map(_.length.toLong).sum, header = header, headerWithSchema = null, rows = rows, rawData = null)

    private def rawData(raw: String): Data =
        Data(size = raw.length.toLong, header = null, headerWithSchema = null, rows = null, rawData = raw)

    private def ctx(status: StatusUtil, data: Data, cfg: PipelineConfig): JobContext =
        JobContext(
            pipelineToken = TOKEN,
            metadata = PipelineMetadata(PIPELINE, "rows.csv", "/tmp/rows.csv", "pub-1", bulkUpload = false),
            data = data,
            config = cfg,
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = status
        )

    private def tmpRoot(): NioPath = Files.createTempDirectory("scratch-loader-spec")

    private def settings(root: NioPath, inlineRows: Int = 200, retentionHours: Int = 24): ScratchLoader.Settings =
        ScratchLoader.Settings(root.toUri.toString.stripSuffix("/"), inlineRows, retentionHours)

    private def resultFile(root: NioPath): NioPath =
        root.resolve("_scratch").resolve(PIPELINE).resolve(TOKEN + ".jsonl")

    private def fileLines(root: NioPath): List[String] = {
        val f = resultFile(root)
        assert(Files.exists(f), s"expected the scratch object at $f")
        Files.readAllLines(f).asScala.toList.filter(_.trim.nonEmpty)
    }

    private def parsedLines(root: NioPath) = fileLines(root).map(l => JsonParser.parseString(l).getAsJsonObject)

    private def previewArray(result: ScratchResult) = JsonParser.parseString(gson.toJson(result.resultPreview)).getAsJsonArray

    private def run(
        status: RecordingStatusUtil,
        data: Data,
        cfg: PipelineConfig,
        root: NioPath,
        inlineRows: Int = 200,
        retentionHours: Int = 24
    ): ScratchResult = {
        new ScratchLoader(ctx(status, data, cfg), settings(root, inlineRows, retentionHours)).process()
        assert(status.result != null, "loader must record a ScratchResult via statusUtil.scratchResult")
        status.result
    }

    // --- record shaping ---------------------------------------------------------

    test("CSV rows become one JSON object per row keyed by header") {
        val root = tmpRoot()
        val status = new RecordingStatusUtil
        val result = run(status, csvData(List("1,alice", "2,bob", "3")), csvConfig(), root)

        val objs = parsedLines(root)
        assert(objs.size == 3, s"one line per row, got ${fileLines(root)}")
        assert(objs(0).get("id").getAsString == "1" && objs(0).get("name").getAsString == "alice")
        assert(objs(1).get("id").getAsString == "2" && objs(1).get("name").getAsString == "bob")
        // Short rows pad missing columns with "", like KafkaLoader.sendStructuredData
        assert(objs(2).get("id").getAsString == "3" && objs(2).get("name").getAsString == "")
        // Keys follow header order
        assert(objs(0).keySet().asScala.toList == List("id", "name"))
        assert(result.resultRowCount == 3L)
    }

    test("CSV rows split on the configured delimiter") {
        val root = tmpRoot()
        run(new RecordingStatusUtil, csvData(List("1|alice", "2|bob")), csvConfig(delimiter = "|"), root)
        val objs = parsedLines(root)
        assert(objs.size == 2)
        assert(objs(0).get("id").getAsString == "1" && objs(0).get("name").getAsString == "alice")
        assert(objs(1).get("name").getAsString == "bob")
    }

    test("JSON records pass through unchanged") {
        val root = tmpRoot()
        val raw = """[{"a":1,"b":{"c":[1,2],"d":"x"}},{"a":2,"b":null}]"""
        val result = run(new RecordingStatusUtil, rawData(raw), jsonConfig(), root)

        val objs = parsedLines(root)
        val expected = JsonParser.parseString(raw).getAsJsonArray
        assert(objs.size == 2)
        assert(objs(0) == expected.get(0).getAsJsonObject, s"record 0 changed: ${objs(0)}")
        assert(objs(1) == expected.get(1).getAsJsonObject, s"record 1 changed: ${objs(1)}")
        assert(result.resultRowCount == 2L)
    }

    test("JSON with everyRowContainsObject=true splits on newlines, skipping blank lines") {
        val root = tmpRoot()
        val raw = "{\"a\":1}\n\n{\"a\":2,\"nested\":{\"k\":true}}\n"
        val result = run(new RecordingStatusUtil, rawData(raw), jsonConfig(everyRowContainsObject = true), root)

        val objs = parsedLines(root)
        assert(objs.size == 2, s"got ${fileLines(root)}")
        assert(objs(0).get("a").getAsInt == 1)
        assert(objs(1).getAsJsonObject("nested").get("k").getAsBoolean)
        assert(result.resultRowCount == 2L)
    }

    test("a single JSON object (not an array) lands as one record") {
        val root = tmpRoot()
        val result = run(new RecordingStatusUtil, rawData("""{"only":"one"}"""), jsonConfig(), root)
        val objs = parsedLines(root)
        assert(objs.size == 1 && objs.head.get("only").getAsString == "one")
        assert(result.resultRowCount == 1L)
    }

    test("XML raw data lands as a single _xml record") {
        val root = tmpRoot()
        val xml = "<rows><row><id>1</id></row><row><id>2</id></row></rows>"
        val result = run(new RecordingStatusUtil, rawData(xml), xmlConfig(), root)

        val objs = parsedLines(root)
        assert(objs.size == 1, s"XML must land as exactly one record, got ${fileLines(root)}")
        assert(objs.head.keySet().asScala.toList == List("_xml"))
        assert(objs.head.get("_xml").getAsString == xml)
        assert(result.resultRowCount == 1L)
    }

    test("transformation output is what lands in the file") {
        // deduplicate is the one transformation that runs without AI/Nashorn:
        // run the real Transformation step, then feed its JobContext to the loader.
        val root = tmpRoot()
        val status = new RecordingStatusUtil
        val cfg = csvConfig(extra = ""","transformation":{"deduplicate":true}""")
        val before = ctx(status, csvData(List("1,alice", "2,bob", "1,alice")), cfg)
        val transformed = new Transformation(before).process()
        assert(transformed.data.rows.size == 2, "precondition: deduplicate removed the duplicate row")

        new ScratchLoader(transformed, settings(root)).process()

        val objs = parsedLines(root)
        assert(objs.map(_.get("id").getAsString) == List("1", "2"), s"file must hold the transformed rows, got ${fileLines(root)}")
        assert(status.result.resultRowCount == 2L)
    }

    // --- object key / result pointer ---------------------------------------------

    test("the object key is _scratch/<pipeline>/<pipelineToken>.jsonl") {
        assert(ScratchPaths.key(PIPELINE, TOKEN) == "_scratch/scratch_pipe/job-token-1.jsonl")
        assert(ScratchPaths.prefix(PIPELINE) == "_scratch/scratch_pipe/")

        val root = tmpRoot()
        val result = run(new RecordingStatusUtil, csvData(List("1,alice")), csvConfig(), root)
        assert(Files.exists(resultFile(root)), s"object must be written under the key; root listing: ${listAll(root)}")
        assert(result.resultUri != null && result.resultUri.endsWith(ScratchPaths.key(PIPELINE, TOKEN)), s"resultUri was ${result.resultUri}")
    }

    private def listAll(root: NioPath): List[String] =
        Files.walk(root).iterator().asScala.map(p => root.relativize(p).toString).toList

    test("resultExpiresAt is now + retentionHours in ISO-8601 UTC") {
        val root = tmpRoot()
        val before = Instant.now()
        val result = run(new RecordingStatusUtil, csvData(List("1,alice")), csvConfig(), root, retentionHours = 24)
        val after = Instant.now()
        assert(result.resultExpiresAt != null && result.resultExpiresAt.endsWith("Z"), s"expected an ISO-8601 UTC instant, got ${result.resultExpiresAt}")
        val expires = Instant.parse(result.resultExpiresAt)
        assert(!expires.isBefore(before.plus(Duration.ofHours(24)).minusSeconds(1)), s"too early: $expires")
        assert(!expires.isAfter(after.plus(Duration.ofHours(24)).plusSeconds(1)), s"too late: $expires")
    }

    test("status lifecycle: begin, then the scratch result, then end") {
        val root = tmpRoot()
        val status = new RecordingStatusUtil
        run(status, csvData(List("1,alice")), csvConfig(), root)
        val codesAndStates = status.messages.map(m => (m._1, m._2)).toList
        assert(codesAndStates.head == ("info", "begin"), s"first event must be begin, got $codesAndStates")
        assert(codesAndStates.last == ("info", "end"), s"last event must be end, got $codesAndStates")
        val resultIdx = codesAndStates.indexOf(("result", "end"))
        assert(resultIdx > 0 && resultIdx < codesAndStates.size - 1, s"scratch result must be recorded before the end event, got $codesAndStates")
        assert(!status.messages.exists(_._1 == "error"))
    }

    // --- data quality interplay ---------------------------------------------------

    test("a DQ warning writes the whole record set") {
        // DataQuality.dumpResults only warns on non-error findings — no rows are
        // dropped — so a loader running after a warning must land every record.
        val root = tmpRoot()
        val status = new RecordingStatusUtil
        status.warn("processing", "3 warning(s) were found while performing data quality rules")
        val rows = (1 to 5).map(i => s"$i,name$i").toList
        val result = run(status, csvData(rows), csvConfig(), root)
        assert(fileLines(root).size == 5)
        assert(result.resultRowCount == 5L)
        assert(status.messages.exists(_._1 == "warning"), "the warning must still be on the stream")
        assert(status.messages.last == (("info", "end", status.messages.last._3)))
    }

    // --- inline preview ---------------------------------------------------------

    test("resultPreview holds the first scratchInlineRows records and resultTruncated is true when rowCount exceeds the cap") {
        val root = tmpRoot()
        val rows = (1 to 5).map(i => s"$i,name$i").toList
        val result = run(new RecordingStatusUtil, csvData(rows), csvConfig(), root, inlineRows = 2)

        assert(result.resultRowCount == 5L)
        assert(result.resultTruncated, "5 rows > cap of 2 must be truncated")
        val preview = previewArray(result)
        assert(preview.size() == 2, s"preview must hold exactly the cap, got $preview")
        assert(preview.get(0).isJsonObject && preview.get(0).getAsJsonObject.get("id").getAsString == "1")
        assert(preview.get(1).getAsJsonObject.get("id").getAsString == "2")
        // The file still has everything
        assert(fileLines(root).size == 5)
    }

    test("resultTruncated is false and the preview is the whole set at or below the cap") {
        val rootAt = tmpRoot()
        val atCap = run(new RecordingStatusUtil, csvData((1 to 3).map(i => s"$i,n$i").toList), csvConfig(), rootAt, inlineRows = 3)
        assert(!atCap.resultTruncated)
        assert(previewArray(atCap).size() == 3)

        val rootBelow = tmpRoot()
        val below = run(new RecordingStatusUtil, csvData(List("1,a", "2,b")), csvConfig(), rootBelow, inlineRows = 200)
        assert(!below.resultTruncated)
        val preview = previewArray(below)
        assert(preview.size() == 2)
        assert(preview.get(1).getAsJsonObject.get("name").getAsString == "b")
    }

    test("the preview is byte-capped and stays a strict prefix of the file") {
        // A record that does not fit closes the preview for good, even when
        // later records would fit: the preview must never skip a row.
        val root = tmpRoot()
        val status = new RecordingStatusUtil
        val wide = "x" * 600
        val rows = List("1,a", "2," + wide, "3,b", "4,c")
        new ScratchLoader(ctx(status, csvData(rows), csvConfig()), ScratchLoader.Settings(root.toUri.toString.stripSuffix("/"), 200, 24, maxPreviewBytes = 500))
            .process()
        val result = status.result

        assert(fileLines(root).size == 4, "the file still holds every row")
        assert(result.resultRowCount == 4L)
        val preview = previewArray(result)
        assert(preview.size() == 1, s"preview must stop at the oversized record, got $preview")
        assert(preview.get(0).getAsJsonObject.get("id").getAsString == "1")
        assert(result.resultTruncated, "a byte-capped preview is truncated")

        // An oversized FIRST record leaves the preview empty rather than starting at row 2
        val root2 = tmpRoot()
        val status2 = new RecordingStatusUtil
        new ScratchLoader(
            ctx(status2, csvData(List("1," + wide, "2,b")), csvConfig()),
            ScratchLoader.Settings(root2.toUri.toString.stripSuffix("/"), 200, 24, maxPreviewBytes = 500)
        ).process()
        assert(fileLines(root2).size == 2)
        assert(previewArray(status2.result).size() == 0)
        assert(status2.result.resultTruncated)
    }

    // --- delete-with-data prefix guard -------------------------------------------

    test("ScratchPaths.prefix refuses an empty pipeline segment") {
        List(null, "", "   ").foreach { bad =>
            val t = intercept[Exception] { ScratchPaths.prefix(bad) }
            assert(
                t.isInstanceOf[DatrisException] || t.isInstanceOf[IllegalStateException] || t.isInstanceOf[IllegalArgumentException],
                s"'$bad' must be refused with a guard exception, got $t"
            )
        }
    }

    test("ScratchPaths.prefix refuses a computed prefix outside _scratch/") {
        List("..", "../other", "orders/../../x", "/orders").foreach { bad =>
            val t = intercept[Exception] { ScratchPaths.prefix(bad) }
            assert(
                t.isInstanceOf[DatrisException] || t.isInstanceOf[IllegalStateException] || t.isInstanceOf[IllegalArgumentException],
                s"'$bad' must be refused with a guard exception, got $t"
            )
            assert(t.getMessage != null && t.getMessage.contains("_scratch"), s"message must name the _scratch/ guard, got: ${t.getMessage}")
        }
        // The happy path always starts with the scratch prefix and ends with the pipeline's own segment
        val ok = ScratchPaths.prefix("orders")
        assert(ok.startsWith("_scratch/") && ok == "_scratch/orders/")
    }
}
