package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.{AuditEntry, AuditLog}
import ai.datris.auth.{CallerVisibility, CapabilityScopes, ChatIdentity, HiddenTapScrub, ScopeDenialFixtures, ScopeDenialWording}
import ai.datris.config.CapabilityInterceptor
import ai.datris.incident.{Incident, IncidentStep}
import ai.datris.model._
import ai.datris.util.{ActivitySignals, APIKeyValidator, InMemoryUserDb, LineageService, ObjectStoreQueryUtil, OpsAgentPrompt, VectorSearchRegistry}
import ai.datris.util.InMemoryUserDb.grants
import ai.datris.util.ScratchReader.ScratchResultPage
import com.google.gson.{Gson, JsonArray, JsonElement, JsonObject, JsonParser}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.{doAnswer, mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.springframework.http.ResponseEntity

import java.time.Instant
import scala.collection.JavaConverters._

/** Story: Scoped API keys, `catalog=` / `owner=self` scope holds on every
  * route (plans/stories/scoped-key-read-routes.md), Part B (records),
  * Acceptance `ScopedKeyRecordVisibilitySpec`, Design 2-3, Decisions D1, D2,
  * D4.
  *
  * A caller is limited per resource (`CallerVisibility.limitedPipelines` /
  * `limitedTaps`, D1): a restricted session always; a key carrying `catalog=`
  * or `owner=` on a pipeline or tap capability (inert ones included, both
  * modes of `DATRIS_KEY_INERT_SCOPES`) without unscoped read on the resource.
  * Every record gate that today asks `isRestrictedSession` asks that instead,
  * and the session filters run unchanged underneath. Named items outside scope
  * are 403 for a key (shared wording, [[ScopeDenialWording]]); lookups by
  * token, run id, container or lineage node answer the session's existing 404
  * (D2). Unchanged callers (unrestricted sessions, `*:*`, legacy and template
  * keys, a `job:read` monitor key, no identity, a null request) get the
  * argument back as the same instance and no reader is called.
  *
  * Every entry point reads Mongo, the vector store or the incident store, so
  * this spec pins the pure seams each one runs on, with every reader injected.
  *
  * Existing seams whose answer changes for a limited key:
  * {{{
  * PipelineStatusAPIController.restrictedView / restrictedViewWith / resultHidden / scrubResultPage
  * HiddenTapScrub.tapHidden / hiddenFeedingTaps
  * QueryAPIController.restrictedRows / restrictedObjectStoreResult
  * SearchAPIController.containerVisible / searchWriters
  * LineageAPIController.visibleGraph / recordsShown / scrubNeighborhood / columnsRefusal (key: 403)
  * ProvenanceAPIController.provenanceRefusal / scrubProvenance
  * CatalogFindAPIController.recordsShown
  * }}}
  * Pinned seams (do not exist at 614cf46e; the implementation adds them):
  * {{{
  * object TapAPIController {
  *   // GET /tap/logs/all: the run-log values (each `{tapName, ...}`) a caller sees. A limited caller:
  *   // one readAll, only logs whose tapName names a tap it may read (null / missing tapName: hidden).
  *   // Everyone else: `logs` itself, readAll not called.
  *   def visibleTapLogs(request: HttpServletRequest, logs: Seq[JsonElement], readAll: () => Seq[TapConfig]): Seq[JsonElement]
  * }
  * object QueryAPIController {
  *   // restrictedPipelineRefusal with the pipeline read given (the 2-arity form reads PipelineConfigIO).
  *   // Restricted session: 404 pipelineNotFound(name) as today; limited key outside scope: 403
  *   // capability-denied (pipeline:read, shared wording). None otherwise; read not called for the unchanged.
  *   private[api] def restrictedPipelineRefusal(request: HttpServletRequest, pipelineName: String,
  *                                              read: String => PipelineConfig): Option[ResponseEntity[String]]
  * }
  * object ActivityAPIController {
  *   // GET /activity/signals: restrictedView's filter on the given stores for a limited caller
  *   // (CapabilityCheck.isLimited); `s` itself for everyone else, neither reader called.
  *   def callerView(request: HttpServletRequest, s: ActivitySignals.Signals,
  *                  pipelines: () => Seq[PipelineConfig], taps: () => Seq[TapConfig]): ActivitySignals.Signals
  * }
  * object IncidentsAPIController {
  *   // GET /incidents: a limited caller sees only incidents whose resource (resourceType tap|pipeline,
  *   // resourceName) it may read; a resource no longer stored is hidden. `incidents` itself for everyone
  *   // else, neither reader called.
  *   def visibleIncidents(request: HttpServletRequest, incidents: List[Incident],
  *                        readPipeline: String => PipelineConfig, readTap: String => TapConfig): List[Incident]
  *   // GET /incidents/{id}, /{id}/evidence (and, OPEN QUESTION reading, POST proceed / abandon for a
  *   // restricted session): Some(the 404 `{"error":"no incident <id>"}` the route gives an unknown id)
  *   // when `incident` is hidden from a limited caller (D2: no oracle); None otherwise (a missing
  *   // incident included: the route answers its own 404).
  *   def incidentRefusal(request: HttpServletRequest, id: String, incident: Option[Incident],
  *                       readPipeline: String => PipelineConfig, readTap: String => TapConfig): Option[ResponseEntity[String]]
  * }
  * object OpsChatAPIController {
  *   // the Ops chat incident block, filtered as visibleIncidents for a limited key holding incident:read;
  *   // the existing 2-arity form keeps its answer for everyone it serves today.
  *   def incidentsFor(identity: ChatIdentity, load: () => List[Incident],
  *                    readPipeline: String => PipelineConfig, readTap: String => TapConfig): List[Incident]
  * }
  * }}}
  */
class ScopedKeyRecordVisibilitySpec extends AnyFunSuite with BeforeAndAfterEach {

    // ================================================================== callers

    private def key(label: String, caps: String*): ResolvedKey = ScopeDenialFixtures.key(label, caps: _*)
    private def req(rk: ResolvedKey, method: String = "GET", uri: String = "/api/v1/pipeline/status"): HttpServletRequest =
        if (rk == null) ScopeDenialFixtures.request(null, method, uri)._1 else ScopeDenialFixtures.request(rk, method, uri)._1

    private def restricted(g: (String, String)*): ResolvedKey =
        APIKeyValidator.resolveFromSession(User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants(g: _*)))
    private val bob: ResolvedKey = restricted("Sales" -> "view")
    private val editor: ResolvedKey = APIKeyValidator.resolveFromSession(User("ed", "h", User.RoleEditor, null, null, null))
    private val legacy: ResolvedKey = ResolvedKey(None, "legacy", Seq(Capability.FullAccess), isLegacyFullAccess = true)
    private val wildcard: ResolvedKey = key("full", "*:*")

    private val SalesRead = Seq("pipeline:read:catalog=Sales", "tap:read:catalog=Sales")
    private val OwnRead = Seq("pipeline:read:owner=self", "tap:read:owner=self")

    /** A limited caller, the pipelines and taps (of the fixtures below) it may read. */
    private case class Shape(name: String, rk: ResolvedKey, caps: Seq[String], pipelines: Set[String], taps: Set[String])

    /** The key shapes Part A showed matter, each holding the route capability `route`. */
    private def shapes(route: String*): Seq[Shape] = {
        def s(name: String, label: String, caps: Seq[String], p: Set[String], t: Set[String]) = Shape(name, key(label, caps: _*), caps, p, t)
        Seq(
            s("catalog=Sales", "sales", SalesRead ++ route, Set("orders"), Set("t_sales")),
            s("owner=self", "own", OwnRead ++ route, Set("own_p"), Set("t_own")),
            // an unscoped route capability with a scoped pipeline read and no tap read: limited for both
            s("unscoped route + scoped read", "action", route :+ "pipeline:read:catalog=Sales", Set("orders"), Set.empty),
            // limited for taps only: every pipeline, no tap
            s("tap-limited only", "taplim", Seq("pipeline:read", "tap:run:owner=self") ++ route, AllPipelines, Set.empty),
            // limited for pipelines only: its own pipeline, every tap
            s("pipeline-limited only", "own", Seq("tap:read", "pipeline:read:owner=self") ++ route, Set("own_p"), AllTaps)
        )
    }

    private def sessionShape: Shape = Shape("restricted session", bob, Nil, Set("orders"), Set("t_sales"))

    /** The limited callers of a family: every key shape and a restricted session. */
    private def limited(route: String*): Seq[Shape] = shapes(route: _*) :+ sessionShape

    /** A key whose only pipeline capability is inert (`collection=` is not applied), in both modes. */
    private def inertKey(route: String*): ResolvedKey = {
        val (applied, inert) = CapabilityScopes.partition(Capability.parseList(route :+ "pipeline:read:catalog=Sales,collection=docs"))
        ResolvedKey(None, "ik", applied, isLegacyFullAccess = false, inertCapabilities = inert)
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

    /** Callers the story leaves unchanged, with the route capability `route`
      * on the monitor key. A null request and a request with no identity
      * (auth off) included. */
    private def unchanged(route: String*): Seq[(String, HttpServletRequest)] =
        Seq(
            "unrestricted editor" -> req(editor),
            "*:* key" -> req(wildcard),
            "legacy key" -> req(legacy),
            "job:read monitor key" -> req(key("monitor", ("job:read" +: route): _*)),
            "no identity" -> req(null),
            "null request" -> null
        ) ++ Seq("read-only", "reporting", "ops", "rag-builder").map(t => (t + " template") -> req(key(t, KeyTemplatesForSpecs.capabilities(t): _*)))

    // ================================================================== stored items

    private def pg(name: String, catalog: String, owner: String = "someone", d: Destination = null): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog, createdByKeyLabel = owner, destination = d)
    private def tap(name: String, catalog: String, target: String, owner: String = "someone"): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = target, catalog = catalog, createdByKeyLabel = owner)

    private val pgOrders = Destination(schemaProperties = null, database = Database(dbName = "datris", schema = "public", table = "orders", usePostgres = true))
    private val pgPayroll =
        Destination(schemaProperties = null, database = Database(dbName = "datris", schema = "public", table = "payroll", usePostgres = true))
    private val pgOwn = Destination(schemaProperties = null, database = Database(dbName = "datris", schema = "public", table = "own_t", usePostgres = true))

    private val orders = pg("orders", "Sales", d = pgOrders)
    private val payroll = pg("payroll", "HR", d = pgPayroll)
    private val ownP = pg("own_p", "HR", owner = "own", d = pgOwn)
    private val pipelines: Seq[PipelineConfig] = Seq(orders, payroll, ownP)
    private lazy val AllPipelines: Set[String] = Set("orders", "payroll", "own_p")
    private val readPipeline: String => PipelineConfig = n => pipelines.find(_.name == n).orNull

    private val tSales = tap("t_sales", "Sales", "orders")
    private val tHr = tap("t_hr_feed", "HR", "orders")
    private val tOwn = tap("t_own", "HR", "own_p", owner = "own")
    private val taps: Seq[TapConfig] = Seq(tSales, tHr, tOwn)
    private lazy val AllTaps: Set[String] = Set("t_sales", "t_hr_feed", "t_own")
    private val readTap: String => TapConfig = n => taps.find(_.name == n).orNull

    private val failRead: String => PipelineConfig = n => fail("the pipeline store must not be read for this caller: " + n)
    private val failReadAll: () => Seq[PipelineConfig] = () => fail("the pipeline store must not be read for this caller")
    private val failReadTap: String => TapConfig = n => fail("the tap store must not be read for this caller: " + n)
    private val failReadTaps: () => Seq[TapConfig] = () => fail("the tap store must not be read for this caller")
    private val failRun: String => Option[RunLineage] = id => fail("the run-lineage store must not be read for this caller: " + id)
    private val failDescriptions: () => Seq[String] = () => fail("job descriptions must not be read for this caller")
    private val failFind: (String, String, Option[String]) => Seq[PipelineConfig] = (_, _, _) => fail("configs must not be read for this caller")

    private val gson = new Gson

    private def status(r: ResponseEntity[String]): Int = r.getStatusCode.value

    /** A key's 403: the shared body for (resource, action), naming none of `hidden`. */
    private def assertKeyDenied(r: Option[ResponseEntity[String]], s: Shape, resource: String, action: String, hidden: Seq[String]): Unit = {
        assert(r.isDefined, s.name + ": expected a 403")
        assert(status(r.get) == 403, s.name + ": " + r.get.getBody)
        ScopeDenialWording.assertBody(r.get.getBody, ScopeDenialWording.message(s.rk.label, resource, action, s.caps), hidden)
    }

    private var savedEnv: DatrisEnvironment = _
    private val captured = scala.collection.mutable.ArrayBuffer[AuditEntry]()

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

    /** Runs `f` with no environment at all, so any store read fails at once. */
    private def withNoEnvironment[T](f: => T): T = {
        val e = DatrisEnvironment.values
        TenantContext.clear()
        DatrisEnvironment.values = null
        try f
        finally {
            DatrisEnvironment.values = e
            if (e != null) TenantContext.set(e)
        }
    }

    // ================================================================== payload fixtures

    private def summary(p: String) = PipelineStatusSummary("t", 0L, 0L, p, "tok-" + p, "proc", "s", "e", "1s", "success")
    private def event(p: String, filename: String = "f", description: String = "") =
        PipelineStatus(0, "t", p, "proc", "pub", "tok-" + p, filename, "end", "ok", description, 0L)
    private def summaries: java.util.List[PipelineStatusSummary] = java.util.Arrays.asList(summary("orders"), summary("payroll"), summary("own_p"))

    private def summaryNames(r: Either[ResponseEntity[String], AnyRef]): Set[String] = r match {
        case Right(l: java.util.List[_]) => l.asScala.map(_.asInstanceOf[PipelineStatusSummary].pipeline).toSet
        case other => fail("summary must be filtered, got " + other)
    }

    private def statusResponse(p: String, feedFile: String) = PipelineStatusResponse(
        PipelineStatusRollup(
            allDone = true,
            status = "error",
            jobs =
                List(PipelineJobRollup("tok-" + p, p, feedFile, "error", "s", "l", "1s", PipelineJobError("p", "row from " + feedFile + " failed", "x"))).asJava
        ),
        List(event(p, feedFile, "Processing " + feedFile + " into public." + p)).asJava
    )

    private def stampedRows: JsonArray =
        JsonParser.parseString(
            """[{"id":1,"_datris_run_id":"tok","_datris_tap_run":"t_hr_feed|t","_datris_script_sha":"hr-sha","_datris_source":"hr.internal"},
              |{"id":2,"_datris_run_id":"tok","_datris_tap_run":"t_sales|t","_datris_script_sha":"sales-sha","_datris_source":"tap:t_sales"}]""".stripMargin
        ).getAsJsonArray

    private def resultPage = ScratchResultPage(stampedRows, 2L, 2, 0L, truncated = false, "u", "e")

    private def row(id: Int): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("id", id)
        m.put("_datris_tap_run", "t_hr_feed|2026-11-16T10:00:00Z")
        m.put("_datris_run_id", "run-" + id)
        m.put("_datris_script_sha", "hr-sha")
        m.put("_datris_source", "hr.internal")
        m.put("amount", id * 10)
        m
    }
    private def rows(n: Int): java.util.List[java.util.Map[String, Any]] = {
        val l = new java.util.ArrayList[java.util.Map[String, Any]]()
        (1 to n).foreach(i => l.add(row(i)))
        l
    }
    private def osResult(n: Int) = ObjectStoreQueryUtil.QueryResult(
        new java.util.ArrayList[String](Seq("id", "_datris_tap_run", "_datris_run_id", "_datris_script_sha", "_datris_source", "amount").asJava),
        rows(n),
        "s3://datris/orders/",
        "parquet"
    )

    private def logValue(tapName: Option[String]): JsonElement = {
        val o = new JsonObject()
        tapName.foreach(o.addProperty("tapName", _))
        o.addProperty("status", "success")
        o
    }
    private def tapLogs: Seq[JsonElement] =
        Seq(logValue(Some("t_sales")), logValue(Some("t_hr_feed")), logValue(Some("t_own")), logValue(Some("gone")), logValue(None))
    private def logNames(ls: Seq[JsonElement]): Set[String] =
        ls.map(_.getAsJsonObject).filter(_.has("tapName")).map(_.get("tapName").getAsString).toSet

    // search
    private def qd(name: String, catalog: String, coll: String, owner: String = "someone"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = catalog,
            createdByKeyLabel = owner,
            destination = Destination(qdrant = QdrantConfig(coll, ChunkingConfig(), null, null, null))
        )
    private val searchConfigs = Seq(
        qd("orders", "Sales", "sales_docs"),
        qd("payroll", "HR", "hr_docs"),
        qd("own_p", "HR", "own_docs", owner = "own"),
        qd("mix_a", "Sales", "shared_docs"),
        qd("mix_b", "HR", "shared_docs")
    )
    private def hit(id: Int, pairs: (String, Any)*): java.util.Map[String, Any] = {
        val m = new java.util.LinkedHashMap[String, Any]()
        m.put("id", id)
        m.put("text", "chunk " + id)
        m.put("score", 0.9)
        pairs.foreach { case (k, v) => m.put(k, v) }
        m
    }
    private def hitList(hs: java.util.Map[String, Any]*): java.util.List[java.util.Map[String, Any]] = {
        val l = new java.util.ArrayList[java.util.Map[String, Any]]()
        hs.foreach(l.add)
        l
    }
    private def storeHits = hitList(
        hit(1, "source_pipeline" -> "orders"),
        hit(2, "source_pipeline" -> "payroll", "text" -> "hr salary chunk"),
        hit(3, "source_pipeline" -> "own_p"),
        hit(4, "source_pipeline" -> "orders", "_datris_tap_run" -> "t_hr_feed|t", "_datris_script_sha" -> "hr-sha", "_datris_source" -> "hr.internal")
    )
    private def ids(l: java.util.List[java.util.Map[String, Any]]): Seq[Any] = l.asScala.map(_.get("id")).toList

    // lineage
    private def lineageGraph: LineageService.Graph = LineageService.build(taps.toList, pipelines.toList)

    private def runJson(id: String, tapName: String): JsonObject =
        JsonParser.parseString(
            s"""{"runId":"$id","pipeline":"orders","input":{"kind":"tap","tapName":"$tapName","tapRunTime":"t","scriptSha":"abc",
               |"source":"tap:$tapName","filename":"tap-$tapName.csv"},"outputs":[]}""".stripMargin
        ).getAsJsonObject
    private def neighborhood: JsonObject = {
        val o = new JsonObject()
        o.addProperty("node", "pipeline:orders")
        val runs = new JsonArray()
        runs.add(runJson("r1", "t_hr_feed"))
        runs.add(runJson("r2", "t_sales"))
        o.add("runs", runs)
        o
    }
    private def provenance(tapName: String, pipeline: String = "orders"): JsonObject =
        JsonParser.parseString(
            s"""{"runId":"run-1","pipeline":"$pipeline","job":{"status":"success","filename":"tap-$tapName.csv"},
               |"input":{"kind":"tap","tapName":"$tapName","tapRunTime":"t","scriptSha":"abc","source":"tap:$tapName","filename":"tap-$tapName.csv"},
               |"tapRun":{"tapName":"$tapName","runTime":"t","status":"success"},"tap":{"name":"$tapName","catalog":"HR"},
               |"source":"tap:$tapName","configVersion":{"version":1}}""".stripMargin
        ).getAsJsonObject
    private def resolvedRun(p: Option[String]): JsonObject = {
        val o = new JsonObject(); o.addProperty("runId", "run-1"); p.foreach(o.addProperty("pipeline", _)); o
    }

    // activity
    import ActivitySignals.{FailingItem, PipelineVolume, Signals, StaleTap}
    private def failing(kind: String, name: String, catalog: String, reason: String, related: Option[String] = None) =
        FailingItem(kind, name, Option(catalog), reason, None, recovered = false, failureCount = 1, pipelineToken = None, relatedTapName = related)
    private def signals = Signals(
        windowMs = 86400000L,
        computedAtMs = 1800000000000L,
        failing = List(
            failing("pipeline", "orders", null, "tap 't_hr_feed' sent bad rows; tap-t_hr_feed.csv rejected", Some("t_hr_feed")),
            failing("pipeline", "payroll", null, "payroll load failed"),
            failing("pipeline", "own_p", null, "own load failed"),
            failing("tap", "t_sales", "Sales", "tap t_sales timed out"),
            failing("tap", "t_hr_feed", "HR", "tap t_hr_feed timed out"),
            failing("tap", "t_own", "HR", "tap t_own timed out")
        ),
        staleTaps = List(StaleTap("t_sales", Some("Sales"), "daily", 86400000L, None), StaleTap("t_hr_feed", Some("HR"), "daily", 86400000L, None)),
        volumes = List(
            PipelineVolume("orders", Some("Sales"), 10L, 100L, 5, Some(-90)),
            PipelineVolume("payroll", Some("HR"), 10L, 100L, 5, Some(-90)),
            PipelineVolume("own_p", Some("HR"), 10L, 100L, 5, Some(-90))
        )
    )

    // incidents
    private val t0 = Instant.parse("2026-11-18T10:00:00Z")
    private def incident(id: String, rt: String, name: String) =
        Incident(
            id = id,
            kind = rt + "_failure",
            resourceType = rt,
            resourceName = name,
            openedAt = t0,
            state = "diagnosing",
            trigger = new JsonObject(),
            steps = List(IncidentStep(t0, "open", "opened")),
            evidence = Some(new JsonObject())
        )
    private val incOrders = incident("inc-1", "pipeline", "orders")
    private val incPayroll = incident("inc-2", "pipeline", "payroll")
    private val incOwn = incident("inc-3", "pipeline", "own_p")
    private val incSalesTap = incident("inc-4", "tap", "t_sales")
    private val incHrTap = incident("inc-5", "tap", "t_hr_feed")
    private val incOwnTap = incident("inc-6", "tap", "t_own")
    private val incidents = List(incOrders, incPayroll, incOwn, incSalesTap, incHrTap, incOwnTap)
    private def incidentShown(s: Shape, i: Incident): Boolean =
        if (i.resourceType == "pipeline") s.pipelines.contains(i.resourceName) else s.taps.contains(i.resourceName)
    private def noIncident(id: String): String = "{\"error\":\"no incident " + id + "\"}"

    // ================================================================== Acceptance bullets (Part B)

    test("status summary drops rows of pipelines outside a key's scope and a token of one is the unknown-token 404") {
        for (s <- limited("job:read")) {
            val r = req(s.rk)
            val shown = PipelineStatusAPIController.restrictedViewWith(r, summaries, pipelines, taps)
            assert(summaryNames(shown) == s.pipelines, s.name)

            // plans/stories/limited-caller-read-leaks.md (pinned edit, D2 (a)): was "the
            // session's existing 404"; a token (event list or rollup) of a pipeline outside
            // scope now reads as a token with no rows, as the session's does
            for (hidden <- AllPipelines -- s.pipelines) {
                val ev = PipelineStatusAPIController.restrictedViewWith(r, java.util.Arrays.asList(event(hidden)), pipelines, taps)
                val ru = PipelineStatusAPIController.restrictedViewWith(r, statusResponse(hidden, "upload.csv"), pipelines, taps)
                val noRows = PipelineStatusAPIController.restrictedViewWith(r, new java.util.ArrayList[PipelineStatus](), pipelines, taps)
                val session = PipelineStatusAPIController.restrictedViewWith(req(bob), java.util.Arrays.asList(event("payroll")), pipelines, taps)
                for (got <- Seq(ev, ru)) {
                    assert(got.isRight, s.name + " " + hidden + ": expected a token with no rows, got " + got)
                    ScopeDenialWording.assertHides(gson.toJson(got.right.get), Seq(hidden, "HR", "someone"))
                }
                assert(gson.toJson(ev.right.get) == gson.toJson(noRows.right.get), s.name + ": " + gson.toJson(ev.right.get))
                assert(session.isRight && gson.toJson(session.right.get) == gson.toJson(noRows.right.get), s.name)
            }
            // a publisher token spanning a visible and a hidden pipeline: only the visible run
            val mixed = PipelineStatusAPIController.restrictedViewWith(r, java.util.Arrays.asList(event("orders"), event("payroll")), pipelines, taps)
            if (!s.pipelines.contains("payroll")) {
                assert(mixed.isRight, s.name + ": " + mixed)
                ScopeDenialWording.assertHides(gson.toJson(mixed.right.get), Seq("payroll"))
            }
        }
        // in-scope token: shown
        val sales = shapes("job:read").head
        assert(PipelineStatusAPIController.restrictedViewWith(req(sales.rk), java.util.Arrays.asList(event("orders")), pipelines, taps).isRight)
    }

    test("result by a hidden token is the unknown-token 404") {
        for (s <- limited("job:read")) {
            val r = req(s.rk, uri = "/api/v1/pipeline/result")
            for (p <- AllPipelines) assert(PipelineStatusAPIController.resultHidden(r, p, pipelines) == !s.pipelines.contains(p), s.name + " " + p)
            assert(PipelineStatusAPIController.resultHidden(r, "never_existed", pipelines), s.name + ": a name with no pipeline is hidden")
        }
        // the answer the route then gives is exactly ScratchReader.resolve's unknown-token 404
        val e = PipelineStatusAPIController.unknownToken("tok-payroll", null)
        assert(e.status == 404 && e.getMessage == "No pipeline run found for pipelinetoken 'tok-payroll'")
        // wiring: the route asks the limited-caller question, not the session one
        val body = methodBody(source("datrisserver/src/main/scala/ai/datris/api/PipelineStatusAPIController.scala"), "def getPipelineResult(")
        assert(!body.contains("isRestrictedSession"), "GET /pipeline/result must gate on the limited caller, not the restricted session")
    }

    test("tap logs: all-taps list filtered, named hidden tap 403") {
        for (s <- limited("job:read")) {
            var reads = 0
            val out = TapAPIController.visibleTapLogs(req(s.rk, uri = "/api/v1/tap/logs/all"), tapLogs, () => { reads += 1; taps })
            assert(logNames(out) == s.taps, s.name)
            assert(out.forall(_.getAsJsonObject.has("tapName")), s.name + ": a log with no tap name is hidden")
            assert(reads == 1, s.name + ": the tap store is read once")
        }
        // named: a key gets the shared 403 on tap:read, a restricted session the tap 404 (Part A seam)
        for (s <- shapes("job:read")) {
            for (hidden <- AllTaps -- s.taps)
                assertKeyDenied(
                    TapAPIController.restrictedRefusal(req(s.rk, uri = "/api/v1/tap/logs"), hidden, "read", readTap),
                    s,
                    "tap",
                    "read",
                    Seq("HR", "someone")
                )
            for (shown <- s.taps) assert(TapAPIController.restrictedRefusal(req(s.rk), shown, "read", readTap).isEmpty, s.name + " " + shown)
        }
        val sessionAnswer = TapAPIController.restrictedRefusal(req(bob), "t_hr_feed", "read", readTap)
        assert(sessionAnswer.exists(r => status(r) == 404 && r.getBody == TapAPIController.tapNotFound("t_hr_feed").getBody))
        // wiring
        val body = methodBody(source("datrisserver/src/main/scala/ai/datris/api/TapAPIController.scala"), "def getAllTapLogs(")
        assert(!body.contains("isRestrictedSession"), "GET /tap/logs/all must gate on the limited caller")
    }

    test("objectstore and warehouse previews of a pipeline outside scope are 403 and in-scope rows lose the tap stamp columns") {
        for (s <- shapes("query:objectstore", "query:snowflake")) {
            val r = req(s.rk, "POST", "/api/v1/query/objectstore")
            for (hidden <- AllPipelines -- s.pipelines)
                assertKeyDenied(QueryAPIController.restrictedPipelineRefusal(r, hidden, readPipeline), s, "pipeline", "read", Seq("HR", "someone"))
            for (shown <- s.pipelines)
                assert(QueryAPIController.restrictedPipelineRefusal(req(s.rk, "POST"), shown, readPipeline).isEmpty, s.name + " " + shown)
        }
        // a restricted session keeps today's 404
        val sr = QueryAPIController.restrictedPipelineRefusal(req(bob, "POST"), "payroll", readPipeline)
        assert(sr.exists(r => status(r) == 404 && r.getBody == PipelineAPIController.pipelineNotFound("payroll").getBody), sr.map(_.getBody))

        // in-scope rows: the tap stamp columns go from every row (as for a restricted session); the row count is unchanged
        // (a key limited for pipelines only reads every tap and keeps the columns: lead ruling 7, pinned below)
        for (s <- limited("query:objectstore", "query:snowflake").filter(_.name != "pipeline-limited only")) {
            val in = rows(3)
            val out = QueryAPIController.restrictedRows(req(s.rk, "POST"), in)
            assert(out.size == 3, s.name)
            out.asScala.foreach(o => HiddenTapScrub.StampTapColumns.foreach(c => assert(!o.containsKey(c), s.name + " keeps " + c)))
            assert(out.asScala.forall(_.containsKey("_datris_run_id")) && out.get(0).get("amount") == 10, s.name)
            val os = QueryAPIController.restrictedObjectStoreResult(req(s.rk, "POST", "/api/v1/query/objectstore"), osResult(2))
            assert(os.columns.asScala.toList == List("id", "_datris_run_id", "amount"), s.name)
            assert(os.rows.size == 2 && os.path == "s3://datris/orders/" && os.format == "parquet", s.name)
            assert(!gson.toJson(os.rows).contains("hr.internal") && !gson.toJson(out).contains("hr-sha"), s.name)
        }
        // lead ruling 7: a key limited for pipelines only (unscoped tap:read) keeps the tap stamp columns on its rows
        val pipeOnly = shapes("query:objectstore", "query:snowflake").find(_.name == "pipeline-limited only").get
        val keptIn = rows(3)
        assert(QueryAPIController.restrictedRows(req(pipeOnly.rk, "POST"), keptIn) eq keptIn, pipeOnly.name)
        val keptOs = osResult(2)
        assert(QueryAPIController.restrictedObjectStoreResult(req(pipeOnly.rk, "POST", "/api/v1/query/objectstore"), keptOs) eq keptOs, pipeOnly.name)
        assert(keptOs.columns.asScala.toList.contains("_datris_tap_run") && keptOs.rows.get(0).containsKey("_datris_tap_run"), pipeOnly.name)
    }

    test("search: a container no in-scope pipeline feeds is 404 and other writers' chunks are dropped") {
        for (s <- limited("search:vector")) {
            val r = req(s.rk, "POST", "/api/v1/search/qdrant")
            def visible(c: String) =
                SearchAPIController.containerVisible(
                    request = r,
                    store = "qdrant",
                    container = c,
                    schema = None,
                    find = (st, co, sc) => VectorSearchRegistry.pipelinesForContainer(st, co, searchConfigs, sc)
                )
            val feeders = Map("sales_docs" -> Set("orders"), "hr_docs" -> Set("payroll"), "own_docs" -> Set("own_p"), "shared_docs" -> Set("mix_a", "mix_b"))
            val readable =
                s.pipelines ++ (if (s.pipelines == AllPipelines) Set("mix_a", "mix_b") else if (s.pipelines.contains("orders")) Set("mix_a") else Set.empty)
            for ((c, f) <- feeders) {
                val expect = f.forall(readable.contains)
                assert(visible(c) == expect, s.name + " " + c)
                val w = SearchAPIController.searchWriters(r, "qdrant", c, None, () => searchConfigs)
                if (expect) assert(w.isRight, s.name + " " + c + ": " + w)
                else {
                    assert(w.isLeft, s.name + " " + c + ": expected the 404, got " + w)
                    assert(
                        status(w.left.get) == 404 && w.left.get.getBody == SearchAPIController.containerNotFound("collection", c).getBody,
                        w.left.get.getBody
                    )
                    ScopeDenialWording.assertHides(w.left.get.getBody, Seq("payroll", "mix_b", "HR"))
                }
            }
            // the container no config names at all: 404 for every caller limited for pipelines; a key that
            // reads every pipeline (tap-limited only) searches it, as on main (lead ruling on review finding 5)
            if (s.name != "tap-limited only") assert(SearchAPIController.searchWriters(r, "qdrant", "orphan_docs", None, () => searchConfigs).isLeft, s.name)
            else assert(SearchAPIController.searchWriters(r, "qdrant", "orphan_docs", None, () => fail("configs read")) == Right(None), s.name)
        }
        // other writers' chunks are dropped from an in-scope container
        val sales = shapes("search:vector").head
        val r = req(sales.rk, "POST", "/api/v1/search/qdrant")
        val w = SearchAPIController.searchWriters(r, "qdrant", "sales_docs", None, () => searchConfigs)
        assert(w.isRight && w.right.get.isDefined, "a limited key's search gets a writer filter: " + w)
        val out = SearchAPIController.hitsFor(r, storeHits, w.right.get, readTap, _ => None)
        assert(ids(out) == Seq(1, 4), out.toString)
        assert(!gson.toJson(out).contains("hr salary chunk"))
        HiddenTapScrub.StampTapColumns.foreach(c => assert(!out.get(1).containsKey(c), "the hidden tap's stamp " + c + " must go: " + out.get(1)))
        // the response's count is the filtered list's size (buildResponse puts results.size())
        val build = methodBody(source("datrisserver/src/main/scala/ai/datris/api/SearchAPIController.scala"), "private def buildResponse(")
        assert(build.contains("response.put(\"count\", results.size())"), build)
    }

    test(
        "lineage graph, neighbourhood runs, columns, provenance and activity signals show only in-scope pipelines and taps, hidden tap names read `[hidden]`"
    ) {
        val g = lineageGraph
        for (s <- limited("metadata:read")) {
            val r = req(s.rk, uri = "/api/v1/lineage")
            // graph
            val ids = LineageAPIController.visibleGraph(r, g).nodes.map(_.id).toSet
            for (p <- AllPipelines -- s.pipelines) assert(!ids.contains("pipeline:" + p), s.name + ": " + ids)
            for (t <- AllTaps -- s.taps) assert(!ids.contains("tap:" + t), s.name + ": " + ids)
            // neighbourhood runs: a hidden feeding tap loses its identity, filename reads tap-[hidden]
            val n = LineageAPIController.scrubNeighborhood(r, neighborhood, readTap)
            val hrRun = n.getAsJsonArray("runs").get(0).getAsJsonObject.getAsJsonObject("input")
            if (!s.taps.contains("t_hr_feed")) {
                HiddenTapScrub.TapIdentityFields.foreach(f => assert(!hrRun.has(f), s.name + ": " + f))
                assert(hrRun.get("filename").getAsString == "tap-" + HiddenTapScrub.Placeholder + ".csv", s.name)
                assert(!n.toString.contains("t_hr_feed"), s.name + ": " + n)
            }
            // provenance of a visible run: a hidden tap's blocks go, filenames read tap-[hidden]
            if (s.pipelines.contains("orders") && !s.taps.contains("t_hr_feed")) {
                val pv = ProvenanceAPIController.scrubProvenance(r, provenance("t_hr_feed"), readTap, Set.empty)
                Seq("tapRun", "tap", "source").foreach(k => assert(!pv.has(k), s.name + ": " + k))
                assert(pv.getAsJsonObject("job").get("filename").getAsString == "tap-[hidden].csv", s.name)
                assert(!pv.toString.contains("t_hr_feed"), s.name)
            }
            // provenance of a hidden pipeline's run: 404 no such run
            for (p <- AllPipelines -- s.pipelines) {
                val pr = ProvenanceAPIController.provenanceRefusal(r, "run-1", resolvedRun(Some(p)), readPipeline)
                assert(pr.exists(x => status(x) == 404 && x.getBody.contains("no such run: run-1")), s.name + " " + p)
                assert(!pr.get.getBody.contains(p), pr.get.getBody)
            }
            for (p <- s.pipelines) assert(ProvenanceAPIController.provenanceRefusal(r, "run-1", resolvedRun(Some(p)), readPipeline).isEmpty, s.name + " " + p)
            // activity signals
            val act = ActivityAPIController.callerView(r, signals, () => pipelines, () => taps)
            for (f <- act.failing) {
                if (f.kind == "pipeline") assert(s.pipelines.contains(f.name), s.name + ": " + f)
                else assert(s.taps.contains(f.name), s.name + ": " + f)
            }
            assert(act.staleTaps.forall(t => s.taps.contains(t.name)), s.name + ": " + act.staleTaps)
            assert(act.volumes.forall(v => s.pipelines.contains(v.name)), s.name + ": " + act.volumes)
            act.failing.find(_.name == "orders").foreach { o =>
                if (!s.taps.contains("t_hr_feed")) {
                    assert(o.relatedTapName.isEmpty, s.name)
                    assert(o.reason == "tap '[hidden]' sent bad rows; tap-[hidden].csv rejected", s.name + ": " + o.reason)
                }
            }
            for (hidden <- (AllPipelines -- s.pipelines) ++ (AllTaps -- s.taps))
                assert(!act.toJson.toString.contains("\"" + hidden + "\""), s.name + " " + hidden)
        }
        // columns of a named pipeline outside scope: 403 for a key (shared wording), the session's 404 unchanged
        for (s <- shapes("metadata:read")) {
            for (p <- AllPipelines -- s.pipelines)
                assertKeyDenied(
                    LineageAPIController.columnsRefusal(req(s.rk, uri = "/api/v1/lineage/columns/" + p), p, readPipeline),
                    s,
                    "pipeline",
                    "read",
                    Seq("HR", "someone")
                )
            for (p <- s.pipelines) assert(LineageAPIController.columnsRefusal(req(s.rk), p, readPipeline).isEmpty, s.name + " " + p)
        }
        val sc = LineageAPIController.columnsRefusal(req(bob), "payroll", readPipeline)
        assert(sc.exists(x => status(x) == 404 && x.getBody.contains("no such pipeline: payroll")))
        // wiring: the neighbourhood, columns, provenance and activity routes ask the limited-caller question
        val lineage = source("datrisserver/src/main/scala/ai/datris/api/LineageAPIController.scala")
        Seq("def neighborhood(", "def columns(").foreach(m => assert(!methodBody(lineage, m).contains("isRestrictedSession"), m))
        assert(
            !methodBody(source("datrisserver/src/main/scala/ai/datris/api/ProvenanceAPIController.scala"), "def provenance(").contains("isRestrictedSession")
        )
        assert(!methodBody(source("datrisserver/src/main/scala/ai/datris/api/ActivityAPIController.scala"), "def signals(").contains("isRestrictedSession"))
    }

    test("incidents list drops, and get/evidence refuse, an incident on a resource outside scope; the Ops chat block matches") {
        for (s <- limited("incident:read")) {
            val r = req(s.rk, uri = "/api/v1/incidents")
            val shown = IncidentsAPIController.visibleIncidents(r, incidents, readPipeline, readTap)
            assert(shown.map(_.id) == incidents.filter(i => incidentShown(s, i)).map(_.id), s.name)
            for (i <- incidents) {
                val refused = IncidentsAPIController.incidentRefusal(req(s.rk, uri = "/api/v1/incidents/" + i.id), i.id, Some(i), readPipeline, readTap)
                if (incidentShown(s, i)) assert(refused.isEmpty, s.name + " " + i.id)
                else {
                    assert(refused.exists(x => status(x) == 404 && x.getBody == noIncident(i.id)), s.name + " " + i.id + ": " + refused.map(_.getBody))
                    ScopeDenialWording.assertHides(refused.get.getBody, Seq(i.resourceName, "HR"))
                }
            }
        }
        // a stored resource that is gone: hidden from a limited caller (fail closed)
        val gone = incident("inc-gone", "pipeline", "deleted_p")
        assert(IncidentsAPIController.visibleIncidents(req(shapes("incident:read").head.rk), List(gone), readPipeline, readTap).isEmpty)

        // the Ops chat block: a limited key holding incident:read gets exactly the list's filter
        val ctx = new JsonObject()
        ctx.addProperty("window", "24h")
        for (s <- shapes("incident:read")) {
            val id = ChatIdentity(None, Some(s.rk), ownKey = true)
            val block = OpsChatAPIController.incidentsFor(id, () => incidents, readPipeline, readTap)
            assert(block.map(_.id) == IncidentsAPIController.visibleIncidents(req(s.rk), incidents, readPipeline, readTap).map(_.id), s.name)
            val msg = OpsAgentPrompt.renderContextMessage(ctx, block)
            for (i <- incidents.filterNot(i => incidentShown(s, i))) assert(!msg.contains(i.id + ":"), s.name + ": " + i.id)
        }
        // a restricted session's chat block stays empty and the store is not read (unchanged)
        val bobUser = User("bob", "h", User.RoleEditor, null, null, null, restricted = true, catalogGrants = grants("Sales" -> "view"))
        val sessId = ChatIdentity(Some(bobUser), Some(bob), ownKey = false)
        assert(OpsChatAPIController.incidentsFor(sessId, () => fail("not read"), failRead, failReadTap) == Nil)
    }

    test("every case returns its argument unchanged (same instance, no reader called) for an unrestricted editor and a full key") {
        val g = lineageGraph
        for ((name, r) <- unchanged("metadata:read", "query:objectstore", "search:vector", "incident:read", "tap:read")) {
            // pipeline status and result
            val list = summaries
            assert(PipelineStatusAPIController.restrictedViewWith(r, list, pipelines, taps, failRun).toOption.get eq list, name)
            val resp = statusResponse("payroll", "tap-t_hr_feed.csv")
            assert(PipelineStatusAPIController.restrictedViewWith(r, resp, pipelines, taps, failRun).toOption.get eq resp, name)
            withNoEnvironment(assert(PipelineStatusAPIController.restrictedView(r, resp).toOption.get eq resp, name))
            assert(!PipelineStatusAPIController.resultHidden(r, "payroll", pipelines), name)
            val page = resultPage
            assert(
                PipelineStatusAPIController.scrubResultPage(r, page, failReadTap, "tap-t_hr_feed.csv", "orders", "tok", failRun, failDescriptions) eq page,
                name
            )
            assert(!HiddenTapScrub.tapHidden(r, "t_hr_feed", failReadTap), name)
            assert(HiddenTapScrub.hiddenFeedingTaps(r, taps, Set("orders")).isEmpty, name)
            // tap logs
            val logs = tapLogs
            assert(TapAPIController.visibleTapLogs(r, logs, failReadTaps) eq logs, name)
            assert(TapAPIController.restrictedRefusal(r, "t_hr_feed", "read", failReadTap).isEmpty, name)
            // queries
            assert(QueryAPIController.restrictedPipelineRefusal(r, "payroll", failRead).isEmpty, name)
            val rs = rows(2)
            assert(QueryAPIController.restrictedRows(r, rs) eq rs, name)
            val os = osResult(2)
            assert(QueryAPIController.restrictedObjectStoreResult(r, os) eq os, name)
            // search
            assert(SearchAPIController.containerVisible(request = r, store = "qdrant", container = "hr_docs", schema = None, find = failFind), name)
            assert(SearchAPIController.searchWriters(r, "qdrant", "hr_docs", None, failReadAll) == Right(None), name)
            val hs = storeHits
            assert(SearchAPIController.hitsFor(r, hs, None, failReadTap, failRun) eq hs, name)
            // lineage, provenance, find, activity
            assert(LineageAPIController.visibleGraph(r, g) eq g, name)
            val nb = neighborhood
            assert((LineageAPIController.scrubNeighborhood(r, nb, failReadTap) eq nb) && nb.toString == neighborhood.toString, name)
            assert(LineageAPIController.recordsShown(r, "payroll", pipelines), name)
            assert(LineageAPIController.columnsRefusal(r, "payroll", failRead).isEmpty, name)
            assert(ProvenanceAPIController.provenanceRefusal(r, "run-1", resolvedRun(Some("payroll")), failRead).isEmpty, name)
            val pv = provenance("t_hr_feed", "payroll")
            assert(
                (ProvenanceAPIController.scrubProvenance(r, pv, failReadTap, Set("t_hr_feed")) eq pv) && pv.toString == provenance("t_hr_feed", "payroll").toString,
                name
            )
            assert(CatalogFindAPIController.recordsShown(r, pipelines, pipelines) == null, name)
            val sg = signals
            assert(ActivityAPIController.callerView(r, sg, failReadAll, failReadTaps) eq sg, name)
            // incidents
            val inc = incidents
            assert(IncidentsAPIController.visibleIncidents(r, inc, failRead, failReadTap) eq inc, name)
            assert(IncidentsAPIController.incidentRefusal(r, incPayroll.id, Some(incPayroll), failRead, failReadTap).isEmpty, name)
        }
        // the Ops chat block: unlimited keys holding incident:read get the loaded list itself.
        // plans/stories/chat-session-role-authority.md (approved edit, Decision 4): an
        // unrestricted editor session's role bundle holds no incident:read, so it gets
        // Nil and the incident store is not read (was: the loaded list itself).
        val edSession = ChatIdentity(Some(User("ed", "h", User.RoleEditor, null, null, null)), Some(editor), ownKey = false)
        assert(OpsChatAPIController.incidentsFor(edSession, () => fail("the incident store was read for an editor session"), failRead, failReadTap) == Nil)
        val ids = Seq(
            ChatIdentity(None, Some(legacy), ownKey = false),
            ChatIdentity(None, Some(wildcard), ownKey = false),
            ChatIdentity(None, Some(key("ops-inc", "incident:read", "pipeline:read")), ownKey = true),
            ChatIdentity(None, Some(key("monitor", "job:read", "incident:read")), ownKey = true)
        )
        for (id <- ids) {
            val loaded = incidents
            assert(OpsChatAPIController.incidentsFor(id, () => loaded, failRead, failReadTap) eq loaded, id.toString)
        }
    }

    // ================================================================== additions: key shapes

    test("key shapes: a key whose only scoped pipeline capability is inert is held to its scope on every record family, in both modes") {
        val g = lineageGraph
        for (allow <- Seq(false, true)) withInertMode(allow) {
            val rk = inertKey("job:read", "metadata:read", "query:objectstore", "search:vector", "incident:read")
            assert(CallerVisibility.limitedPipelines(rk) && CallerVisibility.limitedTaps(rk), "allow=" + allow)
            val r = req(rk)
            val m = "allow=" + allow
            assert(!summaryNames(PipelineStatusAPIController.restrictedViewWith(r, summaries, pipelines, taps)).exists(Set("payroll", "own_p")), m)
            assert(PipelineStatusAPIController.resultHidden(r, "payroll", pipelines), m)
            assert(logNames(TapAPIController.visibleTapLogs(r, tapLogs, () => taps)).isEmpty, m + ": it reads no tap")
            val qr = QueryAPIController.restrictedPipelineRefusal(r, "payroll", readPipeline)
            assert(qr.exists(x => status(x) == 403), m + " " + qr.map(_.getBody))
            ScopeDenialWording.assertHides(qr.get.getBody, Seq("HR", "someone"))
            assert(!QueryAPIController.restrictedRows(r, rows(1)).get(0).containsKey("_datris_tap_run"), m)
            assert(SearchAPIController.searchWriters(r, "qdrant", "hr_docs", None, () => searchConfigs).isLeft, m)
            val ids = LineageAPIController.visibleGraph(r, g).nodes.map(_.id).toSet
            assert(!ids.contains("pipeline:payroll") && !ids.exists(_.startsWith("tap:")), m + " " + ids)
            assert(ProvenanceAPIController.provenanceRefusal(r, "run-1", resolvedRun(Some("payroll")), readPipeline).isDefined, m)
            assert(LineageAPIController.columnsRefusal(r, "payroll", readPipeline).exists(x => status(x) == 403), m)
            assert(!ActivityAPIController.callerView(r, signals, () => pipelines, () => taps).failing.exists(f => f.name == "payroll" || f.kind == "tap"), m)
            assert(
                !IncidentsAPIController.visibleIncidents(r, incidents, readPipeline, readTap).exists(i =>
                    i.resourceName == "payroll" || i.resourceType == "tap"
                ),
                m
            )
        }
    }

    test("key shapes: limited for taps only (unscoped pipeline:read, tap:run:owner=self) sees every pipeline's records with every tap name hidden") {
        val s = shapes("job:read").find(_.name == "tap-limited only").get
        val r = req(s.rk)
        assert(summaryNames(PipelineStatusAPIController.restrictedViewWith(r, summaries, pipelines, taps)) == AllPipelines)
        val body = gson.toJson(PipelineStatusAPIController.restrictedViewWith(r, statusResponse("orders", "tap-t_sales.csv"), pipelines, taps).toOption.get)
        assert(body.contains("tap-[hidden].csv") && !body.contains("t_sales"), body)
        val page = PipelineStatusAPIController.scrubResultPage(req(s.rk, uri = "/api/v1/pipeline/result"), resultPage, readTap, "upload.csv", "orders", "tok")
        page.records.asScala.foreach(x => HiddenTapScrub.StampTapColumns.foreach(c => assert(!x.getAsJsonObject.has(c), c + " " + x)))
        // search: every container is visible, and the tap stamps of chunks fed by a tap it cannot read still go
        val sr = req(key("taplim", "pipeline:read", "tap:run:owner=self", "search:vector"), "POST", "/api/v1/search/qdrant")
        val w = SearchAPIController.searchWriters(sr, "qdrant", "hr_docs", None, () => searchConfigs)
        assert(w.isRight, w.toString)
        val out = SearchAPIController.hitsFor(sr, storeHits, w.right.get, readTap, _ => None)
        assert(Seq(1, 2, 3, 4).forall(ids(out).contains), "every pipeline's chunk is shown: " + ids(out))
        out.asScala.foreach(h => HiddenTapScrub.StampTapColumns.foreach(c => assert(!h.containsKey(c), c + " " + h)))
    }

    test("key shapes: limited for pipelines only (unscoped tap:read, pipeline:read:owner=self) sees only its own pipeline's records with tap names kept") {
        val s = shapes("job:read").find(_.name == "pipeline-limited only").get
        val r = req(s.rk)
        assert(summaryNames(PipelineStatusAPIController.restrictedViewWith(r, summaries, pipelines, taps)) == Set("own_p"))
        // limited-caller-read-leaks (pinned edit, D2 (a)): was isLeft; a hidden token reads as one with no rows
        val other = PipelineStatusAPIController.restrictedViewWith(r, java.util.Arrays.asList(event("orders")), pipelines, taps)
        assert(other.isRight && gson.toJson(other.right.get) == "[]", other.toString)
        val ok = PipelineStatusAPIController.restrictedViewWith(r, statusResponse("own_p", "tap-t_hr_feed.csv"), pipelines, taps)
        assert(gson.toJson(ok.toOption.get).contains("tap-t_hr_feed.csv"), "every tap is readable: its name stays")
        assert(!HiddenTapScrub.tapHidden(r, "t_hr_feed", readTap))
        assert(HiddenTapScrub.tapHidden(r, "gone", readTap), "a tap no longer stored is hidden from a limited caller")
    }

    test("key shapes: owner=self keys see their own pipeline's and tap's records, not another key's (status, tap logs, search, incidents)") {
        val s = shapes("job:read", "search:vector", "incident:read").find(_.name == "owner=self").get
        val r = req(s.rk)
        assert(summaryNames(PipelineStatusAPIController.restrictedViewWith(r, summaries, pipelines, taps)) == Set("own_p"))
        assert(logNames(TapAPIController.visibleTapLogs(r, tapLogs, () => taps)) == Set("t_own"))
        assert(SearchAPIController.searchWriters(r, "qdrant", "own_docs", None, () => searchConfigs).isRight)
        assert(SearchAPIController.searchWriters(r, "qdrant", "sales_docs", None, () => searchConfigs).isLeft)
        assert(IncidentsAPIController.visibleIncidents(r, incidents, readPipeline, readTap).map(_.id).toSet == Set(incOwn.id, incOwnTap.id))
    }

    test(
        "OPEN QUESTION reading: an owner=self key sees its own pipeline's and tap's activity rows (the store gives the owner; the session filter reads none)"
    ) {
        val s = shapes("metadata:read").find(_.name == "owner=self").get
        val act = ActivityAPIController.callerView(req(s.rk), signals, () => pipelines, () => taps)
        assert(act.failing.map(_.name).toSet == Set("own_p", "t_own"), act.failing.toString)
        assert(act.volumes.map(_.name) == List("own_p"), act.volumes.toString)
    }

    // ================================================================== additions: D2 oracles

    test("D2 oracle: a hidden token, run id, container or lineage node answers a limited key byte-identically to one that does not exist") {
        val g = lineageGraph
        for (s <- limited("job:read", "metadata:read", "search:vector", "incident:read")) {
            val r = req(s.rk)
            // run id: a run of a hidden pipeline vs a run that resolves to no pipeline, or to nothing at all
            if (!s.pipelines.contains("payroll")) {
                var hiddenReads = 0
                val hidden = ProvenanceAPIController.provenanceRefusal(r, "run-1", resolvedRun(Some("payroll")), n => { hiddenReads += 1; readPipeline(n) })
                val noPipeline = ProvenanceAPIController.provenanceRefusal(r, "run-1", resolvedRun(None), readPipeline)
                val nothing = ProvenanceAPIController.provenanceRefusal(r, "run-1", null, readPipeline)
                val unknownName = ProvenanceAPIController.provenanceRefusal(r, "run-1", resolvedRun(Some("payroll")), _ => null)
                for (x <- Seq(noPipeline, nothing, unknownName)) {
                    assert(x.isDefined && hidden.isDefined, s.name)
                    assert(
                        status(x.get) == status(hidden.get) && x.get.getBody == hidden.get.getBody,
                        s.name + ": " + x.get.getBody + " vs " + hidden.get.getBody
                    )
                }
                assert(hiddenReads <= 1, s.name)
            }
            // container: hidden vs a container of the same name no config names
            if (!s.pipelines.contains("payroll")) {
                var a = 0
                var b = 0
                val hidden = SearchAPIController.searchWriters(r, "qdrant", "hr_docs", None, () => { a += 1; searchConfigs })
                val absent = SearchAPIController.searchWriters(r, "qdrant", "hr_docs", None, () => { b += 1; searchConfigs.filterNot(_.name == "payroll") })
                assert(hidden.isLeft && absent.isLeft, s.name)
                assert(status(hidden.left.get) == status(absent.left.get) && hidden.left.get.getBody == absent.left.get.getBody, s.name)
                assert(a == b, s.name + ": the store is read the same way in both cases")
            }
            // lineage node: a hidden node is absent from the walked graph exactly as an unknown one
            // (the route's 404 body is built from the request's type and name only)
            if (!s.pipelines.contains("payroll")) {
                val walked = LineageAPIController.visibleGraph(r, g)
                val without = LineageService.build(taps.toList.filterNot(_.targetPipeline == "payroll"), pipelines.toList.filterNot(_.name == "payroll"))
                assert(!walked.nodes.exists(_.id == "pipeline:payroll") && !without.nodes.exists(_.id == "pipeline:payroll"), s.name)
            }
            // incident id: a hidden incident vs an unknown id
            for (i <- incidents.filterNot(i => incidentShown(s, i))) {
                val x = IncidentsAPIController.incidentRefusal(r, i.id, Some(i), readPipeline, readTap)
                assert(x.exists(_.getBody == noIncident(i.id)) && x.exists(xx => status(xx) == 404), s.name + " " + i.id)
            }
            // token: the key's answer is the session's answer (D2: no new oracle)
            // limited-caller-read-leaks (pinned edit, D2 (a)): was both the 404; both now read
            // as a token with no rows
            if (!s.pipelines.contains("payroll")) {
                val k = PipelineStatusAPIController.restrictedViewWith(r, java.util.Arrays.asList(event("payroll")), pipelines, taps)
                val ss = PipelineStatusAPIController.restrictedViewWith(req(bob), java.util.Arrays.asList(event("payroll")), pipelines, taps)
                assert(k.isRight && ss.isRight && gson.toJson(k.right.get) == gson.toJson(ss.right.get) && gson.toJson(k.right.get) == "[]", s.name)
            }
        }
    }

    // ================================================================== additions: counts and [hidden]

    test("counts: filtered rows never leave a trace in the payload (status summary, tap logs, activity anomalies, query and search counts)") {
        val s = shapes("job:read").head // catalog=Sales
        val r = req(s.rk)
        val shown = PipelineStatusAPIController.restrictedViewWith(r, summaries, pipelines, taps).toOption.get
        val body = gson.toJson(shown)
        Seq("payroll", "own_p", "tok-payroll").foreach(h => assert(!body.contains(h), body))
        assert(gson.toJson(TapAPIController.visibleTapLogs(r, tapLogs, () => taps).asJava).split("tapName").length - 1 == 1)
        val act = ActivityAPIController.callerView(r, signals, () => pipelines, () => taps).toJson
        assert(act.getAsJsonArray("anomalies").size == 1 && act.getAsJsonArray("volumes").size == 1, act.toString)
        assert(act.getAsJsonArray("failing").size == 2 && act.getAsJsonArray("staleTaps").size == 1, act.toString)
        Seq("payroll", "own_p", "t_hr_feed", "t_own").foreach(h => assert(!act.toString.contains(h), act.toString))
        // query: stamp columns go, rows stay, so `count` (rows.size) is the row count the caller sees
        assert(QueryAPIController.restrictedRows(req(s.rk, "POST"), rows(3)).size == 3)
        // search: `count` is results.size() after hitsFor (pinned in the search bullet)
    }

    test(
        "[hidden]: a visible pipeline's records name a tap the caller cannot read as `[hidden]` in status, result rows, lineage runs, provenance and activity"
    ) {
        for (s <- limited("job:read", "metadata:read").filter(x => x.pipelines.contains("orders") && !x.taps.contains("t_hr_feed"))) {
            val r = req(s.rk)
            val status =
                gson.toJson(PipelineStatusAPIController.restrictedViewWith(r, statusResponse("orders", "tap-t_hr_feed.csv"), pipelines, taps).toOption.get)
            assert(status.contains("tap-[hidden].csv") && !status.contains("t_hr_feed"), s.name + ": " + status)
            val page = PipelineStatusAPIController.scrubResultPage(r, resultPage, readTap, "upload.csv", "orders", "tok")
            val first = page.records.get(0).getAsJsonObject
            HiddenTapScrub.StampTapColumns.foreach(c => assert(!first.has(c), s.name + ": " + c))
            assert(!page.records.toString.contains("hr-sha") && !page.records.toString.contains("hr.internal"), s.name)
            assert(page.rowCount == 2L && page.returnedCount == 2, s.name + ": the page counts are the caller's rows")
            assert(HiddenTapScrub.hiddenFeedingTaps(r, taps, Set("orders")).contains("t_hr_feed"), s.name)
            assert(HiddenTapScrub.scrubText("tap 't_hr_feed' failed", Set("t_hr_feed")) == "tap '[hidden]' failed")
        }
    }

    // ================================================================== additions: incidents (sessions)

    test("incidents: a restricted session is refused every incident route at the capability gate (its bundle holds no incident capability)") {
        assume(!sys.env.get("CAPABILITY_ENFORCEMENT").exists(_.equalsIgnoreCase("log-only")), "CAPABILITY_ENFORCEMENT=log-only is set")
        Seq(
            "GET" -> "/api/v1/incidents",
            "GET" -> "/api/v1/incidents/inc-payroll",
            "GET" -> "/api/v1/incidents/inc-payroll/evidence",
            "POST" -> "/api/v1/incidents/inc-payroll/proceed",
            "POST" -> "/api/v1/incidents/inc-payroll/abandon"
        ).foreach { case (m, p) =>
            val (cont, st) = intercept(req(restricted("Sales" -> "own"), m, p))
            assert(!cont && st == 403, m + " " + p)
        }
    }

    test(
        "OPEN QUESTION reading: proceed and abandon by a restricted session on an incident outside its grants answer the unknown-incident 404 and act on nothing"
    ) {
        // The capability gate refuses restricted sessions today (test above); under
        // CAPABILITY_ENFORCEMENT=log-only, or the day a restricted bundle gains
        // incident:proceed / abandon, the route must still not act on a hidden incident.
        val session = restricted("Sales" -> "own")
        for (i <- Seq(incPayroll, incHrTap, incOwn); route <- Seq("proceed", "abandon")) {
            val r = req(session, "POST", "/api/v1/incidents/" + i.id + "/" + route)
            val x = IncidentsAPIController.incidentRefusal(r, i.id, Some(i), readPipeline, readTap)
            assert(x.exists(y => status(y) == 404 && y.getBody == noIncident(i.id)), route + " " + i.id)
        }
        assert(IncidentsAPIController.incidentRefusal(req(session, "POST"), incOrders.id, Some(incOrders), readPipeline, readTap).isEmpty)
        // wiring: proceed and abandon call the refusal before IncidentRunner.proceed / IncidentIO.transition
        val src = source("datrisserver/src/main/scala/ai/datris/api/IncidentsAPIController.scala")
        for ((m, act) <- Seq("def proceed(" -> "IncidentRunner.proceed", "def abandon(" -> "IncidentIO.transition")) {
            val b = methodBody(src, m)
            assert(b.contains("incidentRefusal") && b.indexOf("incidentRefusal") < b.indexOf(act), m)
        }
        // and get / evidence / list take the request, so the refusal can run there
        for (m <- Seq("def list(", "def get(", "def evidence(")) assert(methodBody(src, m).contains("HttpServletRequest"), m)
    }

    // ================================================================== additions: search scopes together

    test(
        "search: the collection= scope and the container rule both hold; when both refuse, the caller sees the collection 403 (it runs first, before any lookup)"
    ) {
        val docsCaps = SalesRead ++ Seq("search:vector:collection=sales_docs", "search:vector:collection=hr_docs")
        val k = key("both", docsCaps: _*)
        val r = req(k, "POST", "/api/v1/search/qdrant")
        // collection allowed, container fed by a pipeline outside scope: the container 404
        assert(SearchAPIController.collectionScopeRefusal(r, "qdrant", "hr_docs", None).isEmpty)
        assert(SearchAPIController.searchWriters(r, "qdrant", "hr_docs", None, () => searchConfigs).isLeft)
        // both allow: searched, writers filtered
        assert(SearchAPIController.collectionScopeRefusal(r, "qdrant", "sales_docs", None).isEmpty)
        assert(SearchAPIController.searchWriters(r, "qdrant", "sales_docs", None, () => searchConfigs).right.get.isDefined)
        // collection refused, container in scope: the collection 403 (the pipeline scope does not widen it)
        val narrow = key("narrow", (SalesRead :+ "search:vector:collection=hr_docs"): _*)
        val nr = req(narrow, "POST", "/api/v1/search/qdrant")
        val c = SearchAPIController.collectionScopeRefusal(nr, "qdrant", "sales_docs", None)
        assert(c.exists(x => status(x) == 403), c.map(_.getBody))
        // both refuse (collection and container): the collection 403, decided before searchWriters reads anything
        val both = SearchAPIController.collectionScopeRefusal(
            req(key("narrow2", (SalesRead :+ "search:vector:collection=sales_docs"): _*), "POST"),
            "qdrant",
            "hr_docs",
            None
        )
        assert(both.exists(x => status(x) == 403), both.map(_.getBody))
        ScopeDenialWording.assertHides(both.get.getBody, Seq("payroll", "HR"))
        val body = methodBody(source("datrisserver/src/main/scala/ai/datris/api/SearchAPIController.scala"), "def search(")
        assert(body.indexOf("collectionScopeRefusal") >= 0 && body.indexOf("collectionScopeRefusal") < body.indexOf("searchWriters("), "collection check first")
    }

    // ================================================================== helpers

    private def intercept(r: HttpServletRequest): (Boolean, Int) = {
        val out = new java.io.StringWriter()
        val writer = new java.io.PrintWriter(out)
        var st = 200
        val resp = mock(classOf[HttpServletResponse])
        when(resp.getWriter).thenReturn(writer)
        doAnswer { i => st = i.getArgument[Int](0); null }.when(resp).setStatus(anyInt())
        val cont = new CapabilityInterceptor().preHandle(r, resp, null)
        writer.flush()
        (cont, st)
    }

    private def source(rel: String): String = {
        val file = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get(rel.stripPrefix("datrisserver/")))
            .find(java.nio.file.Files.isRegularFile(_)).getOrElse(fail(rel + " not found"))
        new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
    }

    /** From `signature` to the next method of the same indentation. */
    private def methodBody(src: String, signature: String): String = {
        val i = src.indexOf(signature)
        assert(i >= 0, signature)
        val j = src.indexOf("\n    def ", i + signature.length)
        val k = src.indexOf("\n    @", i + signature.length)
        val end = Seq(j, k).filter(_ >= 0).reduceOption(_ min _).getOrElse(src.length)
        src.substring(i, end)
    }
}
