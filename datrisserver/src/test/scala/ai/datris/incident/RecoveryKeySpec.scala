package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Capability widening for an already-issued recovery-agent key. */
class RecoveryKeySpec extends AnyFunSuite {

    test("a key issued before config:read existed is missing exactly that") {
        val old = RecoveryKey.Capabilities.filterNot(_ == "config:read")
        val json = s"""{"capabilities":[${old.map("\"" + _ + "\"").mkString(",")}],"revoked":false,"keyId":"k_1"}"""
        assert(RecoveryKey.missingCapabilities(json) == Seq("config:read"))
    }

    test("a current key is missing nothing") {
        val json = s"""{"capabilities":[${RecoveryKey.Capabilities.map("\"" + _ + "\"").mkString(",")}],"revoked":false}"""
        assert(RecoveryKey.missingCapabilities(json).isEmpty)
    }

    test("a revoked key is never widened; malformed metadata is left alone") {
        assert(RecoveryKey.missingCapabilities("""{"capabilities":[],"revoked":true}""").isEmpty)
        assert(RecoveryKey.missingCapabilities("not json").isEmpty)
    }
}
