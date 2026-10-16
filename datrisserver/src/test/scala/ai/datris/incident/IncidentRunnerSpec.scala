package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.incident.IncidentRunner.{ProposedAction, RealDiagnoser, ScratchOutcome, ToolOutcome}
import ai.datris.model.TapConfig
import ai.datris.policy.RecoverySettings
import ai.datris.util.RunProfiler.{ColumnStats, Quantiles}
import com.google.gson.{JsonObject, JsonParser}
import org.bson.Document
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.collection.JavaConverters._
import scala.collection.mutable

class IncidentRunnerSpec extends AnyFunSuite {

    private def incident(kind: String) = Incident(
        id = "inc_000000000001",
        kind = kind,
        resourceType = "tap",
        resourceName = "prices",
        openedAt = Instant.now(),
        state = Incident.Open,
        trigger = new JsonObject()
    )

    private def action(tool: String, name: String = "prices") = {
        val args = new JsonObject()
        if (name != null) args.addProperty("name", name)
        ProposedAction(tool, args, "p")
    }

    // ------------------------------------------------------------------
    // Proposal parsing (the LLM/code boundary)
    // ------------------------------------------------------------------

    test("parses a bare JSON proposal") {
        val r = RealDiagnoser.parseProposal(
            """{"classification":"structural-script","summary":"field renamed","needsHuman":false,
              |"actions":[{"tool":"create_tap","args":{"name":"prices"},"purpose":"fix field"},{"tool":"test_tap","args":{"name":"prices"},"purpose":"validate"}],
              |"learnNote":"source renamed account_id"}""".stripMargin
        )
        assert(r.isRight)
        val p = r.right.get
        assert(p.classification == "structural-script")
        assert(!p.needsHuman)
        assert(p.actions.map(_.tool) == List("create_tap", "test_tap"))
        assert(p.learnNote.contains("source renamed account_id"))
    }

    test("parses a fenced proposal with prose around it") {
        val text =
            """I inspected the logs. Here is my proposal:
              |```json
              |{"classification":"transient","summary":"rate limited","needsHuman":false,"actions":[{"tool":"run_tap","args":{"name":"prices"},"purpose":"retry"}]}
              |```
              |""".stripMargin
        val r = RealDiagnoser.parseProposal(text)
        assert(r.isRight)
        assert(r.right.get.classification == "transient")
        assert(r.right.get.actions.map(_.tool) == List("run_tap"))
    }

    test("prose with an embedded object still parses via brace extraction") {
        val r = RealDiagnoser.parseProposal(
            """Diagnosis done. {"classification":"needs-human","summary":"credentials revoked","needsHuman":true,"actions":[]} That's all."""
        )
        assert(r.isRight)
        assert(r.right.get.needsHuman)
    }

    test("needs-human classification forces needsHuman even when the flag is absent") {
        val r = RealDiagnoser.parseProposal("""{"classification":"needs-human","summary":"upstream outage","actions":[]}""")
        assert(r.isRight && r.right.get.needsHuman)
    }

    test("non-JSON output is a Left, never a crash") {
        assert(RealDiagnoser.parseProposal("I could not determine the cause.").isLeft)
        assert(RealDiagnoser.parseProposal("").isLeft)
    }

    // ------------------------------------------------------------------
    // Action filtering (code decides, not the model)
    // ------------------------------------------------------------------

    test("only executable tools survive filtering — deletes and migrations never run") {
        val p = IncidentRunner.Proposal(
            "structural-script",
            "s",
            needsHuman = false,
            List(action("delete_tap"), action("update_tap"), action("apply_dest_types"), action("create_tap"), action("test_tap"), action("update_secret")),
            None
        )
        val filtered = IncidentRunner.filterActions(incident(Incident.KindTapFailure), p)
        assert(filtered.map(_.tool) == List("update_tap", "create_tap", "test_tap"))
    }

    test("actions are pinned to the incident's own resource — other names are discarded") {
        val p = IncidentRunner.Proposal(
            "structural-script",
            "s",
            needsHuman = false,
            List(action("run_tap", "someone-elses-tap"), action("create_tap", null), action("run_tap")),
            None
        )
        val filtered = IncidentRunner.filterActions(incident(Incident.KindTapFailure), p)
        assert(filtered.map(_.tool) == List("run_tap"))
        assert(filtered.forall(_.args.get("name").getAsString == "prices"))
    }

    test("stale incidents are limited to a single run_tap") {
        val p = IncidentRunner.Proposal("transient", "s", needsHuman = false, List(action("update_tap"), action("run_tap"), action("run_tap")), None)
        val filtered = IncidentRunner.filterActions(incident(Incident.KindStale), p)
        assert(filtered.map(_.tool) == List("run_tap"))
    }

    test("action count is capped by maxActionsPerIncident") {
        val many = List.fill(10)(action("run_tap"))
        val p = IncidentRunner.Proposal("transient", "s", needsHuman = false, many, None)
        val filtered = IncidentRunner.filterActions(incident(Incident.KindTapFailure), p)
        assert(filtered.size == IncidentRunner.settings.maxActionsPerIncident)
    }

    // ------------------------------------------------------------------
    // Outcome classification
    // ------------------------------------------------------------------

    test("pending_approval is detected at any nesting depth (composite tools wrap it)") {
        assert(IncidentRunner.findPendingApproval("""{"status":"pending_approval","approvalId":"pa_1"}""").contains("pa_1"))
        assert(IncidentRunner.findPendingApproval(
            """{"message":"Tap created successfully","tap":{"status":"pending_approval","approvalId":"pa_2","action":"tap:create"}}"""
        ).contains("pa_2"))
        assert(IncidentRunner.findPendingApproval("""{"results":[{"ok":true},{"inner":{"status":"pending_approval","approvalId":"pa_3"}}]}""").contains("pa_3"))
        assert(IncidentRunner.findPendingApproval("""{"status":"ok","approvalId":"x"}""").isEmpty)
        assert(IncidentRunner.findPendingApproval("not json").isEmpty)
    }

