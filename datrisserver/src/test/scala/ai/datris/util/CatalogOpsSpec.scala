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
  *   - `CatalogOps.LabelRule` — the pattern `^[a-z0-9_-]+$`
  *   - `CatalogOps.members(taps: Seq[TapConfig], pipelines: Seq[PipelineConfig], catalog: String)`
  *     returns a value with `.taps: Seq[TapConfig]` and `.pipelines: Seq[PipelineConfig]`
  *   - `CatalogOps.clashes(members, targetTaps: Seq[TapConfig], targetPipelines: Seq[PipelineConfig]): Seq[String]`
  *     (clashing member names)
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

    test("label rule rejects 'Sales Data' and accepts 'sales_data-2'") {
        assert(CatalogOps.LabelRule.toString == "^[a-z0-9_-]+$")
        assert(!label("Sales Data"))
        assert(!label("Sales"))
        assert(!label(""))
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

    test("clashes reports a tap in the source whose name matches a pipeline in the target (cross-type)") {
        val m = CatalogOps.members(Seq(tap("orders", "old")), Seq(pipeline("events", "old")), "old")
        val clashes = CatalogOps.clashes(m, Seq(tap("unrelated", "new")), Seq(pipeline("orders", "new")))
        assert(clashes == Seq("orders"))
    }

    test("clashes reports tap-vs-tap and pipeline-vs-pipeline name matches") {
        val m = CatalogOps.members(Seq(tap("t1", "old")), Seq(pipeline("p1", "old")), "old")
        val clashes = CatalogOps.clashes(m, Seq(tap("t1", "new")), Seq(pipeline("p1", "new")))
        assert(clashes.toSet == Set("t1", "p1"))
    }

    test("clashes is empty when no names overlap, and the target's placeholder never clashes") {
        val m = CatalogOps.members(Seq(tap("__catalog__old", "old"), tap("t1", "old")), Seq(pipeline("p1", "old")), "old")
        val clashes = CatalogOps.clashes(m, Seq(tap("__catalog__new", "new"), tap("t2", "new")), Seq(pipeline("p2", "new")))
        assert(clashes.isEmpty)
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
}
