package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

import scala.util.{Failure, Success}

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
        assert(e.getMessage == "Invalid x-api-key")
        assert(!e.getMessage.contains("nope"), "the presented value must not be echoed")
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

    test("absent metadata secret means legacy full access") {
        assert(APIKeyValidator.metadataFrom(Success(None)).isEmpty)
        val legacy = APIKeyValidator.resolveAgainst("val-reader", keys, APIKeyValidator.metadataFrom(Success(None)))
        assert(legacy.isLegacyFullAccess)
    }

    test("present metadata secret is returned as a map") {
        val m = new java.util.HashMap[String, String]()
        m.put("reader", metadata("reader"))
        assert(APIKeyValidator.metadataFrom(Success(Some(m))) == Map("reader" -> metadata("reader")))
    }

    test("metadata read failure fails closed for resolve and validate") {
        val failed = Failure(new RuntimeException("vault 503"))
        val e1 = intercept[DatrisException](APIKeyValidator.resolveAgainst("val-reader", keys, APIKeyValidator.metadataFrom(failed)))
        assert(e1.getMessage == APIKeyValidator.MetadataUnavailableMessage)
        val e2 = intercept[DatrisException](APIKeyValidator.validateAgainst("val-legacy", keys, APIKeyValidator.metadataFrom(failed)))
        assert(e2.getMessage == APIKeyValidator.MetadataUnavailableMessage)
    }

    test("corrupt metadata JSON for one key only affects that key") {
        val md = metadata + ("live" -> "{not json")
        intercept[DatrisException](APIKeyValidator.resolveAgainst("val-live", keys, md))
        assert(APIKeyValidator.resolveAgainst("val-legacy", keys, md).isLegacyFullAccess)
        intercept[DatrisException](APIKeyValidator.resolveAgainst("val-reader", keys, md))
    }
}