    test("a tool result with an embedded error field is a failure") {
        assert(IncidentRunner.hasErrorField("""{"mode":"test","recordCount":0,"error":"Tap script failed (exit code 1)"}"""))
        assert(IncidentRunner.hasErrorField("""{"error":"capability denied"}"""))
        assert(!IncidentRunner.hasErrorField("""{"mode":"run","recordCount":10,"error":""}"""))
        assert(!IncidentRunner.hasErrorField("""{"mode":"run","recordCount":10}"""))
        assert(!IncidentRunner.hasErrorField("plain text"))
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    test("open is a no-op while the feature is disabled") {
        assert(!IncidentRunner.enabled)
        assert(IncidentRunner.open(Incident.KindTapFailure, "tap", "x", new JsonObject()).isEmpty)
    }

    test("diagnosis tools are strictly read-only and executable tools are strictly bounded") {
        val mutating = Set(
            "run_tap",
            "test_tap",
            "update_tap",
            "create_tap",
            "delete_tap",
            "update_secret",
            "apply_dest_types",
            "kill_job",
            "restore_tap_version",
            "delete_pipeline",
            "create_pipeline",
            "upload_data"
        )
        assert(IncidentRunner.DiagnosisTools.intersect(mutating).isEmpty)
        // Phase 3: the doctor is a read-only diagnostic the agent may consult.
        assert(IncidentRunner.DiagnosisTools.contains("run_doctor"))
        // self-healing-4: lineage, provenance, discovery and pipeline version
        // history are readable during diagnosis (all metadata:read / pipeline:read).
        for (t <- Seq("get_lineage", "find_data", "get_provenance", "list_pipeline_versions", "diff_pipeline_versions"))
            assert(IncidentRunner.DiagnosisTools.contains(t), t + " in DiagnosisTools")
        assert(RecoveryKey.Capabilities.contains("config:read"))
        // self-healing-5: pipeline incidents may regenerate their stored AI scripts.
        assert(IncidentRunner.ExecutableTools == Set("run_tap", "test_tap", "update_tap", "create_tap", "regenerate_pipeline_scripts"))
        assert(!IncidentRunner.DiagnosisTools.contains("regenerate_pipeline_scripts"))
    }

    // ------------------------------------------------------------------
    // Incident model round-trip
    // ------------------------------------------------------------------

    test("incident document round-trips") {
        val trigger = JsonParser.parseString("""{"error":"KeyError: account_id","retryCount":3}""").getAsJsonObject
        val i = incident(Incident.KindTapFailure).copy(
            trigger = trigger,
            steps = List(IncidentStep(Instant.parse("2026-08-31T10:00:00Z"), "open", "opened", Some("d"), Some("pa_1"))),
            classification = Some("structural-script"),
            aiCalls = 4,
            actionsTaken = 2,
            awaitingApprovalIds = List("pa_2"),
            revertToVersion = Some(7),
            closedAt = Some(Instant.parse("2026-08-31T10:10:00Z")),
            outcome = Some("recovered")
        )
        val back = Incident.fromDocument(i.toDocument)
        assert(back == i)
    }

    // Story: Incident evidence model (plans/stories/self-healing-1-evidence-model.md),
    // Acceptance bullet 4 — `Incident.evidence` is a nullable subdocument;
    // pre-upgrade documents read unchanged; `verdict` surfaces only with evidence.

    private val evidenceJson =
        JsonParser.parseString(
            """{"verdict":"pending","blastRadius":{"downstreamPipelines":["orders"],"ucTables":[]},"computedAt":"2026-10-16T10:00:00Z"}"""
        ).getAsJsonObject

    test("incident with evidence round-trips through toDocument / fromDocument") {
        val i = incident(Incident.KindTapFailure).copy(
            openedAt = Instant.parse("2026-10-16T10:00:00Z"),
            evidence = Some(evidenceJson)
        )
        val back = Incident.fromDocument(i.toDocument)
        assert(back.evidence == Some(evidenceJson))
        assert(back == i)
    }

    test("a document without evidence (pre-upgrade) yields evidence = None") {
        val i = incident(Incident.KindTapFailure).copy(openedAt = Instant.parse("2026-10-16T10:00:00Z"))
        val d = i.toDocument
        d.remove("evidence")
        d.remove("verdict")
        val back = Incident.fromDocument(d)
        assert(back.evidence.isEmpty)
        assert(back == i)
    }

    test("toPublicJson exposes evidence and a top-level verdict only when evidence exists") {
        val without = incident(Incident.KindTapFailure).toPublicJson
        assert(!without.has("verdict"), without)
        assert(!without.has("evidence"), without)

        val withEv = incident(Incident.KindTapFailure).copy(evidence = Some(evidenceJson)).toPublicJson
        assert(withEv.has("verdict"), withEv)
        assert(withEv.get("verdict").getAsString == "pending")
        assert(withEv.getAsJsonObject("evidence") == evidenceJson)
    }

    test("public JSON exposes the narrative but keeps state machinery consistent") {
        val i = incident(Incident.KindVolume).copy(revertToVersion = Some(7))
        val json = i.toPublicJson.toString
        assert(json.contains("\"kind\":\"volume\""))
        assert(json.contains("\"state\":\"open\""))
        assert(json.contains("\"revertToVersion\":7"))
        assert(Incident.OpenStates.contains(i.state))
        assert(!incident(Incident.KindVolume).toPublicJson.has("revertToVersion"))
    }

    // ==================================================================
    // Story: Scratch-first verification with evidence thresholds
    // (plans/stories/self-healing-3-scratch-first-verify.md), Acceptance bullet 3.
    //
    // The state machine is driven end to end with fakes only (no Mongo, no
    // LLM, no tap runner): fake Diagnoser / ToolExecutor (existing seams),
    // `runAsync = false`, and the seams below. The story names the scratch
    // seam and a "revert version seam"; the other names are chosen here so
    // the loop can run without Mongo — keep them or update this spec:
    //
    // {{{
    // object IncidentRunner {
    //   /** What the scratch lane produced. `candidate` None with `error` Some = the scratch run failed. */
    //   case class ScratchOutcome(candidate: Option[RunProfile], error: Option[String] = None)
    //   /** The story's ScratchRunner seam: (stored tap, candidate script) => outcome.
    //     * Production = TapRunner.run(mode = "scratch", scriptOverride) + rollup poll
    //     * + ScratchReader paging + RunProfiler.profile. */
    //   @volatile var scratch: (TapConfig, Option[String]) => ScratchOutcome
    //   /** When non-null, used instead of PolicyIO.current.recovery. */
    //   @volatile private[incident] var settingsOverride: RecoverySettings = null
    //   /** Every stored-tap read in the runner (TapConfigIO.read): scratch input,
    //     * isTapHealthy, currentTapVersion, revertIfNeeded's live version. */
    //   @volatile private[incident] var tapLookup: String => Option[TapConfig]
    //   /** The revert version seam: the direct restore inside revertIfNeeded,
    //     * (incidentId, tapName, toVersion), called only when live.version != target. */
    //   @volatile private[incident] var revertTap: (String, String, Int) => Unit
    //   /** Only from awaiting_approval with scratchVerdict = needs-human. Honours runAsync. */
    //   def proceed(id: String, by: String): Either[String, Unit]
    // }
    // object IncidentIO {
    //   /** When non-null, every IncidentIO call (and the runner's raw
    //     * nextActionIndex / scratchVerdict reads) is served by it instead of Mongo. */
    //   @volatile private[incident] var store: IncidentIO.Store = null
    //   trait Store {
    //     def insert(i: Incident): Unit
    //     def get(id: String): Option[Incident]
    //     def list(state: Option[String], limit: Int): List[Incident]   // "open" = OpenStates
    //     def transition(id: String, from: Set[String], to: String, sets: Seq[(String, Any)]): Boolean
    //     def appendStep(id: String, step: IncidentStep): Unit
    //     def set(id: String, sets: Seq[(String, Any)]): Unit
    //     def incrementCounters(id: String, aiCalls: Int, actions: Int): Unit
    //     def raw(id: String): Option[Document]
    //   }
    // }
    // object IncidentEvidenceBuilder {
    //   /** When non-null, replaces the lineage read that profiles the verifying real run. */
    //   @volatile private[incident] var realProfileOverride: (Incident, Option[RunProfile]) => Option[RunProfile] = null
    // }
    // }}}
    //
    // The incident is seeded with evidence (a baseline) so runIncident skips
    // the at-open lineage reads.
    // ==================================================================

    /** In-memory incident store over the same documents Mongo would hold. */
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

    private val CandidateScript = "def fetch():\n    return [{'id': 1, 'price': 10}]\n"

    private val t0 = Instant.parse("2026-10-16T10:00:00Z")
    private val q = Quantiles(1.0, 10.0, 20.0, 30.0, 99.0)
    private val baseColumns = List(
        ColumnStats("id", "number", 0.0, 100L, Some(q), None, None),
        ColumnStats("price", "number", 0.0, 90L, Some(q), None, None),
        ColumnStats("region", "string", 0.0, 3L, None, None, Some(List(("eu", 60L), ("us", 40L))))
    )

    private def runProfile(runId: String, source: String, records: Long, columns: List[ColumnStats] = baseColumns): RunProfile =
        RunProfile(
            runId = runId,
            source = source,
            recordCount = records,
            columns = columns,
            dqFailures = 0,
            dqPassRate = 1.0,
            durationMs = 1000L,
            scriptSha = None,
            tapVersion = Some(3),
            pipelineVersion = None,
            sampledRows = records,
            computedAt = t0
        )

    private val baseline = runProfile("run_base", "landed-sample", 100L)
    private val healthyCandidate = runProfile("scratch_1", "scratch", 100L)
    private val healthyReal = runProfile("run_real", "landed-sample", 100L)

    private def evidenceWith(b: Option[RunProfile]): IncidentEvidence = IncidentEvidence(
        baseline = b,
        candidate = None,
        real = None,
        diff = None,
        blastRadius = BlastRadius(Nil, Nil, Nil, Nil, None, None, None),
        thresholds = Thresholds.Default,
        checks = Nil,
        verdict = Thresholds.Pending,
        computedAt = t0
    )

    private case class Call(tool: String, args: JsonObject, stateAtCall: String)

    /** Everything one scenario observed. */
    private class Ctx(val id: String, val store: FakeStore) {
        val calls = mutable.ArrayBuffer[Call]()
        val scratchCalls = mutable.ArrayBuffer[(String, Option[String])]()
        val reverts = mutable.ArrayBuffer[(String, String, Int)]()
        @volatile var diagnoseCalls: Int = 0
        @volatile var tap: TapConfig = TapConfig(name = "prices", description = "d", targetPipeline = "orders", version = 3, lastRunStatus = "failure")
        def incident: Incident = store.get(id).getOrElse(fail("incident " + id + " vanished"))
        def tools: List[String] = calls.map(_.tool).toList
        def summaries: List[String] = incident.steps.map(_.summary)
        def ev: IncidentEvidence = IncidentEvidenceBuilder.of(incident).getOrElse(fail("no evidence on " + incident))
    }

    private val structuralProposal: IncidentRunner.Proposal = {
        val upd = new JsonObject()
        upd.addProperty("name", "prices")
        upd.addProperty("script", CandidateScript)
        val run = new JsonObject()
        run.addProperty("name", "prices")
        IncidentRunner.Proposal(
            "structural-script",
            "source renamed account_id",
            needsHuman = false,
            List(ProposedAction("update_tap", upd, "fix the field name"), ProposedAction("run_tap", run, "land the fix")),
            None
        )
    }

    /** Install every seam, seed one open tap_failure incident and run `body`.
      * `candidate` / `real` are what the scratch lane and the verifying real
      * run profile to. */
    private def scenario[T](
        candidate: Option[RunProfile] = Some(healthyCandidate),
        real: Option[RunProfile] = Some(healthyReal),
        settings: RecoverySettings = RecoverySettings(mode = RecoverySettings.Propose),
        seedBaseline: Option[RunProfile] = Some(baseline),
        seedState: String = Incident.Open,
        kind: String = Incident.KindTapFailure,
        trigger: String = """{"error":"KeyError: account_id"}""",
        proposal: IncidentRunner.Proposal = structuralProposal,
        doctorResponse: String = "{}"
    )(body: Ctx => T): T = {
        val store = new FakeStore
        val ctx = new Ctx(Incident.newId(), store)
        store.insert(Incident(
            id = ctx.id,
            kind = kind,
            resourceType = "tap",
            resourceName = "prices",
            openedAt = Instant.now(),
            state = seedState,
            trigger = JsonParser.parseString(trigger).getAsJsonObject,
            steps = List(IncidentStep(Instant.now(), "open", kind + " signal on tap prices")),
            evidence = Some(evidenceWith(seedBaseline).toJson)
        ))

        val saved = (
            IncidentRunner.diagnoser,
            IncidentRunner.executor,
            IncidentRunner.runAsync,
            IncidentRunner.scratch,
            IncidentRunner.settingsOverride,
            IncidentRunner.tapLookup,
            IncidentRunner.revertTap,
            IncidentIO.store,
            IncidentEvidenceBuilder.realProfileOverride
        )
        IncidentIO.store = store
        IncidentRunner.runAsync = false
        IncidentRunner.settingsOverride = settings
        IncidentRunner.diagnoser = new IncidentRunner.Diagnoser {
            override def diagnose(incident: Incident, onAiCall: () => Unit): Either[String, IncidentRunner.Proposal] = {
                ctx.diagnoseCalls += 1
                onAiCall()
                Right(proposal)
            }
        }
        IncidentRunner.executor = new IncidentRunner.ToolExecutor {
            override def call(tool: String, args: JsonObject, incidentId: String): ToolOutcome = {
                ctx.calls.synchronized { ctx.calls += Call(tool, args, store.get(incidentId).map(_.state).orNull) }
                tool match {
                    case "update_tap" | "create_tap" =>
                        ctx.tap = ctx.tap.copy(version = ctx.tap.version + 1)
                        ToolOutcome.Ok("""{"message":"script updated"}""")
                    case "run_tap" =>
                        ctx.tap = ctx.tap.copy(lastRunStatus = "success", lastRunRecordCount = real.map(_.recordCount.toInt).getOrElse(0))
                        ToolOutcome.Ok("""{"mode":"run","recordCount":""" + real.map(_.recordCount).getOrElse(0L) + "}")
                    case "get_pipeline_status" => ToolOutcome.Ok("""{"allDone":true,"status":"complete"}""")
                    case "run_doctor" => ToolOutcome.Ok(doctorResponse)
                    case _ => ToolOutcome.Ok("{}")
                }
            }
        }
        IncidentRunner.scratch = (t: TapConfig, script: Option[String]) => {
            ctx.scratchCalls.synchronized { ctx.scratchCalls += ((t.name, script)) }
            ScratchOutcome(candidate, if (candidate.isEmpty) Some("scratch run failed") else None)
        }
        IncidentRunner.tapLookup = (name: String) => if (name == "prices") Some(ctx.tap) else None
        IncidentRunner.revertTap = (incidentId: String, tapName: String, toVersion: Int) => {
            ctx.reverts.synchronized { ctx.reverts += ((incidentId, tapName, toVersion)) }
            ctx.tap = ctx.tap.copy(version = toVersion)
        }
        IncidentEvidenceBuilder.realProfileOverride = (_: Incident, _: Option[RunProfile]) => real

        try body(ctx)
        finally {
            IncidentRunner.diagnoser = saved._1
            IncidentRunner.executor = saved._2
            IncidentRunner.runAsync = saved._3
            IncidentRunner.scratch = saved._4
            IncidentRunner.settingsOverride = saved._5
            IncidentRunner.tapLookup = saved._6
            IncidentRunner.revertTap = saved._7
            IncidentIO.store = saved._8
            IncidentEvidenceBuilder.realProfileOverride = saved._9
        }
    }

    private val Mutations = Set("update_tap", "create_tap", "run_tap", "test_tap")

    test("scratch pass: actions execute, the real run verifies, resolved — scratch verification step comes before execute") {
        scenario() { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.scratchCalls.toList == List(("prices", Some(CandidateScript))), "the candidate script from update_tap ran in the scratch lane")
            assert(c.tools.filter(Mutations) == List("update_tap", "run_tap"), c.tools)
            val i = c.incident
            assert(i.state == Incident.Resolved, i.outcome + " / " + c.summaries)
            val scratchAt = i.steps.indexWhere(_.summary.contains("scratch verification"))
            val executeAt = i.steps.indexWhere(_.phase == "execute")
            assert(scratchAt >= 0, "a scratch verification step: " + c.summaries)
            assert(executeAt >= 0, "an execute step: " + c.summaries)
            assert(scratchAt < executeAt, "scratch verification precedes execute: " + c.summaries)
            assert(c.ev.candidate.exists(_.runId == "scratch_1"), "the candidate profile is on the evidence")
            assert(c.reverts.isEmpty)
        }
    }

