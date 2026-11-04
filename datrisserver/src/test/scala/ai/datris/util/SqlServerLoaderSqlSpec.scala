package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, SchemaField}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Microsoft SQL Server destination (plans/stories/mssql-destination.md),
  * Acceptance bullet 3 and Step 4. Pure string assertions, no server.
  *
  * API the story names: `SqlServerConnectionUtil.ident` (bracket-quote
  * non-simple names) and `SqlServerConnectionUtil.qualifiedTable(db)`
  * (`schema.table`, dbName goes in the URL, not the table reference).
  *
  * The loader's statement builders are private in SnowflakeLoader; for SQL
  * Server the spec pins them on the companion so they can be read offline:
  *
  * {{{
  * object SqlServerLoader {
  *     def createTableSql(db: Database, fields: Seq[SchemaField]): String
  *     def mergeSql(targetRef: String, stagingRef: String, columns: Seq[String], keyFields: Seq[String]): String
  * }
  * }}}
  *
  * Type map (Step 4, mirrors PostgresLoader.createTableIfUndefined):
  * `_json` -> NVARCHAR(MAX) + CHECK (ISJSON(...) = 1), `_xml` -> XML,
  * string -> NVARCHAR(MAX), int/smallint/tinyint -> INT, bigint -> BIGINT,
  * float/double -> FLOAT, boolean -> BIT, date -> DATE,
  * timestamp -> DATETIME2, unknown passes through.
  */
class SqlServerLoaderSqlSpec extends AnyFunSuite {

    private val db = Database(dbName = "datris", schema = "dbo", table = "orders", useTransaction = true)

    /** `name TYPE` in the DDL, name optionally bracket-quoted. */
    private def colDef(ddl: String, name: String, sqlType: String): Boolean = {
        val n = java.util.regex.Pattern.quote(name)
        val t = java.util.regex.Pattern.quote(sqlType)
        ("""(?is)(^|[\s(,])\[?""" + n + """\]?\s+""" + t + """(?![A-Za-z0-9_])""").r.findFirstIn(ddl).isDefined
    }

    // ---- ident / qualifiedTable --------------------------------------------

    test("ident leaves simple names unquoted and bracket-quotes the rest") {
        assert(SqlServerConnectionUtil.ident("orders") == "orders")
        assert(SqlServerConnectionUtil.ident("_json") == "_json")
        assert(SqlServerConnectionUtil.ident("my-table") == "[my-table]")
        assert(SqlServerConnectionUtil.ident("order details") == "[order details]")
        assert(SqlServerConnectionUtil.ident("a]b") == "[a]]b]", "a closing bracket is escaped by doubling")
    }

    test("qualifiedTable is schema.table with dbName left to the URL") {
        assert(SqlServerConnectionUtil.qualifiedTable(db) == "dbo.orders")
        assert(SqlServerConnectionUtil.qualifiedTable(db.copy(table = "my-table")) == "dbo.[my-table]")
        assert(!SqlServerConnectionUtil.qualifiedTable(db).contains("datris"))
    }

    // ---- DDL ---------------------------------------------------------------

    test("DDL maps every platform type as listed in Step 4") {
        val fields = Seq(
            SchemaField("s", "string"),
            SchemaField("i", "int"),
            SchemaField("si", "smallint"),
            SchemaField("ti", "tinyint"),
            SchemaField("bi", "bigint"),
            SchemaField("f", "float"),
            SchemaField("d", "double"),
            SchemaField("b", "boolean"),
            SchemaField("dt", "date"),
            SchemaField("ts", "timestamp"),
            SchemaField("amt", "decimal(10,2)")
        )
        val ddl = SqlServerLoader.createTableSql(db, fields)
        val expected = Seq(
            "s" -> "NVARCHAR(MAX)",
            "i" -> "INT",
            "si" -> "INT",
            "ti" -> "INT",
            "bi" -> "BIGINT",
            "f" -> "FLOAT",
            "d" -> "FLOAT",
            "b" -> "BIT",
            "dt" -> "DATE",
            "ts" -> "DATETIME2",
            "amt" -> "decimal(10,2)"
        )
        expected.foreach { case (n, t) => assert(colDef(ddl, n, t), s"expected column '$n $t' in:\n$ddl") }
        assert(ddl.contains("dbo.orders"), ddl)
    }

