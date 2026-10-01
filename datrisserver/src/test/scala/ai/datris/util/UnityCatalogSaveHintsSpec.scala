package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, Destination, ObjectStore, PipelineConfig, UnityCatalogSync}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Early hint when Unity Catalog register mode targets Databricks
  * (plans/stories/uc-register-mode-databricks-hint.md). forConfig with a
  * stubbed hostOf (no Vault): the advisory warnings POST /api/v1/pipeline
  * returns when the Unity Catalog secret points at a Databricks workspace
  * and the configured catalogMode cannot work there. */
class UnityCatalogSaveHintsSpec extends AnyFunSuite {

    private val H = UnityCatalogSaveHints
    private val DBX = "dbc-x.cloud.databricks.com"

    private val hostOf: String => Option[String] = {
        case "dbx_hint" => Some(DBX)
        case "uc_fixture" => Some("http://iceberg-rest:8181")
        case _ => None
    }

    private def uc(
        secret: String = "dbx_hint",
        mode: String = null,
        register: java.lang.Boolean = null,
        enabled: Boolean = true
    ): UnityCatalogSync =
        UnityCatalogSync(
            enabled = enabled,
            credentialsSecret = secret,
            catalog = "main",
            schema = "default",
            register = register,
            catalogMode = mode
        )

    private def objectStore(fileFormat: String = "iceberg", u: UnityCatalogSync = uc()): PipelineConfig =
        PipelineConfig(
            name = "hint_reg",
            destination = Destination(objectStore = ObjectStore(prefixKey = "hint_reg", fileFormat = fileFormat)),
            unityCatalog = u
        )

    private def allHintsFor(configs: PipelineConfig*): Seq[String] = configs.flatMap(H.forConfig(_, hostOf))

    test("register mode (default) on a Databricks host → one hint naming the host, register and catalogMode managed") {
        val hints = H.forConfig(objectStore(), hostOf)
        assert(hints.size == 1, hints)
        val h = hints.head
        assert(h.contains(DBX), h)
        assert(h.contains("register"), h)
        assert(h.contains("Set catalogMode managed"), h)
        assert(h.contains("databricks destination"), h)
        // explicit "register" behaves like the default
        assert(H.forConfig(objectStore(u = uc(mode = "register")), hostOf) == hints)
    }

    test("rest mode on a Databricks host → one hint naming the host and managed") {
        val hints = H.forConfig(objectStore(u = uc(mode = "rest")), hostOf)
        assert(hints.size == 1, hints)
        val h = hints.head
        assert(h.contains(DBX), h)
        assert(h.contains("managed"), h)
        assert(h.contains("Set catalogMode managed"), h)
        assert(h.contains("databricks destination"), h)
    }

    test("register: false → no hint") {
        assert(H.forConfig(objectStore(u = uc(register = java.lang.Boolean.FALSE)), hostOf).isEmpty)
    }

    test("Databricks database destination → no hint") {
        val cfg = PipelineConfig(
            name = "hint_dbx",
            destination = Destination(database =
                Database(useDatabricks = true, dbName = "main", schema = "default", table = "hint_dbx")
            ),
            unityCatalog = uc()
        )
        assert(H.forConfig(cfg, hostOf).isEmpty)
    }

    test("parquet object store → no hint") {
        assert(H.forConfig(objectStore(fileFormat = "parquet"), hostOf).isEmpty)
        assert(H.forConfig(objectStore(fileFormat = null), hostOf).isEmpty)
    }

    test("hostOf returning None (missing, non-Databricks or unreadable secret) → no hint") {
        assert(H.forConfig(objectStore(u = uc(secret = "missing")), hostOf).isEmpty)
        assert(H.forConfig(objectStore(u = uc(secret = null)), hostOf).isEmpty)
        // a hostOf that throws-free returns None for everything
        assert(H.forConfig(objectStore(), _ => None).isEmpty)
    }

    test("non-Databricks host (fixture REST catalog) → no hint") {
        assert(H.forConfig(objectStore(u = uc(secret = "uc_fixture")), hostOf).isEmpty)
        assert(H.forConfig(objectStore(u = uc(secret = "uc_fixture", mode = "rest")), hostOf).isEmpty)
    }

    test("no unityCatalog block or enabled false → no hint") {
        assert(H.forConfig(objectStore(u = null), hostOf).isEmpty)
        assert(H.forConfig(objectStore(u = uc(enabled = false)), hostOf).isEmpty)
    }

    test("managed mode on a Databricks host is the advised fix → no hint") {
        assert(H.forConfig(objectStore(u = uc(mode = "managed")), hostOf).isEmpty)
    }

    test("no hint text contains 'error' or 'exception' (MCP treats those bodies as failure)") {
        val hints = allHintsFor(
            objectStore(),
            objectStore(u = uc(mode = "rest")),
            objectStore(fileFormat = "ICEBERG", u = uc(mode = "REST"))
        )
        assert(hints.size == 3, hints)
        hints.foreach { h =>
            val l = h.toLowerCase
            assert(!l.contains("error") && !l.contains("exception"), h)
        }
    }
}
