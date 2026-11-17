package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.HiddenTapScrub
import ai.datris.config.{CapabilityInterceptor, TenantInterceptor}
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, InMemoryUserDb}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{JsonObject, JsonParser}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
import scala.collection.mutable

/** Catalog-scoped user access, part 3 review follow-ups:
  *  1. a visible pipeline fed by a tap in a catalog the restricted user cannot
  *     read: the tap's name is scrubbed from lineage runs, provenance and
  *     pipeline status for that session; unrestricted sessions and API keys
  *     are byte-identical;
  *  2. the 403 on a table/collection listing for a restricted session carries
  *     a message that does not tell the caller to add metadata:read. */
class RestrictedHiddenTapScrubSpec extends AnyFunSuite with BeforeAndAfterEach {

    private def req(rk: ResolvedKey, method: String = "GET", path: String = "/api/v1/lineage/pipeline/orders"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view")))
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val salesKey: ResolvedKey =
        ResolvedKey(
            None,
            "sales-key",
            Capability.parseList(Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "metadata:read")),
            isLegacyFullAccess = false
        )

    private def tap(n: String, target: String, catalog: String) = TapConfig(name = n, description = "d", targetPipeline = target, catalog = catalog)
    private val taps = Map("t_sales" -> tap("t_sales", "orders", "Sales"), "t_hr_feed" -> tap("t_hr_feed", "orders", "HR"))
    private val readTap: String => TapConfig = n => taps.getOrElse(n, null)

    private val captured = mutable.ArrayBuffer[AuditEntry]()
    private var savedEnv: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        captured.clear()
        AuditLog.sinkOverride = (a: AuditEntry) => captured.synchronized { captured += a; () }
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    // ------------------------------------------------------------ helpers

    test("scrubText replaces a hidden tap name only in tap contexts and never rewrites a table, column or longer name") {
        val hidden = Set("t_hr_feed", "customers", "hr")
        assert(HiddenTapScrub.scrubText("Loaded tap-t_hr_feed.csv from tap 't_hr_feed'", hidden) == "Loaded tap-[hidden].csv from tap '[hidden]'")
        assert(HiddenTapScrub.scrubText("Tap: customers failed; tap customers. tap:customers", hidden) == "Tap: [hidden] failed; tap [hidden]. tap:[hidden]")
        assert(HiddenTapScrub.scrubText("staged tap-docs/customers/a.pdf", hidden) == "staged tap-docs/[hidden]/a.pdf")
        // Finding 2: a destination table or column sharing the name is legitimate text.
        assert(HiddenTapScrub.scrubText("Loaded 500 rows into public.customers", hidden) == "Loaded 500 rows into public.customers")
        assert(HiddenTapScrub.scrubText("column customers is null; customers_clean ran", hidden) == "column customers is null; customers_clean ran")
        // A longer (visible) name sharing a prefix: hidden `hr`, visible tap `hr.eu`.
        assert(HiddenTapScrub.scrubText("tap-hr.eu.csv from tap hr.eu", hidden) == "tap-hr.eu.csv from tap hr.eu")
        assert(HiddenTapScrub.scrubText("tap-hr.csv", hidden) == "tap-[hidden].csv")
        assert(HiddenTapScrub.scrubText("t_hr_feed_v2 and tap-t_hr_feed_v2.csv", hidden) == "t_hr_feed_v2 and tap-t_hr_feed_v2.csv")
        assert(HiddenTapScrub.scrubText(null, hidden) == null)
        assert(HiddenTapScrub.scrubText("tap-t_hr_feed.csv", Set.empty) == "tap-t_hr_feed.csv")
        assert(HiddenTapScrub.scrubFilename("tap-t_hr_feed.json", Set("t_hr_feed")) == "tap-[hidden].json")
        assert(HiddenTapScrub.scrubFilename("t_hr_feed.csv", Set("t_hr_feed")) == "t_hr_feed.csv", "a non-feed filename is untouched")
    }

    test("feedFileNames reads tap names from feed filenames and tap-<name>.csv|json tokens in text") {
        assert(HiddenTapScrub.feedFileNames(Seq("tap-hr.eu.csv")) == Set("hr.eu"))
        assert(HiddenTapScrub.feedFileNames(Seq("tap-gone.json", null, "orders.csv")) == Set("gone"))
        assert(HiddenTapScrub.feedFileNames(Seq("Processing tap-gone.csv and 'tap-other.json' now")) == Set("gone", "other"))
        assert(HiddenTapScrub.feedFileNames(Seq("tap returned a list")).isEmpty)
    }

