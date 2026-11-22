package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CallerVisibility, CapabilityScopes, HiddenTapScrub, ScopeDenialFixtures}
import ai.datris.incident.{Incident, IncidentStep}
import ai.datris.model._
import ai.datris.util.{ActivitySignals, APIKeyValidator, InMemoryUserDb, LineageService, VectorSearchRegistry}
import ai.datris.util.InMemoryUserDb.grants
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import jakarta.servlet.http.HttpServletRequest
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import java.time.Instant
import scala.collection.JavaConverters._

/** Story: plans/stories/scoped-key-read-routes.md, Part B (records), review
  * round 1. Behavioural pins for the reviewer's findings 1-7 and the lead's
  * rulings on them:
  *  1. a key's lineage graph keeps a dataset or source only while linked to
  *     a pipeline or tap it may read (a kept catalog node no longer anchors);
  *  2. GET /tap/logs embeds each run's pipeline rollup as GET /pipeline/status
  *     would show it to the caller (`TapAPIController.shownRollup`);
  *  3. the record handlers decide through injectable seams
  *     (`LineageAPIController.neighborhoodResponse`, `columnsResponse`,
  *     `callerGraphOf`; `ProvenanceAPIController.provenanceResponse`;
  *     `ActivityAPIController.signalsResponse`; `IncidentsAPIController
  *     .listedJson`, `getResponse`, `evidenceResponse`, `shownIncident`);
  *  4. lineage columns of a pipeline that is not stored is the not-found 404
  *     for a limited key, before any version snapshot is read;
  *  5. search: the container (namer) rule and the writer filter apply to
  *     callers limited for pipelines, the tap-stamp scrub to callers limited
  *     for taps;
  *  6. an inert pipeline capability with no `catalog=` / `owner=` does not make
  *     a key limited, in both modes;
  *  7. a visible incident's evidence hides other pipelines' blast radius,
  *     other callers' labels, the secret name, and a hidden target's
  *     profiles. */
class ScopedKeyRecordVisibilityReviewSpec extends AnyFunSuite with BeforeAndAfterEach {

    // ================================================================== callers

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private def req(rk: ResolvedKey, method: String = "GET", uri: String = "/api/v1/lineage"): HttpServletRequest =
        ScopeDenialFixtures.request(rk, method, uri)._1

