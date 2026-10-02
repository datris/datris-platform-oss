package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, PipelineConfig, TapConfig}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Catalog rename and delete: server endpoints and MCP tools
  * (plans/stories/catalog-ops-server-mcp.md), Step 1.
  *
  * API this spec pins (pure, no Mongo):
  *   - `CatalogOps.Placeholder.prefix == "__catalog__"`
  *   - `CatalogOps.isReserved(name: String): Boolean`
  *   - `CatalogOps.LabelRule` — the pattern `^[A-Za-z0-9_-]+$` (mixed case allowed)
  *   - `CatalogOps.members(taps: Seq[TapConfig], pipelines: Seq[PipelineConfig], catalog: String)`
  *     returns a value with `.taps: Seq[TapConfig]` and `.pipelines: Seq[PipelineConfig]`
  *   - `CatalogOps.catalogExists(taps, pipelines, name): Boolean` (rename target check)
  *   - `CatalogOps.affectedKeys(metadata: Map[String, String], old: String): Seq[String]`
  *     (metadata = label -> metadata JSON, as in `{env}/api-key-metadata`)
  *   - `CatalogOps.Result(ok: Seq[String], failed: Seq[(String, String)])`
  *   - `CatalogOps.execute(names: Seq[String])(write: String => Unit): Result`
  */
class CatalogOpsSpec extends AnyFunSuite {

    private def tap(name: String, catalog: String = null): TapConfig =
        TapConfig(name = name, description = null, targetPipeline = null, catalog = catalog)

    private def pipeline(name: String, catalog: String = null): PipelineConfig =
        PipelineConfig(name = name, catalog = catalog)

    private def label(name: String): Boolean =
        CatalogOps.LabelRule.toString.r.pattern.matcher(name).matches()

    // ------------------------------------------------------ Acceptance bullet 1

    test("placeholder prefix is __catalog__") {
        assert(CatalogOps.Placeholder.prefix == "__catalog__")
    }

    test("isReserved is true for Uncataloged (any case) and blank names") {
        assert(CatalogOps.isReserved("Uncataloged"))
        assert(CatalogOps.isReserved("uncataloged"))
        assert(CatalogOps.isReserved("UNCATALOGED"))
        assert(CatalogOps.isReserved(""))
        assert(CatalogOps.isReserved("  "))
        assert(CatalogOps.isReserved(null))
    }

    test("isReserved is false for an ordinary catalog name") {
        assert(!CatalogOps.isReserved("sales"))
        assert(!CatalogOps.isReserved("uncataloged_archive"))
    }

    test("label rule rejects 'Sales Data' and '' and accepts 'Sales' and 'sales_data-2'") {
        assert(CatalogOps.LabelRule.toString == "^[A-Za-z0-9_-]+$")
        assert(!label("Sales Data"))
        assert(!label(""))
        assert(!label("sales.data"))
        assert(label("Sales"))
        assert(label("Sales_Q3"))
        assert(label("sales_data-2"))
    }

    // ------------------------------------------------------ Acceptance bullet 2

    test("members selects taps and pipelines in the catalog and excludes the __catalog__ placeholder") {
        val taps = Seq(tap("__catalog__sales", "sales"), tap("orders-tap", "sales"), tap("other-tap", "hr"), tap("loose-tap"))
        val pipes = Seq(pipeline("orders", "sales"), pipeline("staff", "hr"), pipeline("loose"))
        val m = CatalogOps.members(taps, pipes, "sales")
        assert(m.taps.map(_.name) == Seq("orders-tap"))
        assert(m.pipelines.map(_.name) == Seq("orders"))
    }

    test("catalogExists is true when a member tap or pipeline carries the name") {
        assert(CatalogOps.catalogExists(Seq(tap("t1", "sales")), Nil, "sales"))
        assert(CatalogOps.catalogExists(Nil, Seq(pipeline("p1", "sales")), "sales"))
    }