    test("tapHidden and hiddenFeedingTaps apply to restricted sessions only") {
        assert(HiddenTapScrub.tapHidden(req(bob), "t_hr_feed", readTap))
        assert(HiddenTapScrub.tapHidden(req(bob), "gone", readTap), "a missing tap is hidden")
        assert(!HiddenTapScrub.tapHidden(req(bob), "t_sales", readTap))
        assert(!HiddenTapScrub.tapHidden(req(editor), "t_hr_feed", readTap))
        assert(!HiddenTapScrub.tapHidden(req(salesKey), "t_hr_feed", readTap), "API keys are unchanged")
        val all = taps.values.toSeq :+ tap("t_payroll", "payroll", "HR")
        assert(HiddenTapScrub.hiddenFeedingTaps(req(bob), all, Set("orders")) == Set("t_hr_feed"), "only taps feeding a visible pipeline")
        assert(HiddenTapScrub.hiddenFeedingTaps(req(editor), all, Set("orders")).isEmpty)
        assert(HiddenTapScrub.hiddenFeedingTaps(req(salesKey), all, Set("orders")).isEmpty)
    }

    // ------------------------------------------------------------ lineage

    private def runJson(id: String, tapName: String): JsonObject =
        JsonParser.parseString(
            s"""{"runId":"$id","pipeline":"orders","configVersion":1,"durationMs":5,"recordCount":2,
               |"input":{"kind":"tap","tapName":"$tapName","tapRunTime":"2026-11-16T10:00:00Z","scriptSha":"abc",
               |"source":"tap:$tapName","filename":"tap-$tapName.csv"},"outputs":[]}""".stripMargin
        ).getAsJsonObject

    private def neighborhood: JsonObject = {
        val o = new JsonObject()
        o.addProperty("node", "pipeline:orders")
        val runs = new com.google.gson.JsonArray()
        runs.add(runJson("r1", "t_hr_feed"))
        runs.add(runJson("r2", "t_sales"))
        o.add("runs", runs)
        o
    }

    test("lineage runs of a visible pipeline drop a hidden feeding tap's identity for a restricted session only") {
        val out = LineageAPIController.scrubNeighborhood(req(bob), neighborhood, readTap)
        val runs = out.getAsJsonArray("runs").asScala.map(_.getAsJsonObject).toList
        val hiddenIn = runs.head.getAsJsonObject("input")
        HiddenTapScrub.TapIdentityFields.foreach(f => assert(!hiddenIn.has(f), s"$f must be scrubbed: $hiddenIn"))
        assert(hiddenIn.get("filename").getAsString == "tap-[hidden].csv")
        assert(hiddenIn.get("kind").getAsString == "tap")
        assert(!out.toString.contains("t_hr_feed"), out.toString)
        // The visible tap's run is untouched.
        assert(runs(1).toString == runJson("r2", "t_sales").toString)

        // Unrestricted session and API key: byte-identical.
        Seq(editor, salesKey).foreach { rk =>
            val before = neighborhood
            assert(LineageAPIController.scrubNeighborhood(req(rk), before, readTap).toString == neighborhood.toString)
        }
    }

    // ------------------------------------------------------------ provenance

    private def provenance(tapName: String): JsonObject =
        JsonParser.parseString(
            s"""{"runId":"run-1","pipeline":"orders",
               |"job":{"status":"success","filename":"tap-$tapName.csv"},
               |"input":{"kind":"tap","tapName":"$tapName","tapRunTime":"t","scriptSha":"abc","source":"tap:$tapName","filename":"tap-$tapName.csv"},
               |"tapRun":{"tapName":"$tapName","runTime":"t","status":"success"},
               |"tap":{"name":"$tapName","catalog":"HR"},
               |"source":"tap:$tapName",
               |"configVersion":{"version":1}}""".stripMargin
        ).getAsJsonObject

