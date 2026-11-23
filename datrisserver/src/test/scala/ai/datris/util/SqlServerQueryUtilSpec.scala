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

    // =====================================================================
    // Story: SQL Server, Snowflake and Databricks queries: one statement, no
    // functions that act, and a time limit on every query
    // (plans/stories/query-sqlserver-snowflake-databricks-side-effects.md),
    // the SqlServerQueryUtilSpec half of Acceptance. Every case above this
    // line is unedited (Acceptance bullet 6).
    //
    // `validateQuery` runs in `query` before the limit is looked at, so a
    // refusal there holds for `limit: -1` and for a positive limit alike. The
    // new pure check `singleStatementProblem(sql): Option[String]` and the
    // timeout and rollback in `run(conn, sql, unlimited, effectiveLimit)` are
    // reached by name (QuerySafetyReflect) so this file compiles before they
    // exist.
    // =====================================================================

    private val OneStatement = "Only one statement is allowed in a query"

    private val RefusedWords = Seq(
        "SET",
        "BEGIN",
        "COMMIT",
        "ROLLBACK",
        "SAVE",
        "WHILE",
        "IF",
        "GOTO",
        "PRINT",
        "RAISERROR",
        "CHECKPOINT",
        "OPEN",
        "CLOSE",
        "DEALLOCATE",
        "RETURN",
        "BREAK",
        "CONTINUE",
        "READTEXT"
    )

    private def problem(sql: String): Option[String] =
        QuerySafetyReflect.call(SqlServerQueryUtil, "singleStatementProblem", sql).asInstanceOf[Option[String]]

    private def refusal(sql: String): String = {
        val e = intercept[DatrisException](SqlServerQueryUtil.validateQuery(sql))
        e.getMessage
    }

    private def isKnownRefusal(msg: String): Boolean =
        msg == OneStatement || RefusedWords.exists(w => msg == "Query contains blocked keyword: " + w) ||
            msg == "Query contains blocked keyword: NEXT VALUE FOR"

    private def accepted(sql: String): Unit = {
        SqlServerQueryUtil.validateQuery(sql)
        assert(problem(sql).isEmpty, sql)
    }

    test("a second statement without a semicolon is refused for limit -1 and for a positive limit") {
        val m = refusal("SELECT 1 SELECT 2")
        assert(m == OneStatement, m)
        assert(refusal("SELECT a FROM t SELECT b FROM u") == OneStatement)
        assert(refusal("WITH c AS (SELECT 1 AS a) SELECT a FROM c SELECT 2") == OneStatement)
        assert(refusal("SELECT a FROM t UNION SELECT a FROM u SELECT b FROM v") == OneStatement)
        Seq(
            "SELECT 1 WHILE 1=1 SELECT 1",
            "SELECT 1 SELECT 2",
            "SELECT a FROM t ORDER BY a SET NOCOUNT ON",
            "select 1 while 1=1 select 1"
        ).foreach { sql =>
            val msg = refusal(sql)
            assert(isKnownRefusal(msg), sql + " -> " + msg)
            assert(problem(sql).isDefined, sql)
        }
    }

    test("a closing parenthesis that would end the TOP wrapper is refused") {
        Seq(
            "SELECT 1 x) q PRINT 1 SELECT * FROM (SELECT 2 y",
            // the same shape with no refused word in it: only the scan catches it
            "SELECT a FROM t) q SELECT * FROM (SELECT b FROM u",
            "SELECT a FROM t WHERE (b = 1))"
        ).foreach { sql =>
            intercept[DatrisException](SqlServerQueryUtil.validateQuery(sql))
            assert(problem(sql).isDefined, sql)
        }
    }

    test("an unterminated quote, bracket or double-quoted identifier is refused (Decision 1, the scan behind bullets 1-2)") {
        Seq(
            "SELECT 'abc FROM t",
            "SELECT [abc FROM t",
            "SELECT \"abc FROM t",
            "SELECT a FROM t WHERE b = 'x'' SELECT 2"
        ).foreach { sql =>
            intercept[DatrisException](SqlServerQueryUtil.validateQuery(sql))
            assert(problem(sql).isDefined, sql)
        }
    }

    test("UNION, UNION ALL, EXCEPT and INTERSECT keep their second SELECT; a subquery, a CTE and CASE ... END are accepted") {
        Seq(
            "SELECT a FROM t UNION SELECT a FROM u",
            "SELECT a FROM t UNION ALL SELECT a FROM u",
            "SELECT a FROM t EXCEPT SELECT a FROM u",
            "SELECT a FROM t INTERSECT SELECT a FROM u",
            "select a from t union all select a from u except select a from v",
            "SELECT a FROM t UNION ALL (SELECT a FROM u)",
            "SELECT * FROM (SELECT a FROM t) s WHERE s.a IN (SELECT a FROM u)",
            "SELECT (SELECT MAX(a) FROM u) AS m FROM t",
            "SELECT a FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.a = t.a)",
            "WITH c AS (SELECT a FROM t) SELECT a FROM c",
            "WITH a AS (SELECT id FROM t), b AS (SELECT id FROM u) SELECT id FROM a UNION ALL SELECT id FROM b",
            "SELECT CASE WHEN a > 1 THEN 'big' ELSE 'small' END AS size FROM t",
            "SELECT CASE a WHEN 1 THEN 'x' END AS k FROM t ORDER BY a",
            "SELECT id, ROW_NUMBER() OVER (ORDER BY id) rn FROM t"
        ).foreach(accepted)
    }

    test("END, FETCH and the non-reserved THROW, RECEIVE, SEND, GET, MOVE are not refused as words (Decision 2)") {
        Seq(
            "SELECT CASE WHEN a = 1 THEN 1 END FROM t",
            "SELECT get, move, send, receive, throw FROM t",
            "SELECT id FROM t ORDER BY id OFFSET 10 ROWS FETCH FIRST 5 ROWS ONLY"
        ).foreach(accepted)
    }

    test("each Decision 2 word outside literals and quoted identifiers is refused, by whole word only") {
        RefusedWords.foreach { w =>
            Seq("SELECT a FROM t " + w + " x", "SELECT a FROM t WHERE b = 1 " + w.toLowerCase(java.util.Locale.ROOT) + " 1").foreach { sql =>
                val msg = refusal(sql)
                assert(msg == "Query contains blocked keyword: " + w || msg == OneStatement, sql + " -> " + msg)
                assert(problem(sql).isDefined, sql)
            }
        }
        // a refused word as part of a longer name is not the word
        accepted("SELECT settings, closed_at, returned, if_flag, begin_ts FROM t")
    }

    test("a refused word inside a string literal, a bracketed or a double-quoted identifier is accepted") {
        Seq(
            "SELECT * FROM t WHERE note = 'set if while'",
            "SELECT [set] FROM t",
            "SELECT \"while\", [begin], [commit] FROM t",
            // the documented SQL Server example (docs/api-reference/query-api.mdx)
            "SELECT symbol, [close] FROM dbo.stock_price WHERE volume > 1000000",
            "SELECT * FROM t WHERE note = 'it''s a print, goto or rollback'",
            "SELECT [a]]set] FROM t",
            "SELECT \"x\"\"if\" FROM t",
            "SELECT * FROM t WHERE note = 'next value for s'",
            "SELECT [next value for] FROM t",
            // a parenthesis or SELECT inside a literal or identifier is not structure
            "SELECT 'a) q SELECT 1' AS s, [x)] FROM t",
            "SELECT \"select\" FROM t WHERE c = '(('"
        ).foreach(accepted)
    }

    test("NEXT VALUE FOR is refused; OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY is accepted") {
        Seq(
            "SELECT NEXT VALUE FOR dbo.seq",
            "select next value for s AS n",
            "SELECT NEXT  VALUE\tFOR s",
            "SELECT a, NEXT VALUE FOR s OVER (ORDER BY a) AS n FROM t"
        ).foreach { sql =>
            val msg = refusal(sql)
            assert(msg.contains("blocked keyword") && msg.toUpperCase(java.util.Locale.ROOT).contains("NEXT VALUE FOR"), sql + " -> " + msg)
            assert(problem(sql).isDefined, sql)
        }
        accepted("SELECT id FROM t ORDER BY id OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY")
        accepted("SELECT next_value, value_for FROM t")
    }

    // ---- run: time limit and rollback, on a recording connection ----

    /** Records every call on the connection and the statement it hands out,
      * as PostgresReadOnlyQuerySpec does. `failOn`: "execute" or "next". */
    private final class Recorder(failOn: Set[String] = Set.empty, rows: Int = 2) {
        import java.lang.reflect.{InvocationHandler, Method, Proxy}
        import java.sql.{Connection, PreparedStatement, ResultSet, ResultSetMetaData, SQLException, Statement}

        val calls = scala.collection.mutable.ArrayBuffer[String]()
        var autoCommit = true

        private def zero(m: Method): AnyRef = {
            val t = m.getReturnType
            if (t == java.lang.Boolean.TYPE) java.lang.Boolean.FALSE
            else if (t == java.lang.Integer.TYPE) Integer.valueOf(0)
            else if (t == java.lang.Long.TYPE) java.lang.Long.valueOf(0L)
            else null
        }

        private def proxy[T](cls: Class[T])(h: (Method, Array[AnyRef]) => AnyRef): T =
            Proxy.newProxyInstance(
                getClass.getClassLoader,
                Array[Class[_]](cls),
                new InvocationHandler {
                    def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef = m.getName match {
                        case "hashCode" => Integer.valueOf(System.identityHashCode(p))
                        case "equals" => java.lang.Boolean.valueOf(p.asInstanceOf[AnyRef] eq args(0))
                        case "toString" => cls.getSimpleName + "(recording)"
                        case _ => h(m, Option(args).getOrElse(Array.empty))
                    }
                }
            ).asInstanceOf[T]

        private def resultSet(): ResultSet = {
            var left = rows
            val meta = proxy(classOf[ResultSetMetaData]) { (m, _) =>
                m.getName match {
                    case "getColumnCount" => Integer.valueOf(1)
                    case "getColumnLabel" | "getColumnName" => "v"
                    case _ => zero(m)
                }
            }
            proxy(classOf[ResultSet]) { (m, _) =>
                m.getName match {
                    case "getMetaData" => meta
                    case "next" =>
                        if (failOn("next")) throw new SQLException("result read failed")
                        left -= 1; java.lang.Boolean.valueOf(left >= 0)
                    case "getObject" => Integer.valueOf(left)
                    case "close" => calls += "rs.close"; null
                    case _ => zero(m)
                }
            }
        }

        private def statement[T](cls: Class[T], kind: String): T = proxy(cls) { (m, a) =>
            m.getName match {
                case "executeQuery" =>
                    calls += s"$kind.executeQuery" + a.headOption.map(":" + _).getOrElse("")
                    if (failOn("execute")) throw new SQLException("statement failed")
                    resultSet()
                case "execute" =>
                    calls += s"$kind.execute:" + a.headOption.getOrElse("")
                    java.lang.Boolean.FALSE
                case "setQueryTimeout" => calls += s"$kind.setQueryTimeout:${a(0)}"; null
                case "setMaxRows" => calls += s"$kind.setMaxRows:${a(0)}"; null
                case "close" => calls += s"$kind.close"; null
                case _ => zero(m)
            }
        }

        val conn: Connection = proxy(classOf[Connection]) { (m, a) =>
            m.getName match {
                case "setAutoCommit" =>
                    autoCommit = a(0).asInstanceOf[Boolean]; calls += s"setAutoCommit:$autoCommit"; null
                case "getAutoCommit" => java.lang.Boolean.valueOf(autoCommit)
                case "rollback" => calls += "rollback"; null
                case "commit" => calls += "commit"; null
                case "createStatement" => calls += "createStatement"; statement(classOf[Statement], "stmt")
                case "prepareStatement" => calls += s"prepareStatement:${a(0)}"; statement(classOf[PreparedStatement], "ps")
                case "setTransactionIsolation" => calls += s"setTransactionIsolation:${a(0)}"; null
                case "getTransactionIsolation" => Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
                case _ => zero(m)
            }
        }

        def at(c: String): Int = calls.indexOf(c)
        def query: Int = calls.indexWhere(c => c.startsWith("stmt.executeQuery") || c.startsWith("ps.executeQuery"))
        def timeouts: Seq[String] = calls.filter(c => c.startsWith("stmt.setQueryTimeout:") || c.startsWith("ps.setQueryTimeout:")).toSeq
    }

    /** `SqlServerQueryUtil.run(conn, sql, unlimited, effectiveLimit)`, found by
      * name. Trailing parameters are filled by type: a Boolean gets
      * `unlimited`, an Int the effective limit (-1 when unlimited, as `query`
      * passes it today). */
    private def run(conn: java.sql.Connection, sql: String, unlimited: Boolean, limit: Int): AnyRef = {
        val candidates = QuerySafetyReflect.methods(SqlServerQueryUtil, "run").filter { m =>
            val p = m.getParameterTypes
            p.length >= 2 && p(0) == classOf[java.sql.Connection] && p(1) == classOf[String]
        }
        assert(candidates.nonEmpty, "SqlServerQueryUtil has no run(conn: Connection, sql: String, ...)")
        val m = candidates.head
        m.setAccessible(true)
        val rest = m.getParameterTypes.drop(2).toSeq
        assert(rest.count(_ == classOf[Int]) <= 1, "cannot tell which Int parameter of run is the limit: " + m)
        val args: Seq[AnyRef] = Seq(conn, sql) ++ rest.map {
            case c if c == classOf[Boolean] => java.lang.Boolean.valueOf(unlimited)
            case c if c == classOf[Int] => Integer.valueOf(if (unlimited) -1 else limit)
            case c => throw new AssertionError("run takes a parameter this spec cannot fill: " + c)
        }
        QuerySafetyReflect.invoke(SqlServerQueryUtil, m, args: _*)
    }

    private def assertRolledBack(r: Recorder): Unit = {
        val off = r.at("setAutoCommit:false")
        assert(off >= 0, "autocommit is switched off: " + r.calls)
        assert(r.query < 0 || off < r.query, "autocommit goes off before the statement runs: " + r.calls)
        val rb = r.calls.lastIndexOf("rollback")
        assert(rb >= 0, "the transaction is rolled back: " + r.calls)
        assert(rb > r.query, "the rollback comes after the statement: " + r.calls)
        assert(!r.calls.contains("commit"), "never committed: " + r.calls)
    }

    test("run sets a query timeout for limit -1 and rolls the transaction back on success and on failure") {
        // limit -1: the 300 s ceiling (Decision 5), set before the statement runs
        val u = new Recorder()
        val out = run(u.conn, "SELECT v FROM t", unlimited = true, limit = -1)
        assert(u.timeouts.exists(_.endsWith(":300")), "limit -1 runs under a 300 s timeout: " + u.calls)
        assert(u.calls.indexWhere(_.contains("setQueryTimeout:")) < u.query, u.calls)
        out match {
            case res: SqlServerQueryUtil.SqlServerQueryResult => assert(res.results.size == 2)
            case _ => ()
        }
        assertRolledBack(u)

        // a positive limit keeps 30 s, and is rolled back too
        val b = new Recorder()
        run(b.conn, "SELECT TOP (10) * FROM (SELECT v FROM t) q", unlimited = false, limit = 10)
        assert(b.timeouts.exists(_.endsWith(":30")), "a positive limit keeps the 30 s timeout: " + b.calls)
        assertRolledBack(b)

        // a failing statement, and a failing read, are rolled back and the SQLException surfaces
        Seq("execute", "next").foreach { point =>
            Seq(true, false).foreach { unlimited =>
                val f = new Recorder(failOn = Set(point))
                intercept[java.sql.SQLException](run(f.conn, "SELECT v FROM t", unlimited, 10))
                assert(f.calls.contains("rollback"), s"failure at $point (unlimited=$unlimited) is rolled back: ${f.calls}")
                assert(!f.calls.contains("commit"), f.calls)
                assert(f.timeouts.nonEmpty, s"failure at $point (unlimited=$unlimited) still ran under a timeout: ${f.calls}")
            }
        }
    }
}
