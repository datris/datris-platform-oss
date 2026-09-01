package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.model.{DatrisEnvironment, TapConfig}
import ai.datris.policy.{PendingAction, PendingActionIO, PolicyIO, RecoverySettings}
import ai.datris.util._
import com.google.gson.{Gson, JsonObject, JsonParser}
import io.micrometer.core.instrument.Metrics
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

/** The closed recovery loop: observe → diagnose → propose → gate → execute
  * → verify → record.
  *
  * The LLM proposes, the code decides. Diagnosis runs the shared Ops-agent
  * judgment (OpsAgentPrompt + AgentLoop) headless with READ-ONLY tools and
  * must end in a JSON proposal; everything after that — which actions are
  * executable, the policy gate, limits, verification, revert, cooldown —
  * is deterministic Scala that no prompt can talk past. Every call the
  * runner makes is authenticated as the `recovery-agent` key and tagged
  * with the incident id, so the audit log is the incident's ledger. */
object IncidentRunner {

    private val logger = LoggerFactory.getLogger(getClass)
    private val gson = new Gson()

    /** Tools the diagnosis phase may call — reads only. A mutation can never
      * happen during diagnosis because the model never sees the tool. */
    val DiagnosisTools: Set[String] = Set(
        "get_tap",
        "get_tap_logs",
        "get_tap_state",
        "get_tap_ledger",
        "list_taps",
        "get_pipeline",
        "list_pipelines",
        "get_pipeline_status",
        "get_job_status",
        "list_tap_versions",
        "get_tap_version",
        "diff_tap_versions",
        "get_agent_policy",
        "check_service_health",
        "get_version"
    )

    /** Actions the runner will execute from a proposal. Anything else —
      * deletes, schema migrations, secret changes — is recorded but never
      * run; those need a human. */
    /** `create_tap` is here because it is the platform's documented path for
      * replacing a tap's SCRIPT (update_tap deliberately cannot change
      * scripts); the name-bound filter pins it to the incident's own tap, so
      * it can never create a new one. */
    val ExecutableTools: Set[String] = Set("run_tap", "test_tap", "update_tap", "create_tap")

    case class ProposedAction(tool: String, args: JsonObject, purpose: String)
    case class Proposal(
        classification: String,
        summary: String,
        needsHuman: Boolean,
        actions: List[ProposedAction],
        learnNote: Option[String]
    )

    sealed trait ToolOutcome
    object ToolOutcome {
        case class Ok(text: String) extends ToolOutcome
        case class PendingApproval(approvalId: String) extends ToolOutcome
        case class Denied(message: String) extends ToolOutcome
        case class Failed(message: String) extends ToolOutcome
    }

    /** Seams for IncidentRunnerSpec: the spec fakes both. */
    trait Diagnoser {

        /** Returns Left(error) or Right(proposal). Also reports how many LLM
          * iterations were used, via the callback, for the aiCalls budget. */
        def diagnose(incident: Incident, onAiCall: () => Unit): Either[String, Proposal]
    }
    trait ToolExecutor {
        def call(tool: String, args: JsonObject, incidentId: String): ToolOutcome
    }

    @volatile var diagnoser: Diagnoser = RealDiagnoser
    @volatile var executor: ToolExecutor = RealExecutor

    /** Test seam: specs run incidents synchronously. */
    @volatile var runAsync: Boolean = true

    private lazy val pool = Executors.newFixedThreadPool(
        2,
        (r: Runnable) => {
            val t = new Thread(r, "incident-runner-" + System.nanoTime())
            t.setDaemon(true)
            t
        }
    )

    def enabled: Boolean = {
        val v = DatrisEnvironment.values
        v != null && v.recoveryAgentEnabled && PolicyIO.enabled
    }

    private[incident] def settings: RecoverySettings =
        try PolicyIO.current.recovery
        catch { case _: Exception => RecoverySettings() }

    // ------------------------------------------------------------------
    // Open
    // ------------------------------------------------------------------

