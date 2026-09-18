package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Story: Test-before-cron gate (plans/stories/test-before-cron.md).
  *
  *  A tap may only carry a schedule once the exact script (or HTTP endpoint)
  *  that will run on it has passed a `mode=test` run. All the branching lives
  *  in one pure seam so it is unit-testable without Spring, Mongo or MinIO:
  *
  *  {{{
  *  object TapCronGate {
  *      // `existing` is the STORED tap (null when the name is new); `incoming`
  *      // is the request body after script-reference preservation. Returns the
  *      // 409 refusal message, or None to allow the save. Test state is read
  *      // from `existing` only — the body's lastTestRun* fields are ignored.
  *      def check(existing: TapConfig, incoming: TapConfig): Option[String]
  *  }
  *  }}}
  *
  *  Script identity comes from `TapScriptIdentity.of` ("gh:<sha>" for
  *  repo-backed taps, "http:<endpointUrl>" for HTTP taps, sha256 of the script
  *  for MinIO taps). This spec uses repo-backed and HTTP taps only, so identity
  *  is derivable from the config alone and the gate never touches storage.
  *
  *  Rules pinned (from the story's Steps 3-4 and Backward compat):
  *   - refuse when the effective cron changes (incl. null → set) and the stored
  *     tap has no successful test for the incoming identity;
  *   - refuse when the cron is non-null and unchanged but the incoming identity
  *     differs from the stored stamp (script edit on a scheduled tap);
  *   - allow when the incoming cron is null (clearing / never scheduled);
  *   - allow when identity matches the stored stamp and lastTestRunStatus == "success";
  *   - legacy (unstamped) taps: unrelated edits with an unchanged cron always
  *     pass; the first cron/script change is grandfathered by a prior
  *     lastTestRunStatus == "success" OR lastRunStatus == "success";
  *   - the message names the tap and gives the remedy verbatim.
  */
class TapCronGateSpec extends AnyFunSuite {

    private val Cron = "0 0 3 * * ?"
    private val OtherCron = "0 30 5 * * ?"
    private val Remedy = "save the tap without `cronExpression`, call `test_tap`, then `update_tap` with the cron"

    /** Repo-backed python tap: identity is "gh:" + sha, no storage read needed. */
    private def gh(
        sha: String,
        cron: String = null,
        testStatus: String = null,
        stamp: String = null,
        runStatus: String = null,
        description: String = "nightly prices"
    ): TapConfig =
        TapConfig(
            name = "prices",
            description = description,
            targetPipeline = "prices",
            scriptStorage = "github",
            scriptRepoPath = "taps/prices.py",
            scriptCommitSha = sha,
            cronExpression = cron,
            lastTestRunStatus = testStatus,
            lastTestRunScriptId = stamp,
            lastRunStatus = runStatus
        )

    /** HTTP tap: identity is "http:" + endpointUrl. */
    private def http(url: String, cron: String = null, testStatus: String = null, stamp: String = null): TapConfig =
        TapConfig(
            name = "feed",
            description = "endpoint feed",
            targetPipeline = "feed",
            scriptKind = "http",
            endpointUrl = url,
            cronExpression = cron,
            lastTestRunStatus = testStatus,
            lastTestRunScriptId = stamp
        )

    // ------------------------------------------------------------ bullet 1 ---

    test("refuses a cron on a never-tested script") {
        // Brand-new tap (nothing stored yet) arriving with a cron.
        val fresh = TapCronGate.check(null, gh("aaa111", cron = Cron))
        assert(fresh.isDefined, "a never-tested new tap must not be saved with a cron")
        assert(fresh.get.contains("prices"), s"message must name the tap: ${fresh.get}")
        assert(fresh.get.contains(Remedy), s"message must give the remedy verbatim: ${fresh.get}")

        // Stored tap, script never tested, cron being set for the first time.
        val stored = gh("aaa111")
        val refused = TapCronGate.check(stored, gh("aaa111", cron = Cron))
        assert(refused.isDefined, "setting a cron on a stored-but-untested script must be refused")
        assert(refused.get.contains("prices") && refused.get.contains(Remedy), refused.get)
    }

    test("allows a cadence-only change on a green tap") {
        val green = gh("aaa111", cron = Cron, testStatus = "success", stamp = "gh:aaa111")
        assert(TapCronGate.check(green, gh("aaa111", cron = OtherCron)).isEmpty, "same script, tested, new cadence must pass")
        // First schedule on a tap that was tested before ever being scheduled.
        val testedUnscheduled = gh("aaa111", testStatus = "success", stamp = "gh:aaa111")
        assert(TapCronGate.check(testedUnscheduled, gh("aaa111", cron = Cron)).isEmpty, "tested script, first cron must pass")
    }

    test("refuses a cron after the script identity changes") {
        val green = gh("aaa111", cron = Cron, testStatus = "success", stamp = "gh:aaa111")
        val refused = TapCronGate.check(green, gh("bbb222", cron = Cron))
        assert(refused.isDefined, "script changed under an unchanged cron must be refused until re-tested")
        assert(refused.get.contains("prices") && refused.get.contains(Remedy), refused.get)
        assert(refused.get.toLowerCase.contains("script"), s"message must say the script condition fired: ${refused.get}")
        // Same for a new cadence on the changed script.
        assert(TapCronGate.check(green, gh("bbb222", cron = OtherCron)).isDefined)
    }

    test("allows clearing a cron") {
        val scheduledUntested = gh("aaa111", cron = Cron)
        assert(TapCronGate.check(scheduledUntested, gh("aaa111", cron = null)).isEmpty, "clearing the cron never needs a test")
        // Clearing while also swapping the script: still nothing to schedule, so allowed.
        assert(TapCronGate.check(scheduledUntested, gh("bbb222", cron = null)).isEmpty)
        // A tap that never had a cron and still has none.
        assert(TapCronGate.check(gh("aaa111"), gh("bbb222")).isEmpty)
        assert(TapCronGate.check(null, gh("aaa111")).isEmpty, "a new tap without a cron is not gated")
    }

    test("allows an unrelated edit on a legacy tap with an unchanged cron") {
        // Pre-upgrade tap: scheduled, no stamp, never tested, last real run failed.
        val legacy = gh("aaa111", cron = Cron, runStatus = "failure")
        val edit = gh("aaa111", cron = Cron, description = "nightly prices (renamed)")
            .copy(tags = List("finance").asJava, enabled = false, targetPipeline = "prices_v2")
        assert(TapCronGate.check(legacy, edit).isEmpty, "description/tags/target/enable edits on a legacy scheduled tap must pass with no test")
    }

    test("ignores lastTestRun fields supplied in the incoming body") {
        val storedUntested = gh("aaa111")
        val forged = gh("aaa111", cron = Cron, testStatus = "success", stamp = "gh:aaa111", runStatus = "success")
        val refused = TapCronGate.check(storedUntested, forged)
        assert(refused.isDefined, "test state must be read from the stored tap, never from the request body")
        assert(refused.get.contains(Remedy), refused.get)
        // Same forgery on a brand-new tap.
        assert(TapCronGate.check(null, forged).isDefined)
    }

    // ------------------------------------------------- Backward compat -----

    test("grandfathers a legacy tap's first cron change through a prior successful test or real run") {
        val legacyRan = gh("aaa111", runStatus = "success")
        assert(TapCronGate.check(legacyRan, gh("aaa111", cron = Cron)).isEmpty, "unstamped + lastRunStatus success must pass one cron edit")
        val legacyTested = gh("aaa111", testStatus = "success")
        assert(TapCronGate.check(legacyTested, gh("aaa111", cron = Cron)).isEmpty, "unstamped + lastTestRunStatus success must pass one cron edit")
        val legacyNeither = gh("aaa111", runStatus = "failure", testStatus = "failure")
        assert(TapCronGate.check(legacyNeither, gh("aaa111", cron = Cron)).isDefined, "unstamped with no success anywhere is refused")
    }

    test("HTTP taps key on endpointUrl") {
        val green = http("https://feeds.example.org/v1/prices", cron = Cron, testStatus = "success", stamp = "http:https://feeds.example.org/v1/prices")
        assert(TapCronGate.check(green, http("https://feeds.example.org/v1/prices", cron = OtherCron)).isEmpty)
        val moved = TapCronGate.check(green, http("https://feeds.example.org/v2/prices", cron = Cron))
        assert(moved.isDefined, "a changed endpoint under a cron must be re-tested")
        assert(moved.get.contains("feed") && moved.get.contains(Remedy), moved.get)
    }
}