    test("catalogExists is true for a placeholder-only catalog") {
        assert(CatalogOps.catalogExists(Seq(tap("__catalog__empty", "empty")), Nil, "empty"))
        assert(CatalogOps.catalogExists(Seq(tap("__catalog__bare")), Nil, "bare"))
    }

    test("catalogExists is false when nothing carries the name") {
        val taps = Seq(tap("__catalog__hr", "hr"), tap("t1", "hr"), tap("loose"))
        assert(!CatalogOps.catalogExists(taps, Seq(pipeline("p1", "hr"), pipeline("p2")), "sales"))
        assert(!CatalogOps.catalogExists(Nil, Nil, "sales"))
    }

    test("catalogExists is case-sensitive") {
        val taps = Seq(tap("__catalog__DatrisFund", "DatrisFund"), tap("t1", "Sales"))
        assert(!CatalogOps.catalogExists(taps, Seq(pipeline("p1", "Sales")), "datrisfund"))
        assert(!CatalogOps.catalogExists(taps, Nil, "sales"))
        assert(CatalogOps.catalogExists(taps, Nil, "DatrisFund"))
    }

    // ------------------------------------------------------ Acceptance bullet 3

    test("affectedKeys returns only non-revoked labels whose capability scope has catalog=<old>") {
        val metadata = Map(
            "reader-old" -> """{"capabilities":["pipeline:read:catalog=old"],"revoked":false}""",
            "creator-old" -> """{"capabilities":["job:read","pipeline:create:catalog=old,owner=self"]}""",
            "tap-runner-old" -> """{"capabilities":["tap:run:catalog=old"],"revoked":false}""",
            "revoked-old" -> """{"capabilities":["pipeline:read:catalog=old"],"revoked":true}""",
            "other" -> """{"capabilities":["pipeline:read:catalog=other"],"revoked":false}""",
            "prefix-only" -> """{"capabilities":["pipeline:read:catalog=older"],"revoked":false}""",
            "unscoped" -> """{"capabilities":["pipeline:read","tap:read"],"revoked":false}""",
            "no-caps" -> """{"createdBy":"admin"}""",
            "malformed" -> """not json"""
        )
        val affected = CatalogOps.affectedKeys(metadata, "old")
        assert(affected.toSet == Set("reader-old", "creator-old", "tap-runner-old"))
        assert(affected.distinct.size == affected.size)
    }

    test("affectedKeys is empty when no key is scoped to the old catalog") {
        assert(CatalogOps.affectedKeys(Map.empty, "old").isEmpty)
        assert(CatalogOps.affectedKeys(Map("k" -> """{"capabilities":["*:*"]}"""), "old").isEmpty)
    }

    // ------------------------------------------------------ Acceptance bullet 4

    test("a write that throws for one member lands in failed; the rest still complete") {
        val written = scala.collection.mutable.ArrayBuffer.empty[String]
        val result = CatalogOps.execute(Seq("a", "b", "c")) { name =>
            if (name == "b") throw new RuntimeException("boom on b")
            written += name
        }
        assert(written == Seq("a", "c"), "members after the failing one must still be written")
        assert(result == CatalogOps.Result(ok = Seq("a", "c"), failed = Seq(("b", "boom on b"))))
    }

    test("all writes succeeding yields an empty failed list") {
        val result = CatalogOps.execute(Seq("x", "y")) { _ => () }
        assert(result.ok == Seq("x", "y"))
        assert(result.failed.isEmpty)
    }

    test("every write failing reports each member, none ok") {
        val result = CatalogOps.execute(Seq("x", "y")) { n => throw new IllegalStateException("no " + n) }
        assert(result.ok.isEmpty)
        assert(result.failed == Seq(("x", "no x"), ("y", "no y")))
    }

    // ------------------------------------------------------ Review fixes (scope + placeholder + cascade)

