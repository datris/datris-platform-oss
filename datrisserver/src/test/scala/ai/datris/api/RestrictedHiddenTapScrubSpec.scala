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

    test("scrubText replaces tap-<name> always and the bare name unless it is also a visible name") {
        val hidden = Set("t_hr_feed", "orders")
        assert(HiddenTapScrub.scrubText("Loaded tap-t_hr_feed.csv from t_hr_feed", hidden) == "Loaded tap-[hidden].csv from [hidden]")
        // Bare "orders" is a visible pipeline name: kept; its tap- form is not.
        assert(HiddenTapScrub.scrubText("orders got tap-orders.json", hidden, Set("orders")) == "orders got tap-[hidden].json")
        // Whole names only: a longer name sharing the prefix is untouched.
        assert(HiddenTapScrub.scrubText("t_hr_feed_v2 and tap-t_hr_feed_v2.csv", hidden) == "t_hr_feed_v2 and tap-t_hr_feed_v2.csv")
        assert(HiddenTapScrub.scrubText(null, hidden) == null)
        assert(HiddenTapScrub.scrubText("tap-t_hr_feed.csv", Set.empty) == "tap-t_hr_feed.csv")
        assert(HiddenTapScrub.scrubFilename("t_hr_feed.csv", Set("t_hr_feed")) == "t_hr_feed.csv", "only the tap- form in filenames")
        assert(HiddenTapScrub.scrubFilename("tap-t_hr_feed.csv", Set("t_hr_feed")) == "tap-[hidden].csv")
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

    // ------------------------------------------------------------ pipeline status

    private def event(filename: String, description: String) =
        PipelineStatus(1, "now", "orders", "process", "pub", "tok", filename, "end", "OK", description, 1L, aiSummary = "t_hr_feed returned bad rows")

    test("pipeline status of a visible pipeline names no hidden feeding tap; empty hidden set returns the same payload") {
        val response = PipelineStatusResponse(
            PipelineStatusRollup(
                allDone = true,
                status = "error",
                jobs = List(
                    PipelineJobRollup(
                        "tok",
                        "orders",
                        "tap-t_hr_feed.csv",
                        "error",
                        "s",
                        "l",
                        "1s",
                        PipelineJobError("p", "row from t_hr_feed failed", "t_hr_feed")
                    )
                ).asJava
            ),
            List(event("tap-t_hr_feed.csv", "Processing tap-t_hr_feed.csv for orders")).asJava
        )
        val gson = new com.google.gson.Gson
        val scrubbed = gson.toJson(PipelineStatusAPIController.scrubFor(Set("t_hr_feed"), Set("orders"), response))
        assert(!scrubbed.contains("t_hr_feed"), scrubbed)
        assert(scrubbed.contains("tap-[hidden].csv") && scrubbed.contains("for orders"), scrubbed)

        val summaries = List(PipelineStatusSummary("ts", 1L, 1L, "orders", "tok", "p", "s", "e", "1s", "error", aiSummary = "t_hr_feed timed out")).asJava
        val s2 = gson.toJson(PipelineStatusAPIController.scrubFor(Set("t_hr_feed"), Set("orders"), summaries))
        assert(!s2.contains("t_hr_feed") && s2.contains("[hidden] timed out"), s2)

        val events = List(event("tap-t_hr_feed.csv", "x")).asJava
        assert(!gson.toJson(PipelineStatusAPIController.scrubFor(Set("t_hr_feed"), Set("orders"), events)).contains("t_hr_feed"))

        // Unrestricted callers get an empty hidden set: the very same object back.
        assert(PipelineStatusAPIController.scrubFor(Set.empty, Set("orders"), response) eq response)
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
