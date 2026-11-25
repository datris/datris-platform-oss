package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, PostgresSecrets}
import org.scalatest.funsuite.AnyFunSuite

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.sql.{Connection, SQLException, Statement}

/** The landed-row sample behind a destination-type proposal reads through the
  * query path (plans/stories/postgres-query-role.md, Decision 4). When the
  * Postgres secret names a separate query login and PostgreSQL denies it the
  * table (SQLSTATE 42501), the proposal gets a plain sentence; without a
  * query login every failure passes on unchanged, as before the story.
  * Exercised through PostgresQueryUtil.poolOverride on a connection whose
  * query throws: no database, no Vault. */
class DestSchemaLandedReadSpec extends AnyFunSuite {

    private val DriverText = "ERROR: permission denied for table orders_9c1e"

    private def proxy[T](cls: Class[T])(h: (Method, Array[AnyRef]) => AnyRef): T =
        Proxy.newProxyInstance(
            getClass.getClassLoader,
            Array[Class[_]](cls),
            new InvocationHandler {
                def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef = h(m, Option(args).getOrElse(Array.empty))
            }
        ).asInstanceOf[T]

    private def default(m: Method): AnyRef =
        if (m.getReturnType == java.lang.Boolean.TYPE) java.lang.Boolean.FALSE
        else if (m.getReturnType == Integer.TYPE) Integer.valueOf(0)
        else null

    /** A connection whose query fails with `state`. */
    private def failingConnection(state: String): Connection = {
        val stmt = proxy(classOf[Statement]) { (m, _) =>
            if (m.getName == "executeQuery") throw new SQLException(DriverText, state) else default(m)
        }
        proxy(classOf[Connection]) { (m, _) =>
            if (m.getName == "createStatement") stmt else default(m)
        }
    }

    private def withLogin[A](secrets: PostgresSecrets, state: String)(f: => A): A = {
        val ds = org.mockito.Mockito.mock(classOf[com.zaxxer.hikari.HikariDataSource])
        org.mockito.Mockito.when(ds.getConnection).thenReturn(failingConnection(state))
        PostgresQueryUtil.poolOverride = ds
        PostgresQueryUtil.secretsOverride = secrets
        try f
        finally {
            PostgresQueryUtil.poolOverride = null
            PostgresQueryUtil.secretsOverride = null
        }
    }

    private def sample(): Seq[Map[String, Any]] =
        DestSchemaApply.landedReadRefusal("sales_4d2a", "orders_9c1e") {
            import scala.collection.JavaConverters._
            PostgresQueryUtil.query(PostgresSql.sampleSelect("sales_4d2a", "orders_9c1e"), "db", 50).asScala.map(_.asScala.toMap).toSeq
        }

    private val WithQueryLogin =
        PostgresSecrets("loader-user-7f3a", "loader-pass-91c2", "jdbc:postgresql://pg-host-5d8e:5432/", "reader-user-2b6d", "reader-pass-e04f")
    private val WithoutQueryLogin = PostgresSecrets("loader-user-7f3a", "loader-pass-91c2", "jdbc:postgresql://pg-host-5d8e:5432/")

    test("with a query login, permission denied on the table is a plain sentence that points at the grants") {
        val e = intercept[DatrisException](withLogin(WithQueryLogin, "42501")(sample()))
        val m = e.getMessage
        assert(m.contains("no permission to read sales_4d2a.orders_9c1e"), m)
        assert(m.contains("Separate query login"), m)
        assert(!m.contains("42501") && !m.contains(DriverText), m)
        Seq("loader-user-7f3a", "loader-pass-91c2", "pg-host-5d8e", "reader-user-2b6d", "reader-pass-e04f").foreach(v => assert(!m.contains(v), m))
    }

    test("without a query login, permission denied passes on unchanged") {
        val e = intercept[SQLException](withLogin(WithoutQueryLogin, "42501")(sample()))
        assert(e.getMessage == DriverText && e.getSQLState == "42501")
    }

    test("with a query login, another failure passes on unchanged") {
        val e = intercept[SQLException](withLogin(WithQueryLogin, "42P01")(sample()))
        assert(e.getMessage == DriverText && e.getSQLState == "42P01")
    }

    test("sampleLanded reads its Postgres sample inside landedReadRefusal") {
        val path =
            Seq("src/main/scala/", "datrisserver/src/main/scala/").map(r => Paths.get(r + "ai/datris/util/DestSchemaApply.scala")).find(Files.exists(_)).get
        val src = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        val start = src.indexOf("private[datris] def sampleLanded(")
        val body = src.substring(start, src.indexOf("case \"snowflake\"", start))
        val guard = body.indexOf("landedReadRefusal(")
        assert(guard >= 0 && guard < body.indexOf("PostgresQueryUtil.query("), body)
    }
}
