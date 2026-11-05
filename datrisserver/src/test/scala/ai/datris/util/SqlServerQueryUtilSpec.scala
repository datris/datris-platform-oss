package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Database, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

/** Story: SQL Server read-back and on-demand typing
  * (plans/stories/mssql-query-and-typing.md), Acceptance bullet 3, plus the
  * offline half of bullet 4 (the default "preview the destination table"
  * query). Pure string assertions, no server, no database.
  *
  * The story names `SqlServerQueryUtil` (mirrors SnowflakeQueryUtil) but not
  * the signatures of its helpers. SnowflakeQueryUtil keeps them private; this
  * spec pins them as package-visible so they can be checked offline:
  *
  * {{{
  * object SqlServerQueryUtil {
  *     private[util] def validateQuery(sql: String): Unit          // throws DatrisException
  *     private[util] def applyLimit(sql: String, limit: Int): String
  *     private[util] def defaultQuery(db: Database): String
  * }
  * }}}
  *
  * Limit rule (Step 1): `OFFSET 0 ROWS FETCH NEXT <limit> ROWS ONLY` is
  * appended only when the statement has an `ORDER BY`; otherwise the statement
  * is wrapped as `SELECT TOP (<limit>) * FROM (<sql>) q`.
  */
class SqlServerQueryUtilSpec extends AnyFunSuite {

    private def norm(s: String): String = s.trim.replaceAll("\\s+", " ")

    // ---- read-only allowlist ----

    test("DELETE FROM t is rejected with a message naming the SELECT/WITH allowlist") {
        val e = intercept[DatrisException](SqlServerQueryUtil.validateQuery("DELETE FROM t"))
        val msg = e.getMessage.toUpperCase(java.util.Locale.ROOT)
        assert(msg.contains("SELECT") && msg.contains("WITH"), e.getMessage)
    }

    test("other write/DDL/exec statements are rejected") {
        Seq(
            "UPDATE t SET a = 1",
            "INSERT INTO t VALUES (1)",
            "DROP TABLE t",
            "EXEC sp_who",
            "TRUNCATE TABLE t",
            "MERGE t USING s ON 1 = 1 WHEN MATCHED THEN DELETE"
        ).foreach { sql =>
            assertThrows[DatrisException](SqlServerQueryUtil.validateQuery(sql))
        }
    }

    test("a SELECT that smuggles a write is rejected (stacked, SELECT INTO, comment)") {
        assertThrows[DatrisException](SqlServerQueryUtil.validateQuery("SELECT 1; DROP TABLE t"))
        // T-SQL SELECT ... INTO creates a table: not read-only.
        assertThrows[DatrisException](SqlServerQueryUtil.validateQuery("SELECT * INTO t_copy FROM t"))
        assertThrows[DatrisException](SqlServerQueryUtil.validateQuery("SELECT * FROM t -- ; DROP TABLE t"))
    }

    test("SELECT, WITH, and INFORMATION_SCHEMA metadata queries are allowed") {
        SqlServerQueryUtil.validateQuery("SELECT * FROM t")
        SqlServerQueryUtil.validateQuery("select id from dbo.orders")
        SqlServerQueryUtil.validateQuery("WITH c AS (SELECT id FROM t) SELECT * FROM c")
        SqlServerQueryUtil.validateQuery(
            "SELECT TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
        )
        SqlServerQueryUtil.validateQuery(
            "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'orders'"
        )
    }

    // ---- limit injection ----

    test("SELECT * FROM t gets a TOP wrapper") {
        assert(norm(SqlServerQueryUtil.applyLimit("SELECT * FROM t", 100)) == "SELECT TOP (100) * FROM (SELECT * FROM t) q")
    }

    test("SELECT ... ORDER BY id gets OFFSET/FETCH, not a TOP wrapper") {
        val out = norm(SqlServerQueryUtil.applyLimit("SELECT id, amount FROM t ORDER BY id", 50))
        assert(out == "SELECT id, amount FROM t ORDER BY id OFFSET 0 ROWS FETCH NEXT 50 ROWS ONLY", out)
        assert(!out.toUpperCase(java.util.Locale.ROOT).contains("TOP ("), out)
    }

    test("ORDER BY detection is case-insensitive") {
        val out = norm(SqlServerQueryUtil.applyLimit("select id from t order by id desc", 10))
        assert(out.toUpperCase(java.util.Locale.ROOT).endsWith("OFFSET 0 ROWS FETCH NEXT 10 ROWS ONLY"), out)
    }

    // ---- limit injection: CTEs and nested ORDER BY ----

    test("a WITH statement keeps its CTEs in front and wraps only the final statement") {
        val out = norm(SqlServerQueryUtil.applyLimit("WITH c AS (SELECT id FROM t) SELECT * FROM c", 10))
        assert(out == "WITH c AS (SELECT id FROM t) SELECT TOP (10) * FROM (SELECT * FROM c) q", out)
    }

