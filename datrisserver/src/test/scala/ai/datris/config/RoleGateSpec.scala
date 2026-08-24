package ai.datris.config

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, ResolvedKey}
import org.scalatest.funsuite.AnyFunSuite

class RoleGateSpec extends AnyFunSuite {

    private def key(caps: String*): ResolvedKey =
        ResolvedKey(None, "test-key", Capability.parseList(caps), isLegacyFullAccess = false)

    private val full = ResolvedKey(None, "full", Seq(Capability.FullAccess), isLegacyFullAccess = true)

    private def gate(rk: ResolvedKey, method: String, path: String): Boolean =
        RoleEnforcementInterceptor.programmaticKeySatisfiesRoleGate(rk, method, path)

    test("full-access key satisfies every role gate, mapped or not") {
        assert(gate(full, "POST", "/api/v1/keys"))
        assert(gate(full, "GET", "/api/v1/secrets"))
        assert(gate(full, "POST", "/api/v1/users"))
    }

    test("scoped key satisfies a role gate on a capability-mapped route it holds") {
        val rag = key("secret:read:_type=tap", "secret:write:_type=tap")
        assert(gate(rag, "GET", "/api/v1/secrets"))
        assert(gate(rag, "GET", "/api/v1/secrets/some-tap-secret"))
        assert(gate(rag, "PUT", "/api/v1/secrets/some-tap-secret"))
    }

    test("scoped key never satisfies skip-listed admin surfaces (key minting, users)") {
        val rag = key("secret:read:_type=tap", "secret:write:_type=tap", "tap:create", "pipeline:create")
        assert(!gate(rag, "POST", "/api/v1/keys"))
        assert(!gate(rag, "GET", "/api/v1/keys"))
        assert(!gate(rag, "POST", "/api/v1/users"))
        assert(!gate(rag, "POST", "/api/v1/auth/anything"))
    }

    test("scoped key without the route's capability is refused") {
        val readOnly = key("secret:read")
        assert(gate(readOnly, "GET", "/api/v1/secrets"))
        assert(!gate(readOnly, "PUT", "/api/v1/secrets/x"))
        assert(!gate(readOnly, "DELETE", "/api/v1/secrets/x"))
    }

    test("unmapped routes are refused for scoped keys") {
        assert(!gate(key("tap:read"), "GET", "/api/v1/does-not-exist"))
    }
}
