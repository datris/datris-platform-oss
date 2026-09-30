package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

class DatabricksWarehouseSpec extends AnyFunSuite {
    private val creds = Map("host" -> "dbc-a1b2c3d4-e5f6.cloud.databricks.com", "token" -> "dapi-test")

    test("the pipeline's explicit warehouse wins over the secret's") {
        assert(DatabricksWarehouse.effective("pipelinewh", creds + ("warehouse" -> "secretwh")) == Right("pipelinewh"))
    }

    test("each secret alias is read when the pipeline has no warehouse") {
        Seq("warehouse", "httpPath", "http_path", "DATABRICKS_WAREHOUSE", "WAREHOUSE", "httppath").foreach { k =>
            assert(DatabricksWarehouse.effective(null, creds + (k -> "abc123")) == Right("abc123"), k)
        }
        assert(
            DatabricksWarehouse.effective("", creds + ("httpPath" -> "/sql/1.0/warehouses/abc123")).map(DatabricksConnectionUtil.warehouseHttpPath) ==
                Right("/sql/1.0/warehouses/abc123")
        )
    }

    test("whitespace is blank on both sides and values are trimmed") {
        assert(DatabricksWarehouse.effective("   ", creds + ("warehouse" -> "  secretwh  ")) == Right("secretwh"))
        assert(DatabricksWarehouse.effective("  pipelinewh ", creds) == Right("pipelinewh"))
        assert(DatabricksWarehouse.effective(" ", creds + ("warehouse" -> "  ")).isLeft)
    }

    test("neither → Left naming the pipeline, the secret and both fixes") {
        val left = DatabricksWarehouse.effective(null, creds, "orders", "databricks")
        assert(
            left == Left(
                "No SQL warehouse configured for pipeline orders: set destination.database.warehouse, or add a 'warehouse' field " +
                    "(the warehouse ID or HTTP path) to the Databricks secret 'databricks'"
            ),
            left
        )
        assert(DatabricksWarehouse.effective(null, null).isLeft)
    }
}
