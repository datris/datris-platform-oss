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
}