    /** Open an incident for a signal, applying every guard. Returns the id
      * when one was opened. Never throws. */
    def open(kind: String, resourceType: String, resourceName: String, trigger: JsonObject): Option[String] = {
        try {
            if (!enabled) return None
            val mode = PolicyIO.current.effectiveRecoveryMode(resourceType, resourceName)
            if (mode == RecoverySettings.Off) return None

            // One open incident per resource — a repeat signal is a step, not a new incident.
            IncidentIO.openFor(resourceType, resourceName) match {
                case Some(existing) =>
                    IncidentIO.appendStep(existing.id, IncidentStep(Instant.now(), "open", "signal repeated: " + kind, Some(trigger.toString.take(500))))
                    return None
                case None =>
            }

            // Cooldown after a failed/abandoned attempt so the loop cannot thrash.
            val cooldownUntil = IncidentIO.lastClosedAt(resourceType, resourceName)
                .map(_.plus(settings.cooldownHours.toLong, ChronoUnit.HOURS))
            if (cooldownUntil.exists(_.isAfter(Instant.now()))) {
                logger.info("incident not opened for " + resourceType + ":" + resourceName + " — in cooldown until " + cooldownUntil.get)
                return None
            }

            if (IncidentIO.countOpen() >= settings.maxOpenIncidents) {
                logger.warn("incident not opened for " + resourceType + ":" + resourceName + " — maxOpenIncidents reached")
                return None
            }

            // The [RECOVERED] rule as code: a FAILURE incident is never opened
            // for a resource whose latest run is healthy — the platform's own
            // retry already fixed it. Stale and volume signals are exempt: a
            // stale tap's last run typically succeeded, that's the point.
            if (kind == Incident.KindTapFailure && resourceType == "tap" && isTapHealthy(resourceName)) return None

            val incident = Incident(
                id = Incident.newId(),
                kind = kind,
                resourceType = resourceType,
                resourceName = resourceName,
                openedAt = Instant.now(),
                state = Incident.Open,
                trigger = trigger,
                steps = List(IncidentStep(Instant.now(), "open", kind + " signal on " + resourceType + " " + resourceName))
            )
            IncidentIO.insert(incident)
            AuditLog.system("incident", "open", resourceType, resourceName, metadata = md("incidentId" -> incident.id, "kind" -> kind))
            Metrics.counter("datris_incidents_opened_total", "kind", kind).increment()
            webhook("open", incident.id, kind, resourceType, resourceName, None)
            logger.info("incident " + incident.id + " opened: " + kind + " on " + resourceType + " " + resourceName + " (mode=" + mode + ")")
            submit(incident.id)
            Some(incident.id)
        } catch {
            case e: Exception =>
                logger.warn("incident open failed for " + resourceType + ":" + resourceName + ": " + e.getMessage)
                None
        }
    }

    private def submit(id: String): Unit =
        if (runAsync) { pool.submit(new Runnable { override def run(): Unit = runIncident(id) }); () }
        else runIncident(id)

