package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.AuditLog
import ai.datris.model.{DatrisEnvironment, DatrisException, TapConfig}
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
        "get_version",
        // Doctor is read-only and never spends money unless asked for AI
        // probes, which the diagnosis prompt tells the agent not to do.
        "run_doctor",
        // What a change touches: lineage, provenance, discovery and the
        // pipeline's version history (metadata:read / pipeline:read — the
        // recovery key already holds both).
        "get_lineage",
        "find_data",
        "get_provenance",
        "list_pipeline_versions",
        "diff_pipeline_versions"
    )

    /** Actions the runner will execute from a proposal. Anything else —
      * deletes, schema migrations, secret changes — is recorded but never
      * run; those need a human. */
    /** `create_tap` is here because it is the platform's documented path for
      * replacing a tap's SCRIPT (update_tap deliberately cannot change
      * scripts); the name-bound filter pins it to the incident's own tap, so
      * it can never create a new one.
      *
      * `regenerate_pipeline_scripts` is a runner action, not an MCP tool:
      * pipeline incidents regenerate the pipeline's stored AI data-quality or
      * transformation script (one REST call, see [[RealExecutor]]). */
    val ExecutableTools: Set[String] = Set("run_tap", "test_tap", "update_tap", "create_tap", "regenerate_pipeline_scripts")

    /** The one executable action of a pipeline incident. */
    val RegenerateScripts = "regenerate_pipeline_scripts"

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

    /** What the scratch lane produced. `candidate` None with `error` Some =
      * the scratch run itself failed. `unavailable` Some = the scratch lane
      * cannot rehearse this tap (document taps); the reason is recorded and
      * the incident is verified with the real run, as `verifyMode = real`. */
    case class ScratchOutcome(
        candidate: Option[RunProfile],
        error: Option[String] = None,
        unavailable: Option[String] = None,
        result: Option[TapScriptResult] = None
    )

    /** What a passing scratch rehearsal of a candidate script writes on the
      * STORED tap's `lastTestRun*` fields: it counts as that script's test run
      * for the test-before-cron gate. `scriptId` is
      * `TapScriptIdentity.ofText(candidate)`. */
    case class RehearsalStamp(scriptId: String, recordCount: Int, dataType: String, columns: java.util.List[String]) {

        /** `tap` with this rehearsal as its passing test run. Touches
          * `lastTestRun*` only — never lastRun*, state, cron, enabled or the
          * script reference. */
        def applyTo(tap: TapConfig, now: String): TapConfig =
            tap.copy(
                lastTestRunStatus = "success",
                lastTestRunTime = now,
                lastTestRunRecordCount = recordCount,
                lastTestRunError = null,
                lastTestRunDataType = dataType,
                lastTestRunColumns = columns,
                lastTestRunScriptId = scriptId
            )
    }

    /** scratchVerify's verdict when the lane is not available for the tap. */
    private[incident] val ScratchUnavailable = "unavailable"

    /** The scratch lane: (stored tap, candidate script — None = the stored
      * script) => outcome. Production = TapRunner.run(mode = "scratch",
      * scriptOverride) + rollup poll + ScratchReader paging + RunProfiler. */
    @volatile var scratch: (TapConfig, Option[String]) => ScratchOutcome = (t, script) => realScratch(t, script)

    /** Spec seam: when non-null, used instead of PolicyIO.current.recovery. */
    @volatile private[incident] var settingsOverride: RecoverySettings = null

    /** Spec seam: when non-null, open(...) delegates to it entirely. */
    @volatile private[datris] var openOverride: (String, String, String, JsonObject) => Option[String] = null

    /** The latest run's logs of a tap: (tapName, lastRunTime) => logs. Read
      * for the credential classifier; never throws. */
    @volatile private[incident] var tapRunLogs: (String, String) => Option[String] =
        (name: String, runTime: String) => readTapRunLogs(name, runTime)

    /** Every stored-tap read in the runner. */
    @volatile private[incident] var tapLookup: String => Option[TapConfig] =
        (name: String) => Option(TapConfigIO.read(DatrisEnvironment.current.tapTableName, name))

    /** Writes a passing rehearsal as the tap's test run: (tapName, stamp).
      * Production reads the stored tap and rewrites its `lastTestRun*` fields
      * only, non-versioned (as a `mode=test` run does). Not routed through the
      * executor, so not gateable. */
    @volatile private[incident] var stampTest: (String, RehearsalStamp) => Unit =
        (name: String, stamp: RehearsalStamp) => writeRehearsalStamp(name, stamp)

    /** The plain (non-versioned) stored-tap write that puts back the test
      * fields a rehearsal overwrote, when the candidate was never stored. */
    @volatile private[incident] var tapWrite: TapConfig => Unit = (t: TapConfig) => TapConfigIO.write(t)

    /** Raw-document field: the stored tap's test fields before a rehearsal stamp. */
    private val RehearsalPriorField = "rehearsalPriorTest"

    /** The direct restore inside revertIfNeeded: (incidentId, tapName,
      * toVersion). Called only when the live version differs from the target. */
    @volatile private[incident] var revertTap: (String, String, Int) => Unit =
        (incidentId: String, tapName: String, toVersion: Int) => restoreTapVersion(incidentId, tapName, toVersion)

    /** Spec seam: the sweep's approval lookup (production PendingActionIO.get). */
    @volatile private[incident] var approvalLookup: String => Option[PendingAction] = (approvalId: String) => PendingActionIO.get(approvalId)

    /** Spec seam: when Some, used instead of [[enabled]] by the sweep. */
    @volatile private[incident] var sweepEnabledOverride: Option[Boolean] = None

    /** What a pipeline incident can verify a regenerated script on. */
    @volatile var inputSource: PipelineInputSource = PipelineInputSource

    /** (pipeline, kind) => latest stored script version, 0 = no history yet. */
    @volatile private[incident] var scriptLatestVersion: (String, String) => Int =
        (pipeline: String, kind: String) => CodeGenScriptVersionIO.latestVersion(pipeline, kind)

    /** (pipeline, kind, toVersion, actor) => restore that script version as
      * the live script (a new latest version holding the old text). */
    @volatile private[incident] var restoreScript: (String, String, Int, String) => Either[String, Unit] =
        (pipeline: String, kind: String, toVersion: Int, actor: String) => restorePipelineScript(pipeline, kind, toVersion, actor)

    /** Rows of a scratch result profiled into `evidence.candidate`. */
    val ProfileRowCap = 5000

    /** Raw-document field: the scratch verdict an incident is parked on. */
    private val ScratchVerdictField = "scratchVerdict"

    /** The incident a production scratch run is polled under (the scratch
      * seam's signature carries no id). */
    private val scratchIncident = new ThreadLocal[String]()

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

    private[incident] def settings: RecoverySettings = {
        val o = settingsOverride
        if (o != null) o
        else
            try PolicyIO.current.recovery
            catch { case _: Exception => RecoverySettings() }
    }

    // ------------------------------------------------------------------
    // Open
    // ------------------------------------------------------------------

    /** Open an incident for a signal, applying every guard. Returns the id
      * when one was opened. Never throws. */
    def open(kind: String, resourceType: String, resourceName: String, trigger: JsonObject): Option[String] = {
        val o = openOverride
        if (o != null) return o(kind, resourceType, resourceName, trigger)
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
            tapLookup(name).exists(t => Option(t.lastRunStatus).map(_.toLowerCase).exists(s => s == "success" || s == "no_records"))
        } catch {
            case _: Exception => false
        }

    // ------------------------------------------------------------------
    // The loop
    // ------------------------------------------------------------------

    def runIncident(id: String): Unit = {
        try {
            val opened = IncidentIO.get(id).getOrElse(return
            )
            if (!opened.isOpen) return
            if (!IncidentIO.transition(id, Set(Incident.Open), Incident.Diagnosing)) return

            val s = settings
            // Deterministic evidence at open (blast radius + baseline, verdict
            // pending), computed here on the runner pool rather than in open()
            // so sampling a warehouse never blocks the signal path. Never
            // throws; a failed step leaves its field empty.
            if (opened.evidence.isEmpty) IncidentEvidenceBuilder.store(id, IncidentEvidenceBuilder.atOpen(opened, s))
            val incident = IncidentIO.get(id).getOrElse(opened)
            var aiCalls = 0
            val onAiCall = () => { aiCalls += 1; IncidentIO.incrementCounters(id, aiCalls = 1) }

            // Doctor-first: a failure the platform's own self-check attributes
            // to the platform is classified `platform` before any AI is spent.
            val platform = if (isFailureKind(incident.kind)) doctorFirst(id) else None
            val diagnosed = platform match {
                case Some(p) => Right(p)
                // Re-read so the diagnosis sees any "doctor: …" step just recorded.
                case None => diagnoser.diagnose(IncidentIO.get(id).getOrElse(incident), onAiCall)
            }

            diagnosed match {
                case Left(err) =>
                    step(id, "diagnose", "diagnosis failed", Some(err))
                    close(id, Incident.Failed, "diagnosis failed: " + clip(err))
                case Right(p0) =>
                    // Credential failures are classified by code: never tap
                    // actions, and the card names the secret to check.
                    val secret = if (platform.isEmpty && isFailureKind(incident.kind)) credentialSecret(id, incident) else None
                    val p = secret match {
                        case Some(sec) =>
                            p0.copy(
                                classification = Classifier.Credential,
                                summary = credentialSummary(p0.summary, sec),
                                needsHuman = true,
                                actions = Nil
                            )
                        case None => p0
                    }
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
                    } else if (incident.kind == Incident.KindSchemaDrift && !driftActionable(incident, s)) {
                        // Diagnosis only unless recovery.allowSchemaDriftActions
                        // and autopilot: the value is the diagnosis naming the diff.
                        step(id, "close", "schema drift triaged — no automatic action taken")
                        close(id, Incident.Resolved, "triaged: " + p.summary)
                    } else if (p.needsHuman || filtered.isEmpty) {
                        IncidentIO.transition(id, Set(Incident.Diagnosing), Incident.AwaitingApproval)
                        step(id, "propose", "needs a human", Some(p.summary))
                        val detail = p.summary + secret.filter(_.nonEmpty).map(n => " — secret: " + n).getOrElse("")
                        webhook("awaiting_approval", id, incident.kind, incident.resourceType, incident.resourceName, Some(detail))
                    } else {
                        IncidentIO.transition(id, Set(Incident.Diagnosing), Incident.Proposed)
                        step(id, "propose", filtered.size + " action(s): " + filtered.map(_.tool).mkString(", "), Some(p.summary))
                        // Remember the version to revert to before any mutation.
                        if (incident.resourceType == "tap")
                            currentTapVersion(incident.resourceName).foreach(v => IncidentIO.set(id, "revertToVersion" -> (v: java.lang.Integer)))
                        if (incident.resourceType == "pipeline") {
                            // The script version each regenerated kind is restored
                            // to if verification fails (0 = no history yet; the
                            // regenerate seeds it, so 0 means "restore v1").
                            val kinds = filtered.filter(_.tool == RegenerateScripts).flatMap(a => argString(a.args, "kind")).distinct
                            if (kinds.nonEmpty) {
                                val doc = new org.bson.Document()
                                kinds.foreach(k => doc.put(k, (scriptLatestVersion(incident.resourceName, k): java.lang.Integer)))
                                IncidentIO.set(id, "revertToScriptVersions" -> doc)
                            }
                        }
                        IncidentIO.set(id, "nextActionIndex" -> (0: java.lang.Integer))
                        if (s.verifyMode == RecoverySettings.ScratchFirst && incident.resourceType == "tap") {
                            // Stage 1: rehearse the candidate in the scratch lane
                            // before anything mutates (and, in propose mode,
                            // before any approval is queued — so it carries the
                            // evidence).
                            val candidateScript = filtered.find(a => a.tool == "update_tap" || a.tool == "create_tap")
                                .flatMap(a => Option(a.args).filter(_.has("script")).map(_.get("script")))
                                .filter(_.isJsonPrimitive)
                                .map(_.getAsString)
                            val (verdict, summary) = scratchVerify(id, incident, candidateScript)
                            verdict match {
                                case Thresholds.Pass | ScratchUnavailable => executeFrom(id, 0)
                                case Thresholds.NeedsHuman =>
                                    IncidentIO.transition(
                                        id,
                                        Set(Incident.Diagnosing, Incident.Proposed),
                                        Incident.AwaitingApproval,
                                        ScratchVerdictField -> Thresholds.NeedsHuman,
                                        "nextActionIndex" -> (0: java.lang.Integer)
                                    )
                                    step(id, "propose", "needs a human: " + summary)
                                    webhook("awaiting_approval", id, incident.kind, incident.resourceType, incident.resourceName, Some(summary))
                                case _ =>
                                    // Nothing mutated yet; called for symmetry with every other failed close.
                                    revertAll(id, incident)
                                    close(id, Incident.Failed, "candidate failed scratch verification: " + clip(summary))
                            }
                        } else executeFrom(id, 0)
                    }
            }
        } catch {
            case e: Exception =>
                logger.error("incident " + id + " runner error: " + e.getMessage, e)
                try close(id, Incident.Failed, "runner error: " + clip(e.getMessage))
                catch { case _: Exception => }
        }
    }

    /** Failure signals get the doctor-first and credential checks; drift and
      * volume incidents describe a run that succeeded. */
    private def isFailureKind(kind: String): Boolean =
        kind == Incident.KindTapFailure || kind == Incident.KindPipelineFailure || kind == Incident.KindStale

    /** A drift incident may act only with the policy flag on AND the
      * resource's effective recovery mode at autopilot. */
    private def driftActionable(incident: Incident, s: RecoverySettings): Boolean =
        s.allowSchemaDriftActions && {
            val o = settingsOverride
            val mode =
                if (o != null) o.mode
                else
                    try PolicyIO.current.effectiveRecoveryMode(incident.resourceType, incident.resourceName)
                    catch { case _: Exception => s.mode }
            mode == RecoverySettings.Autopilot
        }

    /** Doctor-first: run the quick self-check (no AI probes). A run-breaking
      * `error` check (Classifier.RunBreakingChecks) makes this a platform
      * incident; the proposal quotes the remediation and the model is never
      * asked. Any other non-tap `error` check is recorded as a "doctor: …"
      * step, which the diagnosis message carries to the model. */
    private[incident] def doctorFirst(id: String): Option[Proposal] = {
        val args = new JsonObject()
        args.addProperty("mode", "quick")
        args.addProperty("ai", false)
        val text = executor.call("run_doctor", args, id) match {
            case ToolOutcome.Ok(t) => t
            case other =>
                step(id, "diagnose", "doctor check unavailable — continuing with diagnosis", Some(clip(other.toString)))
                return None
        }
        val all = Classifier.platformErrors(text)
        val errors = all.filter { case (cid, detail, _) => Classifier.isRunBreaking(cid, detail) }
        all.filterNot(errors.contains).foreach { case (cid, detail, remediation) =>
            step(id, "diagnose", DoctorNotePrefix + cid + " — " + (if (remediation.nonEmpty) remediation else detail), Some(clip(detail)))
        }
        if (errors.isEmpty) None
        else {
            step(id, "diagnose", "doctor reports " + errors.size + " platform error(s): " + errors.map(_._1).mkString(", "))
            val lines = errors.map { case (cid, detail, remediation) =>
                cid + ": " + detail + (if (remediation.nonEmpty) " — " + remediation else "")
            }
            Some(Proposal(Classifier.Platform, "Platform problem, not the tap: " + lines.mkString("; "), needsHuman = true, Nil, None))
        }
    }

    /** Step-summary prefix of a doctor error that did not short-circuit. */
    private[incident] val DoctorNotePrefix = "doctor: "

    /** The secret name to check when the failure is a credential failure
      * (Some("") when it is one but no secret is configured), else None.
      * Stamps the name onto the evidence. Only the name is read. */
    private def credentialSecret(id: String, incident: Incident): Option[String] = {
        val tap =
            if (incident.resourceType == "tap")
                try tapLookup(incident.resourceName)
                catch { case _: Exception => None }
            else None
        val error = Option(incident.trigger).filter(_.has("error")).map(_.get("error")).filter(_.isJsonPrimitive).map(_.getAsString).orNull
        def logs: String = tap.flatMap(t =>
            Option(t.lastRunTime).filter(_.nonEmpty).flatMap(rt =>
                try tapRunLogs(t.name, rt)
                catch { case _: Exception => None }
            )
        ).orNull
        if (!(Classifier.credential(error, null) || Classifier.credential(null, logs))) return None
        val name = tap.flatMap(t => Option(t.secretName)).map(_.trim).getOrElse("")
        if (name.nonEmpty) {
            val current = IncidentIO.get(id).flatMap(IncidentEvidenceBuilder.of)
            current.foreach(e => IncidentEvidenceBuilder.store(id, IncidentEvidenceBuilder.withSecret(e, name)))
        }
        Some(name)
    }

    private def credentialSummary(modelSummary: String, secret: String): String = {
        val head = Option(modelSummary).map(_.trim).filter(_.nonEmpty).map(_ + " — ").getOrElse("")
        if (secret.nonEmpty)
            head + "The source rejected the credentials; check or rotate secret `" + secret + "` (Configuration → Secrets), then re-run the tap."
        else head + "The source rejected the credentials; check the tap's credentials, then re-run the tap."
    }

    private def readTapRunLogs(name: String, runTime: String): Option[String] =
        try {
            val env = DatrisEnvironment.current
            if (env == null) None
            else
                NoSQLDbUtil.getItemJSON(env.tapLogTableName, "key", name + "|" + runTime, "value")
                    .flatMap(j => Option(gson.fromJson(j, classOf[ai.datris.model.TapRunLog])))
                    .flatMap(l => Option(l.logs))
        } catch {
            case e: Exception =>
                logger.debug("incident: tap run log read failed for " + name + ": " + e.getMessage)
                None
        }

    /** Cap and sanitize the proposal: executable tools only, at most
      * maxActionsPerIncident, stale-kind limited to a single run. */
    private[incident] def filterActions(incident: Incident, p: Proposal): List[ProposedAction] = {
        // Executable, and pinned to the incident's own resource: an action
        // naming any other tap (or none) is dropped, whatever the model said.
        val pinned = p.actions.filter(a =>
            ExecutableTools.contains(a.tool) && argString(a.args, "name").contains(incident.resourceName)
        )
        // A pipeline incident's only action is regenerating one of its own
        // stored AI scripts; a tap incident never regenerates anything.
        val executable =
            if (incident.resourceType == "pipeline")
                pinned.filter(a => a.tool == RegenerateScripts && argString(a.args, "kind").exists(PipelineScripts.Kinds.contains))
            else pinned.filter(_.tool != RegenerateScripts)
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
                    if (a.tool == RegenerateScripts) argString(a.args, "kind").foreach(k => recordScriptVersion(id, incident, k))
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
                    revertAll(id, incident)
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
        if (incident.resourceType == "pipeline") {
            verifyPipeline(id, incident)
            return
        }
        if (incident.resourceType != "tap") {
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
                recordRealRunEvidence(id)
                if (healthy && rollupOk && realStageBreached(id)) {
                    revertAll(id, incident)
                    close(id, Incident.Failed, RealBreachOutcome)
                } else if (healthy && rollupOk) {
                    step(
                        id,
                        "verify",
                        "verified: the executed real run is healthy" + publisherToken.map(_ => " and the pipeline rollup completed").getOrElse("") +
                            scratchVerdictNote(id)
                    )
                    close(id, Incident.Resolved, "recovered and verified")
                } else {
                    step(id, "verify", "verification failed on the executed run: healthy=" + healthy + ", rollupOk=" + rollupOk, Some(clip(text)))
                    revertAll(id, incident)
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
                revertAll(id, incident)
                close(id, Incident.Failed, "verification run failed: " + clip(msg))
            case ToolOutcome.Ok(text) =>
                IncidentIO.incrementCounters(id, actions = 1)
                val publisherToken = extractString(text, "publisherToken")
                val healthy = isTapHealthy(name)
                val rollupOk = publisherToken.forall(t => pollRollup(id, t, incident))
                recordRealRunEvidence(id)
                if (healthy && rollupOk && realStageBreached(id)) {
                    revertAll(id, incident)
                    close(id, Incident.Failed, RealBreachOutcome)
                } else if (healthy && rollupOk) {
                    step(
                        id,
                        "verify",
                        "verified: real run healthy" + publisherToken.map(_ => ", pipeline rollup complete").getOrElse("") + scratchVerdictNote(id)
                    )
                    close(id, Incident.Resolved, "recovered and verified")
                } else {
                    step(id, "verify", "verification failed: healthy=" + healthy + ", rollupOk=" + rollupOk, Some(clip(text)))
                    revertAll(id, incident)
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
    /** True when a revert was performed. */
    private def revertIfNeeded(id: String, incident: Incident): Boolean = {
        var reverted = false
        try {
            val fresh = IncidentIO.get(id).getOrElse(incident)
            for (target <- fresh.revertToVersion if incident.resourceType == "tap") {
                tapLookup(incident.resourceName).filter(_.version != target).foreach { _ =>
                    revertTap(id, incident.resourceName, target)
                    reverted = true
                    step(id, "execute", "reverted tap to version " + target)
                    AuditLog.system("incident", "revert", "tap", incident.resourceName, metadata = md("incidentId" -> id, "toVersion" -> target.toString))
                }
            }
        } catch {
            case e: Exception => step(id, "execute", "revert failed", Some(clip(e.getMessage)))
        }
        reverted
    }

    /** Every safety net: the tap definition and a pipeline's stored scripts.
      * True when anything was restored. */
    private def revertAll(id: String, incident: Incident): Boolean = {
        val tap = revertIfNeeded(id, incident)
        val scripts = revertScriptIfNeeded(id, incident)
        tap || scripts
    }

    /** Raw-document flag: the script restore already ran (a restore appends a
      * new latest version, so "latest != recorded" stays true after it). */
    private val ScriptsRevertedField = "scriptsReverted"

    /** Restore each pipeline script regenerated by this incident to the
      * version recorded before the first action. Direct server-side call,
      * never gated; runs at most once per incident; a Left is a "revert
      * failed" step, never a throw. */
    private def revertScriptIfNeeded(id: String, incident: Incident): Boolean = {
        if (incident.resourceType != "pipeline") return false
        var restored = false
        try {
            val fresh = IncidentIO.get(id).getOrElse(incident)
            if (fresh.revertToScriptVersions.isEmpty) return false
            val already = IncidentIO.raw(id).exists(d => java.lang.Boolean.TRUE == d.get(ScriptsRevertedField))
            if (already) return false
            IncidentIO.set(id, ScriptsRevertedField -> java.lang.Boolean.TRUE)
            val name = incident.resourceName
            fresh.revertToScriptVersions.toList.sortBy(_._1).foreach { case (kind, v) =>
                val target = math.max(v, 1)
                val latest =
                    try scriptLatestVersion(name, kind)
                    catch { case _: Exception => -1 }
                if (latest != v) {
                    val result =
                        try restoreScript(name, kind, target, RecoveryKey.Label)
                        catch { case e: Exception => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)) }
                    result match {
                        case Right(_) =>
                            restored = true
                            step(id, "execute", "restored " + kind + " script to version " + target)
                            AuditLog.system(
                                "incident",
                                "revert",
                                "pipeline",
                                name,
                                metadata = md("incidentId" -> id, "kind" -> kind, "toVersion" -> target.toString)
                            )
                            updateScriptVersion(id, kind, before = v, after = target)
                        case Left(reason) =>
                            step(id, "execute", "revert failed", Some(clip(kind + " script: " + reason)))
                    }
                }
            }
        } catch {
            case e: Exception => step(id, "execute", "revert failed", Some(clip(e.getMessage)))
        }
        restored
    }

    /** After a successful regenerate: evidence.scriptVersions gets
      * (kind, before = the recorded version, after = the latest now). */
    private def recordScriptVersion(id: String, incident: Incident, kind: String): Unit =
        try {
            val fresh = IncidentIO.get(id).getOrElse(incident)
            val before = fresh.revertToScriptVersions.getOrElse(kind, 0)
            updateScriptVersion(id, kind, before, scriptLatestVersion(incident.resourceName, kind))
        } catch {
            case e: Exception => logger.debug("incident " + id + ": script version evidence skipped: " + e.getMessage)
        }

    private def updateScriptVersion(id: String, kind: String, before: Int, after: Int): Unit =
        try {
            val base = IncidentIO.get(id).flatMap(IncidentEvidenceBuilder.of).getOrElse(emptyEvidence(settings))
            val change = ScriptVersionChange(kind, before, after)
            val list =
                if (base.scriptVersions.exists(_.kind == kind)) base.scriptVersions.map(c => if (c.kind == kind) change else c)
                else base.scriptVersions :+ change
            IncidentEvidenceBuilder.store(id, base.copy(scriptVersions = list))
        } catch {
            case e: Exception => logger.debug("incident " + id + ": script version evidence skipped: " + e.getMessage)
        }

    /** Production [[restoreScript]]. */
    private def restorePipelineScript(pipeline: String, kind: String, toVersion: Int, actor: String): Either[String, Unit] = {
        val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
        if (config == null) Left("pipeline " + pipeline + " no longer exists")
        else PipelineScripts.restoreVersion(config, kind, toVersion, actor).map(_ => ())
    }

    /** Raw-document field: the feeding tap a pipeline incident verifies with. */
    private val VerifyTapField = "verifyTap"

    private def verifyTapOf(id: String): Option[String] =
        try IncidentIO.raw(id).flatMap(d => Option(d.getString(VerifyTapField))).filter(_.nonEmpty)
        catch { case _: Exception => None }

    /** Verification for a pipeline incident whose stored script was just
      * regenerated. Fed by a tap: rehearse the feeding tap's stored script in
      * the scratch lane (the output flows through the pipeline with the new
      * script into `_scratch/`), then run the tap for real. No recoverable
      * input (uploads, Kafka, object-store triggers): say so and resolve
      * unverified — the next run will tell. */
    private def verifyPipeline(id: String, incident: Incident): Unit = {
        // A regenerate that ran through an approval was replayed by the policy
        // gate, not by executeFrom: fill evidence.scriptVersions here too
        // (idempotent — replaced by kind).
        IncidentIO.get(id).getOrElse(incident).revertToScriptVersions.keys.foreach(k => recordScriptVersion(id, incident, k))
        val input =
            try inputSource.lastGoodInput(incident)
            catch { case _: Exception => None }
        input match {
            case Some(PipelineInputSource.TapFed(tapName)) =>
                IncidentIO.set(id, VerifyTapField -> tapName)
                if (settings.verifyMode == RecoverySettings.ScratchFirst) {
                    val (verdict, summary) = scratchVerify(id, incident, None, tapName)
                    verdict match {
                        case Thresholds.Pass | ScratchUnavailable =>
                        case Thresholds.NeedsHuman =>
                            // The regenerated script changed the output's shape. It is
                            // already live, so the safety net restores it; a person
                            // decides whether the shape change was intended.
                            revertAll(id, incident)
                            close(id, Incident.Failed, "regenerated script needs a human (script restored): " + clip(summary))
                            return
                        case _ =>
                            revertAll(id, incident)
                            close(id, Incident.Failed, "regenerated script failed scratch verification: " + clip(summary))
                            return
                    }
                }
                val args = new JsonObject()
                args.addProperty("name", tapName)
                executor.call("run_tap", args, id) match {
                    case ToolOutcome.PendingApproval(approvalId) =>
                        IncidentIO.set(id, "awaitingApprovalIds" -> java.util.Arrays.asList(approvalId), "nextActionIndex" -> (Int.MaxValue: java.lang.Integer))
                        IncidentIO.transition(id, Set(Incident.Verifying), Incident.AwaitingApproval)
                        step(id, "gate", "verification run of tap " + tapName + " queued for approval", approvalId = Some(approvalId))
                    case ToolOutcome.Denied(msg) =>
                        step(id, "verify", "verification run denied by policy", Some(clip(msg)))
                        close(id, Incident.Abandoned, "verification denied by agent policy")
                    case ToolOutcome.Failed(msg) =>
                        step(id, "verify", "verification run of tap " + tapName + " failed", Some(clip(msg)))
                        revertAll(id, incident)
                        close(id, Incident.Failed, "verification run failed: " + clip(msg))
                    case ToolOutcome.Ok(text) =>
                        IncidentIO.incrementCounters(id, actions = 1)
                        val publisherToken = extractString(text, "publisherToken")
                        val healthy = isTapHealthy(tapName)
                        val rollupOk = publisherToken.forall(t => pollRollup(id, t, incident))
                        recordRealRunEvidence(id)
                        if (healthy && rollupOk && realStageBreached(id)) {
                            revertAll(id, incident)
                            close(id, Incident.Failed, RealScriptBreachOutcome)
                        } else if (healthy && rollupOk) {
                            step(
                                id,
                                "verify",
                                "verified: real run of tap " + tapName + " healthy" + publisherToken.map(_ => ", pipeline rollup complete").getOrElse("") +
                                    scratchVerdictNote(id)
                            )
                            close(id, Incident.Resolved, "scripts regenerated and verified")
                        } else {
                            step(id, "verify", "verification failed: healthy=" + healthy + ", rollupOk=" + rollupOk, Some(clip(text)))
                            revertAll(id, incident)
                            close(id, Incident.Failed, "verification failed after regenerating — script restored")
                        }
                }
            case other =>
                val fedBy =
                    try inputSource.inputKind(incident)
                    catch { case _: Exception => None }
                val why = other match {
                    case Some(PipelineInputSource.Snapshot(_)) => " (run snapshots cannot be replayed yet)"
                    case _ => fedBy.map(k => " (fed by " + k + ")").getOrElse("")
                }
                step(id, "verify", "regenerated; no replayable input for this pipeline" + why + " — not verified, the next run will tell")
                close(id, Incident.Resolved, "scripts regenerated — unverified")
        }
    }

    /** Production [[revertTap]]: write the stored version snapshot back over
      * the live tap, keeping the live run stamps. */
    private def restoreTapVersion(id: String, tapName: String, target: Int): Unit = {
        val env = DatrisEnvironment.current
        val live = TapConfigIO.read(env.tapTableName, tapName)
        if (live == null) throw new DatrisException("tap " + tapName + " no longer exists")
        val snapshot = EntityVersionIO.get(env.tapVersionTableName, tapName, target)
            .getOrElse(throw new DatrisException("no stored snapshot of tap " + tapName + " version " + target))
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
            lastTestRunColumns = live.lastTestRunColumns,
            // The live stamp names the BAD script's identity; the
            // reverted script is a different one. Drop the stamp so
            // the tap falls back to the unstamped branch of
            // TapCronGate (unrelated edits pass; the next cron change
            // is grandfathered by the prior success).
            lastTestRunScriptId = null
        )
        TapConfigIO.writeVersioned(restored, "incident " + id + ": reverted after failed verification", RecoveryKey.Label)
    }

    // ------------------------------------------------------------------
    // Resume / timeout (called by the sweep)
    // ------------------------------------------------------------------

    /** Move incidents forward whose approvals were decided, and abandon
      * whatever exceeded its limits. */
    def sweepOpenIncidents(): Unit = {
        if (!sweepEnabledOverride.getOrElse(enabled)) return
        IncidentIO.listOpen().foreach { incident =>
            try {
                incident.state match {
                    case Incident.AwaitingApproval if incident.awaitingApprovalIds.nonEmpty =>
                        val decisions = incident.awaitingApprovalIds.flatMap(a => approvalLookup(a).toList)
                        if (decisions.exists(_.state == PendingAction.Rejected))
                            close(incident.id, Incident.Abandoned, "approval rejected by " + decisions.flatMap(_.decidedBy).headOption.getOrElse("a person"))
                        else if (decisions.exists(d => d.state == PendingAction.Expired || d.isExpired()))
                            close(incident.id, Incident.Abandoned, "approval expired undecided")
                        else if (decisions.exists(_.state == PendingAction.Failed)) {
                            // The replay was refused (non-2xx): whatever ran before it
                            // is undone, exactly as an inline failed action would be.
                            val reverted = revertAll(incident.id, incident)
                            val note =
                                if (!reverted) ""
                                else if (incident.resourceType == "pipeline") " — script restored"
                                else " — definition reverted"
                            close(incident.id, Incident.Failed, "approved action failed on execution" + note)
                        } else if (decisions.nonEmpty && decisions.forall(_.state == PendingAction.Executed)) {
                            IncidentIO.set(incident.id, "awaitingApprovalIds" -> java.util.Collections.emptyList[String]())
                            step(incident.id, "gate", "approval executed — continuing")
                            val next = storedNextActionIndex(incident)
                            if (next == Int.MaxValue) {
                                // the executed approval WAS the verification run
                                val replayBody = decisions.flatMap(_.resultBody).headOption
                                resume(incident.id, finishVerifyAfterApproval(incident.id, replayBody))
                            } else {
                                resume(incident.id, executeFrom(incident.id, next + 1))
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

    /** Continue an incident after an approval: on the pool, or inline when
      * specs run synchronously. A runner error fails the incident. */
    private def resume(id: String, body: => Unit): Unit = {
        val r = new Runnable {
            override def run(): Unit =
                try body
                catch {
                    case e: Exception =>
                        logger.error("incident " + id + " runner error after approval: " + e.getMessage, e)
                        try close(id, Incident.Failed, "runner error: " + clip(e.getMessage))
                        catch { case _: Exception => }
                }
        }
        if (runAsync) { pool.submit(r); () }
        else r.run()
    }

    /** `replayBody`: the approved run's response as the policy gate stored it
      * (clipped), for its publisherToken. */
    private def finishVerifyAfterApproval(id: String, replayBody: Option[String] = None): Unit = {
        val incident = IncidentIO.get(id).getOrElse(return
        )
        val pipeline = incident.resourceType == "pipeline"
        val tapHealthy =
            if (incident.resourceType == "tap") isTapHealthy(incident.resourceName)
            else verifyTapOf(id).forall(isTapHealthy)
        // A pipeline incident is judged on the load with the regenerated
        // script, not only on the tap: poll the rollup of the approved run,
        // as the inline verification does.
        val rollupOk =
            !pipeline || !tapHealthy || replayBody.flatMap(publisherTokenIn).forall(t => pollRollup(id, t, incident))
        val healthy = tapHealthy && rollupOk
        recordRealRunEvidence(id)
        if (healthy && realStageBreached(id)) {
            revertAll(id, incident)
            close(id, Incident.Failed, if (pipeline) RealScriptBreachOutcome else RealBreachOutcome)
        } else if (healthy) {
            step(id, "verify", "verified after approved run: resource healthy")
            close(id, Incident.Resolved, if (pipeline) "scripts regenerated and verified (via approved run)" else "recovered and verified (via approved run)")
        } else {
            revertAll(id, incident)
            close(
                id,
                Incident.Failed,
                "approved run did not leave the resource healthy — " + (if (pipeline) "script restored" else "definition reverted")
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private val RealBreachOutcome = "real run breached thresholds after scratch pass — definition reverted (data already landed)"
    private val RealScriptBreachOutcome = "real run breached thresholds after scratch pass — script restored (data already landed)"

    private def scratchVerdictOf(id: String): Option[String] =
        try IncidentIO.raw(id).flatMap(d => Option(d.getString(ScratchVerdictField)))
        catch { case _: Exception => None }

    /** The publisherToken of a (possibly clipped) run response. */
    private def publisherTokenIn(text: String): Option[String] =
        extractString(text, "publisherToken").orElse {
            "\"publisherToken\"\\s*:\\s*\"([^\"]+)\"".r.findFirstMatchIn(Option(text).getOrElse("")).map(_.group(1))
        }

    /** " (scratch verdict …)" for incidents that went through the scratch lane. */
    private def scratchVerdictNote(id: String): String =
        (for {
            i <- IncidentIO.get(id)
            ev <- IncidentEvidenceBuilder.of(i)
            if ev.candidate.isDefined
        } yield " (scratch verdict " + ev.verdict + ")").getOrElse("")

    /** Stage 2 of scratch-first verification: an incident whose candidate was
      * rehearsed in scratch is judged again on what the real run landed. A
      * `fail` there stores the real-run judgement on the evidence and returns
      * true (the caller reverts and fails the incident). Incidents verified
      * the `real` way keep today's informational evidence. Never throws. */
    private def realStageBreached(id: String): Boolean =
        try {
            (for {
                i <- IncidentIO.get(id)
                ev <- IncidentEvidenceBuilder.of(i)
                if ev.candidate.isDefined
                judged <- IncidentEvidenceBuilder.judgeReal(i, ev)
            } yield {
                if (judged.verdict == Thresholds.Fail) {
                    IncidentEvidenceBuilder.store(id, judged)
                    step(id, "verify", "real run breached thresholds: " + judged.summaryLine)
                    true
                } else false
            }).getOrElse(false)
        } catch {
            case e: Exception =>
                logger.debug("incident " + id + ": real-stage check skipped: " + e.getMessage)
                false
        }

    // ------------------------------------------------------------------
    // Scratch-first verification (stage 1)
    // ------------------------------------------------------------------

    /** Run the candidate (or, with no script change, the stored script) in
      * the scratch lane, store the candidate profile + diff + checks +
      * verdict on the evidence and record a `verify` step. Not routed through
      * the executor / policy gate: it is the platform's own safety check and
      * writes only to `_scratch/`. Returns (verdict, one-line summary). */
    private[incident] def scratchVerify(
        id: String,
        incident: Incident,
        candidateScript: Option[String],
        tapName: String = null
    ): (String, String) = {
        val s = settings
        val tapToRun = Option(tapName).getOrElse(incident.resourceName)
        val (verdict, summary) =
            try {
                tapLookup(tapToRun) match {
                    case None =>
                        val msg = "tap " + tapToRun + " not found"
                        storeScratchFailure(id, msg)
                        (Thresholds.Fail, msg)
                    case Some(tap) =>
                        val outcome =
                            documentTapReason(tap) match {
                                case Some(reason) => ScratchOutcome(None, None, Some(reason))
                                case None =>
                                    try {
                                        scratchIncident.set(id)
                                        scratch(tap, candidateScript)
                                    } catch {
                                        case e: Exception => ScratchOutcome(None, Some(Option(e.getMessage).getOrElse(e.getClass.getSimpleName)))
                                    } finally scratchIncident.remove()
                            }
                        if (outcome.unavailable.isDefined) {
                            val msg = "scratch lane not available for " + outcome.unavailable.get + " — verifying with the real run"
                            step(id, "verify", msg)
                            AuditLog.system(
                                "incident",
                                "scratch-verify",
                                "tap",
                                tapToRun,
                                metadata = md("incidentId" -> id, "verdict" -> ScratchUnavailable)
                            )
                            return (ScratchUnavailable, msg)
                        }
                        outcome.candidate match {
                            case None =>
                                val msg = "the scratch run failed: " + clip(outcome.error.getOrElse("no result"))
                                storeScratchFailure(id, msg)
                                (Thresholds.Fail, msg)
                            case Some(candidate) =>
                                val fresh = IncidentIO.get(id).getOrElse(incident)
                                val base = IncidentEvidenceBuilder.of(fresh).getOrElse(emptyEvidence(s))
                                val lastN = IncidentEvidenceBuilder.lastNFor(fresh, Set(candidate.runId))
                                val ev = IncidentEvidenceBuilder.withCandidate(base, candidate, s.thresholds, lastN)
                                IncidentEvidenceBuilder.store(id, ev)
                                // A pass (or needs-human, which a person may
                                // Proceed past) of a candidate script is that
                                // script's test run, so the approved fix can be
                                // stored on a scheduled tap.
                                if ((ev.verdict == Thresholds.Pass || ev.verdict == Thresholds.NeedsHuman) && !tap.isHttp)
                                    candidateScript.foreach(script => stampRehearsal(id, tapToRun, script, candidate, outcome.result))
                                (ev.verdict, ev.summaryLine)
                        }
                }
            } catch {
                case e: Exception => (Thresholds.Fail, "scratch verification error: " + clip(e.getMessage))
            }
        step(id, "verify", "scratch verification: " + summary)
        AuditLog.system("incident", "scratch-verify", "tap", tapToRun, metadata = md("incidentId" -> id, "verdict" -> verdict))
        (verdict, summary)
    }

    private def stampRehearsal(id: String, tapName: String, script: String, candidate: RunProfile, result: Option[TapScriptResult]): Unit =
        try {
            // Remember what the stamp overwrites, so a candidate that is never
            // stored (rejected, expired, failed) hands the tap its own test back.
            // Only the first rehearsal of an incident records it.
            if (IncidentIO.raw(id).forall(d => d.get(RehearsalPriorField) == null))
                tapLookup(tapName).foreach { live =>
                    val prior = new org.bson.Document()
                    prior.put("lastTestRunStatus", live.lastTestRunStatus)
                    prior.put("lastTestRunTime", live.lastTestRunTime)
                    prior.put("lastTestRunRecordCount", (live.lastTestRunRecordCount: java.lang.Integer))
                    prior.put("lastTestRunError", live.lastTestRunError)
                    prior.put("lastTestRunDataType", live.lastTestRunDataType)
                    prior.put("lastTestRunColumns", Option(live.lastTestRunColumns).map(c => new java.util.ArrayList[String](c)).orNull)
                    prior.put("lastTestRunScriptId", live.lastTestRunScriptId)
                    IncidentIO.set(id, RehearsalPriorField -> prior)
                }
            val columns = result.flatMap(r => Option(r.columns)).getOrElse {
                import scala.collection.JavaConverters._
                candidate.columns.map(_.name).asJava
            }
            stampTest(
                tapName,
                RehearsalStamp(
                    scriptId = TapScriptIdentity.ofText(script),
                    recordCount = result.map(_.recordCount).getOrElse(candidate.recordCount.toInt),
                    dataType = result.map(_.dataType).orNull,
                    columns = columns
                )
            )
        } catch {
            case e: Exception => logger.warn("incident " + id + ": could not record the scratch rehearsal as a test run of " + tapName + ": " + e.getMessage)
        }

    /** Production [[stampTest]]: plain (non-versioned) write, as a `mode=test` run. */
    private def writeRehearsalStamp(tapName: String, stamp: RehearsalStamp): Unit = {
        val live = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)
        if (live != null) TapConfigIO.write(stamp.applyTo(live, ai.datris.api.TapAPIController.nowStamp()))
    }

    /** Some(reason) when the scratch lane cannot rehearse `tap`: it emits
      * documents (by its last run) or feeds an unstructured pipeline. */
    private def documentTapReason(tap: TapConfig): Option[String] = {
        val docTap = Option(tap.lastRunDataType).contains("document")
        def unstructuredTarget: Boolean =
            try {
                Option(tap.targetPipeline).filter(_.nonEmpty).flatMap(p => Option(PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, p)))
                    .exists(c => c.source != null && c.source.fileAttributes != null && c.source.fileAttributes.unstructuredAttributes != null)
            } catch { case _: Exception => false }
        if (docTap || unstructuredTarget) Some("document taps") else None
    }

    /** The candidate profile of a scratch run that fed no pipeline (0 records,
      * or a tap with no target pipeline): the script's own record count and
      * column names, no row-level statistics. */
    private[incident] def unfedProfile(tap: TapConfig, result: TapScriptResult, scriptSha: Option[String], durationMs: Long): RunProfile = {
        import scala.collection.JavaConverters._
        val names = Option(result.columns).map(_.asScala.toList).getOrElse(Nil).filter(_ != null)
        RunProfile(
            runId = "scratch:" + tap.name + "@" + Instant.now().toString,
            source = "scratch",
            recordCount = result.recordCount.toLong,
            columns = names.map(n => ai.datris.util.RunProfiler.ColumnStats(n, "null", 0.0, 0L, None, None, None)),
            dqFailures = 0,
            dqPassRate = 1.0,
            durationMs = durationMs,
            scriptSha = scriptSha,
            tapVersion = Some(tap.version),
            pipelineVersion = None,
            sampledRows = 0L,
            computedAt = Instant.now()
        )
    }

    private def emptyEvidence(s: RecoverySettings): IncidentEvidence = IncidentEvidence(
        baseline = None,
        candidate = None,
        real = None,
        diff = None,
        blastRadius = BlastRadius(Nil, Nil, Nil, Nil, None, None, None),
        thresholds = s.thresholds,
        checks = Nil,
        verdict = Thresholds.Pending,
        computedAt = Instant.now()
    )

    /** A scratch run that produced nothing to judge: verdict fail, with the reason as the check. */
    private def storeScratchFailure(id: String, reason: String): Unit =
        try {
            val base = IncidentIO.get(id).flatMap(IncidentEvidenceBuilder.of).getOrElse(emptyEvidence(settings))
            val check = ThresholdCheck("scratchRun", passed = false, observed = clip(reason), limit = "a clean scratch run")
            IncidentEvidenceBuilder.store(id, base.copy(checks = List(check), verdict = Thresholds.Fail, computedAt = Instant.now()))
        } catch {
            case e: Exception => logger.debug("incident " + id + ": scratch failure evidence skipped: " + e.getMessage)
        }

    /** Production [[scratch]]: TapRunner in scratch mode, the rollup polled
      * under the incident, up to [[ProfileRowCap]] landed scratch rows paged
      * through ScratchReader and profiled. */
    private def realScratch(tap: TapConfig, candidateScript: Option[String]): ScratchOutcome = {
        val id = scratchIncident.get()
        val started = System.currentTimeMillis()
        val result = TapRunner.run(tap, mode = TapRunner.ScratchMode, scriptOverride = candidateScript.orNull)
        if (result.error != null) return ScratchOutcome(None, Some(result.error))
        val sha = candidateScript.map(sha256)
        if (result.dataType == "document") return ScratchOutcome(None, None, Some("document taps"))
        // Nothing was fed (0 records, or no target pipeline): judge the
        // script's own output.
        if (result.publisherToken == null)
            return ScratchOutcome(Some(unfedProfile(tap, result, sha, System.currentTimeMillis() - started)), result = Some(result))
        val incident = Option(id).flatMap(IncidentIO.get)
        if (incident.isDefined && !pollRollup(id, result.publisherToken, incident.get))
            return ScratchOutcome(None, Some("the scratch pipeline run did not complete cleanly"))
        val job = ScratchReader.resolve(null, result.publisherToken)
        val scratchSettings = ScratchLoader.settingsFromEnvironment()
        val total: Long = Option(job.resultRowCount).map(_.longValue()).getOrElse(0L)
        val cap = math.min(total, ProfileRowCap.toLong)
        val rows = scala.collection.mutable.ArrayBuffer[Map[String, Any]]()
        var offset = 0L
        var done = false
        // ScratchReader clamps a page to settings.inlineRows, so page through.
        while (!done && offset < cap) {
            val page = ScratchReader.read(scratchSettings, job, Some(offset), Some(math.min(scratchSettings.inlineRows.toLong, cap - offset).toInt))
            val it = page.records.iterator()
            while (it.hasNext) {
                val e = it.next()
                if (e.isJsonObject) rows += jsonRow(e.getAsJsonObject)
            }
            offset += page.returnedCount
            done = page.returnedCount == 0
        }
        val config =
            try Option(PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, tap.targetPipeline))
            catch { case _: Exception => None }
        val stats = RunProfiler.profile(rows.iterator, IncidentEvidenceBuilder.valueSafe(config))
        // The rollup carries no per-row DQ counts: a job that completed is
        // taken as fully passing, the same rule recorded runs follow.
        val ok = job.status == null || job.status == "success" || job.status == "warning"
        ScratchOutcome(
            Some(RunProfile(
                runId = job.pipelineToken,
                source = "scratch",
                recordCount = total,
                columns = stats.columns,
                dqFailures = if (ok) 0 else 1,
                dqPassRate = if (ok) 1.0 else 0.0,
                durationMs = System.currentTimeMillis() - started,
                scriptSha = sha,
                tapVersion = Some(tap.version),
                pipelineVersion = config.map(_.version).filter(_ > 0),
                sampledRows = stats.recordCount,
                computedAt = Instant.now()
            )),
            result = Some(result)
        )
    }

    private def jsonRow(o: JsonObject): Map[String, Any] = {
        import scala.collection.JavaConverters._
        o.entrySet().asScala.map { e =>
            val v = e.getValue
            val out: Any =
                if (v == null || v.isJsonNull) null
                else if (v.isJsonPrimitive) {
                    val p = v.getAsJsonPrimitive
                    if (p.isBoolean) p.getAsBoolean
                    else if (p.isNumber) p.getAsBigDecimal
                    else p.getAsString
                } else v.toString
            e.getKey -> out
        }.toMap
    }

    private def sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .map("%02x".format(_))
            .mkString

    // ------------------------------------------------------------------
    // Proceed past a needs-human verdict
    // ------------------------------------------------------------------

    /** A person accepts a needs-human scratch verdict: only from
      * `awaiting_approval` with `scratchVerdict = needs-human` and nothing
      * queued in Approvals. Restarts the runtime budget and executes the
      * proposal from the first action (inline when runAsync = false). */
    def proceed(id: String, by: String): Either[String, Unit] = {
        val incident = IncidentIO.get(id).getOrElse(return Left("incident " + id + " not found"))
        if (incident.state != Incident.AwaitingApproval)
            return Left("incident is " + incident.state + ", not awaiting a needs-human verdict")
        if (incident.awaitingApprovalIds.nonEmpty || !scratchVerdictOf(id).contains(Thresholds.NeedsHuman))
            return Left("only an incident parked on a needs-human scratch verdict can be proceeded past")
        if (
            !IncidentIO.transition(
                id,
                Set(Incident.AwaitingApproval),
                Incident.Executing,
                ScratchVerdictField -> null,
                "resumedAt" -> Instant.now().toString,
                "nextActionIndex" -> (0: java.lang.Integer)
            )
        ) return Left("incident changed state; reload and try again")
        val who = Option(by).map(_.trim).filter(_.nonEmpty).getOrElse("a person")
        step(id, "gate", "human proceeded past needs-human evidence: " + who)
        AuditLog.system("incident", "proceed", incident.resourceType, incident.resourceName, metadata = md("incidentId" -> id, "by" -> who))
        if (runAsync) { pool.submit(new Runnable { override def run(): Unit = runProceeded(id) }); () }
        else runProceeded(id)
        Right(())
    }

    private def runProceeded(id: String): Unit =
        try executeFrom(id, 0)
        catch {
            case e: Exception =>
                logger.error("incident " + id + " runner error after proceed: " + e.getMessage, e)
                try close(id, Incident.Failed, "runner error: " + clip(e.getMessage))
                catch { case _: Exception => }
        }

    /** Profile the verifying real run into the incident's evidence (diff,
      * checks, verdict). Informational only: verification decisions above do
      * not read it. Never throws. */
    private def recordRealRunEvidence(id: String): Unit =
        try {
            for {
                i <- IncidentIO.get(id)
                ev <- IncidentEvidenceBuilder.of(i)
            } IncidentEvidenceBuilder.store(id, IncidentEvidenceBuilder.afterRealRun(i, ev))
        } catch {
            case e: Exception => logger.debug("incident " + id + ": real-run evidence skipped: " + e.getMessage)
        }

    /** The runtime budget runs from the open — or from the moment a person
      * proceeded past a needs-human verdict, which restarts it. */
    private def runtimeExceeded(incident: Incident): Boolean = {
        val from = incident.resumedAt.filter(_.isAfter(incident.openedAt)).getOrElse(incident.openedAt)
        from.plus(settings.maxRuntimeMinutes.toLong, ChronoUnit.MINUTES).isBefore(Instant.now())
    }

    private def currentTapVersion(name: String): Option[Int] =
        try tapLookup(name).map(_.version)
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
            IncidentIO.raw(incident.id).flatMap(d => Option(d.getInteger("nextActionIndex"))).map(_.intValue()).getOrElse(0)
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
                if (state == Incident.Resolved) suggestLearnNote(i)
                else if (i.resourceType == "tap") restoreRehearsalStamp(id, i)
                webhook(state, id, i.kind, i.resourceType, i.resourceName, Some(outcome))
            }
            logger.info("incident " + id + " closed: " + state + " — " + outcome)
        }
    }

    /** An incident closed without its script fix being stored: if the stored
      * tap still carries the rehearsal stamp for the candidate, put back the
      * test fields it had before (plain write — not a definition edit). A
      * stamp someone replaced since (a manual test) is left alone. */
    private def restoreRehearsalStamp(id: String, before: Incident): Unit =
        try {
            val i = IncidentIO.get(id).getOrElse(before)
            if (!executedScriptFix(i.steps)) {
                val prior = IncidentIO.raw(id).flatMap(d => Option(d.get(RehearsalPriorField, classOf[org.bson.Document])))
                val candidate = i.proposal.flatMap { p =>
                    import scala.collection.JavaConverters._
                    Option(p.getAsJsonArray("actions")).toList.flatMap(_.asScala).filter(_.isJsonObject).map(_.getAsJsonObject)
                        .find(a => a.has("tool") && ScriptFixTools.contains(a.get("tool").getAsString))
                        .flatMap(a => Option(a.getAsJsonObject("args")))
                        .filter(args => args.has("script") && args.get("script").isJsonPrimitive)
                        .map(_.get("script").getAsString)
                }
                for (pr <- prior; script <- candidate; live <- tapLookup(i.resourceName))
                    if (live.lastTestRunScriptId == TapScriptIdentity.ofText(script)) {
                        val cols = Option(pr.get("lastTestRunColumns", classOf[java.util.List[String]])).map(c => new java.util.ArrayList[String](c)).orNull
                        tapWrite(live.copy(
                            lastTestRunStatus = pr.getString("lastTestRunStatus"),
                            lastTestRunTime = pr.getString("lastTestRunTime"),
                            lastTestRunRecordCount = Option(pr.getInteger("lastTestRunRecordCount")).map(_.intValue()).getOrElse(0),
                            lastTestRunError = pr.getString("lastTestRunError"),
                            lastTestRunDataType = pr.getString("lastTestRunDataType"),
                            lastTestRunColumns = cols,
                            lastTestRunScriptId = pr.getString("lastTestRunScriptId")
                        ))
                        logger.info("incident " + id + ": candidate never stored — restored the test run of tap " + i.resourceName)
                    }
            }
        } catch {
            case e: Exception => logger.warn("incident " + id + ": could not restore the tap's test run after a rehearsal: " + e.getMessage)
        }

    /** A resolved incident whose fix changed the tap's script (an executed
      * update_tap / create_tap — inline or through an approval) and whose
      * proposal carries a learnNote: save the note as a suggested prompt
      * fragment. A run-only resolution teaches nothing about the source. */
    private def suggestLearnNote(before: Incident): Unit =
        try {
            val i = IncidentIO.get(before.id).getOrElse(before)
            val note = i.proposal
                .filter(p => p.has("learnNote") && p.get("learnNote").isJsonPrimitive)
                .map(_.get("learnNote").getAsString.trim)
                .filter(_.nonEmpty)
            if (note.isDefined && i.resourceType == "tap" && executedScriptFix(i.steps)) {
                if (LearnNotes.trySuggest(i, note.get))
                    step(i.id, "close", "learn note saved as suggested prompt fragment " + LearnNotes.keyFor(i.id))
            }
        } catch {
            case e: Exception => logger.debug("learn note for incident " + before.id + " skipped: " + e.getMessage)
        }

    private val ScriptFixTools = Set("update_tap", "create_tap")

    /** Whether the steps record an executed update_tap / create_tap: an
      * inline `<tool> ok` execute step, or a gated one whose approval was
      * then executed. */
    private[incident] def executedScriptFix(steps: List[IncidentStep]): Boolean = {
        var queuedFix = false
        steps.exists { s =>
            val summary = Option(s.summary).getOrElse("")
            val tool = summary.takeWhile(_ != ' ')
            if (s.phase == "execute" && ScriptFixTools.contains(tool) && summary.startsWith(tool + " ok")) true
            else if (s.phase == "gate" && summary.endsWith("queued for approval")) { queuedFix = ScriptFixTools.contains(tool); false }
            else s.phase == "gate" && summary.startsWith("approval executed") && queuedFix
        }
    }

    /** Spec seam: when non-null, webhook() hands every payload (the JSON
      * body text) to it instead of HttpUtil.post — also when
      * INCIDENT_WEBHOOK_URL is unset. */
    @volatile private[incident] var webhookSink: String => Unit = null

    /** The one-line evidence summary (numbers and column names, never column
      * values) of the incident's evidence as stored right now. */
    private def storedEvidenceSummary(id: String): Option[String] =
        try IncidentIO.get(id).flatMap(_.evidence).map(e => IncidentEvidence.fromJson(e).summaryLine).filter(_.nonEmpty)
        catch {
            case e: Exception =>
                logger.debug("incident webhook: evidence summary skipped: " + e.getMessage)
                None
        }

    private def webhook(event: String, id: String, kind: String, resourceType: String, resourceName: String, detail: Option[String]): Unit = {
        val sink = webhookSink
        val url = Option(DatrisEnvironment.values).flatMap(v => Option(v.incidentWebhookUrl)).map(_.trim).filter(_.nonEmpty)
        if (sink == null && url.isEmpty) return
        try {
            val o = new JsonObject()
            o.addProperty("event", event)
            o.addProperty("incidentId", id)
            o.addProperty("kind", kind)
            o.addProperty("resourceType", resourceType)
            o.addProperty("resource", resourceName)
            detail.foreach(o.addProperty("detail", _))
            storedEvidenceSummary(id).foreach(o.addProperty("evidenceSummary", _))
            o.addProperty("ts", Instant.now().toString)
            if (sink != null) sink(o.toString)
            else HttpUtil.post(url.get, "application/json", o.toString)
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

    /** A string arg of a proposed action, when present and a primitive. */
    private def argString(args: JsonObject, key: String): Option[String] =
        Option(args).filter(o => o.has(key) && o.get(key).isJsonPrimitive).map(_.get(key).getAsString)

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

        private val ProposalKeys = Set("classification", "summary", "needsHuman", "actions")

        private val FencedObject = """(?s).*```(?:json)?\s*(\{.*?\})\s*```.*""".r

        /** The last balanced `{...}` ending at the final `}`, scanning
          * backwards and tracking depth outside JSON string literals. */
        private[incident] def lastBalancedObject(t: String): Option[String] = {
            val end = t.lastIndexOf('}')
            if (end < 0) None
            else {
                var depth = 0
                var inString = false
                var i = end
                var start = -1
                while (i >= 0 && start < 0) {
                    val c = t.charAt(i)
                    if (c == '"') {
                        var bs = 0
                        var j = i - 1
                        while (j >= 0 && t.charAt(j) == '\\') { bs += 1; j -= 1 }
                        if (bs % 2 == 0) inString = !inString
                    } else if (!inString) {
                        if (c == '}') depth += 1
                        else if (c == '{') {
                            depth -= 1
                            if (depth == 0) start = i
                        }
                    }
                    i -= 1
                }
                if (start >= 0) Some(t.substring(start, end + 1)) else None
            }
        }

        /** The balanced `{...}` starting at `start`, scanning forwards. */
        private def balancedFrom(t: String, start: Int): Option[String] = {
            var depth = 0
            var inString = false
            var escaped = false
            var i = start
            while (i < t.length) {
                val c = t.charAt(i)
                if (inString) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                } else if (c == '"') inString = true
                else if (c == '{') depth += 1
                else if (c == '}') {
                    depth -= 1
                    if (depth == 0) return Some(t.substring(start, i + 1))
                }
                i += 1
            }
            None
        }

        /** Candidate JSON strings, most likely first: the last balanced object,
          * the fenced block, then every `{` from the end (lazily). */
        private def proposalCandidates(t: String): Iterator[String] = {
            val fenced = t match {
                case FencedObject(inner) => Some(inner)
                case _ => None
            }
            val starts = Iterator.iterate(t.lastIndexOf('{'))(i => if (i > 0) t.lastIndexOf('{', i - 1) else -1).takeWhile(_ >= 0)
            lastBalancedObject(t).iterator ++ fenced.iterator ++ starts.flatMap(balancedFrom(t, _))
        }

        /** The proposal object in a model reply. Prefers the LAST object that
          * carries a proposal key; falls back to the first candidate that is a
          * JSON object at all; otherwise the first parse error. */
        private def extractProposalObject(text: String): Either[String, JsonObject] = {
            val t = Option(text).getOrElse("").trim
            var firstError: Option[String] = None
            var firstObject: Option[JsonObject] = None
            val it = proposalCandidates(t)
            while (it.hasNext) {
                val c = it.next()
                try {
                    val e = JsonParser.parseString(c)
                    if (e.isJsonObject) {
                        val o = e.getAsJsonObject
                        if (ProposalKeys.exists(o.has)) return Right(o)
                        if (firstObject.isEmpty) firstObject = Some(o)
                    } else if (firstError.isEmpty) firstError = Some("not a JSON object")
                } catch {
                    case ex: Exception => if (firstError.isEmpty) firstError = Some(String.valueOf(ex.getMessage))
                }
            }
            firstObject match {
                case Some(o) => Right(o)
                case None =>
                    // No candidate at all: parse the whole text for the old error shape.
                    firstError match {
                        case Some(err) => Left(err)
                        case None =>
                            try {
                                val e = JsonParser.parseString(t)
                                if (e.isJsonObject) Right(e.getAsJsonObject) else Left("not a JSON object")
                            } catch { case ex: Exception => Left(String.valueOf(ex.getMessage)) }
                    }
            }
        }

        private[incident] def parseProposal(text: String): Either[String, Proposal] = {
            def invalid(msg: String) = Left("proposal was not valid JSON: " + msg + " — text: " + clip(text))
            extractProposalObject(text) match {
                case Left(msg) => invalid(msg)
                case Right(o) =>
                    proposalFrom(o) match {
                        case Left(msg) => invalid(msg)
                        case ok => ok
                    }
            }
        }

        private def proposalFrom(o: JsonObject): Either[String, Proposal] = {
            try {
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
                case e: Exception => Left(String.valueOf(e.getMessage))
            }
        }

        /** With DATRIS_AI_SAMPLE_VALUES=false a pipeline incident's error text
          * is scrubbed before the model sees it (it can quote row values). The
          * stored incident keeps the full text for people. `errorClass` (the
          * exception class chain, class names only) goes through unscrubbed so
          * the model still knows what kind of failure it is; a value that is
          * not a plain class chain is dropped. */
        private[incident] def triggerForModel(incident: Incident): JsonObject = {
            val t = incident.trigger
            if (t == null || incident.resourceType != "pipeline" || ai.datris.util.AiSampleValues.enabled || !t.has("error")) t
            else {
                val copy = t.deepCopy()
                val e = copy.get("error")
                if (e != null && e.isJsonPrimitive) copy.addProperty("error", ai.datris.util.AiSampleValues.scrubErrorForModel(e.getAsString))
                val c = copy.get("errorClass")
                if (c != null && !(c.isJsonPrimitive && ClassChainPattern.pattern.matcher(c.getAsString).matches())) copy.remove("errorClass")
                copy
            }
        }

        /** "pkg.Class <- pkg.Cause <- ...": Java identifiers and dots only. */
        private val ClassChainPattern = "^[A-Za-z_$][\\w$.]*(?: <- [A-Za-z_$][\\w$.]*)*$".r

        private def buildDiagnosisMessage(incident: Incident): String = {
            val sb = new StringBuilder
            sb.append("(Automated incident — you are running headless as the platform's recovery agent; no operator is watching this conversation.)\n\n")
            sb.append("Incident ").append(incident.id).append(": ").append(incident.kind)
                .append(" on ").append(incident.resourceType).append(" `").append(incident.resourceName).append("`.\n")
            sb.append("Trigger: ").append(triggerForModel(incident).toString.take(2000)).append("\n")
            // Doctor errors that did not make this a platform incident: real,
            // possibly related — the model weighs them, the narrative keeps them.
            incident.steps.filter(_.summary.startsWith(DoctorNotePrefix)).foreach(st => sb.append(st.summary.take(500)).append("\n"))
            IncidentEvidenceBuilder.of(incident).foreach(e => sb.append(IncidentEvidenceBuilder.blastRadiusLine(incident, e.blastRadius)).append("\n"))
            sb.append("\n")
            sb.append("Diagnose this incident using the read-only tools available, then END your reply with ONLY a JSON object (no prose after it). " +
                "Put any explanation in the object's summary field, not in text before the object:\n")
            sb.append(
                """{"classification":"transient|structural-script|structural-schema|credential|platform|needs-human","summary":"<one line>","needsHuman":<bool>,"actions":[{"tool":"run_tap|test_tap|update_tap|regenerate_pipeline_scripts","args":{...},"purpose":"<one line>"}],"learnNote":"<optional source quirk worth remembering>"}"""
            ).append("\n\n")
            if (incident.kind == Incident.KindSchemaDrift)
                sb.append(
                    "This is a schema_drift incident: the tap's latest scheduled run SUCCEEDED but returned a different column set than the run before it. " +
                        "The trigger lists the added and removed columns (a rename shows as one removed plus one added — say so when it looks like one). " +
                        "Use get_lineage to name what is downstream and get_pipeline / list_pipeline_versions to judge whether the destination schema still fits; " +
                        "summarize the change and what a person should do about it.\n\n"
                )
            sb.append("Rules for the proposal:\n")
            if (incident.resourceType == "pipeline")
                sb.append(
                    "- For pipeline incidents the only executable action is regenerate_pipeline_scripts with args {\"name\": \"" + incident.resourceName +
                        "\", \"kind\": \"dataQuality|transformation\"}; propose it when the failing step is the AI data-quality or transformation script " +
                        "(the trigger's failingStage says so: dq-script or transformation-script). The platform regenerates the stored script from the " +
                        "pipeline's current instruction, verifies it by re-running the feeding tap when there is one, and restores the previous script " +
                        "version if verification fails. Destination, schema and upload problems (failingStage loader or other) are needsHuman=true with an " +
                        "empty actions list. Tap actions are discarded for pipeline incidents.\n"
                )
            sb.append("- transient (rate limit, network blip, quota): propose at most one run_tap.\n")
            sb.append("- every action's args MUST include \"name\": \"" + incident.resourceName + "\" — actions naming any other tap are discarded.\n")
            sb.append(
                "- structural-script (source shape changed, script bug): propose update_tap with args {\"name\": ..., \"script\": \"<the corrected full script>\"} (the platform replaces the tap's script with it), then run_tap.\n"
            )
            sb.append(
                "- structural-schema (destination/type mismatch), credential (the source rejected the tap's key or token) or an upstream outage: needsHuman=true with an empty actions list and a clear summary of what a person must do. The platform classifies credential failures itself and never runs tap actions for them.\n"
            )
            sb.append("- Never propose deletes, secret changes, or schema migrations — they are not executable here.\n")
            sb.append(
                "- The platform runs the doctor's quick check (no AI probes) before this diagnosis and classifies platform errors itself, so you do not need to call run_doctor; if you do, use mode \"quick\" with no AI probes, and if it reports an error classify as platform and quote its remediation.\n"
            )
            sb.append(
                "- The platform (not you) executes the actions and gates each one through the agent policy; the platform runs your candidate in a scratch lane first, compares it with the last good run, and only then runs it for real; it reverts your script change if either stage fails."
            )
            sb.toString
        }
    }

    private val RecoveryAddendum: String =
        "\n\n## Recovery-agent mode\n\n" +
            "This is a HEADLESS diagnosis run: no operator is present, nothing you write is a conversation. " +
            "Use only the read-only tools provided, keep tool calls few and purposeful, and end your reply with the single JSON proposal object you were asked for — " +
            "the reply must END with that object, and any explanation goes in its summary field, not in prose before the object. " +
            "Do not ask questions; when information is missing, classify as needs-human and say what a person should check. " +
            "Use get_lineage to say what is downstream before proposing a change.\n"

    /** REST-backed executor. Every executable action maps to exactly ONE
      * platform call, so the policy gate queues exactly that call and an
      * approval replay performs exactly it — composite MCP tools (which fan
      * out into several calls) are deliberately not used for execution.
      * Calls carry the recovery key + incident id like any agent. */
    object RealExecutor extends ToolExecutor {

        private[incident] def restFor(tool: String, args: JsonObject): Option[(String, String, Option[String])] = {
            def name = if (args != null && args.has("name")) args.get("name").getAsString else ""
            def enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
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
                case "run_doctor" =>
                    // Quick mode; AI probes only when explicitly asked for.
                    val mode = if (args != null && args.has("mode") && args.get("mode").isJsonPrimitive) args.get("mode").getAsString else "quick"
                    val ai = args != null && args.has("ai") && args.get("ai").isJsonPrimitive && args.get("ai").getAsBoolean
                    val q = "mode=" + (if (mode == "full") "full" else "quick") + (if (ai) "&probes=ai" else "")
                    Some(("GET", "/api/v1/doctor?" + q, None))
                case RegenerateScripts if argString(args, "name").exists(_.nonEmpty) && argString(args, "kind").exists(PipelineScripts.Kinds.contains) =>
                    // No storage / overwrite: a hand-edited repository file is
                    // never overwritten by the agent; a 409 is a failed action.
                    Some((
                        "POST",
                        "/api/v1/pipelines/" + enc(name) + "/codegen-scripts/" + args.get("kind").getAsString + "/regenerate",
                        Some("{}")
                    ))
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
