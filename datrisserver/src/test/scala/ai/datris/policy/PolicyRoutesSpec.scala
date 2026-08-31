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
}
