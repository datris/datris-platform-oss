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

    // ---- review round 1: a keyword glued to a number ----

    test("a keyword glued to the end of a number is refused, refused words and BLOCKED_KEYWORDS words alike") {
        Seq(
            "SELECT 1SET NOCOUNT ON",
            "SELECT 1WHILE 1=1SELECT 1",
            "SELECT 1 x FROM t WHERE 1=1SELECT 2",
            "SELECT a FROM t WHERE 1=1COMMIT",
            "SELECT 1KILL 55",
            "SELECT 1SHUTDOWN",
            "SELECT 1DELETE FROM dbo.t",
            "SELECT 1.5SET NOCOUNT ON",
            "SELECT 0x1FSET NOCOUNT ON"
        ).foreach { sql =>
            intercept[DatrisException](SqlServerQueryUtil.validateQuery(sql))
        }
        val m = refusal("SELECT 1KILL 55")
        assert(m == "Query has a number joined to a word; put a space between them", m)
        Seq("SELECT 1SET NOCOUNT ON", "SELECT 1WHILE 1=1SELECT 1", "SELECT 1 x FROM t WHERE 1=1SELECT 2", "SELECT a FROM t WHERE 1=1COMMIT").foreach { sql =>
            assert(problem(sql).isDefined, sql)
        }
    }

    test("numbers in their written forms are still accepted") {
        Seq(
            "SELECT 1 AS a, 1.5 AS b, 2e3 AS c, 1.5E-3 AS d, 0x1F AS e, .5 AS f FROM t WHERE a = 1",
            "SELECT TOP 10 id FROM t2 WHERE b IN (1, 2, 3) ORDER BY id",
            "SELECT id FROM t ORDER BY id OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY"
        ).foreach(accepted)
    }

    // ---- review round 2: separators the store reads as whitespace, Unicode case ----

    test("a control or Unicode space character between the words of a refused phrase does not hide it") {
        Seq("\u0001", "\u001B", "\u0008", "\u00A0", "\u3000", "\u200B").foreach { sep =>
            Seq("SELECT NEXT" + sep + "VALUE FOR dbo.s", "SELECT NEXT VALUE" + sep + "FOR dbo.s").foreach { sql =>
                val msg = refusal(sql)
                assert(msg == "Query contains blocked keyword: NEXT VALUE FOR", sql + " -> " + msg)
                assert(problem(sql).isDefined, sql)
            }
        }
        // the same separators between ordinary words still read
        accepted("SELECT a,\u00A0b FROM\u0001t WHERE c\u3000= 1")
    }

    test("a set operator spelled with a letter only Unicode case rules fold to ASCII does not let a second SELECT through") {
        Seq(
            "SELECT a FROM t UN\u0131ON SELECT 2",
            "SELECT a FROM t UNION \u0251LL SELECT 2",
            "SELECT a FROM t INTER\u017FECT SELECT 2",
            "SELECT a FROM t \u0435XCEPT SELECT 2"
        ).foreach { sql =>
            assert(refusal(sql) == "Only one statement is allowed in a query", sql)
        }
    }

    test("run does not turn autocommit back on after its rollback") {
        val r = new Recorder()
        run(r.conn, "SELECT v FROM t", unlimited = true, limit = -1)
        assert(!r.calls.contains("setAutoCommit:true"), r.calls)
        val f = new Recorder(failOn = Set("execute"))
        intercept[java.sql.SQLException](run(f.conn, "SELECT v FROM t", unlimited = false, limit = 10))
        assert(!f.calls.contains("setAutoCommit:true"), f.calls)
    }

    // =====================================================================
    // Story: SQL Server query: a column name that ends in a blocked keyword
    // reads again (plans/stories/sqlserver-keyword-boundary-restored.md).
    // Decision 1 as ruled: a BLOCKED_KEYWORDS hit counts unless the character
    // before it is a letter, a decimal digit or `_`. A keyword joined to the
    // end of a number is left to the scan, whose message is NumberJoined.
    // =====================================================================

    private val NumberJoined = "Query has a number joined to a word; put a space between them"

    // The 32 BLOCKED_KEYWORDS words (private in SqlServerQueryUtil).
    private val BlockedKeywords = Seq(
        "INSERT",
        "UPDATE",
        "DELETE",
        "DROP",
        "ALTER",
        "CREATE",
        "TRUNCATE",
        "GRANT",
        "REVOKE",
        "DENY",
        "EXEC",
        "EXECUTE",
        "MERGE",
        "INTO",
        "USE",
        "BULK",
        "OPENROWSET",
        "OPENDATASOURCE",
        "OPENQUERY",
        "DBCC",
        "BACKUP",
        "RESTORE",
        "SHUTDOWN",
        "KILL",
        "RECONFIGURE",
        "WAITFOR",
        "DECLARE",
        "TRIGGER",
        "SETUSER",
        "REVERT",
        "WRITETEXT",
        "UPDATETEXT"
    )

    private def mixedCase(w: String): String =
        w.zipWithIndex.map { case (c, i) => if (i % 2 == 0) c.toUpper else c.toLower }.mkString

    private def keywordRefusal(sql: String, keyword: String): Unit = {
        val msg = refusal(sql)
        assert(msg == "Query contains blocked keyword: " + keyword, sql + " -> " + msg)
    }

    test("a name that ends in a blocked keyword after a digit or a non-ASCII letter is accepted") {
        val names = Seq("co2use", "h2into", "q4update", "step1exec", "caf\u00E9use", "Co2Use", "\u00FCbersicht\u00E4kill")
        Seq(
            "SELECT co2use FROM t",
            "SELECT h2into, q4update FROM t",
            "SELECT step1exec FROM t",
            "SELECT caf\u00E9use FROM t",
            "SELECT Co2Use, \u00FCbersicht\u00E4kill FROM t"
        ).foreach(accepted)
        names.foreach { n =>
            accepted("SELECT [" + n + "] FROM t")
            accepted("SELECT a FROM t WHERE b = '" + n + "'")
        }
    }

    test("a blocked keyword that stands on its own is refused with the keyword named") {
        BlockedKeywords.foreach { kw =>
            Seq(kw, kw.toLowerCase(java.util.Locale.ROOT), mixedCase(kw)).foreach { w =>
                Seq(
                    "SELECT a FROM t " + w,
                    "SELECT a FROM t WHERE b IN (" + w + ")",
                    "SELECT a FROM t WHERE b =" + w,
                    "SELECT a," + w + " FROM t",
                    "SELECT t." + w + " FROM t"
                ).foreach(keywordRefusal(_, kw))
            }
        }
    }

    test("a blocked keyword after @, # or $ is refused, as on the released version") {
        Seq("@", "#", "$").foreach { mark =>
            Seq("USE", "KILL", "EXEC", "INTO", "DELETE").foreach { kw =>
                Seq(kw, kw.toLowerCase(java.util.Locale.ROOT)).foreach { w =>
                    keywordRefusal("SELECT a FROM t WHERE b = " + mark + w, kw)
                }
            }
        }
    }

    test("a blocked keyword after a superscript digit or a combining mark is refused") {
        // superscript two, superscript three, combining acute, combining diaeresis
        Seq("x\u00B2", "x\u00B3", "cafe\u0301", "u\u0308").foreach { front =>
            Seq("USE", "KILL", "INTO").foreach { kw =>
                keywordRefusal("SELECT " + front + kw.toLowerCase(java.util.Locale.ROOT) + " FROM t", kw)
            }
        }
    }

    test("a blocked keyword joined to the end of a number is refused whatever form the number has") {
        // the keyword directly follows a digit or a hex digit: the scan's message
        Seq(
            "SELECT 1KILL 55",
            "SELECT 1SHUTDOWN",
            "SELECT 1DELETE FROM dbo.t",
            "SELECT 1use x",
            "SELECT 1.5KILL 55",
            "SELECT .5KILL 55",
            "SELECT 2e3KILL 55",
            "SELECT 1.5E-3DELETE FROM t",
            "SELECT 1EXEC sp_who",
            "SELECT 0x1FKILL 55",
            "SELECT 0x1FDELETE FROM t",
            "SELECT 0xABINTO t"
        ).foreach { sql =>
            val msg = refusal(sql)
            assert(msg == NumberJoined, sql + " -> " + msg)
            assert(problem(sql).contains(NumberJoined), sql)
        }
        // any other form ending right before the keyword is still refused
        Seq("SELECT 1.KILL 55", "SELECT 1e+KILL 55", "SELECT 0xKILL 55").foreach { sql =>
            intercept[DatrisException](SqlServerQueryUtil.validateQuery(sql))
        }
    }

    // =====================================================================
    // Story: SQL Server query: brace escapes are refused
    // (plans/stories/sqlserver-odbc-call-escape-refused.md). Decision 1 as
    // ruled: every `{` outside string literals and quoted identifiers is
    // refused by the scan, with the Decision 2 text (BraceRule). Every case
    // above this line is unedited.
    // =====================================================================

    private val BraceRule =
        "Curly braces are not allowed in queries outside string literals; " +
            "write dates as '2024-01-31' and use T-SQL function names instead of {d ...} and {fn ...}"

    private val AllowlistMessage =
        "Only read-only statements are allowed: SELECT (WITH/CTE); for metadata, SELECT from INFORMATION_SCHEMA views"

    private def braceRefused(sql: String): Unit = {
        val msg = refusal(sql)
        assert(msg == BraceRule, sql + " -> " + msg)
        val p = problem(sql)
        assert(p.isDefined, sql)
        assert(p.contains(BraceRule), sql + " -> " + p)
    }

    /** One query per brace form; `%s` is where the form's word goes. */
    private val BraceForms: Seq[(String, String)] = Seq(
        "call" -> "SELECT 1 AS a {%s p(1)}",
        "call" -> "SELECT 1 AS a {? = %s dbo.p(1)}",
        "fn" -> "SELECT {%s UCASE(name)} FROM t",
        "d" -> "SELECT a FROM t WHERE d >= {%s '2024-01-31'}",
        "t" -> "SELECT a FROM t WHERE c >= {%s '10:00:00'}",
        "ts" -> "SELECT a FROM t WHERE c >= {%s '2024-01-31 10:00:00'}",
        "oj" -> "SELECT a.x FROM {%s a LEFT OUTER JOIN b ON a.id = b.id}",
        "escape" -> "SELECT a FROM t WHERE n LIKE '\\_%%' {%s '\\'}",
        "limit" -> "SELECT a FROM t {%s 5}"
    )

    test("an opening brace outside string literals and quoted identifiers is refused with the brace rule named") {
        BraceForms.foreach { case (word, template) =>
            Seq(word.toUpperCase(java.util.Locale.ROOT), word.toLowerCase(java.util.Locale.ROOT)).foreach { w =>
                braceRefused(template.format(w))
            }
        }
    }

    test("a brace is refused whatever stands between it and the next word") {
        val gaps = Seq("", " ", "\t", "\n", "\u0001", "\u001B", "\u00a0", "\u3000", "\u2003", "\u200b")
        gaps.foreach { g =>
            braceRefused("SELECT 1 AS a {" + g + "call p(1)}")
            braceRefused("SELECT 1 AS a {" + g + "CALL dbo.p}")
            braceRefused("SELECT a FROM t WHERE d >= {" + g + "d '2024-01-31'}")
            braceRefused("SELECT {" + g + "fn UCASE(name)} FROM t")
            // the return-value form, the gap between every part
            braceRefused("SELECT 1 AS a {" + g + "?" + g + "=" + g + "call p(1)}")
            // the same characters in front of the brace
            braceRefused("SELECT 1 AS a" + g + "{call p(1)}")
            braceRefused("SELECT a FROM t WHERE d >=" + g + "{d '2024-01-31'}")
        }
    }

    test("a brace is refused at any parenthesis depth") {
        Seq(
            // a subquery
            "SELECT * FROM (SELECT a FROM t WHERE d >= {d '2024-01-31'}) s",
            "SELECT * FROM (SELECT 1 AS a {call p(1)}) s",
            // a CTE body
            "WITH c AS (SELECT {fn UCASE(a)} AS u FROM t) SELECT u FROM c",
            "WITH c AS (SELECT 1 AS a {call p}) SELECT a FROM c",
            // a function argument
            "SELECT COALESCE(a, {fn NOW()}) AS n FROM t",
            "SELECT ISNULL(a, (SELECT 1 AS b {? = call p})) AS n FROM t",
            // an IN (...) list
            "SELECT a FROM t WHERE d IN ({d '2024-01-31'}, {d '2024-02-01'})",
            "SELECT a FROM t WHERE b IN (1, {call p})"
        ).foreach(braceRefused)
    }

    test("a brace is refused for limit -1 and for a positive limit") {
        // `query` calls validateQuery before it looks at the limit, so the
        // refusal holds for both; the limit-capped text is refused as well.
        Seq(
            "SELECT 1 AS a {call p(1)}",
            "SELECT a FROM t WHERE d >= {d '2024-01-31'} ORDER BY a",
            "WITH c AS (SELECT a FROM t) SELECT a FROM c {limit 5}"
        ).foreach { sql =>
            assert(problem(sql).isDefined, sql)
            val e = intercept[DatrisException](SqlServerQueryUtil.validateQuery(sql))
            assert(e.getMessage == BraceRule, sql + " -> " + e.getMessage)
            Seq(1, 100).foreach { limit =>
                val capped = SqlServerQueryUtil.applyLimit(sql, limit)
                assert(problem(capped).isDefined, capped)
                intercept[DatrisException](SqlServerQueryUtil.validateQuery(capped))
            }
        }
    }

    test("a brace inside a string literal, a bracketed or a double-quoted identifier is accepted") {
        Seq(
            "SELECT * FROM t WHERE payload = '{\"a\": 1}'",
            "SELECT * FROM t WHERE note LIKE '%{fn%'",
            "SELECT JSON_VALUE(doc, '$.a') FROM t WHERE doc = '{}'",
            "SELECT [col{1}], \"{x}\" FROM t",
            "SELECT * FROM t WHERE note = 'it''s {'",
            "SELECT * FROM t WHERE note = N'{call p} {d ''2024-01-31''}'",
            "SELECT [a]]{b], \"c\"\"{d\" FROM t",
            "SELECT [{call p}], \"{fn x}\" FROM t WHERE c = N'{' OR c = '}'",
            "SELECT * FROM OPENJSON(N'{\"a\": [1, 2]}') AS j",
            // the wide whitespace set around a brace that sits inside quotes
            "SELECT * FROM t WHERE note = '\u00a0{\u200bcall\u3000p}\u2003'",
            "SELECT [\u0001{\u001Bd x}] FROM t",
            "SELECT \"\u3000{\u00a0fn x}\" FROM t WHERE n = N'\u200b{\t?\n=\u2003call p}'"
        ).foreach(accepted)
    }

    test("the same reads written without braces are accepted") {
        Seq(
            "SELECT a FROM t WHERE d >= '2024-01-31'",
            "SELECT a FROM t WHERE d >= CAST('2024-01-31' AS date)",
            "SELECT UPPER(name) FROM t",
            "SELECT SYSDATETIME() AS n, CONCAT(a, b) AS c FROM t",
            "SELECT a.x FROM a LEFT OUTER JOIN b ON a.id = b.id",
            "SELECT a FROM t WHERE n LIKE '\\_%' ESCAPE '\\'",
            "SELECT TOP 5 a FROM t"
        ).foreach(accepted)
    }

    test("a query must start with SELECT or WITH, so a statement that is only a name is refused") {
        Seq(
            "p",
            "p 1, 'x'",
            "dbo.p",
            "dbo.p 1, 'x'",
            "[p]",
            "[dbo].[p]",
            "[dbo].[p] 1, N'x'",
            "p\u00a0(1)",
            "  dbo.p  ",
            "selectp 1",
            "with_p 1",
            "select_p",
            "\"p\" 1"
        ).foreach { sql =>
            val msg = refusal(sql)
            assert(msg == AllowlistMessage, sql + " -> " + msg)
        }
        // the text the limit step produces always starts with SELECT or WITH
        Seq(
            "SELECT a FROM t",
            "SELECT a FROM t ORDER BY a",
            "WITH c AS (SELECT a FROM t) SELECT a FROM c"
        ).foreach { sql =>
            val out = SqlServerQueryUtil.applyLimit(sql, 10).trim.toUpperCase(java.util.Locale.ROOT)
            assert(out.startsWith("SELECT ") || out.startsWith("WITH "), out)
        }
    }

    // =====================================================================
    // Review follow-up: the text the driver sends is the text that was
    // validated. The driver's limit escape is refused anywhere in the text,
    // quoted or not, and the driver's own translator leaves every accepted
    // text unchanged. Every case above this line is unedited.
    // =====================================================================

    private val LimitRule =
        "The text {limit ...} is not allowed anywhere in a query, even inside a string or a quoted name; " +
            "use the request's limit instead"

    private def limitRefused(sql: String): Unit = {
        val msg = refusal(sql)
        assert(msg == LimitRule, sql + " -> " + msg)
    }

    test("a limit escape is refused anywhere in the text, inside quotes too") {
        Seq(
            "SELECT 1 AS a \"{limit 1} {call p} AS [b\"]",
            "SELECT 1 AS a \"{limit 1} x AS [b\"] FROM t",
            "SELECT 1 AS a, 2 AS \"'\" FROM t WHERE b = '{limit 1} x [y']",
            "SELECT \"a{LIMIT\t5}\" FROM t",
            "SELECT [a{ limit 5}] FROM t",
            "SELECT * FROM t WHERE x = 'x {limit 5}'",
            "SELECT [x{limit 1 offset 2}] FROM t",
            "SELECT * FROM t WHERE x = N'{\u000B\f\r\nLiMiT\n5}'",
            "WITH c AS (SELECT a FROM t WHERE b = '{limit 5}') SELECT a FROM c"
        ).foreach(limitRefused)
    }

    test("a brace outside quotes keeps the brace rule's message even before the word limit") {
        Seq("SELECT a FROM t {limit 5}", "SELECT a FROM t { LIMIT 5}").foreach(braceRefused)
    }

    test("brace text inside quotes that the driver does not read as a limit escape is accepted") {
        Seq(
            "SELECT * FROM t WHERE doc = '{\"limit\": 5}'",
            "SELECT * FROM t WHERE doc = '{limit:5}'",
            "SELECT * FROM t WHERE doc = '{limit}'",
            "SELECT [{limits 5}] FROM t",
            "SELECT \"{ limitx 5}\" FROM t",
            "SELECT * FROM t WHERE doc = '{ \"a\": \"limit 5\" }'"
        ).foreach(accepted)
    }

    /** The driver's own escape translator (mssql-jdbc), reached by reflection:
      * it is package-private. The same call the driver makes for a plain
      * Statement whose text holds a brace. */
    private lazy val translator: String => Either[Throwable, String] = {
        val cls = Class.forName("com.microsoft.sqlserver.jdbc.JDBCSyntaxTranslator")
        val ctor = cls.getDeclaredConstructor()
        ctor.setAccessible(true)
        val translate = cls.getDeclaredMethod("translate", classOf[String])
        translate.setAccessible(true)
        (sql: String) =>
            try Right(translate.invoke(ctor.newInstance(), sql).asInstanceOf[String])
            catch { case e: java.lang.reflect.InvocationTargetException => Left(e.getCause) }
    }

    private def isAccepted(sql: String): Boolean =
        try { SqlServerQueryUtil.validateQuery(sql); true }
        catch { case _: DatrisException => false }

    test("for every text the validator accepts, the driver's translator sends it unchanged") {
        val refusedShapes = Seq(
            "SELECT 1 AS a \"{limit 1} {call p} AS [b\"]",
            "SELECT 1 AS a, 2 AS \"'\" FROM t WHERE b = '{limit 1} x [y']",
            "SELECT [a{limit 5}] FROM t",
            "SELECT [x{limit 1 offset 2}] FROM t",
            "SELECT * FROM t WHERE x = 'x {limit 5}'",
            "SELECT a FROM t {limit 5}",
            "SELECT 1 AS a {call p(1)}",
            "SELECT {fn UCASE(name)} FROM t",
            "SELECT a FROM t WHERE d >= {d '2024-01-31'}"
        )
        val acceptedShapes = Seq(
            "SELECT a FROM t",
            "SELECT a FROM t ORDER BY a",
            "WITH c AS (SELECT a FROM t) SELECT a FROM c",
            "SELECT * FROM t WHERE payload = '{\"a\": 1}'",
            "SELECT * FROM t WHERE note LIKE '%{fn%'",
            "SELECT JSON_VALUE(doc, '$.a') FROM t WHERE doc = '{}'",
            "SELECT [col{1}], \"{x}\" FROM t",
            "SELECT * FROM t WHERE note = 'it''s {'",
            "SELECT * FROM t WHERE note = N'{call p} {d ''2024-01-31''}'",
            "SELECT [a]]{b], \"c\"\"{d\" FROM t",
            "SELECT [{call p}], \"{fn x}\" FROM t WHERE c = N'{' OR c = '}'",
            "SELECT * FROM OPENJSON(N'{\"a\": [1, 2]}') AS j",
            "SELECT * FROM t WHERE note = ' {\u200bcall\u3000p} '",
            "SELECT [\u0001{\u001Bd x}] FROM t",
            "SELECT \"\u3000{ fn x}\" FROM t WHERE n = N'\u200b{\t?\n= call p}'",
            "SELECT * FROM t WHERE doc = '{\"limit\": 5}'",
            "SELECT * FROM t WHERE doc = '{limit:5}'",
            "SELECT * FROM t WHERE doc = '{limit}'",
            "SELECT [{limits 5}] FROM t",
            "SELECT \"{ limitx 5}\" FROM t",
            "SELECT a FROM t WHERE n LIKE '\\_%' ESCAPE '\\'",
            "SELECT TOP 5 a FROM t ORDER BY a",
            "SELECT a FROM t ORDER BY a OFFSET 2 ROWS FETCH NEXT 3 ROWS ONLY",
            "SELECT * FROM t WHERE c = '?' AND d = [?] AND e = \"? = x\""
        )
        acceptedShapes.foreach(sql => assert(isAccepted(sql), sql))
        refusedShapes.foreach(sql => assert(!isAccepted(sql), sql))
        // Every text that could be sent for an accepted query: as given
        // (limit -1) and, unfiltered, as the limit step rewrites it for a
        // positive limit, since `query` sends that text without validating it
        // again.
        (acceptedShapes ++ refusedShapes).filter(isAccepted).foreach { sql =>
            Seq(sql, SqlServerQueryUtil.applyLimit(sql, 1), SqlServerQueryUtil.applyLimit(sql, 100)).foreach { text =>
                assert(translator(text) == Right(text), text + " -> " + translator(text))
            }
        }
        // The translator is live: it does rewrite the limit escape shapes,
        // which is why they are refused.
        Seq("SELECT [a{limit 5}] FROM t", "SELECT a FROM t {limit 5}").foreach { sql =>
            assert(translator(sql) != Right(sql), sql)
        }
    }

    // ---- ADD: refused as a word, like the other STATEMENT_KEYWORDS ----

    test("the reserved word ADD is refused as a word in any case, after a SELECT or within one") {
        Seq(
            "SELECT a FROM t ADD SIGNATURE TO p BY CERTIFICATE c",
            "SELECT a FROM t add x",
            "SELECT a FROM t Add x",
            "SELECT a FROM t aDD x",
            "SELECT add FROM t",
            "SELECT a FROM t WHERE add = 1",
            "SELECT a FROM t\nADD\tx"
        ).foreach { sql =>
            assert(refusal(sql) == "Query contains blocked keyword: ADD", sql)
            assert(problem(sql).contains("Query contains blocked keyword: ADD"), sql)
        }
    }

    test("ADD inside a literal or a quoted identifier, and words that only contain it, are accepted") {
        Seq(
            "SELECT a FROM t WHERE note = 'add'",
            "SELECT a FROM t WHERE note = N'ADD x TO y'",
            "SELECT [add] FROM t",
            "SELECT [ADD] AS v FROM t",
            "SELECT \"add\" FROM t",
            "SELECT \"ADD\" AS v FROM t",
            "SELECT address FROM t",
            "SELECT added_at, readd FROM t",
            "SELECT DATEADD(day, 1, created_at) FROM t",
            "SELECT dateadd(month, -1, GETDATE()) AS d"
        ).foreach(accepted)
    }
}
