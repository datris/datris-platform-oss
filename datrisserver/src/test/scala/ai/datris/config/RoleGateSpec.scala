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

    // Owner decision on the api-key-inert-scopes review (finding 2): a scoped
    // wildcard key never counts as full access for admin checks, with or
    // without DATRIS_KEY_INERT_SCOPES=allow.

    private def inMode[A](value: Option[String])(body: => A): A = {
        val prop = ai.datris.auth.CapabilityScopes.InertScopesProperty
        val previous = sys.props.get(prop)
        value match {
            case Some(v) => sys.props(prop) = v
            case None => sys.props -= prop
        }
        try body
        finally previous match {
                case Some(v) => sys.props(prop) = v
                case None => sys.props -= prop
            }
    }

    /** A stored key as APIKeyValidator.resolveAgainst builds it. */
    private def stored(label: String, raw: String*): ResolvedKey = {
        val (kept, inert) = ai.datris.auth.CapabilityScopes.partition(Capability.parseList(raw))
        ResolvedKey(None, label, kept, isLegacyFullAccess = false, inertCapabilities = inert)
    }

    private val AdminRoutes = Seq(
        ("POST", "/api/v1/keys"),
        ("GET", "/api/v1/keys"),
        ("POST", "/api/v1/keys/x/rotate"),
        ("DELETE", "/api/v1/keys/x"),
        ("POST", "/api/v1/users"),
        ("POST", "/api/v1/auth/users"),
        ("DELETE", "/api/v1/auth/users/someone"),
        ("POST", "/api/v1/config-chat/chat"),
        ("GET", "/api/v1/audit-log"),
        ("PUT", "/api/v1/policy"),
        ("GET", "/api/v1/secrets"),
        ("PUT", "/api/v1/secrets/x")
    )

    private val scopedWildcards = Seq("*:*:catalog=Sales", "*:*:owner=self", "*:read:catalog=Sales", "*:*:catalog=*")

    test("a scoped wildcard key never satisfies an admin role gate, in refuse mode and under the opt-out") {
        for (mode <- Seq(None, Some("allow")); raw <- scopedWildcards) {
            inMode(mode) {
                val rk = stored("sales_all", raw)
                for ((m, p) <- AdminRoutes) assert(!gate(rk, m, p), s"$raw $m $p mode=$mode")
                assert(!rk.isFullAccess, s"$raw mode=$mode")
                assert(!ai.datris.auth.ChatCaller.isFullAccess(rk), s"$raw mode=$mode")
                val id = ai.datris.auth.ChatIdentity(None, Some(rk), ownKey = true)
                assert(!ai.datris.auth.ChatCaller.mayConfigure(id), s"$raw mode=$mode")
                assert(!ai.datris.auth.ChatCaller.mayReadIncidents(id), s"$raw mode=$mode")
                // the same capability held without partition (a v1.60.0-shaped key)
                val raw160 = key(raw)
                for ((m, p) <- AdminRoutes) assert(!gate(raw160, m, p), s"unpartitioned $raw $m $p mode=$mode")
            }
        }
    }

    test("plain *:*, a legacy key and the ui key still satisfy every admin role gate in both modes") {
        for (mode <- Seq(None, Some("allow"))) {
            inMode(mode) {
                val plain = stored("admin-key", "*:*")
                val uiLegacy = ResolvedKey(None, "ui", Seq(Capability.FullAccess), isLegacyFullAccess = true)
                val uiScoped = stored("ui", "*:*")
                for (rk <- Seq(plain, full, uiLegacy, uiScoped); (m, p) <- AdminRoutes) assert(gate(rk, m, p), s"${rk.label} $m $p mode=$mode")
                for (rk <- Seq(plain, full, uiLegacy, uiScoped)) {
                    assert(rk.isFullAccess && ai.datris.auth.ChatCaller.isFullAccess(rk), rk.label)
                    assert(ai.datris.auth.ChatCaller.mayConfigure(ai.datris.auth.ChatIdentity(None, Some(rk), ownKey = false)), rk.label)
                }
            }
        }
    }

    test("a narrowed pipeline or tap capability still satisfies a role gate only on its own mapped routes") {
        for (mode <- Seq(None, Some("allow"))) {
            inMode(mode) {
                val rk = stored("sales_all", "*:*:catalog=Sales", "secret:read:_type=tap")
                assert(gate(rk, "GET", "/api/v1/secrets"), "secret:read is an applied capability of the key")
                assert(!gate(rk, "PUT", "/api/v1/secrets/x"))
                assert(!gate(rk, "GET", "/api/v1/audit-log"))
            }
        }
    }

    // Story: In-platform chats act with the signed-in user's role, not the
    // platform key's (plans/stories/chat-session-role-authority.md), Steps 4.
    // `viewerMayPost(path)` takes the dispatcher's matched path
    // (RequestPaths.path); `unauthenticated(resolved, rejection)` is the
    // role gate's answer for a role-gated route reached with no session.

    test("a viewer may POST the four chat routes and nothing else") {
        Seq("/api/v1/assistant/chat", "/api/v1/ops-chat/chat", "/api/v1/catalog-chat/chat", "/api/v1/search-chat/chat").foreach { p =>
            assert(RoleEnforcementInterceptor.viewerMayPost(p), p)
        }
        Seq(
            "/api/v1/assistant/attachment",
            "/api/v1/pipeline",
            "/api/v1/config-chat/chat",
            "/api/v1/assistant/init",
            "/api/v1/tap/run",
            "/api/v1/query/postgres",
            // near misses: the matched path is compared exactly
            "/api/v1/assistant/chat/",
            "/api/v1/assistant/chatx",
            "/api/v1/assistant/chat/extra",
            "/api/v1/Assistant/chat",
            "/api/v2/assistant/chat",
            "/assistant/chat",
            "api/v1/assistant/chat",
            "",
            null
        ).foreach(p => assert(!RoleEnforcementInterceptor.viewerMayPost(p), String.valueOf(p)))
    }

    test("role-gated route, no session: no resolved key is 401, a rejected key is 401, a key-store outage is 503, a resolved key without the role stays 403") {
        assert(RoleEnforcementInterceptor.unauthenticated(None, None) == Some((401, ai.datris.api.ApiErrors.AuthenticationRequiredBody)))

        Seq(ai.datris.util.APIKeyValidator.InvalidKeyMessage, "API key 'old' is revoked", ai.datris.util.APIKeyValidator.MissingKeyMessage).foreach {
            reason =>
                val got = RoleEnforcementInterceptor.unauthenticated(None, Some(reason))
                assert(got == Some((401, CapabilityInterceptor.RejectedKeyBody)), reason)
                assert(got == Some(CapabilityInterceptor.rejectionResponse(reason)), reason)
        }

        Seq(ai.datris.util.APIKeyValidator.MetadataUnavailableMessage, ai.datris.util.APIKeyValidator.KeyStoreUnavailableMessage).foreach { reason =>
            val got = RoleEnforcementInterceptor.unauthenticated(None, Some(reason))
            assert(got.map(_._1) == Some(503), reason)
            assert(got == Some(CapabilityInterceptor.rejectionResponse(reason)), reason)
        }

        // A resolved key (any shape) is not unauthenticated: the gate keeps its 403.
        val anonymous = ResolvedKey(None, "anonymous", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        val tap = ResolvedKey(None, "tap:t1", ai.datris.auth.TapRunTokens.Capabilities, isLegacyFullAccess = false)
        Seq(key("pipeline:read"), key("job:read"), stored("sales_all", "*:*:catalog=Sales"), tap, anonymous, full).foreach { rk =>
            assert(RoleEnforcementInterceptor.unauthenticated(Some(rk), None).isEmpty, rk.label)
        }
    }
}