    test("scratch fail: failed, no update_tap / run_tap reached the executor, actionsTaken == 0") {
        // 60 % of the rows dropped: maxRecordDeltaPct (25) breached.
        scenario(candidate = Some(runProfile("scratch_1", "scratch", 40L))) { c =>
            IncidentRunner.runIncident(c.id)
            val i = c.incident
            assert(i.state == Incident.Failed, i.outcome + " / " + c.summaries)
            assert(c.tools.filter(Mutations).isEmpty, "nothing mutating ran: " + c.tools)
            assert(i.actionsTaken == 0)
            assert(i.outcome.exists(_.startsWith("candidate failed scratch verification")), i.outcome)
            assert(c.ev.verdict == Thresholds.Fail)
            assert(c.tap.version == 3, "tap version unchanged")
            assert(c.reverts.isEmpty, "no mutation happened, so nothing to restore")
        }
    }

    test("scratch needs-human: awaiting_approval with no approval ids; proceed moves to executing and the actions then run") {
        // A column removed with allowColumnRemoval = false.
        scenario(candidate = Some(runProfile("scratch_1", "scratch", 100L, baseColumns.filterNot(_.name == "region")))) { c =>
            IncidentRunner.runIncident(c.id)
            val parked = c.incident
            assert(parked.state == Incident.AwaitingApproval, parked.outcome + " / " + c.summaries)
            assert(parked.awaitingApprovalIds.isEmpty, "nothing queued in Approvals: " + parked.awaitingApprovalIds)
            assert(c.tools.filter(Mutations).isEmpty, "nothing mutating ran before a human decided: " + c.tools)
            assert(parked.steps.exists(s => s.phase == "propose" && s.summary.startsWith("needs a human")), c.summaries)
            assert(c.ev.verdict == Thresholds.NeedsHuman)

            val r = IncidentRunner.proceed(c.id, "todd")
            assert(r == Right(()), r)
            val mutating = c.calls.filter(k => Mutations.contains(k.tool)).toList
            assert(mutating.map(_.tool) == List("update_tap", "run_tap"), c.tools)
            assert(mutating.head.stateAtCall == Incident.Executing, "the incident is executing when the first action runs")
            assert(c.summaries.exists(_.contains("human proceeded past needs-human evidence: todd")), c.summaries)
            assert(c.incident.state == Incident.Resolved, c.incident.outcome + " / " + c.summaries)
        }
    }

