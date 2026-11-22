package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{Connection, PreparedStatement, ResultSet, Savepoint, Statement}
import scala.collection.mutable.ListBuffer

/** A scripted JDBC connection for driving a loader's table step without a
  * database. Statements are recorded in order; `refuse` decides which
  * `execute` calls fail; `answer` gives the rows of each query (by position,
  * with optional column labels). Savepoint calls are recorded as "set",
  * "rollback" and "release". */
class FakeJdbc(var autoCommit: Boolean = true) {
    val executed: ListBuffer[String] = ListBuffer[String]()
    val queries: ListBuffer[String] = ListBuffer[String]()
    val savepoints: ListBuffer[String] = ListBuffer[String]()
    var refuse: String => Option[Exception] = (_: String) => None
    var answer: (String, Seq[String]) => (Seq[String], Seq[Seq[AnyRef]]) = (_: String, _: Seq[String]) => (Nil, Nil)
    var rollbackFails: Boolean = false

    private def proxy[T](cls: Class[T])(f: (String, Array[AnyRef]) => AnyRef): T =
        Proxy
            .newProxyInstance(
                getClass.getClassLoader,
                Array[Class[_]](cls),
                new InvocationHandler {
                    override def invoke(p: AnyRef, m: Method, args: Array[AnyRef]): AnyRef =
                        try f(m.getName, if (args == null) Array.empty[AnyRef] else args)
                        catch { case e: InvocationTargetException => throw e.getCause }
                }
            )
            .asInstanceOf[T]

    private def defaultFor(name: String): AnyRef = name match {
        case "isClosed" | "wasNull" => java.lang.Boolean.FALSE
        case "hashCode" => Integer.valueOf(System.identityHashCode(this))
        case "toString" => "FakeJdbc"
        case _ => null
    }

    private def resultSet(labels: Seq[String], rows: Seq[Seq[AnyRef]]): ResultSet = {
        var i = -1
        def cell(a: AnyRef): AnyRef = a match {
            case n: Integer => rows(i)(n.intValue - 1)
            case s: String => rows(i)(labels.indexWhere(_.equalsIgnoreCase(s)))
            case _ => null
        }
        proxy(classOf[ResultSet]) { (name, args) =>
            name match {
                case "next" => i += 1; java.lang.Boolean.valueOf(i < rows.size)
                case "getString" => Option(cell(args(0))).map(_.toString).orNull
                case "getObject" => cell(args(0))
                case "close" => null
                case other => defaultFor(other)
            }
        }
    }

    private def run(sql: String): AnyRef = {
        executed += sql
        refuse(sql).foreach(e => throw e)
        java.lang.Boolean.FALSE
    }

    private def query(sql: String, params: Seq[String]): ResultSet = {
        queries += sql
        val (labels, rows) = answer(sql, params)
        resultSet(labels, rows)
    }

    lazy val connection: Connection = proxy(classOf[Connection]) { (name, args) =>
        name match {
            case "getAutoCommit" => java.lang.Boolean.valueOf(autoCommit)
            case "setAutoCommit" => autoCommit = args(0).asInstanceOf[java.lang.Boolean].booleanValue; null
            case "createStatement" => statement
            case "prepareStatement" =>
                val sql = args(0).asInstanceOf[String]
                val params = scala.collection.mutable.Map[Int, String]()
                proxy(classOf[PreparedStatement]) { (n, a) =>
                    n match {
                        case "setString" => params(a(0).asInstanceOf[Integer].intValue) = a(1).asInstanceOf[String]; null
                        case "executeQuery" => query(sql, params.toSeq.sortBy(_._1).map(_._2))
                        case "close" => null
                        case other => defaultFor(other)
                    }
                }
            case "setSavepoint" =>
                savepoints += "set"
                proxy(classOf[Savepoint])((n, _) => defaultFor(n))
            case "rollback" if args.length == 1 =>
                savepoints += "rollback"
                if (rollbackFails) throw new java.sql.SQLException("the connection is gone")
                null
            case "releaseSavepoint" => savepoints += "release"; null
            case other => defaultFor(other)
        }
    }

    lazy val statement: Statement = proxy(classOf[Statement]) { (name, args) =>
        name match {
            case "execute" => run(args(0).asInstanceOf[String])
            case "executeUpdate" => run(args(0).asInstanceOf[String]); Integer.valueOf(0)
            case "executeQuery" => query(args(0).asInstanceOf[String], Nil)
            case "getConnection" => connection
            case "close" => null
            case other => defaultFor(other)
        }
    }
}