    test("provenance of a visible pipeline's run drops a hidden tap's blocks, source and filename for a restricted session only") {
        val out = ProvenanceAPIController.scrubProvenance(req(bob, path = "/api/v1/provenance"), provenance("t_hr_feed"), readTap, Set.empty)
        Seq("tapRun", "tap", "source").foreach(k => assert(!out.has(k), s"$k must be gone: $out"))
        assert(out.getAsJsonObject("job").get("filename").getAsString == "tap-[hidden].csv")
        assert(out.getAsJsonObject("input").get("filename").getAsString == "tap-[hidden].csv")
        assert(!out.toString.contains("t_hr_feed"), out.toString)
        assert(out.get("pipeline").getAsString == "orders" && out.has("configVersion") && out.has("job"))

        // A visible tap keeps everything.
        val visible = provenance("t_sales")
        assert(ProvenanceAPIController.scrubProvenance(req(bob), visible, readTap, Set.empty).toString == provenance("t_sales").toString)

        // A run predating the recorded tap identity: only the filename names the tap.
        val old = JsonParser.parseString("""{"runId":"run-1","pipeline":"orders","job":{"filename":"tap-t_hr_feed.csv"}}""").getAsJsonObject
        val scrubbedOld = ProvenanceAPIController.scrubProvenance(req(bob), old, readTap, Set("t_hr_feed"))
        assert(scrubbedOld.getAsJsonObject("job").get("filename").getAsString == "tap-[hidden].csv")

        // Unrestricted session and API key: byte-identical.
        Seq(editor, salesKey).foreach { rk =>
            assert(
                ProvenanceAPIController.scrubProvenance(req(rk), provenance("t_hr_feed"), readTap, Set("t_hr_feed")).toString == provenance("t_hr_feed").toString
            )
        }
    }

    test("a tap no longer stored is hidden: lineage run filename, provenance filenames and tap:<name> source are scrubbed") {
        val run = JsonParser.parseString("""{"runId":"r","pipeline":"orders","input":{"kind":"tap","filename":"tap-gone.csv"}}""").getAsJsonObject
        HiddenTapScrub.scrubRun(run, n => HiddenTapScrub.tapHidden(req(bob), n, readTap))
        assert(run.getAsJsonObject("input").get("filename").getAsString == "tap-[hidden].csv", run.toString)

        val p = JsonParser.parseString(
            """{"runId":"run-1","pipeline":"orders","job":{"filename":"tap-gone.csv"},"input":{"kind":"tap","filename":"tap-gone.csv","source":"tap:gone"},"source":"tap:gone"}"""
        ).getAsJsonObject
        val out = ProvenanceAPIController.scrubProvenance(req(bob), p, readTap, Set.empty)
        assert(out.getAsJsonObject("job").get("filename").getAsString == "tap-[hidden].csv", out.toString)
        assert(out.getAsJsonObject("input").get("filename").getAsString == "tap-[hidden].csv", out.toString)
        assert(!out.has("source") && !out.getAsJsonObject("input").has("source"), out.toString)
        assert(!out.toString.contains("gone"), out.toString)

        // A visible tap's tap:<name> source stays; unrestricted callers are untouched.
        val visible = JsonParser.parseString("""{"runId":"run-1","pipeline":"orders","source":"tap:t_sales"}""").getAsJsonObject
        assert(ProvenanceAPIController.scrubProvenance(req(bob), visible, readTap, Set.empty).has("source"))
        val original =
            """{"runId":"run-1","pipeline":"orders","job":{"filename":"tap-gone.csv"},"input":{"kind":"tap","filename":"tap-gone.csv","source":"tap:gone"},"source":"tap:gone"}"""
        assert(ProvenanceAPIController.scrubProvenance(req(editor), JsonParser.parseString(original).getAsJsonObject, readTap, Set.empty).toString ==
            JsonParser.parseString(original).getAsJsonObject.toString)
    }

    // ------------------------------------------------------------ pipeline status

    private def event(filename: String, description: String) =
        PipelineStatus(1, "now", "orders", "process", "pub", "tok", filename, "end", "OK", description, 1L, aiSummary = "tap 't_hr_feed' returned bad rows")

    private def statusResponse(feedFile: String) = PipelineStatusResponse(
        PipelineStatusRollup(
            allDone = true,
            status = "error",
            jobs = List(
                PipelineJobRollup("tok", "orders", feedFile, "error", "s", "l", "1s", PipelineJobError("p", "row from tap t_hr_feed failed", "tap: t_hr_feed"))
            ).asJava
        ),
        List(event(feedFile, "Processing " + feedFile + " into public.orders")).asJava
    )

