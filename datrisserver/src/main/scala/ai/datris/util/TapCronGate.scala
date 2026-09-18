package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.TapConfig

/** Test-before-cron gate (plans/stories/test-before-cron.md).
  *
  * A tap may only carry a schedule once the exact script (or HTTP endpoint)
  * that will run on it has passed a `mode=test` run. Pure: all branching lives
  * here so it is unit-testable without Spring, Mongo or MinIO.
  *
  * Rules:
  *  - the incoming cron is null (never scheduled / being cleared) → allow;
  *  - the cron is unchanged and the script identity matches what the stored
  *    tap was tested with (or, for an unstamped legacy tap, the stored script
  *    is unchanged) → allow — unrelated edits never need a test;
  *  - the cron changes (incl. null → set) → allow only when the stored tap has
  *    a successful test stamped for the incoming identity, or is a legacy
  *    unstamped tap with a prior successful test or real run;
  *  - the cron is unchanged but the identity differs from the stamp → refuse
  *    (script edit on a scheduled tap).
  *
  * Test state is read from `existing` ONLY. Clients (UI save, taps-list cron
  * edit) post stale or forged `lastTestRun*` fields in the body; they are ignored.
  */
object TapCronGate {

    val Remedy = "save the tap without `cronExpression`, call `test_tap`, then `update_tap` with the cron"

    /** Remedy for the STORE lane (`POST /tap/script`, AI script endpoints): the
      * stored tap still carries its cron, so re-saving without a cron would hit
      * the same gate. The cron has to be cleared on the stored tap first. */
    val StoreRemedy =
        "clear its cronExpression first (update_tap with an empty cron_expression), store the script, call test_tap, then update_tap with the cron"

    /** `existing` is the STORED tap (null when the name is new); `incoming` is
      * the request body after script-reference preservation. Returns the 409
      * refusal message, or None to allow the save. */
    def check(existing: TapConfig, incoming: TapConfig): Option[String] = {
        val incomingCron = blankToNull(incoming.cronExpression)
        if (incomingCron == null) return None

        val name = incoming.name
        val existingCron = if (existing == null) null else blankToNull(existing.cronExpression)
        val cronChanged = existingCron != incomingCron

        if (existing == null)
            return Some(refusal(name, "its script has never passed a test run"))

        val incomingId = TapScriptIdentity.of(incoming)
        val stamp = blankToNull(existing.lastTestRunScriptId)
        val testedGreen = "success".equalsIgnoreCase(existing.lastTestRunStatus)

        if (stamp != null) {
            // Stamped tap: the stamp names the exact bytes that passed the test.
            val identityMatches = incomingId.contains(stamp)
            if (identityMatches && testedGreen) None
            else if (!identityMatches)
                Some(refusal(name, "its script changed since it last passed a test run"))
            else Some(refusal(name, "its script has never passed a test run"))
        } else {
            // Legacy / unstamped tap (pre-upgrade or never tested).
            val existingId = TapScriptIdentity.of(existing)
            val scriptUnchanged = incomingId.isDefined && incomingId == existingId
            // Unrelated edit (cron untouched) on a legacy tap whose script the
            // store cannot read right now: the reference itself is unchanged, so
            // nothing new is being scheduled. No identity is minted either way.
            val sameUnreadableRef = incomingId.isEmpty && existingId.isEmpty && sameScriptRef(existing, incoming)
            if (!cronChanged && (scriptUnchanged || sameUnreadableRef)) None
            else if (!cronChanged)
                Some(refusal(name, "its script changed since it last passed a test run"))
            else {
                val ranGreen = "success".equalsIgnoreCase(existing.lastRunStatus)
                if (scriptUnchanged && (testedGreen || ranGreen)) None
                else if (!scriptUnchanged)
                    Some(refusal(name, "its script changed since it last passed a test run"))
                else Some(refusal(name, "its script has never passed a test run"))
            }
        }
    }

    /** Up-front gate for `POST /tap/script`: may `script` replace the stored
      * bytes of `existing`? Evaluated BEFORE anything is written, so a refused
      * edit under a cron never commits to the repo or MinIO (a commit that the
      * tap is not repointed at would leave the repo head ahead of the pin and
      * every later store would fail the drift check).
      *
      * Rule: nothing to gate when the tap is new or unscheduled; identical
      * bytes are a no-op; different (or unreadable stored) bytes under a cron
      * are the "script changed" refusal — the remedy clears the cron first.
      * `readScript` reads the CURRENT stored bytes of `existing`. */
    def checkScriptStore(existing: TapConfig, script: String, readScript: TapConfig => Option[String]): Option[String] = {
        if (existing == null || blankToNull(existing.cronExpression) == null) return None
        val stored = scala.util.Try(readScript(existing)).toOption.flatten
        if (stored.contains(script)) None
        else Some(storeRefusal(existing.name))
    }

    /** The store-lane refusal: same "cannot be scheduled: " prefix (the wizard
      * keys on it), store-lane remedy. Also used by the AI script endpoints
      * when they decline to repoint a scheduled tap. */
    def storeRefusal(name: String): String =
        "Tap '" + name + "' cannot be scheduled: its script changed since it last passed a test run. Remedy: " + StoreRemedy + "."

    private def sameScriptRef(a: TapConfig, b: TapConfig): Boolean =
        a.isHttp == b.isHttp &&
            blankToNull(a.endpointUrl) == blankToNull(b.endpointUrl) &&
            blankToNull(a.scriptStorage) == blankToNull(b.scriptStorage) &&
            blankToNull(a.scriptPath) == blankToNull(b.scriptPath) &&
            blankToNull(a.scriptRepoPath) == blankToNull(b.scriptRepoPath) &&
            blankToNull(a.scriptCommitSha) == blankToNull(b.scriptCommitSha)

    private def refusal(name: String, reason: String): String =
        "Tap '" + name + "' cannot be scheduled: " + reason + ". Remedy: " + Remedy + "."

    private def blankToNull(s: String): String = if (s == null || s.trim.isEmpty) null else s
}
