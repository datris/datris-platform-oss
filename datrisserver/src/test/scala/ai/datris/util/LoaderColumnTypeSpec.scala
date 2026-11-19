package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, SchemaField}
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Try

/** Story: Column types: one exact rule for the type a schema field may carry,
  * checked on every save and again before any destination statement
  * (plans/stories/column-type-text-escaping.md), Decisions 4, 5 and 6.
  *
  * Each engine's type function returns its fixed mapping, or type text that
  * passes `ColumnTypeText.safe`, and throws otherwise; the statement builders
  * that call it therefore throw too. Document columns (`_json`, `_xml`) are
  * mapped by name before the type is read.
  *
  * {{{
  * object SnowflakeLoader  { private[util] def snowflakeType(field: SchemaField): String }
  * object DatabricksLoader { private[util] def databricksType(field: SchemaField): String }
  * object SqlServerLoader  { def sqlServerType(field: SchemaField, isKey: Boolean = false): String
  *                           def createTableSql(db: Database, fields: Seq[SchemaField]): String }
  * }}}
  *
  * Pure, no I/O. */
class LoaderColumnTypeSpec extends AnyFunSuite {

    private val sfDb = Database(dbName = "datris", schema = "public", table = "orders", keyFields = java.util.Arrays.asList("id"), useSnowflake = true)
    private val dbxDb = Database(dbName = "main", schema = "sales", table = "orders", keyFields = java.util.Arrays.asList("id"), useDatabricks = true)
    private val msDb = Database(dbName = "datris", schema = "dbo", table = "orders", useTransaction = true)

    private val evil = "varchar(10)); DROP TABLE public.canary; --"

    /** Types that fail the rule (Decision 1): each must make every type
      * function throw. */
    private val failing: Seq[String] = Seq(
        evil,
        "varchar(10) not null",
        "decimal(10,2) default 0",
        "char(1) collate \"C\"",
        "decimal(10,-2)",
        "numeric(10,2)",
        "timestamp with time zone",
        "varchar(10)/* x */",
        "varchar(10)\n; DROP TABLE public.canary",
        "decimal(12,2), extra int",
        "varchar(255)) AS x FROM other_table --",
        "(SELECT 1)"
    )

    private def f(t: String, name: String = "c"): SchemaField = SchemaField(name, t)

    // ------------------------------------------------ plain types: v1.60.0 text