    test("pipeline status of a visible pipeline names no hidden feeding tap; empty hidden set returns the same payload") {
        val response = statusResponse("tap-t_hr_feed.csv")
        val gson = new com.google.gson.Gson
        val scrubbed = gson.toJson(PipelineStatusAPIController.scrubFor(Set("t_hr_feed"), response))
        assert(!scrubbed.contains("t_hr_feed"), scrubbed)
        assert(scrubbed.contains("tap-[hidden].csv") && scrubbed.contains("into public.orders"), scrubbed)

        val summaries = List(PipelineStatusSummary("ts", 1L, 1L, "orders", "tok", "p", "s", "e", "1s", "error", aiSummary = "tap t_hr_feed timed out")).asJava
        val s2 = gson.toJson(PipelineStatusAPIController.scrubFor(Set("t_hr_feed"), summaries))
        assert(!s2.contains("t_hr_feed") && s2.contains("tap [hidden] timed out"), s2)

        val events = List(event("tap-t_hr_feed.csv", "x")).asJava
        assert(!gson.toJson(PipelineStatusAPIController.scrubFor(Set("t_hr_feed"), events)).contains("t_hr_feed"))

        assert(PipelineStatusAPIController.scrubFor(Set.empty, response) eq response)
    }

    test("GET /pipeline/status wiring: a deleted or retargeted hidden tap named only by its feed file is scrubbed; unrestricted callers get the same object") {
        val pipelines = Seq(PipelineConfig(name = "orders", catalog = "Sales"), PipelineConfig(name = "payroll", catalog = "HR"))
        // t_hr_feed now targets payroll (retargeted); "gone" is no longer stored.
        val stored = Seq(tap("t_sales", "orders", "Sales"), tap("t_hr_feed", "payroll", "HR"))
        val gson = new com.google.gson.Gson
        Seq("tap-gone.csv", "tap-t_hr_feed.csv").foreach { file =>
            val name = file.stripPrefix("tap-").stripSuffix(".csv")
            val shown = PipelineStatusAPIController.restrictedViewWith(req(bob, path = "/api/v1/pipeline/status"), statusResponse(file), pipelines, stored)
            val body = gson.toJson(shown.toOption.get)
            assert(body.contains("tap-[hidden].csv"), body)
            assert(!body.contains(file), body)
        }
        // A visible tap's feed file is kept.
        val ok = gson.toJson(PipelineStatusAPIController.restrictedViewWith(req(bob), statusResponse("tap-t_sales.csv"), pipelines, stored).toOption.get)
        assert(ok.contains("tap-t_sales.csv"), ok)
        // Unrestricted session and API key: the very same object.
        Seq(editor, salesKey).foreach { rk =>
            val data = statusResponse("tap-gone.csv")
            assert(PipelineStatusAPIController.restrictedViewWith(req(rk), data, pipelines, stored).toOption.get eq data)
            assert(PipelineStatusAPIController.restrictedView(req(rk), data).toOption.get eq data)
        }
    }

    // ------------------------------------------------------------ scratch rows (round 2, finding 1)

    private def stampedRows: com.google.gson.JsonArray =
        JsonParser.parseString(
            """[{"id":1,"_datris_run_id":"tok","_datris_tap_run":"gone|2026-11-16T10:00:00Z","_datris_script_sha":"abc","_datris_source":"tap:gone"},
              |{"id":2,"_datris_tap_run":"t_hr_feed|t","_datris_script_sha":"def","_datris_source":"feeds.example.com"},
              |{"id":3,"_datris_tap_run":"t_sales|t","_datris_script_sha":"ghi","_datris_source":"tap:t_sales"},
              |{"id":4,"_datris_source":"tap:gone"}]""".stripMargin
        ).getAsJsonArray

    private def assertRowsScrubbed(rows: com.google.gson.JsonArray): Unit = {
        val r = rows.asScala.map(_.getAsJsonObject).toList
        Seq(0, 1, 3).foreach { i =>
            HiddenTapScrub.StampTapColumns.foreach(c => assert(!r(i).has(c), s"row $i keeps $c: ${r(i)}"))
        }
        assert(r.head.get("_datris_run_id").getAsString == "tok" && r.head.get("id").getAsInt == 1)
        assert(r(2).get("_datris_tap_run").getAsString == "t_sales|t" && r(2).has("_datris_source"), "a visible tap's stamp stays")
        assert(!rows.toString.contains("gone") && !rows.toString.contains("t_hr_feed"), rows.toString)
    }