    test("DDL creates the table only when undefined, via sys.tables") {
        val ddl = SqlServerLoader.createTableSql(db, Seq(SchemaField("id", "string")))
        assert("""(?is)IF\s+NOT\s+EXISTS\s*\(\s*SELECT\s+1\s+FROM\s+sys\.tables""".r.findFirstIn(ddl).isDefined, ddl)
        assert("""(?is)CREATE\s+TABLE\s+dbo\.orders""".r.findFirstIn(ddl).isDefined, ddl)
    }

    test("DDL: _json is NVARCHAR(MAX) with an ISJSON check") {
        val ddl = SqlServerLoader.createTableSql(db, Seq(SchemaField("_json", "string")))
        assert(colDef(ddl, "_json", "NVARCHAR(MAX)"), ddl)
        assert("""(?is)CHECK\s*\(\s*ISJSON\s*\(\s*\[?_json\]?\s*\)\s*=\s*1\s*\)""".r.findFirstIn(ddl).isDefined, s"ISJSON check missing:\n$ddl")
    }

    test("DDL: _xml is XML") {
        val ddl = SqlServerLoader.createTableSql(db, Seq(SchemaField("_xml", "string")))
        assert(colDef(ddl, "_xml", "XML"), ddl)
        assert(!colDef(ddl, "_xml", "NVARCHAR(MAX)"), ddl)
        assert(!ddl.toUpperCase.contains("ISJSON"), ddl)
    }

    test("DDL bracket-quotes a hyphenated table name") {
        val ddl = SqlServerLoader.createTableSql(db.copy(table = "my-table"), Seq(SchemaField("id", "string")))
        assert(ddl.contains("[my-table]"), ddl)
    }

    // ---- MERGE -------------------------------------------------------------

    private def updateSetClause(merge: String): String = {
        val m = """(?is)WHEN\s+MATCHED\s+THEN\s+UPDATE\s+SET\s+(.*?)\s+WHEN\s+NOT\s+MATCHED""".r
        m.findFirstMatchIn(merge).map(_.group(1)).getOrElse(fail(s"no UPDATE SET clause before WHEN NOT MATCHED:\n$merge"))
    }

    test("MERGE names only non-key columns in UPDATE SET and inserts every column") {
        val merge = SqlServerLoader.mergeSql("dbo.orders", "#DATRIS_STG_x", Seq("id", "name", "amount"), Seq("id"))
        assert("""(?is)^\s*MERGE\s+(INTO\s+)?dbo\.orders\b""".r.findFirstIn(merge).isDefined, merge)
        assert(merge.contains("#DATRIS_STG_x"), merge)
        val set = updateSetClause(merge)
        assert("""\bname\b""".r.findFirstIn(set).isDefined, set)
        assert("""\bamount\b""".r.findFirstIn(set).isDefined, set)
        assert("""\bid\b""".r.findFirstIn(set).isEmpty, s"key column in UPDATE SET: $set")
        val insert = """(?is)WHEN\s+NOT\s+MATCHED\s+(BY\s+TARGET\s+)?THEN\s+INSERT\s*\((.*?)\)""".r
            .findFirstMatchIn(merge).map(_.group(2)).getOrElse(fail(s"no INSERT clause:\n$merge"))
        Seq("id", "name", "amount").foreach(c => assert(("""\b""" + c + """\b""").r.findFirstIn(insert).isDefined, s"$c missing from INSERT: $insert"))
        assert(merge.trim.endsWith(";"), "SQL Server requires MERGE to be terminated by a semicolon")
    }

    test("MERGE key match is case-insensitive and composite keys are ANDed") {
        val merge = SqlServerLoader.mergeSql("dbo.orders", "#s", Seq("Region", "ID", "qty"), Seq("id", "region"))
        val set = updateSetClause(merge)
        assert("""\bqty\b""".r.findFirstIn(set).isDefined, set)
        assert("""(?i)\b(id|region)\b""".r.findFirstIn(set).isEmpty, s"key column in UPDATE SET: $set")
        assert("""(?is)\bON\b.*\bAND\b""".r.findFirstIn(merge).isDefined, merge)
    }

    test("MERGE with only key columns has no UPDATE SET") {
        val merge = SqlServerLoader.mergeSql("dbo.orders", "#s", Seq("id"), Seq("id"))
        assert(!merge.toUpperCase.contains("UPDATE SET"), merge)
        assert("""(?is)WHEN\s+NOT\s+MATCHED""".r.findFirstIn(merge).isDefined, merge)
    }

    test("MERGE bracket-quotes non-simple column names") {
        val merge = SqlServerLoader.mergeSql("dbo.[my-table]", "#s", Seq("id", "unit price"), Seq("id"))
        assert(updateSetClause(merge).contains("[unit price]"), merge)
    }
}
