package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TapConfig, TenantContext}
import ai.datris.util.{AiSampleValuesMarkers, TapScriptResult, TapTestPreviewCache}
import com.google.gson.{JsonArray, JsonObject, JsonPrimitive}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Schema proposal reuses the tap's test rows
  * (plans/stories/schema-proposal-reuse-test-rows.md), Steps 2-3, reviewer
  * follow-up: the `/tap/run` reuse branch and the post-test hold/drop wiring,
  * lifted onto the companion as pure functions. */
class TapRunReuseResponseSpec extends AnyFunSuite {

    private object Env extends AiSampleValuesMarkers

    private def asTenant[T](body: => T): T = {
        TenantContext.set(Env.testEnv.copy(environment = "tenant_a"))
        try body
        finally TenantContext.clear()
    }

    private val Url = "https://feed.example.org/v1/items"
    private val Id = Some("http:" + Url)
    private val tap: TapConfig =
        TapConfig(name = "src", description = "d", targetPipeline = "p1", scriptKind = "http", endpointUrl = Url)

    private def rows(n: Int): JsonArray = {
        val arr = new JsonArray()
        (0 until n).foreach { i =>
            val o = new JsonObject()
            o.addProperty("id", i.toString)
            arr.add(o)
        }
        arr
    }

    private val Columns = java.util.Arrays.asList("id")

    private def held(n: Int, truncated: Boolean = false, testLimit: Int = 100): TapTestPreviewCache.Held =
        TapTestPreviewCache.Held(
            rows(n),
            Columns,
            recordCount = n,
            recordsTruncated = truncated,
            scriptId = Id.get,
            testLimit = testLimit,
            capturedAtMs = 1762942500000L,
            dataType = "json"
        )

    // ---------------------------------------------------- reusedTestResponse

    test("uncut: every held row, the usual test fields, reused and testedAt, no logs or tokens") {
        val r = TapAPIController.reusedTestResponse(tap, held(8), 20)
        assert(r.get("records") == rows(8))
        assert(!r.containsKey("recordsTruncated"))
        assert(r.get("status") == "success" && r.get("mode") == "test")
        assert(r.get("persisted") == java.lang.Boolean.FALSE && r.get("persistedReason") == "test_mode")
        assert(r.get("recordCount") == Integer.valueOf(8))
        assert(r.get("columns") == Columns && r.get("dataType") == "json")
        assert(r.get("tap") == "src" && r.get("targetPipeline") == "p1")
        assert(r.get("reused") == java.lang.Boolean.TRUE)
        assert(r.get("testedAt") == java.time.Instant.ofEpochMilli(1762942500000L).toString)
        Seq("logs", "error", "publisherToken", "pipelineTokens").foreach(k => assert(!r.containsKey(k), k))
    }

    test("cut: records cut to the requested preview size and marked truncated") {
        val r = TapAPIController.reusedTestResponse(tap, held(100), 20)
        assert(r.get("records").asInstanceOf[JsonArray] == { val a = rows(20); a })
        assert(r.get("recordsTruncated") == java.lang.Boolean.TRUE)
        assert(r.get("recordCount") == Integer.valueOf(100))
    }

    test("held truncated: marked truncated even when not cut") {
        val r = TapAPIController.reusedTestResponse(tap, held(20, truncated = true, testLimit = 20), 20)
        assert(r.get("records").asInstanceOf[JsonArray].size() == 20)
        assert(r.get("recordsTruncated") == java.lang.Boolean.TRUE)
    }

    test("the response does not share the held JsonArray") {
        val h = held(3)
        TapAPIController.reusedTestResponse(tap, h, 20).get("records").asInstanceOf[JsonArray].add(new JsonPrimitive(1))
        assert(h.records.size() == 3)
    }

    // ------------------------------------------------------- holdTestPreview

    private def ok(n: Int) = TapScriptResult(staged = null, recordCount = n, error = null, columns = Columns)

    private def seed(cache: TapTestPreviewCache): Unit =
        cache.put(tap, rows(5), Columns, 5, recordsTruncated = false, testLimit = 20, scriptId = Id)

    test("a passing no-params test with rows is held under the identity captured before the run") {
        val cache = new TapTestPreviewCache()
        asTenant {
            TapAPIController.holdTestPreview(cache, tap, ok(8), rows(8), previewTruncated = false, 20, paramsSent = false, scriptId = Id)
            val h = cache.get(tap, 20)
            assert(h.isDefined && h.get.recordCount == 8 && h.get.scriptId == Id.get)
        }
    }

    test("a failed test drops the held entry, with or without params") {
        Seq(false, true).foreach { withParams =>
            val cache = new TapTestPreviewCache()
            asTenant {
                seed(cache)
                val failed = TapScriptResult(staged = null, recordCount = 0, error = "boom")
                TapAPIController.holdTestPreview(cache, tap, failed, null, previewTruncated = false, 20, paramsSent = withParams, scriptId = Id)
                assert(cache.get(tap, 20).isEmpty, s"params=$withParams")
            }
        }
    }

    test("a passing test with params drops, never holds") {
        val cache = new TapTestPreviewCache()
        asTenant {
            seed(cache)
            TapAPIController.holdTestPreview(cache, tap, ok(8), rows(8), previewTruncated = false, 20, paramsSent = true, scriptId = Id)
            assert(cache.get(tap, 20).isEmpty)
        }
    }

    test("a passing test with an empty or non-array preview drops") {
        Seq(new JsonArray(), new JsonPrimitive("text"), null).foreach { preview =>
            val cache = new TapTestPreviewCache()
            asTenant {
                seed(cache)
                TapAPIController.holdTestPreview(cache, tap, ok(0), preview, previewTruncated = false, 20, paramsSent = false, scriptId = Id)
                assert(cache.get(tap, 20).isEmpty, String.valueOf(preview))
            }
        }
    }

    test("an unreadable script identity holds nothing") {
        val cache = new TapTestPreviewCache()
        asTenant {
            seed(cache)
            TapAPIController.holdTestPreview(cache, tap, ok(8), rows(8), previewTruncated = false, 20, paramsSent = false, scriptId = None)
            assert(cache.get(tap, 20).isEmpty)
        }
    }

    // ------------------------------------------------------- reuseCandidate

    test("reuseCandidate: mode=test with the flag and no params is a candidate") {
        assert(TapAPIController.reuseCandidate("test", java.lang.Boolean.TRUE, Map.empty))
        assert(TapAPIController.reuseCandidate("test", "true", Map.empty))
    }

    test("reuseCandidate: params present is never a candidate") {
        assert(!TapAPIController.reuseCandidate("test", java.lang.Boolean.TRUE, Map("since" -> "2026-05-01")))
    }

    test("reuseCandidate: mode=run is never a candidate") {
        assert(!TapAPIController.reuseCandidate("run", java.lang.Boolean.TRUE, Map.empty))
    }
}
