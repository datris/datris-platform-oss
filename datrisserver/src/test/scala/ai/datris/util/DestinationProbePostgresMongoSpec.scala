package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{mock, verify, when}
import org.scalatest.funsuite.AnyFunSuite

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException}

/** Pipeline delete keep-data review, finding 2: the Postgres and MongoDB
  * existence probes address the object the loader writes and the
  * destination-type sample reads, and classify only "definitely absent" as
  * absent. No database: the JDBC objects are mocks, MongoDB is a function. */
class DestinationProbePostgresMongoSpec extends AnyFunSuite {

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))
    private val tenantEnv = SharedDatasets.Env(defaultBucket = "t1-data", tenantDatabase = Some("t1"), pgvectorDatabase = Some("t1"))

    private def pg(table: String, schema: String = "public", dbName: String = "datris"): PipelineConfig =
        PipelineConfig(
            name = "p",
            catalog = "Sales",
            destination = Destination(database = Database(dbName = dbName, schema = schema, table = table, usePostgres = true))
        )
    private def mongo(coll: String, dbName: String = "datris"): PipelineConfig =
        PipelineConfig(name = "m", catalog = "Sales", destination = Destination(database = Database(dbName = dbName, table = coll, useMongoDB = true)))

    private def target(c: PipelineConfig, e: SharedDatasets.Env = env) = DestinationTargets.targetsOf(c, e).head

    /** A connection whose statement answers `answer` (or throws), capturing the bound parameter and SQL. */
    private class Jdbc(answer: Long = 0, throwOnQuery: Option[SQLException] = None) {
        val conn: Connection = mock(classOf[Connection])
        val ps: PreparedStatement = mock(classOf[PreparedStatement])
        val rs: ResultSet = mock(classOf[ResultSet])
        var sql: String = _
        var url: String = _
        when(conn.prepareStatement(anyString())).thenAnswer { inv =>
            sql = inv.getArgument[String](0)
            ps
        }
        throwOnQuery match {
            case Some(e) => when(ps.executeQuery()).thenThrow(e)
            case None => when(ps.executeQuery()).thenReturn(rs)
        }
        when(rs.next()).thenReturn(true)
        when(rs.getLong(1)).thenReturn(answer)
        def open: String => Connection = u => { url = u; conn }
    }

    test("postgres: to_regclass of the quoted schema.table, bound as a parameter, in the key's database") {
        val j = new Jdbc(answer = 1)
        assert(DestinationProbe.postgresWith(target(pg("Payroll", schema = "hr")), "jdbc:postgresql://db:5432", 5, j.open))
        assert(j.url == "jdbc:postgresql://db:5432/datris")
        assert(j.sql == DestinationProbe.PostgresExistsSql)
        assert(j.sql.contains("to_regclass") && !j.sql.contains("Payroll"))
        verify(j.ps).setString(1, "\"hr\".\"Payroll\"")
        verify(j.conn).close()
    }

    test("postgres: a URL that already names a database is used as is (as the sample and the delete do); the tenant database replaces dbName") {
        val j = new Jdbc()
        assert(!DestinationProbe.postgresWith(target(pg("t")), "jdbc:postgresql://db:5432/platform", 5, j.open))
        assert(j.url == "jdbc:postgresql://db:5432/platform")
        val k = new Jdbc()
        DestinationProbe.postgresWith(target(pg("t", dbName = "datris"), tenantEnv), "jdbc:postgresql://db:5432", 5, k.open)
        assert(k.url == "jdbc:postgresql://db:5432/t1")
    }

    test("postgres: quoting keeps case and doubles embedded quotes") {
        assert(DestinationProbe.postgresRegclass("public", "Orders") == "\"public\".\"Orders\"")
        assert(DestinationProbe.postgresRegclass("a\"b", "c") == "\"a\"\"b\".\"c\"")
    }

    test("postgres: a missing database (3D000) or schema (3F000) is absent; auth, network and anything else propagate") {
        Seq("3D000", "3F000").foreach { st =>
            val open: String => Connection = _ => throw new SQLException("FATAL: does not exist", st)
            assert(!DestinationProbe.postgresWith(target(pg("t")), "jdbc:postgresql://db:5432", 5, open), st)
            val j = new Jdbc(throwOnQuery = Some(new SQLException("schema does not exist", st)))
            assert(!DestinationProbe.postgresWith(target(pg("t")), "jdbc:postgresql://db:5432", 5, j.open), st)
        }
        Seq(
            new SQLException("password authentication failed for user", "28P01"),
            new SQLException("Connection refused", "08001"),
            new SQLException("permission denied for schema hr", "42501"),
            new SQLException("canceling statement due to statement timeout", "57014")
        ).foreach { e =>
            val open: String => Connection = _ => throw e
            intercept[SQLException](DestinationProbe.postgresWith(target(pg("t")), "jdbc:postgresql://db:5432", 5, open))
            assert(!DestinationProbe.postgresMissing(e), e.getSQLState)
        }
        assert(DestinationProbe.postgresMissing(new RuntimeException("wrapped", new SQLException("x", "3D000"))))
    }

    test("postgres: a pgvector target is not a postgres probe") {
        val pgv = SharedDatasets.Key("pgtable", "pgvector", Seq("datris", "public", "chunks"))
        intercept[IllegalArgumentException](
            DestinationProbe.postgresWith(DestinationTargets.Target(pgv, Nil), "jdbc:postgresql://db:5432", 5, new Jdbc().open)
        )
    }

    test("mongodb: the loader's dbName and the key's database are checked for the exact collection name") {
        val asked = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
        def answer(existing: Set[(String, String)]): (String, String) => Boolean = (d, c) => { asked += (d -> c); existing.contains(d -> c) }
        val c = mongo("Events")
        assert(DestinationProbe.mongoWith(c, target(c), answer(Set("datris" -> "Events"))))
        assert(asked.toSeq == Seq("datris" -> "Events"))
        assert(!DestinationProbe.mongoWith(c, target(c), answer(Set("datris" -> "events"))))

        // multi-tenant: the key's tenant database and the loader's dbName
        val t = target(c, tenantEnv)
        assert(DestinationProbe.mongoDatabases(c, t) == Seq("t1", "datris"))
        assert(DestinationProbe.mongoWith(c, t, answer(Set("datris" -> "Events"))))
        assert(DestinationProbe.mongoWith(c, t, answer(Set("t1" -> "Events"))))
        assert(!DestinationProbe.mongoWith(c, t, answer(Set.empty)))
    }

    test("mongodb: any failure propagates (fail closed)") {
        val c = mongo("events")
        intercept[RuntimeException](DestinationProbe.mongoWith(c, target(c), (_, _) => throw new RuntimeException("Timed out while waiting for a server")))
    }
}
