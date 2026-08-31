package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

class AgentPolicySpec extends AnyFunSuite {

    private def policy(json: String): AgentPolicy = AgentPolicy.fromJson(json) match {
        case Right(p) => p
        case Left(err) => fail("expected a valid policy: " + err)
    }

    test("empty policy → everything auto") {
        val p = AgentPolicy.Empty
        assert(p.decide("tap:delete", Some("tap"), Some("x")) == PolicyMode.Auto)
        assert(p.decide("pipeline:update:dest-types", Some("pipeline"), Some("x")) == PolicyMode.Auto)
    }

    test("exact key, parent key and resource wildcard resolve most-specific first") {
        val p = policy("""{"actions":{"pipeline:*":"deny","pipeline:update":"approve","pipeline:update:dest-types":"auto"}}""")
        assert(p.decide("pipeline:update:dest-types", Some("pipeline"), Some("a")) == PolicyMode.Auto)
        assert(p.decide("pipeline:update", Some("pipeline"), Some("a")) == PolicyMode.Approve)
        assert(p.decide("pipeline:delete", Some("pipeline"), Some("a")) == PolicyMode.Deny)
        assert(p.decide("tap:delete", Some("tap"), Some("a")) == PolicyMode.Auto)
    }

    test("a sub-action inherits its parent's mode when not named") {
        val p = policy("""{"actions":{"pipeline:update":"approve"}}""")
        assert(p.decide("pipeline:update:dest-types", Some("pipeline"), Some("a")) == PolicyMode.Approve)
    }

    test("override may only tighten, never loosen") {
        val p = policy("""{"actions":{"tap:run":"approve","tap:delete":"deny"},"overrides":{"tap:prices":{"tap:run":"auto","tap:delete":"approve"}}}""")
        // tries to loosen approve → auto: ignored
        assert(p.decide("tap:run", Some("tap"), Some("prices")) == PolicyMode.Approve)
        // tries to loosen deny → approve: ignored
        assert(p.decide("tap:delete", Some("tap"), Some("prices")) == PolicyMode.Deny)
        val q = policy("""{"actions":{"tap:run":"auto"},"overrides":{"tap:prices":{"tap:run":"approve"}}}""")
        assert(q.decide("tap:run", Some("tap"), Some("prices")) == PolicyMode.Approve)
        assert(q.decide("tap:run", Some("tap"), Some("other")) == PolicyMode.Auto)
        assert(q.decide("tap:run", Some("tap"), None) == PolicyMode.Auto)
    }

    test("unknown action keys are rejected on parse, naming the key") {
        val r = AgentPolicy.fromJson("""{"actions":{"tap:delete":"approve","widget:frob":"deny"}}""")
        assert(r.isLeft)
        assert(r.left.get.contains("widget:frob"))
    }

    test("resource wildcard is accepted only for known resources") {
        assert(AgentPolicy.fromJson("""{"actions":{"tap:*":"approve"}}""").isRight)
        assert(AgentPolicy.fromJson("""{"actions":{"nothing:*":"approve"}}""").isLeft)
    }

    test("bad modes and bad override keys are rejected") {
        assert(AgentPolicy.fromJson("""{"actions":{"tap:delete":"maybe"}}""").left.get.contains("maybe"))
        assert(AgentPolicy.fromJson("""{"overrides":{"widget:x":{"tap:run":"approve"}}}""").left.get.contains("widget:x"))
        assert(AgentPolicy.fromJson("""{"overrides":{"tap:x":{"nope:run":"approve"}}}""").left.get.contains("nope:run"))
    }

    test("limits are bounded and default when absent") {
        assert(policy("""{}""").limits == PolicyLimits(24, 50))
        assert(policy("""{"limits":{"pendingTtlHours":48,"maxPendingPerActor":5}}""").limits == PolicyLimits(48, 5))
        assert(AgentPolicy.fromJson("""{"limits":{"pendingTtlHours":0}}""").isLeft)
        assert(AgentPolicy.fromJson("""{"limits":{"maxPendingPerActor":"lots"}}""").isLeft)
    }

    test("toJson round-trips through fromJson") {
        val p = policy(
            """{"version":3,"actions":{"tap:delete":"approve","secret:write":"deny"},"overrides":{"pipeline:orders":{"pipeline:update":"approve"}},"limits":{"pendingTtlHours":12,"maxPendingPerActor":7}}"""
        )
        val back = policy(p.toJson.toString)
        assert(back == p)
    }

    test("recommended template validates against the live route table") {
        assert(AgentPolicy.fromJson(AgentPolicy.Recommended.toJson.toString).isRight)
        assert(AgentPolicy.Recommended.decide("pipeline:update:dest-types", Some("pipeline"), Some("a")) == PolicyMode.Approve)
        assert(AgentPolicy.Recommended.decide("secret:write", Some("secret"), Some("a")) == PolicyMode.Deny)
        assert(AgentPolicy.Recommended.decide("tap:run", Some("tap"), Some("a")) == PolicyMode.Auto)
    }
}
