package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: pgvector schema, table and column names are escaped everywhere
  * (plans/stories/pgvector-identifier-escaping.md). `PgIdentifier` is the one
  * shared quoting helper: `"` + name with every `"` doubled + `"`. Pure. */
class PgIdentifierSpec extends AnyFunSuite {

    test("a plain name is wrapped unchanged, case, spaces, dots and hyphens kept") {
        assert(PgIdentifier.quote("documents") == "\"documents\"")
        assert(PgIdentifier.quote("Orders") == "\"Orders\"")
        assert(PgIdentifier.quote("My Docs") == "\"My Docs\"")
        assert(PgIdentifier.quote("sales.v2") == "\"sales.v2\"")
        assert(PgIdentifier.quote("pv-ok_1") == "\"pv-ok_1\"")
        assert(PgIdentifier.quote(" padded ") == "\" padded \"")
    }

    test("every double quote is doubled, a name of only quotes included") {
        assert(PgIdentifier.quote("a\"b") == "\"a\"\"b\"")
        assert(PgIdentifier.quote("\"lead") == "\"\"\"lead\"")
        assert(PgIdentifier.quote("trail\"") == "\"trail\"\"\"")
        assert(PgIdentifier.quote("\"") == "\"\"\"\"")
        assert(PgIdentifier.quote("\"\"") == "\"\"\"\"\"\"")
        assert(PgIdentifier.quote("\"\"\"") == "\"\"\"\"\"\"\"\"")
    }

    test("semicolons, single quotes, backslashes, comment markers and newlines pass through inside the quotes") {
        for (name <- Seq("a;b", "o'brien", "back\\slash", "x -- y", "x /* y */", "line1\nline2", "tab\there", "; DROP TABLE public.canary; --"))
            assert(PgIdentifier.quote(name) == "\"" + name + "\"", name)
        // combined with a double quote: only the double quote changes
        assert(PgIdentifier.quote("x\"; DROP TABLE t; --") == "\"x\"\"; DROP TABLE t; --\"")
    }

    test("null, empty and NUL throw") {
        intercept[IllegalArgumentException](PgIdentifier.quote(null))
        intercept[IllegalArgumentException](PgIdentifier.quote(""))
        intercept[IllegalArgumentException](PgIdentifier.quote("\u0000"))
        intercept[IllegalArgumentException](PgIdentifier.quote("a\u0000b"))
        intercept[IllegalArgumentException](PgIdentifier.qualified(null, "t"))
        intercept[IllegalArgumentException](PgIdentifier.qualified("public", null))
        intercept[IllegalArgumentException](PgIdentifier.qualified("", "t"))
        intercept[IllegalArgumentException](PgIdentifier.qualified("public", ""))
        intercept[IllegalArgumentException](PgIdentifier.qualified("pub\u0000lic", "t"))
        intercept[IllegalArgumentException](PgIdentifier.qualified("public", "t\u0000"))
    }

    test("qualified is quote(schema).quote(table)") {
        assert(PgIdentifier.qualified("public", "documents") == "\"public\".\"documents\"")
        for ((s, t) <- Seq("public" -> "docs", "Vec Store" -> "a.b", "s\"q" -> "t\";--", "\"" -> "\"\"")) {
            assert(PgIdentifier.qualified(s, t) == PgIdentifier.quote(s) + "." + PgIdentifier.quote(t), s + " / " + t)
        }
    }
}