    test("snowflakeType, databricksType and sqlServerType return the v1.60.0 text for every plain type, decimal(10,2), varchar(255) and char(1)") {
        // (type, Snowflake, Databricks, SQL Server non-key, SQL Server key): the
        // text the v1.60.0 functions return (read from the code at fbf79e1b).
        // `integer` is mapped by all three and stays tolerated at load (Decision 6).
        val expected: Seq[(String, String, String, String, String)] = Seq(
            ("string", "VARCHAR", "STRING", "NVARCHAR(MAX)", "NVARCHAR(450)"),
            ("String", "VARCHAR", "STRING", "NVARCHAR(MAX)", "NVARCHAR(450)"),
            ("int", "NUMBER(38,0)", "INT", "INT", "INT"),
            ("INT", "NUMBER(38,0)", "INT", "INT", "INT"),
            ("integer", "NUMBER(38,0)", "INT", "INT", "INT"),
            ("tinyint", "NUMBER(38,0)", "INT", "INT", "INT"),
            ("smallint", "NUMBER(38,0)", "INT", "INT", "INT"),
            ("bigint", "NUMBER(38,0)", "BIGINT", "BIGINT", "BIGINT"),
            ("float", "FLOAT", "FLOAT", "FLOAT", "FLOAT"),
            ("double", "FLOAT", "DOUBLE", "FLOAT", "FLOAT"),
            ("boolean", "BOOLEAN", "BOOLEAN", "BIT", "BIT"),
            ("date", "DATE", "DATE", "DATE", "DATE"),
            ("timestamp", "TIMESTAMP_NTZ", "TIMESTAMP", "DATETIME2", "DATETIME2"),
            ("Timestamp", "TIMESTAMP_NTZ", "TIMESTAMP", "DATETIME2", "DATETIME2"),
            // passed through exactly as written
            ("decimal(10,2)", "decimal(10,2)", "decimal(10,2)", "decimal(10,2)", "decimal(10,2)"),
            ("varchar(255)", "varchar(255)", "varchar(255)", "varchar(255)", "varchar(255)"),
            ("char(1)", "char(1)", "char(1)", "char(1)", "char(1)"),
            // the other forms Backward compat lists as byte-identical
            ("DECIMAL(10,2)", "DECIMAL(10,2)", "DECIMAL(10,2)", "DECIMAL(10,2)", "DECIMAL(10,2)"),
            ("decimal(10, 2)", "decimal(10, 2)", "decimal(10, 2)", "decimal(10, 2)", "decimal(10, 2)"),
            ("decimal(10)", "decimal(10)", "decimal(10)", "decimal(10)", "decimal(10)"),
            ("varchar(max)", "varchar(max)", "varchar(max)", "varchar(max)", "varchar(max)")
        )
        for ((t, sf, dbx, ms, msKey) <- expected) {
            assert(SnowflakeLoader.snowflakeType(f(t)) == sf, "snowflake " + t)
            assert(DatabricksLoader.databricksType(f(t)) == dbx, "databricks " + t)
            assert(SqlServerLoader.sqlServerType(f(t)) == ms, "sqlserver " + t)
            assert(SqlServerLoader.sqlServerType(f(t), isKey = true) == msKey, "sqlserver key " + t)
        }
        // and the builders that use them: same statements as v1.60.0
        assert(
            SnowflakeLoader.createTableSql(sfDb, Seq(f("int", "id"), f("decimal(10,2)", "amount"), f("varchar(255)", "note"), f("char(1)", "flag"))) ==
                "CREATE TABLE IF NOT EXISTS datris.public.orders (id NUMBER(38,0), amount decimal(10,2), note varchar(255), flag char(1), PRIMARY KEY (id))"
        )
        assert(
            DatabricksLoader.createTableSql(dbxDb.copy(keyFields = null), Seq(f("int", "id"), f("decimal(10,2)", "amount"), f("varchar(255)", "note"))) ==
                "CREATE TABLE IF NOT EXISTS main.sales.orders (id INT, amount decimal(10,2), note varchar(255))"
        )
        assert(DatabricksLoader.selectList(Seq(f("decimal(10,2)", "amount"))) == "CAST(`amount` AS decimal(10,2)) AS amount")
        assert(
            SqlServerLoader.createTableSql(msDb, Seq(f("string", "id"), f("decimal(10,2)", "amount"), f("varchar(max)", "note"))) ==
                "IF NOT EXISTS (SELECT 1 FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = N'dbo' AND t.name = N'orders')" +
                " CREATE TABLE dbo.orders (id NVARCHAR(MAX), amount decimal(10,2), note varchar(max))"
        )
        assert(DatabricksLoader.addColumnSql(dbxDb, f("decimal(10,2)", "amount")).endsWith(" decimal(10,2)"))
        assert(SnowflakeLoader.addColumnSql(sfDb, f("char(1)", "flag")).endsWith(" char(1)"))
    }

    // ----------------------------------------------------------- failing types

    test("each throws for a type that fails the rule and never returns it") {
        for (t <- failing) {
            val clue = "[" + t + "]"
            val sf = Try(SnowflakeLoader.snowflakeType(f(t)))
            assert(sf.isFailure, "snowflakeType returned " + sf + " for " + clue)
            val dbx = Try(DatabricksLoader.databricksType(f(t)))
            assert(dbx.isFailure, "databricksType returned " + dbx + " for " + clue)
            val ms = Try(SqlServerLoader.sqlServerType(f(t)))
            assert(ms.isFailure, "sqlServerType returned " + ms + " for " + clue)
            val msKey = Try(SqlServerLoader.sqlServerType(f(t), isKey = true))
            assert(msKey.isFailure, "sqlServerType(isKey) returned " + msKey + " for " + clue)
        }
        // Decision 4: no builder can send the text either, on any engine
        val fs = Seq(f("string", "id"), f(evil, "amount"))
        val builders: Seq[(String, () => String)] = Seq(
            "snowflake createTableSql" -> (() => SnowflakeLoader.createTableSql(sfDb, fs)),
            "snowflake addColumnSql" -> (() => SnowflakeLoader.addColumnSql(sfDb, f(evil, "amount"))),
            "databricks createTableSql" -> (() => DatabricksLoader.createTableSql(dbxDb, fs)),
            "databricks addColumnSql" -> (() => DatabricksLoader.addColumnSql(dbxDb, f(evil, "amount"))),
            "databricks selectList" -> (() => DatabricksLoader.selectList(fs)),
            "databricks copySql" -> (() => DatabricksLoader.copySql(dbxDb, "/Volumes/main/sales/datris_staging/x.csv", fs, ",")),
            "databricks mergeSql" -> (() => DatabricksLoader.mergeSql(dbxDb, "/Volumes/main/sales/datris_staging/x.csv", fs, Seq("id"), ",")),
            "databricks insertOverwriteSql" -> (() => DatabricksLoader.insertOverwriteSql(dbxDb, "/Volumes/main/sales/datris_staging/x.csv", fs, ",")),
            "sqlserver columnDef" -> (() => SqlServerLoader.columnDef(f(evil, "amount"), isKey = false))
        )
        for ((what, build) <- builders) {
            val r = Try(build())
            assert(r.isFailure, what + " built " + r)
        }
    }

