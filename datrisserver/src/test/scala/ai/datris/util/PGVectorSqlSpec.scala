package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: pgvector schema, table and column names are escaped everywhere
  * (plans/stories/pgvector-identifier-escaping.md). `PGVectorSql` builds every
  * statement the loader, the search and the delete run, each identifier
  * through `PgIdentifier.quote`. Pure.
  *
  * The "v1.60.0" strings below are copied from what `PGVectorLoader`,
  * `PGVectorSearchUtil` and `PipelineAPIController.cleanupDestinationData`
  * built at 3891cdaf for plain names; they must stay byte-identical. */
class PGVectorSqlSpec extends AnyFunSuite {

    private def cfg(table: String, schema: String): PGVectorConfig = PGVectorConfig(table, schema, ChunkingConfig(), null, null, null)

    // ---------------------------------------------------------- tokenizer

    /** Splits `sql` into the double-quoted identifiers it contains (with `""`
      * read as one `"` inside an identifier), skipping single-quoted literals
      * (with `''` inside one). Returns the identifiers in order, the number
      * of `;` outside identifiers and literals, and whether the text ended
      * outside any identifier or literal. A quote that is not part of a
      * doubled pair inside an identifier opens or closes one. */
    private case class Scan(identifiers: List[String], semicolonsOutside: Int, balanced: Boolean)

    private def scan(sql: String): Scan = {
        val ids = List.newBuilder[String]
        var semis = 0
        var i = 0
        var state = 0 // 0 outside, 1 identifier, 2 literal
        val buf = new StringBuilder
        while (i < sql.length) {
            val c = sql.charAt(i)
            state match {
                case 0 =>
                    if (c == '"') { state = 1; buf.clear() }
                    else if (c == '\'') state = 2
                    else if (c == ';') semis += 1
                case 1 =>
                    if (c == '"') {
                        if (i + 1 < sql.length && sql.charAt(i + 1) == '"') { buf.append('"'); i += 1 }
                        else { ids += buf.toString; state = 0 }
                    } else buf.append(c)
                case _ =>
                    if (c == '\'') {
                        if (i + 1 < sql.length && sql.charAt(i + 1) == '\'') i += 1
                        else state = 0
                    }
            }
            i += 1
        }
        Scan(ids.result(), semis, state == 0)
    }

    private val BaseColumns = List("id", "text", "chunk_index", "source_pipeline", "filename")

    // ------------------------------------------------------------- v1.60.0

    test("for plain names every statement equals the text the v1.60.0 code built") {
        assert(PGVectorSql.createSchema("public") == "CREATE SCHEMA IF NOT EXISTS \"public\"")
        assert(PGVectorSql.createSchema("Vec Store") == "CREATE SCHEMA IF NOT EXISTS \"Vec Store\"")

        assert(
            PGVectorSql.createTable("public", "docs", List("author", "source"), 1024) ==
                "CREATE TABLE IF NOT EXISTS \"public\".\"docs\" (\n" +
                "    id UUID PRIMARY KEY,\n" +
                "    text TEXT,\n" +
                "    chunk_index INTEGER,\n" +
                "    source_pipeline TEXT,\n" +
                "    filename TEXT, \"author\" TEXT, \"source\" TEXT,\n" +
                "    embedding vector(1024)\n" +
                ")"
        )
        // no metadata keys (v1.60.0 with `metadata` null)
        assert(
            PGVectorSql.createTable("public", "docs", Nil, 3) ==
                "CREATE TABLE IF NOT EXISTS \"public\".\"docs\" (\n" +
                "    id UUID PRIMARY KEY,\n" +
                "    text TEXT,\n" +
                "    chunk_index INTEGER,\n" +
                "    source_pipeline TEXT,\n" +
                "    filename TEXT,\n" +
                "    embedding vector(3)\n" +
                ")"
        )

        assert(PGVectorSql.addColumn("public", "docs", "author") == "ALTER TABLE \"public\".\"docs\" ADD COLUMN IF NOT EXISTS \"author\" TEXT")
        assert(PGVectorSql.addColumn("Vec", "My-Docs", "Source File") == "ALTER TABLE \"Vec\".\"My-Docs\" ADD COLUMN IF NOT EXISTS \"Source File\" TEXT")

        assert(
            PGVectorSql.upsert("public", "docs", List("author", "source")) ==
                "INSERT INTO \"public\".\"docs\" (\"id\", \"text\", \"chunk_index\", \"source_pipeline\", \"filename\", \"author\", \"source\", \"embedding\")\n" +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)\n" +
                "ON CONFLICT (id) DO UPDATE SET \"text\" = EXCLUDED.\"text\", \"chunk_index\" = EXCLUDED.\"chunk_index\", " +
                "\"source_pipeline\" = EXCLUDED.\"source_pipeline\", \"filename\" = EXCLUDED.\"filename\", \"author\" = EXCLUDED.\"author\", " +
                "\"source\" = EXCLUDED.\"source\", \"embedding\" = EXCLUDED.\"embedding\""
        )
        assert(
            PGVectorSql.upsert("public", "docs", Nil) ==
                "INSERT INTO \"public\".\"docs\" (\"id\", \"text\", \"chunk_index\", \"source_pipeline\", \"filename\", \"embedding\")\n" +
                "VALUES (?, ?, ?, ?, ?, ?)\n" +
                "ON CONFLICT (id) DO UPDATE SET \"text\" = EXCLUDED.\"text\", \"chunk_index\" = EXCLUDED.\"chunk_index\", " +
                "\"source_pipeline\" = EXCLUDED.\"source_pipeline\", \"filename\" = EXCLUDED.\"filename\", \"embedding\" = EXCLUDED.\"embedding\""
        )

