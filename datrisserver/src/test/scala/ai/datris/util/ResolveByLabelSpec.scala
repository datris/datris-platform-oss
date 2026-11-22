package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.Capability
import org.scalatest.funsuite.AnyFunSuite

/** Story: an approved agent action runs with the requester's access
  * (plans/stories/approval-replay-requester-scope.md). The replay of an
  * approval finds the requesting key's current access by its label
  * (`APIKeyValidator.resolveByLabel`); this pins its pure core,
  * `resolveLabelAgainst`, against the key and metadata maps the secret store
  * holds: the same answer a request presenting that key's value gets. */
class ResolveByLabelSpec extends AnyFunSuite {

    private val keys = Map("sales-deleter" -> "v-sales", "old-agent" -> "v-old", "gone-agent" -> "v-gone", "owner-agent" -> "v-owner")

    private val metadata = Map(
        "sales-deleter" -> """{"capabilities":["pipeline:read:catalog=Sales","pipeline:delete:catalog=Sales"],"keyId":"k_sales"}""",
        "gone-agent" -> """{"capabilities":["pipeline:read"],"revoked":true,"keyId":"k_gone"}""",
        "owner-agent" -> """{"capabilities":["pipeline:read:owner=self","pipeline:create"]}"""
    )

    private def resolve(label: String, md: => Map[String, String] = metadata) = APIKeyValidator.resolveLabelAgainst(label, keys, md)

    test("a revoked key resolves to nothing") {
        assert(resolve("gone-agent").isEmpty)
    }

    test("a label no key is issued under resolves to nothing, even when metadata names it") {
        assert(resolve("nobody").isEmpty)
        assert(APIKeyValidator.resolveLabelAgainst("sales-deleter", Map.empty, metadata).isEmpty)
    }

    test("a key with no metadata is legacy full access with no key id") {
        val rk = resolve("old-agent").get
        assert(rk.label == "old-agent" && rk.isLegacyFullAccess && rk.isFullAccess && rk.keyId.isEmpty, rk.toString)
        assert(rk.capabilities == Seq(Capability.FullAccess))
    }

    test("a scoped key resolves to its capabilities, its scopes and its key id, as a request presenting it would") {
        val rk = resolve("sales-deleter").get
        assert(rk.label == "sales-deleter" && !rk.isLegacyFullAccess && rk.keyId.contains("k_sales"), rk.toString)
        assert(rk.capabilities.map(_.raw).toSet == Set("pipeline:read:catalog=Sales", "pipeline:delete:catalog=Sales"), rk.toString)
        assert(rk.grants("pipeline", "delete", Map("catalog" -> "Sales")))
        assert(!rk.grants("pipeline", "delete", Map("catalog" -> "Finance")))
        assert(rk == APIKeyValidator.resolveAgainst("v-sales", keys, metadata))
    }

    test("a key recorded without a key id resolves with none") {
        val rk = resolve("owner-agent").get
        assert(rk.keyId.isEmpty && !rk.isLegacyFullAccess, rk.toString)
        assert(rk == APIKeyValidator.resolveAgainst("v-owner", keys, metadata))
    }

    test("a metadata read that fails throws instead of answering: never nothing, never legacy full access") {
        val e = intercept[Exception](resolve("sales-deleter", throw new IllegalStateException("metadata unavailable")))
        assert(e.getMessage.contains("metadata unavailable"))
        // the read failure the store reports becomes a throw, not an empty map
        intercept[Exception](APIKeyValidator.metadataFrom(scala.util.Failure(new RuntimeException("down"))))
    }

    test("malformed metadata throws instead of answering") {
        intercept[Exception](resolve("sales-deleter", Map("sales-deleter" -> "{not json")))
    }
}