    test("scratch preview rows in /pipeline/status lose a hidden or deleted tap's stamp columns even when no filename names it") {
        val pipelines = Seq(PipelineConfig(name = "orders", catalog = "Sales"))
        val stored = Seq(tap("t_sales", "orders", "Sales"), tap("t_hr_feed", "payroll", "HR"))
        def payload = PipelineStatusResponse(
            PipelineStatusRollup(
                allDone = true,
                status = "success",
                jobs = List(PipelineJobRollup("tok", "orders", "upload.csv", "success", "s", "l", "1s", null, resultPreview = stampedRows)).asJava
            ),
            List(
                PipelineStatus(
                    1,
                    "now",
                    "orders",
                    "p",
                    "pub",
                    "tok",
                    "upload.csv",
                    "end",
                    "OK",
                    "done",
                    1L,
                    scratchResult = ScratchResult("u", 4L, "e", stampedRows, false)
                )
            ).asJava
        )
        val shown = PipelineStatusAPIController.restrictedViewWith(req(bob, path = "/api/v1/pipeline/status"), payload, pipelines, stored).toOption.get
            .asInstanceOf[PipelineStatusResponse]
        assertRowsScrubbed(shown.rollup.jobs.get(0).resultPreview)
        assertRowsScrubbed(shown.events.get(0).scratchResult.resultPreview)
        // Unrestricted session and API key: the very same object, rows intact.
        Seq(editor, salesKey).foreach { rk =>
            val data = payload
            assert(PipelineStatusAPIController.restrictedViewWith(req(rk), data, pipelines, stored).toOption.get eq data)
        }
    }

    test("GET /pipeline/result page rows lose a hidden or deleted tap's stamp columns for a restricted session only") {
        def page = ai.datris.util.ScratchReader.ScratchResultPage(stampedRows, 4L, 4, 0L, truncated = false, "u", "e")
        assertRowsScrubbed(PipelineStatusAPIController.scrubResultPage(req(bob, path = "/api/v1/pipeline/result"), page, readTap).records)
        Seq(editor, salesKey).foreach { rk =>
            val p = page
            assert(PipelineStatusAPIController.scrubResultPage(req(rk), p, readTap) eq p)
        }
        // Nothing stamped with a hidden tap: the same page back.
        val clean =
            ai.datris.util.ScratchReader.ScratchResultPage(JsonParser.parseString("""[{"id":1}]""").getAsJsonArray, 1L, 1, 0L, truncated = false, "u", "e")
        assert(PipelineStatusAPIController.scrubResultPage(req(bob), clean, readTap) eq clean)
    }

    // ------------------------------------------------------------ outputs[].error and uploads (round 2, findings 2 and 3)

    test("outputs[].error naming a hidden or deleted tap's feed file is scrubbed in lineage runs and provenance") {
        val run = JsonParser.parseString(
            """{"runId":"r","pipeline":"orders","input":{"kind":"tap","tapName":"t_hr_feed","filename":"tap-t_hr_feed.json"},
              |"outputs":[{"kind":"pgvector","error":"failed on uploaded file: tap-t_hr_feed.json"},{"kind":"postgres","status":"SUCCESS"}]}""".stripMargin
        ).getAsJsonObject
        HiddenTapScrub.scrubRun(run, n => HiddenTapScrub.tapHidden(req(bob), n, readTap))
        assert(!run.toString.contains("t_hr_feed"), run.toString)
        assert(run.getAsJsonArray("outputs").get(0).getAsJsonObject.get("error").getAsString == "failed on uploaded file: tap-[hidden].json")

        // Deleted tap, named only in an output error.
        val p = JsonParser.parseString(
            """{"runId":"run-1","pipeline":"orders","input":{"kind":"tap"},"outputs":[{"kind":"pgvector","error":"failed on uploaded file: tap-gone.json"}]}"""
        ).getAsJsonObject
        val out = ProvenanceAPIController.scrubProvenance(req(bob), p, readTap, Set.empty)
        assert(out.getAsJsonArray("outputs").get(0).getAsJsonObject.get("error").getAsString == "failed on uploaded file: tap-[hidden].json", out.toString)

        val original = """{"runId":"run-1","pipeline":"orders","input":{"kind":"tap"},"outputs":[{"kind":"pgvector","error":"x tap-gone.json"}]}"""
        assert(ProvenanceAPIController.scrubProvenance(req(editor), JsonParser.parseString(original).getAsJsonObject, readTap, Set.empty).toString ==
            JsonParser.parseString(original).getAsJsonObject.toString)
    }