    test("a WITH statement with several CTEs and a UNION wraps the whole final statement") {
        val sql = "WITH a AS (SELECT id FROM t), b AS (SELECT id FROM u) SELECT id FROM a UNION ALL SELECT id FROM b"
        val out = norm(SqlServerQueryUtil.applyLimit(sql, 5))
        assert(
            out == "WITH a AS (SELECT id FROM t), b AS (SELECT id FROM u) SELECT TOP (5) * FROM (SELECT id FROM a UNION ALL SELECT id FROM b) q",
            out
        )
    }

    test("a WITH statement with a top-level ORDER BY gets OFFSET/FETCH") {
        val out = norm(SqlServerQueryUtil.applyLimit("WITH c AS (SELECT id FROM t) SELECT * FROM c ORDER BY id", 7))
        assert(out == "WITH c AS (SELECT id FROM t) SELECT * FROM c ORDER BY id OFFSET 0 ROWS FETCH NEXT 7 ROWS ONLY", out)
    }

    test("ORDER BY inside OVER (...) or a CTE body does not count as top-level") {
        val w = norm(SqlServerQueryUtil.applyLimit("SELECT id, ROW_NUMBER() OVER (ORDER BY id) rn FROM t", 3))
        assert(w == "SELECT TOP (3) * FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY id) rn FROM t) q", w)
        val c = norm(SqlServerQueryUtil.applyLimit("WITH c AS (SELECT TOP 5 id FROM t ORDER BY id) SELECT * FROM c", 3))
        assert(c == "WITH c AS (SELECT TOP 5 id FROM t ORDER BY id) SELECT TOP (3) * FROM (SELECT * FROM c) q", c)
    }

    test("ORDER BY inside a string literal or bracketed name is ignored; an existing OFFSET is left alone") {
        val lit = norm(SqlServerQueryUtil.applyLimit("SELECT * FROM t WHERE note = 'order by x'", 2))
        assert(lit == "SELECT TOP (2) * FROM (SELECT * FROM t WHERE note = 'order by x') q", lit)
        val paged = "SELECT id FROM t ORDER BY id OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY"
        assert(norm(SqlServerQueryUtil.applyLimit(paged, 100)) == paged)
    }

    test("a trailing semicolon is dropped before the limit is applied") {
        assert(norm(SqlServerQueryUtil.applyLimit("SELECT * FROM t;", 4)) == "SELECT TOP (4) * FROM (SELECT * FROM t) q")
    }

    // ---- review fixes ----

    test("SELECT TOP ... ORDER BY is wrapped, not given OFFSET/FETCH (TOP and OFFSET cannot mix)") {
        val out = norm(SqlServerQueryUtil.applyLimit("SELECT TOP 10 * FROM t ORDER BY id", 100))
        assert(out == "SELECT TOP (100) * FROM (SELECT TOP 10 * FROM t ORDER BY id) q", out)
        val cte = norm(SqlServerQueryUtil.applyLimit("WITH c AS (SELECT id FROM t) SELECT TOP 5 id FROM c ORDER BY id", 3))
        assert(cte == "WITH c AS (SELECT id FROM t) SELECT TOP (3) * FROM (SELECT TOP 5 id FROM c ORDER BY id) q", cte)
    }

    test("a second statement stacked without a semicolon is rejected") {
        Seq(
            "SELECT 1 DISABLE TRIGGER ALL ON dbo.orders",
            "SELECT 1 ENABLE TRIGGER ALL ON dbo.orders",
            "SELECT 1 SETUSER 'dbo'",
            "SELECT 1 REVERT",
            "SELECT 1 WRITETEXT t.c @p 'x'",
            "SELECT 1 UPDATETEXT t.c @p 0 NULL 'x'",
            "SELECT 1 DELETE FROM t"
        ).foreach { sql =>
            assertThrows[DatrisException](SqlServerQueryUtil.validateQuery(sql))
        }
    }

    test("T-SQL-specific blocked words are rejected") {
        Seq(
            "SELECT 1 WAITFOR DELAY '00:00:05'",
            "SELECT * FROM OPENROWSET('SQLNCLI', 'Server=x', 'SELECT 1') r",
            "WITH c AS (SELECT 1 AS a) SELECT * FROM c DECLARE @x INT"
        ).foreach { sql =>
            val e = intercept[DatrisException](SqlServerQueryUtil.validateQuery(sql))
            assert(e.getMessage.contains("blocked keyword"), e.getMessage)
        }
    }

    // ---- default query (no sql) ----

    test("with no sql, the default query previews the pipeline's destination table") {
        val db = Database(dbName = "datris", schema = "dbo", table = "orders", useSqlServer = true)
        val q = norm(SqlServerQueryUtil.defaultQuery(db))
        assert(q == "SELECT * FROM " + SqlServerConnectionUtil.qualifiedTable(db), q)
        // and it passes the allowlist itself
        SqlServerQueryUtil.validateQuery(q)
    }
}
