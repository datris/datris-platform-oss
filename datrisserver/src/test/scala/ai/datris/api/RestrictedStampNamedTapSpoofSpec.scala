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

/** Story: Catalog-scoped user access: a tap's own name or declared source no
  * longer vouches for its stamps
  * (plans/stories/catalog-scoped-user-access-stamp-named-tap-spoof.md),
  * Acceptance.
  *
  * `_datris_source` is author text and never vouches (a `tap:<name>` naming
  * a hidden tap still strips). `_datris_tap_run` names every non-empty
  * prefix ending just before a `|` (the whole value when it has none); it
  * vouches only when all of them are readable. bob: restricted, grants
  * `{Sales: edit}`; taps `t_sales` (Sales), `t_hr_feed` (HR), `t_sales|x`
  * (HR), `a|b` (Sales). */
class RestrictedStampNamedTapSpoofSpec extends AnyFunSuite with BeforeAndAfterEach {

    private type Hit = java.util.Map[String, Any]
    private type Hits = java.util.List[java.util.Map[String, Any]]

    private def req(rk: ResolvedKey, method: String = "POST", path: String = "/api/v1/search/pgvector"): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn(method)
        when(r.getRequestURI).thenReturn(path)
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def bob: ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "edit")))
    private def editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val fullKey: ResolvedKey = ResolvedKey(None, "full", Nil, isLegacyFullAccess = true)
    private val salesKey: ResolvedKey =
        ResolvedKey(
            None,
            "sales-key",
            Capability.parseList(Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "metadata:read")),
            isLegacyFullAccess = false
        )

    private def tap(n: String, catalog: String) = TapConfig(name = n, description = "d", targetPipeline = "orders", catalog = catalog)
    private val taps =
        Map("t_sales" -> tap("t_sales", "Sales"), "t_hr_feed" -> tap("t_hr_feed", "HR"), "t_sales|x" -> tap("t_sales|x", "HR"), "a|b" -> tap("a|b", "Sales"))
    private val readTap: String => TapConfig = n => taps.getOrElse(n, null)
    private val readTapWithA: String => TapConfig = n => (taps + ("a" -> tap("a", "Sales"))).getOrElse(n, null)

    private val failReadTap: String => TapConfig = n => throw new AssertionError("readTap must not be called, got " + n)
    private val failRun: String => Option[RunLineage] = id => throw new AssertionError("run must not be called, got " + id)
    private val failDescriptions: () => Seq[String] = () => throw new AssertionError("job descriptions must not be read")

    private val Time = "2026-11-01 00:00:00 UTC"

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

    // ------------------------------------------------------------ helpers

    private def hit(id: Int, pairs: (String, Any)*): Hit = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("id", id)
        m.put("text", "chunk " + id)
        m.put("source_pipeline", "orders")
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    private def hits(hs: Hit*): Hits = {
        val l = new java.util.ArrayList[Hit]()
        hs.foreach(l.add)
        l
    }

    private val writers: Option[Set[String]] = Some(Set("orders"))

    private def bobHits(hs: Hits, run: String => Option[RunLineage] = failRun, rt: String => TapConfig = readTap): Hits =
        SearchAPIController.hitsFor(req(bob), hs, writers, rt, run)

    private def assertStripped(h: Hit, ctx: String): Unit =
        HiddenTapScrub.StampTapColumns.foreach(c => assert(!h.containsKey(c), s"$ctx: $c must be stripped: $h"))

    private def record(pipeline: String, tapName: String): String => Option[RunLineage] =
        id => Some(RunLineage(id, pipeline, input = RunLineageInput("tap", tapName = tapName)))

    private def pipeNamed(id: Int, extra: (String, Any)*): Hit =
        hit(
            id,
            (Seq[(String, Any)](
                "_datris_tap_run" -> ("t_sales|x|" + Time),
                "_datris_script_sha" -> "sha-hr",
                "_datris_source" -> "hr.internal",
                "filename" -> "doc.pdf"
            ) ++ extra): _*
        )

    private def sourceSpoof(id: Int, extra: (String, Any)*): Hit =
        hit(id, (Seq[(String, Any)]("_datris_source" -> "tap:t_sales", "_datris_script_sha" -> "sha-hr") ++ extra): _*)

    // ------------------------------------------------------------ search hits

    test("a hidden tap named with a pipe is not read as its readable prefix") {
        val h = pipeNamed(1)
        val before = new java.util.LinkedHashMap[String, Any](h)
        val out = bobHits(hits(h))
        val o = out.get(0)
        assertStripped(o, "t_sales|x|<time>")
        assert(o.get("id") == 1)
        assert(o.get("text") == "chunk 1")
        assert(o.get("source_pipeline") == "orders")
        assert(h == before, "the input map is not modified")
        val body = new Gson().toJson(out)
        assert(!body.contains("hr.internal") && !body.contains("sha-hr"), body)

        // a run id beside it changes nothing, and run is still not called
        val withRunId = bobHits(hits(pipeNamed(2, "_datris_run_id" -> "run-hr")))
        assertStripped(withRunId.get(0), "t_sales|x|<time> with a run id")
        assert(withRunId.get(0).get("_datris_run_id") == "run-hr")
        val body2 = new Gson().toJson(withRunId)
        assert(!body2.contains("hr.internal") && !body2.contains("sha-hr"), body2)
    }

    test("a tap_run keeps its columns only when every pipe prefix is a readable tap") {
        def stamped(id: Int, tapRun: String): Hit = hit(id, "_datris_tap_run" -> tapRun, "_datris_script_sha" -> "s", "_datris_source" -> "feeds.example.com")

        // prefix `a` is unknown
        assertStripped(bobHits(hits(stamped(1, "a|b|" + Time))).get(0), "a|b|<time>, no tap a")
        // with a readable tap `a`, both prefixes (a, a|b) are readable
        val ab = hits(stamped(2, "a|b|" + Time))
        assert(bobHits(ab, rt = readTapWithA) eq ab, "a|b|<time> with readable a")

        // a readable first prefix does not vouch for an unknown longer prefix
        assertStripped(bobHits(hits(stamped(3, "t_sales|t_hr_feed|" + Time))).get(0), "t_sales|t_hr_feed|<time>")

        val one = hits(stamped(4, "t_sales|" + Time))
        assert(bobHits(one) eq one, "t_sales|<time>")
        val bare = hits(stamped(5, "t_sales"))
        assert(bobHits(bare) eq bare, "bare t_sales")
        assertStripped(bobHits(hits(stamped(6, "t_hr_feed"))).get(0), "bare t_hr_feed")
    }

    test("a tap: source naming a readable tap does not vouch for a hit") {
        val noRunId = bobHits(hits(sourceSpoof(1)))
        assertStripped(noRunId.get(0), "tap:t_sales, no tap run, no run id")
        assert(!new Gson().toJson(noRunId).contains("sha-hr"), new Gson().toJson(noRunId))

        // with a run id the run record filed under the hit's writer decides
        val hrRecord = bobHits(hits(sourceSpoof(2, "_datris_run_id" -> "run-hr")), record("orders", "t_hr_feed"))
        assertStripped(hrRecord.get(0), "tap:t_sales, record orders/t_hr_feed")
        assert(hrRecord.get(0).get("_datris_run_id") == "run-hr")
        assert(!new Gson().toJson(hrRecord).contains("sha-hr"))

        val salesHits = hits(sourceSpoof(3, "_datris_run_id" -> "run-hr"))
        assert(bobHits(salesHits, record("orders", "t_sales")) eq salesHits, "record orders/t_sales keeps the columns")

        assertStripped(bobHits(hits(sourceSpoof(4, "_datris_run_id" -> "run-hr")), record("payroll", "t_sales")).get(0), "record of another pipeline")
        assertStripped(bobHits(hits(sourceSpoof(5, "_datris_run_id" -> "run-hr")), _ => None).get(0), "no record")
    }

    test("a readable tap_run beside a tap: source keeps the columns and run is not called") {
        val hs = hits(hit(1, "_datris_tap_run" -> ("t_sales|" + Time), "_datris_source" -> "tap:t_sales", "_datris_script_sha" -> "sha-hr"))
        assert(bobHits(hs, failRun) eq hs)
    }

    // ------------------------------------------------------------ scratch rows

    private val StreamFile = "stream: orders"
    private def beginDescription(feedFile: String) = "Stream data received, pipeline: orders, filename: " + feedFile

    private def spoofRows: JsonArray =
        JsonParser.parseString(
            """[{"id":1,"_datris_run_id":"tok","_datris_source":"tap:t_sales","_datris_script_sha":"sha-hr"},
              |{"id":2,"_datris_run_id":"tok","_datris_source":"tap:t_sales","_datris_script_sha":"sha-hr"}]""".stripMargin
        ).getAsJsonArray

    private def pipeRows: JsonArray =
        JsonParser
            .parseString(
                s"""[{"id":1,"_datris_tap_run":"t_sales|x|$Time","_datris_script_sha":"sha-hr","_datris_source":"hr.internal"},{"id":2,"_datris_tap_run":"t_sales|t"}]"""
            )
            .getAsJsonArray

    private def page(rows: JsonArray = spoofRows) = ScratchResultPage(rows, 2L, 2, 0L, truncated = false, "u", "e")

    private def bobPage(p: ScratchResultPage, run: String => Option[RunLineage], descriptions: () => Seq[String]): ScratchResultPage =
        PipelineStatusAPIController.scrubResultPage(req(bob, "GET", "/api/v1/pipeline/result"), p, readTap, StreamFile, "orders", "tok", run, descriptions)

    private def assertRowsStripped(rows: JsonArray, ctx: String): Unit = {
        val r = rows.asScala.map(_.getAsJsonObject).toList
        assert(r.size == 2, ctx)
        r.foreach { row =>
            HiddenTapScrub.StampTapColumns.foreach(c => assert(!row.has(c), s"$ctx: keeps $c: $row"))
            assert(row.get("_datris_run_id").getAsString == "tok", ctx)
        }
        assert(r.map(_.get("id").getAsInt) == List(1, 2), ctx)
        assert(!rows.toString.contains("sha-hr"), ctx)
    }

    test("scratch rows: a tap: source naming a readable tap is resolved by the run record") {
        val hr = bobPage(page(), record("orders", "t_hr_feed"), () => Nil)
        assertRowsStripped(hr.records, "record tap t_hr_feed")

        val p1 = page()
        assert(bobPage(p1, record("orders", "t_sales"), () => Nil) eq p1, "record tap t_sales keeps the page")

        val hrFeed = bobPage(page(), _ => None, () => Seq(beginDescription("tap-t_hr_feed.csv")))
        assertRowsStripped(hrFeed.records, "no record, begin event tap-t_hr_feed.csv")

        val p2 = page()
        assert(bobPage(p2, _ => None, () => Seq(beginDescription("tap-t_sales.csv"))) eq p2, "no record, begin event tap-t_sales.csv")

        val none = bobPage(page(), _ => None, () => Nil)
        assertRowsStripped(none.records, "no record, no events (stream run, fail closed)")
    }

    test("scratch rows: a hidden tap named with a pipe is stripped") {
        val rows = pipeRows
        val before = rows.deepCopy()
        val out = HiddenTapScrub.scrubStampedRows(rows, n => HiddenTapScrub.tapHidden(req(bob), n, readTap))
        val r0 = out.get(0).getAsJsonObject
        HiddenTapScrub.StampTapColumns.foreach(c => assert(!r0.has(c), r0.toString))
        assert(r0.get("id").getAsInt == 1)
        assert(out.get(1).getAsJsonObject.get("_datris_tap_run").getAsString == "t_sales|t", "a readable-only row keeps its value")
        assert(rows == before, "the input array is not modified")
    }

    // ------------------------------------------------------------ unrestricted

    test("unrestricted callers get the same instances") {
        def allHits: Hits = hits(
            pipeNamed(1),
            pipeNamed(2, "_datris_run_id" -> "run-hr"),
            hit(3, "_datris_tap_run" -> ("a|b|" + Time), "_datris_script_sha" -> "s"),
            hit(4, "_datris_tap_run" -> ("t_sales|t_hr_feed|" + Time), "_datris_script_sha" -> "s"),
            hit(5, "_datris_tap_run" -> "t_hr_feed", "_datris_script_sha" -> "s"),
            sourceSpoof(6),
            sourceSpoof(7, "_datris_run_id" -> "run-hr"),
            hit(8, "_datris_tap_run" -> ("t_sales|" + Time), "_datris_source" -> "tap:t_sales", "_datris_script_sha" -> "sha-hr")
        )
        for (rk <- Seq(editor, fullKey, salesKey)) {
            val hs = allHits
            assert(SearchAPIController.hitsFor(req(rk), hs, None, failReadTap, failRun) eq hs, rk.label)
            for (rows <- Seq(spoofRows, pipeRows)) {
                val p = page(rows)
                val r = req(rk, "GET", "/api/v1/pipeline/result")
                assert(PipelineStatusAPIController.scrubResultPage(r, p, failReadTap, StreamFile, "orders", "tok", failRun, failDescriptions) eq p, rk.label)
            }
        }
    }
}
