package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Destination, ObjectStore, PipelineConfig, UnityCatalogSync, UnityCatalogSyncState}
import org.scalatest.funsuite.AnyFunSuite

/** UnityCatalogDeleteAdvice.forPipeline: the warning a pipeline delete
  * returns when Unity Catalog still holds the pipeline's Iceberg table
  * (committed through the REST catalog). Buckets come from
  * destinationBucketOverride so no environment is needed. */
class UnityCatalogDeleteAdviceSpec extends AnyFunSuite {

    private val A = UnityCatalogDeleteAdvice
    private val ROOT = "s3a://lake/orders_daily"

    private def config(fileFormat: String, prefix: String = "orders_daily", uc: UnityCatalogSync = null): PipelineConfig =
        PipelineConfig(
            name = "orders_daily",
            destination = Destination(objectStore =
                ObjectStore(prefixKey = prefix, fileFormat = fileFormat, destinationBucketOverride = "lake")
            ),
            unityCatalog =
                if (uc != null) uc
                else UnityCatalogSync(enabled = true, catalog = "main", schema = "sales", catalogMode = "rest")
        )

    private def state(mode: String, loc: String): UnityCatalogSyncState =
        UnityCatalogSyncState(
            "orders_daily",
            null,
            null,
            null,
            null,
            null,
            null,
            catalogMode = mode,
            restMetadataLocation = loc,
            // A catalog commit records its time (IcebergRestSession.record).
            lastRestCommitAt = if (mode == "rest") "2026-09-29T10:00:03Z" else null
        )

    private val committed = state("rest", ROOT.replace("s3a://", "s3://") + "/metadata/00003-ab.metadata.json")

    test("non-Iceberg object store → None") {
        assert(A.forPipeline(config("parquet"), committed).isEmpty)
        assert(A.forPipeline(config(null), committed).isEmpty)
    }

    test("no object store destination → None") {
        assert(A.forPipeline(PipelineConfig(name = "p", destination = Destination()), committed).isEmpty)
        assert(A.forPipeline(PipelineConfig(name = "p"), committed).isEmpty)
    }

    test("Iceberg never committed through the catalog → None") {
        assert(A.forPipeline(config("iceberg"), null).isEmpty)
        assert(A.forPipeline(config("iceberg"), state("register", null)).isEmpty)
        assert(A.forPipeline(config("iceberg"), state("refused", null)).isEmpty)
        // rest commit recorded for another table root (the prefix moved).
        assert(A.forPipeline(config("iceberg", prefix = "orders_v2"), committed).isEmpty)
        // sibling sharing the root as a string prefix is not this table
        assert(A.forPipeline(config("iceberg", prefix = "orders"), committed).isEmpty)
    }

    test("committed → warning naming the qualified table") {
        val msg = A.forPipeline(config("ICEBERG"), committed)
        assert(msg.contains("Unity Catalog still holds main.sales.orders_daily; have an admin drop it"), msg)
    }

    test("committed, files kept → says the catalog can still read it") {
        val msg = A.forPipeline(config("iceberg"), committed, dataDeleted = false).getOrElse("")
        assert(msg.contains("main.sales.orders_daily"), msg)
        assert(msg.contains("can still read it"), msg)
        assert(msg.contains("have an admin drop it"), msg)
    }

    test("committed with the unityCatalog block removed → placeholders, still warns") {
        val cfg = config("iceberg").copy(unityCatalog = null)
        val msg = A.forPipeline(cfg, committed).getOrElse("")
        assert(msg.contains("Unity Catalog still holds"), msg)
    }

    test("prefix with slashes normalizes to the same root") {
        assert(A.forPipeline(config("iceberg", prefix = "/orders_daily/"), committed).nonEmpty)
    }

    // --- E2E upgrade-path follow-ups -------------------------------------------

    test("reset (config kept, data deleted) on a committed table → the next run will fail until dropped or moved") {
        val msg = A.forPipeline(config("iceberg"), committed, dataDeleted = true, configDeleted = false).getOrElse("")
        assert(msg.contains("main.sales.orders_daily"), msg)
        assert(msg.contains("the next run of this pipeline will fail until an admin drops it (or the pipeline moves to a new prefix)"), msg)
    }

