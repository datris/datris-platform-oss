package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, PreparedStatement, ResultSet, ResultSetMetaData, SQLException, Statement}
import scala.collection.mutable.ArrayBuffer

/** A query through PostgresQueryUtil runs inside a transaction the database
  * treats as read-only, under a statement timeout, and is always rolled back;
  * the pooled connection goes back with the pool's expected state and its
  * session reset. Exercised on a recording connection: no database. */
class PostgresReadOnlyQuerySpec extends AnyFunSuite {

    /** Records every call on the connection and the statements it hands out.
      * `failOn` names a point that throws: "setup", "execute", "next",
      * "rollback", "reset". */
    private final class Recorder(failOn: Set[String] = Set.empty, rows: Int = 2) {
        val calls = ArrayBuffer[String]()
        var autoCommit = true
        var readOnly = false
        var evicted = 0

        private def proxy[T](cls: Class[T])(h: (String, Array[AnyRef]) => AnyRef): T =
            Proxy.newProxyInstance(
                getClass.getClassLoader,
                Array[Class[_]](cls),
                new InvocationHandler {
                    def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef = h(m.getName, Option(args).getOrElse(Array.empty))
                }
            ).asInstanceOf[T]

        private def defaultFor(name: String): AnyRef = name match {
            case "execute" | "next" | "isClosed" => java.lang.Boolean.FALSE
            case "hashCode" => Integer.valueOf(System.identityHashCode(this))
            case _ => null
        }

        private def resultSet(): ResultSet = {
            var left = rows
            val meta = proxy(classOf[ResultSetMetaData]) { (n, _) =>
                n match {
                    case "getColumnCount" => Integer.valueOf(1)
                    case "getColumnLabel" => "v"
                    case other => defaultFor(other)
                }
            }
            proxy(classOf[ResultSet]) { (n, a) =>
                n match {
                    case "getMetaData" => meta
                    case "next" =>
                        if (failOn("next")) throw new SQLException("result read failed")
                        left -= 1; java.lang.Boolean.valueOf(left >= 0)
                    case "getObject" => Integer.valueOf(left)
                    case "close" => calls += "rs.close"; null
                    case other => defaultFor(other)
                }
            }
        }

        private def statementHandler(kind: String): (String, Array[AnyRef]) => AnyRef = { (n, a) =>
            n match {
                case "execute" =>
                    val sql = a(0).toString
                    calls += s"$kind.execute:$sql"
                    if (sql == PostgresQueryUtil.SessionReset && failOn("reset")) throw new SQLException("reset failed")
                    if (sql.startsWith("SET") && failOn("setup")) throw new SQLException("setup failed")
                    java.lang.Boolean.FALSE
                case "executeQuery" =>
                    calls += s"$kind.executeQuery" + a.headOption.map(":" + _).getOrElse("")
                    if (failOn("execute")) throw new SQLException("statement failed")
                    resultSet()
                case "setQueryTimeout" => calls += s"$kind.setQueryTimeout:${a(0)}"; null
                case "setString" => calls += s"$kind.setString:${a(0)}=${a(1)}"; null
                case "close" => calls += s"$kind.close"; null
                case other => defaultFor(other)
            }
        }

        val conn: Connection = proxy(classOf[Connection]) { (n, a) =>
            n match {
                case "setAutoCommit" =>
                    autoCommit = a(0).asInstanceOf[Boolean]; calls += s"setAutoCommit:$autoCommit"; null
                case "getAutoCommit" => java.lang.Boolean.valueOf(autoCommit)
                case "setReadOnly" => readOnly = a(0).asInstanceOf[Boolean]; calls += s"setReadOnly:$readOnly"; null
                case "isReadOnly" => java.lang.Boolean.valueOf(readOnly)
                case "rollback" =>
                    calls += "rollback"
                    if (failOn("rollback")) throw new SQLException("rollback failed")
                    null
                case "commit" => calls += "commit"; null
                case "createStatement" =>
                    calls += "createStatement"; proxy(classOf[Statement])(statementHandler("stmt"))
                case "prepareStatement" =>
                    calls += s"prepareStatement:${a(0)}"; proxy(classOf[PreparedStatement])(statementHandler("ps"))
                case other => defaultFor(other)
            }
        }

