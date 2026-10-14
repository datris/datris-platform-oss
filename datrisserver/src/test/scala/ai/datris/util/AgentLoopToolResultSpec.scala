package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Tool-result cap applied by AgentLoop before a result is round-tripped to
  * the model (ToolResultTrim). */
class AgentLoopToolResultSpec extends AnyFunSuite with BeforeAndAfterEach {

    override def afterEach(): Unit = {
        ToolResultTrim.maxChars = ToolResultTrim.DefaultMaxChars
        ToolResultTrim.scriptMaxChars = ToolResultTrim.DefaultScriptMaxChars
    }

    private def chars(n: Int): String = "x" * n

    test("defaults are 32000 general and 131072 for code-bearing tools") {
        assert(ToolResultTrim.maxChars == 32000)
        assert(ToolResultTrim.scriptMaxChars == 131072)
    }

    test("a result under the cap passes through unchanged") {
        val s = chars(31999)
        assert(ToolResultTrim.trim("list_taps", s) eq s)
        val exact = chars(32000)
        assert(ToolResultTrim.trim("list_taps", exact) eq exact)
    }

    test("a result over the cap is cut at the cap with the new trailer") {
        val s = chars(40000)
        val out = ToolResultTrim.trim("list_taps", s)
        val trailer = ToolResultTrim.trailer(8000, 40000)
        assert(out == chars(32000) + trailer)
        assert(out.length == 32000 + trailer.length)
        assert(trailer.contains("truncated 8000 of 40000 chars"))
        assert(trailer.contains("the user sees the full result"))
        assert(trailer.contains("Ask the user to paste the remainder, or narrow the request"))
    }

    test("a 50 KB get_tap result passes through under the script ceiling") {
        val s = chars(50 * 1024)
        assert(ToolResultTrim.trim("get_tap", s) eq s)
        assert(ToolResultTrim.trim("get_tap_version", s) eq s)
        assert(ToolResultTrim.trim("get_tap_script", s) eq s)
        assert(ToolResultTrim.trim("diff_tap_versions", s) eq s)
        assert(ToolResultTrim.trim("read_pipeline_script", s) eq s)
        // A non-code tool of the same size is capped.
        assert(ToolResultTrim.trim("get_tap_logs", s).startsWith(chars(32000) + "\n\n…[truncated"))
    }

    test("a 200 KB get_tap result is truncated at the script ceiling") {
        val s = chars(200 * 1024)
        val out = ToolResultTrim.trim("get_tap", s)
        val trailer = ToolResultTrim.trailer(200 * 1024 - 131072, 200 * 1024)
        assert(out == chars(131072) + trailer)
        assert(out.length == 131072 + trailer.length)
        assert(ToolResultTrim.trim("diff_tap_versions", s) == out)
    }

    test("null result becomes empty") {
        assert(ToolResultTrim.trim("get_tap", null) == "")
    }

    test("property overrides are respected") {
        ToolResultTrim.maxChars = 100
        ToolResultTrim.scriptMaxChars = 500
        assert(ToolResultTrim.trim("list_taps", chars(150)) == chars(100) + ToolResultTrim.trailer(50, 150))
        assert(ToolResultTrim.trim("get_tap", chars(450)) == chars(450))
        assert(ToolResultTrim.trim("get_tap", chars(600)) == chars(500) + ToolResultTrim.trailer(100, 600))
    }

    test("the script ceiling never drops below the general cap") {
        ToolResultTrim.maxChars = 1000
        ToolResultTrim.scriptMaxChars = 10
        assert(ToolResultTrim.trim("get_tap", chars(900)) == chars(900))
    }

    test("configured values parse as positive integers only") {
        assert(ToolResultTrim.parseCap("64000").contains(64000))
        assert(ToolResultTrim.parseCap(" 200 ").contains(200))
        assert(ToolResultTrim.parseCap("0").isEmpty)
        assert(ToolResultTrim.parseCap("-5").isEmpty)
        assert(ToolResultTrim.parseCap("lots").isEmpty)
        assert(ToolResultTrim.parseCap(null).isEmpty)
    }
}