    test("register mode: a registered entry under the root with its data deleted → warns it points at a location that no longer exists") {
        val registered = UnityCatalogSyncState(
            "orders_daily",
            null,
            null,
            null,
            null,
            null,
            null,
            registeredMetadataLocation = ROOT + "/metadata/v2.metadata.json",
            lastRegisterAt = "2026-09-29T10:00:00Z"
        )
        val cfg = config("iceberg", uc = UnityCatalogSync(enabled = true, catalog = "main", schema = "sales"))
        val msg = A.forPipeline(cfg, registered).getOrElse("")
        assert(msg == "Unity Catalog still has main.sales.orders_daily registered at a location that no longer exists; have an admin drop it", msg)
        // Reset in register mode: same advice.
        assert(A.forPipeline(cfg, registered, configDeleted = false).contains(msg))
        // Files kept: the registered entry still reads; nothing to say.
        assert(A.forPipeline(cfg, registered, dataDeleted = false).isEmpty)
        // Never actually registered (no lastRegisterAt), or registered elsewhere: nothing.
        assert(A.forPipeline(cfg, registered.copy(lastRegisterAt = null)).isEmpty)
        assert(A.forPipeline(cfg, registered.copy(registeredMetadataLocation = "s3://lake/orders_daily2/metadata/v2.metadata.json")).isEmpty)
    }

    test("restCreatedTable (Databricks managed table Datris created but never wrote) → warns on delete and on reset") {
        val created =
            UnityCatalogSyncState("orders_daily", null, null, null, null, null, null, catalogMode = "refused", restCreatedTable = "main.sales.orders_daily")
        val want =
            "Unity Catalog holds main.sales.orders_daily, created by Datris but never written to (the catalog ignored the requested location); have an admin drop it"
        assert(A.forPipeline(config("iceberg"), created).contains(want))
        assert(A.forPipeline(config("iceberg"), created, configDeleted = false).contains(want))
        assert(A.forPipeline(config("iceberg"), created, dataDeleted = false).contains(want))
        assert(A.forPipeline(config("iceberg"), created.copy(restCreatedTable = null)).isEmpty)
    }

    // ---- Story: Unity Catalog 7: Databricks-managed Iceberg mode -----------
    // (plans/stories/unity-catalog-7-managed-iceberg.md), Acceptance bullet 6.
    // A managed-committed table lives where the catalog put it (not under
    // prefixKey): Datris deleted nothing there, whatever dataDeleted /
    // configDeleted say.

    test("managed table: advice says Unity Catalog owns the files") {
        val managedUc = UnityCatalogSync(enabled = true, catalog = "main", schema = "sales", catalogMode = "managed")
        val managed = UnityCatalogSyncState(
            "orders_daily",
            null,
            null,
            null,
            null,
            null,
            null,
            catalogMode = "managed",
            restMetadataLocation = "s3://lake/uc/__unitystorage/schemas/5e1f/tables/9a2b/metadata/00002-e5f6.metadata.json",
            lastRestCommitAt = "2026-10-01T09:00:00Z"
        )
        val want =
            "Unity Catalog owns main.sales.orders_daily and its files (catalogMode managed); Datris deleted nothing there; " +
                "drop main.sales.orders_daily in Unity Catalog to remove the data"
        assert(A.forPipeline(config("iceberg", uc = managedUc), managed).contains(want))
        assert(A.forPipeline(config("iceberg", uc = managedUc), managed, configDeleted = false).contains(want))
        assert(A.forPipeline(config("iceberg", uc = managedUc), managed, dataDeleted = false).contains(want))
        // A different prefixKey does not matter (root-independent).
        assert(A.forPipeline(config("iceberg", prefix = "orders_v2", uc = managedUc), managed).contains(want))
        // No recorded commit: nothing to warn about.
        assert(A.forPipeline(config("iceberg", uc = managedUc), managed.copy(lastRestCommitAt = null)).isEmpty)
    }
}