        val evict: Connection => Unit = _ => evicted += 1

        def index(p: String => Boolean): Int = calls.indexWhere(p)
        def at(c: String): Int = calls.indexOf(c)
    }

    private val Sql = "SELECT data->>'k' FROM t WHERE data ? 'k' LIMIT 5"

    private def assertRestored(r: Recorder): Unit = {
        assert(!r.calls.contains("commit"), r.calls)
        val rb = r.at("rollback")
        assert(rb >= 0, r.calls)
        assert(r.at("setReadOnly:false") > rb, r.calls)
        val ac = r.calls.lastIndexOf("setAutoCommit:true")
        assert(ac > rb, "autocommit is switched back on only after the rollback: " + r.calls)
        assert(r.calls.lastIndexOf("stmt.execute:" + PostgresQueryUtil.SessionReset) > ac, r.calls)
        assert(r.autoCommit && !r.readOnly)
        assert(r.evicted == 0)
    }

    test("autocommit goes off and read-only on, and the transaction is made read-only with a timeout, before the statement runs") {
        val r = new Recorder()
        val out = PostgresQueryUtil.runOn(r.conn, Sql, Nil, 30, r.evict)
        assert(out.size == 2)
        val q = r.index(_.startsWith("stmt.executeQuery"))
        assert(r.at("setAutoCommit:false") >= 0 && r.at("setAutoCommit:false") < r.at("setReadOnly:true"), r.calls)
        assert(r.at("setReadOnly:true") < r.at("stmt.execute:SET TRANSACTION READ ONLY"), r.calls)
        assert(r.at("stmt.execute:SET TRANSACTION READ ONLY") < r.at("stmt.execute:SET LOCAL statement_timeout = 30000"), r.calls)
        assert(r.at("stmt.execute:SET LOCAL statement_timeout = 30000") < q, r.calls)
        assert(r.at("stmt.setQueryTimeout:30") < q, r.calls)
        assertRestored(r)
    }

    test("caller SQL runs on a plain statement, so a ? in it is not a placeholder") {
        val r = new Recorder()
        PostgresQueryUtil.runOn(r.conn, Sql, Nil, 30, r.evict)
        assert(r.calls.contains("stmt.executeQuery:" + Sql), r.calls)
        assert(!r.calls.exists(_.startsWith("prepareStatement")), r.calls)
    }

    test("a server-built statement with params runs as a prepared statement inside the same read-only transaction") {
        val r = new Recorder()
        PostgresQueryUtil.runOn(r.conn, "SELECT a FROM b WHERE c = ? AND d = ?", Seq("x", "y"), 30, r.evict)
        assert(r.calls.contains("prepareStatement:SELECT a FROM b WHERE c = ? AND d = ?"), r.calls)
        assert(r.calls.contains("ps.setString:1=x") && r.calls.contains("ps.setString:2=y"), r.calls)
        assert(r.at("stmt.execute:SET TRANSACTION READ ONLY") < r.at("ps.executeQuery"), r.calls)
        assert(r.calls.contains("ps.setQueryTimeout:30"), r.calls)
        assertRestored(r)
    }

    test("an unlimited query runs under the unlimited ceiling, server-side and client-side") {
        val t = PostgresQueryUtil.timeoutSeconds(-1)
        assert(t == PostgresQueryUtil.UNLIMITED_QUERY_TIMEOUT_SECONDS && t > 0)
        assert(PostgresQueryUtil.timeoutSeconds(0) == PostgresQueryUtil.QUERY_TIMEOUT_SECONDS)
        assert(PostgresQueryUtil.timeoutSeconds(100) == PostgresQueryUtil.QUERY_TIMEOUT_SECONDS)
        val r = new Recorder()
        PostgresQueryUtil.runOn(r.conn, Sql, Nil, t, r.evict)
        assert(r.calls.contains("stmt.execute:SET LOCAL statement_timeout = " + (t * 1000L)), r.calls)
        assert(r.calls.contains("stmt.setQueryTimeout:" + t), r.calls)
    }

