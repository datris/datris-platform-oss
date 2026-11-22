package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.LineageAPIController
import ai.datris.auth.ScopeDenialFixtures
import ai.datris.model._
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.JsonObject
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Story: Four small read leaks for callers limited to a catalog
  * (plans/stories/limited-caller-read-leaks.md), Acceptance bullet 4 (gap 11,
  * owner decision D4): the freshness of a visible pipeline whose feeding tap
  * the caller cannot read is reported from the pipeline's own landings only
  * (`fresh` / `unknown`, never `stale`, no `cursorUpdatedAt`); with a feeding
  * tap the caller can read it is what it is today.
  *
  * `GET /lineage/{type}/{name}` builds freshness inside
  * `LineageService.neighborhood`, which a limited caller walks on its own
  * graph (`LineageAPIController.callerGraph`) and everyone else on null (the
  * whole cached graph). The cached store is Mongo, so the walk's tap choice
  * is pinned on one new pure seam the walk must use:
  * {{{
  * object LineageService {
  *   // the taps freshness is computed from on a walk of `graph`: `taps` itself
  *   // when graph is null; otherwise only the taps whose `tap:<name>` node is in
  *   // the (caller-filtered) graph
  *   def freshnessTaps(taps: List[TapConfig], graph: LineageService.Graph): List[TapConfig]
  * }
  * }}}
  * `find_data` (CatalogFindAPIController) already hands CatalogFind only the
  * taps the caller may read; that is pinned at the end. */
class LineageFreshnessHiddenTapSpec extends AnyFunSuite with BeforeAndAfterEach {

