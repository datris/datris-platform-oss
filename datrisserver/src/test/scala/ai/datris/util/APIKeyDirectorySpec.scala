package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Tap runs and owner access, report-only release, review follow-up: the key
  * resolver a scan uses ([[APIKeyValidator.labelResolver]] /
  * [[APIKeyValidator.keyDirectory]], pure core
  * [[APIKeyValidator.directoryOver]]) reads the key store and its metadata at
  * most once each, keeps a failed read, and never turns a store failure into
  * "unknown key". */
class APIKeyDirectorySpec extends AnyFunSuite {

    private val keys = Map("sales-agent" -> "v1", "old-agent" -> "v2", "gone-agent" -> "v3")
    private val metadata = Map(
        "sales-agent" -> """{"capabilities":["pipeline:read:catalog=Sales","tap:read:catalog=Sales"]}""",
        "gone-agent" -> """{"capabilities":["job:read"],"revoked":true}"""
    )

    test("each store is read at most once for many labels; answers match resolveByLabel's rules") {
        var keyReads = 0
        var metaReads = 0
        val d = APIKeyValidator.directoryOver(() => { keyReads += 1; keys }, () => { metaReads += 1; metadata })
        assert(d.byLabel("sales-agent").exists(k => !k.isLegacyFullAccess && k.label == "sales-agent"))
        assert(d.byLabel("old-agent").exists(_.isLegacyFullAccess), "no metadata: legacy full access")
        assert(d.byLabel("gone-agent").isEmpty, "revoked")
        assert(d.byLabel("never-issued").isEmpty, "unknown")
        assert(d.byLabel(null).isEmpty && d.byLabel("").isEmpty)
        assert(d.all().map(_.label) == List("old-agent", "sales-agent"), "every key that resolves, by label; the revoked one is not listed")
        assert(keyReads == 1 && metaReads == 1, (keyReads, metaReads).toString)
    }

    test("a failed key store read is kept: every later lookup throws it again, none answers unknown, and the store is not asked again") {
        var keyReads = 0
        val d = APIKeyValidator.directoryOver(() => { keyReads += 1; throw new IllegalStateException("API key store unavailable") }, () => metadata)
        for (label <- Seq("sales-agent", "never-issued", "sales-agent")) {
            val e = intercept[IllegalStateException](d.byLabel(label))
            assert(e.getMessage == "API key store unavailable", label)
        }
        intercept[IllegalStateException](d.all())
        assert(keyReads == 1, keyReads.toString)
    }

    test("a failed metadata read is kept the same way") {
        var metaReads = 0
        val d = APIKeyValidator.directoryOver(() => keys, () => { metaReads += 1; throw new IllegalStateException("API key metadata unavailable") })
        assert(intercept[IllegalStateException](d.byLabel("sales-agent")).getMessage == "API key metadata unavailable")
        assert(intercept[IllegalStateException](d.byLabel("old-agent")).getMessage == "API key metadata unavailable", "never legacy full access for an outage")
        // a label the key store does not hold needs no metadata: it is unknown
        assert(d.byLabel("never-issued").isEmpty)
        assert(metaReads == 1, metaReads.toString)
    }

    test("labelResolver answers through the override when one is set") {
        val saved = APIKeyValidator.resolveByLabelOverride
        try {
            APIKeyValidator.resolveByLabelOverride = _ => throw new IllegalStateException("store down")
            val r = APIKeyValidator.labelResolver()
            intercept[IllegalStateException](r("sales-agent"))
            intercept[IllegalStateException](APIKeyValidator.keyDirectory().all())
        } finally APIKeyValidator.resolveByLabelOverride = saved
    }
}
