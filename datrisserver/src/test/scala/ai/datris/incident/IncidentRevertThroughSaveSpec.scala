package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.api.TapAPIController
import ai.datris.incident.IncidentRunner.{ProposedAction, ToolOutcome}
import ai.datris.model.{
    DatrisEnvironment,
    Destination,
    FileAttributes,
    PipelineConfig,
    PipelineStatusRollup,
    QdrantConfig,
    Source,
    TapConfig,
    TenantContext,
    UnstructuredAttributes
}
import ai.datris.policy.RecoverySettings
import ai.datris.util.RunProfiler.{ColumnStats, Quantiles}
import ai.datris.util.{CatalogEnableJob, CatalogOps, SecretNames, TapDependencies, TapScriptResult}
import com.google.gson.{JsonObject, JsonParser}
import org.bson.Document
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.JavaConverters._
import scala.collection.mutable

/** The recovery agent's tap revert (plans/stories/incident-revert-through-save-core.md)
  * goes through the tap save path: the tap gets back the definition it had
  * before the agent acted and nothing else. It keeps the live tap's catalog,
  * owner, enabled state, run, retry and test state (D2); it waits for the
  * catalog lock and proceeds while an ordered-enable job runs (D3). When the
  * save rules refuse the pre-agent definition, the revert still lands and the
  * incident says, in plain words, why the definition would not be accepted as
  * a new save (lead ruling on D1); a tap-store failure fails the revert and
  * the incident says so; a lock not obtained within the wait does not stop
  * the revert (the incident says it wrote without the lock); no tap is ever
  * half-written.
  *
  * The incident runs end to end on fakes (no Mongo, Vault, object store or
  * code repository): the existing runner seams plus the ones below, which
  * the production revert body (the default `IncidentRunner.revertTap`) must
  * use for every read and write it makes:
  *
  * {{{
  * object TapAPIController {
  *   // visible to ai.datris.incident (today private[api])
  *   private[datris] case class TapSaveIO(...)            // unchanged fields
  * }
  * object IncidentRunner {
  *   /** (tapName, version) => the stored version snapshot of the tap, None
  *     * when there is none. Production: EntityVersionIO.get + Gson. */
  *   @volatile private[incident] var tapVersionLookup: (String, Int) => Option[TapConfig]
  *   /** The store reads and the versioned write of the revert, through the
  *     * tap save path. Production: TapAPIController.ProductionSaveIO. */
  *   @volatile private[incident] var revertSaveIO: TapAPIController.TapSaveIO
  *   /** How long the revert waits for CatalogOps' catalog lock before it
  *     * writes without it (the incident says so). */
  *   @volatile private[incident] var revertLockWaitMs: Long
  * }
  * }}}
  *
  * `revertTap` keeps its type `(String, String, Int) => Unit`; the existing
  * IncidentRunnerSpec fakes are unchanged. */
class IncidentRevertThroughSaveSpec extends AnyFunSuite with BeforeAndAfterEach {

    // ------------------------------------------------------------ environment

