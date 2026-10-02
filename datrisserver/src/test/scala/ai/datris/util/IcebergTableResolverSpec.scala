package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.UnityCatalogSyncState
import org.scalatest.funsuite.AnyFunSuite

/** Story: Unity Catalog 7: Databricks-managed Iceberg mode
  * (plans/stories/unity-catalog-7-managed-iceberg.md), Acceptance bullet 5
  * (Step 6).
  *
  * Seam this spec pins:
  *
  * {{{
  *   object IcebergTableResolver {
  *     final case class Resolved(metadataLocation: String, managed: Boolean)
  *     def resolve(previous: UnityCatalogSyncState, tableRoot: String,
  *                 catalogCurrent: () => Option[String]): Option[Resolved]
  *   }
  * }}}
  *
  * A managed doc (`catalogMode: "managed"` with a recorded catalog commit) is
  * resolved whatever the table root (the table lives where the catalog put it,
  * not under prefixKey). The catalog's answer is accepted when it is in the
  * pipeline's bucket (the authority of `tableRoot`, s3/s3a/s3n equal), else
  * the recorded `restMetadataLocation` is read. Locations come back readable
  * (`s3a://`). A rest doc behaves as before, with `managed = false`. */
class IcebergTableResolverSpec extends AnyFunSuite {

    private val R = IcebergTableResolver
    private val ROOT = "s3a://datris/uc_managed"
    private val RECORDED = "s3://datris/uc/__unitystorage/schemas/5e1f/tables/9a2b/metadata/00001-c3d4.metadata.json"
    private val NEWER = "s3://datris/uc/__unitystorage/schemas/5e1f/tables/9a2b/metadata/00002-e5f6.metadata.json"

    private def s3a(loc: String): String = loc.replaceFirst("^s3n?://", "s3a://")

    private def doc(mode: String, loc: String, at: String = "2026-10-01T09:00:00Z"): UnityCatalogSyncState =
        UnityCatalogSyncState("uc_managed", null, null, null, null, null, null, catalogMode = mode, restMetadataLocation = loc, lastRestCommitAt = at)

    private val managed = doc("managed", RECORDED)

    test("managed doc: the catalog's current metadata wins when it is in the pipeline's bucket (outside prefixKey)") {
        assert(R.resolve(managed, ROOT, () => Some(NEWER)).contains(R.Resolved(s3a(NEWER), managed = true)))
        // s3a spelling from the catalog compares equal.
        assert(R.resolve(managed, ROOT, () => Some(s3a(NEWER))).contains(R.Resolved(s3a(NEWER), managed = true)))
        // The table root's own prefix plays no part: a new prefixKey still follows the catalog.
        assert(R.resolve(managed, "s3a://datris/uc_managed_v2", () => Some(NEWER)).contains(R.Resolved(s3a(NEWER), managed = true)))
    }

    test("managed doc: a catalog location in another bucket falls back to the recorded commit") {
        val elsewhere = "s3://datris-archive/uc/__unitystorage/schemas/5e1f/tables/9a2b/metadata/00009-ffff.metadata.json"
        assert(R.resolve(managed, ROOT, () => Some(elsewhere)).contains(R.Resolved(s3a(RECORDED), managed = true)))
    }

    test("managed doc: catalog unreachable or empty falls back to the recorded commit") {
        assert(R.resolve(managed, ROOT, () => throw new RuntimeException("catalog down")).contains(R.Resolved(s3a(RECORDED), managed = true)))
        assert(R.resolve(managed, ROOT, () => None).contains(R.Resolved(s3a(RECORDED), managed = true)))
    }

    test("not catalog-committed: path read, the catalog is never asked") {
        assert(R.resolve(null, ROOT, () => fail("must not ask the catalog")).isEmpty)
        assert(R.resolve(doc("managed", RECORDED, at = null), ROOT, () => fail("no commit time")).isEmpty)
        assert(R.resolve(doc("managed", null), ROOT, () => fail("no recorded location")).isEmpty)
        assert(R.resolve(doc("refused", null, at = null), ROOT, () => fail("refused")).isEmpty)
    }

    test("rest doc unchanged: under the root follows the catalog, elsewhere the recorded commit; never managed") {
        val restRecorded = "s3://datris/uc_managed/metadata/00003-9b2d.metadata.json"
        val restNewer = "s3://datris/uc_managed/metadata/00004-7c1e.metadata.json"
        val rest = doc("rest", restRecorded)
        assert(R.resolve(rest, ROOT, () => Some(restNewer)).contains(R.Resolved(s3a(restNewer), managed = false)))
        // A rest table only follows the catalog under its own root, even in the same bucket.
        assert(R.resolve(rest, ROOT, () => Some(NEWER)).contains(R.Resolved(s3a(restRecorded), managed = false)))
        // A rest doc at another prefix is a new table: path read.
        assert(R.resolve(rest, "s3a://datris/uc_managed_v2", () => fail("other root")).isEmpty)
    }

    test("managed doc: a catalog answer in another table dir (same bucket) falls back to the recorded commit") {
        // E.g. the schema was edited and the identifier now names another table.
        val otherTable = "s3://datris/uc/__unitystorage/schemas/77aa/tables/c0ffee/metadata/00004-1a2b.metadata.json"
        assert(R.resolve(managed, ROOT, () => Some(otherTable)).contains(R.Resolved(s3a(RECORDED), managed = true)))
    }
}
