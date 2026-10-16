package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.incident.Thresholds
import org.scalatest.funsuite.AnyFunSuite

class RecoveryPolicySpec extends AnyFunSuite {

    private def policy(json: String): AgentPolicy = AgentPolicy.fromJson(json) match {
        case Right(p) => p
        case Left(err) => fail("expected a valid policy: " + err)
    }

    test("recovery defaults to off with the documented limits") {
        val p = policy("""{}""")
        assert(p.recovery == RecoverySettings())
        assert(p.recovery.mode == RecoverySettings.Off)
        assert(p.recovery.maxAiCallsPerIncident == 12)
        assert(p.recovery.maxActionsPerIncident == 3)
        assert(p.recovery.maxRuntimeMinutes == 15)
        assert(p.recovery.maxOpenIncidents == 10)
        assert(p.recovery.cooldownHours == 6)
    }

    test("recovery block parses and round-trips") {
        val p = policy(
            """{"recovery":{"mode":"autopilot","maxAiCallsPerIncident":5,"maxActionsPerIncident":2,"maxRuntimeMinutes":10,"maxOpenIncidents":4,"cooldownHours":12}}"""
        )
        assert(p.recovery.mode == RecoverySettings.Autopilot)
        assert(p.recovery.maxActionsPerIncident == 2)
        val back = policy(p.toJson.toString)
        assert(back.recovery == p.recovery)
    }

    test("bad recovery modes and out-of-range limits are rejected") {
        assert(AgentPolicy.fromJson("""{"recovery":{"mode":"yolo"}}""").isLeft)
        assert(AgentPolicy.fromJson("""{"recovery":{"maxRuntimeMinutes":0}}""").isLeft)
        assert(AgentPolicy.fromJson("""{"recovery":{"maxAiCallsPerIncident":"many"}}""").isLeft)
    }

    test("per-resource recovery override moves in either direction") {
        val p = policy("""{"recovery":{"mode":"propose"},"overrides":{"tap:prices":{"recovery":"autopilot"},"tap:hr-data":{"recovery":"off"}}}""")
        assert(p.effectiveRecoveryMode("tap", "prices") == RecoverySettings.Autopilot)
        assert(p.effectiveRecoveryMode("tap", "hr-data") == RecoverySettings.Off)
        assert(p.effectiveRecoveryMode("tap", "other") == RecoverySettings.Propose)
        assert(p.effectiveRecoveryMode("pipeline", "prices") == RecoverySettings.Propose)
    }

    test("recovery override coexists with action overrides in the same object and round-trips") {
        val p = policy("""{"actions":{"tap:run":"auto"},"overrides":{"tap:prices":{"tap:run":"approve","recovery":"autopilot"}}}""")
        assert(p.decide("tap:run", Some("tap"), Some("prices")) == PolicyMode.Approve)
        assert(p.effectiveRecoveryMode("tap", "prices") == RecoverySettings.Autopilot)
        val back = policy(p.toJson.toString)
        assert(back == p)
    }

    test("a bad recovery override mode is rejected, naming the resource") {
        val r = AgentPolicy.fromJson("""{"overrides":{"tap:prices":{"recovery":"sometimes"}}}""")
        assert(r.isLeft && r.left.get.contains("tap:prices"))
    }

    // Story: Incident evidence model (plans/stories/self-healing-1-evidence-model.md),
    // Acceptance bullet 5 — five verification thresholds on RecoverySettings.

    test("verification thresholds default to 25 / false / false / None / 0.10") {
        val r = policy("""{}""").recovery
        assert(r.maxRecordDeltaPct == 25)
        assert(!r.allowColumnRemoval)
        assert(!r.allowTypeChange)
        assert(r.minDqPassRate.isEmpty)
        assert(r.maxNullRateIncrease == 0.10)
        assert(r.thresholds == Thresholds(25.0, allowColumnRemoval = false, allowTypeChange = false, minDqPassRate = None, maxNullRateIncrease = 0.10))
    }

    test("an older recovery block without thresholds parses with the threshold defaults") {
        val r = policy("""{"recovery":{"mode":"propose","maxAiCallsPerIncident":5}}""").recovery
        assert(r.mode == RecoverySettings.Propose)
        assert(r.maxRecordDeltaPct == 25 && !r.allowColumnRemoval && !r.allowTypeChange && r.minDqPassRate.isEmpty && r.maxNullRateIncrease == 0.10)
    }

    test("maxRecordDeltaPct = 101 is rejected naming the field") {
        val r = AgentPolicy.fromJson("""{"recovery":{"maxRecordDeltaPct":101}}""")
        assert(r.isLeft && r.left.get.contains("maxRecordDeltaPct"), r)
    }

    test("maxNullRateIncrease = 1.5 is rejected naming the field") {
        val r = AgentPolicy.fromJson("""{"recovery":{"maxNullRateIncrease":1.5}}""")
        assert(r.isLeft && r.left.get.contains("maxNullRateIncrease"), r)
    }

    test("verification thresholds round-trip through toJson") {
        val p = policy(
            """{"recovery":{"mode":"propose","maxRecordDeltaPct":40,"allowColumnRemoval":true,"allowTypeChange":true,"minDqPassRate":0.9,"maxNullRateIncrease":0.2}}"""
        )
        assert(p.recovery.maxRecordDeltaPct == 40)
        assert(p.recovery.allowColumnRemoval && p.recovery.allowTypeChange)
        assert(p.recovery.minDqPassRate == Some(0.9))
        assert(p.recovery.maxNullRateIncrease == 0.2)
        val back = policy(p.toJson.toString)
        assert(back.recovery == p.recovery)
        assert(back.recovery.thresholds == Thresholds(
            40.0,
            allowColumnRemoval = true,
            allowTypeChange = true,
            minDqPassRate = Some(0.9),
            maxNullRateIncrease = 0.2
        ))
    }

    // Story: Scratch-first verification (plans/stories/self-healing-3-scratch-first-verify.md),
    // Acceptance bullet 1 — `recovery.verifyMode`: scratch-first (default) | real.

    test("verifyMode defaults to scratch-first, including an older recovery block without it") {
        assert(RecoverySettings.ScratchFirst == "scratch-first")
        assert(RecoverySettings().verifyMode == RecoverySettings.ScratchFirst)
        assert(policy("""{}""").recovery.verifyMode == RecoverySettings.ScratchFirst)
        assert(policy("""{"recovery":{"mode":"propose","maxAiCallsPerIncident":5}}""").recovery.verifyMode == RecoverySettings.ScratchFirst)
        assert(RecoverySettings.VerifyModes == Set("scratch-first", "real"))
    }

    test("verifyMode accepts real") {
        val r = policy("""{"recovery":{"mode":"propose","verifyMode":"real"}}""").recovery
        assert(r.verifyMode == "real")
        assert(r.mode == RecoverySettings.Propose)
    }

    test("verifyMode = always is rejected naming the field") {
        val r = AgentPolicy.fromJson("""{"recovery":{"verifyMode":"always"}}""")
        assert(r.isLeft, r)
        assert(r.left.get.contains("recovery.verifyMode must be scratch-first or real"), r)
    }

    test("verifyMode round-trips through toJson") {
        for (vm <- Seq("real", "scratch-first")) {
            val p = policy("""{"recovery":{"mode":"autopilot","verifyMode":"""" + vm + """"}}""")
            assert(p.recovery.verifyMode == vm)
            val json = p.toJson
            assert(json.getAsJsonObject("recovery").get("verifyMode").getAsString == vm, json)
            val back = policy(json.toString)
            assert(back.recovery == p.recovery)
        }
    }
}
