package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, DatrisException, Destination, PipelineConfig, SchemaField, SchemaProperties}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** Pure guards of destination-side typing: which destinations are in scope,
  * and what an apply request may change (types only — never the column set).
  * The database-touching paths (sampling, migration) are covered by live E2E,
  * not unit tests. */
class DestSchemaApplySpec extends AnyFunSuite {

    private def config(db: Database, fields: List[SchemaField] = List(SchemaField("a", "string"), SchemaField("b", "string"))): PipelineConfig =
        PipelineConfig(
            name = "p",
            destination = Destination(
                schemaProperties = SchemaProperties(dbName = null, fields = fields.asJava),
                database = db
            )
        )

    // ---- inScopeDest ----

    test("postgres, snowflake, and databricks destinations are in scope") {
        assert(DestSchemaApply.inScopeDest(config(Database(usePostgres = true))) == Some("postgres"))
        assert(DestSchemaApply.inScopeDest(config(Database(useSnowflake = true))) == Some("snowflake"))
        assert(DestSchemaApply.inScopeDest(config(Database(useDatabricks = true))) == Some("databricks"))
    }

    test("mongo, destination-less, and null configs are out of scope") {
        assert(DestSchemaApply.inScopeDest(config(Database(useMongoDB = true))) == None)
        assert(DestSchemaApply.inScopeDest(PipelineConfig(name = "p")) == None)
        assert(DestSchemaApply.inScopeDest(null) == None)
    }

    // ---- validateFields ----

    private val pg = config(Database(usePostgres = true))

    test("validateFields accepts a full rename-free retype") {
        val fields = List(SchemaField("a", "int"), SchemaField("b", "string")).asJava
        assert(DestSchemaApply.validateFields(pg, fields) == fields)
    }

    test("validateFields matches names case-insensitively") {
        val fields = List(SchemaField("A", "int"), SchemaField("B", "double")).asJava
        assert(DestSchemaApply.validateFields(pg, fields) == fields)
    }

    test("validateFields requires fields") {
        assertThrows[DatrisException](DestSchemaApply.validateFields(pg, null))
        assertThrows[DatrisException](DestSchemaApply.validateFields(pg, new java.util.ArrayList[SchemaField]()))
    }

    test("validateFields rejects a missing, extra, or renamed column") {
        assertThrows[DatrisException](DestSchemaApply.validateFields(pg, List(SchemaField("a", "int")).asJava))
        assertThrows[DatrisException](DestSchemaApply.validateFields(pg,
            List(SchemaField("a", "int"), SchemaField("b", "int"), SchemaField("c", "int")).asJava))
        assertThrows[DatrisException](DestSchemaApply.validateFields(pg,
            List(SchemaField("a", "int"), SchemaField("renamed", "int")).asJava))
    }

    test("validateFields rejects unsupported types") {
        assertThrows[DatrisException](DestSchemaApply.validateFields(pg,
            List(SchemaField("a", "uuid"), SchemaField("b", "string")).asJava))
        assertThrows[DatrisException](DestSchemaApply.validateFields(pg,
            List(SchemaField("a", null), SchemaField("b", "string")).asJava))
    }
}
