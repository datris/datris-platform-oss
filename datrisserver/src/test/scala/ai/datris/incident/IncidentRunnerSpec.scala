package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.incident.IncidentRunner.{ProposedAction, RealDiagnoser}
import com.google.gson.{JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant

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
        assert(IncidentRunner.ExecutableTools == Set("run_tap", "test_tap", "update_tap", "create_tap"))
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

    test("public JSON exposes the narrative but keeps state machinery consistent") {
        val i = incident(Incident.KindVolume)
        val json = i.toPublicJson.toString
        assert(json.contains("\"kind\":\"volume\""))
        assert(json.contains("\"state\":\"open\""))
        assert(Incident.OpenStates.contains(i.state))
    }
}