    // ------------------------------------------------------- document columns

    test("_json and _xml are mapped by name whatever their type holds") {
        for (t <- failing ++ Seq("string", "int", null)) {
            val clue = "[" + t + "]"
            for (j <- Seq("_json", "_JSON", "_Json")) {
                assert(SnowflakeLoader.snowflakeType(f(t, j)) == "VARIANT", "snowflake " + j + " " + clue)
                assert(DatabricksLoader.databricksType(f(t, j)) == "VARIANT", "databricks " + j + " " + clue)
                assert(SqlServerLoader.sqlServerType(f(t, j)) == "NVARCHAR(MAX)", "sqlserver " + j + " " + clue)
            }
            for (x <- Seq("_xml", "_XML")) {
                assert(SnowflakeLoader.snowflakeType(f(t, x)) == "VARCHAR", "snowflake " + x + " " + clue)
                assert(DatabricksLoader.databricksType(f(t, x)) == "STRING", "databricks " + x + " " + clue)
                assert(SqlServerLoader.sqlServerType(f(t, x)) == "XML", "sqlserver " + x + " " + clue)
            }
        }
        // the SQL Server DDL for them is unchanged too
        assert(SqlServerLoader.columnDef(f(evil, "_json"), isKey = false) == "_json NVARCHAR(MAX) CHECK (ISJSON(_json) = 1)")
        assert(SqlServerLoader.columnDef(f(evil, "_xml"), isKey = false) == "_xml XML")
    }

    // -------------------------------------------------------- SQL Server DDL

    test("SqlServerLoader.createTableSql throws for a field with a failing type") {
        for (t <- failing) {
            val r = Try(SqlServerLoader.createTableSql(msDb, Seq(f("string", "id"), f(t, "amount"))))
            assert(r.isFailure, "[" + t + "] built " + r)
            val keyed = Try(SqlServerLoader.createTableSql(msDb.copy(keyFields = java.util.Arrays.asList("amount")), Seq(f(t, "amount"))))
            assert(keyed.isFailure, "[" + t + "] as key built " + keyed)
        }
    }

    // ------------------------------------------- implementer additions (lead ruling D)

    test("a null type: SQL Server keeps reading it as string, Snowflake, Databricks and Postgres refuse it") {
        assert(SqlServerLoader.sqlServerType(f(null)) == "NVARCHAR(MAX)")
        assert(SqlServerLoader.sqlServerType(f(null), isKey = true) == "NVARCHAR(450)")
        assert(ColumnTypeText.unsafeFields(SqlServerLoader.withNullAsString(Seq(f(null)))).isEmpty)
        assert(Try(SnowflakeLoader.snowflakeType(f(null))).isFailure)
        assert(Try(DatabricksLoader.databricksType(f(null))).isFailure)
        assert(Try(PostgresSql.columnType(f(null))).isFailure)
    }

    test("PostgresSql.addColumnType writes the v1.60.0 ADD COLUMN text for every safe type that worked, int2/float8 for the two that never did") {
        for (
            (t, pg) <- Seq(
                "string" -> "text",
                "STRING" -> "text",
                "int" -> "int",
                "smallint" -> "smallint",
                "float" -> "float",
                "decimal(10,2)" -> "decimal(10,2)",
                // v1.60.0 sent these as written and PostgreSQL has no such type
                "tinyint" -> "int2",
                "TINYINT" -> "int2",
                "double" -> "float8",
                "Double" -> "float8"
            )
        )
            assert(PostgresSql.addColumnType(f(t)) == pg, t)
        // document columns: string -> text as before; any other type is mapped by name
        assert(PostgresSql.addColumnType(f("string", "_json")) == "text")
        assert(PostgresSql.addColumnType(f(evil, "_json")) == "json")
        assert(PostgresSql.addColumnType(f(evil, "_xml")) == "xml")
        for (t <- failing :+ null) assert(Try(PostgresSql.addColumnType(f(t))).isFailure, "[" + t + "]")
    }

