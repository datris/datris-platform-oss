package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

/** Revocation only flags metadata — the key value stays in `oss/api-keys` —
  * so both the controller-side `validate` and the interceptor-side
  * `resolveKey` must consult the revoked flag. */
class APIKeyValidatorSpec extends AnyFunSuite {

    private val keys = Map("reader" -> "val-reader", "legacy" -> "val-legacy", "live" -> "val-live")
    private val metadata = Map(
        "reader" -> """{"capabilities":["pipeline:read"],"revoked":true,"keyId":"k1"}""",
        "live" -> """{"capabilities":["pipeline:read"],"revoked":false,"keyId":"k2"}"""
    )

    test("validate rejects a revoked key whose value is still in oss/api-keys") {
        val e = intercept[DatrisException](APIKeyValidator.validateAgainst("val-reader", keys, metadata))
        assert(e.getMessage == "API key is revoked")
    }

    test("validate accepts a live scoped key") {
        APIKeyValidator.validateAgainst("val-live", keys, metadata)
    }

    test("validate accepts a legacy key with no metadata entry") {
        APIKeyValidator.validateAgainst("val-legacy", keys, metadata)
    }

    test("validate rejects an unknown value without reading metadata") {
        val e = intercept[DatrisException](
            APIKeyValidator.validateAgainst("nope", keys, fail("metadata must not be read for an unknown key"))
        )
        assert(e.getMessage.startsWith("Invalid x-api-key"))
    }

    test("resolveKey core still throws for a revoked key") {
        val e = intercept[DatrisException](APIKeyValidator.resolveAgainst("val-reader", keys, metadata))
        assert(e.getMessage == "API key 'reader' is revoked")
    }

    test("resolveKey core resolves scoped and legacy keys") {
        val live = APIKeyValidator.resolveAgainst("val-live", keys, metadata)
        assert(live.label == "live" && !live.isLegacyFullAccess && live.keyId.contains("k2"))
        val legacy = APIKeyValidator.resolveAgainst("val-legacy", keys, metadata)
        assert(legacy.isLegacyFullAccess && legacy.capabilities == Seq(Capability.FullAccess))
    }
}