    test("scopeContext carries catalog and owner so catalog-scoped and owner=self keys both match") {
        val ctx = CatalogOps.scopeContext("sales", "builder")
        assert(ctx == Map("catalog" -> "sales", "owner" -> "builder"))
        assert(Capability.parse("pipeline:update:catalog=sales").grants("pipeline", "update", ctx, "someone"))
        assert(!Capability.parse("pipeline:update:catalog=other").grants("pipeline", "update", ctx, "someone"))
        assert(Capability.parse("tap:update:owner=self").grants("tap", "update", ctx, "builder"))
        assert(!Capability.parse("tap:update:owner=self").grants("tap", "update", ctx, "intruder"))
    }

    test("scopeContext omits blank values") {
        assert(CatalogOps.scopeContext(null, null).isEmpty)
        assert(CatalogOps.scopeContext("", "").isEmpty)
        assert(CatalogOps.scopeContext("sales", null) == Map("catalog" -> "sales"))
    }

    test("placeholderAction: placeholder-only catalogs check the placeholder; any scope denial skips placeholders") {
        import CatalogOps.PlaceholderAction._
        assert(CatalogOps.placeholderAction(membersEmpty = true, denied = Nil) == CheckPlaceholderScope)
        assert(CatalogOps.placeholderAction(membersEmpty = false, denied = Seq("p1")) == Skip)
        assert(CatalogOps.placeholderAction(membersEmpty = false, denied = Nil) == Proceed)
    }

    test("skipped reports every name as failed with the reason and none ok") {
        val r = CatalogOps.skipped(Seq("p1", "p2"), "skipped: tap deletions in this catalog failed")
        assert(r.ok.isEmpty)
        assert(r.failed == Seq(("p1", "skipped: tap deletions in this catalog failed"), ("p2", "skipped: tap deletions in this catalog failed")))
    }

    test("capabilityDeniedBody matches the interceptor's denial shape and escapes quotes") {
        val body = CatalogOps.capabilityDeniedBody("capability denied: key 'k' says \"no\"")
        val obj = com.google.gson.JsonParser.parseString(body).getAsJsonObject
        assert(obj.get("error").getAsString == "capability denied")
        assert(obj.get("errorKind").getAsString == "capability_denied")
        assert(obj.get("message").getAsString == "capability denied: key 'k' says \"no\"")
    }

    // ------------------------------------------------------ E2E fixes (placeholder matching, concurrent moves)

    test("placeholder belongs to the catalog in its catalog field") {
        assert(CatalogOps.Placeholder.belongsTo(tap("__catalog__sales", "sales"), "sales"))
        assert(!CatalogOps.Placeholder.belongsTo(tap("sales-tap", "sales"), "sales"), "not a placeholder")
    }

    test("placeholder falls back to its name suffix only when the catalog field is empty") {
        assert(CatalogOps.Placeholder.belongsTo(tap("__catalog__sales"), "sales"))
        assert(CatalogOps.Placeholder.belongsTo(tap("__catalog__sales", ""), "sales"))
        assert(!CatalogOps.Placeholder.belongsTo(tap("__catalog__sales"), "hr"))
    }

    test("a legacy placeholder whose name differs from its catalog field belongs to the field's catalog only") {
        val legacy = tap("__catalog__legacy_name", "fund")
        assert(CatalogOps.Placeholder.belongsTo(legacy, "fund"))
        assert(!CatalogOps.Placeholder.belongsTo(legacy, "legacy_name"))
        assert(CatalogOps.Placeholder.of(Seq(legacy), "legacy_name").isEmpty)
    }

    test("Placeholder.of returns every placeholder of a catalog, and none are members") {
        val taps = Seq(
            tap("__catalog__g", "g"),
            tap("__catalog__h", "g"),
            tap("__catalog__other", "other"),
            tap("__catalog__bare"),
            tap("t1", "g")
        )
        assert(CatalogOps.Placeholder.of(taps, "g").map(_.name).toSet == Set("__catalog__g", "__catalog__h"))
        assert(CatalogOps.Placeholder.of(taps, "bare").map(_.name) == Seq("__catalog__bare"))
        assert(CatalogOps.members(taps, Nil, "g").taps.map(_.name) == Seq("t1"))
    }

