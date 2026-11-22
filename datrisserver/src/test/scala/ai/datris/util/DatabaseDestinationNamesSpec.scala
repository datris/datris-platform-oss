package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: Postgres: database, schema, table and column names are checked on
  * every save, escaped in every statement, and never rewrite the connection
  * (plans/stories/postgres-identifier-escaping.md), Decisions 2-4.
  *
  * `DatabaseDestinationNames.problem(db)` is the validator's own
  * `destination.database` rules (required fields, the identifier charset,
  * the `;` rule for `options`, exactly one engine) plus the 63-byte rule for
  * Postgres names, as a pure function. `same(a, b)` decides the
  * grandfathering of a stored block. `postgresDropProblem(db, tenantDatabase)`
  * decides whether a delete may drop the table at all. */
class DatabaseDestinationNamesSpec extends AnyFunSuite {

    private def pgDb(dbName: String = "datris", schema: String = "public", table: String = "orders"): Database =
        Database(dbName = dbName, schema = schema, table = table, usePostgres = true)

    /** The message the save validator throws for this identifier, read from
      * the validator itself so the two can never drift. */
    private def validatorMessage(value: String, label: String): String =
        try {
            PipelineValidatorUtil.validateSqlIdentifier(value, label)
            fail("validator accepted [" + value + "] for " + label)
        } catch { case e: DatrisException => e.getMessage }

    private def cfg(db: Database, name: String = "p"): PipelineConfig =
        PipelineConfig(
            name = name,
            catalog = "Sales",
            source = Source(fileAttributes = FileAttributes(unstructuredAttributes = UnstructuredAttributes(fileExtension = "pdf"))),
            destination = Destination(objectStore = ObjectStore(prefixKey = "docs"), database = db)
        )

    private def list(xs: String*): java.util.List[String] = java.util.Arrays.asList(xs: _*)

    // ------------------------------------------------------------- problem

    test("a plain dbName, schema and table have no problem") {
        val plain = Seq(
            ("datris", "public", "orders"),
            ("Datris2", "Sales", "Orders_2026"),
            ("d", "s", "t"),
            ("my_db", "my_schema", "a" * 63)
        )
        for ((d, s, t) <- plain)
            assert(DatabaseDestinationNames.problem(pgDb(d, s, t)).isEmpty, d + "." + s + "." + t)
        // the other engines, each with what its own validator branch needs
        assert(DatabaseDestinationNames.problem(Database(dbName = "datris", table = "orders", useMongoDB = true)).isEmpty, "mongo, no schema")
        assert(
            DatabaseDestinationNames.problem(
                Database(dbName = "DB", schema = "PUBLIC", table = "ORDERS", useSnowflake = true, warehouse = "WH", credentialsSecret = "sf")
            ).isEmpty,
            "snowflake"
        )
        assert(
            DatabaseDestinationNames.problem(
                Database(dbName = "main", schema = "default", table = "orders", useDatabricks = true, credentialsSecret = "dbx")
            ).isEmpty,
            "databricks, blank warehouse"
        )
        assert(
            DatabaseDestinationNames.problem(Database(dbName = "sales", schema = "dbo", table = "orders", useSqlServer = true, credentialsSecret = "ms"))
                .isEmpty,
            "sql server"
        )
        // a COPY options entry without ';' is fine
        assert(DatabaseDestinationNames.problem(pgDb().copy(options = list("FORMAT csv", "DELIMITER ','"))).isEmpty, "options")
    }

    test("a double quote, semicolon, space, question mark, slash or empty value in dbName, schema or table returns the validator's message for that field") {
        val bad = Seq(
            "x\"; DROP TABLE public.canary; --",
            "a\"b",
            "a;b",
            "a b",
            "datris?user=nobody",
            "a/b",
            ""
        )
        for (v <- bad) {
            val clue = "[" + v + "]"
            assert(
                DatabaseDestinationNames.problem(pgDb(dbName = v)) == Some(validatorMessage(v, "destination.database.dbName")),
                clue + " dbName: " + DatabaseDestinationNames.problem(pgDb(dbName = v))
            )
            assert(
                DatabaseDestinationNames.problem(pgDb(schema = v)) == Some(validatorMessage(v, "destination.database.schema")),
                clue + " schema: " + DatabaseDestinationNames.problem(pgDb(schema = v))
            )
            assert(
                DatabaseDestinationNames.problem(pgDb(table = v)) == Some(validatorMessage(v, "destination.database.table")),
                clue + " table: " + DatabaseDestinationNames.problem(pgDb(table = v))
            )
        }
        // the literal text, so a change to the validator's wording is seen here too
        assert(
            DatabaseDestinationNames.problem(pgDb(table = "a;b")) ==
                Some("'destination.database.table' value 'a;b' is invalid. Valid characters are a-z, A-Z, 0-9 and _")
        )
        assert(DatabaseDestinationNames.problem(pgDb(schema = "")) == Some("'destination.database.schema' must not be empty"))
        // the charset rule is not Postgres-only: it is the validator's rule for every engine
        assert(
            DatabaseDestinationNames.problem(
                Database(dbName = "DB", schema = "PUBLIC", table = "a;b", useSnowflake = true, warehouse = "WH", credentialsSecret = "sf")
            ) == Some(validatorMessage("a;b", "destination.database.table"))
        )
    }

