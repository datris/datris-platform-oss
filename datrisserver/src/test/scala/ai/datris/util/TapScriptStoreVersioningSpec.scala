package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.scalatest.funsuite.AnyFunSuite

/** The script store lane versions a script replacement on an existing tap;
  * the config lane mints no version for a save that changes nothing. */
class TapScriptStoreVersioningSpec extends AnyFunSuite {

    private val minioTap = TapConfig(
        name = "orders",
        description = "d",
        targetPipeline = "orders",
        scriptPath = "tap-scripts/orders_aaaa1111.py",
        version = 9,
        lastTestRunScriptId = "hash-old"
    )
    private val ghTap = minioTap.copy(
        scriptPath = null,
        scriptStorage = "github",
        scriptRepoPath = "taps/orders.py",
        scriptCommitSha = "023575b5"
    )

    /** Identity by script text, keyed on the reference (no backend). */
    private def identities(m: Map[String, String]): TapConfig => Option[String] = t =>
        Option(if (t.scriptStorage == "github") t.scriptCommitSha else t.scriptPath).flatMap(m.get)

    private val bytes = identities(Map(
        "tap-scripts/orders_aaaa1111.py" -> "hash-old",
        "tap-scripts/orders_bbbb2222.py" -> "hash-new",
        "tap-scripts/orders_cccc3333.py" -> "hash-old",
        "023575b5" -> "gh:023575b5",
        "d373a026" -> "gh:d373a026"
    ))

    test("new bytes on an existing built-in tap: versioned as 'script replaced'") {
        val repointed = minioTap.copy(scriptPath = "tap-scripts/orders_bbbb2222.py")
        assert(TapScriptStoreVersioning.decide(minioTap, repointed, None, bytes) == Some("script replaced"))
    }

    test("new commit on an existing repository tap: versioned") {
        val repointed = ghTap.copy(scriptCommitSha = "d373a026")
        assert(TapScriptStoreVersioning.decide(ghTap, repointed, None, bytes) == Some("script replaced"))
    }

    test("the incident id is named in the change note") {
        val repointed = ghTap.copy(scriptCommitSha = "d373a026")
        assert(TapScriptStoreVersioning.decide(ghTap, repointed, Some("inc_42"), bytes) == Some("script replaced (incident inc_42)"))
        assert(TapScriptStoreVersioning.decide(ghTap, repointed, Some("  "), bytes) == Some("script replaced"))
    }

    test("an AI lane passes its own note; the incident id is still appended") {
        val repointed = minioTap.copy(scriptPath = "tap-scripts/orders_bbbb2222.py")
        assert(
            TapScriptStoreVersioning.decide(minioTap, repointed, None, bytes, TapScriptStoreVersioning.ScriptGenerated) == Some("script generated")
        )
        assert(
            TapScriptStoreVersioning.decide(minioTap, repointed, Some("inc_7"), bytes, TapScriptStoreVersioning.ScriptFixed) ==
                Some("script fixed (incident inc_7)")
        )
        assert(TapScriptStoreVersioning.decide(minioTap, repointed, None, bytes, null) == Some("script replaced"))
        // Same rules as the store lane: identical bytes stay a plain write.
        val sameBytes = minioTap.copy(scriptPath = "tap-scripts/orders_cccc3333.py")
        assert(TapScriptStoreVersioning.decide(minioTap, sameBytes, None, bytes, TapScriptStoreVersioning.ScriptGenerated).isEmpty)
    }

    test("no existing tap (wizard first upload): plain write") {
        assert(TapScriptStoreVersioning.decide(null, minioTap, Some("inc_42"), bytes).isEmpty)
    }

    test("same reference: plain write") {
        assert(TapScriptStoreVersioning.decide(minioTap, minioTap, None, bytes).isEmpty)
        assert(TapScriptStoreVersioning.decide(ghTap, ghTap.copy(inferredDependsOn = java.util.Arrays.asList("a")), None, bytes).isEmpty)
    }

    test("only the test stamp moved: plain write") {
        assert(TapScriptStoreVersioning.decide(minioTap, minioTap.copy(lastTestRunScriptId = "hash-new"), None, bytes).isEmpty)
    }

    test("identical bytes re-stored at a new built-in path: plain write") {
        val repointed = minioTap.copy(scriptPath = "tap-scripts/orders_cccc3333.py")
        assert(TapScriptStoreVersioning.decide(minioTap, repointed, None, bytes).isEmpty)
    }

    test("an unreadable old or new script is treated as a replacement") {
        val unreadable = minioTap.copy(scriptPath = "tap-scripts/orders_missing.py")
        assert(TapScriptStoreVersioning.decide(unreadable, minioTap, None, bytes).isDefined)
        val throwing: TapConfig => Option[String] = _ => throw new RuntimeException("backend down")
        assert(TapScriptStoreVersioning.decide(minioTap, minioTap.copy(scriptPath = "tap-scripts/orders_bbbb2222.py"), None, throwing).isDefined)
    }

    test("sameDefinition ignores version, timestamps and run/test status churn") {
        val echoed = minioTap.copy(
            version = 4,
            updatedAt = "2026-10-19 10:00:00",
            createdAt = "x",
            lastRunStatus = "failure",
            lastRunRecordCount = 7,
            lastTestRunStatus = "success",
            lastTestRunScriptId = "other",
            retryCount = 2
        )
        assert(TapScriptStoreVersioning.sameDefinition(minioTap, echoed))
    }

    test("sameDefinition sees definition changes") {
        assert(!TapScriptStoreVersioning.sameDefinition(minioTap, minioTap.copy(description = "changed")))
        assert(!TapScriptStoreVersioning.sameDefinition(minioTap, minioTap.copy(cronExpression = "0 0 * * * ?")))
        assert(!TapScriptStoreVersioning.sameDefinition(minioTap, minioTap.copy(scriptPath = "tap-scripts/orders_bbbb2222.py")))
        assert(!TapScriptStoreVersioning.sameDefinition(minioTap, minioTap.copy(enabled = false)))
        assert(!TapScriptStoreVersioning.sameDefinition(minioTap, minioTap.copy(dependsOn = new java.util.ArrayList[String]())))
        assert(!TapScriptStoreVersioning.sameDefinition(null, minioTap))
    }
}
