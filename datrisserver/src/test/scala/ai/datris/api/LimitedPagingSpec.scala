package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.incident.{Incident, IncidentIO, IncidentStep}
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, InMemoryUserDb, PipelineStatusUtil}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{JsonElement, JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Four small read leaks for callers limited to a catalog
  * (plans/stories/limited-caller-read-leaks.md), Acceptance bullet 3 (gap
  * 10b, owner decision D3): a caller limited to a catalog or to what it
  * created gets full pages of the rows it may see where enough exist within
  * the bound (10 store pages of 20 summary rows per request; 5x the limit,
  * capped at the existing maxima, on the incident and tap-log lists); past
  * the bound a short page, never an error. Everyone else reads exactly what
  * it reads today.
  *
  * New names (these specs do not compile until they exist):
  * {{{
  * object PipelineStatusUtil {
  *   // Page `page` (1-based, 20 rows) of the summary rows `keep` accepts, newest
  *   // first, read from `readPage(n)` (store page n, 1-based, 20 rows, newest
  *   // first; a short or empty page is the end); at most 10 store pages read.
  *   def filledSummaryPage(page: Int, keep: PipelineStatusSummary => Boolean,
  *                         readPage: Int => java.util.List[PipelineStatusSummary]): java.util.List[PipelineStatusSummary]
  * }
  * object PipelineStatusAPIController {
  *   // GET /pipeline/status summary path (no token): `readPage(page)` itself for a
  *   // caller that is not limited (one read, `pipelines` not called); for a limited
  *   // caller filledSummaryPage over the names it may see (PipelineAliases.shownRecordNames).
  *   def summaryData(request: HttpServletRequest, page: Int, readPage: Int => java.util.List[PipelineStatusSummary],
  *                   pipelines: () => Seq[PipelineConfig]): java.util.List[PipelineStatusSummary]
  * }
  * object IncidentsAPIController {
  *   // GET /incidents: `read(n)` reads the n newest stored incidents (IncidentIO.list(state, n)).
  *   // Not limited: read(limit) once, then listedJson as today. Limited: one
  *   // read(min(5 * limit, IncidentIO.MaxListLimit)), the visible ones (shown as
  *   // listedJson shows them), cut to `limit`.
  *   def listedFor(request: HttpServletRequest, limit: Int, read: Int => List[Incident], readPipeline: String => PipelineConfig,
  *                 readTap: String => TapConfig, run: String => Option[RunLineage], items: () => StoredItems): JsonArray
  * }
  * object TapAPIController {
  *   // GET /tap/logs/all: `read(n)` reads the n newest run-log values in the window.
  *   // Not limited: read(maxItems) itself, `readAll` not called. Limited: one
  *   // read(min(5 * maxItems, 5000)), visibleTapLogs, cut to `maxItems`.
  *   def visibleTapLogsPage(request: HttpServletRequest, maxItems: Int, read: Int => Seq[JsonElement],
  *                          readAll: () => Seq[TapConfig]): Seq[JsonElement]
  * }
  * }}} */
class LimitedPagingSpec extends AnyFunSuite with BeforeAndAfterEach {

    private def req(rk: ResolvedKey): HttpServletRequest = ScopeDenialFixtures.request(rk, "GET", "/api/v1/pipeline/status")._1
    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)

    private val bob: ResolvedKey = APIKeyValidator.resolveFromSession(
        User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))
    )
    private val salesKey: ResolvedKey = key("sales", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "incident:read", "job:read")
    private val ownKey: ResolvedKey = key("own", "pipeline:read:owner=self", "tap:read:owner=self", "incident:read", "job:read")
    private val limited: Seq[(String, ResolvedKey)] = Seq("restricted session" -> bob, "catalog= key" -> salesKey, "owner=self key" -> ownKey)

    private def unchanged: Seq[(String, HttpServletRequest)] = Seq(
        "admin session" -> req(APIKeyValidator.resolveFromSession(User("admin", "h", User.RoleAdmin, null, null, null))),
        "unrestricted editor session" -> req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))),
        "*:* key" -> req(key("full", "*:*")),
        "unscoped key" -> req(key("monitor", "job:read", "incident:read", "pipeline:read", "tap:read")),
        "legacy key" -> req(ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)),
        "keys off (no identity)" -> ScopeDenialFixtures.request(null, "GET", "/api/v1/pipeline/status")._1
    )

    // visible to every limited caller: Sales, created by `own`; hidden: HR, someone else
    private val orders = PipelineConfig(name = "orders", catalog = "Sales", createdByKeyLabel = "own")
    private val payroll = PipelineConfig(name = "payroll", catalog = "HR", createdByKeyLabel = "someone")
    private val pipelines: Seq[PipelineConfig] = Seq(orders, payroll)
    private val tOrders = TapConfig(name = "t_orders", description = "d", targetPipeline = "orders", catalog = "Sales", createdByKeyLabel = "own")
    private val tPayroll = TapConfig(name = "t_payroll", description = "d", targetPipeline = "payroll", catalog = "HR", createdByKeyLabel = "someone")
    private val taps: Seq[TapConfig] = Seq(tOrders, tPayroll)

    private val failPipelines: () => Seq[PipelineConfig] = () => fail("the pipeline store must not be read for this caller")
    private val failTaps: () => Seq[TapConfig] = () => fail("the tap store must not be read for this caller")

    // ------------------------------------------------------------ fake summary store

    /** `total` summary rows, newest first (row 0 newest); row i is of
      * `visible(i) ? orders : payroll`. Pages of 20, 1-based, as
      * getPipelineStatusSummary reads them; every read is counted. */
    private class SummaryStore(total: Int, visible: Int => Boolean) {
        val rows: IndexedSeq[PipelineStatusSummary] = (0 until total).map { i =>
            val at = 1800000000000L - i * 1000L
            PipelineStatusSummary("ts" + i, at, at, if (visible(i)) "orders" else "payroll", "tok-" + i, "JobRunner", "s", "e", "1s", "success")
        }
        val pagesRead: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer[Int]()
        def reads: Int = pagesRead.size
        val readPage: Int => java.util.List[PipelineStatusSummary] = n => {
            pagesRead += n
            val from = math.max(0, (n - 1) * 20)
            new java.util.ArrayList[PipelineStatusSummary](rows.slice(from, from + 20).asJava)
        }
        def visibleRows: IndexedSeq[PipelineStatusSummary] = rows.filter(_.pipeline == "orders")
    }

    private val isOrders: PipelineStatusSummary => Boolean = s => s.pipeline == "orders"

    private def tokens(l: java.util.List[PipelineStatusSummary]): Seq[String] = l.asScala.map(_.pipelineToken).toSeq

    private def assertNewestFirst(l: java.util.List[PipelineStatusSummary], who: String): Unit = {
        val at = l.asScala.map(_.createdAt)
        assert(at == at.sorted.reverse, who + ": newest first " + at)
    }

    private var savedEnv: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env(useAuditLog = true)
        DatrisEnvironment.init(e)
        TenantContext.set(e)
        AuditLog.sinkOverride = (_: AuditEntry) => ()
    }

    override def afterEach(): Unit = {
        AuditLog.sinkOverride = null
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    // ================================================================== summary rows: the fill rule (pure)

    test("bullet 3: 60 summary rows of which every third is visible: page 1 returns 20 visible rows and page 2 returns none") {
        val s1 = new SummaryStore(60, _ % 3 == 0)
        val p1 = PipelineStatusUtil.filledSummaryPage(1, isOrders, s1.readPage)
        assert(p1.size == 20, tokens(p1))
        assert(tokens(p1) == s1.visibleRows.map(_.pipelineToken), tokens(p1))
        assertNewestFirst(p1, "page 1")
        assert(s1.reads <= 10, "store pages read: " + s1.pagesRead)
        val s2 = new SummaryStore(60, _ % 3 == 0)
        val p2 = PipelineStatusUtil.filledSummaryPage(2, isOrders, s2.readPage)
        assert(p2.isEmpty, tokens(p2))
        assert(s2.reads <= 10, "store pages read: " + s2.pagesRead)
    }

    test("bullet 3: a full page is returned when exactly enough visible rows lie within the 10-page bound") {
        val s = new SummaryStore(400, _ % 10 == 0) // 20 visible rows in the first 200
        val p = PipelineStatusUtil.filledSummaryPage(1, isOrders, s.readPage)
        assert(p.size == 20, tokens(p))
        assert(tokens(p) == s.visibleRows.take(20).map(_.pipelineToken))
        assert(s.reads <= 10, s.pagesRead)
    }

    test("bullet 3: with the bound hit the page is short, no error is raised and no more than 10 store pages are read") {
        val sparse = new SummaryStore(1000, _ % 15 == 0) // 14 visible rows in the first 200 rows
        val p = PipelineStatusUtil.filledSummaryPage(1, isOrders, sparse.readPage)
        assert(sparse.reads == 10, "the bound: " + sparse.pagesRead)
        assert(p.size == 14, tokens(p))
        assert(tokens(p) == sparse.visibleRows.take(14).map(_.pipelineToken))
        val none = new SummaryStore(1000, _ => false)
        assert(PipelineStatusUtil.filledSummaryPage(1, isOrders, none.readPage).isEmpty)
        assert(none.reads == 10, none.pagesRead)
    }

    test("bullet 3: pages are disjoint, newest first, and together hold every visible row in store order; the store is not read past its end") {
        val s = new SummaryStore(90, _ % 3 == 0) // 30 visible
        val pages = (1 to 3).map(n => PipelineStatusUtil.filledSummaryPage(n, isOrders, s.readPage))
        assert(pages.map(_.size) == Seq(20, 10, 0), pages.map(tokens))
        pages.foreach(p => assertNewestFirst(p, "page"))
        assert(pages.flatMap(tokens) == s.visibleRows.map(_.pipelineToken))
        val short = new SummaryStore(30, _ => true)
        val p1 = PipelineStatusUtil.filledSummaryPage(1, isOrders, short.readPage)
        assert(tokens(p1) == short.rows.take(20).map(_.pipelineToken))
        val ended = new SummaryStore(30, _ % 2 == 0)
        assert(PipelineStatusUtil.filledSummaryPage(1, isOrders, ended.readPage).size == 15)
        assert(ended.reads <= 3, "a short store page is the end: " + ended.pagesRead)
    }

    // ================================================================== summary rows through the route seam

    test("bullet 3: a limited caller's summary page is filled from the pipelines it may see") {
        for ((who, rk) <- limited) {
            val s = new SummaryStore(60, _ % 3 == 0)
            val p1 = PipelineStatusAPIController.summaryData(req(rk), 1, s.readPage, () => pipelines)
            assert(tokens(p1) == s.visibleRows.map(_.pipelineToken), who + ": " + tokens(p1))
            assert(s.reads <= 10, who + ": " + s.pagesRead)
            assert(PipelineStatusAPIController.summaryData(req(rk), 2, new SummaryStore(60, _ % 3 == 0).readPage, () => pipelines).isEmpty, who)
        }
    }

    test("unchanged: a caller that is not limited reads one store page, the page it asked for, and gets it as read") {
        for ((who, r) <- unchanged; page <- Seq(1, 2, 7)) {
            val s = new SummaryStore(300, _ % 3 == 0)
            val got = PipelineStatusAPIController.summaryData(r, page, s.readPage, failPipelines)
            assert(s.pagesRead == Seq(page), who + " page " + page + ": " + s.pagesRead)
            assert(tokens(got) == s.rows.slice((page - 1) * 20, page * 20).map(_.pipelineToken), who)
        }
    }

    test("unchanged: a limited caller that may see every row gets, on every page it can reach today, the rows it gets today") {
        for ((who, rk) <- limited; page <- Seq(1, 2, 5, 12)) {
            val s = new SummaryStore(300, _ => true)
            val got = PipelineStatusAPIController.summaryData(req(rk), page, s.readPage, () => pipelines)
            assert(tokens(got) == s.rows.slice((page - 1) * 20, page * 20).map(_.pipelineToken), who + " page " + page + ": " + tokens(got))
        }
    }

    // ================================================================== GET /incidents

    private val t0 = Instant.parse("2026-11-18T10:00:00Z")

    /** `total` stored incidents, newest first; incident i is on `orders` when visible(i), else on `payroll`. */
    private class IncidentStore(total: Int, visible: Int => Boolean) {
        val all: List[Incident] = (0 until total).toList.map { i =>
            Incident(
                id = "inc-" + i,
                kind = "pipeline_failure",
                resourceType = "pipeline",
                resourceName = if (visible(i)) "orders" else "payroll",
                openedAt = t0.minusSeconds(i.toLong),
                state = "diagnosing",
                trigger = new JsonObject(),
                steps = List(IncidentStep(t0, "open", "opened"))
            )
        }
        val asked: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer[Int]()
        val read: Int => List[Incident] = n => { asked += n; all.take(math.max(1, math.min(n, IncidentIO.MaxListLimit))) }
    }

    private val readPipeline: String => PipelineConfig = n => pipelines.find(_.name == n).orNull
    private val readTap: String => TapConfig = n => taps.find(_.name == n).orNull
    private def items: () => IncidentsAPIController.StoredItems = () => IncidentsAPIController.StoredItems(pipelines, taps)

    private def ids(a: com.google.gson.JsonArray): Seq[String] = a.asScala.map(_.getAsJsonObject.get("id").getAsString).toSeq

    test("bullet 3: GET /incidents reads 5x the limit for a limited caller, capped at 200, and returns up to `limit` visible incidents in order") {
        for ((who, rk) <- limited) {
            val st = new IncidentStore(400, _ % 3 == 0)
            val ten = IncidentsAPIController.listedFor(req(rk), 10, st.read, readPipeline, readTap, IncidentsAPIController.NoRuns, items)
            assert(st.asked == Seq(50), who + ": " + st.asked)
            assert(ids(ten) == st.all.filter(_.resourceName == "orders").take(10).map(_.id), who + ": " + ids(ten))

            val st50 = new IncidentStore(400, _ % 3 == 0)
            val fifty = IncidentsAPIController.listedFor(req(rk), 50, st50.read, readPipeline, readTap, IncidentsAPIController.NoRuns, items)
            assert(st50.asked == Seq(200), who + ": capped at the existing maximum " + st50.asked)
            assert(fifty.size == 50, who)

            // the cap is hit: a short list, no error
            val st200 = new IncidentStore(400, _ % 3 == 0)
            val all = IncidentsAPIController.listedFor(req(rk), 200, st200.read, readPipeline, readTap, IncidentsAPIController.NoRuns, items)
            assert(st200.asked == Seq(200), who)
            assert(ids(all) == st200.all.take(200).filter(_.resourceName == "orders").map(_.id), who)
        }
    }

    test("unchanged: GET /incidents for a caller that is not limited reads `limit` once and lists what it read, no store read") {
        for ((who, r) <- unchanged; limit <- Seq(10, 50)) {
            val st = new IncidentStore(400, _ % 3 == 0)
            val got = IncidentsAPIController.listedFor(
                r,
                limit,
                st.read,
                n => fail("pipeline read: " + n),
                n => fail("tap read: " + n),
                _ => fail("run read"),
                () => fail("stores listed")
            )
            assert(st.asked == Seq(limit), who + ": " + st.asked)
            assert(got.toString == IncidentsAPIController.listedJson(r, st.all.take(limit), null, null).toString, who)
        }
    }

    // ================================================================== GET /tap/logs/all

    /** `total` run-log values, newest first; log i is of t_orders when visible(i). */
    private class TapLogStore(total: Int, visible: Int => Boolean) {
        val all: Seq[JsonElement] = (0 until total).map { i =>
            JsonParser.parseString("""{"tapName":"""" + (if (visible(i)) "t_orders" else "t_payroll") + """","runTime":"r""" + i + """"}""")
        }
        val asked: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer[Int]()
        val read: Int => Seq[JsonElement] = n => { asked += n; all.take(n) }
    }

    private def runTimes(s: Seq[JsonElement]): Seq[String] = s.map(_.getAsJsonObject.get("runTime").getAsString)

    test("bullet 3: GET /tap/logs/all reads 5x the limit for a limited caller, capped at 5000, and returns up to `limit` visible logs in order") {
        for ((who, rk) <- limited) {
            val st = new TapLogStore(12000, _ % 4 == 0)
            val got = TapAPIController.visibleTapLogsPage(req(rk), 100, st.read, () => taps)
            assert(st.asked == Seq(500), who + ": " + st.asked)
            assert(runTimes(got) == runTimes(st.all.filter(_.getAsJsonObject.get("tapName").getAsString == "t_orders").take(100)), who)

            val st2000 = new TapLogStore(12000, _ % 4 == 0)
            assert(TapAPIController.visibleTapLogsPage(req(rk), 2000, st2000.read, () => taps).size == 1250, who + ": 5000 rows read, a quarter visible")
            assert(st2000.asked == Seq(5000), who + ": capped at the existing maximum " + st2000.asked)
        }
    }

    test("unchanged: GET /tap/logs/all for a caller that is not limited reads `limit` once and returns what it read, tap store not read") {
        for ((who, r) <- unchanged) {
            val st = new TapLogStore(3000, _ % 4 == 0)
            val got = TapAPIController.visibleTapLogsPage(r, 2000, st.read, failTaps)
            assert(st.asked == Seq(2000), who + ": " + st.asked)
            assert(got == st.all.take(2000), who)
        }
    }
}