    private def req(rk: ResolvedKey): HttpServletRequest = ScopeDenialFixtures.request(rk, "GET", "/api/v1/lineage/pipeline/orders")._1
    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)

    private val bob: ResolvedKey = APIKeyValidator.resolveFromSession(
        User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))
    )
    private val limited: Seq[(String, ResolvedKey)] = Seq(
        "restricted session" -> bob,
        "catalog= key" -> key("sales", "pipeline:read:catalog=Sales", "tap:read:catalog=Sales", "metadata:read"),
        "owner=self key" -> key("own", "pipeline:read:owner=self", "tap:read:owner=self", "metadata:read")
    )

    private val now = 1800000000000L // 2027-01-15T08:00:00Z
    private def at(ms: Long): String = {
        val f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z")
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
        f.format(new java.util.Date(ms))
    }

    private val pgOrders = Destination(schemaProperties = null, database = Database(dbName = "datris", schema = "public", table = "orders", usePostgres = true))
    private val orders = PipelineConfig(name = "orders", catalog = "Sales", createdByKeyLabel = "own", destination = pgOrders)
    private val pipelines: List[PipelineConfig] = List(orders)

    /** Hourly tap; `lastRunMsAgo` past 2 hours makes it stale (ActivitySignals.computeStale). */
    private def hourly(name: String, catalog: String, owner: String, lastRunMsAgo: Long): TapConfig =
        TapConfig(
            name = name,
            description = "d",
            targetPipeline = "orders",
            catalog = catalog,
            createdByKeyLabel = owner,
            cronExpression = "0 0 * * * ?",
            lastRunTime = at(now - lastRunMsAgo)
        )

    private val hiddenStale = hourly("hr_feed", "HR", "someone", 3L * 3600000L)
    private val visibleStale = hourly("t_orders", "Sales", "own", 3L * 3600000L)
    private val visibleFresh = hourly("t_orders", "Sales", "own", 10L * 60000L)

    private val cursors = Map("hr_feed" -> "2027-01-15T04:59:00Z", "t_orders" -> "2027-01-15T07:55:00Z")

    private class Cursors {
        val asked: mutable.ArrayBuffer[String] = mutable.ArrayBuffer[String]()
        val fn: String => Option[String] = n => { asked += n; cursors.get(n) }
    }

    private def landing: List[PipelineStatusSummaryTable] = {
        val t = now - 30L * 60000L
        List(PipelineStatusSummaryTable("tok-1", PipelineStatusSummary("ts", t, t, "orders", "tok-1", "p", "s", "e", "1s", "success", recordCount = 7), t))
    }

    /** Freshness of `orders` as a walk of `graph` reports it. */
    private def walked(allTaps: List[TapConfig], graph: LineageService.Graph, landed: Boolean, c: Cursors): JsonObject =
        LineageService.freshnessOf("orders", LineageService.freshnessTaps(allTaps, graph), () => if (landed) landing else Nil, recordsShown = true, now, c.fn)

    private def callerGraph(rk: ResolvedKey, taps: List[TapConfig]): LineageService.Graph =
        LineageAPIController.callerGraphOf(req(rk), LineageService.build(taps, pipelines), () => pipelines)

    private var savedEnv: DatrisEnvironment = _

    override def beforeEach(): Unit = {
        savedEnv = DatrisEnvironment.values
        val e = InMemoryUserDb.env()
        DatrisEnvironment.init(e)
        TenantContext.set(e)
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedEnv
    }

    test("precondition: computed over every stored tap, orders reads stale with the hidden tap's cursor") {
        val c = new Cursors
        val f = LineageService.freshnessOf("orders", List(hiddenStale), () => landing, recordsShown = true, now, c.fn)
        assert(f.get("state").getAsString == "stale" && f.get("cursorUpdatedAt").getAsString == cursors("hr_feed"), f)
    }

    test("bullet 4: a visible pipeline fed by a hidden tap is never stale and has no cursorUpdatedAt; the hidden tap's state is not read") {
        for ((who, rk) <- limited; landed <- Seq(true, false)) {
            val c = new Cursors
            val g = callerGraph(rk, List(hiddenStale))
            assert(g.nodes.exists(_.id == "pipeline:orders") && !g.nodes.exists(_.id == "tap:hr_feed"), who + ": precondition " + g.nodes.map(_.id))
            val f = walked(List(hiddenStale), g, landed, c)
            val expected = if (landed) "fresh" else "unknown"
            assert(f.get("state").getAsString == expected, who + " landed=" + landed + ": " + f)
            assert(!f.has("cursorUpdatedAt"), who + ": " + f)
            assert(!c.asked.contains("hr_feed"), who + ": " + c.asked)
            if (landed) assert(f.get("latestRunId").getAsString == "tok-1" && f.get("recordCount").getAsInt == 7, who + ": own landings kept " + f)
        }
    }

    test("bullet 4: with a visible and a hidden feeding tap, freshness follows the visible tap only") {
        for ((who, rk) <- limited) {
            val taps = List(hiddenStale, visibleFresh) // the hidden tap first: what a full-list find picks
            val c = new Cursors
            val f = walked(taps, callerGraph(rk, taps), landed = true, c)
            assert(f.get("state").getAsString == "fresh", who + ": " + f)
            assert(f.get("cursorUpdatedAt").getAsString == cursors("t_orders"), who + ": " + f)
            assert(!c.asked.contains("hr_feed"), who + ": " + c.asked)
        }
    }

    test("unchanged: a visible feeding tap behind schedule still reads stale, with its cursor, for a limited caller") {
        for ((who, rk) <- limited; landed <- Seq(true, false)) {
            val c = new Cursors
            val f = walked(List(visibleStale), callerGraph(rk, List(visibleStale)), landed, c)
            assert(f.get("state").getAsString == "stale" && f.get("cursorUpdatedAt").getAsString == cursors("t_orders"), who + ": " + f)
        }
    }

    test("unchanged: a caller that is not limited walks null and gets freshness over every stored tap, as today") {
        val taps = List(hiddenStale, visibleFresh)
        assert(LineageService.freshnessTaps(taps, null) eq taps)
        val c = new Cursors
        val f = walked(taps, null, landed = true, c)
        val today = LineageService.freshnessOf("orders", taps, () => landing, recordsShown = true, now, new Cursors().fn)
        assert(f.toString == today.toString, f)
        assert(f.get("state").getAsString == "stale" && f.get("cursorUpdatedAt").getAsString == cursors("hr_feed"), f)
    }

    private def source(rel: String): String = {
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get(rel.stripPrefix("datrisserver/")))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
    }

    test("wiring: the lineage walk computes freshness from freshnessTaps on the graph it walks") {
        val src = source("datrisserver/src/main/scala/ai/datris/util/LineageService.scala")
        val i = src.indexOf(
            "    def neighborhood(\n        nodeType: String,\n        name: String,\n        direction: String,\n        depth: Int,\n        runs: Int,\n        columns: Boolean"
        )
        assert(i >= 0, "the full neighborhood signature")
        val end = src.indexOf("\n    def ", i + 10)
        val body = src.substring(i, if (end < 0) src.length else end)
        assert(body.contains("freshnessTaps("), body.take(600))
    }

    test("pin: find_data already computes freshness from the taps the caller may read") {
        val src = source("datrisserver/src/main/scala/ai/datris/api/CatalogFindAPIController.scala")
        assert(src.contains("""taps.filter(t => t != null && CapabilityCheck.grants(request, "tap", "read", tapScopeContext(t)))"""))
    }
}