    Seq("setup", "execute", "next").foreach { point =>
        test(s"a failure at $point is rolled back, never committed, and the connection is restored") {
            val r = new Recorder(failOn = Set(point))
            intercept[SQLException](PostgresQueryUtil.runOn(r.conn, Sql, Nil, 30, r.evict))
            assertRestored(r)
        }
    }

    test("a failure inside the body is rolled back and the body's exception is the one raised") {
        val r = new Recorder()
        val e = intercept[DatrisException](PostgresQueryUtil.inReadOnlyTransaction(r.conn, 30, r.evict)(_ => throw new DatrisException("boom")))
        assert(e.getMessage == "boom")
        assertRestored(r)
    }

    test("when the rollback fails the connection is evicted and autocommit is not switched back on") {
        val r = new Recorder(failOn = Set("rollback"))
        val out = PostgresQueryUtil.runOn(r.conn, Sql, Nil, 30, r.evict)
        assert(out.size == 2)
        assert(r.evicted == 1)
        assert(!r.calls.contains("commit"))
        assert(r.calls.lastIndexOf("setAutoCommit:true") < r.at("rollback"), r.calls)
    }

    test("when the session reset fails the connection is evicted, and a body failure still surfaces") {
        val r = new Recorder(failOn = Set("reset"))
        PostgresQueryUtil.runOn(r.conn, Sql, Nil, 30, r.evict)
        assert(r.evicted == 1)
        val r2 = new Recorder(failOn = Set("reset", "execute"))
        val e = intercept[SQLException](PostgresQueryUtil.runOn(r2.conn, Sql, Nil, 30, r2.evict))
        assert(e.getMessage == "statement failed")
        assert(r2.evicted == 1)
    }

    test("the statement and result set are closed on success and on failure") {
        val r = new Recorder()
        PostgresQueryUtil.runOn(r.conn, Sql, Nil, 30, r.evict)
        assert(r.calls.contains("rs.close"))
        val r2 = new Recorder(failOn = Set("next"))
        intercept[SQLException](PostgresQueryUtil.runOn(r2.conn, Sql, Nil, 30, r2.evict))
        assert(r2.calls.contains("rs.close"))
        assert(r2.calls.count(_ == "stmt.close") >= 3, r2.calls) // setup, query, reset
    }

    test("the transaction setup is a read-only transaction and a server-side statement timeout in milliseconds") {
        assert(PostgresQueryUtil.transactionSetup(30) == Seq("SET TRANSACTION READ ONLY", "SET LOCAL statement_timeout = 30000"))
        assert(PostgresQueryUtil.SessionReset == "DISCARD ALL")
    }

    // ---------------------------------------------------------- keyword filter

    test("the keyword filter still passes plain reads, WITH queries and jsonb operators") {
        Seq(
            "SELECT * FROM public.t",
            "WITH x AS (SELECT 1 AS a) SELECT a FROM x",
            "select data ? 'k', data ?| array['a'] from t",
            "SELECT reltuples::bigint AS estimate FROM pg_class WHERE relname = 't'",
            "SELECT pg_size_pretty(pg_total_relation_size('t'))",
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'pg_stat_activity'"
        ).foreach(PostgresQueryUtil.validateQuery)
    }

    test("the keyword filter refuses functions that act outside the query's transaction, whatever their case") {
        Seq(
            "pg_terminate_backend(1)",
            "PG_CANCEL_BACKEND(1)",
            "pg_catalog.pg_reload_conf()",
            "pg_rotate_logfile()",
            "pg_promote()",
            "pg_switch_wal()",
            "pg_read_file('f')",
            "pg_read_binary_file('f')",
            "pg_ls_dir('.')",
            "pg_ls_waldir()",
            "pg_stat_file('f')",
            "lo_import('f')",
            "lo_export(1, 'f')",
            "dblink('c', 'q')",
            "\"dblink_exec\"('c', 'q')"
        ).foreach { expr =>
            val e = intercept[DatrisException](PostgresQueryUtil.validateQuery("SELECT " + expr))
            assert(e.getMessage.startsWith("Query contains blocked function: "), expr)
        }
    }