    test("freshName uses __catalog__<name> unless a tap already has that name") {
        assert(CatalogOps.Placeholder.freshName("x", Set("t1")) == "__catalog__x")
        assert(CatalogOps.Placeholder.freshName("x", Set("__catalog__x")) == "__catalog__x-2")
        assert(CatalogOps.Placeholder.freshName("x", Set("__catalog__x", "__catalog__x-2")) == "__catalog__x-3")
    }

    test("requireInCatalog refuses a member moved away concurrently") {
        CatalogOps.requireInCatalog("t", "old", "old")
        val moved = intercept[Exception](CatalogOps.requireInCatalog("t", "x2", "old"))
        assert(moved.getMessage.contains("no longer in catalog old"))
        assert(moved.getMessage.contains("x2"))
        val cleared = intercept[Exception](CatalogOps.requireInCatalog("t", null, "old"))
        assert(cleared.getMessage.contains("Uncataloged"))
    }

    test("a concurrent move surfaces in failed, not ok") {
        val live = Map("a" -> "old", "b" -> "x2")
        val r = CatalogOps.execute(Seq("a", "b"))(n => CatalogOps.requireInCatalog(n, live(n), "old"))
        assert(r.ok == Seq("a"))
        assert(r.failed.map(_._1) == Seq("b"))
    }

    test("placeholderAction skips placeholders when members existed but none moved") {
        import CatalogOps.PlaceholderAction._
        assert(CatalogOps.placeholderAction(membersEmpty = false, denied = Nil, anyOk = false) == Skip)
        assert(CatalogOps.placeholderAction(membersEmpty = false, denied = Nil, anyOk = true) == Proceed)
        assert(CatalogOps.placeholderAction(membersEmpty = true, denied = Nil, anyOk = false) == CheckPlaceholderScope)
    }

    // ------------------------------------------------------ E2E fix: serialized catalog operations

    test("withCatalogLock never lets two threads inside at once and returns the body's value") {
        val inside = new java.util.concurrent.atomic.AtomicInteger(0)
        val maxInside = new java.util.concurrent.atomic.AtomicInteger(0)
        val start = new java.util.concurrent.CountDownLatch(1)
        val threads = (1 to 8).map { _ =>
            new Thread(() => {
                start.await()
                (1 to 20).foreach { _ =>
                    CatalogOps.withCatalogLock {
                        val now = inside.incrementAndGet()
                        maxInside.accumulateAndGet(now, (a: Int, b: Int) => math.max(a, b))
                        Thread.sleep(1)
                        inside.decrementAndGet()
                    }
                }
            })
        }
        threads.foreach(_.start())
        start.countDown()
        threads.foreach(_.join(30000))
        assert(threads.forall(!_.isAlive))
        assert(maxInside.get == 1)
        assert(CatalogOps.withCatalogLock(42) == 42)
    }

    test("withCatalogLock releases the lock when the body throws") {
        intercept[RuntimeException](CatalogOps.withCatalogLock[Unit](throw new RuntimeException("boom")))
        val t = new Thread(() => CatalogOps.withCatalogLock(()))
        t.start()
        t.join(5000)
        assert(!t.isAlive, "lock was not released after an exception")
    }

    // ------------------------------------------------------ Story: Catalog cascade delete surfaces Unity Catalog warnings
    // (plans/stories/uc-catalog-cascade-delete-warnings.md)
    //   - `CatalogOps.Result` gains `warnings: Seq[(String, String)] = Nil` (name -> message)
    //   - `CatalogOps.executeCollecting(names: Seq[String])(write: String => Seq[String]): Result`
    //   - `execute` delegates to it, so it never yields warnings

