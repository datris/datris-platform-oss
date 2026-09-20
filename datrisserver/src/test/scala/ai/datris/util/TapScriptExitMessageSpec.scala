package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: taps survive large sources (plans/stories/tap-large-sources.md),
  *  Step 8 — the exit-code → failure-message mapping.
  *
  *  Both execution lanes (sidecar trailer, `executeViaRunner`; in-process
  *  `executeWithTimeout`) end in the same `"Tap script failed (exit code N): …"`
  *  throw. The sidecar reports a SIGKILLed child as Python's negative signal
  *  number, `-9`; with `timedOut == false` that is, in practice, always the
  *  kernel OOM killer taking a script that materialised its whole result. The
  *  message must say so and name the fix, or an agent reverse-engineers the
  *  wrapper instead of rewriting `fetch()`.
  *
  *  Seam assumed (one helper both throw sites call — the text must be
  *  identical on both lanes, so it cannot live inline twice):
  *
  *  {{{
  *  object TapScriptRunner {
  *      // The DatrisException message for a non-zero exit that did not time out.
  *      private[util] def scriptFailureMessage(exitCode: Int, errOutput: String): String
  *  }
  *  }}}
  *
  *  The live `-9` path (a real sidecar, a real OOM) is E2E-only; see the
  *  story's Verify block ("a deliberately memory-hungry script fails with an
  *  error naming memory and `yield`").
  */
class TapScriptExitMessageSpec extends AnyFunSuite {

    test("exit -9 (not timed out) is reported as killed for memory with the yield remedy, not as a bare exit code") {
        val msg = TapScriptRunner.scriptFailureMessage(-9, "")
        val lower = msg.toLowerCase
        assert(lower.contains("killed"), "must say the script was killed: " + msg)
        assert(lower.contains("memory"), "must name memory as the cause: " + msg)
        assert(lower.contains("yield"), "must give the remedy — yield records from fetch(): " + msg)
        assert(msg.contains("tap-workflow-reference"), "must point at the contract the agent already has: " + msg)
        assert(!msg.startsWith("Tap script failed (exit code -9)"), "today's bare wording is gone: " + msg)
    }

    test("exit -9 keeps the script's stderr tail so a real traceback is not hidden behind the memory hint") {
        val msg = TapScriptRunner.scriptFailureMessage(-9, "[wrapper] calling fetch()\nlast line before the kill")
        assert(msg.contains("last line before the kill"), msg)
    }

    test("other non-zero exits keep today's wording verbatim") {
        assert(TapScriptRunner.scriptFailureMessage(1, "Traceback: boom") == "Tap script failed (exit code 1): Traceback: boom")
        assert(TapScriptRunner.scriptFailureMessage(2, "") == "Tap script failed (exit code 2): ")
    }

    test("exit 137 (the in-process ProcessBuilder lane's SIGKILL) still carries 137 in the message") {
        // TapStagingSpec's "script killed mid-write" case matches on "137";
        // whether or not the implementer adds the memory hint for 137 too, the
        // code must stay visible.
        val msg = TapScriptRunner.scriptFailureMessage(137, "")
        assert(msg.contains("137"), msg)
    }
}
