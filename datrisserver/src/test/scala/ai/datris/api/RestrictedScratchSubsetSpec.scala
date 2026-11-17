package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.HiddenTapScrub
import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, InMemoryUserDb}
import ai.datris.util.InMemoryUserDb.grants
import ai.datris.util.ScratchReader.ScratchResultPage
import com.google.gson.{Gson, JsonArray, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Catalog-scoped user access, scratch stamp-field subsets on REAL run
  * shapes: StreamNotifier names every job `stream: <pipeline>` (rollup job
  * and every event), and the feed file appears only in its begin event's
  * description (`Stream data received, pipeline: <p>, filename: <f>`). A
  * `provenance.fields` subset without `_datris_tap_run` leaves rows with
  * `_datris_run_id` and `_datris_source` only. bob: restricted, grants
  * `{Sales: edit}`; taps `t_sales` (Sales), `t_hr_feed` (HR), `gone` (not
  * stored); pipeline `orders` in Sales. */
class RestrictedScratchSubsetSpec extends AnyFunSuite with BeforeAndAfterEach {

    private def req(rk: ResolvedKey, path: String = "/api/v1/pipeline/status"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("GET")
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val salesKey: ResolvedKey =
        ResolvedKey(
            None,
            "sales-key",
            Capability.parseList(Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "metadata:read")),
            isLegacyFullAccess = false
        )

    private def tap(n: String, target: String, catalog: String) = TapConfig(name = n, description = "d", targetPipeline = target, catalog = catalog)
    // t_hr_feed now targets payroll, so the hidden name is not pre-seeded by
    // hiddenFeedingTaps: only the rows' own run/feed tie them to it.
    private val storedTaps = Seq(tap("t_sales", "orders", "Sales"), tap("t_hr_feed", "payroll", "HR"))
    private val readTap: String => TapConfig = n => storedTaps.find(_.name == n).orNull
    private val pipelines = Seq(PipelineConfig(name = "orders", catalog = "Sales"))

    private val failReadTap: String => TapConfig = n => throw new AssertionError("readTap must not be called, got " + n)
    private val failRun: String => Option[RunLineage] = id => throw new AssertionError("run must not be called, got " + id)
    private val failDescriptions: () => Seq[String] = () => throw new AssertionError("job descriptions must not be read")
    private val noRun: String => Option[RunLineage] = _ => None

    private var savedEnv: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    // ------------------------------------------------------------ real shapes

    private val StreamFile = "stream: orders"

    /** Exactly what StreamNotifier.processWith writes as the begin event. */
    private def beginDescription(feedFile: String) = "Stream data received, pipeline: orders, filename: " + feedFile

    /** Rows of a `fields: [_datris_run_id, _datris_source, _datris_script_sha]` run, as seen live. */
    private def subsetRows(source: String = "hr-hidden.example", sha: String = ""): JsonArray = {
        val a = new JsonArray()
        Seq(1, 2).foreach { i =>
            val o = new com.google.gson.JsonObject()
            o.addProperty("id", i)
            o.addProperty("_datris_run_id", "tok")
            o.addProperty("_datris_script_sha", sha)
            o.addProperty("_datris_source", source)
            a.add(o)
        }
        a
    }

    private def status(id: Int, state: String, description: String, scratch: ScratchResult = null) =
        PipelineStatus(id, "now", "orders", "StreamNotifier", "pub", "tok", StreamFile, state, "info", description, id.toLong, scratchResult = scratch)

    /** A token query with rollup. `feedFile` = the begin event's file (None: no begin event in the payload). */
    private def response(feedFile: Option[String], rows: JsonArray = subsetRows()): PipelineStatusResponse =
        PipelineStatusResponse(
            PipelineStatusRollup(
                allDone = true,
                status = "success",
                jobs = List(PipelineJobRollup("tok", "orders", StreamFile, "success", "s", "l", "1s", null, resultUri = "u", resultPreview = rows)).asJava
            ),
            (feedFile.map(f => status(1, "begin", beginDescription(f))).toList ++ List(
                status(2, "processing", "Total data size: 10"),
                status(3, "end", "Scratch result written", ScratchResult("u", 2L, "e", rows, false))
            )).asJava
        )

    private def page(rows: JsonArray = subsetRows()) = ScratchResultPage(rows, 2L, 2, 0L, truncated = false, "u", "e")

    private def tapRecord(tapName: String, pipeline: String = "orders"): RunLineage =
        RunLineage("tok", pipeline, input = RunLineageInput("tap", tapName = tapName, filename = "tap-" + tapName + ".csv"))

    /** A run reader that counts reads per run id. */
    private class Runs(rs: (String, RunLineage)*) extends (String => Option[RunLineage]) {
        val calls: mutable.Map[String, Int] = mutable.Map[String, Int]().withDefaultValue(0)
        private val m = rs.toMap
        def apply(id: String): Option[RunLineage] = { calls(id) += 1; m.get(id) }
    }

    private def shown(rk: ResolvedKey, data: AnyRef, run: String => Option[RunLineage]): PipelineStatusResponse =
        PipelineStatusAPIController.restrictedViewWith(req(rk), data, pipelines, storedTaps, run).toOption.get.asInstanceOf[PipelineStatusResponse]

    private def previews(r: PipelineStatusResponse): Seq[JsonArray] =
        r.rollup.jobs.asScala.map(_.resultPreview).toList ++ r.events.asScala.flatMap(e => Option(e.scratchResult).map(_.resultPreview)).toList

    private def assertStripped(rows: JsonArray, ctx: String): Unit = {
        val r = rows.asScala.map(_.getAsJsonObject).toList
        assert(r.size == 2, ctx)
        r.foreach { row =>
            HiddenTapScrub.StampTapColumns.foreach(c => assert(!row.has(c), s"$ctx: keeps $c: $row"))
            assert(row.get("_datris_run_id").getAsString == "tok", ctx)
        }
        assert(r.map(_.get("id").getAsInt) == List(1, 2), ctx)
    }

    private def bobPage(p: ScratchResultPage, run: String => Option[RunLineage], descriptions: () => Seq[String]): ScratchResultPage =
        PipelineStatusAPIController.scrubResultPage(req(bob, "/api/v1/pipeline/result"), p, readTap, StreamFile, "orders", "tok", run, descriptions)

    // ------------------------------------------------------------ tests

    test("streamFeedFilename reads the feed file of StreamNotifier's begin event only") {
        assert(HiddenTapScrub.streamFeedFilename("orders", beginDescription("tap-t_hr_feed.csv")) == Some("tap-t_hr_feed.csv"))
        assert(HiddenTapScrub.streamFeedFilename("payroll", beginDescription("tap-t_hr_feed.csv")) == None, "another pipeline's begin text")
        assert(HiddenTapScrub.streamFeedFilename("orders", "Stream received for pipeline: orders") == None)
        assert(HiddenTapScrub.streamFeedFilename("orders", null) == None)
    }

    test("a hidden tap named by the run record strips the subset columns in rollup, events and /pipeline/result") {
        val runs = new Runs("tok" -> tapRecord("t_hr_feed"))
        val out = shown(bob, response(None), runs)
        previews(out).foreach(assertStripped(_, "status"))
        assert(out.rollup.jobs.get(0).filename == StreamFile, "the job filename is left as is")
        assert(runs.calls("tok") == 1, "one run-lineage read per run id per request: " + runs.calls)
        assert(!new Gson().toJson(out).contains("hr-hidden.example"))

        val runs2 = new Runs("tok" -> tapRecord("t_hr_feed"))
        assertStripped(bobPage(page(), runs2, failDescriptions).records, "/pipeline/result (descriptions not read)")
        assert(runs2.calls("tok") == 1)
        val gone = new Runs("tok" -> tapRecord("gone"))
        assertStripped(bobPage(page(), gone, failDescriptions).records, "deleted tap")
    }

    test("with no run record yet (in-flight run), the begin event's hidden feed file strips the columns and reads tap-[hidden].csv") {
        val out = shown(bob, response(Some("tap-t_hr_feed.csv")), noRun)
        previews(out).foreach(assertStripped(_, "status"))
        val body = new Gson().toJson(out)
        assert(!body.contains("t_hr_feed") && body.contains("tap-[hidden].csv"), body)

        assertStripped(bobPage(page(), noRun, () => Seq(beginDescription("tap-t_hr_feed.json"), "done")).records, "/result .json")
        assertStripped(bobPage(page(), noRun, () => Seq(beginDescription("tap-gone.csv"))).records, "/result deleted tap")
    }

    test("a readable feeding tap keeps the columns: the very same payload and page back") {
        val data = response(None)
        assert(
            PipelineStatusAPIController.restrictedViewWith(req(bob), data, pipelines, storedTaps, new Runs("tok" -> tapRecord("t_sales"))).toOption.get eq data
        )
        val viaBegin = response(Some("tap-t_sales.csv"))
        assert(PipelineStatusAPIController.restrictedViewWith(req(bob), viaBegin, pipelines, storedTaps, noRun).toOption.get eq viaBegin)
        val p = page()
        assert(bobPage(p, new Runs("tok" -> tapRecord("t_sales")), failDescriptions) eq p)
        val p2 = page()
        assert(bobPage(p2, noRun, () => Seq(beginDescription("tap-t_sales.csv"))) eq p2)
    }

    test("an upload run keeps the columns") {
        val upload = RunLineage("tok", "orders", input = RunLineageInput("upload", filename = "orders.csv"))
        val data = response(Some("orders.csv"), subsetRows(source = "uploader-value"))
        assert(PipelineStatusAPIController.restrictedViewWith(req(bob), data, pipelines, storedTaps, new Runs("tok" -> upload)).toOption.get eq data)
        val p = page(subsetRows(source = "uploader-value"))
        assert(bobPage(p, new Runs("tok" -> upload), failDescriptions) eq p)
        // A real upload stamps no tap source: untouched even with no record.
        val empty = response(Some("orders.csv"), subsetRows(source = ""))
        assert(PipelineStatusAPIController.restrictedViewWith(req(bob), empty, pipelines, storedTaps, noRun).toOption.get eq empty)
    }

    test("a feeding tap that cannot be determined strips non-empty tap columns (fail closed)") {
        // No run record and no begin event naming a feed file.
        previews(shown(bob, response(None), noRun)).foreach(assertStripped(_, "no record"))
        // A document tap's begin event names the document, not a feed file.
        previews(shown(bob, response(Some("report.pdf")), noRun)).foreach(assertStripped(_, "document name"))
        // A record filed under another pipeline is not used.
        previews(shown(bob, response(None), new Runs("tok" -> tapRecord("t_sales", pipeline = "payroll")))).foreach(assertStripped(_, "other pipeline"))
        assertStripped(bobPage(page(), noRun, () => Seq("done")).records, "/result")
        // Only _datris_script_sha non-empty also counts.
        assertStripped(bobPage(page(subsetRows(source = "", sha = "abc123")), noRun, () => Nil).records, "/result sha only")
    }

    test("rows whose tap columns are all empty or absent are untouched and no run record is read") {
        val data = response(None, subsetRows(source = "", sha = ""))
        assert(PipelineStatusAPIController.restrictedViewWith(req(bob), data, pipelines, storedTaps, failRun).toOption.get eq data)
        val p = page(JsonParser.parseString("""[{"id":1,"_datris_run_id":"tok"},{"id":2,"_datris_run_id":"tok","_datris_source":null}]""").getAsJsonArray)
        assert(bobPage(p, failRun, failDescriptions) eq p)
    }

    test("run-lineage reads are bounded per request") {
        val rows = new JsonArray()
        (1 to 40).foreach { i =>
            val o = new com.google.gson.JsonObject()
            o.addProperty("_datris_run_id", "r" + i)
            o.addProperty("_datris_source", "x.example")
            rows.add(o)
        }
        val runs = new Runs()
        val out = bobPage(page(rows), runs, () => Nil)
        assert(runs.calls.values.sum <= 16, runs.calls.toString)
        out.records.asScala.foreach(r => HiddenTapScrub.StampTapColumns.foreach(c => assert(!r.getAsJsonObject.has(c))))
    }

    test("unrestricted editor and catalog-scoped API key: the very same payload and page, no reader called") {
        Seq(editor, salesKey).foreach { rk =>
            val data = response(Some("tap-t_hr_feed.csv"))
            assert(PipelineStatusAPIController.restrictedViewWith(req(rk), data, pipelines, storedTaps, failRun).toOption.get eq data, rk.label)
            assert(PipelineStatusAPIController.restrictedView(req(rk), data).toOption.get eq data, rk.label)
            val p = page()
            assert(
                PipelineStatusAPIController.scrubResultPage(
                    req(rk, "/api/v1/pipeline/result"),
                    p,
                    failReadTap,
                    StreamFile,
                    "orders",
                    "tok",
                    failRun,
                    failDescriptions
                ) eq p,
                rk.label
            )
        }
    }
}
