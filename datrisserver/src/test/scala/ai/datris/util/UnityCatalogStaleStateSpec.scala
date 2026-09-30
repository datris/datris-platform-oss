package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.UnityCatalogSyncState
import org.scalatest.funsuite.AnyFunSuite

/** Leftover Unity Catalog sync state (E2E upgrade-path finding): the pure
  * decisions behind the create-time clear, the run-time stale check, the
  * startup cleanup and the state endpoint's top-level `state`. */
class UnityCatalogStaleStateSpec extends AnyFunSuite {

    private val S = UnityCatalogStaleState
    private val ROOT = "s3a://lake/orders_daily"

    private def doc(
        name: String = "orders_daily",
        mode: String = "rest",
        loc: String = "s3://lake/orders_daily/metadata/00003-ab.metadata.json",
        at: String = "2026-09-29T10:00:03Z"
    ): UnityCatalogSyncState =
        UnityCatalogSyncState(name, null, null, null, null, null, null, catalogMode = mode, restMetadataLocation = loc, lastRestCommitAt = at)

    test("staleCommitted: only when committed and BOTH the catalog and the prefix are known empty") {
        assert(S.staleCommitted(committed = true, catalogHasTable = Some(false), prefixHasMetadata = Some(false)))
        assert(!S.staleCommitted(committed = true, Some(true), Some(false)), "catalog has the table")
        assert(!S.staleCommitted(committed = true, Some(false), Some(true)), "prefix has metadata")
        assert(!S.staleCommitted(committed = true, None, Some(false)), "catalog unknown")
        assert(!S.staleCommitted(committed = true, Some(false), None), "prefix unknown")
        assert(!S.staleCommitted(committed = false, Some(false), Some(false)), "nothing to ignore")
    }

    test("withoutRestCommit drops the catalog-commit fields only") {
        val d = doc().copy(lineageHash = "h", registeredMetadataLocation = "r", restRefusedReason = "x")
        val w = S.withoutRestCommit(d)
        assert(w.catalogMode == null && w.restMetadataLocation == null && w.lastRestCommitAt == null && w.restRefusedReason == null, s"$w")
        assert(w.lineageHash == "h" && w.registeredMetadataLocation == "r" && w.pipeline == "orders_daily", s"$w")
        assert(!IcebergRestSession.restCommitted(w, ROOT))
    }

    test("keepOnCreate: keep only a doc committed at the new pipeline's root whose prefix may still hold the table") {
        assert(S.keepOnCreate(doc(), Some(ROOT), () => Some(true)))
        assert(S.keepOnCreate(doc(), Some(ROOT), () => None), "unknown prefix: conservative keep")
        assert(!S.keepOnCreate(doc(), Some(ROOT), () => Some(false)), "prefix empty: stale, clear")
        assert(!S.keepOnCreate(doc(), Some("s3a://lake/orders_v2"), () => fail("other root: prefix never probed")))
        assert(!S.keepOnCreate(doc(), None, () => fail("no object store: never probed")))
        assert(!S.keepOnCreate(doc(mode = "refused"), Some(ROOT), () => fail("not committed: never probed")))
        assert(!S.keepOnCreate(doc(at = null), Some(ROOT), () => fail("no commit time: not committed")))
        assert(!S.keepOnCreate(null, Some(ROOT), () => fail("no doc")))
    }

    test("orphanDocs: docs of missing pipelines, except catalog-committed ones") {
        val docs = Seq(
            doc("live"),
            doc("gone_register", mode = null, loc = null, at = null),
            doc("gone_refused", mode = "refused", loc = null, at = null),
            doc("gone_committed"),
            null
        )
        assert(S.orphanDocs(docs, Set("live")) == Seq("gone_register", "gone_refused"))
        assert(S.orphanDocs(Nil, Set("live")).isEmpty)
    }

    test("parseDoc reads the stored {pipeline, value} shape") {
        val d = S.parseDoc("""{"_id":{"$oid":"x"},"pipeline":"orders","value":{"pipeline":"orders","catalogMode":"rest","lastRestCommitAt":"t"}}""")
        assert(d.exists(s => s.pipeline == "orders" && s.catalogMode == "rest" && s.lastRestCommitAt == "t"), s"$d")
        assert(S.parseDoc("""{"pipeline":"bare"}""").exists(_.pipeline == "bare"))
        assert(S.parseDoc("not json").isEmpty)
    }

    test("topLevelState: never / error on lastError or a refused catalog run / synced") {
        assert(S.topLevelState(null, "never") == "never")
        assert(S.topLevelState(doc(), "rest") == "synced")
        assert(S.topLevelState(doc().copy(lastError = "uc-lineage: x"), "rest") == "error")
        assert(S.topLevelState(doc(mode = "refused").copy(restRefusedReason = "uc-rest: refused"), "refused") == "error")
        // A refused reason left over after switching to register mode does not flip it.
        assert(S.topLevelState(doc(mode = "refused").copy(restRefusedReason = "uc-rest: refused"), "registered") == "synced")
    }
}
