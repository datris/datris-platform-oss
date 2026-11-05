package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, Destination, PipelineConfig, SchemaField, SchemaProperties}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** SQL Server on-demand typing (plans/stories/mssql-query-and-typing.md,
  * Step 3): the in-place ALTER COLUMN statements, and that `_json` / `_xml`
  * document columns are never retyped. Statement shapes only; the migration
  * itself is covered by live E2E. */
class SqlServerDestTypesSpec extends AnyFunSuite {

    private val table = "dbo.orders"

    test("typed non-key columns get empty strings nulled, then ALTER COLUMN ... NULL") {
        val out = DestSchemaApply.sqlServerMigrationStatements(
            table,
            Seq(SchemaField("amount", "double"), SchemaField("placed", "timestamp")),
            Nil,
            None
        )
        assert(
            out == Seq(
                "UPDATE dbo.orders SET amount = NULL WHERE amount = N''",
                "UPDATE dbo.orders SET placed = NULL WHERE placed = N''",
                "ALTER TABLE dbo.orders ALTER COLUMN amount FLOAT NULL",
                "ALTER TABLE dbo.orders ALTER COLUMN placed DATETIME2 NULL"
            ),
            out
        )
    }

    test("a typed primary-key column drops and re-adds the constraint and stays NOT NULL") {
        val out = DestSchemaApply.sqlServerMigrationStatements(
            table,
            Seq(SchemaField("id", "int"), SchemaField("flag", "boolean")),
            Seq("id", "region"),
            Some("PK__orders__1")
        )
        assert(
            out == Seq(
                "UPDATE dbo.orders SET flag = NULL WHERE flag = N''",
                "ALTER TABLE dbo.orders DROP CONSTRAINT PK__orders__1",
                "ALTER TABLE dbo.orders ALTER COLUMN id INT NOT NULL",
                "ALTER TABLE dbo.orders ALTER COLUMN flag BIT NULL",
                "ALTER TABLE dbo.orders ADD CONSTRAINT PK__orders__1 PRIMARY KEY (id, region)"
            ),
            out
        )
    }

    test("a primary key no typed column belongs to is left alone") {
        val out = DestSchemaApply.sqlServerMigrationStatements(table, Seq(SchemaField("qty", "bigint")), Seq("id"), Some("PK_x"))
        assert(out == Seq("UPDATE dbo.orders SET qty = NULL WHERE qty = N''", "ALTER TABLE dbo.orders ALTER COLUMN qty BIGINT NULL"), out)
    }

    test("reserved-word column names are bracket-quoted") {
        val out = DestSchemaApply.sqlServerMigrationStatements(table, Seq(SchemaField("order", "int")), Nil, None)
        assert(out.last == "ALTER TABLE dbo.orders ALTER COLUMN [order] INT NULL", out)
    }

    private def mssql(fields: SchemaField*): PipelineConfig =
        PipelineConfig(
            name = "p",
            destination = Destination(
                schemaProperties = SchemaProperties(dbName = null, fields = fields.toList.asJava),
                database = Database(useSqlServer = true)
            )
        )

    test("validateFields does not type-check SQL Server document columns") {
        val cfg = mssql(SchemaField("_xml", "xml"), SchemaField("a", "string"))
        val fields = List(SchemaField("_xml", "xml"), SchemaField("a", "int")).asJava
        assert(DestSchemaApply.validateFields(cfg, fields) == fields)
    }
}
