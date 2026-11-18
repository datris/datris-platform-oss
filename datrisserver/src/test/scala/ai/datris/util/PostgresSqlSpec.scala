package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.util.Properties
import scala.collection.JavaConverters._

/** Story: Postgres: database, schema, table and column names are checked on
  * every save, escaped in every statement, and never rewrite the connection
  * (plans/stories/postgres-identifier-escaping.md), Decisions 1, 5 and 6.
  *
  * `PostgresSql` builds every Postgres statement the loader, the type
  * migration, the sample and the delete run: every identifier through
  * `PgIdentifier.quote`, every database name through `jdbcUrl`
  * (percent-encoded into the URL path), and the catalog lookups as constants
  * with placeholders only. Pure, no I/O. */
class PostgresSqlSpec extends AnyFunSuite {

    /** Splits `sql` into its quoted identifiers (unescaped, `""` -> `"`) and
      * the text outside them. Fails on an unterminated identifier. */
    private def identifiersOf(sql: String): (Seq[String], String) = {
        val ids = Seq.newBuilder[String]
        val outside = new StringBuilder
        var i = 0
        while (i < sql.length) {
            val c = sql.charAt(i)
            if (c == '"') {
                val id = new StringBuilder
                i += 1
                var closed = false
                while (i < sql.length && !closed) {
                    if (sql.charAt(i) == '"') {
                        if (i + 1 < sql.length && sql.charAt(i + 1) == '"') { id.append('"'); i += 2 }
                        else { closed = true; i += 1 }
                    } else { id.append(sql.charAt(i)); i += 1 }
                }
                assert(closed, "unterminated identifier in: " + sql)
                ids += id.toString
                outside.append("<id>")
            } else { outside.append(c); i += 1 }
        }
        (ids.result(), outside.toString)
    }

    /** One statement: no `;` outside identifiers except one at the very end,
      * and exactly the expected identifiers, in order. */
    private def assertOneStatement(sql: String, expected: Seq[String], clue: String): Unit = {
        val (ids, outside) = identifiersOf(sql)
        assert(ids == expected, clue + ": identifiers " + ids + " in " + sql)
        assert(!outside.stripSuffix(";").contains(";"), clue + ": a statement separator outside identifiers: " + sql)
        assert(!outside.contains("--") && !outside.contains("/*"), clue + ": a comment outside identifiers: " + sql)
    }

    // ------------------------------------------------- plain names: v1.60.0

    test("for plain names every statement equals the text the v1.60.0 code built") {
        // cleanupDestinationData (delete)
        assert(PostgresSql.dropTable("public", "orders") == "DROP TABLE IF EXISTS \"public\".\"orders\" CASCADE")
        // PostgresLoader truncateBeforeWrite
        assert(PostgresSql.truncate("datris", "public", "orders") == "truncate table \"datris\".\"public\".\"orders\"")
        // PostgresLoader upsert target
        assert(PostgresSql.tableRef("datris", "public", "orders") == "\"datris\".\"public\".\"orders\"")
        // PostgresLoader raw COPY, up to FROM STDIN
        assert(PostgresSql.copyTarget("public", "orders", Seq("id", "amount")) == "COPY \"public\".\"orders\" (\"id\", \"amount\")")
        // PostgresLoader CREATE SCHEMA: now quoted (see the next tests); same schema for lower-case names
        assert(PostgresSql.createSchema("public") == "CREATE SCHEMA IF NOT EXISTS \"public\";")
        // PostgresLoader create table, the head up to the column list
        assert(PostgresSql.createTableHead("datris", "public", "orders") == "create table if not exists \"datris\".\"public\".\"orders\" (")
        assert(PostgresSql.column("amount") == "\"amount\"")
        // PostgresLoader additive schema evolution
        assert(
            PostgresSql.addColumn("datris", "public", "orders", "note", "text") ==
                "ALTER TABLE \"datris\".\"public\".\"orders\" ADD COLUMN IF NOT EXISTS \"note\" text"
        )
        // PostgresLoader keyFields unique index
        assert(
            PostgresSql.uniqueIndex("datris_uniq_orders_id_region", "datris", "public", "orders", Seq("id", "region")) ==
                "CREATE UNIQUE INDEX IF NOT EXISTS \"datris_uniq_orders_id_region\" ON \"datris\".\"public\".\"orders\" (\"id\", \"region\")"
        )
        // PostgresLoader upsert lists: columns, keys, SET clause (None when every column is a key)
        assert(
            PostgresSql.upsertLists(Seq("id", "name", "amount"), Seq("id")) ==
                ("\"id\", \"name\", \"amount\"", "\"id\"", Some("\"name\" = EXCLUDED.\"name\", \"amount\" = EXCLUDED.\"amount\""))
        )
        assert(PostgresSql.upsertLists(Seq("id", "region"), Seq("ID", "region")) == ("\"id\", \"region\"", "\"ID\", \"region\"", None))
        // DestSchemaApply sample and type migration
        assert(PostgresSql.sampleSelect("public", "orders") == "SELECT * FROM \"public\".\"orders\"")
        assert(
            PostgresSql.alterColumnType("public", "orders", "amount", "numeric") ==
                "ALTER TABLE \"public\".\"orders\" ALTER COLUMN \"amount\" TYPE numeric USING NULLIF(\"amount\", '')::numeric"
        )
        // spaces, dots, hyphens and mixed case in column names: same text as before
        assert(PostgresSql.copyTarget("public", "orders", Seq("Order Id", "a.b", "x-y")) == "COPY \"public\".\"orders\" (\"Order Id\", \"a.b\", \"x-y\")")
    }

