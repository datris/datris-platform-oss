package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

/** Story: MCP and REST tap tests are capped at 20 records like the UI's Test
  *  button (plans/stories/tap-test-limit.md).
  *
  *  `POST /api/v1/tap/run` with `mode: "test"` has always called
  *  `TapRunner.run(..., testLimit = 0)` — unlimited — so an Assistant / MCP /
  *  CLI test of a generator tap streams the whole source and dies on the tap
  *  script timeout. The UI's `POST /api/v1/tap/test?testLimit=20` path has
  *  capped at 20 all along. This spec pins the pure resolver that closes the
  *  gap; reaching `runTap` itself needs Vault, an API key and a servlet
  *  request, so the decision is lifted onto the companion:
  *
  *  {{{
  *  object TapAPIController {
  *      // `bodyTestLimit` is `body.get("testLimit")` straight off the
  *      // Jackson-parsed @RequestBody map: null when absent, Integer for 20,
  *      // Double for 20.0, String for "20".
  *      private[api] def resolveTestLimit(mode: String, bodyTestLimit: Any): Int
  *  }
  *  }}}
  *
  *  Rules pinned: only `mode=test` caps at all (a `testLimit` sent with
  *  `mode=run` is ignored, not an error — production runs read every row);
  *  absent/null means the default preview size (`testRecordSampleSize` = 20);
  *  an explicit 0 or a negative value is the deliberate unlimited opt-out; a
  *  non-numeric value is a `DatrisException`.
  */
class TapRunTestLimitSpec extends AnyFunSuite {

    test("mode=test with no body testLimit resolves to 20") {
        assert(TapAPIController.resolveTestLimit("test", null) == 20)
    }

    test("mode=test with testLimit 5 resolves to 5") {
        assert(TapAPIController.resolveTestLimit("test", Integer.valueOf(5)) == 5)
    }

    test("mode=test with testLimit 0 resolves to 0 (unlimited)") {
        assert(TapAPIController.resolveTestLimit("test", Integer.valueOf(0)) == 0)
    }

    test("mode=test with testLimit -3 resolves to 0") {
        assert(TapAPIController.resolveTestLimit("test", Integer.valueOf(-3)) == 0)
    }

    test("mode=run always resolves to 0 even with testLimit 5") {
        assert(TapAPIController.resolveTestLimit("run", Integer.valueOf(5)) == 0)
        assert(TapAPIController.resolveTestLimit("run", null) == 0)
    }

    test("an Integer 5, a Double 5.0 and a String \"5\" all resolve to 5") {
        assert(TapAPIController.resolveTestLimit("test", Integer.valueOf(5)) == 5)
        assert(TapAPIController.resolveTestLimit("test", java.lang.Double.valueOf(5.0d)) == 5)
        assert(TapAPIController.resolveTestLimit("test", "5") == 5)
    }

    // Review follow-up: "NaN" and "Infinity" parse as doubles, so without an
    // explicit guard they would silently mean "unlimited" (NaN.toInt == 0) and
    // Int.MaxValue respectively instead of being rejected as non-numeric.
    test("NaN and Infinity testLimits are DatrisExceptions, not a silent unlimited") {
        for (bad <- Seq("NaN", "Infinity", "-Infinity")) {
            val e = intercept[DatrisException] {
                TapAPIController.resolveTestLimit("test", bad)
            }
            assert(e.getMessage != null && e.getMessage.toLowerCase.contains("testlimit"), bad)
        }
    }

    test("a non-numeric testLimit is a DatrisException") {
        val e = intercept[DatrisException] {
            TapAPIController.resolveTestLimit("test", "lots")
        }
        assert(e.getMessage != null && e.getMessage.toLowerCase.contains("testlimit"))
    }
}
