package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.sql.{Connection, ResultSet, ResultSetMetaData, Statement}
import scala.collection.mutable.ArrayBuffer

/** The Snowflake and Databricks time limit where it is applied: `run(conn,
  * finalSql, limit)` sets the statement's query timeout before the statement
  * runs, for `limit: -1` as for a positive limit. A recording connection, as
  * SqlServerQueryUtilSpec and PostgresReadOnlyQuerySpec use; no account, no
  * network. */
class WarehouseQueryRunTimeoutSpec extends AnyFunSuite {

    /** Records the calls on the connection and the statement it hands out. */
    private final class Recorder(rows: Int = 3) {
        val calls = ArrayBuffer[String]()

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
                    case "next" => left -= 1; java.lang.Boolean.valueOf(left >= 0)
                    case "getObject" => Integer.valueOf(left)
                    case "close" => calls += "rs.close"; null
                    case _ => zero(m)
                }
            }
        }

        private val statement: Statement = proxy(classOf[Statement]) { (m, a) =>
            m.getName match {
                case "executeQuery" => calls += s"executeQuery:${a(0)}"; resultSet()
                case "setQueryTimeout" => calls += s"setQueryTimeout:${a(0)}"; null
                case "setMaxRows" => calls += s"setMaxRows:${a(0)}"; null
                case "close" => calls += "stmt.close"; null
                case _ => zero(m)
            }
        }

        val conn: Connection = proxy(classOf[Connection]) { (m, _) =>
            m.getName match {
                case "createStatement" => calls += "createStatement"; statement
                case _ => zero(m)
            }
        }

        def query: Int = calls.indexWhere(_.startsWith("executeQuery:"))
        def timeouts: Seq[String] = calls.filter(_.startsWith("setQueryTimeout:")).toSeq
    }

    private type Run = (Connection, String, Int) => java.util.List[java.util.Map[String, Any]]

    private val runs: Seq[(String, Run)] = Seq(
        "Snowflake" -> ((c, s, l) => SnowflakeQueryUtil.run(c, s, l).results),
        "Databricks" -> ((c, s, l) => DatabricksQueryUtil.run(c, s, l).results)
    )

    runs.foreach { case (name, run) =>
        test(s"$name run sets the 300 s timeout for limit -1 before the statement runs, and reads every row") {
            val r = new Recorder(rows = 3)
            val results = run(r.conn, "SELECT v FROM t", -1)
            assert(r.timeouts == Seq("setQueryTimeout:300"), "limit -1 runs under a 300 s timeout: " + r.calls)
            assert(r.query >= 0, "the statement runs: " + r.calls)
            assert(r.calls.indexWhere(_.startsWith("setQueryTimeout:")) < r.query, "the timeout is set before the statement runs: " + r.calls)
            assert(r.calls.contains("executeQuery:SELECT v FROM t"), r.calls)
            assert(results.size == 3, "limit -1 reads every row")
            assert(r.calls.contains("rs.close") && r.calls.contains("stmt.close"), r.calls)
        }

        test(s"$name run sets the 30 s timeout for a positive limit before the statement runs, and caps the rows") {
            val r = new Recorder(rows = 3)
            val results = run(r.conn, "SELECT v FROM t LIMIT 2", 2)
            assert(r.timeouts == Seq("setQueryTimeout:30"), "a positive limit runs under a 30 s timeout: " + r.calls)
            assert(r.calls.indexWhere(_.startsWith("setQueryTimeout:")) < r.query, "the timeout is set before the statement runs: " + r.calls)
            assert(results.size == 2, "a positive limit caps the rows read")
        }
    }
}