    // ------------------------------------------------------- hostile names

    test("a schema, table, column or key field holding a double quote and a semicolon yields one statement whose only unescaped quotes delimit identifiers") {
        val evil = "x\"; DROP TABLE public.canary; --"
        val evil2 = "a\"\"b;\"c"
        for (n <- Seq(evil, evil2)) {
            assertOneStatement(PostgresSql.dropTable(n, n), Seq(n, n), "dropTable")
            assertOneStatement(PostgresSql.truncate("datris", n, n), Seq("datris", n, n), "truncate")
            assertOneStatement(PostgresSql.tableRef("datris", n, n), Seq("datris", n, n), "tableRef")
            assertOneStatement(PostgresSql.copyTarget(n, n, Seq("id", n)), Seq(n, n, "id", n), "copyTarget")
            assertOneStatement(PostgresSql.createSchema(n), Seq(n), "createSchema")
            assertOneStatement(PostgresSql.createTableHead("datris", n, n) + PostgresSql.column(n) + " text)", Seq("datris", n, n, n), "createTable")
            assertOneStatement(PostgresSql.column(n), Seq(n), "column")
            assertOneStatement(PostgresSql.addColumn("datris", n, n, n, "text"), Seq("datris", n, n, n), "addColumn")
            assertOneStatement(PostgresSql.uniqueIndex("idx", "datris", n, n, Seq(n, "id")), Seq("idx", "datris", n, n, n, "id"), "uniqueIndex")
            assertOneStatement(PostgresSql.sampleSelect(n, n), Seq(n, n), "sampleSelect")
            assertOneStatement(PostgresSql.alterColumnType(n, n, n, "int4"), Seq(n, n, n, n), "alterColumnType")
            val (cols, keys, set) = PostgresSql.upsertLists(Seq("id", n), Seq(n))
            assertOneStatement(cols, Seq("id", n), "upsert columns")
            assertOneStatement(keys, Seq(n), "upsert keys")
            assert(set.isDefined, "upsert set")
            assertOneStatement(set.get, Seq("id", "id"), "upsert set")
            val (_, keys2, set2) = PostgresSql.upsertLists(Seq(n, "amount"), Seq("id"))
            assertOneStatement(keys2, Seq("id"), "upsert keys 2")
            assertOneStatement(set2.get, Seq(n, n, "amount", "amount"), "upsert set 2")
        }
        // the escaping itself is PgIdentifier's
        assert(PostgresSql.dropTable("public", evil) == "DROP TABLE IF EXISTS " + PgIdentifier.qualified("public", evil) + " CASCADE")
        assert(PostgresSql.column(evil) == PgIdentifier.quote(evil))
    }

    test("createSchema quotes the schema") {
        assert(PostgresSql.createSchema("Sales") == "CREATE SCHEMA IF NOT EXISTS \"Sales\";")
        assert(PostgresSql.createSchema("my schema") == "CREATE SCHEMA IF NOT EXISTS \"my schema\";")
        assert(PostgresSql.createSchema("public; DROP TABLE public.canary") == "CREATE SCHEMA IF NOT EXISTS \"public; DROP TABLE public.canary\";")
        assert(PostgresSql.createSchema("s\"q") == "CREATE SCHEMA IF NOT EXISTS \"s\"\"q\";")
    }

    // ------------------------------------------------------ lookup constants

    test("the four lookup constants contain only placeholders") {
        val fixedLiterals = Set("p", "u", "BASE TABLE", "embedding", "information_schema", "pg_catalog", "pg_toast")
        val consts = Seq(
            ("ConstraintColumnsSql", PostgresSql.ConstraintColumnsSql, Some(2), "pg_constraint"),
            ("ExistingColumnsSql", PostgresSql.ExistingColumnsSql, Some(3), "information_schema.columns"),
            ("ColumnsOfTableSql", PostgresSql.ColumnsOfTableSql, Some(2), "information_schema.columns"),
            ("TablesOfSchemaSql", PostgresSql.TablesOfSchemaSql, None, "information_schema.tables")
        )
        for ((label, sql, placeholders, source) <- consts) {
            assert(sql != null && sql.contains(source), label + ": " + sql)
            val n = sql.count(_ == '?')
            placeholders match {
                case Some(p) => assert(n == p, label + ": " + n + " placeholders in " + sql)
                case None => assert(n >= 1, label + ": no placeholder in " + sql)
            }
            // every string literal is a fixed catalog word, never a name
            val literals = "'([^']*)'".r.findAllMatchIn(sql).map(_.group(1)).toSeq
            literals.foreach(l => assert(fixedLiterals.contains(l), label + ": literal '" + l + "' in " + sql))
            assert(!sql.contains("$") && !sql.contains("{"), label + ": interpolation marker in " + sql)
        }
    }