    private val bob: ResolvedKey = APIKeyValidator.resolveFromSession(
        User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))
    )
    private val editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val legacy: ResolvedKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val wildcard: ResolvedKey = key("full", "*:*")

    /** owner=self key (label `own`). */
    private def ownKey(extra: String*): ResolvedKey = key("own", (Seq("pipeline:read:owner=self", "tap:read:owner=self") ++ extra): _*)

    /** Limited for pipelines only: `catalog=Sales` pipeline read, every tap. */
    private def salesPlusTaps(extra: String*): ResolvedKey = key("salestap", (Seq("pipeline:read:catalog=Sales", "tap:read") ++ extra): _*)

    /** Limited for pipelines only: own pipelines, every tap. */
    private def pipelineOnly(extra: String*): ResolvedKey = key("own", (Seq("tap:read", "pipeline:read:owner=self") ++ extra): _*)

    private def salesKey(extra: String*): ResolvedKey = key("sales", (Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales") ++ extra): _*)

    private def unchanged: Seq[(String, HttpServletRequest)] = Seq(
        "unrestricted editor" -> req(editor),
        "*:* key" -> req(wildcard),
        "legacy key" -> req(legacy),
        "job:read monitor key" -> req(key("monitor", "job:read", "metadata:read", "incident:read")),
        "no identity" -> ScopeDenialFixtures.request(null, "GET", "/api/v1/lineage")._1,
        "null request" -> null
    ) ++ Seq("read-only", "reporting", "ops", "rag-builder").map(t => (t + " template") -> req(key(t, KeyTemplatesForSpecs.capabilities(t): _*)))

    // ================================================================== stored items

    private def pgDest(table: String) =
        Destination(schemaProperties = null, database = Database(dbName = "datris", schema = "public", table = table, usePostgres = true))
    private def pg(name: String, catalog: String, table: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = owner, destination = pgDest(table))
    private def tap(name: String, catalog: String, target: String, source: String, owner: String = "someone"): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = catalog, createdByKeyLabel = owner, source = source)

    private val ownP = pg("own_p", "HR", "own_t", owner = "own")
    private val payroll = pg("payroll", "HR", "payroll_secret_tbl")
    private val orders = pg("orders", "Sales", "orders_t")
    private val sharedA = pg("shared_a", "HR", "shared_t", owner = "own")
    private val sharedB = pg("shared_b", "HR", "shared_t")
    private val pipelines: Seq[PipelineConfig] = Seq(ownP, payroll, orders, sharedA, sharedB)
    private val readPipeline: String => PipelineConfig = n => pipelines.find(_.name == n).orNull

    private val tOwn = tap("t_own", "HR", "own_p", "own.api", owner = "own")
    private val tHr = tap("t_hr", "HR", "payroll", "hr.internal")
    private val tSharedOwn = tap("t_shared_own", "HR", "own_p", "shared.api", owner = "own")
    private val tSharedHr = tap("t_shared_hr", "HR", "payroll", "shared.api")
    private val tSales = tap("t_sales", "Sales", "orders", "sales.api")
    private val tSalesToHr = tap("t_sales_hr", "Sales", "payroll", "sales2.api")
    private val taps: Seq[TapConfig] = Seq(tOwn, tHr, tSharedOwn, tSharedHr, tSales, tSalesToHr)
    private val readTap: String => TapConfig = n => taps.find(_.name == n).orNull

    private def ds(p: PipelineConfig): String = LineageService.datasets(p).head.id
    private def graph: LineageService.Graph = LineageService.build(taps.toList, pipelines.toList)

    private val failReadPipeline: String => PipelineConfig = n => fail("the pipeline store must not be read for this caller: " + n)
    private val failReadTap: String => TapConfig = n => fail("the tap store must not be read for this caller: " + n)
    private val failPipelines: () => Seq[PipelineConfig] = () => fail("the pipeline store must not be read for this caller")
    private val failTaps: () => Seq[TapConfig] = () => fail("the tap store must not be read for this caller")
    private val failGraph: () => LineageService.Graph = () => fail("the caller graph must not be built for this caller")

    private val gson = new Gson
    private def status(r: ResponseEntity[String]): Int = r.getStatusCode.value

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

    private def withInertMode[T](allow: Boolean)(f: => T): T = {
        val prior = sys.props.get(CapabilityScopes.InertScopesProperty)
        if (allow) sys.props(CapabilityScopes.InertScopesProperty) = CapabilityScopes.InertScopesAllow
        else sys.props -= CapabilityScopes.InertScopesProperty
        try f
        finally prior match {
                case Some(v) => sys.props(CapabilityScopes.InertScopesProperty) = v
                case None => sys.props -= CapabilityScopes.InertScopesProperty
            }
    }

    private def ids(g: LineageService.Graph): Set[String] = g.nodes.map(_.id).toSet
    private def touches(g: LineageService.Graph, id: String): Boolean = g.edges.exists(e => e.from == id || e.to == id)

    // ================================================================== finding 1: lineage anchors

    test("finding 1: an owner=self key's graph holds its own pipeline, taps and datasets and nothing that only a hidden pipeline or tap links") {
        for (allow <- Seq(false, true)) withInertMode(allow) {
            val v = LineageAPIController.visibleGraph(req(ownKey("metadata:read")), graph)
            val i = ids(v)
            val m = "allow=" + allow + ": " + i
            // ruling 3 pinned for lineage: the key's own items are there
            Seq("pipeline:own_p", "tap:t_own", "tap:t_shared_own", "pipeline:shared_a", ds(ownP), ds(sharedA), "source:own.api", "source:shared.api")
                .foreach(x => assert(i.contains(x), x + " " + m))
            assert(v.edges.exists(e => e.from == "pipeline:own_p" && e.to == ds(ownP)), m)
            // the hidden pipeline's dataset is gone from nodes and edges (it reached the graph through catalog:HR before)
            assert(!i.contains(ds(payroll)) && !touches(v, ds(payroll)), m)
            Seq("pipeline:payroll", "pipeline:shared_b", "tap:t_hr", "tap:t_shared_hr", "source:hr.internal").foreach { x =>
                assert(!i.contains(x) && !touches(v, x), x + " " + m)
            }
            assert(!v.toJson.toString.contains("payroll_secret_tbl"), m)
            // a shared dataset and a shared source stay, with nothing of the hidden writer or reader hanging off them
            assert(v.edges.filter(_.to == ds(sharedA)).map(_.from).filter(_.startsWith("pipeline:")).toSet == Set("pipeline:shared_a"), m)
            assert(v.edges.filter(_.from == "source:shared.api").map(_.to).toSet == Set("tap:t_shared_own"), m)
            // runs of the hidden writer are not shown on the walked graph (freshness and columns follow the upstream pipelines kept)
            assert(!LineageService.runShown("shared_b", pipelines, i.contains), m)
            assert(LineageService.runShown("shared_a", pipelines, i.contains), m)
            assert(v.edges.forall(e => i.contains(e.from) && i.contains(e.to)), m)
        }
    }

    test("finding 1: a key limited for pipelines only (catalog=Sales, unscoped tap:read) keeps every tap but no dataset of a pipeline it cannot read") {
        val v = LineageAPIController.visibleGraph(req(salesPlusTaps("metadata:read")), graph)
        val i = ids(v)
        assert(i.contains("pipeline:orders") && i.contains(ds(orders)), i)
        taps.foreach(t => assert(i.contains("tap:" + t.name), t.name + " " + i))
        Seq(ds(payroll), ds(ownP), ds(sharedA)).foreach(d => assert(!i.contains(d) && !touches(v, d), d + " " + i))
        Seq("pipeline:payroll", "pipeline:own_p", "pipeline:shared_a", "pipeline:shared_b").foreach(p => assert(!i.contains(p), p))
        // a kept catalog node holds no dataset of a hidden pipeline
        assert(!v.edges.exists(e => e.to == "catalog:HR"), v.edges)
    }

    test("finding 1 (D2): a dataset only a hidden pipeline writes, and a source only a hidden tap reads, answer exactly as an unknown node") {
        def walk(nodeType: String, name: String): LineageService.Graph => JsonObject =
            g => {
                val effective = if (g == null) graph else g
                if (!effective.nodes.exists(_.id == nodeType + ":" + name)) null
                else { val o = new JsonObject(); o.addProperty("node", nodeType + ":" + name); o }
            }
        for (rk <- Seq(ownKey("metadata:read"), salesPlusTaps("metadata:read"), salesKey("metadata:read"))) {
            val r = req(rk)
            val hiddenDs = ds(payroll).stripPrefix("dataset:")
            val hidden = LineageAPIController.neighborhoodResponse(
                r,
                "dataset",
                hiddenDs,
                walk("dataset", hiddenDs),
                () => LineageAPIController.callerGraphOf(r, graph, () => pipelines),
                readTap
            )
            val unknownName = "postgres:datris.public.never_existed"
            val unknown = LineageAPIController.neighborhoodResponse(
                r,
                "dataset",
                unknownName,
                walk("dataset", unknownName),
                () => LineageAPIController.callerGraphOf(r, graph, () => pipelines),
                readTap
            )
            assert(status(hidden) == 404 && status(unknown) == 404, rk.label)
            assert(hidden.getBody == unknown.getBody.replace(unknownName, hiddenDs), rk.label + ": " + hidden.getBody)
            if (!CallerVisibility.mayReadStored(Some(rk), "tap", tHr.catalog, tHr.createdByKeyLabel)) {
                val src = LineageAPIController.neighborhoodResponse(
                    r,
                    "source",
                    "hr.internal",
                    walk("source", "hr.internal"),
                    () => LineageAPIController.callerGraphOf(r, graph, () => pipelines),
                    readTap
                )
                val none = LineageAPIController.neighborhoodResponse(
                    r,
                    "source",
                    "no.such",
                    walk("source", "no.such"),
                    () => LineageAPIController.callerGraphOf(r, graph, () => pipelines),
                    readTap
                )
                assert(status(src) == 404 && src.getBody == none.getBody.replace("no.such", "hr.internal"), rk.label)
            }
        }
        // unchanged callers walk the whole graph (null) and the caller graph is never built
        for ((name, r) <- unchanged) {
            var walked: LineageService.Graph = graph
            val out = LineageAPIController.neighborhoodResponse(
                r,
                "pipeline",
                "payroll",
                g => { walked = g; val o = new JsonObject(); o.addProperty("x", 1); o },
                failGraph,
                failReadTap
            )
            assert(walked == null && status(out) == 200, name)
        }
    }

    // ================================================================== finding 2: tap log rollups

    private def statusResponse(p: String, feedFile: String, preview: JsonArray = null): PipelineStatusResponse = PipelineStatusResponse(
        PipelineStatusRollup(
            allDone = true,
            status = "error",
            jobs = List(
                PipelineJobRollup(
                    "tok-" + p,
                    p,
                    feedFile,
                    "error",
                    "s",
                    "l",
                    "1s",
                    PipelineJobError("p", "row from " + feedFile + " failed", "x"),
                    resultPreview = preview
                )
            ).asJava
        ),
        List(PipelineStatus(0, "t", p, "proc", "pub", "tok-" + p, feedFile, "end", "ok", "Processing " + feedFile, 0L)).asJava
    )

    test("finding 2: GET /tap/logs embeds a run's rollup exactly as GET /pipeline/status shows it to a limited caller, and null when it may not read the run") {
        def rollupOf(r: HttpServletRequest, resp: PipelineStatusResponse): Option[PipelineStatusRollup] =
            PipelineStatusAPIController.restrictedViewWith(r, resp, pipelines, taps, _ => None) match {
                // limited-caller-read-leaks (pinned edit, D2 (a)): a hidden run now reads as an
                // empty rollup on /pipeline/status; /tap/logs embeds null for it, as for no jobs
                case Right(x: PipelineStatusResponse) => Option(x.rollup).filter(ro => ro.jobs != null && !ro.jobs.isEmpty)
                case _ => None
            }
        val cases: Seq[(String, HttpServletRequest, PipelineStatusResponse, Boolean)] = Seq(
            // pipeline-limited only: another key's pipeline is null, its own keeps readable tap names
            ("pipeline-limited only, payroll", req(pipelineOnly("job:read")), statusResponse("payroll", "tap-t_hr.csv"), false),
            ("pipeline-limited only, own_p", req(pipelineOnly("job:read")), statusResponse("own_p", "tap-t_own.csv"), true),
            // a catalog=Sales key on a Sales tap that targets an HR pipeline
            ("catalog=Sales key, Sales tap into HR", req(salesKey("job:read")), statusResponse("payroll", "tap-t_sales_hr.csv"), false),
            ("catalog=Sales key, orders with a hidden tap", req(salesKey("job:read")), statusResponse("orders", "tap-t_hr.csv"), true),
            // restricted session
            ("restricted session, payroll", req(bob), statusResponse("payroll", "tap-t_sales_hr.csv"), false),
            ("restricted session, orders with a hidden tap", req(bob), statusResponse("orders", "tap-t_hr.csv"), true)
        )
        for ((name, r, resp, shown) <- cases) {
            var reads = 0
            val got = TapAPIController.shownRollup(r, resp, () => { reads += 1; pipelines }, () => { reads += 1; taps }, _ => None)
            assert(got.isDefined == shown, name + ": " + got)
            assert(gson.toJson(got.orNull) == gson.toJson(rollupOf(r, resp).orNull), name)
            if (!shown) assert(got.isEmpty, name)
            got.foreach(g => assert(!gson.toJson(g).contains("t_hr") && !gson.toJson(g).contains("payroll"), name + ": " + gson.toJson(g)))
            assert(reads == 2, name + ": each store read once")
        }
        // a readable tap's name stays (pipeline-limited only reads every tap)
        val own = TapAPIController.shownRollup(req(pipelineOnly("job:read")), statusResponse("own_p", "tap-t_own.csv"), () => pipelines, () => taps, _ => None)
        assert(gson.toJson(own.get).contains("tap-t_own.csv"))
        val hiddenTap =
            TapAPIController.shownRollup(req(salesKey("job:read")), statusResponse("orders", "tap-t_hr.csv"), () => pipelines, () => taps, _ => None)
        assert(gson.toJson(hiddenTap.get).contains("tap-[hidden].csv"))
        // preview rows stamped with a hidden tap lose the stamp columns, as on /pipeline/status
        val preview =
            JsonParser.parseString("""[{"id":1,"_datris_tap_run":"t_hr|t","_datris_script_sha":"hr-sha","_datris_source":"tap:t_hr"}]""").getAsJsonArray
        val p = TapAPIController.shownRollup(req(salesKey("job:read")), statusResponse("orders", "orders.csv", preview), () => pipelines, () => taps, _ => None)
        assert(!gson.toJson(p.get).contains("hr-sha"), gson.toJson(p.get))
        // unchanged callers: the same rollup instance, no store read
        for ((name, r) <- unchanged) {
            val resp = statusResponse("payroll", "tap-t_hr.csv")
            assert(TapAPIController.shownRollup(r, resp, failPipelines, failTaps, _ => fail("run read")).get eq resp.rollup, name)
        }
        // no rollup or no jobs: null for everyone, as before
        assert(TapAPIController.shownRollup(req(editor), PipelineStatusResponse(null, Nil.asJava), failPipelines, failTaps, _ => None).isEmpty)
        assert(TapAPIController.shownRollup(
            req(bob),
            PipelineStatusResponse(PipelineStatusRollup(true, "s", Nil.asJava), Nil.asJava),
            failPipelines,
            failTaps,
            _ => None
        ).isEmpty)
        // wiring
        val body = methodBody(source("datrisserver/src/main/scala/ai/datris/api/TapAPIController.scala"), "def getTapLogs(")
        assert(body.contains("TapAPIController") && body.contains(".shownRollup(") && !body.contains("gson.toJsonTree(response.rollup)"), body)
    }

    // ================================================================== finding 3: handler seams

    test("finding 3c: callerGraphOf drops the runs a limited key may not see (withoutHiddenRuns) and is the graph itself for unchanged callers") {
        val prev = new java.util.ArrayList[String](Seq("mkt_old").asJava)
        val salesOld = PipelineConfig(name = "mkt_old", catalog = "Sales", destination = pgDest("sales_t"))
        val mktNew = PipelineConfig(name = "mkt_new", catalog = "Marketing", destination = pgDest("mkt_t"), previousNames = prev)
        val all = List(salesOld, mktNew)
        val marketingDataset = "dataset:postgres:datris.public.mkt_t_legacy"
        val salesDataset = LineageService.datasets(salesOld).head.id
        val evidence = Map(
            ("pipeline:mkt_old", marketingDataset) -> LineageService.EdgeEvidence(3, 30L, "2026-11-17T19:12:37Z", "SUCCESS", 0),
            ("pipeline:mkt_old", salesDataset) -> LineageService.EdgeEvidence(1, 7L, "2026-11-17T20:00:00Z", "SUCCESS", 0)
        )
        val g = LineageService.build(Nil, all, List(LineageService.ObservedDataset("mkt_old", marketingDataset)), evidence)
        for (rk <- Seq(key("k", "pipeline:read:catalog=Sales", "metadata:read"), bob)) {
            var reads = 0
            val v = LineageAPIController.callerGraphOf(req(rk), g, () => { reads += 1; all })
            assert(reads == 1, rk.label)
            assert(ids(v).contains("pipeline:mkt_old") && !ids(v).contains(marketingDataset), rk.label + " " + ids(v))
            assert(v.edges.filter(e => e.from == "pipeline:mkt_old").forall(_.evidence.isEmpty), rk.label)
        }
        for ((name, r) <- unchanged) assert(LineageAPIController.callerGraphOf(r, g, failPipelines) eq g, name)
        val src = source("datrisserver/src/main/scala/ai/datris/api/LineageAPIController.scala")
        assert(methodBody(src, "def callerGraph(").contains("callerGraphOf("))
    }

    test("finding 3d: lineage columns asks for stored inference only for limited callers, from recordsShown") {
        for (rk <- Seq(ownKey("metadata:read"), salesKey("metadata:read"), bob)) {
            val r = req(rk)
            val name = if (rk eq bob) "orders" else if (rk.label == "own") "own_p" else "orders"
            var asked: Option[Boolean] = null
            var reads = 0
            val out = LineageAPIController.columnsResponse(
                r,
                name,
                None,
                infer = false,
                readPipeline,
                () => { reads += 1; pipelines },
                (_, _, _, stored) => { asked = stored; null }
            )
            assert(asked == Some(LineageAPIController.recordsShown(r, name, pipelines)) && reads == 1, rk.label + " " + asked)
            assert(status(out) == 404 && out.getBody == "{\"error\":\"no such pipeline: " + name + "\"}", out.getBody)
        }
        for ((name, r) <- unchanged) {
            var asked: Option[Boolean] = Some(true)
            LineageAPIController.columnsResponse(
                r,
                "payroll",
                Some(2),
                infer = true,
                failReadPipeline,
                failPipelines,
                (_, v, inf, stored) => {
                    assert(v == Some(2) && inf); asked = stored; null
                }
            )
            assert(asked.isEmpty, name)
        }
        val src = source("datrisserver/src/main/scala/ai/datris/api/LineageAPIController.scala")
        assert(methodBody(src, "def columns(").contains("columnsResponse("))
        assert(methodBody(src, "def neighborhood(").contains("neighborhoodResponse("))
    }

    test("finding 4: lineage columns of a pipeline that is not stored is the not-found 404 for a limited key, before any snapshot is read") {
        for (rk <- Seq(ownKey("metadata:read"), salesKey("metadata:read"), salesPlusTaps("metadata:read"))) {
            val r = req(rk, uri = "/api/v1/lineage/columns/orphaned")
            for (version <- Seq(None, Some(3))) {
                val out = LineageAPIController.columnsResponse(
                    r,
                    "orphaned",
                    version,
                    infer = false,
                    _ => null,
                    () => pipelines,
                    (_, _, _, _) => fail("no snapshot may be read")
                )
                // what the same key gets for a name that never existed
                val never = LineageAPIController.columnsResponse(
                    r,
                    "orphaned",
                    version,
                    infer = false,
                    readPipeline,
                    () => pipelines,
                    (_, _, _, _) => fail("no snapshot")
                )
                val expected = "{\"error\":\"no such pipeline" + (if (version.isDefined) " version" else "") + ": orphaned\"}"
                assert(status(out) == 404 && out.getBody == expected, rk.label + " " + version + ": " + out.getBody)
                assert(status(never) == 404 && never.getBody == expected, rk.label)
                // and the body an unrestricted caller gets for a name with no definition at all
                val editorAnswer =
                    LineageAPIController.columnsResponse(req(editor), "orphaned", version, infer = false, failReadPipeline, failPipelines, (_, _, _, _) => null)
                assert(editorAnswer.getBody == expected)
            }
            assert(LineageAPIController.columnsRefusal(r, "orphaned", _ => null).exists(x => status(x) == 404))
        }
        // a restricted session keeps its 404
        assert(LineageAPIController.columnsRefusal(req(bob), "orphaned", _ => null).exists(_.getBody == "{\"error\":\"no such pipeline: orphaned\"}"))
    }

    private def provenanceOf(p: String, tapName: String): JsonObject =
        JsonParser.parseString(
            s"""{"runId":"run-1","pipeline":"$p","job":{"status":"success","filename":"tap-$tapName.csv"},
               |"input":{"kind":"tap","tapName":"$tapName","tapRunTime":"t","scriptSha":"abc","source":"tap:$tapName","filename":"tap-$tapName.csv"},
               |"tapRun":{"tapName":"$tapName","runTime":"t","status":"success"},"tap":{"name":"$tapName","catalog":"HR"},"source":"tap:$tapName"}""".stripMargin
        ).getAsJsonObject

    test("finding 3d/3f: provenance ignores a limited caller's hints, so a visible pipeline name cannot vouch for a hidden run, and scrubs hidden taps") {
        for (rk <- Seq(salesKey("metadata:read"), ownKey("metadata:read"), salesPlusTaps("metadata:read"), bob)) {
            var hints: (String, String) = ("unset", "unset")
            // the run is payroll's; the caller names a pipeline it can see
            val hidden = ProvenanceAPIController.provenanceResponse(
                req(rk, uri = "/api/v1/provenance"),
                "run-1",
                " orders ",
                " t_sales|t ",
                null,
                (p, _, t, _) => { hints = (p, t); provenanceOf("payroll", "t_hr") },
                () => pipelines,
                () => taps,
                readTap
            )
            assert(hints == ((null, null)), rk.label + " " + hints)
            assert(status(hidden) == 404 && hidden.getBody == "{\"error\":\"no such run: run-1\"}", rk.label + ": " + hidden.getBody)
        }
        // a visible run fed by a hidden tap: the tap's blocks go
        val shown = ProvenanceAPIController.provenanceResponse(
            req(salesKey("metadata:read")),
            "run-1",
            null,
            null,
            null,
            (_, _, _, _) => provenanceOf("orders", "t_hr"),
            () => pipelines,
            () => taps,
            readTap
        )
        assert(status(shown) == 200 && !shown.getBody.contains("t_hr") && shown.getBody.contains("tap-[hidden].csv"), shown.getBody)
        // unchanged callers: trimmed hints, the resolved run as is, nothing read
        for ((name, r) <- unchanged) {
            var hints: (String, String) = null
            val out = ProvenanceAPIController.provenanceResponse(
                r,
                "run-1",
                " orders ",
                " t|x ",
                null,
                (p, _, t, _) => { hints = (p, t); provenanceOf("payroll", "t_hr") },
                failPipelines,
                failTaps,
                failReadTap
            )
            assert(hints == (("orders", "t|x")) && status(out) == 200 && out.getBody == gson.toJson(provenanceOf("payroll", "t_hr")), name)
        }
        assert(methodBody(source("datrisserver/src/main/scala/ai/datris/api/ProvenanceAPIController.scala"), "def provenance(").contains("provenanceResponse("))
    }

    test("finding 3d: activity signals are filtered for limited callers through signalsResponse, with the window rule kept") {
        import ActivitySignals.{FailingItem, PipelineVolume, Signals}
        def f(kind: String, name: String) =
            FailingItem(kind, name, None, "x", None, recovered = false, failureCount = 1, pipelineToken = None, relatedTapName = None)
        val s = Signals(
            86400000L,
            1800000000000L,
            List(f("pipeline", "own_p"), f("pipeline", "payroll"), f("tap", "t_own"), f("tap", "t_hr")),
            Nil,
            List(PipelineVolume("own_p", Some("HR"), 1L, 1L, 1, None), PipelineVolume("payroll", Some("HR"), 1L, 1L, 1, None))
        )
        var window = 0L
        val out = ActivityAPIController.signalsResponse(req(ownKey("metadata:read")), "7d", ms => { window = ms; s }, () => pipelines, () => taps)
        assert(window == 7L * 86400000L && status(out) == 200)
        assert(out.getBody.contains("own_p") && out.getBody.contains("t_own") && !out.getBody.contains("payroll") && !out.getBody.contains("t_hr"), out.getBody)
        for ((name, r) <- unchanged) {
            val o = ActivityAPIController.signalsResponse(r, null, _ => s, failPipelines, failTaps)
            assert(o.getBody == gson.toJson(s.toJson), name)
        }
        assert(status(ActivityAPIController.signalsResponse(req(editor), "1y", _ => fail("not computed"), failPipelines, failTaps)) == 400)
        assert(methodBody(source("datrisserver/src/main/scala/ai/datris/api/ActivityAPIController.scala"), "def signals(").contains("signalsResponse("))
    }

    // incidents
    private val t0 = Instant.parse("2026-11-18T10:00:00Z")
    private def incident(id: String, rt: String, name: String, evidence: JsonObject = new JsonObject()) =
        Incident(
            id = id,
            kind = rt + "_failure",
            resourceType = rt,
            resourceName = name,
            openedAt = t0,
            state = "diagnosing",
            trigger = new JsonObject(),
            steps = List(IncidentStep(t0, "open", "opened")),
            evidence = Some(evidence)
        )

    test("finding 3e: incident list, get and evidence responses filter and refuse through their seams") {
        val incs = List(
            incident("inc-1", "pipeline", "own_p"),
            incident("inc-2", "pipeline", "payroll"),
            incident("inc-3", "tap", "t_own"),
            incident("inc-4", "tap", "t_hr")
        )
        val r = req(ownKey("incident:read"), uri = "/api/v1/incidents")
        val listed = IncidentsAPIController.listedJson(r, incs, readPipeline, readTap).asScala.map(_.getAsJsonObject.get("id").getAsString).toList
        assert(listed == List("inc-1", "inc-3"), listed)
        for (i <- incs) {
            val g = IncidentsAPIController.getResponse(r, i.id, Some(i), readPipeline, readTap)
            val e = IncidentsAPIController.evidenceResponse(r, i.id, Some(i), readPipeline, readTap)
            if (listed.contains(i.id)) assert(status(g) == 200 && status(e) == 200, i.id)
            else for (x <- Seq(g, e)) assert(status(x) == 404 && x.getBody == "{\"error\":\"no incident " + i.id + "\"}", i.id + ": " + x.getBody)
        }
        // unknown id: the same 404 for everyone
        assert(IncidentsAPIController.getResponse(r, "inc-9", None, readPipeline, readTap).getBody == "{\"error\":\"no incident inc-9\"}")
        // unchanged callers: every incident, as stored, no reader called
        for ((name, rr) <- unchanged) {
            assert(IncidentsAPIController.listedJson(rr, incs, failReadPipeline, failReadTap).size == 4, name)
            val g = IncidentsAPIController.getResponse(rr, "inc-2", Some(incs(1)), failReadPipeline, failReadTap)
            assert(status(g) == 200 && g.getBody == gson.toJson(incs(1).toPublicJson), name)
        }
        val src = source("datrisserver/src/main/scala/ai/datris/api/IncidentsAPIController.scala")
        // limited-caller-read-leaks (pinned edit, D3): the list route reads through listedFor,
        // which lists through listedJson for a caller that is not limited (LimitedPagingSpec)
        assert(methodBody(src, "def list(").contains("listedFor("))
        assert(methodBody(src, "def get(").contains("getResponse("))
        assert(methodBody(src, "def evidence(").contains("evidenceResponse("))
    }

    // ================================================================== finding 7 (and 3a): incident evidence

    private def evidenceWith(downstream: Seq[String], datasets: Seq[(String, String)], extra: JsonObject => Unit = _ => ()): JsonObject = {
        val e = new JsonObject()
        val b = new JsonObject()
        val dp = new JsonArray(); downstream.foreach(dp.add); b.add("downstreamPipelines", dp)
        val dd = new JsonArray()
        datasets.foreach { case (k, c) => val o = new JsonObject(); o.addProperty("kind", k); o.addProperty("coords", c); dd.add(o) }
        b.add("downstreamDatasets", dd)
        val uc = new JsonArray(); uc.add("main.hr.payroll"); b.add("ucTables", uc)
        val cc = new JsonArray(); cc.add("finance-dashboard"); b.add("consumersFromContracts", cc)
        b.addProperty("lastDownstreamRunAt", "2026-11-18T09:00:00Z")
        val ag = new JsonArray(); ag.add("other-agent"); b.add("agentsThatQueriedLast7d", ag)
        b.addProperty("secretName", "hr_db_secret")
        e.add("blastRadius", b)
        Seq("baseline", "candidate", "real").foreach { k =>
            val p = new JsonObject(); p.addProperty("topK", "salary=90000"); e.add(k, p)
        }
        e.addProperty("verdict", "fail")
        extra(e)
        e
    }

    /** Sets `runId` on each row profile of `e`. */
    private def withRunIds(e: JsonObject, id: String): Unit =
        Seq("baseline", "candidate", "real").foreach(k => if (e.has(k)) e.getAsJsonObject(k).addProperty("runId", id))

    /** Run records: own_p's runs fed by t_own, payroll's by t_hr, orders' by an upload. */
    private val ownRuns: String => Option[RunLineage] = {
        case "run-own" => Some(RunLineage("run-own", "own_p", input = RunLineageInput("tap", tapName = "t_own")))
        case "run-payroll" => Some(RunLineage("run-payroll", "payroll", input = RunLineageInput("tap", tapName = "t_hr")))
        case "run-orders-hr" => Some(RunLineage("run-orders-hr", "orders", input = RunLineageInput("tap", tapName = "t_hr")))
        case "run-orders-upload" => Some(RunLineage("run-orders-upload", "orders", input = RunLineageInput("upload", filename = "o.csv")))
        case _ => None
    }

    private def kc(p: PipelineConfig): (String, String) = {
        val d = LineageService.datasets(p).head
        (d.kind, d.name.stripPrefix(d.kind + ":"))
    }

    test("finding 7 / 3a: a visible incident's evidence hides other pipelines' blast radius, other callers' labels and the secret name") {
        val e = evidenceWith(Seq("own_p", "payroll"), Seq(kc(ownP), kc(payroll)))
        val stored = e.toString
        val inc = incident("inc-1", "pipeline", "shared_a", e)
        val r = req(ownKey("incident:read"))
        val shown = IncidentsAPIController.shownIncident(r, inc, readPipeline, readTap)
        val b = shown.evidence.get.getAsJsonObject("blastRadius")
        assert(b.getAsJsonArray("downstreamPipelines").asScala.map(_.getAsString).toList == List("own_p", HiddenTapScrub.Placeholder), b)
        val dsets = b.getAsJsonArray("downstreamDatasets").asScala.map(_.getAsJsonObject.get("coords").getAsString).toList
        assert(dsets == List(kc(ownP)._2, HiddenTapScrub.Placeholder), dsets)
        assert(b.getAsJsonArray("ucTables").asScala.map(_.getAsString).toList == List(HiddenTapScrub.Placeholder))
        assert(b.getAsJsonArray("consumersFromContracts").asScala.map(_.getAsString).toList == List(HiddenTapScrub.Placeholder))
        // review round 2, lead ruling on finding 4: lastDownstreamRunAt goes when a downstream pipeline is hidden
        assert(!b.has("agentsThatQueriedLast7d") && !b.has("secretName") && !b.has("lastDownstreamRunAt"), b)
        // the incident's own pipeline is visible: its profiles stay
        assert(shown.evidence.get.has("baseline") && shown.evidence.get.has("real"))
        Seq("payroll", "payroll_secret_tbl", "main.hr.payroll", "other-agent", "hr_db_secret", "finance-dashboard").foreach(x =>
            assert(!gson.toJson(shown.toPublicJson).contains(x), x)
        )
        // nothing stored is altered
        assert(inc.evidence.get.toString == stored)
        // through the get route's seam, the same
        val body = IncidentsAPIController.getResponse(r, "inc-1", Some(inc), readPipeline, readTap).getBody
        assert(!body.contains("payroll") && !body.contains("hr_db_secret"), body)
        // a dataset no downstream pipeline writes cannot be attributed: the whole list is one [hidden] entry
        val unattributed = IncidentsAPIController.shownIncident(
            r,
            incident("inc-1", "pipeline", "shared_a", evidenceWith(Seq("own_p", "payroll"), Seq(kc(ownP), ("postgres", "datris.public.historical_t")))),
            readPipeline,
            readTap
        )
        val ud = unattributed.evidence.get.getAsJsonObject("blastRadius").getAsJsonArray("downstreamDatasets")
        assert(ud.size == 1 && ud.get(0).getAsJsonObject.get("coords").getAsString == HiddenTapScrub.Placeholder, ud)
        // no hidden downstream pipeline: the lists stay; labels and secret name still go
        val clean = IncidentsAPIController.shownIncident(
            r,
            incident("inc-1", "pipeline", "shared_a", evidenceWith(Seq("own_p"), Seq(kc(ownP), ("postgres", "x.y.z")))),
            readPipeline,
            readTap
        )
        val cb = clean.evidence.get.getAsJsonObject("blastRadius")
        assert(cb.getAsJsonArray("ucTables").get(0).getAsString == "main.hr.payroll" && cb.getAsJsonArray("downstreamDatasets").size == 2, cb)
        assert(!cb.has("agentsThatQueriedLast7d") && !cb.has("secretName"))
        // the list filter applies the same scrub
        val listed = IncidentsAPIController.visibleIncidents(r, List(inc), readPipeline, readTap)
        assert(listed.size == 1 && !gson.toJson(listed.head.toPublicJson).contains("payroll"))
        // unchanged callers: the incident itself
        for ((name, rr) <- unchanged) assert(IncidentsAPIController.shownIncident(rr, inc, failReadPipeline, failReadTap) eq inc, name)
    }

    test("finding 7: a tap incident whose target pipeline the caller cannot read loses the profiles, diff and checks computed from that pipeline's rows") {
        def withDiffAndChecks(e: JsonObject): Unit = {
            val d = new JsonObject()
            val rm = new JsonArray(); rm.add("salary"); d.add("columnsRemoved", rm)
            e.add("diff", d)
            val cs = new JsonArray()
            val c = new JsonObject(); c.addProperty("name", "maxRecordDeltaPct"); c.addProperty("passed", false); c.addProperty("observed", "-42.00");
            c.addProperty("limit", "25")
            cs.add(c); e.add("checks", cs)
        }
        val r = req(pipelineOnly("incident:read"))
        val hrTap = incident("inc-5", "tap", "t_hr", evidenceWith(Nil, Nil, withDiffAndChecks))
        val stored = hrTap.evidence.get.toString
        val shown = IncidentsAPIController.shownIncident(r, hrTap, readPipeline, readTap)
        Seq("baseline", "candidate", "real", "diff", "checks").foreach(k => assert(!shown.evidence.get.has(k), k))
        val body = gson.toJson(shown.toPublicJson)
        Seq("salary", "-42.00", "maxRecordDeltaPct").foreach(x => assert(!body.contains(x), x + ": " + body))
        assert(shown.evidence.get.get("verdict").getAsString == "fail")
        assert(hrTap.evidence.get.toString == stored, "nothing stored is altered")
        // a target no longer stored: the same
        val gone = TapConfig(name = "t_gone_target", description = "d", targetPipeline = "deleted_p", catalog = "HR", createdByKeyLabel = "someone")
        val goneShown = IncidentsAPIController.shownIncident(
            r,
            incident("inc-7", "tap", "t_gone_target", evidenceWith(Nil, Nil, withDiffAndChecks)),
            readPipeline,
            n => if (n == "t_gone_target") gone else readTap(n)
        )
        Seq("baseline", "candidate", "real", "diff", "checks").foreach(k => assert(!goneShown.evidence.get.has(k), k))
        // its own tap's target is readable: everything stays (review round 2: the profiles name the runs they
        // describe, and those runs are of own_p, fed by t_own)
        val ownTap = IncidentsAPIController.shownIncident(
            r,
            incident("inc-6", "tap", "t_own", evidenceWith(Nil, Nil, e => { withDiffAndChecks(e); withRunIds(e, "run-own") })),
            readPipeline,
            readTap,
            ownRuns
        )
        Seq("baseline", "candidate", "real", "diff", "checks").foreach(k => assert(ownTap.evidence.get.has(k), k))
        // a pipeline incident on a visible pipeline keeps them too
        val ownPipe =
            IncidentsAPIController.shownIncident(r, incident("inc-8", "pipeline", "own_p", evidenceWith(Nil, Nil, withDiffAndChecks)), readPipeline, readTap)
        Seq("baseline", "diff", "checks").foreach(k => assert(ownPipe.evidence.get.has(k), k))
    }

    // ================================================================== finding 5: search per resource

    private def qd(name: String, catalog: String, coll: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            destination = Destination(qdrant = QdrantConfig(coll, ChunkingConfig(), null, null, null))
        )
    private val searchConfigs = Seq(qd("orders", "Sales", "sales_docs"), qd("payroll", "HR", "hr_docs"))
    private def hit(id: Int, pairs: (String, Any)*): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("id", id); m.put("text", "chunk " + id)
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }

    test("finding 5: a key that reads every pipeline searches every container and keeps chunks with no writer; only tap stamps of hidden taps go") {
        for (
            rk <- Seq(
                key("taplim", "pipeline:read", "tap:run:owner=self", "search:vector"),
                key("both", "pipeline:read", "pipeline:read:catalog=Sales", "search:vector")
            )
        ) {
            assert(!CallerVisibility.limitedPipelines(rk) && CallerVisibility.limitedTaps(rk), rk.label)
            val r = req(rk, "POST", "/api/v1/search/qdrant")
            assert(SearchAPIController.containerVisible(
                request = r,
                store = "qdrant",
                container = "manual_docs",
                schema = None,
                find = (_, _, _) => fail("configs read")
            ))
            assert(SearchAPIController.searchWriters(r, "qdrant", "manual_docs", None, () => fail("configs read")) == Right(None), rk.label)
            val hits = new java.util.ArrayList[java.util.Map[String, Any]](Seq(
                hit(1, "text" -> "no writer"),
                hit(2, "source_pipeline" -> "payroll"),
                hit(3, "source_pipeline" -> "orders", "_datris_tap_run" -> "t_hr|t", "_datris_script_sha" -> "hr-sha", "_datris_source" -> "hr.internal")
            ).asJava)
            val out = SearchAPIController.hitsFor(r, hits, None, readTap, _ => None)
            assert(out.asScala.map(_.get("id")).toList == List(1, 2, 3), rk.label)
            HiddenTapScrub.StampTapColumns.foreach(c => assert(!out.get(2).containsKey(c), c))
        }
        // a key limited for pipelines keeps the namer rule: a container no config names is the 404
        val pl = req(salesKey("search:vector"), "POST", "/api/v1/search/qdrant")
        assert(SearchAPIController.searchWriters(pl, "qdrant", "manual_docs", None, () => searchConfigs).isLeft)
        assert(SearchAPIController.searchWriters(pl, "qdrant", "hr_docs", None, () => searchConfigs).isLeft)
        // a key limited for pipelines only (reads every tap) keeps readable tap stamps
        val po = req(pipelineOnly("search:vector"), "POST", "/api/v1/search/qdrant")
        val w = SearchAPIController.searchWriters(po, "qdrant", "own_docs", None, () => Seq(qd("own_p", "HR", "own_docs", owner = "own")))
        assert(w.isRight && w.right.get.isDefined)
        val kept = SearchAPIController.hitsFor(
            po,
            new java.util.ArrayList[java.util.Map[String, Any]](Seq(hit(1, "source_pipeline" -> "own_p", "_datris_tap_run" -> "t_own|t")).asJava),
            w.right.get,
            readTap,
            _ => None
        )
        assert(kept.get(0).containsKey("_datris_tap_run"))
        // unchanged callers
        for ((name, r) <- unchanged) {
            assert(SearchAPIController.searchWriters(r, "qdrant", "manual_docs", None, () => fail("read")) == Right(None), name)
            val hs = new java.util.ArrayList[java.util.Map[String, Any]](Seq(hit(1, "_datris_tap_run" -> "t_hr|t")).asJava)
            assert(SearchAPIController.hitsFor(r, hs, None, failReadTap, _ => fail("run")) eq hs, name)
        }
        assert(VectorSearchRegistry.forStore("qdrant").isDefined)
    }

    // ================================================================== finding 6: inert capability without catalog= / owner=

    test("finding 6: an inert pipeline capability with no catalog= or owner= (pipeline:read:collection=docs) does not make a key limited, in both modes") {
        for (allow <- Seq(false, true)) withInertMode(allow) {
            val (applied, inert) = CapabilityScopes.partition(Capability.parseList(Seq("job:read", "metadata:read", "pipeline:read:collection=docs")))
            assert(inert.nonEmpty, "fixture: the collection scope is not applied on pipeline:read")
            val rk = ResolvedKey(None, "coll", applied, isLegacyFullAccess = false, inertCapabilities = inert)
            assert(!CallerVisibility.limitedPipelines(rk) && !CallerVisibility.limitedTaps(rk), "allow=" + allow)
            val r = req(rk)
            val g = graph
            assert(LineageAPIController.visibleGraph(r, g) eq g, "allow=" + allow)
            val data = statusResponse("payroll", "tap-t_hr.csv")
            assert(PipelineStatusAPIController.restrictedViewWith(r, data, pipelines, taps).toOption.get eq data, "allow=" + allow)
        }
    }

    // ================================================================== review round 2

    test("round 2, finding 4: a profile keeps scriptSha and tapVersion only when its feeding tap is established and readable") {
        def prof(e: JsonObject, id: String): Unit =
            Seq("baseline", "candidate", "real").foreach { k =>
                val p = e.getAsJsonObject(k); p.addProperty("runId", id); p.addProperty("scriptSha", "hr-sha"); p.addProperty("tapVersion", 7)
            }
        // key reading every pipeline, only Sales taps: orders is fed by the HR tap t_hr
        val r = req(key("k", "pipeline:read", "tap:read:catalog=Sales", "incident:read"))
        val hrFed = IncidentsAPIController.shownIncident(
            r,
            incident("inc-1", "pipeline", "orders", evidenceWith(Nil, Nil, prof(_, "run-orders-hr"))),
            readPipeline,
            readTap,
            ownRuns
        )
        Seq("baseline", "candidate", "real").foreach { k =>
            val p = hrFed.evidence.get.getAsJsonObject(k)
            HiddenTapScrub.RunProfileTapFields.foreach(f => assert(!p.has(f), k + "." + f))
            assert(p.has("topK") && p.get("runId").getAsString == "run-orders-hr", "the profile itself stays on a visible pipeline incident")
        }
        assert(!gson.toJson(hrFed.toPublicJson).contains("hr-sha"))
        // an upload run has no feeding tap: kept
        val upload = IncidentsAPIController.shownIncident(
            r,
            incident("inc-1", "pipeline", "orders", evidenceWith(Nil, Nil, prof(_, "run-orders-upload"))),
            readPipeline,
            readTap,
            ownRuns
        )
        assert(upload.evidence.get.getAsJsonObject("baseline").has("scriptSha"))
        // a run that cannot be established: removed (fail closed)
        val unknown = IncidentsAPIController.shownIncident(
            r,
            incident("inc-1", "pipeline", "orders", evidenceWith(Nil, Nil, prof(_, "run-missing"))),
            readPipeline,
            readTap,
            ownRuns
        )
        assert(!unknown.evidence.get.getAsJsonObject("baseline").has("scriptSha"))
        // the owner=self key's own pipeline fed by its own tap keeps them
        val own = IncidentsAPIController.shownIncident(
            req(ownKey("incident:read")),
            incident("inc-1", "pipeline", "own_p", evidenceWith(Nil, Nil, prof(_, "run-own"))),
            readPipeline,
            readTap,
            ownRuns
        )
        assert(own.evidence.get.getAsJsonObject("real").get("scriptSha").getAsString == "hr-sha")
        // unchanged callers: as stored, no reader called
        for ((name, rr) <- unchanged) {
            val i = incident("inc-1", "pipeline", "orders", evidenceWith(Nil, Nil, prof(_, "run-orders-hr")))
            assert(IncidentsAPIController.shownIncident(rr, i, failReadPipeline, failReadTap, _ => fail("run read")) eq i, name)
        }
    }

    test("round 2, finding 4: lastDownstreamRunAt goes when a downstream pipeline is hidden and stays otherwise") {
        val r = req(ownKey("incident:read"))
        val hidden =
            IncidentsAPIController.shownIncident(r, incident("inc-1", "pipeline", "own_p", evidenceWith(Seq("own_p", "payroll"), Nil)), readPipeline, readTap)
        assert(!hidden.evidence.get.getAsJsonObject("blastRadius").has("lastDownstreamRunAt"))
        val visible = IncidentsAPIController.shownIncident(r, incident("inc-1", "pipeline", "own_p", evidenceWith(Seq("own_p"), Nil)), readPipeline, readTap)
        assert(visible.evidence.get.getAsJsonObject("blastRadius").get("lastDownstreamRunAt").getAsString == "2026-11-18T09:00:00Z")
    }

    test("round 2, finding 4 note: a tap incident's profiles are judged on the pipeline their run describes, not the tap's current target") {
        val r = req(pipelineOnly("incident:read"))
        // t_own targets own_p today, but the profiles describe payroll's runs (it was retargeted since): removed
        val retargeted = IncidentsAPIController.shownIncident(
            r,
            incident("inc-6", "tap", "t_own", evidenceWith(Nil, Nil, e => withRunIds(e, "run-payroll"))),
            readPipeline,
            readTap,
            ownRuns
        )
        Seq("baseline", "candidate", "real").foreach(k => assert(!retargeted.evidence.get.has(k), k))
        // t_hr targets payroll today, but the profiles describe own_p's runs: kept
        val fromOwn = IncidentsAPIController.shownIncident(
            r,
            incident("inc-5", "tap", "t_hr", evidenceWith(Nil, Nil, e => withRunIds(e, "run-own"))),
            readPipeline,
            readTap,
            ownRuns
        )
        Seq("baseline", "candidate", "real").foreach(k => assert(fromOwn.evidence.get.has(k), k))
        // the tap's own last run (`tap:<name>@<time>`) describes the tap: kept
        val tapRun = IncidentsAPIController.shownIncident(
            r,
            incident("inc-5", "tap", "t_hr", evidenceWith(Nil, Nil, e => withRunIds(e, "tap:t_hr@2026-11-18T09:00:00Z"))),
            readPipeline,
            readTap,
            ownRuns
        )
        assert(tapRun.evidence.get.has("real"))
    }

    private def pending(incidentId: Option[String]): ai.datris.policy.PendingAction = {
        val now = Instant.parse("2026-11-18T10:00:00Z")
        ai.datris.policy.PendingAction(
            id = "pa_1",
            action = "tap:run",
            resourceType = Some("tap"),
            resourceName = Some("t_sales"),
            resourceVersion = None,
            actor = ai.datris.audit.AuditActorInfo("api-key", "sales"),
            reason = None,
            agentSession = None,
            incidentId = incidentId,
            method = "POST",
            path = "/api/v1/tap/run",
            query = None,
            contentType = None,
            body = Some("{}"),
            bodyHash = "h",
            createdAt = now,
            expiresAt = now.plusSeconds(3600),
            state = "pending"
        )
    }

    test("round 2, finding 1: an approval carries an incident's evidence only for a caller that may see the incident, scrubbed") {
        val hrInc = incident("inc-9", "pipeline", "payroll", evidenceWith(Seq("payroll"), Seq(kc(payroll))))
        val load: String => Option[Incident] = { case "inc-9" => Some(hrInc); case _ => None }
        val withHr = pending(Some("inc-9"))
        // the shape of an approval whose incident does not exist
        val absent = ApprovalsAPIController.withIncidentEvidence(req(editor), pending(Some("inc-none")), load, failReadPipeline, failReadTap, _ => None)
        assert(!absent.has("incidentEvidence"))
        for (rk <- Seq(salesKey("approval:read"), key("k", "pipeline:read:catalog=Sales", "tap:run:catalog=Sales", "approval:read"), bob)) {
            val got = ApprovalsAPIController.withIncidentEvidence(req(rk, uri = "/api/v1/approvals/pa_1"), withHr, load, readPipeline, readTap, ownRuns)
            assert(!got.has("incidentEvidence"), rk.label + ": " + got)
            assert(got.toString == absent.toString.replace("inc-none", "inc-9"), rk.label + ": same shape as an approval whose incident does not exist")
        }
        // a caller that may see it gets the scrubbed view
        val ownInc = incident("inc-8", "pipeline", "own_p", evidenceWith(Seq("own_p", "payroll"), Seq(kc(ownP), kc(payroll))))
        val ownGot = ApprovalsAPIController.withIncidentEvidence(
            req(ownKey("approval:read")),
            pending(Some("inc-8")),
            { case "inc-8" => Some(ownInc); case _ => None },
            readPipeline,
            readTap,
            ownRuns
        )
        assert(ownGot.has("incidentEvidence"))
        val b = ownGot.getAsJsonObject("incidentEvidence").getAsJsonObject("blastRadius")
        assert(!b.has("secretName") && !b.has("agentsThatQueriedLast7d") && !ownGot.toString.contains("payroll"), ownGot)
        // unchanged callers: the stored evidence as before, no store reader but the incident load
        for ((name, r) <- unchanged) {
            var loads = 0
            val got =
                ApprovalsAPIController.withIncidentEvidence(r, withHr, id => { loads += 1; load(id) }, failReadPipeline, failReadTap, _ => fail("run read"))
            assert(loads == 1 && got.get("incidentEvidence").toString == hrInc.evidence.get.toString, name)
        }
        // no incident on the approval: nothing loaded
        assert(!ApprovalsAPIController.withIncidentEvidence(req(bob), pending(None), _ => fail("loaded"), failReadPipeline, failReadTap, _ => None).has(
            "incidentEvidence"
        ))
        // wiring: list and get build approvals through the seam
        val src = source("datrisserver/src/main/scala/ai/datris/api/ApprovalsAPIController.scala")
        assert(methodBody(src, "def list(").contains("ApprovalsAPIController.withIncidentEvidence(request"))
        assert(methodBody(src, "def get(").contains("ApprovalsAPIController.withIncidentEvidence(request"))
    }

    private def sharedFixture: (Seq[PipelineConfig], LineageService.Graph) = {
        val ownHr = pg("own_hr", "HR", "own_hr_t", owner = "own")
        val ownSales = pg("own_sales", "Sales", "shared_t", owner = "own")
        val hrHidden = pg("hr_hidden", "HR", "shared_t")
        val salesOrders = pg("sales_orders", "Sales", "shared2_t")
        val hrShared2 = pg("hr_shared2", "HR", "shared2_t")
        val ps = Seq(ownHr, ownSales, hrHidden, salesOrders, hrShared2)
        val oldBoth = "dataset:postgres:datris.public.old_both"
        val g = LineageService.build(
            List(tap("t_hr_feed2", "HR", "hr_hidden", "hrsrc.api")),
            ps.toList,
            List(LineageService.ObservedDataset("own_sales", oldBoth), LineageService.ObservedDataset("hr_hidden", oldBoth)),
            Map.empty
        )
        (ps, g)
    }

    test("round 2, finding 2: a key's kept shared dataset says nothing about a hidden writer (catalog edge, catalog, authority); no isolated catalog") {
        val (ps, g) = sharedFixture
        def dsOf(name: String) = LineageService.datasets(ps.find(_.name == name).get).head.id
        val shared = dsOf("own_sales")
        val shared2 = dsOf("sales_orders")
        val oldBoth = "dataset:postgres:datris.public.old_both"
        // (a) owner=self: own_hr keeps catalog:HR; the shared datasets are written in HR only by hidden pipelines
        val own = LineageAPIController.visibleGraph(req(ownKey("metadata:read")), g)
        assert(ids(own).contains(shared) && ids(own).contains(oldBoth), ids(own))
        for (d <- Seq(shared, oldBoth)) {
            assert(!own.edges.exists(e => e.from == d && e.to == "catalog:HR"), d + " " + own.edges)
            assert(own.edges.exists(e => e.from == d && e.to == "catalog:Sales"), d)
        }
        // every dataset -> catalog:X edge left has a kept pipeline of X writing that dataset
        def pinned(v: LineageService.Graph): Unit = {
            val byId = v.nodes.map(n => n.id -> n).toMap
            v.edges.filter(e => e.to.startsWith("catalog:")).foreach { e =>
                val x = e.to.stripPrefix("catalog:")
                assert(v.edges.exists(w => w.to == e.from && byId.get(w.from).exists(n => n.nodeType == "pipeline" && n.catalog.contains(x))), e)
            }
            v.nodes.filter(_.nodeType == "catalog").foreach(c => assert(v.edges.exists(e => e.from == c.id || e.to == c.id), "isolated " + c.id))
        }
        pinned(own)
        // (b) catalog=Sales key: the shared dataset's catalog comes from its kept Sales writer and authority is omitted
        val sales = LineageAPIController.visibleGraph(req(salesKey("metadata:read")), g)
        pinned(sales)
        val node = sales.nodes.find(_.id == shared2).get
        assert(node.catalog.contains("Sales") && node.authority.isEmpty, node)
        assert(!node.toJson.toString.contains("HR") && !node.toJson.toString.contains("undeclared"), node.toJson)
        // a key whose catalog:HR is kept through HR taps only: no isolated catalog node is left
        val taps = LineageAPIController.visibleGraph(req(salesPlusTaps("metadata:read")), g)
        pinned(taps)
        assert(!ids(taps).contains("catalog:HR"), ids(taps))
        assert(ids(taps).contains("tap:t_hr_feed2"))
        // restricted sessions keep exactly today's graph (catalog anchors, the node as built)
        val sess = LineageAPIController.visibleGraph(req(bob), g)
        val expected = LineageService.visibleTo(
            g,
            n =>
                n.nodeType match {
                    case "pipeline" | "tap" => n.catalog.contains("Sales")
                    case "catalog" => n.name == "Sales"
                    case _ => true
                }
        )
        assert(sess == expected, sess)
        // unchanged callers: the same instance
        for ((name, r) <- unchanged) assert(LineageAPIController.visibleGraph(r, g) eq g, name)
    }

    test("round 2, finding 3: restrictedView's gate filters a scoped key through injected readers; unchanged callers get the same instance unread") {
        val data = java.util.Arrays.asList(
            PipelineStatusSummary("t", 0L, 0L, "orders", "tok-o", "proc", "s", "e", "1s", "success"),
            PipelineStatusSummary("t", 0L, 0L, "payroll", "tok-p", "proc", "s", "e", "1s", "success")
        )
        for (rk <- Seq(salesKey("job:read"), ownKey("job:read"), bob)) {
            var reads = 0
            val got = PipelineStatusAPIController.restrictedViewFrom(
                req(rk, uri = "/api/v1/pipeline/status"),
                data,
                () => { reads += 1; pipelines },
                () => { reads += 1; taps },
                _ => None
            )
            val names = got.toOption.get.asInstanceOf[java.util.List[PipelineStatusSummary]].asScala.map(_.pipeline).toSet
            assert(!names.contains("payroll") && reads == 2, rk.label + " " + names)
        }
        for ((name, r) <- unchanged) {
            assert(PipelineStatusAPIController.restrictedViewFrom(r, data, failPipelines, failTaps, _ => fail("run")).toOption.get eq data, name)
        }
        val status = source("datrisserver/src/main/scala/ai/datris/api/PipelineStatusAPIController.scala")
        assert(methodBody(status, "def restrictedView(").contains("restrictedViewFrom("))
        assert(methodBody(status, "def getPipelineStatus(").contains("PipelineStatusAPIController.restrictedView(request, data)"))
        // lineage: the whole graph and the neighbourhood walk use the caller's graph
        val lineage = source("datrisserver/src/main/scala/ai/datris/api/LineageAPIController.scala")
        val whole = methodBody(lineage, "def wholeGraph(")
        assert(whole.contains("LineageAPIController.callerGraph(request)") && !whole.contains("LineageService.graph()"), whole)
        val nb = methodBody(lineage, "def neighborhood(")
        assert(nb.contains("() => LineageAPIController.callerGraph(request)") && !nb.contains("LineageService.graph()"), nb)
        assert(methodBody(lineage, "def callerGraph(").contains("callerGraphOf(request, LineageService.graph()"))
    }

    // ================================================================== helpers

    private def source(rel: String): String = {
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get(rel.stripPrefix("datrisserver/")))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
    }

    private def methodBody(src: String, signature: String): String = {
        val i = src.indexOf(signature)
        assert(i >= 0, signature)
        val j = src.indexOf("\n    def ", i + signature.length)
        val k = src.indexOf("\n    @", i + signature.length)
        val end = Seq(j, k).filter(_ >= 0).reduceOption(_ min _).getOrElse(src.length)
        src.substring(i, end)
    }
}
