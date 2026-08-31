package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

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
}
