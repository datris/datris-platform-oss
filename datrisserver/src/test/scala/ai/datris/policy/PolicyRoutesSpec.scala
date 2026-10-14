package ai.datris.policy

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.{CapabilityRoutes, RouteCheck}
import org.scalatest.funsuite.AnyFunSuite

class PolicyRoutesSpec extends AnyFunSuite {

    test("action key is the capability resource:action for a mapped route") {
        assert(PolicyRoutes.actionKey("DELETE", "/api/v1/tap").contains("tap:delete"))
        assert(PolicyRoutes.actionKey("POST", "/api/v1/tap/run").contains("tap:run"))
        assert(PolicyRoutes.actionKey("POST", "/api/v1/pipeline").contains("pipeline:create"))
    }

    test("dest-types apply is refined to a sub-action whose parent matches the capability route") {
        assert(PolicyRoutes.actionKey("POST", "/api/v1/pipeline/dest-types").contains("pipeline:update:dest-types"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/pipeline/dest-types") == RouteCheck.Require("pipeline", "update"))
        assert(PolicyRoutes.actionKey("GET", "/api/v1/pipeline/dest-types").contains("pipeline:read"))
    }

    // Story: Enable a catalog's taps in dependency order
    // (plans/stories/tap-dependency-order-enable.md). The bulk enable is its
    // own sub-action under tap:run, so a policy can require approval for it
    // alone; GET (status) is a tap read and DELETE (cancel) plain tap:run.
    test("ordered catalog enable is the tap:run:enable-ordered sub-action; its parent matches the capability route") {
        assert(PolicyRoutes.actionKey("POST", "/api/v1/catalog/x/enable-ordered") == Some("tap:run:enable-ordered"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/catalog/x/enable-ordered") == RouteCheck.Require("tap", "run"))
        assert(CapabilityRoutes.lookup("GET", "/api/v1/catalog/x/enable-ordered") == RouteCheck.Require("tap", "read"))
        assert(CapabilityRoutes.lookup("DELETE", "/api/v1/catalog/x/enable-ordered") == RouteCheck.Require("tap", "run"))
        assert(PolicyRoutes.actionKey("GET", "/api/v1/catalog/x/enable-ordered").contains("tap:read"))
        assert(PolicyRoutes.actionKey("DELETE", "/api/v1/catalog/x/enable-ordered").contains("tap:run"))
        assert(PolicyRoutes.isKnownActionKey("tap:run:enable-ordered"))
        assert(PolicyRoutes.isGateable("tap:run:enable-ordered"))
        assert(PolicyRoutes.catalog.contains("tap:run:enable-ordered"))
        // The path name is a catalog: a queued approval records no tap version for it.
        assert(PolicyRoutes.targetsCatalog("tap:run:enable-ordered"))
        assert(!PolicyRoutes.targetsCatalog("tap:run"))
        // The catalog rename/delete rows next to it are untouched.
        assert(CapabilityRoutes.lookup("DELETE", "/api/v1/catalog/x") == RouteCheck.Require("pipeline", "delete"))
        assert(CapabilityRoutes.lookup("PUT", "/api/v1/catalog/x") == RouteCheck.Require("pipeline", "update"))
    }

    test("a policy can require approval for the ordered enable alone, and it inherits tap:run otherwise") {
        val only = AgentPolicy.fromJson("""{"actions":{"tap:run:enable-ordered":"approve"}}""")
        assert(only.isRight, only)
        assert(only.toOption.get.decide("tap:run:enable-ordered", Some("tap"), Some("sales")) == PolicyMode.Approve)
        assert(only.toOption.get.decide("tap:run", Some("tap"), Some("prices")) == PolicyMode.Auto)
        val parent = AgentPolicy.fromJson("""{"actions":{"tap:run":"approve"}}""").toOption.get
        assert(parent.decide("tap:run:enable-ordered", Some("tap"), Some("sales")) == PolicyMode.Approve)
    }

    test("skip-class and unmapped routes have no action key") {
        assert(PolicyRoutes.actionKey("POST", "/api/v1/auth/login").isEmpty)
        assert(PolicyRoutes.actionKey("GET", "/api/v1/version").isEmpty)
        assert(PolicyRoutes.actionKey("POST", "/api/v1/does-not-exist").isEmpty)
    }

    test("reads, queries, searches and metadata are never gateable") {
        assert(!PolicyRoutes.isGateable("tap:read"))
        assert(!PolicyRoutes.isGateable("query:postgres"))
        assert(!PolicyRoutes.isGateable("search:vector"))
        assert(!PolicyRoutes.isGateable("metadata:read"))
        assert(!PolicyRoutes.isGateable("policy:read"))
        assert(!PolicyRoutes.isGateable("approval:read"))
        assert(PolicyRoutes.isGateable("tap:delete"))
        assert(PolicyRoutes.isGateable("pipeline:update:dest-types"))
        assert(PolicyRoutes.isGateable("document:upload"))
    }

    test("policy management and approval decisions are governed by hard rules, not the policy") {
        assert(!PolicyRoutes.isGateable("policy:update"))
        assert(!PolicyRoutes.isGateable("approval:decide"))
    }

    test("every catalog entry is a known, gateable key") {
        assert(PolicyRoutes.catalog.nonEmpty)
        PolicyRoutes.catalog.foreach { k =>
            assert(PolicyRoutes.isKnownActionKey(k), k)
            assert(PolicyRoutes.isGateable(k), k)
        }
        assert(PolicyRoutes.catalog == PolicyRoutes.catalog.sorted)
    }

    test("new policy and approval routes are capability-mapped") {
        assert(CapabilityRoutes.lookup("GET", "/api/v1/policy") == RouteCheck.Require("policy", "read"))
        assert(CapabilityRoutes.lookup("PUT", "/api/v1/policy") == RouteCheck.Require("policy", "update"))
        assert(CapabilityRoutes.lookup("GET", "/api/v1/approvals") == RouteCheck.Require("approval", "read"))
        assert(CapabilityRoutes.lookup("GET", "/api/v1/approvals/pa_1") == RouteCheck.Require("approval", "read"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/approvals/pa_1/approve") == RouteCheck.Require("approval", "decide"))
        assert(CapabilityRoutes.lookup("POST", "/api/v1/approvals/pa_1/reject") == RouteCheck.Require("approval", "decide"))
    }

    test("the scratch result route is capability-mapped as a job read") {
        // plans/stories/scratch-result-endpoint-retention.md: unmapped routes deny
        // under CAPABILITY_ENFORCEMENT=enforce, so GET /pipeline/result must sit
        // beside /pipeline/status with the same job:read requirement.
        assert(CapabilityRoutes.lookup("GET", "/api/v1/pipeline/result") == RouteCheck.Require("job", "read"))
        assert(CapabilityRoutes.lookup("GET", "/api/v1/pipeline/status") == RouteCheck.Require("job", "read"))
    }
}
