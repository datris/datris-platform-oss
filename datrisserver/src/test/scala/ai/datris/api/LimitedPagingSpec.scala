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
import org.springframework.http.ResponseEntity
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
  * 10b, owner decision D3, review ruling: filter in the store query): a
  * caller limited to a catalog or to what it created reads its summary page
  * with the store filtered to the pipelines it may see, so page N is rows
  * [20(N-1), 20N) of the rows it may see, newest first, every one of them
  * reachable and every page full until the last; one store read per page.
  * The incident and tap-log lists read 5x the limit, capped at the existing
  * maxima. Everyone else reads exactly what it reads today.
  *
  * Names under test:
  * {{{
  * object PipelineStatusUtil {
  *   def getVisiblePipelineStatusSummary(pipelines: Set[String], page: Int, db: NoSQLDbUtility): java.util.List[PipelineStatusSummary]
  * }
  * object PipelineStatusAPIController {
  *   def summaryView(request: HttpServletRequest, page: Int, readPage: Int => java.util.List[PipelineStatusSummary],
  *                   readVisiblePage: (Int, Set[String]) => java.util.List[PipelineStatusSummary],
  *                   pipelines: () => Seq[PipelineConfig], taps: () => Seq[TapConfig],
  *                   run: String => Option[RunLineage]): Either[ResponseEntity[String], AnyRef]
  * }
  * object IncidentsAPIController {
  *   def listedFor(request: HttpServletRequest, limit: Int, read: Int => List[Incident], readPipeline: String => PipelineConfig,
  *                 readTap: String => TapConfig, run: String => Option[RunLineage], items: () => StoredItems): JsonArray
  * }
  * object TapAPIController {
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

    /** The summary table as stored: `total` rows, row 0 newest; row i is of
      * `name(i)`, with status `status(i)`. Held in reverse store order, so a
      * page is only right when the read sorts. Its filtered page read is the
      * store trait's own (every row read, then filtered, sorted and paged in
      * memory: the non-MongoDB branch); every read is counted. */
    private class SummaryDb(total: Int, name: Int => String, status: Int => String = _ => "success") extends InMemoryUserDb {
        private val gson = new com.google.gson.Gson
        val summaries: IndexedSeq[PipelineStatusSummary] = (0 until total).map { i =>
            val at = 1800000000000L - i * 1000L
            PipelineStatusSummary("ts" + i, at, at, name(i), "tok-" + i, "JobRunner", "s", "e", "1s", status(i))
        }
        private val docs: List[String] = summaries.reverse.map(r => gson.toJson(PipelineStatusSummaryTable(r.pipelineToken, r, r.createdAt))).toList
        val filteredReads: mutable.ArrayBuffer[(String, Set[String], Int, Int, String)] = mutable.ArrayBuffer()
        val eventReads: mutable.ArrayBuffer[String] = mutable.ArrayBuffer()
        override def getAllItemsAsJSON(tableName: String): List[String] = docs
        override def queryJSONItemsByKey(tableName: String, keyName: String, key: String): List[String] = { eventReads += key; Nil }
        override def getPageOfItemsWhereInAsJSON(
            tableName: String,
            field: String,
            values: Set[String],
            pageNbr: Int,
            maxPageSize: Int,
            sortField: String,
            sortDescending: Boolean
        ): List[String] = {
            filteredReads += ((field, values, pageNbr, maxPageSize, sortField))
            super.getPageOfItemsWhereInAsJSON(tableName, field, values, pageNbr, maxPageSize, sortField, sortDescending)
        }
        def page(names: Set[String], n: Int): java.util.List[PipelineStatusSummary] = PipelineStatusUtil.getVisiblePipelineStatusSummary(names, n, this)
        def tokensOf(keep: String => Boolean): IndexedSeq[String] = summaries.filter(r => keep(r.pipeline)).map(_.pipelineToken)
    }

    private def everyThird(i: Int): String = if (i % 3 == 0) "orders" else "payroll"

    private def tokens(l: java.util.List[PipelineStatusSummary]): Seq[String] = l.asScala.map(_.pipelineToken).toSeq

    private def assertNewestFirst(l: java.util.List[PipelineStatusSummary], who: String): Unit = {
        val at = l.asScala.map(_.createdAt)
        assert(at == at.sorted.reverse, who + ": newest first " + at)
    }

    private val failReadPage: Int => java.util.List[PipelineStatusSummary] = n => fail("the unfiltered page must not be read for this caller: " + n)

    private def rows(r: Either[ResponseEntity[String], AnyRef]): java.util.List[PipelineStatusSummary] = r match {
        case Right(l: java.util.List[_]) => new java.util.ArrayList[PipelineStatusSummary](l.asScala.map(_.asInstanceOf[PipelineStatusSummary]).asJava)
        case other => fail("expected summary rows, got " + other)
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

    // ================================================================== summary rows: the store-filtered page

    test("bullet 3: 60 summary rows of which every third is visible: page 1 returns 20 visible rows and page 2 returns none") {
        val db = new SummaryDb(60, everyThird)
        val p1 = db.page(Set("orders"), 1)
        assert(tokens(p1) == db.tokensOf(_ == "orders"), tokens(p1))
        assert(p1.size == 20)
        assertNewestFirst(p1, "page 1")
        assert(db.page(Set("orders"), 2).isEmpty)
        assert(db.filteredReads.size == 2, "one store read per page: " + db.filteredReads)
    }

    test("bullet 3: a sparse history (1 row in 15 visible): every visible row is reachable, in order, no duplicates, full pages until the last") {
        val db = new SummaryDb(1000, i => if (i % 15 == 0) "orders" else "payroll") // 67 visible rows
        val visible = db.tokensOf(_ == "orders")
        assert(visible.size == 67)
        val pages = (1 to 5).map(n => db.page(Set("orders"), n))
        assert(pages.map(_.size) == Seq(20, 20, 20, 7, 0), pages.map(tokens))
        pages.foreach(p => assertNewestFirst(p, "page"))
        val all = pages.flatMap(tokens)
        assert(all == visible, all)
        assert(all.distinct == all, "no duplicates")
        // one store read per page: field, names, skip page, size and sort as the unfiltered read uses
        assert(db.filteredReads == (0 until 5).map(n => ("json.pipeline", Set("orders"), n, 20, "created_at")), db.filteredReads)
    }

    test("bullet 3: deep pages stay full: page 40 of a half-visible history of 2000 rows holds visible rows 780 to 799") {
        val db = new SummaryDb(2000, i => if (i % 2 == 0) "orders" else "payroll")
        val visible = db.tokensOf(_ == "orders")
        for (page <- Seq(1, 12, 40, 50)) assert(tokens(db.page(Set("orders"), page)) == visible.slice((page - 1) * 20, page * 20), "page " + page)
        assert(db.page(Set("orders"), 51).isEmpty)
    }

    test("bullet 3: a row still processing is checked against its events only when it is returned") {
        val db = new SummaryDb(90, everyThird, _ => "processing")
        val p1 = db.page(Set("orders"), 1)
        assert(db.eventReads == tokens(p1), db.eventReads)
        assert(db.eventReads.size == 20)
    }

    test("bullet 3: no store read for an empty visible set") {
        val db = new SummaryDb(60, everyThird)
        assert(db.page(Set.empty, 1).isEmpty)
        assert(db.filteredReads.isEmpty)
    }

    // ================================================================== summary rows through the route seam

    // orders was renamed from orders_old: its old rows count for it (PipelineAliases)
    private val ordersRenamed = orders.copy(previousNames = java.util.Arrays.asList("orders_old"))
    private val renamedPipelines: Seq[PipelineConfig] = Seq(ordersRenamed, payroll)
    private def renamedHistory(i: Int): String = if (i % 15 == 0) "orders" else if (i % 15 == 7) "orders_old" else "payroll"

    test("bullet 3: a limited caller's page is the store page filtered to the record names it may see, equal to all rows filtered then paged") {
        for ((who, rk) <- limited) {
            val db = new SummaryDb(1000, renamedHistory)
            var pipelineReads = 0
            var tapReads = 0
            val names = mutable.ArrayBuffer[Set[String]]()
            def view(page: Int) = PipelineStatusAPIController.summaryView(
                req(rk),
                page,
                failReadPage,
                (n, v) => { names += v; db.page(v, n) },
                () => { pipelineReads += 1; renamedPipelines },
                () => { tapReads += 1; taps },
                _ => None
            )
            // the in-memory rule: every row, filtered as restrictedView filters, then paged
            val all = new java.util.ArrayList[PipelineStatusSummary](db.summaries.asJava)
            val visible = PipelineStatusAPIController.visibleRecordNames(req(rk), renamedPipelines)
            val filtered = tokens(rows(PipelineStatusAPIController.filterFor(visible, all)))
            assert(visible == Set("orders", "orders_old"), who + ": " + visible)
            assert(filtered.size == 134, who + ": " + filtered.size)
            val pages = (1 to 8).map(view).map(rows)
            for ((p, k) <- pages.zipWithIndex) assert(tokens(p) == filtered.slice(k * 20, (k + 1) * 20), who + " page " + (k + 1))
            assert(pages.map(_.size) == Seq(20, 20, 20, 20, 20, 20, 14, 0), who)
            assert(pipelineReads == 8 && tapReads == 8, who + ": each store listed once per request: " + pipelineReads + "/" + tapReads)
            assert(names.toSet == Set(Set("orders", "orders_old")) && db.filteredReads.size == 8, who + ": " + names)
        }
    }

    test("bullet 3: a limited caller's page still has hidden tap names scrubbed from what it shows") {
        val db = new SummaryDb(3, _ => "orders")
        val withSummary = (i: Int) => db.summaries(i).copy(aiSummary = "tap t_hr sent no rows")
        // a tap outside every limited caller's scope that feeds the visible pipeline
        val tHr = TapConfig(name = "t_hr", description = "d", targetPipeline = "orders", catalog = "HR", createdByKeyLabel = "someone")
        for ((who, rk) <- limited) {
            val got = PipelineStatusAPIController.summaryView(
                req(rk),
                1,
                failReadPage,
                (_, _) => new java.util.ArrayList[PipelineStatusSummary]((0 until 3).map(withSummary).asJava),
                () => pipelines,
                () => taps :+ tHr,
                _ => None
            )
            assert(rows(got).asScala.map(_.aiSummary).toSet == Set("tap [hidden] sent no rows"), who + ": " + rows(got))
        }
    }

    test("unchanged: a caller that is not limited reads one store page, the page it asked for, and gets it as read") {
        for ((who, r) <- unchanged; page <- Seq(1, 2, 7)) {
            val db = new SummaryDb(300, everyThird)
            val asked = mutable.ArrayBuffer[Int]()
            val read: Int => java.util.List[PipelineStatusSummary] = n => {
                asked += n
                new java.util.ArrayList[PipelineStatusSummary](db.summaries.slice((n - 1) * 20, n * 20).asJava)
            }
            val got = PipelineStatusAPIController.summaryView(
                r,
                page,
                read,
                (_, _) => fail("the filtered page must not be read for this caller"),
                failPipelines,
                failTaps,
                _ => fail("run read")
            )
            assert(asked == Seq(page), who + " page " + page + ": " + asked)
            assert(got == Right(read(page)), who)
            assert(tokens(rows(got)) == db.summaries.slice((page - 1) * 20, page * 20).map(_.pipelineToken), who)
        }
    }

    test("unchanged: a limited caller that may see every row gets, on every page, the rows of that store page") {
        for ((who, rk) <- limited; page <- Seq(1, 2, 5, 12)) {
            val db = new SummaryDb(300, _ => "orders")
            val got = PipelineStatusAPIController.summaryView(req(rk), page, failReadPage, (n, v) => db.page(v, n), () => pipelines, () => taps, _ => None)
            assert(tokens(rows(got)) == db.summaries.slice((page - 1) * 20, page * 20).map(_.pipelineToken), who + " page " + page)
        }
    }

    test("wiring: GET /pipeline/status reads the summary through summaryView with the store-filtered page; filledSummaryPage is gone") {
        def source(path: String): String = {
            var dir = java.nio.file.Paths.get("").toAbsolutePath
            while (dir != null && !java.nio.file.Files.exists(dir.resolve(path))) dir = dir.getParent
            new String(java.nio.file.Files.readAllBytes(dir.resolve(path)), "UTF-8")
        }
        val route = source("datrisserver/src/main/scala/ai/datris/api/PipelineStatusAPIController.scala")
        assert(route.contains("PipelineStatusAPIController.summaryView("))
        assert(route.contains("(n, names) => PipelineStatusUtil.getVisiblePipelineStatusSummary(names, n)"))
        assert(route.contains("n => PipelineStatusUtil.getPipelineStatusSummary(pipelinename, n)"))
        val util = source("datrisserver/src/main/scala/ai/datris/util/PipelineStatusUtil.scala")
        assert(!util.contains("def filledSummaryPage(") && !util.contains("SummaryOverReadPages"))
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
            assert(got.toString == IncidentsAPIController.listedJson(r, st.all.take(limit), null, null, items = IncidentsAPIController.NoItems).toString, who)
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