    test("each engine's type function sends a stored type with tabs or line breaks in the accepted places with them removed") {
        for ((t, sent) <- Seq("decimal(10,\t2)" -> "decimal(10,2)", "decimal(10,2)\n" -> "decimal(10,2)", "varchar( 50 )\r\n" -> "varchar(50)")) {
            assert(PostgresSql.columnType(f(t)) == sent, "postgres " + t)
            assert(PostgresSql.addColumnType(f(t)) == sent, "postgres add " + t)
            assert(SnowflakeLoader.snowflakeType(f(t)) == sent, "snowflake " + t)
            assert(DatabricksLoader.databricksType(f(t)) == sent, "databricks " + t)
            assert(SqlServerLoader.sqlServerType(f(t)) == sent, "sqlserver " + t)
        }
        for (t <- Seq("decimal(10,2) /* x */", "varchar(10) not null", evil)) {
            assert(Try(PostgresSql.columnType(f(t))).isFailure, t)
            assert(Try(SnowflakeLoader.snowflakeType(f(t))).isFailure, t)
            assert(Try(DatabricksLoader.databricksType(f(t))).isFailure, t)
            assert(Try(SqlServerLoader.sqlServerType(f(t))).isFailure, t)
        }
    }

    // ---------------------------------------- load plans (create, then evolve)

    private val ok = Seq(f("int", "id"), f("decimal(10,2)", "amount"), f("string", "note"))
    private val odd = Seq(f("int", "id"), f("varchar(10) not null", "b"), f("string", "note"))

    test("Postgres: no unsafe field gives the v1.60.0 statements in the v1.60.0 order") {
        val keys = java.util.Arrays.asList("id")
        val c = PostgresSql.createPlan("datris", "public", "orders", ok, keys)
        assert(c.schemaSql.contains("CREATE SCHEMA IF NOT EXISTS \"public\";"))
        assert(
            c.tableSql.contains(
                "create table if not exists \"datris\".\"public\".\"orders\" (\"id\" int, \"amount\" decimal(10,2), \"note\" text, primary key (id));"
            ),
            c.toString
        )
        // table absent: every column is added (IF NOT EXISTS), in field order
        assert(
            PostgresSql.evolvePlan("datris", "public", "orders", ok, Set.empty) == Right(
                ColumnTypeText.EvolvePlan(
                    Seq(
                        "ALTER TABLE \"datris\".\"public\".\"orders\" ADD COLUMN IF NOT EXISTS \"id\" int",
                        "ALTER TABLE \"datris\".\"public\".\"orders\" ADD COLUMN IF NOT EXISTS \"amount\" decimal(10,2)",
                        "ALTER TABLE \"datris\".\"public\".\"orders\" ADD COLUMN IF NOT EXISTS \"note\" text"
                    ),
                    None
                )
            )
        )
        assert(PostgresSql.evolvePlan("datris", "public", "orders", ok, Set("id", "amount", "note")) == Right(ColumnTypeText.EvolvePlan(Nil, None)))
    }

    test("Postgres: an unsafe field whose column exists gives no create, only the missing columns and the status line; otherwise the problem") {
        assert(PostgresSql.createPlan("datris", "public", "orders", odd, null) == ColumnTypeText.CreatePlan(None, None))
        val kept = PostgresSql.evolvePlan("datris", "public", "orders", odd, Set("id", "b"))
        assert(kept.isRight, kept.toString)
        assert(kept.right.get.addColumns == Seq("ALTER TABLE \"datris\".\"public\".\"orders\" ADD COLUMN IF NOT EXISTS \"note\" text"))
        assert(kept.right.get.statusLine.exists(l => l.contains("b") && l.contains("was not used")), kept.toString)
        for (existing <- Seq(Set.empty[String], Set("id", "note"))) {
            val refused = PostgresSql.evolvePlan("datris", "public", "orders", odd, existing)
            assert(refused.isLeft && refused.left.get.contains("Invalid field type passed") && refused.left.get.contains("'b'"), refused.toString)
        }
    }