    test("the keyword filter refuses functions with effects outside the transaction and functions that run a query given as text") {
        Seq(
            "pg_create_restore_point('r')",
            "pg_backup_start('b')",
            "pg_backup_stop()",
            "pg_stat_reset()",
            "pg_stat_reset_shared('x')",
            "pg_create_logical_replication_slot('s', 'p')",
            "pg_create_physical_replication_slot('s')",
            "pg_drop_replication_slot('s')",
            "pg_replication_slot_advance('s', '0/0')",
            "pg_logical_emit_message(true, 'p', 'm')",
            "query_to_xml('q', true, false, '')",
            "query_to_xml_and_xmlschema('q', true, false, '')",
            "table_to_xml('t', true, false, '')",
            "cursor_to_xml('c', 1, true, false, '')",
            "database_to_xml(true, false, '')",
            "schema_to_xml('s', true, false, '')",
            "ts_stat('q')",
            "ts_rewrite('a'::tsquery, 'q')",
            "crosstab('q')",
            "crosstab3('q')"
        ).foreach { expr =>
            val e = intercept[DatrisException](PostgresQueryUtil.validateQuery("SELECT * FROM " + expr))
            assert(e.getMessage.startsWith("Query contains blocked function: "), expr)
        }
    }

    test("Unicode-escaped identifiers and strings are refused, whatever their case") {
        Seq("SELECT U&\"x\" FROM t", "SELECT u&'x' AS a", "SELECT * FROM t WHERE a = U&'b'").foreach { q =>
            val e = intercept[DatrisException](PostgresQueryUtil.validateQuery(q))
            assert(e.getMessage.contains("U&"), q)
        }
        // an ampersand elsewhere is fine
        PostgresQueryUtil.validateQuery("SELECT a & b, 'u & me' FROM t")
    }

    // ---------------------------------------------------------- wiring through query / queryBound

    private def withPool[A](r: Recorder)(f: com.zaxxer.hikari.HikariDataSource => A): A = {
        val ds = org.mockito.Mockito.mock(classOf[com.zaxxer.hikari.HikariDataSource])
        org.mockito.Mockito.when(ds.getConnection).thenReturn(r.conn)
        PostgresQueryUtil.poolOverride = ds
        try f(ds)
        finally PostgresQueryUtil.poolOverride = null
    }

    test("query passes the timeout chosen for its limit to the statement, on a plain statement") {
        for (
            (limit, t) <- Seq(
                -1 -> PostgresQueryUtil.UNLIMITED_QUERY_TIMEOUT_SECONDS,
                0 -> PostgresQueryUtil.QUERY_TIMEOUT_SECONDS,
                7 -> PostgresQueryUtil.QUERY_TIMEOUT_SECONDS
            )
        ) {
            val r = new Recorder()
            withPool(r)(_ => PostgresQueryUtil.query("SELECT a FROM t", "db", limit))
            assert(r.calls.contains("stmt.execute:SET LOCAL statement_timeout = " + (t * 1000L)), s"limit $limit: ${r.calls}")
            assert(r.calls.contains("stmt.setQueryTimeout:" + t), s"limit $limit: ${r.calls}")
            assert(!r.calls.exists(_.startsWith("prepareStatement")), r.calls)
            assertRestored(r)
        }
    }

    test("queryBound runs a prepared statement under the bounded timeout") {
        val r = new Recorder()
        withPool(r)(_ => PostgresQueryUtil.queryBound("SELECT a FROM t WHERE b = ?", Seq("x"), "db", 200))
        assert(r.calls.contains("prepareStatement:SELECT a FROM t WHERE b = ? LIMIT 200"), r.calls)
        assert(r.calls.contains("ps.setQueryTimeout:" + PostgresQueryUtil.QUERY_TIMEOUT_SECONDS), r.calls)
        assertRestored(r)
    }

    test("a connection whose restore fails is evicted from the pool it came from; a restored one is not") {
        val bad = new Recorder(failOn = Set("reset"))
        withPool(bad)(ds => {
            PostgresQueryUtil.query("SELECT a FROM t", "db", 5)
            org.mockito.Mockito.verify(ds).evictConnection(bad.conn)
        })
        val good = new Recorder()
        withPool(good)(ds => {
            PostgresQueryUtil.query("SELECT a FROM t", "db", 5)
            org.mockito.Mockito.verify(ds, org.mockito.Mockito.never()).evictConnection(org.mockito.ArgumentMatchers.any())
        })
    }
}
