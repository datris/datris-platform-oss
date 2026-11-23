package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

class TapRunTokensSpec extends AnyFunSuite {

    test("issued token resolves to a read-only tap:<name> identity") {
        val t = TapRunTokens.issue("crypto-prices", None, 60)
        assert(t.startsWith("trt_") && t.length > 40)
        val rk = TapRunTokens.resolve(t).get
        assert(rk.label == "tap:crypto-prices")
        assert(!rk.isLegacyFullAccess)
        assert(rk.keyId.isEmpty)
        assert(rk.matchesResourceAction("query", "postgres"))
        assert(rk.matchesResourceAction("query", "mongodb"))
        assert(rk.matchesResourceAction("metadata", "read"))
        assert(rk.matchesResourceAction("search", "vector"))
        assert(!rk.matchesResourceAction("tap", "create"))
        assert(!rk.matchesResourceAction("pipeline", "delete"))
        assert(!rk.matchesResourceAction("secret", "read"))
        assert(!rk.matchesResourceAction("*", "*"))
        TapRunTokens.revoke(t)
    }

    test("revoked token no longer resolves") {
        val t = TapRunTokens.issue("x", None, 60)
        assert(TapRunTokens.lookup(t).isDefined)
        TapRunTokens.revoke(t)
        assert(TapRunTokens.lookup(t).isEmpty)
        assert(TapRunTokens.resolve(t).isEmpty)
    }

    test("expired token no longer resolves") {
        val t = TapRunTokens.issue("x", None, 0)
        Thread.sleep(5)
        assert(TapRunTokens.lookup(t).isEmpty)
    }

    test("unknown, null and non-token strings never resolve") {
        assert(TapRunTokens.lookup(null).isEmpty)
        assert(TapRunTokens.lookup("").isEmpty)
        assert(TapRunTokens.lookup("default-ui-key").isEmpty)
        assert(TapRunTokens.lookup("trt_" + "0" * 64).isEmpty)
    }

    test("tenant environment travels with the token") {
        val t = TapRunTokens.issue("x", Some("tenant-a"), 60)
        assert(TapRunTokens.resolve(t).get.tenantEnvironment.contains("tenant-a"))
        TapRunTokens.revoke(t)
    }

    test("label helpers") {
        assert(TapRunTokens.isTapLabel("tap:foo"))
        assert(!TapRunTokens.isTapLabel("session:foo"))
        assert(!TapRunTokens.isTapLabel(null))
        assert(TapRunTokens.tapName("tap:foo") == "foo")
        assert(TapRunTokens.tapName("foo") == "foo")
    }
    // ---- plans/stories/tap-runs-hold-owner-scope.md, Acceptance bullet 4
    // (`TapRunTokensSpec`): a token records that it was used; capabilities,
    // expiry and revoke are unchanged.
    //
    // Pinned (does not exist at 4f38ae31): `TapRunToken` gains a `used` mark,
    // set when `lookup` (and so `resolve`, which every callback request reaches
    // through APIKeyValidator.validate / resolveKey) finds the live token;
    //     def revoke(token: String): Boolean   // true when the token was used
    // Today `revoke` returns Unit, so this block does not compile until then.

    test("a token that no callback presented revokes as unused") {
        val t = TapRunTokens.issue("quiet-tap", None, 60)
        val used: Boolean = TapRunTokens.revoke(t)
        assert(!used, "nothing looked the token up")
        assert(TapRunTokens.lookup(t).isEmpty, "revoke still removes the token")
    }

    test("a token presented on the callback records that it was used, and revoke returns it") {
        val viaResolve = TapRunTokens.issue("reading-tap", None, 60)
        assert(TapRunTokens.resolve(viaResolve).isDefined)
        val usedByResolve: Boolean = TapRunTokens.revoke(viaResolve)
        assert(usedByResolve, "APIKeyValidator.resolveKey reaches the token through resolve")

        val viaLookup = TapRunTokens.issue("reading-tap", None, 60)
        assert(TapRunTokens.lookup(viaLookup).isDefined)
        val usedByLookup: Boolean = TapRunTokens.revoke(viaLookup)
        assert(usedByLookup, "APIKeyValidator.validate reaches the token through lookup")
    }

    test("the used mark is per token: one run's callback does not mark another run's token") {
        val a = TapRunTokens.issue("same-tap", None, 60)
        val b = TapRunTokens.issue("same-tap", None, 60)
        TapRunTokens.resolve(a)
        val usedB: Boolean = TapRunTokens.revoke(b)
        val usedA: Boolean = TapRunTokens.revoke(a)
        assert(usedA && !usedB)
    }

    test("revoking null, an unknown string or an already revoked token answers unused and does not throw") {
        val none: Boolean = TapRunTokens.revoke(null)
        assert(!none)
        val unknown: Boolean = TapRunTokens.revoke("trt_" + "0" * 64)
        assert(!unknown)
        val t = TapRunTokens.issue("x", None, 60)
        TapRunTokens.resolve(t)
        TapRunTokens.revoke(t)
        val again: Boolean = TapRunTokens.revoke(t)
        assert(!again, "a second revoke finds nothing")
    }

    test("being used changes nothing about the identity the callback gets: same label, same fixed read bundle, no write") {
        assert(TapRunTokens.Capabilities.map(_.raw) == Seq("query:*", "search:vector", "metadata:read", "pipeline:read", "tap:read"))
        val t = TapRunTokens.issue("reading-tap", None, 60)
        val first = TapRunTokens.resolve(t).get
        val second = TapRunTokens.resolve(t).get
        assert(first == second, "a used token resolves exactly as an unused one")
        assert(first == ai.datris.model.ResolvedKey(None, "tap:reading-tap", TapRunTokens.Capabilities, isLegacyFullAccess = false))
        assert(!CallerVisibility.limited(first), "the callback identity is not held to anyone's scope in this release")
        TapRunTokens.revoke(t)
    }

    test("expiry is unchanged by use: a used token still expires, and an expired one revokes as unused") {
        val t = TapRunTokens.issue("x", None, 0)
        Thread.sleep(5)
        assert(TapRunTokens.lookup(t).isEmpty)
        assert(TapRunTokens.resolve(t).isEmpty)
        val used: Boolean = TapRunTokens.revoke(t)
        assert(!used, "a lookup that found it expired is not a use")
    }
}