    test("Snowflake: same statements as v1.60.0 without an unsafe field; no create and only missing columns with one; the problem otherwise") {
        val c = SnowflakeLoader.createPlan(sfDb, ok)
        assert(c.schemaSql.contains("CREATE SCHEMA IF NOT EXISTS datris.public"), c.toString)
        assert(c.tableSql.contains("CREATE TABLE IF NOT EXISTS datris.public.orders (id NUMBER(38,0), amount decimal(10,2), note VARCHAR, PRIMARY KEY (id))"))
        assert(
            SnowflakeLoader.evolvePlan(sfDb, ok, Set("id")) ==
                Right(
                    ColumnTypeText.EvolvePlan(
                        Seq(
                            "ALTER TABLE datris.public.orders ADD COLUMN IF NOT EXISTS amount decimal(10,2)",
                            "ALTER TABLE datris.public.orders ADD COLUMN IF NOT EXISTS note VARCHAR"
                        ),
                        None
                    )
                )
        )
        assert(SnowflakeLoader.createPlan(sfDb, odd) == ColumnTypeText.CreatePlan(None, None))
        val kept = SnowflakeLoader.evolvePlan(sfDb, odd, Set("id", "b"))
        assert(kept.isRight && kept.right.get.addColumns == Seq("ALTER TABLE datris.public.orders ADD COLUMN IF NOT EXISTS note VARCHAR"), kept.toString)
        assert(kept.right.get.statusLine.isDefined)
        assert(SnowflakeLoader.evolvePlan(sfDb, odd, Set.empty).left.exists(_.contains("'b'")))
    }

    test(
        "SQL Server: same statements as v1.60.0 without an unsafe field (a null type is string); no create and only missing columns with one; the problem otherwise"
    ) {
        val withNull = ok :+ f(null, "extra")
        val c = SqlServerLoader.createPlan(msDb, withNull)
        assert(c.schemaSql.contains("IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = N'dbo') EXEC(N'CREATE SCHEMA dbo')"), c.toString)
        assert(
            c.tableSql.contains(
                "IF NOT EXISTS (SELECT 1 FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = N'dbo' AND t.name = N'orders')" +
                    " CREATE TABLE dbo.orders (id INT, amount decimal(10,2), note NVARCHAR(MAX), extra NVARCHAR(MAX))"
            ),
            c.toString
        )
        assert(
            SqlServerLoader.evolvePlan(msDb, withNull, Set("id", "amount")) ==
                Right(ColumnTypeText.EvolvePlan(Seq("ALTER TABLE dbo.orders ADD note NVARCHAR(MAX)", "ALTER TABLE dbo.orders ADD extra NVARCHAR(MAX)"), None))
        )
        assert(SqlServerLoader.createPlan(msDb, odd) == ColumnTypeText.CreatePlan(None, None))
        val kept = SqlServerLoader.evolvePlan(msDb, odd, Set("id", "b"))
        assert(kept.isRight && kept.right.get.addColumns == Seq("ALTER TABLE dbo.orders ADD note NVARCHAR(MAX)"), kept.toString)
        assert(kept.right.get.statusLine.isDefined)
        assert(SqlServerLoader.evolvePlan(msDb, odd, Set("id")).left.exists(_.contains("'b'")))
    }

    test("Databricks: the up-front decision refuses an unsafe type whatever manageTableManually says, and passes loadable ones") {
        import ai.datris.model.{Destination, PipelineConfig, SchemaProperties}
        def cfg(fs: Seq[SchemaField], manual: Boolean): PipelineConfig =
            PipelineConfig(
                name = "dbx",
                destination = Destination(
                    schemaProperties = SchemaProperties(null, java.util.Arrays.asList(fs: _*)),
                    database = dbxDb.copy(manageTableManually = manual)
                )
            )
        for (manual <- Seq(false, true)) {
            assert(DatabricksLoader.loadProblem(cfg(odd, manual)).exists(_.contains("'b'")), "manual=" + manual)
            assert(DatabricksLoader.loadProblem(cfg(ok :+ f("decimal(10,\t2)", "t") :+ f(evil, "_json"), manual)).isEmpty, "manual=" + manual)
        }
    }
}