        assert(PGVectorSql.dropTable("public", "docs") == "DROP TABLE IF EXISTS \"public\".\"docs\" CASCADE")
        assert(PGVectorSql.dropTable("Vec", "a.b c") == "DROP TABLE IF EXISTS \"Vec\".\"a.b c\" CASCADE")

        assert(
            PGVectorSql.search("public", "docs", List("text", "chunk_index", "author"), "[0.1,0.2,0.3]", 5) ==
                "SELECT \"text\", \"chunk_index\", \"author\", 1 - (embedding <=> '[0.1,0.2,0.3]'::vector) AS similarity" +
                " FROM \"public\".\"docs\"" +
                " ORDER BY embedding <=> '[0.1,0.2,0.3]'::vector" +
                " LIMIT 5"
        )
    }

    // -------------------------------------------------------------- hostile

    private val hostileTables = Seq("x\"; DROP TABLE public.canary; --", "\"", "a\"\"b;", "it's\";")
    private val hostileSchemas = Seq("public", "s\"q; DROP SCHEMA public CASCADE; --", "\"\"")
    private val hostileKeys = Seq("k\"; DROP TABLE public.canary; --", "plain", "o'k\";")

    test("a table, schema or metadata key holding a double quote and a semicolon yields one statement whose only unescaped quotes delimit identifiers") {
        def one(what: String, sql: String, expected: List[String]): Unit = {
            val s = scan(sql)
            assert(s.balanced, what + ": unbalanced quotes in " + sql)
            assert(s.semicolonsOutside == 0, what + ": a ; outside an identifier in " + sql)
            assert(s.identifiers == expected, what + ": identifiers in " + sql)
        }
        for (schema <- hostileSchemas; table <- hostileTables) {
            val keys = hostileKeys.toList
            one("createSchema", PGVectorSql.createSchema(schema), List(schema))
            one("createTable", PGVectorSql.createTable(schema, table, keys, 8), List(schema, table) ++ keys)
            for (k <- keys) one("addColumn", PGVectorSql.addColumn(schema, table, k), List(schema, table, k))
            val cols = BaseColumns ++ keys ++ List("embedding")
            one("upsert", PGVectorSql.upsert(schema, table, keys), List(schema, table) ++ cols ++ cols.filter(_ != "id").flatMap(c => List(c, c)))
            one("dropTable", PGVectorSql.dropTable(schema, table), List(schema, table))
            one("search", PGVectorSql.search(schema, table, List("text") ++ keys, "[0.5,0.25]", 3), List("text") ++ keys ++ List(schema, table))
        }
    }

    test("search quotes every discovered column") {
        val cols = List("text", "Author Name", "we\"ird", "semi;colon", "chunk_index")
        val sql = PGVectorSql.search("public", "docs", cols, "[1.0]", 10)
        for (c <- cols) assert(sql.contains(PgIdentifier.quote(c)), c + " in " + sql)
        val s = scan(sql)
        assert(s.balanced && s.semicolonsOutside == 0, sql)
        assert(s.identifiers == cols ++ List("public", "docs"), sql)
        assert(sql.startsWith("SELECT " + cols.map(PgIdentifier.quote).mkString(", ") + ", 1 - (embedding <=> '[1.0]'::vector) AS similarity"), sql)
        assert(sql.endsWith(" LIMIT 10"), sql)
    }

    test("ColumnsSql has two placeholders and no interpolation") {
        val sql = PGVectorSql.ColumnsSql
        assert(sql.count(_ == '?') == 2, sql)
        assert(!sql.contains('\''), sql)
        assert(!sql.contains('"'), sql)
        assert(sql.contains("information_schema.columns"), sql)
        assert(sql.contains("table_schema"), sql)
        assert(sql.contains("table_name"), sql)
        assert(sql.contains("ORDER BY ordinal_position"), sql)
        // nothing caller-chosen is in it: the same text every time
        assert(PGVectorSql.ColumnsSql == sql)
    }

    test("tableOf: null is the legacy text null, empty throws the table-name text, others as written") {
        assert(PGVectorSql.tableOf(cfg(null, "public")) == "null")
        val e = intercept[DatrisException](PGVectorSql.tableOf(cfg("", "public")))
        assert(e.getMessage == DestinationTargets.BlankVectorTableRefusal)
        for (t <- Seq("docs", "My Docs", "pv-ok", "a.b", "   ", "x\"; DROP TABLE public.canary; --"))
            assert(PGVectorSql.tableOf(cfg(t, "public")) == t, t)
    }

    test("schemaOf: null is public, others as written") {
        assert(PGVectorSql.schemaOf(cfg("docs", null)) == "public")
        for (s <- Seq("public", "Vec", "my schema", "a-b", "s\"q"))
            assert(PGVectorSql.schemaOf(cfg("docs", s)) == s, s)
    }
}
