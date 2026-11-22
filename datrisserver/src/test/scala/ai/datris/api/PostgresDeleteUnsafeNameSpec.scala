package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.{DatabaseDestinationNames, PgIdentifier, PostgresSql, SharedDatasets}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Postgres: database, schema, table and column names are checked on
  * every save, escaped in every statement, and never rewrite the connection
  * (plans/stories/postgres-identifier-escaping.md), Decision 4: a delete (or
  * reset) never connects or drops for a Postgres block whose effective
  * database, schema or table fails the rule (charset, empty, over 63 bytes);
  * the response `warnings` gain one line
  * (`PipelineAPIController.postgresNameDeleteWarning(config, env)`). Every
  * other Postgres delete drops exactly `PostgresSql.dropTable(schema, table)`.
  * Pure: the JDBC drop itself is covered by the manual Verify. */
class PostgresDeleteUnsafeNameSpec extends AnyFunSuite {

    private val env = SharedDatasets.Env(defaultBucket = "dev-data", tenantDatabase = None, pgvectorDatabase = Some("datris"))

    private def pg(dbName: String = "datris", schema: String = "public", table: String = "orders"): PipelineConfig =
        PipelineConfig(
            name = "pg_q",
            catalog = "Sales",
            source = Source(fileAttributes = FileAttributes(unstructuredAttributes = UnstructuredAttributes(fileExtension = "pdf"))),
            destination = Destination(
                objectStore = ObjectStore(prefixKey = "docs"),
                database = Database(dbName = dbName, schema = schema, table = table, usePostgres = true)
            )
        )

    private def assertOneLine(w: Option[String], clue: String): Unit = {
        assert(w.isDefined, clue + ": no warning")
        assert(w.get.trim.nonEmpty, clue + ": empty warning")
        assert(!w.get.contains('\n') && !w.get.contains('\r'), clue + ": more than one line: " + w.get)
    }

    test(
        "postgresNameDeleteWarning is one line for a failing dbName, schema or table and for a 64-byte table, None for plain names and for non-Postgres blocks"
    ) {
        val failing = Seq("x\"; DROP TABLE public.canary; --", "a\"b", "a;b", "a b", "datris?user=nobody", "a/b", "")
        for (v <- failing) {
            assertOneLine(PipelineAPIController.postgresNameDeleteWarning(pg(dbName = v), env), "[" + v + "] dbName")
            assertOneLine(PipelineAPIController.postgresNameDeleteWarning(pg(schema = v), env), "[" + v + "] schema")
            assertOneLine(PipelineAPIController.postgresNameDeleteWarning(pg(table = v), env), "[" + v + "] table")
        }
        assertOneLine(PipelineAPIController.postgresNameDeleteWarning(pg(table = "a" * 64), env), "64-byte table")
        assertOneLine(PipelineAPIController.postgresNameDeleteWarning(pg(schema = "s" * 64), env), "64-byte schema")
        assertOneLine(PipelineAPIController.postgresNameDeleteWarning(pg(table = null), env), "null table")
        // the warning is given exactly when the drop is skipped
        for (c <- Seq(pg(table = failing.head), pg(table = "a" * 64), pg(dbName = "datris?user=nobody")))
            assert(DatabaseDestinationNames.postgresDropProblem(c.destination.database, env.tenantDatabase).isDefined)

        // plain names, a null schema (public), 63 bytes: no warning
        for (c <- Seq(pg(), pg(schema = null), pg(dbName = "Datris2", schema = "Sales", table = "Orders_2026"), pg(table = "a" * 63)))
            assert(PipelineAPIController.postgresNameDeleteWarning(c, env).isEmpty, c.destination.database.toString)
        // the tenant database replaces dbName, as the delete connects
        val tenant = env.copy(tenantDatabase = Some("acme"))
        assert(PipelineAPIController.postgresNameDeleteWarning(pg(dbName = "datris?user=nobody"), tenant).isEmpty, "tenant database")

        // non-Postgres blocks and configs without one
        def other(db: Database): PipelineConfig = pg().copy(destination = Destination(database = db))
        val nonPg = Seq(
            other(Database(dbName = "datris", table = "a;b", useMongoDB = true)),
            other(Database(dbName = "DB", schema = "PUBLIC", table = "a\"b", useSnowflake = true, warehouse = "WH", credentialsSecret = "sf")),
            other(Database(dbName = "main", schema = "default", table = "a" * 64, useDatabricks = true, credentialsSecret = "dbx")),
            other(Database(dbName = "sales", schema = "dbo", table = "a b", useSqlServer = true, credentialsSecret = "ms")),
            PipelineConfig(name = "x"),
            PipelineConfig(name = "x", destination = Destination()),
            PipelineConfig(name = "v", destination = Destination(pgvector = PGVectorConfig("a\"b", "public", ChunkingConfig(), null, null, null)))
        )
        for (c <- nonPg) assert(PipelineAPIController.postgresNameDeleteWarning(c, env).isEmpty, String.valueOf(c.destination))
    }

    test("the drop statement for plain names is PostgresSql.dropTable of the same names") {
        // the v1.60.0 delete text for plain names
        for ((s, t) <- Seq(("public", "orders"), ("Sales", "Orders_2026"), ("s", "a" * 63))) {
            assert(DatabaseDestinationNames.postgresDropProblem(pg(schema = s, table = t).destination.database, None).isEmpty, s + "." + t)
            assert(PostgresSql.dropTable(s, t) == "DROP TABLE IF EXISTS \"" + s + "\".\"" + t + "\" CASCADE", s + "." + t)
            assert(PostgresSql.dropTable(s, t) == "DROP TABLE IF EXISTS " + PgIdentifier.qualified(s, t) + " CASCADE")
        }
        // a stored null schema is dropped in public, as before
        assert(DatabaseDestinationNames.postgresDropProblem(pg(schema = null).destination.database, None).isEmpty)
        assert(PostgresSql.dropTable("public", "orders") == "DROP TABLE IF EXISTS \"public\".\"orders\" CASCADE")
    }
}
