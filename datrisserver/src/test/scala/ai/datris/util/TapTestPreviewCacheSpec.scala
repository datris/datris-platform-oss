package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TapConfig, TenantContext}
import com.google.gson.{JsonArray, JsonObject}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Schema proposal reuses the tap's test rows and stops cleanly on
  * failure (plans/stories/schema-proposal-reuse-test-rows.md), Acceptance 1.
  *
  * The server keeps the last successful `mode=test` preview per tenant + tap in
  * memory so `propose_pipeline_schema` can answer from it instead of running the
  * tap a second time. The hold is plain in-memory state with an injectable
  * clock (same seam as [[ConfirmationRegistry]] / `UnityCatalogCache`); the
  * production singleton is the companion:
  *
  * {{{
  * class TapTestPreviewCache(clock: () => Long = () => System.currentTimeMillis()) {
  *     def put(tap: TapConfig, records: JsonArray, columns: java.util.List[String],
  *             recordCount: Int, recordsTruncated: Boolean, testLimit: Int): Unit
  *     def get(tap: TapConfig, requestedTestLimit: Int): Option[TapTestPreviewCache.Held]
  *     def drop(tapName: String): Unit
  * }
  * object TapTestPreviewCache extends TapTestPreviewCache(() => System.currentTimeMillis()) {
  *     val HoldMs: Long      // 30 minutes
  *     val MaxEntries: Int   // 200, oldest evicted
  *     case class Held(records: JsonArray, columns: java.util.List[String], recordCount: Int,
  *                     recordsTruncated: Boolean, scriptId: String, testLimit: Int, capturedAtMs: Long, ...)
  * }
  * }}}
  *
  * Key: `DatrisEnvironment.current.environment + "|" + tapName`. A hit needs the
  * same `TapScriptIdentity.of(tap)` as at put time, age <= HoldMs, and
  * `testPreviewSize(requested) <= testPreviewSize(held.testLimit)`.
  *
  * HTTP taps are used throughout: their identity is `"http:" + endpointUrl`, so
  * no object store is read and editing the endpoint changes the identity.
  */
class TapTestPreviewCacheSpec extends AnyFunSuite {

    private object Env extends AiSampleValuesMarkers

    private def envFor(tenant: String): DatrisEnvironment = Env.testEnv.copy(environment = tenant)

    private def asTenant[T](tenant: String)(body: => T): T = {
        TenantContext.set(envFor(tenant))
        try body
        finally TenantContext.clear()
    }

    private class FakeClock(var now: Long = 1000000000000L) {
        def apply(): Long = now
        def advanceMs(ms: Long): Unit = now += ms
    }

    private def fresh(): (TapTestPreviewCache, FakeClock) = {
        val clock = new FakeClock()
        (new TapTestPreviewCache(clock = () => clock()), clock)
    }

    private def httpTap(name: String, url: String = "https://feed.example.org/v1/items"): TapConfig =
        TapConfig(name = name, description = "d", targetPipeline = null, scriptKind = "http", endpointUrl = url)

    private def rows(n: Int): JsonArray = {
        val arr = new JsonArray()
        (0 until n).foreach { i =>
            val o = new JsonObject()
            o.addProperty("id", i.toString)
            o.addProperty("label", "v" + i)
            arr.add(o)
        }
        arr
    }

    private val Columns: java.util.List[String] = java.util.Arrays.asList("id", "label")

    private def putRows(cache: TapTestPreviewCache, tap: TapConfig, n: Int = 8, testLimit: Int = 20): Unit =
        cache.put(tap, records = rows(n), columns = Columns, recordCount = n, recordsTruncated = false, testLimit = testLimit)

    test("put then get returns the held rows") {
        val (cache, _) = fresh()
        asTenant("tenant_a") {
            val tap = httpTap("src")
            putRows(cache, tap, n = 8)
            val hit = cache.get(tap, 20)
            assert(hit.isDefined, "a fresh put must be a hit for the same tap and limit")
            val held = hit.get
            assert(held.records == rows(8), held.records.toString)
            assert(held.recordCount == 8)
            assert(held.columns == Columns)
            assert(held.testLimit == 20)
            assert(held.scriptId == "http:https://feed.example.org/v1/items")
        }
    }

    test("a different tenant environment misses") {
        val (cache, _) = fresh()
        val tap = httpTap("src")
        asTenant("tenant_a")(putRows(cache, tap))
        asTenant("tenant_b") {
            assert(cache.get(tap, 20).isEmpty, "another tenant must not see tenant_a's held rows")
        }
        asTenant("tenant_a") {
            assert(cache.get(tap, 20).isDefined, "tenant_a still holds its own rows")
        }
    }