    test("an upload's own tap-<name>.csv filename is not rewritten when no such tap is stored") {
        val run = JsonParser.parseString(
            """{"runId":"r","pipeline":"orders","input":{"kind":"upload","filename":"tap-foo.csv"},"outputs":[{"kind":"postgres","error":"bad row in tap-foo.csv"}]}"""
        ).getAsJsonObject
        val before = run.toString
        HiddenTapScrub.scrubRun(run, n => HiddenTapScrub.tapHidden(req(bob), n, readTap))
        assert(run.toString == before, run.toString)

        val p = JsonParser.parseString(
            """{"runId":"run-1","pipeline":"orders","job":{"filename":"tap-foo.csv"},"input":{"kind":"upload","filename":"tap-foo.csv"}}"""
        ).getAsJsonObject
        val pBefore = p.toString
        assert(ProvenanceAPIController.scrubProvenance(req(bob), p, readTap, Set.empty).toString == pBefore)
    }

    // ------------------------------------------------------------ activity signals

    test("activity failing rows: a pipeline row's reason is scrubbed, a tap row's reason is untouched, a hidden relatedTapName is cleared") {
        import ai.datris.util.ActivitySignals.FailingItem
        def item(kind: String, name: String, reason: String, related: Option[String]) =
            FailingItem(kind, name, Some("Sales"), reason, None, recovered = false, failureCount = 1, pipelineToken = None, relatedTapName = related)
        val tapHidden: String => Boolean = n => n != "t_sales" // gone and t_hr_feed are hidden
        val rows = List(
            item("pipeline", "orders", "Processing tap-gone.csv failed; tap 't_hr_feed' sent bad rows into public.t_hr_feed", Some("t_hr_feed")),
            item("pipeline", "orders", "tap t_sales ok", Some("t_sales")),
            item("tap", "t_sales", "tap t_sales: upstream tap-t_sales.csv failed", None)
        )
        val out = ActivityAPIController.scrubFailing(rows, tapHidden, Set("t_hr_feed"))
        assert(out.head.reason == "Processing tap-[hidden].csv failed; tap '[hidden]' sent bad rows into public.t_hr_feed", out.head.reason)
        assert(out.head.relatedTapName.isEmpty)
        assert(out(1) == rows(1), "a visible related tap and its reason stay")
        assert(out(2) == rows(2), "tap rows are untouched")
    }

    // ------------------------------------------------------------ 403 message

    private def intercept(r: HttpServletRequest): (Boolean, Int, String) = {
        val out = new java.io.StringWriter()
        val writer = new java.io.PrintWriter(out)
        var status = 200
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getWriter).thenReturn(writer)
        doAnswer { i => status = i.getArgument[Int](0); null }.when(resp).setStatus(anyInt())
        val cont = new CapabilityInterceptor().preHandle(r, resp, null)
        writer.flush()
        (cont, status, out.toString)
    }

    test("the restricted-listing 403 says the listing is refused for a catalog-scoped user; other fields and API-key denials are unchanged") {
        val path = "/api/v1/metadata/postgres/tables"
        val (cont, status, body) = intercept(req(bob, path = path))
        assert(!cont && status == 403, body)
        val o = JsonParser.parseString(body).getAsJsonObject
        assert(o.get("error").getAsString == "capability denied")
        assert(o.get("errorKind").getAsString == "capability_denied")
        assert(o.get("key").getAsString == "session:bob")
        assert(o.get("required").getAsString == "metadata:read")
        assert(o.get("route").getAsString == "GET " + path)
        val msg = o.get("message").getAsString
        assert(msg.contains("listing is refused for a catalog-scoped user"), msg)
        assert(!msg.contains("does not hold capability"), msg)
        assert(msg.contains("find_data") && msg.contains("get_pipeline"), msg)
        assert(o.keySet().asScala == Set("error", "errorKind", "key", "required", "route", "message"))

        // A key that lacks the capability keeps the original remedy text.
        val noMeta = ResolvedKey(None, "no-meta", Capability.parseList(Seq("pipeline:read")), isLegacyFullAccess = false)
        val (_, keyStatus, keyBody) = intercept(req(noMeta, path = path))
        assert(keyStatus == 403)
        assert(
            JsonParser.parseString(keyBody).getAsJsonObject.get("message").getAsString ==
                "API key 'no-meta' does not hold capability 'metadata:read'. " +
                "This is a permission boundary on the API key, not a problem with the upstream service. " +
                "The key would need 'metadata:read' added to its capability bundle, " +
                "or the operator must use a different key with the required capability.",
            keyBody
        )
    }
}