    test("executeCollecting keeps every returned string as a (name, message) warning and continues past failures") {
        val written = scala.collection.mutable.ArrayBuffer.empty[String]
        val result = CatalogOps.executeCollecting(Seq("a", "b", "c", "d")) { name =>
            written += name
            name match {
                case "a" => Seq("a needs an admin", "a second note")
                case "b" => throw new RuntimeException("boom on b")
                case "c" => Nil
                case _ => Seq("d needs an admin")
            }
        }
        assert(written == Seq("a", "b", "c", "d"), "members after the failing one must still be written")
        assert(result.ok == Seq("a", "c", "d"))
        assert(result.failed == Seq(("b", "boom on b")))
        assert(result.warnings == Seq(("a", "a needs an admin"), ("a", "a second note"), ("d", "d needs an admin")))
    }

    test("executeCollecting with no returned strings yields no warnings") {
        val result = CatalogOps.executeCollecting(Seq("x", "y"))(_ => Nil)
        assert(result.ok == Seq("x", "y"))
        assert(result.failed.isEmpty)
        assert(result.warnings.isEmpty)
    }

    test("execute yields no warnings and the same ok/failed as before") {
        val result = CatalogOps.execute(Seq("a", "b", "c")) { name =>
            if (name == "b") throw new RuntimeException("boom on b")
        }
        assert(result.ok == Seq("a", "c"))
        assert(result.failed == Seq(("b", "boom on b")))
        assert(result.warnings.isEmpty)
        assert(result == CatalogOps.Result(ok = Seq("a", "c"), failed = Seq(("b", "boom on b")), warnings = Nil))
    }

    test("skipped yields no warnings") {
        val r = CatalogOps.skipped(Seq("p1", "p2"), "skipped: tap deletions in this catalog failed")
        assert(r.ok.isEmpty)
        assert(r.failed.map(_._1) == Seq("p1", "p2"))
        assert(r.warnings.isEmpty)
    }

    test("a cascade pipeline committed through the REST catalog becomes a (pipeline, advice) warning") {
        import ai.datris.model.{Destination, ObjectStore, UnityCatalogSync, UnityCatalogSyncState}
        val restPipe = PipelineConfig(
            name = "orders_daily",
            catalog = "cascade_demo",
            destination = Destination(objectStore =
                ObjectStore(prefixKey = "orders_daily", fileFormat = "iceberg", destinationBucketOverride = "lake")
            ),
            unityCatalog = UnityCatalogSync(enabled = true, catalog = "main", schema = "sales", catalogMode = "rest")
        )
        val restState = UnityCatalogSyncState(
            "orders_daily",
            null,
            null,
            null,
            null,
            null,
            null,
            catalogMode = "rest",
            restMetadataLocation = "s3://lake/orders_daily/metadata/00003-ab.metadata.json",
            lastRestCommitAt = "2026-09-29T10:00:03Z"
        )
        val plainPipe = pipeline("plain_pg", "cascade_demo")
        val states = Map[String, UnityCatalogSyncState]("orders_daily" -> restState)
        val byName = Map("orders_daily" -> restPipe, "plain_pg" -> plainPipe)
        // Stand-in for deletePipelineInternal: returns forPipeline's advice as its Seq[String].
        val result = CatalogOps.executeCollecting(Seq("plain_pg", "orders_daily")) { n =>
            UnityCatalogDeleteAdvice.forPipeline(byName(n), states.getOrElse(n, null)).toSeq
        }
        assert(result.ok == Seq("plain_pg", "orders_daily"))
        assert(result.failed.isEmpty)
        assert(result.warnings == Seq(("orders_daily", "Unity Catalog still holds main.sales.orders_daily; have an admin drop it")))
    }

    test("a cascade whose pipelines have no Unity Catalog state yields warnings empty") {
        val result = CatalogOps.executeCollecting(Seq("p1", "p2")) { n =>
            UnityCatalogDeleteAdvice.forPipeline(pipeline(n, "cascade_demo"), null).toSeq
        }
        assert(result.ok == Seq("p1", "p2"))
        assert(result.warnings.isEmpty)
    }
}