    test("a 63-byte Postgres name passes and a 64-byte one is refused; other engines have no length rule") {
        val ok = "a" * 63
        val long = "a" * 64
        assert(DatabaseDestinationNames.problem(pgDb(dbName = ok, schema = ok, table = ok)).isEmpty, "63 bytes")
        for (
            (label, db) <- Seq(
                "destination.database.dbName" -> pgDb(dbName = long),
                "destination.database.schema" -> pgDb(schema = long),
                "destination.database.table" -> pgDb(table = long)
            )
        ) {
            val p = DatabaseDestinationNames.problem(db)
            assert(p.isDefined, label + ": 64 bytes not refused")
            assert(p.get.contains(label), label + ": message does not name the field: " + p.get)
            assert(p.get.contains("63"), label + ": message does not state the limit: " + p.get)
        }
        // other engines: the same 64-character names are not refused
        assert(DatabaseDestinationNames.problem(Database(dbName = long, table = long, useMongoDB = true)).isEmpty, "mongo")
        assert(
            DatabaseDestinationNames.problem(
                Database(dbName = long, schema = long, table = long, useSnowflake = true, warehouse = "WH", credentialsSecret = "sf")
            ).isEmpty,
            "snowflake"
        )
        assert(
            DatabaseDestinationNames.problem(Database(dbName = long, schema = long, table = long, useDatabricks = true, credentialsSecret = "dbx"))
                .isEmpty,
            "databricks"
        )
        assert(
            DatabaseDestinationNames.problem(Database(dbName = long, schema = long, table = long, useSqlServer = true, credentialsSecret = "ms"))
                .isEmpty,
            "sql server"
        )
    }

    test("two engine flags, a missing table and an options entry with a semicolon return the validator's messages") {
        val engines =
            "For the 'destination.database' section, you must select exactly one of usePostgres, useMongoDB, useSnowflake, useDatabricks, or useSqlServer"
        assert(DatabaseDestinationNames.problem(pgDb().copy(useMongoDB = true)) == Some(engines + " — multiple are currently set"))
        assert(DatabaseDestinationNames.problem(pgDb().copy(usePostgres = false)) == Some(engines), "no engine")
        assert(
            DatabaseDestinationNames.problem(pgDb(table = null)) ==
                Some("If the 'destination.database' section is defined, the 'destination.database.table' must be defined")
        )
        assert(
            DatabaseDestinationNames.problem(pgDb(dbName = null)) ==
                Some("If the 'destination.database' section is defined, the 'destination.database.dbName' must be defined")
        )
        assert(
            DatabaseDestinationNames.problem(pgDb(schema = null)) ==
                Some("If the 'destination.database' section is defined, the 'destination.database.schema' must be defined")
        )
        assert(
            DatabaseDestinationNames.problem(pgDb().copy(options = list("FORMAT csv", "DELIMITER ','); DROP TABLE public.canary; --"))) ==
                Some("destination.database.options entries must not contain ';'")
        )
    }

    // ---------------------------------------------------------------- same