    private def isTapHealthy(name: String): Boolean =
        try {
            val t = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)
            t != null && Option(t.lastRunStatus).map(_.toLowerCase).exists(s => s == "success" || s == "no_records")
        } catch {
            case _: Exception => false
        }

    // ------------------------------------------------------------------
    // The loop
    // ------------------------------------------------------------------

    def runIncident(id: String): Unit = {
        try {
            val incident = IncidentIO.get(id).getOrElse(return
            )
            if (!incident.isOpen) return
            if (!IncidentIO.transition(id, Set(Incident.Open), Incident.Diagnosing)) return

            val s = settings
            var aiCalls = 0
            val onAiCall = () => { aiCalls += 1; IncidentIO.incrementCounters(id, aiCalls = 1) }

            diagnoser.diagnose(incident, onAiCall) match {
                case Left(err) =>
                    step(id, "diagnose", "diagnosis failed", Some(err))
                    close(id, Incident.Failed, "diagnosis failed: " + clip(err))
                case Right(p) =>
                    step(id, "diagnose", "classified " + p.classification, Some(p.summary))
                    val filtered = filterActions(incident, p)
                    IncidentIO.set(
                        id,
                        "classification" -> p.classification,
                        "proposal" -> org.bson.Document.parse(proposalJson(p, filtered).toString)
                    )
                    if (aiCalls > s.maxAiCallsPerIncident) {
                        close(id, Incident.Abandoned, "AI-call limit exceeded during diagnosis (" + aiCalls + " > " + s.maxAiCallsPerIncident + ")")
                    } else if (incident.kind == Incident.KindVolume) {
                        // Diagnosis only in v1: the value is the triage text.
                        step(id, "close", "volume anomaly triaged — no automatic action taken")
                        close(id, Incident.Resolved, "triaged: " + p.summary)
                    } else if (p.needsHuman || filtered.isEmpty) {
                        IncidentIO.transition(id, Set(Incident.Diagnosing), Incident.AwaitingApproval)
                        step(id, "propose", "needs a human", Some(p.summary))
                        webhook("awaiting_approval", id, incident.kind, incident.resourceType, incident.resourceName, Some(p.summary))
                    } else {
                        IncidentIO.transition(id, Set(Incident.Diagnosing), Incident.Proposed)
                        step(id, "propose", filtered.size + " action(s): " + filtered.map(_.tool).mkString(", "), Some(p.summary))
                        // Remember the version to revert to before any mutation.
                        if (incident.resourceType == "tap")
                            currentTapVersion(incident.resourceName).foreach(v => IncidentIO.set(id, "revertToVersion" -> (v: java.lang.Integer)))
                        IncidentIO.set(id, "nextActionIndex" -> (0: java.lang.Integer))
                        executeFrom(id, 0)
                    }
            }
        } catch {
            case e: Exception =>
                logger.error("incident " + id + " runner error: " + e.getMessage, e)
                try close(id, Incident.Failed, "runner error: " + clip(e.getMessage))
                catch { case _: Exception => }
        }
    }

    /** Cap and sanitize the proposal: executable tools only, at most
      * maxActionsPerIncident, stale-kind limited to a single run. */
    private[incident] def filterActions(incident: Incident, p: Proposal): List[ProposedAction] = {
        // Executable, and pinned to the incident's own resource: an action
        // naming any other tap (or none) is dropped, whatever the model said.
        val executable = p.actions.filter(a =>
            ExecutableTools.contains(a.tool) &&
                Option(a.args).exists(o =>
                    o.has("name") && o.get("name").isJsonPrimitive && o.get("name").getAsString == incident.resourceName
                )
        )
        val kindLimited =
            if (incident.kind == Incident.KindStale) executable.filter(_.tool == "run_tap").take(1)
            else executable
        kindLimited.take(settings.maxActionsPerIncident)
    }

    /** Execute proposed actions from an index (resumable after approvals). */
    def executeFrom(id: String, fromIndex: Int): Unit = {
        val incident = IncidentIO.get(id).getOrElse(return
        )
        val actions = parseStoredActions(incident)
        val s = settings
        IncidentIO.transition(id, Set(Incident.Proposed, Incident.AwaitingApproval, Incident.Executing), Incident.Executing)

        var i = fromIndex
        var lastRealRun: Option[String] = None
        while (i < actions.length) {
            if (runtimeExceeded(incident)) { close(id, Incident.Abandoned, "runtime limit reached during execution"); return }
            val a = actions(i)
            executor.call(a.tool, a.args, id) match {
                case ToolOutcome.Ok(text) =>
                    IncidentIO.incrementCounters(id, actions = 1)
                    step(id, "execute", a.tool + " ok — " + a.purpose, Some(clip(text)))
                    lastRealRun = if (a.tool == "run_tap") Some(text) else None
                    i += 1
                    IncidentIO.set(id, "nextActionIndex" -> (i: java.lang.Integer))
                case ToolOutcome.PendingApproval(approvalId) =>
                    IncidentIO.set(id, "awaitingApprovalIds" -> java.util.Arrays.asList(approvalId), "nextActionIndex" -> (i: java.lang.Integer))
                    IncidentIO.transition(id, Set(Incident.Executing), Incident.AwaitingApproval)
                    step(id, "gate", a.tool + " queued for approval", Some(a.purpose), approvalId = Some(approvalId))
                    webhook("awaiting_approval", id, incident.kind, incident.resourceType, incident.resourceName, Some(a.tool + ": " + a.purpose))
                    return // the sweep resumes when the approval is decided
                case ToolOutcome.Denied(msg) =>
                    step(id, "gate", a.tool + " denied by agent policy", Some(clip(msg)))
                    close(id, Incident.Abandoned, "action denied by agent policy: " + a.tool)
                    return
                case ToolOutcome.Failed(msg) =>
                    step(id, "execute", a.tool + " failed", Some(clip(msg)))
                    revertIfNeeded(id, incident)
                    close(id, Incident.Failed, a.tool + " failed: " + clip(msg))
                    return
            }
        }
        verify(id, lastRealRun)
    }

    /** Deterministic verification: the resource must prove it is healthy.
      * For taps: a real run ends healthy, and — when it fed a pipeline — the
      * rollup completes without error. Failure reverts the definition. */
    def verify(id: String, priorRealRun: Option[String] = None): Unit = {
        val incident = IncidentIO.get(id).getOrElse(return
        )
        IncidentIO.transition(id, Set(Incident.Executing, Incident.AwaitingApproval, Incident.Proposed), Incident.Verifying)
        if (incident.resourceType != "tap") {
            // Pipeline incidents have no executable actions in v1; reaching
            // verify means diagnosis-only — record and resolve.
            step(id, "verify", "no automatic verification for " + incident.resourceType + " incidents — closing with the diagnosis")
            close(id, Incident.Resolved, "diagnosed; see steps")
            return
        }
        val name = incident.resourceName

        // When the last executed action already WAS a successful real run,
        // judge that run instead of firing another (which would only hit the
        // tap's duplicate-trigger debounce).
        priorRealRun match {
            case Some(text) =>
                val publisherToken = extractString(text, "publisherToken")
                val healthy = isTapHealthy(name)
                val rollupOk = publisherToken.forall(t => pollRollup(id, t, incident))
                if (healthy && rollupOk) {
                    step(
                        id,
                        "verify",
                        "verified: the executed real run is healthy" + publisherToken.map(_ => " and the pipeline rollup completed").getOrElse("")
                    )
                    close(id, Incident.Resolved, "recovered and verified")
                } else {
                    step(id, "verify", "verification failed on the executed run: healthy=" + healthy + ", rollupOk=" + rollupOk, Some(clip(text)))
                    revertIfNeeded(id, incident)
                    close(id, Incident.Failed, "verification failed after actions — definition reverted")
                }
                return
            case None =>
        }

        val args = new JsonObject()
        args.addProperty("name", name)
        executor.call("run_tap", args, id) match {
            case ToolOutcome.PendingApproval(approvalId) =>
                IncidentIO.set(id, "awaitingApprovalIds" -> java.util.Arrays.asList(approvalId), "nextActionIndex" -> (Int.MaxValue: java.lang.Integer))
                IncidentIO.transition(id, Set(Incident.Verifying), Incident.AwaitingApproval)
                step(id, "gate", "verification run queued for approval", approvalId = Some(approvalId))
            case ToolOutcome.Denied(msg) =>
                step(id, "verify", "verification run denied by policy", Some(clip(msg)))
                close(id, Incident.Abandoned, "verification denied by agent policy")
            case ToolOutcome.Failed(msg) =>
                step(id, "verify", "verification run failed", Some(clip(msg)))
                revertIfNeeded(id, incident)
                close(id, Incident.Failed, "verification run failed: " + clip(msg))
            case ToolOutcome.Ok(text) =>
                IncidentIO.incrementCounters(id, actions = 1)
                val publisherToken = extractString(text, "publisherToken")
                val healthy = isTapHealthy(name)
                val rollupOk = publisherToken.forall(t => pollRollup(id, t, incident))
                if (healthy && rollupOk) {
                    step(id, "verify", "verified: real run healthy" + publisherToken.map(_ => ", pipeline rollup complete").getOrElse(""))
                    close(id, Incident.Resolved, "recovered and verified")
                } else {
                    step(id, "verify", "verification failed: healthy=" + healthy + ", rollupOk=" + rollupOk, Some(clip(text)))
                    revertIfNeeded(id, incident)
                    close(id, Incident.Failed, "verification failed after actions — definition reverted")
                }
        }
    }

    /** Poll the pipeline rollup for a publisher token until done or the
      * runtime budget runs out. Reads go through the executor so they are
      * audited under the incident like everything else. */
    private def pollRollup(id: String, publisherToken: String, incident: Incident): Boolean = {
        val args = new JsonObject()
        args.addProperty("publisher_token", publisherToken)
        var attempts = 0
        while (attempts < 20) {
            if (runtimeExceeded(incident)) return false
            executor.call("get_pipeline_status", args, id) match {
                case ToolOutcome.Ok(text) =>
                    val allDone = text.contains("\"allDone\":true") || text.contains("\"allDone\": true")
                    val isError = text.contains("\"status\":\"error\"") || text.contains("\"status\": \"error\"")
                    if (allDone) return !isError
                case _ => return false
            }
            attempts += 1
            try Thread.sleep(15000L)
            catch { case _: InterruptedException => return false }
        }
        false
    }

    /** Restore the tap definition captured before the first mutation. Direct
      * server-side restore — the safety net must not itself be gateable. */
    private def revertIfNeeded(id: String, incident: Incident): Unit = {
        try {
            val fresh = IncidentIO.get(id).getOrElse(incident)
            for (target <- fresh.revertToVersion if incident.resourceType == "tap") {
                val env = DatrisEnvironment.current
                val live = TapConfigIO.read(env.tapTableName, incident.resourceName)
                if (live != null && live.version != target) {
                    EntityVersionIO.get(env.tapVersionTableName, incident.resourceName, target).foreach { snapshot =>
                        val snap = gson.fromJson(snapshot.config, classOf[TapConfig])
                        val restored = snap.copy(
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
                            lastTestRunColumns = live.lastTestRunColumns
                        )
                        TapConfigIO.writeVersioned(restored, "incident " + id + ": reverted after failed verification", RecoveryKey.Label)
                        step(id, "execute", "reverted tap to version " + target)
                        AuditLog.system("incident", "revert", "tap", incident.resourceName, metadata = md("incidentId" -> id, "toVersion" -> target.toString))
                    }
                }
            }
        } catch {
            case e: Exception => step(id, "execute", "revert failed", Some(clip(e.getMessage)))
        }
    }

    // ------------------------------------------------------------------
    // Resume / timeout (called by the sweep)
    // ------------------------------------------------------------------

    /** Move incidents forward whose approvals were decided, and abandon
      * whatever exceeded its limits. */
    def sweepOpenIncidents(): Unit = {
        if (!enabled) return
        IncidentIO.listOpen().foreach { incident =>
            try {
                incident.state match {
                    case Incident.AwaitingApproval if incident.awaitingApprovalIds.nonEmpty =>
                        val decisions = incident.awaitingApprovalIds.flatMap(PendingActionIO.get)
                        if (decisions.exists(_.state == PendingAction.Rejected))
                            close(incident.id, Incident.Abandoned, "approval rejected by " + decisions.flatMap(_.decidedBy).headOption.getOrElse("a person"))
                        else if (decisions.exists(d => d.state == PendingAction.Expired || d.isExpired()))
                            close(incident.id, Incident.Abandoned, "approval expired undecided")
                        else if (decisions.exists(_.state == PendingAction.Failed))
                            close(incident.id, Incident.Failed, "approved action failed on execution")
                        else if (decisions.nonEmpty && decisions.forall(_.state == PendingAction.Executed)) {
                            IncidentIO.set(incident.id, "awaitingApprovalIds" -> java.util.Collections.emptyList[String]())
                            step(incident.id, "gate", "approval executed — continuing")
                            val next = storedNextActionIndex(incident)
                            if (next == Int.MaxValue) {
                                // the executed approval WAS the verification run
                                pool.submit(new Runnable { override def run(): Unit = finishVerifyAfterApproval(incident.id) })
                            } else {
                                pool.submit(new Runnable { override def run(): Unit = executeFrom(incident.id, next + 1) })
                            }
                        }
                    case Incident.AwaitingApproval =>
                        // needs-human card with nothing queued: expire with the runtime budget ×8
                        if (incident.openedAt.plus(settings.maxRuntimeMinutes.toLong * 8, ChronoUnit.MINUTES).isBefore(Instant.now()))
                            close(incident.id, Incident.Abandoned, "no human decision arrived")
                    case _ =>
                        if (runtimeExceeded(incident))
                            close(incident.id, Incident.Abandoned, "runtime limit reached")
                }
            } catch {
                case e: Exception => logger.warn("incident sweep error on " + incident.id + ": " + e.getMessage)
            }
        }
    }

    private def finishVerifyAfterApproval(id: String): Unit = {
        val incident = IncidentIO.get(id).getOrElse(return
        )
        val healthy = incident.resourceType != "tap" || isTapHealthy(incident.resourceName)
        if (healthy) {
            step(id, "verify", "verified after approved run: resource healthy")
            close(id, Incident.Resolved, "recovered and verified (via approved run)")
        } else {
            revertIfNeeded(id, incident)
            close(id, Incident.Failed, "approved run did not leave the resource healthy — definition reverted")
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private def runtimeExceeded(incident: Incident): Boolean =
        incident.openedAt.plus(settings.maxRuntimeMinutes.toLong, ChronoUnit.MINUTES).isBefore(Instant.now())

    private def currentTapVersion(name: String): Option[Int] =
        try Option(TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)).map(_.version)
        catch { case _: Exception => None }

    private def parseStoredActions(incident: Incident): Vector[ProposedAction] =
        incident.proposal.map { p =>
            if (!p.has("actions") || !p.get("actions").isJsonArray) Vector.empty[ProposedAction]
            else {
                val it = p.getAsJsonArray("actions").iterator()
                val buf = Vector.newBuilder[ProposedAction]
                while (it.hasNext) {
                    val o = it.next().getAsJsonObject
                    buf += ProposedAction(
                        tool = if (o.has("tool")) o.get("tool").getAsString else "",
                        args = if (o.has("args") && o.get("args").isJsonObject) o.getAsJsonObject("args") else new JsonObject(),
                        purpose = if (o.has("purpose")) o.get("purpose").getAsString else ""
                    )
                }
                buf.result()
            }
        }.getOrElse(Vector.empty)

    private def storedNextActionIndex(incident: Incident): Int = {
        // nextActionIndex is stored outside the case class; re-read the raw doc.
        try {
            IncidentIO.get(incident.id) // ensure fresh
            val coll = ai.datris.util.NoSQLDbUtil match {
                case m: MongoDBUtil => m.collection(DatrisEnvironment.current.incidentTableName)
                case _ => return 0
            }
            Option(coll.find(com.mongodb.client.model.Filters.eq("_id", incident.id)).first())
                .flatMap(d => Option(d.getInteger("nextActionIndex"))).map(_.intValue()).getOrElse(0)
        } catch {
            case _: Exception => 0
        }
    }

    private def proposalJson(p: Proposal, filtered: List[ProposedAction]): JsonObject = {
        val o = new JsonObject()
        o.addProperty("classification", p.classification)
        o.addProperty("summary", p.summary)
        o.addProperty("needsHuman", p.needsHuman)
        val arr = new com.google.gson.JsonArray()
        filtered.foreach { a =>
            val ao = new JsonObject()
            ao.addProperty("tool", a.tool)
            ao.add("args", a.args)
            ao.addProperty("purpose", a.purpose)
            arr.add(ao)
        }
        o.add("actions", arr)
        p.learnNote.foreach(o.addProperty("learnNote", _))
        o
    }

    private def step(id: String, phase: String, summary: String, detail: Option[String] = None, approvalId: Option[String] = None): Unit =
        IncidentIO.appendStep(id, IncidentStep(Instant.now(), phase, summary, detail, approvalId))

    private def close(id: String, state: String, outcome: String): Unit = {
        val incident = IncidentIO.get(id)
        if (IncidentIO.transition(id, Incident.OpenStates, state, "outcome" -> outcome)) {
            step(id, "close", state + ": " + outcome)
            incident.foreach { i =>
                AuditLog.system(
                    "incident",
                    "close",
                    i.resourceType,
                    i.resourceName,
                    metadata = md("incidentId" -> id, "state" -> state, "outcome" -> clip(outcome))
                )
                Metrics.counter("datris_incidents_total", "kind", i.kind, "outcome", state).increment()
                Metrics.timer("datris_incident_duration_seconds", "kind", i.kind)
                    .record(java.time.Duration.between(i.openedAt, Instant.now()))
                webhook(state, id, i.kind, i.resourceType, i.resourceName, Some(outcome))
            }
            logger.info("incident " + id + " closed: " + state + " — " + outcome)
        }
    }

    private def webhook(event: String, id: String, kind: String, resourceType: String, resourceName: String, detail: Option[String]): Unit = {
        val url = Option(DatrisEnvironment.values).flatMap(v => Option(v.incidentWebhookUrl)).map(_.trim).filter(_.nonEmpty).getOrElse(return
        )
        try {
            val o = new JsonObject()
            o.addProperty("event", event)
            o.addProperty("incidentId", id)
            o.addProperty("kind", kind)
            o.addProperty("resourceType", resourceType)
            o.addProperty("resource", resourceName)
            detail.foreach(o.addProperty("detail", _))
            o.addProperty("ts", Instant.now().toString)
            HttpUtil.post(url, "application/json", o.toString)
        } catch {
            case e: Exception => logger.debug("incident webhook failed: " + e.getMessage)
        }
    }

    /** A tool result that reports an error INSIDE its JSON (a failed tap
      * run's envelope, an API error object) is a failure even when the tool
      * call itself returned cleanly. */
    private[incident] def hasErrorField(jsonText: String): Boolean =
        try {
            val el = JsonParser.parseString(jsonText)
            el.isJsonObject && {
                val o = el.getAsJsonObject
                o.has("error") && !o.get("error").isJsonNull &&
                (!o.get("error").isJsonPrimitive || o.get("error").getAsString.trim.nonEmpty)
            }
        } catch {
            case _: Exception => false
        }

    /** Depth-first search for an object with status=pending_approval and an
      * approvalId, at any nesting level. */
    private[incident] def findPendingApproval(jsonText: String): Option[String] = {
        def walk(el: com.google.gson.JsonElement): Option[String] = {
            if (el == null) None
            else if (el.isJsonObject) {
                val o = el.getAsJsonObject
                val here =
                    if (
                        o.has("status") && o.get("status").isJsonPrimitive && o.get("status").getAsString == "pending_approval" &&
                        o.has("approvalId") && o.get("approvalId").isJsonPrimitive
                    )
                        Some(o.get("approvalId").getAsString)
                    else None
                here.orElse {
                    val it = o.entrySet().iterator()
                    var found: Option[String] = None
                    while (found.isEmpty && it.hasNext) found = walk(it.next().getValue)
                    found
                }
            } else if (el.isJsonArray) {
                val it = el.getAsJsonArray.iterator()
                var found: Option[String] = None
                while (found.isEmpty && it.hasNext) found = walk(it.next())
                found
            } else None
        }
        try walk(JsonParser.parseString(jsonText))
        catch { case _: Exception => None }
    }

    private def extractString(jsonText: String, field: String): Option[String] =
        try {
            val el = JsonParser.parseString(jsonText)
            if (el.isJsonObject && el.getAsJsonObject.has(field) && el.getAsJsonObject.get(field).isJsonPrimitive)
                Some(el.getAsJsonObject.get(field).getAsString).filter(_.nonEmpty)
            else None
        } catch {
            case _: Exception => None
        }

    /** One direct platform call, through the normal interceptor chain. */
    private def restCall(method: String, path: String, body: Option[String], apiKey: String, incidentId: String): String = {
        val url = "http://127.0.0.1:" + ai.datris.policy.PolicyReplay.port + path
        val req: org.apache.http.client.methods.HttpRequestBase = method match {
            case "GET" => new org.apache.http.client.methods.HttpGet(url)
            case _ =>
                val post = new org.apache.http.client.methods.HttpPost(url)
                body.foreach { b =>
                    post.setEntity(new org.apache.http.entity.StringEntity(b, java.nio.charset.StandardCharsets.UTF_8))
                    post.setHeader("Content-Type", "application/json")
                }
                post
        }
        if (apiKey != null && apiKey.nonEmpty) req.setHeader("x-api-key", apiKey)
        req.setHeader(ai.datris.audit.AuditActor.HeaderIncident, incidentId)
        val config = org.apache.http.client.config.RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(10 * 60 * 1000).build()
        val client = org.apache.http.impl.client.HttpClients.custom().setDefaultRequestConfig(config).build()
        try {
            val resp = client.execute(req)
            Option(resp.getEntity).map(e => org.apache.http.util.EntityUtils.toString(e, java.nio.charset.StandardCharsets.UTF_8)).getOrElse("")
        } finally {
            try client.close()
            catch { case _: Exception => }
        }
    }

    private def clip(s: String): String = Option(s).getOrElse("").take(500)

    private def md(pairs: (String, String)*): JsonObject = {
        val o = new JsonObject()
        pairs.foreach { case (k, v) => o.addProperty(k, v) }
        o
    }

    // ------------------------------------------------------------------
    // Real implementations
    // ------------------------------------------------------------------

    /** Headless run of the shared Ops-agent judgment: read-only tools, and
      * the final message must be a JSON proposal. */
    object RealDiagnoser extends Diagnoser {
        override def diagnose(incident: Incident, onAiCall: () => Unit): Either[String, Proposal] = {
            val env = DatrisEnvironment.current
            val aiConfig = DatrisEnvironment.aiConfigForChat
            if (aiConfig == null) return Left("AI configuration is not initialized")
            val key = RecoveryKey.value().orNull
            val toolDefs = OpsAgentPrompt.reorderToolsOpsFirst(MCPClient.listTools(key))
                .filter(t => Option(t.get("name")).exists(n => DiagnosisTools.contains(n.getAsString)))

            val system = OpsAgentPrompt.buildOpsSystemPrompt(env.environment) + RecoveryAddendum
            val userMessage = buildDiagnosisMessage(incident)
            val finalText = new StringBuilder
            var error: Option[String] = None

            MCPClient.incidentContext.set(incident.id)
            try {
                AgentLoop.run(
                    aiConfig = aiConfig,
                    system = system,
                    userMessages = List(("user", userMessage)),
                    toolDefs = toolDefs,
                    apiKey = key,
                    enableThinking = env.extendedThinking,
                    maxIterations = settings.maxAiCallsPerIncident,
                    maxTokensPerCall = 8000,
                    cancelled = () => false,
                    sink = {
                        case AgentLoop.LoopEvent.IterationStart => onAiCall()
                        case AgentLoop.LoopEvent.TextDelta(t) => finalText.append(t); ()
                        case AgentLoop.LoopEvent.Error(msg) => error = Some(msg)
                        case _ => ()
                    }
                )
            } finally {
                MCPClient.incidentContext.remove()
            }
            error match {
                case Some(e) if finalText.isEmpty => Left(e)
                case _ => parseProposal(finalText.toString)
            }
        }

        private[incident] def parseProposal(text: String): Either[String, Proposal] = {
            val jsonStr = {
                val t = text.trim
                val fenced = """(?s).*```(?:json)?\s*(\{.*?\})\s*```.*""".r
                t match {
                    case fenced(inner) => inner
                    case _ =>
                        val start = t.indexOf('{')
                        val end = t.lastIndexOf('}')
                        if (start >= 0 && end > start) t.substring(start, end + 1) else t
                }
            }
            try {
                val o = JsonParser.parseString(jsonStr).getAsJsonObject
                val classification = if (o.has("classification")) o.get("classification").getAsString else "needs-human"
                val actions =
                    if (o.has("actions") && o.get("actions").isJsonArray) {
                        val it = o.getAsJsonArray("actions").iterator()
                        val buf = List.newBuilder[ProposedAction]
                        while (it.hasNext) {
                            val a = it.next().getAsJsonObject
                            buf += ProposedAction(
                                tool = if (a.has("tool")) a.get("tool").getAsString else "",
                                args = if (a.has("args") && a.get("args").isJsonObject) a.getAsJsonObject("args") else new JsonObject(),
                                purpose = if (a.has("purpose")) a.get("purpose").getAsString else ""
                            )
                        }
                        buf.result()
                    } else Nil
                Right(Proposal(
                    classification = classification,
                    summary = if (o.has("summary")) o.get("summary").getAsString else "",
                    needsHuman = (o.has("needsHuman") && o.get("needsHuman").getAsBoolean) || classification == "needs-human",
                    actions = actions,
                    learnNote = if (o.has("learnNote") && o.get("learnNote").isJsonPrimitive) Some(o.get("learnNote").getAsString) else None
                ))
            } catch {
                case e: Exception => Left("proposal was not valid JSON: " + e.getMessage + " — text: " + clip(text))
            }
        }

        private def buildDiagnosisMessage(incident: Incident): String = {
            val sb = new StringBuilder
            sb.append("(Automated incident — you are running headless as the platform's recovery agent; no operator is watching this conversation.)\n\n")
            sb.append("Incident ").append(incident.id).append(": ").append(incident.kind)
                .append(" on ").append(incident.resourceType).append(" `").append(incident.resourceName).append("`.\n")
            sb.append("Trigger: ").append(incident.trigger.toString.take(2000)).append("\n\n")
            sb.append("Diagnose this incident using the read-only tools available, then END your reply with ONLY a JSON object (no prose after it):\n")
            sb.append(
                """{"classification":"transient|structural-script|structural-schema|needs-human","summary":"<one line>","needsHuman":<bool>,"actions":[{"tool":"run_tap|test_tap|update_tap","args":{...},"purpose":"<one line>"}],"learnNote":"<optional source quirk worth remembering>"}"""
            ).append("\n\n")
            sb.append("Rules for the proposal:\n")
            sb.append("- transient (rate limit, network blip, quota): propose at most one run_tap.\n")
            sb.append("- every action's args MUST include \"name\": \"" + incident.resourceName + "\" — actions naming any other tap are discarded.\n")
            sb.append(
                "- structural-script (source shape changed, script bug): propose update_tap with args {\"name\": ..., \"script\": \"<the corrected full script>\"} (the platform replaces the tap's script with it), then test_tap, then run_tap.\n"
            )
            sb.append(
                "- structural-schema (destination/type mismatch) or credentials/upstream outage: needsHuman=true with an empty actions list and a clear summary of what a person must do.\n"
            )
            sb.append("- Never propose deletes, secret changes, or schema migrations — they are not executable here.\n")
            sb.append(
                "- The platform (not you) executes the actions, gates each one through the agent policy, verifies with a real run, and reverts your script change if verification fails."
            )
            sb.toString
        }
    }

    private val RecoveryAddendum: String =
        "\n\n## Recovery-agent mode\n\n" +
            "This is a HEADLESS diagnosis run: no operator is present, nothing you write is a conversation. " +
            "Use only the read-only tools provided, keep tool calls few and purposeful, and end with the single JSON proposal object you were asked for. " +
            "Do not ask questions; when information is missing, classify as needs-human and say what a person should check.\n"

    /** REST-backed executor. Every executable action maps to exactly ONE
      * platform call, so the policy gate queues exactly that call and an
      * approval replay performs exactly it — composite MCP tools (which fan
      * out into several calls) are deliberately not used for execution.
      * Calls carry the recovery key + incident id like any agent. */
    object RealExecutor extends ToolExecutor {

        private def restFor(tool: String, args: JsonObject): Option[(String, String, Option[String])] = {
            def name = if (args != null && args.has("name")) args.get("name").getAsString else ""
            tool match {
                case "update_tap" | "create_tap" if args != null && args.has("script") =>
                    val body = new JsonObject()
                    body.addProperty("tapName", name)
                    body.addProperty("script", args.get("script").getAsString)
                    Some(("POST", "/api/v1/tap/script", Some(body.toString)))
                case "test_tap" =>
                    // Same route the MCP tool uses: a run of the STORED script
                    // in test mode (POST /tap/test would validate the posted
                    // body's config instead of the stored one).
                    val body = new JsonObject()
                    body.addProperty("name", name)
                    body.addProperty("mode", "test")
                    Some(("POST", "/api/v1/tap/run", Some(body.toString)))
                case "run_tap" =>
                    val body = new JsonObject()
                    body.addProperty("name", name)
                    // The endpoint DEFAULTS to mode=test when absent — a real
                    // run must say so explicitly or verification reads stale.
                    body.addProperty("mode", "run")
                    Some(("POST", "/api/v1/tap/run", Some(body.toString)))
                case "get_pipeline_status" =>
                    val token = if (args != null && args.has("publisher_token")) args.get("publisher_token").getAsString else ""
                    Some(("GET", "/api/v1/pipeline/status?withrollup=true&publishertoken=" + java.net.URLEncoder.encode(token, "UTF-8"), None))
                case _ => None
            }
        }

        override def call(tool: String, args: JsonObject, incidentId: String): ToolOutcome = {
            val key = RecoveryKey.value().orNull
            MCPClient.incidentContext.set(incidentId)
            try {
                val text = restFor(tool, args) match {
                    case Some((method, path, body)) => restCall(method, path, body, key, incidentId)
                    case None =>
                        // A config-only update_tap/create_tap (no script) is not a
                        // single-call action — refuse rather than fan out.
                        if (tool == "update_tap" || tool == "create_tap")
                            return ToolOutcome.Failed("action '" + tool + "' without a script argument is not executable by the runner")
                        MCPClient.callTool(tool, args, key)
                }
                // Composite MCP tools (create_tap and friends) make several
                // REST calls and can wrap the platform's 202 queue response
                // inside their own success envelope — so look for a
                // pending_approval object ANYWHERE in the result tree, not
                // just at the top level.
                findPendingApproval(text) match {
                    case Some(approvalId) => ToolOutcome.PendingApproval(approvalId)
                    case None =>
                        if (text.contains("\"errorKind\":\"policy_denied\"")) ToolOutcome.Denied(text)
                        else if (hasErrorField(text)) ToolOutcome.Failed(text)
                        else ToolOutcome.Ok(text)
                }
            } catch {
                case e: Exception =>
                    if (Option(e.getMessage).exists(_.contains("policy_denied"))) ToolOutcome.Denied(e.getMessage)
                    else ToolOutcome.Failed(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
            } finally {
                MCPClient.incidentContext.remove()
            }
        }
    }
}