    private val env: DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        // No store configuration: anything that reaches a real store fails
        // instead of connecting.
        mongoDbConfig = null,
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false
    )

    private var savedValues: DatrisEnvironment = _
    private var savedScope: Option[String] = None

    override def beforeEach(): Unit = {
        savedValues = DatrisEnvironment.values
        DatrisEnvironment.values = env
        TenantContext.set(env)
        savedScope = sys.props.get(SecretNames.TapScopeProperty)
        sys.props(SecretNames.TapScopeProperty) = "tap"
    }

    override def afterEach(): Unit = {
        TenantContext.clear()
        DatrisEnvironment.values = savedValues
        savedScope match {
            case Some(v) => sys.props(SecretNames.TapScopeProperty) = v
            case None => sys.props -= SecretNames.TapScopeProperty
        }
    }

    private def jl(xs: String*): java.util.List[String] = new java.util.ArrayList[String](xs.asJava)

    // -------------------------------------------- incident store (as IncidentRunnerSpec)

    private class FakeStore extends IncidentIO.Store {
        val docs = new java.util.concurrent.ConcurrentHashMap[String, Document]()
        override def insert(i: Incident): Unit = { docs.put(i.id, i.toDocument); () }
        override def get(id: String): Option[Incident] = Option(docs.get(id)).map(Incident.fromDocument)
        override def list(state: Option[String], limit: Int): List[Incident] =
            docs.values().asScala.toList.map(Incident.fromDocument).filter(i =>
                state match {
                    case Some("open") => Incident.OpenStates.contains(i.state)
                    case Some(st) => i.state == st
                    case None => true
                }
            ).take(limit)
        override def transition(id: String, from: Set[String], to: String, sets: Seq[(String, Any)]): Boolean = synchronized {
            val d = docs.get(id)
            if (d == null || !from.contains(d.getString("state"))) false
            else {
                d.put("state", to)
                sets.foreach { case (k, v) => d.put(k, v.asInstanceOf[AnyRef]) }
                if (Incident.ClosedStates.contains(to)) {
                    d.put("closedAt", Instant.now().toString)
                    d.put("closedAtDate", java.util.Date.from(Instant.now()))
                }
                true
            }
        }
        override def appendStep(id: String, step: IncidentStep): Unit = synchronized {
            val d = docs.get(id)
            if (d != null) {
                val steps = Option(d.get("steps", classOf[java.util.List[Document]])).getOrElse {
                    val l = new java.util.ArrayList[Document](); d.put("steps", l); l
                }
                steps.add(Document.parse(step.toJson.toString))
            }
        }
        override def set(id: String, sets: Seq[(String, Any)]): Unit = synchronized {
            val d = docs.get(id)
            if (d != null) sets.foreach { case (k, v) => d.put(k, v.asInstanceOf[AnyRef]) }
        }
        override def incrementCounters(id: String, aiCalls: Int, actions: Int): Unit = synchronized {
            val d = docs.get(id)
            if (d != null) {
                d.put("aiCalls", Option(d.getInteger("aiCalls")).map(_.intValue()).getOrElse(0) + aiCalls: java.lang.Integer)
                d.put("actionsTaken", Option(d.getInteger("actionsTaken")).map(_.intValue()).getOrElse(0) + actions: java.lang.Integer)
            }
        }
        override def raw(id: String): Option[Document] = Option(docs.get(id))
    }

    private val t0 = Instant.parse("2026-10-16T10:00:00Z")
    private val q = Quantiles(1.0, 10.0, 20.0, 30.0, 99.0)
    private val baseline = RunProfile(
        runId = "run_base",
        source = "landed-sample",
        recordCount = 100L,
        columns = List(ColumnStats("id", "number", 0.0, 100L, Some(q), None, None)),
        dqFailures = 0,
        dqPassRate = 1.0,
        durationMs = 1000L,
        scriptSha = None,
        tapVersion = Some(3),
        pipelineVersion = None,
        sampledRows = 100L,
        computedAt = t0
    )
    private val evidence = IncidentEvidence(
        baseline = Some(baseline),
        candidate = None,
        real = None,
        diff = None,
        blastRadius = BlastRadius(Nil, Nil, Nil, Nil, None, None, None),
        thresholds = Thresholds.Default,
        checks = Nil,
        verdict = Thresholds.Pending,
        computedAt = t0
    )

    // --------------------------------------------------- tap store behind the save path

    /** Every versioned write the revert makes: (config, note, actor,
      * whether the catalog lock was held by the writer, when it was made). */
    private case class Write(config: TapConfig, note: String, actor: String, lockHeld: Boolean, at: Long = System.nanoTime())

    /** True when the calling thread's catalog lock is held: another thread
      * cannot take it within 150 ms. */
    private def catalogLockHeld(): Boolean = {
        val got = new CountDownLatch(1)
        val t = new Thread(() => CatalogOps.withCatalogLock { got.countDown() })
        t.setDaemon(true)
        t.start()
        !got.await(150, TimeUnit.MILLISECONDS)
    }

    /** In-memory taps, versions, pipelines, secret types and script objects;
      * the store reads and the write of the tap save path. */
    private class TapStore(
        initial: Seq[TapConfig],
        val versions: Map[(String, Int), TapConfig],
        pipelines: Seq[PipelineConfig] = Nil,
        secretTypes: Map[String, Option[String]] = Map.empty,
        scripts: Map[String, String] = Map.empty
    ) {
        val taps: mutable.Map[String, TapConfig] = mutable.LinkedHashMap(initial.map(t => t.name -> t): _*)
        val writes: mutable.Buffer[Write] = mutable.Buffer.empty
        @volatile var writeAttempts: Int = 0
        val calls: mutable.Buffer[String] = mutable.Buffer.empty
        val versionReads: mutable.Buffer[(String, Int)] = mutable.Buffer.empty
        @volatile var writeFailure: Option[String] = None
        @volatile var loadAllFailure: Option[String] = None
        @volatile var readPipelineFailure: Option[String] = None
        @volatile var readScriptFailure: Option[String] = None
        @volatile var readTapFailure: Option[String] = None

        /** Runs inside loadAll (after the revert has decided, before it writes). */
        @volatile var onLoadAll: () => Unit = () => ()

        private def scriptKey(t: TapConfig): String = Option(t.scriptPath).filter(_.nonEmpty).getOrElse(t.scriptRepoPath)
        private def record(s: String): Unit = calls.synchronized { calls += s; () }

        def tap: TapConfig = taps.synchronized(taps.get("prices").orNull)

        lazy val io: TapAPIController.TapSaveIO = TapAPIController.TapSaveIO(
            readTap = n => {
                record("readTap:" + n)
                readTapFailure.foreach(m => throw new RuntimeException(m))
                taps.synchronized(taps.get(n).orNull)
            },
            readPipeline = n => {
                record("readPipeline:" + n)
                readPipelineFailure.foreach(m => throw new RuntimeException(m))
                pipelines.find(_.name == n).orNull
            },
            secretType = n => { record("secretType:" + n); secretTypes.get(n) },
            scriptExists = t => { record("scriptExists:" + scriptKey(t)); scripts.contains(scriptKey(t)) },
            readScript = t => {
                record("readScript:" + scriptKey(t))
                readScriptFailure.foreach(m => throw new RuntimeException(m))
                scripts.get(scriptKey(t))
            },
            loadAll = () => {
                record("loadAll")
                loadAllFailure.foreach(m => throw new RuntimeException(m))
                onLoadAll()
                (taps.synchronized(taps.values.toList), pipelines)
            },
            // The script reference and secret rules' reads: the revert has no
            // caller, so it must never make them (recorded so a spec can say so).
            secretOwner = n => { record("secretOwner:" + n); None },
            repoPathPrefix = () => { record("repoPathPrefix"); "" },
            writeVersioned = (t, note, actor) => {
                writeAttempts += 1
                val held = catalogLockHeld()
                writeFailure.foreach(m => throw new RuntimeException(m))
                writes.synchronized { writes += Write(t, note, actor, held) }
                taps.synchronized {
                    val saved = t.copy(version = Option(taps.get(t.name).orNull).map(_.version + 1).getOrElse(1))
                    taps(t.name) = saved
                    saved
                }
            }
        )
    }

    /** The only place the spec touches the revert's new seams. */
    private def installRevertSeams(store: TapStore): Unit = {
        IncidentRunner.revertSaveIO = store.io
        IncidentRunner.tapVersionLookup = (n: String, v: Int) => {
            store.versionReads.synchronized { store.versionReads += ((n, v)) }
            store.versions.get((n, v))
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** The tap as it was before the agent acted: version 3, stored as the
      * version-3 snapshot and live when the incident opens. */
    /** Unique: the ordered-enable job registry is process-wide. */
    private val CatalogA = "revert-spec-catalog-a"

    private val pre: TapConfig = TapConfig(
        name = "prices",
        description = "pre-agent",
        targetPipeline = "orders",
        catalog = CatalogA,
        createdByKeyLabel = "owner",
        createdAt = "2026-01-01 00:00:00",
        updatedAt = "2026-11-01 00:00:00",
        scriptKind = "python",
        scriptStorage = "minio",
        scriptPath = "tap-scripts/prices_pre.py",
        packages = jl("requests"),
        tags = jl("prices"),
        source = "example source",
        dependsOn = jl(),
        inferredDependsOn = jl("stale"),
        enabled = true,
        version = 3,
        lastRunStatus = "failure",
        lastRunTime = "2026-11-21 00:00:00",
        lastRunRecordCount = 0,
        lastRunError = "KeyError: account_id",
        lastRunTrigger = "cron",
        lastRunRetrySafe = true,
        retryCount = 0,
        lastTestRunStatus = "success",
        lastTestRunTime = "2026-11-19 00:00:00",
        lastTestRunRecordCount = 100,
        lastTestRunDataType = "csv",
        lastTestRunColumns = jl("id"),
        lastTestRunScriptId = "id-pre"
    )

    private val scripts = Map("tap-scripts/prices_pre.py" -> "print('pre')", "tap-scripts/prices_agent.py" -> "print('agent')")

    /** The agent's change (update_tap with a script): a new script object,
      * one more package, a new description; version + 1. */
    private val agentChange: TapConfig => TapConfig = t =>
        t.copy(
            description = "agent fix",
            scriptPath = "tap-scripts/prices_agent.py",
            packages = jl("requests", "lxml"),
            version = t.version + 1
        )

    /** What the agent's verification run stamps: a failed manual run with
      * a retry count, so the revert's kept run state is observable. */
    private val failedRun: TapConfig => TapConfig = t =>
        t.copy(
            lastRunStatus = "failure",
            lastRunTime = "2026-11-22 10:00:00",
            lastRunRecordCount = 7,
            lastRunError = "agent fix failed",
            lastRunDataType = "json",
            lastRunColumns = jl("x"),
            lastRunTrigger = "manual",
            lastRunRetrySafe = false,
            retryCount = 2
        )

    private val CandidateScript = "def fetch():\n    return [{'id': 1}]\n"

    private def proposal: IncidentRunner.Proposal = {
        val upd = new JsonObject()
        upd.addProperty("name", "prices")
        upd.addProperty("script", CandidateScript)
        val test = new JsonObject()
        test.addProperty("name", "prices")
        val run = new JsonObject()
        run.addProperty("name", "prices")
        IncidentRunner.Proposal(
            "structural-script",
            "source renamed account_id",
            needsHuman = false,
            List(
                ProposedAction("update_tap", upd, "fix the field name"),
                ProposedAction("test_tap", test, "test the fix"),
                ProposedAction("run_tap", run, "land the fix")
            ),
            None
        )
    }

    private case class Call(tool: String, args: String)

    /** One incident run. */
    private class Ctx(val id: String, val incidents: FakeStore, val store: TapStore) {
        val calls: mutable.Buffer[Call] = mutable.Buffer.empty
        val webhooks: mutable.Buffer[JsonObject] = mutable.Buffer.empty

        /** The live tap just before verification failed (after the agent's
          * change, the person's change and the failed run). */
        @volatile var liveBeforeRevert: TapConfig = _
        def incident: Incident = incidents.get(id).getOrElse(fail("incident " + id + " vanished"))
        def steps: List[IncidentStep] = incident.steps
        def summaries: List[String] = steps.map(_.summary)
        def stepText(s: IncidentStep): String = s.summary + " " + s.detail.getOrElse("")
        def revertFailed: Option[IncidentStep] = steps.find(s => s.phase == "execute" && s.summary == "revert failed")
        def reverted: Boolean = summaries.contains("reverted tap to version 3")
    }

    /** Seed an open tap_failure incident on `prices`, run it with fakes. The
      * agent's update_tap applies [[agentChange]]; its run_tap applies
      * `personChange` (what a person did to the tap during the incident) and
      * `runResult`. `healthy = false` (default): the run leaves the tap
      * unhealthy, so verification fails and the runner reverts to version 3.
      * `fakeRevert`: install a fake revertTap instead of the production body
      * (the comparison case). */
    private def scenario[T](
        store: TapStore,
        personChange: TapConfig => TapConfig = identity,
        healthy: Boolean = false,
        fakeRevert: Boolean = false,
        install: Boolean = true,
        incidents: FakeStore = new FakeStore
    )(body: Ctx => T): T = {
        val ctx = new Ctx(Incident.newId(), incidents, store)
        incidents.insert(Incident(
            id = ctx.id,
            kind = Incident.KindTapFailure,
            resourceType = "tap",
            resourceName = "prices",
            openedAt = Instant.now(),
            state = Incident.Open,
            trigger = JsonParser.parseString("""{"error":"KeyError: account_id"}""").getAsJsonObject,
            steps = List(IncidentStep(Instant.now(), "open", "tap_failure signal on tap prices")),
            evidence = Some(evidence.toJson)
        ))
        val saved = (
            IncidentRunner.diagnoser,
            IncidentRunner.executor,
            IncidentRunner.runAsync,
            IncidentRunner.settingsOverride,
            IncidentRunner.tapLookup,
            IncidentRunner.revertTap,
            IncidentIO.store,
            IncidentEvidenceBuilder.realProfileOverride,
            IncidentRunner.tapRunLogs,
            IncidentRunner.stampTest,
            IncidentRunner.tapWrite,
            IncidentRunner.webhookSink
        )
        val savedSaveIO = IncidentRunner.revertSaveIO
        val savedVersionLookup = IncidentRunner.tapVersionLookup
        val savedLockWait = IncidentRunner.revertLockWaitMs
        IncidentIO.store = incidents
        IncidentRunner.runAsync = false
        IncidentRunner.settingsOverride = RecoverySettings(mode = RecoverySettings.Propose, verifyMode = RecoverySettings.Real)
        IncidentRunner.diagnoser = new IncidentRunner.Diagnoser {
            override def diagnose(incident: Incident, onAiCall: () => Unit): Either[String, IncidentRunner.Proposal] = {
                onAiCall()
                Right(proposal)
            }
        }
        IncidentRunner.executor = new IncidentRunner.ToolExecutor {
            override def call(tool: String, args: JsonObject, incidentId: String): ToolOutcome = {
                ctx.calls.synchronized { ctx.calls += Call(tool, args.toString) }
                tool match {
                    case "update_tap" | "create_tap" =>
                        store.taps.synchronized { store.taps("prices") = agentChangeOverride.getOrElse(agentChange)(store.taps("prices")) }
                        ToolOutcome.Ok("""{"message":"script updated"}""")
                    case "test_tap" => ToolOutcome.Ok("""{"mode":"test","recordCount":1}""")
                    case "run_tap" =>
                        store.taps.synchronized {
                            val ran =
                                if (healthy) store.taps("prices").copy(lastRunStatus = "success", lastRunRecordCount = 100)
                                else failedRun(store.taps("prices"))
                            store.taps("prices") = personChange(ran)
                            ctx.liveBeforeRevert = store.taps("prices")
                        }
                        ToolOutcome.Ok("""{"mode":"run","recordCount":100}""")
                    case _ => ToolOutcome.Ok("{}")
                }
            }
        }
        IncidentRunner.tapLookup = (name: String) => store.taps.synchronized(store.taps.get(name))
        if (fakeRevert) IncidentRunner.revertTap = (_: String, _: String, _: Int) => ()
        IncidentRunner.tapRunLogs = (_: String, _: String) => None
        IncidentRunner.stampTest = (_: String, _: IncidentRunner.RehearsalStamp) => ()
        IncidentRunner.tapWrite = (_: TapConfig) => ()
        IncidentRunner.webhookSink = (payload: String) => ctx.webhooks.synchronized { ctx.webhooks += JsonParser.parseString(payload).getAsJsonObject; () }
        // No real-run profile: judging one reads recent runs from the
        // run-lineage store, which a client built by an earlier suite would
        // wait 30 s on. Verification here is decided by the tap's health.
        IncidentEvidenceBuilder.realProfileOverride = (_: Incident, _: Option[RunProfile]) => None
        if (install) installRevertSeams(store)
        try body(ctx)
        finally {
            IncidentRunner.diagnoser = saved._1
            IncidentRunner.executor = saved._2
            IncidentRunner.runAsync = saved._3
            IncidentRunner.settingsOverride = saved._4
            IncidentRunner.tapLookup = saved._5
            IncidentRunner.revertTap = saved._6
            IncidentIO.store = saved._7
            IncidentEvidenceBuilder.realProfileOverride = saved._8
            IncidentRunner.tapRunLogs = saved._9
            IncidentRunner.stampTest = saved._10
            IncidentRunner.tapWrite = saved._11
            IncidentRunner.webhookSink = saved._12
            IncidentRunner.revertSaveIO = savedSaveIO
            IncidentRunner.tapVersionLookup = savedVersionLookup
            IncidentRunner.revertLockWaitMs = savedLockWait
        }
    }

    private def standardStore(live: TapConfig = pre, snap: TapConfig = pre, others: Seq[TapConfig] = Nil, extraScripts: Map[String, String] = Map.empty) =
        new TapStore(live +: others, Map(("prices", 3) -> snap), scripts = scripts ++ extraScripts)

    /** The pre-agent definition under the live tap's catalog, owner,
      * createdAt, enabled state, run, retry and test state, with the test
      * stamp cleared (it names the agent's script, not the reverted one). */
    private def expectedRevert(snap: TapConfig, live: TapConfig): TapConfig =
        snap.copy(
            catalog = live.catalog,
            createdAt = live.createdAt,
            createdByKeyLabel = live.createdByKeyLabel,
            enabled = live.enabled,
            lastRunStatus = live.lastRunStatus,
            lastRunTime = live.lastRunTime,
            lastRunRecordCount = live.lastRunRecordCount,
            lastRunError = live.lastRunError,
            lastRunDataType = live.lastRunDataType,
            lastRunColumns = live.lastRunColumns,
            lastRunTrigger = live.lastRunTrigger,
            lastRunRetrySafe = live.lastRunRetrySafe,
            retryCount = live.retryCount,
            lastTestRunStatus = live.lastTestRunStatus,
            lastTestRunTime = live.lastTestRunTime,
            lastTestRunRecordCount = live.lastTestRunRecordCount,
            lastTestRunError = live.lastTestRunError,
            lastTestRunDataType = live.lastTestRunDataType,
            lastTestRunColumns = live.lastTestRunColumns,
            lastTestRunScriptId = null
        )

    /** What v1.61.0's revert wrote (snapshot with the live createdAt, owner,
      * lastRun* status fields and lastTestRun* fields, stamp cleared). */
    private def v161Revert(snap: TapConfig, live: TapConfig): TapConfig =
        snap.copy(
            createdAt = live.createdAt,
            createdByKeyLabel = live.createdByKeyLabel,
            lastRunStatus = live.lastRunStatus,
            lastRunTime = live.lastRunTime,
            lastRunRecordCount = live.lastRunRecordCount,
            lastRunError = live.lastRunError,
            lastRunDataType = live.lastRunDataType,
            lastRunColumns = live.lastRunColumns,
            lastTestRunStatus = live.lastTestRunStatus,
            lastTestRunTime = live.lastTestRunTime,
            lastTestRunRecordCount = live.lastTestRunRecordCount,
            lastTestRunError = live.lastTestRunError,
            lastTestRunDataType = live.lastTestRunDataType,
            lastTestRunColumns = live.lastTestRunColumns,
            lastTestRunScriptId = null
        )

    /** Fields the save path stamps itself (and the store's version). */
    private def unstamped(t: TapConfig): TapConfig = t.copy(updatedAt = null, version = 0, inferredDependsOn = null)

    private def onlyWrite(c: Ctx): Write = {
        assert(c.store.writes.size == 1, "exactly one versioned write: " + c.store.writes + " / " + c.summaries)
        c.store.writes.head
    }

    private def assertAttribution(c: Ctx, w: Write): Unit = {
        assert(w.note == "incident " + c.id + ": reverted after failed verification", w.note)
        assert(w.actor == "recovery-agent", w.actor)
    }

    /** The store reads the revert made before the script reference rules,
      * for a tap with a tap secret and a built-in script, in order: the live
      * tap the revert decides on; the save checks (the secret's type, the
      * script's existence, the tap and pipeline list once, the script for
      * dependency inference); the live tap again under the catalog lock. */
    private val RevertReads: List[String] = List(
        "readTap:prices",
        "secretType:weather",
        "scriptExists:tap-scripts/prices_pre.py",
        "loadAll",
        "readScript:tap-scripts/prices_pre.py",
        "readTap:prices"
    )

    private def errorText(body: String): String = JsonParser.parseString(body).getAsJsonObject.get("error").getAsString

    // ================================================================ Acceptance 1 (D2)

    test(
        "bullet 1: the revert writes exactly the pre-agent definition, as one version noted 'incident <id>: reverted after failed verification' by recovery-agent"
    ) {
        val store = standardStore()
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.incident.state == Incident.Failed, c.incident.outcome + " / " + c.summaries)
            assert(c.reverted, c.summaries)
            assert(c.revertFailed.isEmpty, c.summaries)
            val w = onlyWrite(c)
            assertAttribution(c, w)
            assert(unstamped(w.config) == unstamped(expectedRevert(pre, c.liveBeforeRevert)), s"${w.config}")
            // Everything the agent's change touched is back.
            assert(w.config.scriptPath == pre.scriptPath && w.config.packages == pre.packages && w.config.description == pre.description, s"${w.config}")
            assert(w.config.lastTestRunScriptId == null)
            assert(store.versionReads.toList == List(("prices", 3)), store.versionReads)
        }
    }

    test("bullet 1: a tap a person moved from catalog A to B during the incident stays in B") {
        val store = standardStore()
        scenario(store, personChange = _.copy(catalog = "B")) { c =>
            IncidentRunner.runIncident(c.id)
            val w = onlyWrite(c)
            assert(w.config.catalog == "B", s"${w.config}")
            assert(store.tap.catalog == "B")
            assert(w.config.scriptPath == pre.scriptPath)
        }
    }

    test("bullet 1: a tap disabled during the incident stays disabled; one enabled during the incident stays enabled") {
        val disabled = standardStore()
        scenario(disabled, personChange = _.copy(enabled = false)) { c =>
            IncidentRunner.runIncident(c.id)
            assert(!onlyWrite(c).config.enabled, s"${c.store.writes}")
        }
        val off = pre.copy(enabled = false)
        val enabled = standardStore(live = off, snap = off)
        scenario(enabled, personChange = _.copy(enabled = true)) { c =>
            IncidentRunner.runIncident(c.id)
            assert(onlyWrite(c).config.enabled, s"${c.store.writes}")
        }
    }

    test("bullet 1: retryCount, lastRunTrigger, lastRunRetrySafe and the other run fields keep their live values") {
        val store = standardStore()
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            val w = onlyWrite(c).config
            val live = c.liveBeforeRevert
            assert((w.lastRunTrigger, w.lastRunRetrySafe, w.retryCount) == (live.lastRunTrigger, live.lastRunRetrySafe, live.retryCount), s"$w")
            assert((w.lastRunTrigger, w.lastRunRetrySafe, w.retryCount) == ("manual", false, 2), s"$w")
            assert(
                (w.lastRunStatus, w.lastRunTime, w.lastRunRecordCount, w.lastRunError, w.lastRunDataType, w.lastRunColumns) ==
                    (live.lastRunStatus, live.lastRunTime, live.lastRunRecordCount, live.lastRunError, live.lastRunDataType, live.lastRunColumns),
                s"$w"
            )
        }
    }

    test("bullet 1: test state changed during the incident is kept (stamp cleared); a changed owner is kept") {
        val store = standardStore()
        val person: TapConfig => TapConfig = _.copy(
            createdByKeyLabel = "new-owner",
            lastTestRunStatus = "failure",
            lastTestRunTime = "2026-11-22 09:00:00",
            lastTestRunRecordCount = 0,
            lastTestRunError = "test failed",
            lastTestRunDataType = "xml",
            lastTestRunColumns = jl("y"),
            lastTestRunScriptId = "id-agent"
        )
        scenario(store, personChange = person) { c =>
            IncidentRunner.runIncident(c.id)
            val w = onlyWrite(c).config
            assert(w.createdByKeyLabel == "new-owner", s"$w")
            assert(
                (w.lastTestRunStatus, w.lastTestRunTime, w.lastTestRunRecordCount, w.lastTestRunError, w.lastTestRunDataType, w.lastTestRunColumns) ==
                    ("failure", "2026-11-22 09:00:00", 0, "test failed", "xml", jl("y")),
                s"$w"
            )
            assert(w.lastTestRunScriptId == null, s"$w")
        }
    }

    // ===================================== accepted definition: through the save path

    test("an accepted pre-agent definition is saved through the save path: its checks ran and it stamped updatedAt and inferredDependsOn") {
        val withSecret = pre.copy(secretName = "weather")
        val store = new TapStore(Seq(withSecret), Map(("prices", 3) -> withSecret), secretTypes = Map("weather" -> Some("tap")), scripts = scripts)
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            val w = onlyWrite(c).config
            val calls = store.calls.toList
            assert(calls.contains("secretType:weather"), calls)
            assert(calls.contains("scriptExists:tap-scripts/prices_pre.py"), calls)
            assert(calls.contains("readScript:tap-scripts/prices_pre.py"), calls)
            assert(calls.contains("loadAll"), calls)
            assert(w.updatedAt != null && w.updatedAt != pre.updatedAt, "updatedAt is the revert time: " + w.updatedAt)
            assert(w.inferredDependsOn == jl(), "re-derived from the pre-agent script: " + w.inferredDependsOn)
            assert(c.steps.forall(s => !c.stepText(s).contains("would not")), c.summaries)
        }
    }

    test("the revert makes no store read it did not make before the script reference rules (secret owner, repository settings, a second list)") {
        val withSecret = pre.copy(secretName = "weather")
        val store = new TapStore(Seq(withSecret), Map(("prices", 3) -> withSecret), secretTypes = Map("weather" -> Some("tap")), scripts = scripts)
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.reverted, c.summaries)
            val calls = store.calls.toList
            assert(!calls.exists(x => x.startsWith("secretOwner") || x == "repoPathPrefix"), calls)
            assert(calls.count(_ == "loadAll") == 1, calls)
            assert(calls == RevertReads, calls)
        }
    }

    test("an accepted pre-agent definition on an untouched tap writes the same definition v1.61.0 wrote") {
        val store = standardStore()
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            val w = onlyWrite(c).config
            val live = c.liveBeforeRevert
            // Differences allowed by the story: the save path stamps updatedAt
            // and inferredDependsOn; retry state is the live tap's (D2).
            val v161 = v161Revert(pre, live).copy(lastRunTrigger = live.lastRunTrigger, lastRunRetrySafe = live.lastRunRetrySafe, retryCount = live.retryCount)
            assert(unstamped(w) == unstamped(v161), s"$w\n vs\n$v161")
        }
    }

    // ============================ Acceptance 2, adjusted for the D1 ruling (fallback)

    /** A pre-agent definition the save rules refuse: the revert still lands
      * with the definition and the D2 fields, under the lock, with the usual
      * note and actor; the incident carries `reason` in plain words; no
      * "revert failed" step. */
    private def refusedButLands(store: TapStore, reason: String, snap: TapConfig = pre): Unit =
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, "a refusal by the save rules does not fail the revert: " + c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assertAttribution(c, w)
            assert(w.lockHeld, "the fallback write is made under the catalog lock")
            assert(unstamped(w.config) == unstamped(expectedRevert(snap, c.liveBeforeRevert)), s"${w.config}")
            val note = c.steps.find(s => c.stepText(s).contains(reason))
            assert(note.isDefined, "the incident names the reason the save rules refused the definition: " + reason + "\n" + c.steps)
            assert(!c.stepText(note.get).contains("{\"error\""), "plain words, not a response body: " + note)
            assert(c.incident.state == Incident.Failed, c.incident.outcome)
        }

    test("bullet 2 (D1 ruling): a pre-agent secret that is no longer a tap secret still reverts and the incident says why") {
        val withSecret = pre.copy(secretName = "weather")
        val store = new TapStore(Seq(withSecret), Map(("prices", 3) -> withSecret), secretTypes = Map("weather" -> None), scripts = scripts)
        refusedButLands(store, SecretNames.tapSecretMessage("prices", "weather"), withSecret)
    }

    test("bullet 2 (D1 ruling): a pre-agent script object that is gone still reverts and the incident says why") {
        val store = new TapStore(Seq(pre), Map(("prices", 3) -> pre), scripts = scripts - "tap-scripts/prices_pre.py")
        refusedButLands(store, "Tap script not found in its storage backend.")
    }

    test("bullet 2 (D1 ruling): a pre-agent dependsOn naming a tap deleted since still reverts and the incident says why") {
        val withDep = pre.copy(dependsOn = jl("feeder"))
        // The agent's change cleared dependsOn (so the revert changes it back);
        // `feeder` was deleted during the incident.
        val store = new TapStore(Seq(withDep), Map(("prices", 3) -> withDep), scripts = scripts)
        val expectedReason = errorText(TapAPIController.dependencySaveProblem(expectedRevert(withDep, withDep), Nil).get.getBody)
        assert(expectedReason.contains("feeder"), expectedReason)
        scenarioWithAgent(store, t => agentChange(t).copy(dependsOn = jl())) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assertAttribution(c, w)
            assert(w.lockHeld)
            assert(w.config.dependsOn == jl("feeder"), s"${w.config}")
            assert(unstamped(w.config) == unstamped(expectedRevert(withDep, c.liveBeforeRevert)), s"${w.config}")
            val note = c.steps.find(s => c.stepText(s).contains(expectedReason))
            assert(note.isDefined, expectedReason + "\n" + c.steps)
            assert(!c.stepText(note.get).contains("{\"error\""), note)
        }
    }

    /** [[scenario]] with another agent change. */
    private def scenarioWithAgent[T](store: TapStore, change: TapConfig => TapConfig)(body: Ctx => T): T = {
        val savedExecutorChange = agentChangeOverride
        agentChangeOverride = Some(change)
        try scenario(store)(body)
        finally agentChangeOverride = savedExecutorChange
    }
    @volatile private var agentChangeOverride: Option[TapConfig => TapConfig] = None

    // ======================================================= Acceptance 3 (cron gate)

    test("bullet 3: a scheduled tap reverts without a test-before-cron refusal, and the saved tap has no lastTestRunScriptId") {
        // Code-repository taps: the script identity is the pinned commit, so
        // nothing is read from a repository (the store seam answers).
        val scheduled = pre.copy(
            scriptStorage = "github",
            scriptPath = null,
            scriptRepoPath = "taps/prices.py",
            scriptCommitSha = "aaa111",
            cronExpression = "0 0 * * * ?",
            lastTestRunStatus = "success",
            lastTestRunScriptId = "gh:aaa111"
        )
        val store = new TapStore(Seq(scheduled), Map(("prices", 3) -> scheduled), scripts = Map("taps/prices.py" -> "print('pre')"))
        // The agent stored a new commit; its rehearsal stamped that commit.
        scenarioWithAgent(store, t => t.copy(scriptCommitSha = "bbb222", lastTestRunScriptId = "gh:bbb222", version = t.version + 1)) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            assert(c.steps.forall(s => !c.stepText(s).contains("cannot be scheduled")), "no test-before-cron refusal: " + c.steps)
            val w = onlyWrite(c).config
            assert(w.scriptCommitSha == "aaa111" && w.cronExpression == "0 0 * * * ?", s"$w")
            assert(w.lastTestRunScriptId == null, s"$w")
            assert(store.tap.lastTestRunScriptId == null)
        }
    }

    // ============================== Acceptance 4 (lock, re-check, store failure)

    test("bullet 4: the revert is written under the catalog lock") {
        val store = standardStore()
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            assert(onlyWrite(c).lockHeld, "written while the catalog lock is held")
        }
    }

    test("bullet 4: a tap moved between the revert's decision and its write is never written with the decided catalog or enabled state") {
        val store = standardStore()
        scenario(store) { c =>
            // A person moves and disables the tap after the revert decided,
            // before it writes.
            store.onLoadAll = () => {
                store.onLoadAll = () => ()
                store.taps.synchronized { store.taps("prices") = store.taps("prices").copy(catalog = "Moved", enabled = false) }
            }
            IncidentRunner.runIncident(c.id)
            assert(store.calls.contains("loadAll"), "the revert ran the save path: " + store.calls)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            // Re-decided on the moved tap: its catalog and enabled state are kept.
            val w = onlyWrite(c)
            assert(w.config.catalog == "Moved" && !w.config.enabled, "never moved back: " + w.config)
            assert(w.config.scriptPath == pre.scriptPath, s"${w.config}")
            assert(w.lockHeld)
            assert(store.tap.catalog == "Moved" && !store.tap.enabled && store.tap.scriptPath == pre.scriptPath, s"${store.tap}")
        }
    }

    test("store failure: a write the store refuses fails the revert, the incident says so, nothing is written and no fallback write is tried") {
        val store = standardStore()
        store.writeFailure = Some("store down")
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            val st = c.revertFailed.getOrElse(fail("revert failed step: " + c.summaries))
            assert(st.detail.exists(_.contains("store down")), st)
            assert(!c.reverted, c.summaries)
            assert(store.writeAttempts == 1, "the failed write is not retried as a fallback: " + store.writeAttempts)
            assert(store.tap == c.liveBeforeRevert, "the tap is unchanged")
            assert(c.incident.state == Incident.Failed, c.incident.outcome)
        }
    }

    test("a tap and pipeline list that cannot be read during the save checks: the revert lands as it is and the incident says the list was unavailable") {
        val store = standardStore()
        store.loadAllFailure = Some("tap list unavailable")
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assertAttribution(c, w)
            assert(store.writeAttempts == 1)
            assert(unstamped(w.config) == unstamped(expectedRevert(pre, c.liveBeforeRevert)), s"${w.config}")
            val note = c.steps.find(_.summary == "restored definition could not be fully checked").getOrElse(fail("could-not-be-checked note: " + c.steps))
            val text = c.stepText(note)
            assert(text.contains("the tap and pipeline list could not be read") && text.contains("tap list unavailable"), text)
            assert(!c.steps.exists(s => c.stepText(s).contains("would not be accepted")), "no dependency refusal: " + c.steps)
            assert(c.incident.state == Incident.Failed, c.incident.outcome)
        }
    }

    test("store failure: the tap's own read failing fails the revert, the incident says so and nothing is written") {
        val store = standardStore()
        store.readTapFailure = Some("tap store down")
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            val st = c.revertFailed.getOrElse(fail("revert failed step: " + c.summaries))
            assert(st.detail.exists(_.contains("tap store down")), st)
            assert(store.writeAttempts == 0, "nothing written")
            assert(store.tap == c.liveBeforeRevert)
            assert(!c.reverted, c.summaries)
        }
    }

    test("a stored snapshot that cannot be read into a tap definition fails the revert with a plain message; nothing is written") {
        val store = new TapStore(Seq(pre), Map(("prices", 3) -> null), scripts = scripts)
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            val st = c.revertFailed.getOrElse(fail("revert failed step: " + c.summaries))
            assert(st.detail.contains("the stored version 3 of tap prices could not be read"), st)
            assert(store.writeAttempts == 0)
            assert(store.tap == c.liveBeforeRevert)
        }
    }

    test("a tap deleted between the revert's decision and its write is not written or re-created; the incident says it no longer exists") {
        val store = standardStore()
        scenario(store) { c =>
            store.onLoadAll = () => {
                store.onLoadAll = () => ()
                store.taps.synchronized { store.taps -= "prices" }
            }
            IncidentRunner.runIncident(c.id)
            val st = c.revertFailed.getOrElse(fail("revert failed step: " + c.summaries))
            assert(st.detail.exists(_.contains("tap prices no longer exists")), st)
            assert(store.writeAttempts == 0, "nothing written")
            assert(store.tap == null, "not re-created: " + store.tap)
            assert(!c.reverted, c.summaries)
        }
    }

    test("a script that cannot be read to infer dependencies: the revert lands with the stored inferred dependencies and the incident says so") {
        val store = standardStore()
        store.readScriptFailure = Some("object store timeout")
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assert(w.config.inferredDependsOn == pre.inferredDependsOn, s"${w.config}")
            assert(w.config.scriptPath == pre.scriptPath)
            val notes = c.steps.filter(_.summary == "restored definition could not be fully checked")
            assert(notes.size == 1, "one note lists everything that could not be checked: " + c.steps)
            val text = c.stepText(notes.head)
            assert(text.contains("infer the tap's dependencies") && text.contains("object store timeout"), text)
        }
    }

    test("a document tap whose target pipeline cannot be read: the revert lands and the incident says the pipeline could not be checked") {
        val doc = pre.copy(tapType = "document")
        val store = new TapStore(Seq(doc), Map(("prices", 3) -> doc), scripts = scripts)
        store.readPipelineFailure = Some("pipeline store timeout")
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assert(unstamped(w.config) == unstamped(expectedRevert(doc, c.liveBeforeRevert)), s"${w.config}")
            val note = c.steps.find(_.summary == "restored definition could not be fully checked").getOrElse(fail("note: " + c.steps))
            val text = c.stepText(note)
            assert(text.contains("the target pipeline 'orders' could not be read") && text.contains("pipeline store timeout"), text)
        }
    }

    test("a document tap targeting a document pipeline reverts cleanly with no note") {
        val doc = pre.copy(tapType = "document")
        val orders = PipelineConfig(
            name = "orders",
            source = Source(fileAttributes = FileAttributes(unstructuredAttributes = UnstructuredAttributes())),
            destination = Destination(qdrant = QdrantConfig("docs", null, null, null, null))
        )
        val store = new TapStore(Seq(doc), Map(("prices", 3) -> doc), pipelines = Seq(orders), scripts = scripts)
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assertAttribution(c, w)
            assert(unstamped(w.config) == unstamped(expectedRevert(doc, c.liveBeforeRevert)), s"${w.config}")
            assert(store.calls.contains("readPipeline:orders"), store.calls)
            assert(!c.steps.exists(s => s.summary.startsWith("restored definition")), c.summaries)
        }
    }

    test("an HTTP tap reverts cleanly to its pre-agent endpoint with no note") {
        val http = pre.copy(
            scriptKind = "http",
            endpointUrl = "https://source.example/prices",
            scriptStorage = null,
            scriptPath = null,
            packages = null
        )
        val store = new TapStore(Seq(http), Map(("prices", 3) -> http), scripts = scripts)
        scenarioWithAgent(store, t => t.copy(description = "agent fix", endpointUrl = "https://source.example/prices-v2", version = t.version + 1)) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assertAttribution(c, w)
            assert(w.config.endpointUrl == "https://source.example/prices" && w.config.description == "pre-agent", s"${w.config}")
            assert(unstamped(w.config) == unstamped(expectedRevert(http, c.liveBeforeRevert)), s"${w.config}")
            assert(!c.steps.exists(s => s.summary.startsWith("restored definition")), c.summaries)
        }
    }

    test("a long refusal keeps its closing instruction within the note's 500 characters") {
        val longName = "feeder_" + ("x" * 700)
        val withDep = pre.copy(dependsOn = jl(longName))
        val store = new TapStore(Seq(withDep), Map(("prices", 3) -> withDep), scripts = scripts)
        scenarioWithAgent(store, t => agentChange(t).copy(dependsOn = jl())) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val note = c.steps.find(_.summary == "restored definition would not be accepted as a new save").getOrElse(fail("refusal note: " + c.steps))
            val detail = note.detail.getOrElse("")
            assert(detail.length <= 500, detail.length)
            assert(detail.endsWith("The definition from before the agent's change was restored. Correct it and save the tap."), detail)
            assert(detail.contains("feeder_x"), detail)
        }
    }

    test("a note that cannot be recorded after the write does not turn a landed revert into a failed one") {
        val store = standardStore()
        val incidents = new FakeStore {
            override def appendStep(id: String, step: IncidentStep): Unit =
                if (step.summary == "revert written without the catalog lock") throw new RuntimeException("incident store hiccup")
                else super.appendStep(id, step)
        }
        val audited = mutable.Buffer.empty[ai.datris.audit.AuditEntry]
        val savedSink = ai.datris.audit.AuditLog.sinkOverride
        DatrisEnvironment.values = env.copy(useAuditLog = true, auditLogTableName = "revert-spec-audit")
        ai.datris.audit.AuditLog.sinkOverride = (e: ai.datris.audit.AuditEntry) => audited.synchronized { audited += e; () }
        try scenario(store, incidents = incidents) { c =>
                IncidentRunner.revertLockWaitMs = 300L
                val release = new CountDownLatch(1)
                holdLock(release)
                try runBounded(c, 20000L)
                finally release.countDown()
                assert(store.writeAttempts == 1)
                assert(c.revertFailed.isEmpty, c.summaries)
                assert(c.reverted, c.summaries)
                assert(c.incident.state == Incident.Failed, c.incident.outcome)
                assert(c.incident.outcome.contains("verification failed after actions — definition reverted"), c.incident.outcome)
                val reverts = audited.synchronized(audited.toList).filter(e => e.category == "incident" && e.action == "revert")
                assert(reverts.size == 1 && reverts.head.resourceName.contains("prices"), audited)
            }
        finally {
            ai.datris.audit.AuditLog.sinkOverride = savedSink
            DatrisEnvironment.values = env
        }
    }

    test("no stored snapshot: the revert fails with today's message and nothing is written") {
        val store = new TapStore(Seq(pre), Map.empty, scripts = scripts)
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            val st = c.revertFailed.getOrElse(fail("revert failed step: " + c.summaries))
            assert(st.detail.exists(_.contains("no stored snapshot of tap prices version 3")), st)
            assert(store.writeAttempts == 0)
            assert(store.tap == c.liveBeforeRevert)
        }
    }

    // ================================================================= D3 (lock wait)

    /** Hold the catalog lock on another thread until `release` counts down. */
    private def holdLock(release: CountDownLatch): CountDownLatch = {
        val held = new CountDownLatch(1)
        val t = new Thread(() =>
            CatalogOps.withCatalogLock {
                held.countDown()
                release.await(60, TimeUnit.SECONDS)
                ()
            }
        )
        t.setDaemon(true)
        t.start()
        assert(held.await(10, TimeUnit.SECONDS), "lock holder started")
        held
    }

    /** Run the incident on another thread; fail (instead of hanging) when it
      * does not finish within `ms`. */
    private def runBounded(c: Ctx, ms: Long): Unit = {
        val done = new CountDownLatch(1)
        val err = new java.util.concurrent.atomic.AtomicReference[Throwable]()
        val t = new Thread(() =>
            try IncidentRunner.runIncident(c.id)
            catch { case e: Throwable => err.set(e) }
            finally done.countDown()
        )
        t.setDaemon(true)
        t.start()
        assert(done.await(ms, TimeUnit.MILLISECONDS), "the incident did not finish within " + ms + " ms (the revert hung): " + c.summaries)
        if (err.get != null) throw err.get
    }

    test("D3: the revert waits for a catalog lock held by another operation, then writes") {
        val store = standardStore()
        scenario(store) { c =>
            IncidentRunner.revertLockWaitMs = 30000L
            val release = new CountDownLatch(1)
            holdLock(release)
            @volatile var releasedAt = 0L
            val releaser = new Thread(() => {
                Thread.sleep(400)
                releasedAt = System.nanoTime()
                release.countDown()
            })
            releaser.setDaemon(true)
            releaser.start()
            try runBounded(c, 20000L)
            finally release.countDown()
            assert(c.reverted, c.summaries)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(!c.summaries.contains("revert written without the catalog lock"), c.summaries)
            val w = onlyWrite(c)
            assert(w.lockHeld)
            assert(releasedAt > 0L && w.at > releasedAt, "the write came after the other operation released the lock")
        }
    }

    test("lock timeout: after the wait, the revert lands and the incident says it did not hold the lock; it does not hang") {
        val store = standardStore()
        scenario(store) { c =>
            IncidentRunner.revertLockWaitMs = 300L
            val release = new CountDownLatch(1)
            holdLock(release)
            try runBounded(c, 20000L)
            finally release.countDown()
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assertAttribution(c, w)
            assert(store.writeAttempts == 1, "one write")
            assert(unstamped(w.config) == unstamped(expectedRevert(pre, c.liveBeforeRevert)), s"${w.config}")
            val note = c.steps.find(s => c.stepText(s).toLowerCase.contains("lock"))
            assert(note.isDefined, "names the lock: " + c.steps)
            assert(c.stepText(note.get).contains("Could not wait for a catalog operation in progress"), note)
            assert(c.incident.state == Incident.Failed, c.incident.outcome)
        }
    }

    test("a tap that changes again after the revert decided a second time is written on a third read, keeping that read's state, and the incident says so") {
        val store = standardStore()
        scenario(store) { c =>
            // Every check moves the tap: the decision is never current at the write.
            val moves = new java.util.concurrent.atomic.AtomicInteger(0)
            store.onLoadAll = () => {
                val n = moves.incrementAndGet()
                store.taps.synchronized {
                    store.taps("prices") = store.taps("prices").copy(catalog = "Moved" + n, enabled = n % 2 == 0, createdByKeyLabel = "owner" + n)
                }
            }
            IncidentRunner.runIncident(c.id)
            assert(moves.get == 2, "decided twice: " + moves.get)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assertAttribution(c, w)
            assert(w.lockHeld)
            assert((w.config.catalog, w.config.enabled, w.config.createdByKeyLabel) == ("Moved2", true, "owner2"), s"${w.config}")
            assert(w.config.scriptPath == pre.scriptPath && w.config.description == pre.description, s"${w.config}")
            assert(c.summaries.contains("tap was being changed while it was reverted"), c.summaries)
            assert(store.tap.catalog == "Moved2" && store.tap.scriptPath == pre.scriptPath, s"${store.tap}")
        }
    }

    test("lock timeout combined with a change: the revert written without the lock carries the tap's fresh state") {
        val store = standardStore()
        scenario(store) { c =>
            IncidentRunner.revertLockWaitMs = 300L
            store.onLoadAll = () => {
                store.onLoadAll = () => ()
                store.taps.synchronized {
                    store.taps("prices") =
                        store.taps("prices").copy(catalog = "Moved", lastRunStatus = "running", lastRunTime = "2026-11-22 11:00:00", retryCount = 5)
                }
            }
            val release = new CountDownLatch(1)
            holdLock(release)
            try runBounded(c, 20000L)
            finally release.countDown()
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assert(store.writeAttempts == 1)
            assert(
                (w.config.catalog, w.config.lastRunStatus, w.config.lastRunTime, w.config.retryCount) == ("Moved", "running", "2026-11-22 11:00:00", 5),
                s"${w.config}"
            )
            assert(w.config.enabled == c.liveBeforeRevert.enabled && w.config.scriptPath == pre.scriptPath, s"${w.config}")
            assert(c.summaries.contains("revert written without the catalog lock"), c.summaries)
        }
    }

    test("a secret store that does not answer is a check that could not be made: the revert lands and the incident says so") {
        val withSecret = pre.copy(secretName = "weather")
        val store = new TapStore(Seq(withSecret), Map(("prices", 3) -> withSecret), scripts = scripts) {
            override lazy val io: TapAPIController.TapSaveIO =
                TapAPIController.TapSaveIO(
                    readTap = n => taps.synchronized(taps.get(n).orNull),
                    readPipeline = _ => null,
                    secretType = _ => throw new RuntimeException("vault sealed"),
                    scriptExists = _ => throw new RuntimeException("object store timeout"),
                    readScript = _ => None,
                    loadAll = () => (taps.synchronized(taps.values.toList), Nil),
                    writeVersioned = (t, note, actor) => {
                        writeAttempts += 1
                        writes.synchronized { writes += Write(t, note, actor, catalogLockHeld()) }
                        taps.synchronized { val s = t.copy(version = taps(t.name).version + 1); taps(t.name) = s; s }
                    }
                )
        }
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.revertFailed.isEmpty, c.summaries)
            assert(c.reverted, c.summaries)
            val w = onlyWrite(c)
            assertAttribution(c, w)
            assert(unstamped(w.config) == unstamped(expectedRevert(withSecret, c.liveBeforeRevert)), s"${w.config}")
            val text = c.steps.map(c.stepText).mkString("\n")
            assert(text.contains("could not be fully checked"), text)
            assert(text.contains("vault sealed") && text.contains("object store timeout"), text)
            assert(!text.contains("not a tap secret") && !text.contains("which is a platform secret"), text)
            assert(!text.contains("Tap script not found"), text)
            assert(!text.contains("would not be accepted"), text)
        }
    }

    test("D3: an ordered-enable job active for the tap's catalog does not stop the revert") {
        val store = standardStore()
        val release = new CountDownLatch(1)
        val jobTap = TapConfig(
            name = "other",
            description = "d",
            targetPipeline = null,
            catalog = CatalogA,
            enabled = false,
            scriptKind = "http",
            endpointUrl = "https://source.example/other"
        )
        val deps = new CatalogEnableJob.Deps {
            override def read(name: String): TapConfig = if (name == "other") jobTap else null
            override def test(tap: TapConfig): TapScriptResult = {
                release.await(60, TimeUnit.SECONDS)
                TapScriptResult(staged = null, recordCount = 0, error = "stopped by the spec", publisherToken = null)
            }
            override def enable(tap: TapConfig, note: String, actor: String): Unit = fail("no enable expected")
            override def run(tap: TapConfig): TapScriptResult = fail("no run expected")
            override def rollup(publisherToken: String): PipelineStatusRollup = fail("no rollup expected")
            override def pollIntervalMs: Long = 10L
            override def waitTimeoutMs: Long = 1000L
        }
        val job = CatalogEnableJob.start(CatalogA, "spec-actor", TapDependencies.order(Seq(jobTap), (_: TapConfig) => None), deps) match {
            case Right(j) => j
            case Left(e) => fail("job not started: " + e)
        }
        try {
            assert(CatalogEnableJob.activeFor(CatalogA))
            scenario(store) { c =>
                IncidentRunner.revertLockWaitMs = 30000L
                runBounded(c, 20000L)
                assert(CatalogEnableJob.activeFor(CatalogA), "the job was still running during the revert")
                assert(c.reverted, c.summaries)
                assert(c.revertFailed.isEmpty, c.summaries)
                assert(onlyWrite(c).config.scriptPath == pre.scriptPath)
            }
        } finally {
            release.countDown()
            val deadline = System.currentTimeMillis() + 10000L
            while (job.finishedAt.isEmpty && System.currentTimeMillis() < deadline) Thread.sleep(10)
        }
    }

    // ============================================================ unchanged guards

    test("unchanged: an incident whose verification passes never reads a version or writes through the revert") {
        val store = standardStore()
        scenario(store, healthy = true) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.incident.state == Incident.Resolved, c.incident.outcome + " / " + c.summaries)
            assert(store.versionReads.isEmpty, store.versionReads)
            assert(store.writeAttempts == 0)
            assert(store.calls.isEmpty, "the revert's save path was never entered: " + store.calls)
        }
    }

    test("unchanged: the agent's forward changes (script save, test run, tap run) go to the executor exactly as proposed") {
        val store = standardStore()
        scenario(store) { c =>
            IncidentRunner.runIncident(c.id)
            val mutating = c.calls.filter(k => Set("update_tap", "create_tap", "test_tap", "run_tap").contains(k.tool)).toList
            assert(mutating == proposal.actions.map(a => Call(a.tool, a.args.toString)), mutating)
            // The only write through the save seam is the revert's.
            assert(store.writes.size == 1, store.writes)
        }
    }

    test("unchanged: a pipeline incident's script restore never touches the tap revert") {
        val store = standardStore()
        val incidents = new FakeStore
        val id = Incident.newId()
        incidents.insert(Incident(
            id = id,
            kind = Incident.KindPipelineFailure,
            resourceType = "pipeline",
            resourceName = "orders",
            openedAt = Instant.now(),
            state = Incident.Open,
            trigger = JsonParser.parseString("""{"pipelineToken":"tok_1","error":"script failed"}""").getAsJsonObject,
            steps = List(IncidentStep(Instant.now(), "open", "pipeline_failure signal on pipeline orders")),
            evidence = Some(evidence.toJson)
        ))
        val latest = mutable.Map("transformation" -> 3)
        val restores = mutable.Buffer[(String, String, Int, String)]()
        val saved = (
            IncidentRunner.diagnoser,
            IncidentRunner.executor,
            IncidentRunner.runAsync,
            IncidentRunner.settingsOverride,
            IncidentIO.store,
            IncidentRunner.scriptLatestVersion,
            IncidentRunner.restoreScript,
            IncidentRunner.tapLookup,
            IncidentRunner.webhookSink
        )
        val savedSaveIO = IncidentRunner.revertSaveIO
        val savedVersionLookup = IncidentRunner.tapVersionLookup
        try {
            IncidentIO.store = incidents
            IncidentRunner.runAsync = false
            IncidentRunner.settingsOverride = RecoverySettings(mode = RecoverySettings.Propose)
            IncidentRunner.webhookSink = (_: String) => ()
            IncidentRunner.tapLookup = (name: String) => store.taps.synchronized(store.taps.get(name))
            val args = new JsonObject()
            args.addProperty("name", "orders")
            args.addProperty("kind", "transformation")
            IncidentRunner.diagnoser = new IncidentRunner.Diagnoser {
                override def diagnose(incident: Incident, onAiCall: () => Unit): Either[String, IncidentRunner.Proposal] =
                    Right(IncidentRunner.Proposal(
                        "structural-script",
                        "s",
                        needsHuman = false,
                        List(ProposedAction("regenerate_pipeline_scripts", args, "p")),
                        None
                    ))
            }
            IncidentRunner.executor = new IncidentRunner.ToolExecutor {
                override def call(tool: String, a: JsonObject, incidentId: String): ToolOutcome = tool match {
                    case "regenerate_pipeline_scripts" =>
                        latest("transformation") = 4
                        ToolOutcome.Failed("regenerate failed after storing")
                    case _ => ToolOutcome.Ok("{}")
                }
            }
            IncidentRunner.scriptLatestVersion = (_: String, k: String) => latest.getOrElse(k, 0)
            IncidentRunner.restoreScript = (p: String, k: String, v: Int, actor: String) => { restores += ((p, k, v, actor)); Right(()) }
            installRevertSeams(store)
            IncidentRunner.runIncident(id)
            val i = incidents.get(id).get
            assert(i.state == Incident.Failed, i.outcome + " / " + i.steps.map(_.summary))
            assert(restores.toList == List(("orders", "transformation", 3, "recovery-agent")), restores)
            assert(store.versionReads.isEmpty && store.calls.isEmpty && store.writeAttempts == 0, store.calls)
        } finally {
            IncidentRunner.diagnoser = saved._1
            IncidentRunner.executor = saved._2
            IncidentRunner.runAsync = saved._3
            IncidentRunner.settingsOverride = saved._4
            IncidentIO.store = saved._5
            IncidentRunner.scriptLatestVersion = saved._6
            IncidentRunner.restoreScript = saved._7
            IncidentRunner.tapLookup = saved._8
            IncidentRunner.webhookSink = saved._9
            IncidentRunner.revertSaveIO = savedSaveIO
            IncidentRunner.tapVersionLookup = savedVersionLookup
        }
    }

    test("unchanged: a successful revert leaves the incident's states, steps and webhook payloads as a revert that returns normally does today") {
        def observe(fake: Boolean): (String, Option[String], List[(String, String)], List[(String, String)]) = {
            val store = standardStore()
            scenario(store, fakeRevert = fake) { c =>
                IncidentRunner.runIncident(c.id)
                val i = c.incident
                val hooks = c.webhooks.toList.map(o => (o.get("event").getAsString, Option(o.get("detail")).map(_.getAsString).getOrElse("")))
                (i.state, i.outcome, i.steps.map(s => (s.phase, s.summary)), hooks)
            }
        }
        val today = observe(fake = true)
        val now = observe(fake = false)
        assert(now == today, s"\n$now\n vs\n$today")
    }

    // ========================================================= Acceptance 6

    test("bullet 6: the incident runner makes no direct versioned tap write") {
        val rel = "src/main/scala/ai/datris/incident"
        val dir = Seq(java.nio.file.Paths.get(rel), java.nio.file.Paths.get("datrisserver", rel))
            .find(java.nio.file.Files.isDirectory(_)).getOrElse(fail(rel + " not found"))
        val hits = java.nio.file.Files.list(dir).iterator().asScala.toList
            .filter(_.toString.endsWith(".scala"))
            .filter(p => new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8).contains("TapConfigIO.writeVersioned"))
        assert(hits.isEmpty, hits)
    }
}
