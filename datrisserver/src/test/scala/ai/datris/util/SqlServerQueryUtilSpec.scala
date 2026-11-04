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

    // ---- default query (no sql) ----

    test("with no sql, the default query previews the pipeline's destination table") {
        val db = Database(dbName = "datris", schema = "dbo", table = "orders", useSqlServer = true)
        val q = norm(SqlServerQueryUtil.defaultQuery(db))
        assert(q == "SELECT * FROM " + SqlServerConnectionUtil.qualifiedTable(db), q)
        // and it passes the allowlist itself
        SqlServerQueryUtil.validateQuery(q)
    }
}
