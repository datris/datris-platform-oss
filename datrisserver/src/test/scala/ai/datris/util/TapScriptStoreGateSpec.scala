package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.scalatest.funsuite.AnyFunSuite

/** Story: Test-before-cron gate — `POST /tap/script` ordering.
  *
  *  `TapCronGate.checkScriptStore(existing, script, readScript)` is evaluated
  *  BEFORE the code store writes anything, so a refused script edit under a
  *  cron never commits to the repo (which would leave the repo head ahead of
  *  the tap's pinned commit and make every later store fail the drift check).
  */
class TapScriptStoreGateSpec extends AnyFunSuite {

    private val Cron = "0 0 3 * * ?"
    // Store-lane remedy: re-saving without a cron would hit the same gate (the
    // STORED tap still has its cron), so the message must say to clear it first.
    private val Remedy =
        "clear its cronExpression first (update_tap with an empty cron_expression), store the script, call test_tap, then update_tap with the cron"
    private val Old = "def fetch():\n    return [{'a': 1}]\n"
    private val New = "def fetch():\n    return [{'a': 2}]\n"

    private val noRead: TapConfig => Option[String] =
        _ => throw new AssertionError("must not read the stored script when there is nothing to gate")

    private def gh(cron: String = null, stamp: String = null, testStatus: String = null): TapConfig =
        TapConfig(
            name = "prices",
            description = "d",
            targetPipeline = "p",
            scriptStorage = "github",
            scriptRepoPath = "taps/prices.py",
            scriptCommitSha = "b49f667",
            cronExpression = cron,
            lastTestRunScriptId = stamp,
            lastTestRunStatus = testStatus
        )

    private def minio(cron: String = null): TapConfig =
        TapConfig(name = "prices", description = "d", targetPipeline = "p", scriptPath = "tap-scripts/prices_1.py", cronExpression = cron)

    test("a new or unscheduled tap is never gated and nothing is read") {
        assert(TapCronGate.checkScriptStore(null, New, noRead).isEmpty)
        assert(TapCronGate.checkScriptStore(gh(), New, noRead).isEmpty)
        assert(TapCronGate.checkScriptStore(gh(cron = "  "), New, noRead).isEmpty)
        assert(TapCronGate.checkScriptStore(minio(), New, noRead).isEmpty)
    }

    test("different bytes under a cron are refused before any write, on both lanes") {
        val refused = TapCronGate.checkScriptStore(gh(cron = Cron, stamp = "gh:b49f667", testStatus = "success"), New, _ => Some(Old))
        assert(refused.isDefined, "a scheduled github tap must not have new bytes committed")
        assert(refused.get.contains("prices") && refused.get.contains(Remedy), refused.get)
        assert(refused.get.contains("cannot be scheduled: "), s"wizard keys on this prefix: ${refused.get}")
        assert(refused.get == TapCronGate.storeRefusal("prices"), "AI endpoints reuse the same store-lane message")
        assert(refused.get.toLowerCase.contains("script"), refused.get)
        assert(TapCronGate.checkScriptStore(minio(cron = Cron), New, _ => Some(Old)).isDefined)
        // Legacy unstamped scheduled tap: same rule (cron unchanged + script changed).
        assert(TapCronGate.checkScriptStore(gh(cron = Cron), New, _ => Some(Old)).isDefined)
    }

    test("identical bytes under a cron are a no-op and allowed") {
        assert(TapCronGate.checkScriptStore(gh(cron = Cron, stamp = "gh:b49f667", testStatus = "success"), Old, _ => Some(Old)).isEmpty)
        assert(TapCronGate.checkScriptStore(minio(cron = Cron), Old, _ => Some(Old)).isEmpty)
    }

    test("identical bytes that differ only in trailing newline or line endings are allowed") {
        // The object-store reader joins lines and drops the final newline.
        assert(TapCronGate.checkScriptStore(minio(cron = Cron), Old, _ => Some(Old.stripSuffix("\n"))).isEmpty, "posted with trailing newline, stored without")
        assert(TapCronGate.checkScriptStore(minio(cron = Cron), Old.stripSuffix("\n"), _ => Some(Old)).isEmpty, "stored with trailing newline, posted without")
        assert(TapCronGate.checkScriptStore(gh(cron = Cron), Old.replace("\n", "\r\n"), _ => Some(Old)).isEmpty, "CRLF vs LF")
        // Exact equality otherwise: a one-character edit is still a change.
        assert(TapCronGate.checkScriptStore(minio(cron = Cron), Old + "#", _ => Some(Old)).isDefined)
    }

    test("AI script endpoints never repoint a scheduled tap, on either lane") {
        val newPath = "tap-scripts/prices_9ea4e291.py"
        // Repo-backed tap: identity is the pinned commit, so a scriptPath swap must be caught here.
        val ghRefused = TapCronGate.checkAiRepoint(gh(cron = Cron, stamp = "gh:b49f667", testStatus = "success"), newPath)
        assert(ghRefused.isDefined && ghRefused.get == TapCronGate.storeRefusal("prices"), ghRefused.toString)
        assert(TapCronGate.checkAiRepoint(minio(cron = Cron), newPath).isDefined)
        // Unscheduled, new, or same path: allowed.
        assert(TapCronGate.checkAiRepoint(gh(), newPath).isEmpty)
        assert(TapCronGate.checkAiRepoint(minio(), newPath).isEmpty)
        assert(TapCronGate.checkAiRepoint(null, newPath).isEmpty)
        assert(TapCronGate.checkAiRepoint(minio(cron = Cron), "tap-scripts/prices_1.py").isEmpty, "same path is not a repoint")
    }

    test("an unreadable stored script under a cron is refused rather than silently replaced") {
        assert(TapCronGate.checkScriptStore(gh(cron = Cron), New, _ => None).isDefined)
        assert(TapCronGate.checkScriptStore(gh(cron = Cron), New, _ => throw new RuntimeException("connection refused")).isDefined)
    }
}