    test("proceed is Left on a diagnosing incident, and on an approval-queued one without a needs-human verdict") {
        scenario(seedState = Incident.Diagnosing) { c =>
            assert(IncidentRunner.proceed(c.id, "todd").isLeft)
            assert(c.incident.state == Incident.Diagnosing)
            assert(c.calls.isEmpty)
        }
        scenario(seedState = Incident.AwaitingApproval) { c =>
            c.store.set(c.id, Seq("awaitingApprovalIds" -> java.util.Arrays.asList("pa_1")))
            assert(IncidentRunner.proceed(c.id, "todd").isLeft, "only a needs-human scratch verdict can be proceeded past")
            assert(c.calls.isEmpty)
        }
        scenario() { c =>
            assert(IncidentRunner.proceed("inc_doesnotexist", "todd").isLeft)
        }
    }

    test("verifyMode = real never invokes the scratch seam") {
        scenario(settings = RecoverySettings(mode = RecoverySettings.Propose, verifyMode = "real")) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.scratchCalls.isEmpty, "scratch lane skipped: " + c.scratchCalls)
            assert(c.tools.filter(Mutations) == List("update_tap", "run_tap"), c.tools)
            assert(c.incident.state == Incident.Resolved, c.incident.outcome + " / " + c.summaries)
            assert(!c.summaries.exists(_.contains("scratch verification")), c.summaries)
        }
    }

    test("real-stage breach after a scratch pass reverts the definition and says data already landed") {
        // Scratch looked fine; the real run landed 30 % of the baseline rows.
        scenario(real = Some(runProfile("run_real", "landed-sample", 30L))) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.scratchCalls.size == 1)
            assert(c.tools.filter(Mutations) == List("update_tap", "run_tap"), c.tools)
            val i = c.incident
            assert(i.state == Incident.Failed, i.outcome + " / " + c.summaries)
            assert(c.reverts.toList == List((c.id, "prices", 3)), "revertIfNeeded restored the pre-incident version: " + c.reverts)
            assert(i.outcome.exists(_.contains("data already landed")), i.outcome)
        }
    }

    test("no baseline: the scratch verdict is needs-human with the baseline check, and nothing executes") {
        scenario(seedBaseline = None) { c =>
            IncidentRunner.runIncident(c.id)
            val i = c.incident
            assert(i.state == Incident.AwaitingApproval, i.outcome + " / " + c.summaries)
            assert(c.tools.filter(Mutations).isEmpty, c.tools)
            assert(c.ev.verdict == Thresholds.NeedsHuman)
            val b = c.ev.checks.find(_.name == "baseline")
            assert(b.exists(!_.passed), c.ev.checks)
            assert(b.exists(_.observed == "no successful run in the last 30 days"), b)
        }
    }

    // ------------------------------------------------------------------
    // Review fixes (self-healing-3): document taps, unfed scratch runs
    // ------------------------------------------------------------------

    test("document tap: the scratch lane is skipped with a verify step and the real run verifies (as verifyMode = real)") {
        scenario() { c =>
            c.tap = c.tap.copy(lastRunDataType = "document")
            IncidentRunner.runIncident(c.id)
            assert(c.scratchCalls.isEmpty, "no scratch rehearsal for a document tap: " + c.scratchCalls)
            assert(c.summaries.exists(_.contains("scratch lane not available for document taps")), c.summaries)
            assert(c.tools.filter(Mutations) == List("update_tap", "run_tap"), c.tools)
            assert(c.incident.state == Incident.Resolved, c.incident.outcome + " / " + c.summaries)
        }
    }

    test("a scratch run that fed nothing is profiled from the script's own record count and columns") {
        val t = TapConfig(name = "prices", description = "d", targetPipeline = null, version = 3)
        val r = ai.datris.util.TapScriptResult(null, 500, null, columns = java.util.Arrays.asList("id", "price"))
        val p = IncidentRunner.unfedProfile(t, r, Some("abc"), 12L)
        assert(p.recordCount == 500L)
        assert(p.columns.map(_.name) == List("id", "price"))
        assert(p.columns.forall(_.inferredType == "null"))
        assert(p.source == "scratch" && p.scriptSha.contains("abc") && p.tapVersion.contains(3))
        // Judged against a 500-record baseline it is not a record-delta failure.
        val ev = IncidentEvidenceBuilder.withCandidate(
            evidenceWith(Some(runProfile(
                "run_base",
                "lineage-only",
                500L,
                List(ColumnStats("id", "null", 0.0, 0L, None, None, None), ColumnStats("price", "null", 0.0, 0L, None, None, None))
            ))),
            p,
            Thresholds.Default
        )
        assert(ev.verdict == Thresholds.Pass, ev.checks)
    }

    // ==================================================================
    // Story: Schema-drift signal, credential and platform classifications,
    // wider diagnosis tools (plans/stories/self-healing-4-signals.md),
    // Acceptance bullet 3.
    //
    // Contract pinned here:
    // {{{
    // RecoverySettings(allowSchemaDriftActions: Boolean = false)
    // /** Pure; the credential regex from the story, over the trigger error and the latest tap run log. */
    // object Classifier { def credential(error: String, logs: String): Boolean }   // ai.datris.incident
    // object IncidentEvidenceBuilder { def withSecret(e: IncidentEvidence, secretName: String): IncidentEvidence }
    // }}}
    // - Doctor-first: before diagnoser.diagnose the runner calls
    //   executor.call("run_doctor", {"mode":"quick","ai":false}, id); a check
    //   with status "error" whose id does not start with "tap." short-circuits
    //   to Proposal("platform", <remediation lines>, needsHuman = true, Nil)
    //   without the model. A doctor answer without an error check (or "{}")
    //   changes nothing.
    // - Credential: the stored classification is "credential", the stored
    //   proposal has needsHuman = true and no actions, the incident parks in
    //   awaiting_approval, evidence.blastRadius.secretName = tap.secretName
    //   (read via tapLookup). The tap run-log read must never throw without
    //   Mongo (the spec has none).
    // - schema_drift is triaged (resolved, outcome "triaged: ...") unless
    //   allowSchemaDriftActions && the effective recovery mode is autopilot
    //   (with settingsOverride set, its `mode` is the effective mode).
    // ==================================================================

    private def transientRunProposal: IncidentRunner.Proposal = {
        val run = new JsonObject()
        run.addProperty("name", "prices")
        IncidentRunner.Proposal("transient", "looks like a blip", needsHuman = false, List(ProposedAction("run_tap", run, "retry")), None)
    }

    private val DriftTrigger =
        """{"added":["amount"],"removed":["price"],"previousColumns":["id","price","region"],"currentColumns":["id","amount","region"],"runTime":"2026-10-16 10:00:00"}"""

    private def storedProposal(c: Ctx): JsonObject = c.incident.proposal.getOrElse(fail("no stored proposal: " + c.summaries))

    test("schema_drift with allowSchemaDriftActions=false: one diagnosis, resolved as triaged, nothing executed") {
        scenario(kind = Incident.KindSchemaDrift, trigger = DriftTrigger, settings = RecoverySettings(mode = RecoverySettings.Autopilot)) { c =>
            c.tap = c.tap.copy(lastRunStatus = "success")
            IncidentRunner.runIncident(c.id)
            val i = c.incident
            assert(c.diagnoseCalls == 1, "exactly one diagnosis")
            assert(i.state == Incident.Resolved, i.outcome + " / " + c.summaries)
            assert(i.outcome.exists(_.startsWith("triaged")), i.outcome)
            assert(c.tools.filterNot(_ == "run_doctor").isEmpty, "the executor ran nothing but the read-only doctor: " + c.tools)
            assert(c.scratchCalls.isEmpty, c.scratchCalls)
            assert(i.actionsTaken == 0)
            assert(c.tap.version == 3, "no tap version change")
            assert(c.reverts.isEmpty)
        }
    }

    test("schema_drift with allowSchemaDriftActions=true but mode propose is still triaged") {
        scenario(
            kind = Incident.KindSchemaDrift,
            trigger = DriftTrigger,
            settings = RecoverySettings(mode = RecoverySettings.Propose, allowSchemaDriftActions = true)
        ) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.incident.state == Incident.Resolved, c.incident.outcome + " / " + c.summaries)
            assert(c.incident.outcome.exists(_.startsWith("triaged")), c.incident.outcome)
            assert(c.tools.filter(Mutations).isEmpty, c.tools)
        }
    }

    test("schema_drift with allowSchemaDriftActions=true on autopilot follows the executable path") {
        scenario(
            kind = Incident.KindSchemaDrift,
            trigger = DriftTrigger,
            settings = RecoverySettings(mode = RecoverySettings.Autopilot, allowSchemaDriftActions = true)
        ) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.tools.filter(Mutations) == List("update_tap", "run_tap"), c.tools)
            assert(c.scratchCalls.toList == List(("prices", Some(CandidateScript))), c.scratchCalls)
            val i = c.incident
            assert(i.state == Incident.Resolved, i.outcome + " / " + c.summaries)
            assert(i.outcome.contains("recovered and verified"), i.outcome)
        }
    }

    test("a trigger error with HTTP 401 forces credential: needsHuman, no actions, secret named — even when the model proposed run_tap") {
        scenario(trigger = """{"error":"HTTP 401 Unauthorized from https://api.example.com/prices"}""", proposal = transientRunProposal) { c =>
            c.tap = c.tap.copy(secretName = "prices-api")
            IncidentRunner.runIncident(c.id)
            val i = c.incident
            assert(i.classification.contains("credential"), i.classification + " / " + c.summaries)
            val p = storedProposal(c)
            assert(p.get("classification").getAsString == "credential", p)
            assert(p.get("needsHuman").getAsBoolean, p)
            assert(p.getAsJsonArray("actions").size() == 0, p)
            assert(i.state == Incident.AwaitingApproval, i.outcome + " / " + c.summaries)
            assert(i.awaitingApprovalIds.isEmpty)
            assert(c.tools.filter(Mutations).isEmpty, "the proposed run_tap never ran: " + c.tools)
            assert(c.scratchCalls.isEmpty)
            assert(c.ev.blastRadius.secretName == Some("prices-api"), c.ev.blastRadius)
        }
    }

    test("a non-credential failure is not reclassified (control)") {
        scenario(trigger = """{"error":"HTTP 429 Too Many Requests"}""", proposal = transientRunProposal) { c =>
            c.tap = c.tap.copy(secretName = "prices-api")
            IncidentRunner.runIncident(c.id)
            assert(c.incident.classification.contains("transient"), c.incident.classification)
            assert(c.tools.filter(Mutations) == List("run_tap"), c.tools)
        }
    }

    test("Classifier.credential matches auth failures in the error or the logs, and nothing else") {
        assert(Classifier.credential("HTTP 401 Unauthorized", null))
        assert(Classifier.credential("403 Forbidden", null))
        assert(Classifier.credential("request failed: unauthorized", null))
        assert(Classifier.credential(null, "Traceback ...\noauth error: invalid_grant\n"))
        assert(Classifier.credential("the access token has expired", null))
        assert(Classifier.credential("invalid token", null))
        assert(Classifier.credential("Credentials are rejected by the server", null))
        assert(!Classifier.credential("HTTP 429 Too Many Requests", "retrying later"))
        assert(!Classifier.credential("KeyError: account_id", "fetched 4012 rows"))
        assert(!Classifier.credential(null, null))
        assert(!Classifier.credential("", ""))
    }

    test("withSecret sets blastRadius.secretName and keeps the rest of the evidence") {
        val e = evidenceWith(Some(baseline))
        val w = IncidentEvidenceBuilder.withSecret(e, "prices-api")
        assert(w.blastRadius.secretName == Some("prices-api"))
        assert(w.baseline == e.baseline)
        assert(w.verdict == e.verdict)
        assert(IncidentEvidence.fromJson(w.toJson).blastRadius.secretName == Some("prices-api"))
    }

    private val DoctorPlatformError =
        """{"doctorVersion":1,"mode":"quick","checks":[
          |{"id":"vault.token_ttl","status":"ok","severity":"info","detail":"ok","remediation":""},
          |{"id":"disk.usage","status":"error","severity":"critical","detail":"disk 98% full","remediation":"Free disk space on the Docker host."}
          |]}""".stripMargin

    test("doctor-first: an error check short-circuits to platform with aiCalls == 0 and the diagnoser never called") {
        scenario(doctorResponse = DoctorPlatformError, proposal = transientRunProposal) { c =>
            IncidentRunner.runIncident(c.id)
            val i = c.incident
            assert(c.diagnoseCalls == 0, "the model was never asked")
            assert(i.aiCalls == 0, i.aiCalls)
            assert(i.classification.contains("platform"), i.classification + " / " + c.summaries)
            val p = storedProposal(c)
            assert(p.get("needsHuman").getAsBoolean, p)
            assert(p.getAsJsonArray("actions").size() == 0, p)
            assert(p.get("summary").getAsString.contains("Free disk space on the Docker host."), p)
            assert(i.state == Incident.AwaitingApproval, i.outcome + " / " + c.summaries)
            assert(c.tools.filter(Mutations).isEmpty, c.tools)
            val doctor = c.calls.find(_.tool == "run_doctor").getOrElse(fail("run_doctor was called: " + c.tools))
            assert(doctor.args.get("mode").getAsString == "quick", doctor.args)
            assert(doctor.args.has("ai") && !doctor.args.get("ai").getAsBoolean, doctor.args)
        }
    }

    test("doctor-first: a tap-specific error check or a warning does not short-circuit") {
        val tapSpecific =
            """{"checks":[{"id":"tap.secret_scope","status":"error","severity":"high","detail":"x","remediation":"Re-tag the tap secret."}]}"""
        scenario(doctorResponse = tapSpecific, proposal = transientRunProposal) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.diagnoseCalls == 1)
            assert(!c.incident.classification.contains("platform"), c.incident.classification)
        }
        val warnOnly = """{"checks":[{"id":"disk.usage","status":"warn","severity":"medium","detail":"80%","remediation":"Watch disk."}]}"""
        scenario(doctorResponse = warnOnly, proposal = transientRunProposal) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.diagnoseCalls == 1)
            assert(c.incident.classification.contains("transient"), c.incident.classification)
        }
    }

    // Review follow-up: only run-breaking doctor errors short-circuit; a bare
    // 401/403 counts only next to HTTP / status / code / response.

    private def doctorWith(id: String, detail: String, remediation: String): String =
        s"""{"checks":[{"id":"$id","status":"error","severity":"high","detail":"$detail","remediation":"$remediation"}]}"""

    test("doctor-first: an unrelated doctor error does not short-circuit; the diagnoser runs and sees it as a doctor note") {
        scenario(doctorResponse = doctorWith("vault.token_ttl", "expires in 3 days", "Renew the Vault token."), proposal = transientRunProposal) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.diagnoseCalls == 1)
            assert(c.incident.classification.contains("transient"), c.incident.classification)
            assert(c.summaries.exists(s => s.startsWith("doctor: vault.token_ttl") && s.contains("Renew the Vault token.")), c.summaries)
            assert(c.tools.filter(Mutations) == List("run_tap"), c.tools)
        }
    }

    test("doctor-first: run-breaking checks short-circuit (staging.area, vault.ai_slots on ai-primary) but not vault.ai_slots on another slot") {
        for (
            (id, detail) <- Seq(
                "staging.area" -> "staging area /tmp/datris is not writable",
                "vault.ai_slots" -> "Vault secret `oss/ai-primary` (ai-primary) missing"
            )
        ) scenario(doctorResponse = doctorWith(id, detail, "Fix it."), proposal = transientRunProposal) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.diagnoseCalls == 0, id)
            assert(c.incident.classification.contains("platform"), id + ": " + c.incident.classification)
        }
        scenario(doctorResponse = doctorWith("vault.ai_slots", "Vault secret `oss/codegen` (codegen) missing", "Fix it."), proposal = transientRunProposal) {
            c =>
                IncidentRunner.runIncident(c.id)
                assert(c.diagnoseCalls == 1)
                assert(!c.incident.classification.contains("platform"), c.incident.classification)
        }
        assert(Classifier.RunBreakingChecks.contains("disk.usage"))
        assert(!Classifier.RunBreakingChecks.contains("vault.token_ttl"))
        assert(!Classifier.RunBreakingChecks.contains("staging.orphans"))
        assert(!Classifier.RunBreakingChecks.contains("jdbc.mssql_driver"))
    }

    test("Classifier.credential: a bare number in a run log is not a credential failure; HTTP/status forms are") {
        assert(!Classifier.credential(null, "page 401 of 900"))
        assert(!Classifier.credential(null, "batch 403 written"))
        assert(!Classifier.credential("KeyError: account_id", "page 401 of 900\nbatch 403"))
        assert(Classifier.credential("status code: 401", null))
        assert(Classifier.credential(null, "HTTP/1.1 403"))
        assert(Classifier.credential("response status_code=401", null))
        assert(Classifier.credential("401 Client Error: Unauthorized for url", null))
        // Bare-status phrasings (curl, generic HTTP clients).
        assert(Classifier.credential("curl: (22) The requested URL returned error: 401", null))
        assert(Classifier.credential(null, "request failed with 403"))
        assert(Classifier.credential("upstream returned 401", null))
        assert(Classifier.credential(null, "error: 403"))
    }

    // ==================================================================
    // Story: Pipeline incidents: regenerate stored AI scripts, verify when the
    // input is recoverable (plans/stories/self-healing-5-pipeline-incidents.md),
    // Acceptance bullets 1 and 2.
    //
    // Contract pinned here (names the story leaves open are chosen here —
    // keep them or update this spec):
    // {{{
    // // ai.datris.incident
    // trait PipelineInputSource { def lastGoodInput(incident: Incident): Option[PipelineInputSource.Input] }
    // object PipelineInputSource extends PipelineInputSource {   // production: LineageService.recentRuns
    //   sealed trait Input
    //   case class TapFed(tap: String) extends Input
    //   case class Snapshot(ref: String) extends Input             // reserved for deterministic-replay.md
    // }
    // case class ScriptVersionChange(kind: String, before: Int, after: Int)
    // IncidentEvidence(..., scriptVersions: List[ScriptVersionChange] = Nil)
    //   // toJson: "scriptVersions":[{"kind":..,"before":..,"after":..}], absent when empty
    // Incident(..., revertToScriptVersions: Map[String, Int] = Map.empty)
    //   // toPublicJson: "revertToScriptVersions":{"transformation":3}; fromDocument: absent -> Map.empty
    // object IncidentRunner {
    //   @volatile var inputSource: PipelineInputSource = PipelineInputSource
    //   /** (pipeline, kind) => latest script version; production CodeGenScriptVersionIO.latestVersion. */
    //   @volatile private[incident] var scriptLatestVersion: (String, String) => Int
    //   /** (pipeline, kind, toVersion, actor) => restore; production reads the pipeline config
    //     * and calls PipelineScripts.restoreVersion(config, kind, toVersion, actor). */
    //   @volatile private[incident] var restoreScript: (String, String, Int, String) => Either[String, Unit]
    //   object RealExecutor { private[incident] def restFor(tool: String, args: JsonObject): Option[(String, String, Option[String])] }
    // }
    // }}}
    // - filterActions on a pipeline incident: only regenerate_pipeline_scripts,
    //   args.name == resourceName, args.kind in PipelineScripts.Kinds. Tap
    //   incidents never keep it.
    // - Before the first action: revertToScriptVersions = kinds in the proposal
    //   -> scriptLatestVersion(name, kind).
    // - After regenerate succeeds: evidence.scriptVersions gets
    //   (kind, before = revertToScriptVersions(kind), after = scriptLatestVersion).
    // - verify for pipelines: inputSource TapFed(t) -> scratch(tapLookup(t), None)
    //   with story 3's verdict rules, then run_tap {name: t}, rollup, resolved;
    //   scratch fail -> restoreScript(name, kind, max(v, 1), "recovery-agent"),
    //   step "restored <kind> script to version <v>", scriptVersions.after = v,
    //   failed. A Left from restoreScript is an execute step "revert failed".
    //   inputSource None -> resolved, outcome containing "unverified", no run_tap.
    // ==================================================================

    private def pipelineIncident(kind: String = Incident.KindPipelineFailure) =
        incident(kind).copy(resourceType = "pipeline", resourceName = "orders")

    private def regen(name: String = "orders", kind: String = "transformation"): ProposedAction = {
        val args = new JsonObject()
        if (name != null) args.addProperty("name", name)
        if (kind != null) args.addProperty("kind", kind)
        ProposedAction("regenerate_pipeline_scripts", args, "regenerate the transformation script")
    }

    private def proposalOf(actions: ProposedAction*): IncidentRunner.Proposal =
        IncidentRunner.Proposal("structural-script", "the transformation script fails on the new input", needsHuman = false, actions.toList, None)

    test("pipeline incident: regenerate_pipeline_scripts for its own pipeline and a known kind survives filterActions") {
        val filtered = IncidentRunner.filterActions(pipelineIncident(), proposalOf(regen()))
        assert(filtered.map(_.tool) == List("regenerate_pipeline_scripts"), filtered)
        assert(filtered.head.args.get("name").getAsString == "orders")
        assert(filtered.head.args.get("kind").getAsString == "transformation")
        val dq = IncidentRunner.filterActions(pipelineIncident(), proposalOf(regen(kind = "dataQuality")))
        assert(dq.map(_.tool) == List("regenerate_pipeline_scripts"), dq)
    }

    test("pipeline incident: another pipeline's name, an unknown or missing kind, and run_tap are dropped") {
        val runOrders = action("run_tap", "orders")
        val filtered = IncidentRunner.filterActions(
            pipelineIncident(),
            proposalOf(regen(name = "other"), regen(kind = "foo"), regen(kind = null), regen(name = null), runOrders, action("update_tap", "orders"))
        )
        assert(filtered.isEmpty, filtered.map(a => a.tool + " " + a.args))
    }

    test("tap incident: regenerate_pipeline_scripts is dropped even when it names the incident's resource") {
        val filtered = IncidentRunner.filterActions(incident(Incident.KindTapFailure), proposalOf(regen(name = "prices"), action("run_tap")))
        assert(filtered.map(_.tool) == List("run_tap"), filtered.map(_.tool))
    }

    test("RealExecutor maps regenerate_pipeline_scripts to POST .../codegen-scripts/{kind}/regenerate with body {} and no query string") {
        val r = IncidentRunner.RealExecutor.restFor("regenerate_pipeline_scripts", regen().args)
        assert(r == Some(("POST", "/api/v1/pipelines/orders/codegen-scripts/transformation/regenerate", Some("{}"))), r)
        assert(!r.get._2.contains("?"), "storage / overwrite are never passed")
    }

    // ------------------------------------------------------------------
    // Pipeline incident state machine
    // ------------------------------------------------------------------

    /** Everything one pipeline scenario observed. */
    private class PCtx(val id: String, val store: FakeStore) {
        val calls = mutable.ArrayBuffer[Call]()
        val scratchCalls = mutable.ArrayBuffer[(String, Option[String])]()
        val restores = mutable.ArrayBuffer[(String, String, Int, String)]()
        val latest = mutable.Map[String, Int]("transformation" -> 3)
        @volatile var revertAtFirstAction: Option[Map[String, Int]] = None
        @volatile var tap: TapConfig = TapConfig(name = "prices", description = "d", targetPipeline = "orders", version = 3, lastRunStatus = "success")
        def incident: Incident = store.get(id).getOrElse(fail("incident " + id + " vanished"))
        def tools: List[String] = calls.map(_.tool).toList
        def summaries: List[String] = incident.steps.map(_.summary)
        def ev: IncidentEvidence = IncidentEvidenceBuilder.of(incident).getOrElse(fail("no evidence on " + incident))
    }

    private val PipelineMutations = Set("regenerate_pipeline_scripts", "run_tap", "update_tap", "create_tap", "test_tap")

    private def pipelineScenario[T](
        input: Option[PipelineInputSource.Input],
        candidate: Option[RunProfile] = Some(healthyCandidate),
        real: Option[RunProfile] = Some(healthyReal),
        restoreResult: Either[String, Unit] = Right(()),
        proposal: IncidentRunner.Proposal = proposalOf(regen())
    )(body: PCtx => T): T = {
        val store = new FakeStore
        val ctx = new PCtx(Incident.newId(), store)
        store.insert(Incident(
            id = ctx.id,
            kind = Incident.KindPipelineFailure,
            resourceType = "pipeline",
            resourceName = "orders",
            openedAt = Instant.now(),
            state = Incident.Open,
            trigger = JsonParser.parseString(
                """{"pipelineToken":"tok_1","error":"AI transformation script failed: KeyError 'amount'","failingStage":"transformation-script"}"""
            ).getAsJsonObject,
            steps = List(IncidentStep(Instant.now(), "open", "pipeline_failure signal on pipeline orders")),
            evidence = Some(evidenceWith(Some(baseline)).toJson)
        ))

        val saved = (
            IncidentRunner.diagnoser,
            IncidentRunner.executor,
            IncidentRunner.runAsync,
            IncidentRunner.scratch,
            IncidentRunner.settingsOverride,
            IncidentRunner.tapLookup,
            IncidentRunner.revertTap,
            IncidentIO.store,
            IncidentEvidenceBuilder.realProfileOverride,
            IncidentRunner.inputSource,
            IncidentRunner.scriptLatestVersion,
            IncidentRunner.restoreScript
        )
        IncidentIO.store = store
        IncidentRunner.runAsync = false
        IncidentRunner.settingsOverride = RecoverySettings(mode = RecoverySettings.Propose)
        IncidentRunner.diagnoser = new IncidentRunner.Diagnoser {
            override def diagnose(incident: Incident, onAiCall: () => Unit): Either[String, IncidentRunner.Proposal] = {
                onAiCall()
                Right(proposal)
            }
        }
        IncidentRunner.executor = new IncidentRunner.ToolExecutor {
            override def call(tool: String, args: JsonObject, incidentId: String): ToolOutcome = {
                ctx.calls.synchronized { ctx.calls += Call(tool, args, store.get(incidentId).map(_.state).orNull) }
                tool match {
                    case "regenerate_pipeline_scripts" =>
                        if (ctx.revertAtFirstAction.isEmpty) ctx.revertAtFirstAction = store.get(incidentId).map(_.revertToScriptVersions)
                        val k = args.get("kind").getAsString
                        ctx.latest(k) = ctx.latest.getOrElse(k, 0) + 1
                        ToolOutcome.Ok("""{"pipeline":"orders","kind":"""" + k + """","status":"ready"}""")
                    case "run_tap" =>
                        ctx.tap = ctx.tap.copy(lastRunStatus = "success", lastRunRecordCount = real.map(_.recordCount.toInt).getOrElse(0))
                        ToolOutcome.Ok("""{"mode":"run","recordCount":""" + real.map(_.recordCount).getOrElse(0L) + ""","publisherToken":"pt_1"}""")
                    case "get_pipeline_status" => ToolOutcome.Ok("""{"allDone":true,"status":"complete"}""")
                    case _ => ToolOutcome.Ok("{}")
                }
            }
        }
        IncidentRunner.scratch = (t: TapConfig, script: Option[String]) => {
            ctx.scratchCalls.synchronized { ctx.scratchCalls += ((t.name, script)) }
            ScratchOutcome(candidate, if (candidate.isEmpty) Some("scratch run failed") else None)
        }
        IncidentRunner.tapLookup = (name: String) => if (name == "prices") Some(ctx.tap) else None
        // A pipeline incident never reverts a tap: recorded so the restore assertions would catch it.
        IncidentRunner.revertTap = (_: String, tapName: String, v: Int) => ctx.restores.synchronized { ctx.restores += ((tapName, "tap-revert", v, "")); () }
        IncidentEvidenceBuilder.realProfileOverride = (_: Incident, _: Option[RunProfile]) => real
        IncidentRunner.inputSource = new PipelineInputSource {
            override def lastGoodInput(incident: Incident): Option[PipelineInputSource.Input] = input
        }
        IncidentRunner.scriptLatestVersion = (pipeline: String, kind: String) =>
            if (pipeline == "orders") ctx.latest.getOrElse(kind, 0) else 0
        IncidentRunner.restoreScript = (pipeline: String, kind: String, toVersion: Int, actor: String) => {
            ctx.restores.synchronized { ctx.restores += ((pipeline, kind, toVersion, actor)) }
            // A restore appends a NEW latest version holding the old text.
            if (restoreResult.isRight) ctx.latest(kind) = ctx.latest.getOrElse(kind, 0) + 1
            restoreResult
        }

        try body(ctx)
        finally {
            IncidentRunner.diagnoser = saved._1
            IncidentRunner.executor = saved._2
            IncidentRunner.runAsync = saved._3
            IncidentRunner.scratch = saved._4
            IncidentRunner.settingsOverride = saved._5
            IncidentRunner.tapLookup = saved._6
            IncidentRunner.revertTap = saved._7
            IncidentIO.store = saved._8
            IncidentEvidenceBuilder.realProfileOverride = saved._9
            IncidentRunner.inputSource = saved._10
            IncidentRunner.scriptLatestVersion = saved._11
            IncidentRunner.restoreScript = saved._12
        }
    }

    test("pipeline incident: revertToScriptVersions is recorded before the first action from the versions seam") {
        pipelineScenario(input = Some(PipelineInputSource.TapFed("prices"))) { c =>
            IncidentRunner.runIncident(c.id)
            assert(c.tools.contains("regenerate_pipeline_scripts"), c.tools + " / " + c.summaries)
            assert(c.revertAtFirstAction == Some(Map("transformation" -> 3)), c.revertAtFirstAction)
            assert(c.incident.revertToScriptVersions == Map("transformation" -> 3))
            assert(c.incident.revertToVersion.isEmpty, "a pipeline incident records no tap version")
        }
    }

    test("pipeline incident fed by a tap: regenerate, scratch of the feeding tap's stored script, run_tap, resolved with scriptVersions 3 -> 4") {
        pipelineScenario(input = Some(PipelineInputSource.TapFed("prices"))) { c =>
            IncidentRunner.runIncident(c.id)
            val i = c.incident
            assert(i.state == Incident.Resolved, i.outcome + " / " + c.summaries)
            assert(c.scratchCalls.toList == List(("prices", None)), "the feeding tap ran in scratch with its own script: " + c.scratchCalls)
            val mutating = c.tools.filter(PipelineMutations)
            assert(mutating == List("regenerate_pipeline_scripts", "run_tap"), c.tools)
            val run = c.calls.find(_.tool == "run_tap").get
            assert(run.args.get("name").getAsString == "prices", run.args)
            assert(c.tools.contains("get_pipeline_status"), "the rollup of the real run is polled: " + c.tools)
            assert(c.ev.scriptVersions == List(ScriptVersionChange("transformation", 3, 4)), c.ev.scriptVersions)
            assert(c.ev.candidate.exists(_.runId == "scratch_1"), "the scratch run is evidence.candidate")
            assert(c.ev.real.isDefined, "the real run is profiled onto the evidence")
            assert(c.restores.isEmpty, c.restores)
            // The scratch stage comes after the regenerate (the new script must be live) and before the real run.
            val regenAt = i.steps.indexWhere(s => s.phase == "execute" && s.summary.startsWith("regenerate_pipeline_scripts"))
            val scratchAt = i.steps.indexWhere(_.summary.contains("scratch verification"))
            assert(regenAt >= 0 && scratchAt > regenAt, c.summaries)
            val runAt = c.tools.indexOf("run_tap")
            val regenCallAt = c.tools.indexOf("regenerate_pipeline_scripts")
            assert(regenCallAt < runAt, c.tools)
        }
    }

    test("pipeline incident: scratch fail restores the script once, says so, scriptVersions.after == 3, failed, no real run") {
        // 60 % of the rows dropped: maxRecordDeltaPct (25) breached.
        pipelineScenario(input = Some(PipelineInputSource.TapFed("prices")), candidate = Some(runProfile("scratch_1", "scratch", 40L))) { c =>
            IncidentRunner.runIncident(c.id)
            val i = c.incident
            assert(i.state == Incident.Failed, i.outcome + " / " + c.summaries)
            assert(c.restores.toList == List(("orders", "transformation", 3, "recovery-agent")), c.restores)
            assert(c.summaries.contains("restored transformation script to version 3"), c.summaries)
            assert(c.ev.scriptVersions.map(_.after) == List(3), c.ev.scriptVersions)
            assert(c.ev.scriptVersions.map(_.before) == List(3), c.ev.scriptVersions)
            assert(!c.tools.contains("run_tap"), "no real run after a scratch fail: " + c.tools)
        }
    }

    test("pipeline incident: a Left from the restore is a 'revert failed' step, never an exception") {
        pipelineScenario(
            input = Some(PipelineInputSource.TapFed("prices")),
            candidate = Some(runProfile("scratch_1", "scratch", 40L)),
            restoreResult = Left("the repository file was edited since the recorded commit")
        ) { c =>
            IncidentRunner.runIncident(c.id)
            val i = c.incident
            assert(i.state == Incident.Failed, i.outcome + " / " + c.summaries)
            assert(c.restores.size == 1, c.restores)
            val st = i.steps.find(s => s.phase == "execute" && s.summary == "revert failed")
            assert(st.isDefined, c.summaries)
            assert(st.get.detail.exists(_.contains("the repository file was edited")), st)
            assert(!i.outcome.exists(_.startsWith("runner error")), "the Left did not escape as an exception: " + i.outcome)
            assert(!c.summaries.exists(_.startsWith("restored transformation script")), c.summaries)
        }
    }

    test("pipeline incident with no replayable input: regenerated, resolved 'unverified', no scratch and no run_tap") {
        pipelineScenario(input = None) { c =>
            IncidentRunner.runIncident(c.id)
            val i = c.incident
            assert(i.state == Incident.Resolved, i.outcome + " / " + c.summaries)
            assert(i.outcome.exists(_.contains("unverified")), i.outcome)
            assert(c.tools.contains("regenerate_pipeline_scripts"), c.tools)
            assert(!c.tools.contains("run_tap"), c.tools)
            assert(c.scratchCalls.isEmpty, c.scratchCalls)
            assert(c.summaries.exists(s => s.contains("no replayable input") && s.contains("not verified")), c.summaries)
            assert(!c.summaries.exists(_.contains("no automatic verification for pipeline incidents")), "the v1 diagnosis-only text is gone")
            assert(c.ev.scriptVersions == List(ScriptVersionChange("transformation", 3, 4)), c.ev.scriptVersions)
            assert(c.restores.isEmpty)
        }
    }

    // ------------------------------------------------------------------
    // Acceptance bullet 2: revertToScriptVersions round-trip
    // ------------------------------------------------------------------

    test("revertToScriptVersions survives toDocument / fromDocument and is an object in toPublicJson") {
        val i = pipelineIncident().copy(
            openedAt = Instant.parse("2026-10-16T10:00:00Z"),
            revertToScriptVersions = Map("transformation" -> 3, "dataQuality" -> 0)
        )
        val back = Incident.fromDocument(i.toDocument)
        assert(back.revertToScriptVersions == Map("transformation" -> 3, "dataQuality" -> 0), back.revertToScriptVersions)
        assert(back == i)
        val pub = i.toPublicJson
        assert(pub.has("revertToScriptVersions") && pub.get("revertToScriptVersions").isJsonObject, pub)
        val o = pub.getAsJsonObject("revertToScriptVersions")
        assert(o.get("transformation").getAsInt == 3 && o.get("dataQuality").getAsInt == 0, o)
    }

    test("a document without revertToScriptVersions (pre-upgrade) reads as empty") {
        val i = pipelineIncident().copy(openedAt = Instant.parse("2026-10-16T10:00:00Z"))
        val d = i.toDocument
        d.remove("revertToScriptVersions")
        val back = Incident.fromDocument(d)
        assert(back.revertToScriptVersions == Map.empty[String, Int])
        assert(back == i)
    }

    test("evidence.scriptVersions serialises as [{kind, before, after}], reads back, and is absent when empty") {
        val e = evidenceWith(Some(baseline)).copy(scriptVersions = List(ScriptVersionChange("transformation", 3, 4)))
        val j = e.toJson
        assert(j.has("scriptVersions") && j.get("scriptVersions").isJsonArray, j)
        val first = j.getAsJsonArray("scriptVersions").get(0).getAsJsonObject
        assert(first.get("kind").getAsString == "transformation" && first.get("before").getAsInt == 3 && first.get("after").getAsInt == 4, first)
        assert(IncidentEvidence.fromJson(j).scriptVersions == List(ScriptVersionChange("transformation", 3, 4)))
        val none = evidenceWith(Some(baseline)).toJson
        assert(!none.has("scriptVersions"), "absent for tap incidents: " + none)
        assert(IncidentEvidence.fromJson(none).scriptVersions.isEmpty)
    }
}
