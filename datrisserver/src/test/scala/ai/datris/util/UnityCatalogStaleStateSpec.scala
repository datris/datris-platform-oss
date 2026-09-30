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

    // E2E follow-up: the cleared doc must be PERSISTED, or a register-mode
    // run (whose registrar re-reads the doc) writes the stale fields back.
    test("clearIfStale persists the cleared doc when both sides are empty, and only then") {
        val written = scala.collection.mutable.ListBuffer[UnityCatalogSyncState]()
        val d = doc().copy(lineageHash = "h", registeredMetadataLocation = "r")
        val cleared = S.clearIfStale(d, ROOT, () => Some(false), () => Some(false), written += _)
        assert(cleared.isDefined, "stale")
        assert(written.size == 1 && written.head == cleared.get, s"$written")
        assert(!IcebergRestSession.restCommitted(written.head, ROOT), "the persisted doc no longer reads as committed")
        assert(written.head.lineageHash == "h" && written.head.registeredMetadataLocation == "r" && written.head.pipeline == "orders_daily")

        written.clear()
        assert(S.clearIfStale(d, ROOT, () => Some(true), () => Some(false), written += _).isEmpty, "catalog has the table")
        assert(S.clearIfStale(d, ROOT, () => Some(false), () => Some(true), written += _).isEmpty, "prefix has metadata")
        assert(S.clearIfStale(d, ROOT, () => None, () => Some(false), written += _).isEmpty, "catalog unknown")
        assert(written.isEmpty, s"nothing written when not stale: $written")
        // Not committed at this root: the probes are never run.
        assert(S.clearIfStale(d, "s3a://lake/orders_v2", () => fail("probe"), () => fail("probe"), _ => fail("write")).isEmpty)
        assert(S.clearIfStale(null, ROOT, () => fail("probe"), () => fail("probe"), _ => fail("write")).isEmpty)
    }

    test("clearIfStale propagates a write failure (the run must not continue with the stale doc on disk)") {
        intercept[RuntimeException](S.clearIfStale(doc(), ROOT, () => Some(false), () => Some(false), _ => throw new RuntimeException("mongo down")))
    }

    test("IcebergRestSession.interimState records the catalog table before the data write, keeping other fields") {
        val prev = doc(mode = "refused", loc = null, at = null).copy(lineageHash = "h", restRefusedReason = "old")
        val st = IcebergRestSession.interimState(prev, "orders_daily", "s3://lake/orders_daily/metadata/00000-a.metadata.json", "2026-09-30T12:00:00Z")
        assert(st.catalogMode == "rest" && st.lastRestCommitAt == "2026-09-30T12:00:00Z" && st.restRefusedReason == null, s"$st")
        assert(st.lineageHash == "h" && st.pipeline == "orders_daily")
        assert(IcebergRestSession.restCommitted(st, ROOT), "the created table is guarded from then on")
        val fresh = IcebergRestSession.interimState(null, "orders_daily", "s3://lake/orders_daily/metadata/00000-a.metadata.json", "t")
        assert(fresh.pipeline == "orders_daily" && fresh.catalogMode == "rest")
    }

    test("outsideRootMessage names the table, both locations, the managed-table cause and the drop advice") {
        val m = IcebergRestSession.outsideRootMessage("main.uc.probe", "s3://datris/uc/__unitystorage/schemas/x/tables/y", "s3a://datris/uc/probe/t")
        assert(m.contains("the catalog placed main.uc.probe at s3://datris/uc/__unitystorage/schemas/x/tables/y"), m)
        assert(m.contains("outside this pipeline's table root s3://datris/uc/probe/t"), m)
        assert(m.contains("Databricks creates managed Iceberg tables and ignores the requested location"), m)
        assert(m.contains("Have an admin drop main.uc.probe"), m)
        assert(m.contains("falling back to the path-based write"), m)
    }

    test("foreignAdvice: after a refused run, renaming will not help (managed table from an earlier run); otherwise rename") {
        val refused = doc(mode = "refused", loc = null, at = null)
        val a = IcebergRestSession.foreignAdvice(refused, "main.uc.probe")
        assert(a.contains("renaming will not help: have an admin drop main.uc.probe"), a)
        assert(a.contains("Databricks managed tables") && a.contains("use the Databricks destination"), a)
        assert(!a.contains("rename the pipeline"), a)
        assert(IcebergRestSession.foreignAdvice(null, "q") == "rename the pipeline or choose another schema")
        assert(IcebergRestSession.foreignAdvice(doc(), "q") == "rename the pipeline or choose another schema")
    }

    test("createdRefusedState records the created table and the reason, keeping other fields; a later catalog commit clears it") {
        val prev = doc(mode = null, loc = null, at = null).copy(lineageHash = "h")
        val st = IcebergRestSession.createdRefusedState(prev, "orders_daily", "main.uc.orders_daily", "uc-rest: refused")
        assert(st.catalogMode == "refused" && st.restCreatedTable == "main.uc.orders_daily" && st.restRefusedReason == "uc-rest: refused", s"$st")
        assert(st.lineageHash == "h")
        assert(IcebergRestSession.createdRefusedState(null, "p", "q", "r").pipeline == "p")
        // Carried by the stale-state clear; cleared by the next catalog commit.
        assert(S.withoutRestCommit(st).restCreatedTable == "main.uc.orders_daily")
        assert(IcebergRestSession.interimState(st, "orders_daily", "s3://lake/orders_daily/metadata/00000-a.metadata.json", "t").restCreatedTable == null)
    }

    test("foreignAdvice is state-independent for Databricks managed storage, and follows restCreatedTable") {
        val managed = "s3://datris/uc/__unitystorage/schemas/a/tables/b/metadata/00000-x.metadata.json"
        assert(IcebergRestSession.foreignAdvice(null, "q", managed).contains("renaming will not help: have an admin drop q"))
        assert(IcebergRestSession.foreignAdvice(doc(), "q", managed).contains("renaming will not help"))
        val created = doc(mode = null, loc = null, at = null).copy(restCreatedTable = "q")
        assert(IcebergRestSession.foreignAdvice(created, "q", "s3://other/t/metadata/1.json").contains("renaming will not help"))
        assert(IcebergRestSession.foreignAdvice(null, "q", "s3://other/t/metadata/1.json") == "rename the pipeline or choose another schema")
    }

    test("RestLocationRefused from a create carries created=true; from a load it does not") {
        assert(new IcebergWriter.RestLocationRefused("a", "b", created = true).created)
        assert(!new IcebergWriter.RestLocationRefused("a", "b").created)
    }

    test("a doc recording restCreatedTable survives create-time clearing and startup cleanup") {
        val created = doc(mode = "refused", loc = null, at = null).copy(restCreatedTable = "main.uc.orders_daily")
        assert(S.keepOnCreate(created, Some(ROOT), () => fail("no prefix probe needed")))
        assert(S.keepOnCreate(created, None, () => fail("no prefix probe needed")), "kept even without an object store root")
        val gone = created.copy(pipeline = "gone_created")
        assert(S.orphanDocs(Seq(gone, doc("gone_plain", mode = null, loc = null, at = null)), Set.empty) == Seq("gone_plain"))
    }
}
