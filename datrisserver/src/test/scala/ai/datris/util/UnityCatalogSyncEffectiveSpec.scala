package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: Unity Catalog metadata on by default (`DATRIS_UNITY_CATALOG_DEFAULT`)
  * (plans/stories/uc-default-enabled.md), Acceptance bullet 1.
  *
  * Seams this spec pins (named by the story's Files section):
  *
  * {{{
  *   UnityCatalogSync.effective(config: PipelineConfig, defaultEnabled: Boolean)
  *       : Option[(UnityCatalogSync, String)]   // (effective block, enabledBy); None = off
  *   UnityCatalogSync.defaultEnabledFromEnv: Boolean
  *       // sys.prop datris.unityCatalogDefault, else env DATRIS_UNITY_CATALOG_DEFAULT;
  *       // trim + equalsIgnoreCase("enabled"); anything else (unset, blank, garbage) = false
  * }}}
  *
  * enabledBy is "pipeline" (an explicit block decided) or "default" (the
  * install default filled in a missing block). The kill switch
  * (DATRIS_UNITY_CATALOG_SYNC) is not part of `effective`; the runtime hooks
  * check it after the effective gate (UnityCatalogMetadataSyncSpec).
  */
class UnityCatalogSyncEffectiveSpec extends AnyFunSuite {

    private val DefaultProp = "datris.unityCatalogDefault"
    private val DefaultEnv = "DATRIS_UNITY_CATALOG_DEFAULT"

    private val dbxDb = Database(dbName = "datris", schema = "default", table = "orders", useDatabricks = true, warehouse = "abc123", credentialsSecret = "dbx")

    private def dbx(uc: UnityCatalogSync = null): PipelineConfig =
        PipelineConfig(name = "orders_daily", destination = Destination(database = dbxDb), unityCatalog = uc)

    private def iceberg(uc: UnityCatalogSync = null): PipelineConfig =
        PipelineConfig(
            name = "orders_lake",
            destination = Destination(objectStore = ObjectStore(prefixKey = "orders_lake", fileFormat = "iceberg")),
            unityCatalog = uc
        )

    private def postgres(uc: UnityCatalogSync = null): PipelineConfig =
        PipelineConfig(
            name = "orders_pg",
            destination = Destination(database = Database(dbName = "datris", schema = "public", table = "orders")),
            unityCatalog = uc
        )

    // --- effective: the precedence table -------------------------------------------

    test("default disabled + no block → None") {
        assert(UnityCatalogSync.effective(dbx(), defaultEnabled = false).isEmpty)
        assert(UnityCatalogSync.effective(iceberg(), defaultEnabled = false).isEmpty)
    }

    test("default enabled + no block + Databricks → Some(block with every knob on, \"default\")") {
        val eff = UnityCatalogSync.effective(dbx(), defaultEnabled = true)
        assert(eff.isDefined, "a Databricks pipeline with no block must be defaulted on")
        val (block, enabledBy) = eff.get
        assert(enabledBy == "default", enabledBy)
        assert(block != null)
        assert(block.enabled)
        assert(block.commentsOn && block.tagsOn && block.propertiesOn && block.lineageOn, s"$block")
    }

    test("default enabled + no block + object-store Iceberg → None (registration needs per-pipeline secret/catalog)") {
        assert(UnityCatalogSync.effective(iceberg(), defaultEnabled = true).isEmpty)
    }

    test("default enabled + no block + non-Databricks database → None") {
        assert(UnityCatalogSync.effective(postgres(), defaultEnabled = true).isEmpty)
    }

    test("default enabled + no destination at all → None, never throws") {
        assert(UnityCatalogSync.effective(PipelineConfig(name = "bare"), defaultEnabled = true).isEmpty)
    }

    test("default enabled + {enabled:false} → None (explicit opt-out wins)") {
        assert(UnityCatalogSync.effective(dbx(UnityCatalogSync(enabled = false)), defaultEnabled = true).isEmpty)
        assert(UnityCatalogSync.effective(dbx(UnityCatalogSync(enabled = false)), defaultEnabled = false).isEmpty)
    }

    test("{enabled:true} with either default → Some(same block, \"pipeline\")") {
        val explicit = UnityCatalogSync(enabled = true)
        Seq(true, false).foreach { d =>
            val eff = UnityCatalogSync.effective(dbx(explicit), defaultEnabled = d)
            assert(eff.isDefined, s"defaultEnabled=$d")
            assert(eff.get._1 == explicit, s"defaultEnabled=$d: ${eff.get._1}")
            assert(eff.get._2 == "pipeline", s"defaultEnabled=$d: ${eff.get._2}")
        }
    }

    test("{enabled:true, lineage:false} keeps lineage off (explicit knobs are not overwritten by the default)") {
        // Jackson without DefaultScalaModule, as Spring's @RequestBody mapper parses it.
        val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        val uc = mapper.readValue("""{"enabled":true,"lineage":false}""", classOf[UnityCatalogSync])
        Seq(true, false).foreach { d =>
            val eff = UnityCatalogSync.effective(dbx(uc), defaultEnabled = d)
            assert(eff.isDefined, s"defaultEnabled=$d")
            val (block, enabledBy) = eff.get
            assert(!block.lineageOn, s"defaultEnabled=$d: $block")
            assert(block.commentsOn && block.tagsOn && block.propertiesOn, s"$block")
            assert(enabledBy == "pipeline")
        }
    }

    // --- defaultEnabledFromEnv -------------------------------------------------------

    private def withProp[T](value: String)(body: => T): T = {
        val old = System.getProperty(DefaultProp)
        if (value == null) System.clearProperty(DefaultProp) else System.setProperty(DefaultProp, value)
        try body
        finally if (old == null) System.clearProperty(DefaultProp) else System.setProperty(DefaultProp, old)
    }

    /** Set (or remove, value = null) a process environment variable for the
      * duration of `body`. Mutates the map behind System.getenv() (java.util is
      * opened to the forked test JVM in build.sbt), so both System.getenv(name)
      * and sys.env see it. */
    private def withEnv[T](name: String, value: String)(body: => T): T = {
        val unmodifiable = System.getenv()
        val f = unmodifiable.getClass.getDeclaredField("m")
        f.setAccessible(true)
        val m = f.get(unmodifiable).asInstanceOf[java.util.Map[String, String]]
        val old = m.get(name)
        if (value == null) m.remove(name) else m.put(name, value)
        try body
        finally if (old == null) m.remove(name) else m.put(name, old)
    }

    test("defaultEnabledFromEnv: enabled / ENABLED / ' enabled ' via sys.prop → true") {
        withEnv(DefaultEnv, null) {
            Seq("enabled", "ENABLED", "Enabled", " enabled ").foreach { v =>
                withProp(v) { assert(UnityCatalogSync.defaultEnabledFromEnv, s"'$v'") }
            }
        }
    }

    test("defaultEnabledFromEnv: disabled / blank / true / yes / garbage via sys.prop → false") {
        withEnv(DefaultEnv, null) {
            Seq("disabled", "", "   ", "true", "yes", "on", "enable", "enabledd", "garbage").foreach { v =>
                withProp(v) { assert(!UnityCatalogSync.defaultEnabledFromEnv, s"'$v'") }
            }
        }
    }

    test("defaultEnabledFromEnv: unset sys.prop and unset env → false (existing installs see no change)") {
        withEnv(DefaultEnv, null) {
            withProp(null) { assert(!UnityCatalogSync.defaultEnabledFromEnv) }
        }
    }

    test("defaultEnabledFromEnv: env DATRIS_UNITY_CATALOG_DEFAULT is read when the sys.prop is unset") {
        withProp(null) {
            withEnv(DefaultEnv, "enabled") { assert(UnityCatalogSync.defaultEnabledFromEnv, "env enabled") }
            withEnv(DefaultEnv, "disabled") { assert(!UnityCatalogSync.defaultEnabledFromEnv, "env disabled") }
        }
    }

    test("defaultEnabledFromEnv: sys.prop wins over env") {
        withEnv(DefaultEnv, "enabled") {
            withProp("disabled") { assert(!UnityCatalogSync.defaultEnabledFromEnv, "prop disabled beats env enabled") }
        }
        withEnv(DefaultEnv, "disabled") {
            withProp("enabled") { assert(UnityCatalogSync.defaultEnabledFromEnv, "prop enabled beats env disabled") }
        }
    }
}
