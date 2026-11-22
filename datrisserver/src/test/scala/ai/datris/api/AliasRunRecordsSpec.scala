package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.TenantInterceptor
import ai.datris.model._
import ai.datris.util.{APIKeyValidator, LineageService}
import ai.datris.util.InMemoryUserDb.grants
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito.{mock, when}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Run-derived fields keyed by pipeline name (freshness, find_data
  * provenance and evidence, lineage graph evidence and historical datasets,
  * the cached inferred column lineage) follow the previous-name record rule
  * for a restricted session: a name reused after a hidden pipeline's rename
  * reads as if it had no runs unless every pipeline it may belong to is
  * visible. Scenario: Marketing `mkt_old` renamed to `mkt_new`, then a Sales
  * pipeline created as `mkt_old`. */
class AliasRunRecordsSpec extends AnyFunSuite {

    private def req(rk: ResolvedKey): HttpServletRequest = {
        val r = mock(classOf[HttpServletRequest])
        when(r.getMethod).thenReturn("GET")
        when(r.getRequestURI).thenReturn("/api/v1/lineage/pipeline/mkt_old")
        when(r.getRemoteAddr).thenReturn("127.0.0.1")
        when(r.getAttribute(TenantInterceptor.ResolvedKeyAttr)).thenAnswer(_ => rk)
        r
    }

    private def session(g: java.util.Map[String, String]): HttpServletRequest =
        req(APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = g)))

    private val bobSales = session(grants("Sales" -> "view"))
    private val bobBoth = session(grants("Sales" -> "view", "Marketing" -> "view"))
    private val editor = req(APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null)))
    private val scopedKey = req(ResolvedKey(None, "k", Capability.parseList(Seq("pipeline:read:catalog=Sales")), isLegacyFullAccess = false))

    private def pg(name: String, catalog: String, table: String, prev: Seq[String] = null): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true)),
            previousNames = if (prev == null) null else new java.util.ArrayList[String](prev.asJava)
        )

    private val salesMktOld = pg("mkt_old", "Sales", "sales_t")
    private val mktNew = pg("mkt_new", "Marketing", "mkt_t", prev = Seq("mkt_old"))
    private val all = List(salesMktOld, mktNew)

    // ------------------------------------------------------------ freshness

    private def summary(name: String, token: String, at: Long): PipelineStatusSummaryTable =
        PipelineStatusSummaryTable(token, PipelineStatusSummary("ts", at, at, name, token, "p", "s", "e", "1s", "success", recordCount = 7), at)

    private class Reader(rows: List[PipelineStatusSummaryTable]) {
        var calls = 0
        val fn: () => List[PipelineStatusSummaryTable] = () => { calls += 1; rows }
    }

    private val now = 1800000000000L
    private val marketingRun = summary("mkt_old", "run-marketing", now - 1000)

    test("freshness: runs not shown read as no runs (unknown, no run id) and the summaries are not read") {
        val r = new Reader(List(marketingRun))
        val f = LineageService.freshnessOf("mkt_old", Nil, r.fn, recordsShown = false, now, _ => None)
        assert(f.get("state").getAsString == "unknown", f)
        assert(!f.has("latestRunId") && !f.has("lastLandedAt") && !f.has("recordCount"), f)
        assert(r.calls == 0)
    }

    test("freshness: runs shown keep the last landing, count and run id") {
        val r = new Reader(List(marketingRun))
        val f = LineageService.freshnessOf("mkt_old", Nil, r.fn, recordsShown = true, now, _ => None)
        assert(f.get("state").getAsString == "fresh", f)
        assert(f.get("latestRunId").getAsString == "run-marketing" && f.get("recordCount").getAsInt == 7, f)
        assert(f.has("lastLandedAt"))
        assert(r.calls == 1)
    }

    // ------------------------------------------------------------ find_data

    test("find: a reused name hides its runs unless the session sees every claimant; others unchanged") {
        val sales = CatalogFindAPIController.recordsShown(bobSales, all, List(salesMktOld))
        assert(!sales("mkt_old"), "contested: the Marketing pipeline is hidden")
        val both = CatalogFindAPIController.recordsShown(bobBoth, all, all)
        assert(both("mkt_old") && both("mkt_new"))
        // a name nobody else claims stays shown
        val solo = pg("orders", "Sales", "orders_t")
        assert(CatalogFindAPIController.recordsShown(bobSales, solo :: all, List(solo, salesMktOld))("orders"))
        // unrestricted sessions: no rule (null = as before)
        assert(CatalogFindAPIController.recordsShown(editor, all, all) == null)
        // a catalog-scoped API key is held to its scope (scoped-key-read-routes, Part B): the session's rule
        val keyRule = CatalogFindAPIController.recordsShown(scopedKey, all, List(salesMktOld))
        assert(keyRule != null && !keyRule("mkt_old"), "contested: the Marketing pipeline is hidden from the key")
    }

    test("lineage: recordsShown follows the same rule; true for unrestricted callers") {
        assert(!LineageAPIController.recordsShown(bobSales, "mkt_old", all))
        assert(LineageAPIController.recordsShown(bobBoth, "mkt_old", all))
        assert(LineageAPIController.recordsShown(bobSales, "orders", pg("orders", "Sales", "orders_t") :: all))
        assert(LineageAPIController.recordsShown(editor, "mkt_old", all))
        // a catalog-scoped API key is held to its scope (scoped-key-read-routes, Part B)
        assert(!LineageAPIController.recordsShown(scopedKey, "mkt_old", all))
    }

    // ------------------------------------------------------------ lineage graph

    private val marketingDataset = "dataset:postgres:datris.public.mkt_t_legacy"
    private val salesDataset = LineageService.datasets(salesMktOld).head.id
    private val evidence = Map(
        ("pipeline:mkt_old", marketingDataset) -> LineageService.EdgeEvidence(3, 30L, "2026-11-17T19:12:37Z", "SUCCESS", 0),
        ("pipeline:mkt_old", salesDataset) -> LineageService.EdgeEvidence(1, 7L, "2026-11-17T20:00:00Z", "SUCCESS", 0)
    )
    private val g = LineageService.build(Nil, all, List(LineageService.ObservedDataset("mkt_old", marketingDataset)), evidence)

    test("graph: the reused name loses run evidence and the historical dataset its runs landed in") {
        assert(g.nodes.exists(n => n.id == marketingDataset && n.historical), "fixture: Marketing's run made a historical node")
        val v = LineageService.withoutHiddenRuns(LineageAPIController.visibleGraph(bobSales, g), all)
        val ids = v.nodes.map(_.id).toSet
        assert(ids.contains("pipeline:mkt_old") && ids.contains(salesDataset), ids)
        assert(!ids.contains(marketingDataset), ids)
        assert(v.edges.forall(e => ids.contains(e.from) && ids.contains(e.to)))
        assert(v.edges.filter(e => e.from == "pipeline:mkt_old" || e.to == "pipeline:mkt_old").forall(_.evidence.isEmpty), v.edges)
    }

    test("graph: a session seeing every claimant, and unrestricted callers, keep the graph as is") {
        val visible = LineageAPIController.visibleGraph(bobBoth, g)
        assert(LineageService.withoutHiddenRuns(visible, all) eq visible)
        assert(LineageService.withoutHiddenRuns(g, all) eq g, "every pipeline visible: unchanged")
        assert(LineageAPIController.visibleGraph(editor, g) eq g)
        // runs shown for the visible graph's own pipeline when the name is not reused
        assert(LineageService.runShown("mkt_new", all, Set("pipeline:mkt_new", "pipeline:mkt_old")))
    }
}
