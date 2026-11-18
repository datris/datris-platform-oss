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

/** The pgvector table probe addresses the table where the pgvector loader
  * writes and the delete's DROP drops it (the pgvector secret's URL, the
  * tenant database in multi-tenant mode, the quoted schema and table), and
  * classifies only "definitely absent" as absent. No database: mocks. */
class DestinationProbeVectorTableSpec extends AnyFunSuite {

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("vectors"))

    private def pgv(table: String, schema: String = "public", secret: String = null): PipelineConfig =
        PipelineConfig(
            name = "v",
            catalog = "Sales",
            destination = Destination(pgvector = PGVectorConfig(table, schema, ChunkingConfig(), null, null, secret))
        )

    private def target(c: PipelineConfig) = DestinationTargets.vectorTableTargets(c, env).head

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

    test("the JDBC URL is the secret's, as the loader and the delete use it; multi-tenant replaces the last path segment") {
        assert(DestinationProbe.pgvectorJdbcUrl("jdbc:postgresql://pg:5432/vectors", None) == "jdbc:postgresql://pg:5432/vectors")
        assert(DestinationProbe.pgvectorJdbcUrl("jdbc:postgresql://pg:5432", None) == "jdbc:postgresql://pg:5432")
        assert(DestinationProbe.pgvectorJdbcUrl("jdbc:postgresql://pg:5432/vectors", Some("t1")) == "jdbc:postgresql://pg:5432/t1")
        // the same regex as PGVectorLoader and the delete
        val raw = "jdbc:postgresql://pg:5432/vectors?sslmode=require"
        assert(DestinationProbe.pgvectorJdbcUrl(raw, Some("t1")) == raw.replaceFirst("/[^/]*$", "/t1"))
    }

    test("the secret is the environment's pgvector secret, else the config's, as the loader") {
        assert(DestinationProbe.pgvectorSecretName("dev/pgvector", pgv("t", secret = "mine")) == "dev/pgvector")
        assert(DestinationProbe.pgvectorSecretName(null, pgv("t", secret = "mine")) == "mine")
    }

    test("to_regclass of the quoted schema.table (case kept), bound as a parameter, on the URL as given, read-only") {
        val j = new Jdbc(answer = 1)
        assert(DestinationProbe.vectorTableWith(target(pgv("Orders", schema = "Vec")), "jdbc:postgresql://pg:5432/vectors", 5, j.open))
        assert(j.url == "jdbc:postgresql://pg:5432/vectors")
        assert(j.sql == DestinationProbe.PostgresExistsSql)
        assert(!j.sql.contains("Orders"))
        verify(j.ps).setString(1, "\"Vec\".\"Orders\"")
        verify(j.conn).setReadOnly(true)
        verify(j.conn).close()
        // a null schema is public
        val j2 = new Jdbc(answer = 0)
        assert(!DestinationProbe.vectorTableWith(target(pgv("orders", schema = null)), "jdbc:postgresql://pg:5432", 5, j2.open))
        verify(j2.ps).setString(1, "\"public\".\"orders\"")
    }

    test("a missing database or schema is 'does not exist'; any other failure propagates") {
        Seq("3D000", "3F000").foreach { state =>
            val open: String => Connection = _ => throw new SQLException("missing", state)
            assert(!DestinationProbe.vectorTableWith(target(pgv("orders")), "jdbc:postgresql://pg:5432/x", 5, open), state)
            val j = new Jdbc(throwOnQuery = Some(new SQLException("missing", state)))
            assert(!DestinationProbe.vectorTableWith(target(pgv("orders")), "jdbc:postgresql://pg:5432/x", 5, j.open), state)
        }
        Seq(
            new SQLException("password authentication failed", "28P01"),
            new SQLException("connection refused", "08001"),
            new SQLException("zero-length delimited identifier", "42602"),
            new SQLException("canceling statement due to statement timeout", "57014")
        ).foreach { e =>
            val open: String => Connection = _ => throw e
            intercept[SQLException](DestinationProbe.vectorTableWith(target(pgv("orders")), "jdbc:postgresql://pg:5432/x", 5, open))
        }
    }

    test("only a pgvector target with a plain schema and table is probed") {
        val pgKey = SharedDatasets.Key("pgtable", "postgres", Seq("datris", "public", "orders"))
        intercept[IllegalArgumentException](
            DestinationProbe.vectorTableWith(DestinationTargets.Target(pgKey, Nil, Seq("public", "orders")), "jdbc:postgresql://pg", 5, new Jdbc().open)
        )
        val pgvKey = SharedDatasets.Key("pgtable", "pgvector", Seq("vectors", "public", "orders"))
        intercept[IllegalArgumentException](DestinationProbe.vectorTableWith(
            DestinationTargets.Target(pgvKey, Nil),
            "jdbc:postgresql://pg",
            5,
            new Jdbc().open
        ))
        intercept[IllegalArgumentException](
            DestinationProbe.vectorTableWith(DestinationTargets.Target(pgvKey, Nil, Seq("pub\"lic", "orders")), "jdbc:postgresql://pg", 5, new Jdbc().open)
        )
        intercept[IllegalArgumentException](
            DestinationProbe.vectorTableWith(DestinationTargets.Target(pgvKey, Nil, Seq("", "orders")), "jdbc:postgresql://pg", 5, new Jdbc().open)
        )
    }

    test("the postgres probe still refuses a pgvector target, and exists has no pgvector family") {
        val t = target(pgv("orders"))
        intercept[IllegalArgumentException](DestinationProbe.postgresWith(t, "jdbc:postgresql://pg:5432", 5, new Jdbc().open))
        intercept[IllegalArgumentException](DestinationProbe.exists(pgv("orders"), t))
    }
}
