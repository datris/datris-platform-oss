package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Early hint when Unity Catalog register mode targets Databricks
  * (plans/stories/uc-register-mode-databricks-hint.md). The pure host
  * detector behind the save-time hint: normalizeHost, lowercase, suffix. */
class DatabricksHostDetectorSpec extends AnyFunSuite {

    private val D = DatabricksHostDetector

    test("cloud, gcp and azure workspace hosts are detected, with scheme, port or path present") {
        val hosts = Seq(
            "dbc-a1b2c3d4-e5f6.cloud.databricks.com",
            "https://dbc-a1b2c3d4-e5f6.cloud.databricks.com/",
            "https://dbc-a1b2c3d4-e5f6.cloud.databricks.com:443/sql/1.0/warehouses/abc123",
            "DBC-A1B2C3D4-E5F6.Cloud.Databricks.COM",
            "adb-123.azuredatabricks.net",
            "https://adb-1234567890123456.7.azuredatabricks.net/?o=1234567890123456",
            "dbc-x.gcp.databricks.com",
            "  https://token@dbc-x.gcp.databricks.com:8443  "
        )
        hosts.foreach(h => assert(D.isDatabricksHost(h), h))
    }

    test("MinIO, fixture and blank hosts are not") {
        val hosts = Seq(
            "http://iceberg-rest:8181",
            "iceberg-rest",
            "http://minio:9000",
            "localhost",
            "databricks.com.evil.example",
            "https://example.com/dbc-x.cloud.databricks.com",
            "notazuredatabricks.net.example.org",
            "",
            "   "
        )
        hosts.foreach(h => assert(!D.isDatabricksHost(h), h))
        assert(!D.isDatabricksHost(null))
    }

    test("the suffix list covers databricks.com and azuredatabricks.net") {
        assert(D.Suffixes.contains(".databricks.com"), D.Suffixes)
        assert(D.Suffixes.contains(".azuredatabricks.net"), D.Suffixes)
    }
}