    test("a changed script identity misses") {
        val (cache, _) = fresh()
        asTenant("tenant_a") {
            putRows(cache, httpTap("src", "https://feed.example.org/v1/items"))
            val edited = httpTap("src", "https://feed.example.org/v2/items")
            assert(cache.get(edited, 20).isEmpty, "an edited endpoint must invalidate the held preview")
        }
    }

    test("an entry older than the hold window misses") {
        val (cache, clock) = fresh()
        asTenant("tenant_a") {
            val tap = httpTap("src")
            putRows(cache, tap)
            assert(TapTestPreviewCache.HoldMs == 30L * 60L * 1000L, "hold window is 30 minutes")
            clock.advanceMs(TapTestPreviewCache.HoldMs)
            assert(cache.get(tap, 20).isDefined, "an entry exactly at the hold window is still valid")
            clock.advanceMs(1L)
            assert(cache.get(tap, 20).isEmpty, "an entry past the hold window must miss")
        }
    }

    test("a request for a larger preview than was held misses; an equal or smaller one hits") {
        val (cache, _) = fresh()
        asTenant("tenant_a") {
            val tap = httpTap("src")
            putRows(cache, tap, n = 20, testLimit = 20)
            assert(cache.get(tap, 100).isEmpty, "asking for 100 rows when 20 were held must miss")
            assert(cache.get(tap, 20).isDefined, "an equal preview size hits")
            assert(cache.get(tap, 5).isDefined, "a smaller preview size hits")
            // testLimit 0 previews the default 20, so it is not larger than a held 20.
            assert(cache.get(tap, 0).isDefined, "an unlimited test previews 20, which a held 20 covers")
        }
    }

    test("an empty array is not held") {
        val (cache, _) = fresh()
        asTenant("tenant_a") {
            val tap = httpTap("src")
            cache.put(tap, records = new JsonArray(), columns = Columns, recordCount = 0, recordsTruncated = false, testLimit = 20)
            assert(cache.get(tap, 20).isEmpty, "an empty test preview must not be held")
        }
    }

    test("drop removes the entry") {
        val (cache, _) = fresh()
        asTenant("tenant_a") {
            val tap = httpTap("src")
            putRows(cache, tap)
            assert(cache.get(tap, 20).isDefined)
            cache.drop("src")
            assert(cache.get(tap, 20).isEmpty, "drop must remove the held preview")
        }
    }

    test("the 201st entry evicts the oldest") {
        val (cache, clock) = fresh()
        asTenant("tenant_a") {
            assert(TapTestPreviewCache.MaxEntries == 200)
            (1 to 201).foreach { i =>
                putRows(cache, httpTap("tap_" + i), n = 2)
                clock.advanceMs(1L)
            }
            assert(cache.get(httpTap("tap_1"), 20).isEmpty, "the oldest entry must be evicted by the 201st")
            assert(cache.get(httpTap("tap_2"), 20).isDefined, "the second-oldest entry survives")
            assert(cache.get(httpTap("tap_201"), 20).isDefined, "the newest entry is held")
        }
    }

    // Reviewer follow-ups (2026-11-12): the 4 MiB cap and the identity captured before the run.

    test("a preview whose JSON is over 4 MiB is not held") {
        val (cache, _) = fresh()
        asTenant("tenant_a") {
            val tap = httpTap("src")
            val big = new JsonArray()
            val blob = "x" * (64 * 1024)
            (0 until 70).foreach { i =>
                val o = new JsonObject()
                o.addProperty("id", i.toString)
                o.addProperty("label", blob)
                big.add(o)
            }
            assert(big.toString.length > TapTestPreviewCache.MaxPreviewBytes)
            cache.put(tap, records = big, columns = Columns, recordCount = 70, recordsTruncated = false, testLimit = 100)
            assert(cache.get(tap, 20).isEmpty, "an over-4-MiB preview must not be held")
        }
    }

    test("an identity captured before the run is what the entry is held under") {
        val (cache, _) = fresh()
        asTenant("tenant_a") {
            // The test ran v1; the stored tap was replaced with v2 while it ran.
            val v2 = httpTap("src", "https://feed.example.org/v2/items")
            cache.put(
                v2,
                records = rows(4),
                columns = Columns,
                recordCount = 4,
                recordsTruncated = false,
                testLimit = 20,
                scriptId = Some("http:https://feed.example.org/v1/items")
            )
            assert(cache.get(v2, 20).isEmpty, "v1's rows must not be served for v2")
            assert(cache.get(httpTap("src", "https://feed.example.org/v1/items"), 20).isDefined)
        }
    }
}