    test("same is true only for identical dbName, schema, table, warehouse, options and flags, nulls included") {
        val base = pgDb(table = "x\"; DROP TABLE public.canary; --").copy(warehouse = "wh", options = list("FORMAT csv"))
        val a = cfg(base)
        // identical block, other fields differ (only the block's names, options and flags are compared)
        assert(DatabaseDestinationNames.same(a, cfg(base.copy(options = list("FORMAT csv")))), "equal options in another list")
        assert(
            DatabaseDestinationNames.same(
                a,
                cfg(base.copy(keyFields = list("id"), credentialsSecret = "s", role = "r", truncateBeforeWrite = true), name = "p").copy(
                    catalog = "Finance",
                    version = 7,
                    tags = list("t")
                )
            ),
            "fields outside the compared set"
        )
        // each compared field changed alone
        val changed: Seq[(String, Database)] = Seq(
            "dbName" -> base.copy(dbName = "other"),
            "schema" -> base.copy(schema = "other"),
            "table" -> base.copy(table = "other"),
            "warehouse" -> base.copy(warehouse = "other"),
            "options" -> base.copy(options = list("FORMAT text")),
            "options entry added" -> base.copy(options = list("FORMAT csv", "HEADER")),
            "options empty" -> base.copy(options = list()),
            "usePostgres" -> base.copy(usePostgres = false),
            "useMongoDB" -> base.copy(useMongoDB = true),
            "useSnowflake" -> base.copy(useSnowflake = true),
            "useDatabricks" -> base.copy(useDatabricks = true),
            "useSqlServer" -> base.copy(useSqlServer = true),
            "null dbName" -> base.copy(dbName = null),
            "null schema" -> base.copy(schema = null),
            "null table" -> base.copy(table = null),
            "null warehouse" -> base.copy(warehouse = null),
            "null options" -> base.copy(options = null)
        )
        for ((what, db) <- changed) {
            assert(!DatabaseDestinationNames.same(a, cfg(db)), what + " changed but same")
            assert(!DatabaseDestinationNames.same(cfg(db), a), what + " changed but same (reversed)")
        }
        // nulls compared as written: null schema is not "public"
        val nullSchema = pgDb(schema = null)
        assert(DatabaseDestinationNames.same(cfg(nullSchema), cfg(nullSchema.copy())), "null schema both sides")
        assert(!DatabaseDestinationNames.same(cfg(nullSchema), cfg(pgDb(schema = "public"))), "null schema vs public")
        // a block against no stored config, or a stored config without a block
        assert(!DatabaseDestinationNames.same(a, null), "no stored config")
        assert(!DatabaseDestinationNames.same(a, cfg(null)), "stored config without a block")
        assert(!DatabaseDestinationNames.same(a, PipelineConfig(name = "p")), "stored config without a destination")
        assert(!DatabaseDestinationNames.same(cfg(null), a), "block added")
    }

    // ---------------------------------------------------- postgresDropProblem

    test("postgresDropProblem uses the tenant database when given and public for a null schema") {
        // plain names: no problem
        assert(DatabaseDestinationNames.postgresDropProblem(pgDb(), None).isEmpty, "plain")
        // a null schema is public (as the delete and the loader address it)
        assert(DatabaseDestinationNames.postgresDropProblem(pgDb(schema = null), None).isEmpty, "null schema")
        // a failing dbName is a problem unless the tenant database replaces it
        for (d <- Seq("datris?user=nobody", "a\"b", "a;b", "", null, "a" * 64)) {
            assert(DatabaseDestinationNames.postgresDropProblem(pgDb(dbName = d), None).isDefined, "[" + d + "] dbName, no tenant")
            assert(DatabaseDestinationNames.postgresDropProblem(pgDb(dbName = d), Some("datris")).isEmpty, "[" + d + "] dbName, tenant")
        }
        // the tenant database is percent-encoded into the URL and quoted in SQL (Decision 5):
        // a hyphen or URL characters are no problem; only what neither can carry is
        assert(DatabaseDestinationNames.postgresDropProblem(pgDb(), Some("acme-prod")).isEmpty, "hyphenated tenant database")
        assert(DatabaseDestinationNames.postgresDropProblem(pgDb(), Some("t?user=nobody&sslmode=disable")).isEmpty, "tenant with URL characters")
        assert(DatabaseDestinationNames.postgresDropProblem(pgDb(), Some("a\u0000b")).isDefined, "NUL tenant database")
        assert(DatabaseDestinationNames.postgresDropProblem(pgDb(), Some("")).isDefined, "empty tenant database")
        // schema and table: charset, empty and length, tenant or not
        for (
            v <- Seq("x\"; DROP TABLE public.canary; --", "a;b", "a b", "", "a" * 64);
            tenant <- Seq(None, Some("datris"))
        ) {
            assert(DatabaseDestinationNames.postgresDropProblem(pgDb(schema = v), tenant).isDefined, "[" + v + "] schema " + tenant)
            assert(DatabaseDestinationNames.postgresDropProblem(pgDb(table = v), tenant).isDefined, "[" + v + "] table " + tenant)
        }
        assert(DatabaseDestinationNames.postgresDropProblem(pgDb(table = null), None).isDefined, "null table")
        // 63 bytes still drop
        assert(DatabaseDestinationNames.postgresDropProblem(pgDb(dbName = "a" * 63, schema = "b" * 63, table = "c" * 63), None).isEmpty, "63 bytes")
    }
}