    // ---------------------------------------------------------------- jdbcUrl

    private val base = "jdbc:postgresql://postgres:5432"

    test("jdbcUrl leaves a URL that already names a database unchanged") {
        for (
            url <- Seq(
                "jdbc:postgresql://postgres:5432/datris",
                "jdbc:postgresql://db.example.com:5432/platform?sslmode=require",
                "jdbc:postgresql://h/x"
            );
            name <- Seq("datris", "other?user=nobody", "a/b")
        )
            assert(PostgresSql.jdbcUrl(url, name) == url, url + " + " + name)
    }

    test("jdbcUrl appends a plain name unchanged") {
        for (name <- Seq("datris", "my_db", "Datris2", "acme-prod", "postgres"))
            assert(PostgresSql.jdbcUrl(base, name) == base + "/" + name, name)
        assert(PostgresSql.jdbcUrl("jdbc:postgresql://localhost", "datris") == "jdbc:postgresql://localhost/datris")
    }

    test(
        "a name with ? & = # / % or a space is percent-encoded and org.postgresql.Driver.parseURL returns that exact database, the caller's user and no extra property"
    ) {
        val names = Seq(
            "datris?user=nobody",
            "x?user=nobody&sslmode=disable&options=-c search_path=evil",
            "a&b",
            "a=b",
            "a#b",
            "a/b",
            "50%",
            "%2F",
            "my db",
            "a+b"
        )
        val defaults = new Properties()
        defaults.setProperty("user", "datris")
        for (name <- names) {
            val url = PostgresSql.jdbcUrl(base, name)
            assert(url.startsWith(base + "/"), name + ": " + url)
            val path = url.substring(base.length + 1)
            for (c <- Seq('?', '&', '=', '#', '/', ' '))
                assert(!path.contains(c), name + ": raw '" + c + "' in " + url)
            val parsed = org.postgresql.Driver.parseURL(url, defaults)
            assert(parsed != null, name + ": driver rejected " + url)
            assert(parsed.getProperty("PGDBNAME") == name, name + ": database " + parsed.getProperty("PGDBNAME"))
            assert(parsed.getProperty("PGHOST") == "postgres" && parsed.getProperty("PGPORT") == "5432", name + ": host/port")
            assert(parsed.getProperty("user") == "datris", name + ": user " + parsed.getProperty("user"))
            assert(parsed.stringPropertyNames.asScala.toSet == Set("user", "PGDBNAME", "PGHOST", "PGPORT"), name + ": " + parsed.stringPropertyNames)
        }
    }

    test("a trailing slash with an empty path, with or without a query string, and a query string without a path get the encoded name") {
        val defaults = new Properties()
        defaults.setProperty("user", "datris")
        val cases = Seq(
            // (base, expected URL for "datris", extra properties the base itself carries)
            ("jdbc:postgresql://postgres:5432/", "jdbc:postgresql://postgres:5432/datris", Map.empty[String, String]),
            ("jdbc:postgresql://postgres:5432/?sslmode=require", "jdbc:postgresql://postgres:5432/datris?sslmode=require", Map("sslmode" -> "require")),
            ("jdbc:postgresql://postgres:5432?sslmode=require", "jdbc:postgresql://postgres:5432/datris?sslmode=require", Map("sslmode" -> "require"))
        )
        for ((b, plainUrl, extra) <- cases) {
            assert(PostgresSql.jdbcUrl(b, "datris") == plainUrl, b)
            for (name <- Seq("datris", "acme-prod", "datris?user=nobody", "x?user=nobody&sslmode=disable&options=-c search_path=evil", "a/b", "my db")) {
                val url = PostgresSql.jdbcUrl(b, name)
                val parsed = org.postgresql.Driver.parseURL(url, defaults)
                assert(parsed != null, b + " + " + name + ": driver rejected " + url)
                assert(parsed.getProperty("PGDBNAME") == name, b + " + " + name + ": database " + parsed.getProperty("PGDBNAME") + " in " + url)
                assert(parsed.getProperty("PGHOST") == "postgres" && parsed.getProperty("PGPORT") == "5432", b + " + " + name + ": host/port")
                assert(parsed.getProperty("user") == "datris", b + " + " + name + ": user " + parsed.getProperty("user"))
                extra.foreach { case (k, v) => assert(parsed.getProperty(k) == v, b + " + " + name + ": " + k) }
                assert(
                    parsed.stringPropertyNames.asScala.toSet == Set("user", "PGDBNAME", "PGHOST", "PGPORT") ++ extra.keySet,
                    b + " + " + name + ": " + parsed.stringPropertyNames
                )
            }
        }
    }

    test("null, empty and NUL throw") {
        for (name <- Seq(null, "", "a\u0000b", "\u0000"))
            intercept[IllegalArgumentException](PostgresSql.jdbcUrl(base, name))
    }
}
