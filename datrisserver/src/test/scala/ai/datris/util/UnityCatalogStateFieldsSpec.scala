package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** GET /pipelines/{name}/unity-catalog `enabled` / `enabledBy` / `lineageEnabled`
  * (plans/stories/uc-default-enabled.md, Acceptance bullets 3 and 4). */
class UnityCatalogStateFieldsSpec extends AnyFunSuite {

    private val dbxDb = Database(dbName = "datris", schema = "default", table = "orders", useDatabricks = true, warehouse = "abc123", credentialsSecret = "dbx")

    private def dbx(uc: UnityCatalogSync = null): PipelineConfig =
        PipelineConfig(name = "orders_daily", destination = Destination(database = dbxDb), unityCatalog = uc)

    private def iceberg(uc: UnityCatalogSync = null): PipelineConfig =
        PipelineConfig(
            name = "orders_lake",
            destination = Destination(objectStore = ObjectStore(prefixKey = "orders_lake", fileFormat = "iceberg")),
            unityCatalog = uc
        )

    test("Databricks, no block, default disabled → enabled:false, enabledBy:null, lineageEnabled:false") {
        assert(UnityCatalogSync.stateFields(dbx(), defaultEnabled = false) == ((false, null, false)))
    }

    test("Databricks, no block, default enabled → enabled:true, enabledBy:default, lineageEnabled:true") {
        assert(UnityCatalogSync.stateFields(dbx(), defaultEnabled = true) == ((true, "default", true)))
    }

    test("{enabled:false} → enabled:false, enabledBy:pipeline (opt-out is the pipeline's decision)") {
        Seq(true, false).foreach { d =>
            assert(UnityCatalogSync.stateFields(dbx(UnityCatalogSync(enabled = false)), d) == ((false, "pipeline", false)), s"default=$d")
        }
    }

    test("{enabled:true} → enabledBy:pipeline; lineage knob honoured") {
        assert(UnityCatalogSync.stateFields(dbx(UnityCatalogSync(enabled = true)), defaultEnabled = true) == ((true, "pipeline", true)))
        assert(UnityCatalogSync.stateFields(dbx(UnityCatalogSync(enabled = true, lineage = false)), defaultEnabled = false) == ((true, "pipeline", false)))
    }

    test("object-store Iceberg, no block, default enabled → off (never defaulted)") {
        assert(UnityCatalogSync.stateFields(iceberg(), defaultEnabled = true) == ((false, null, false)))
    }

    test("null config → off, never throws") {
        assert(UnityCatalogSync.stateFields(null, defaultEnabled = true) == ((false, null, false)))
    }
}
