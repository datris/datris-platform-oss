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

    // ------------------------------------------------------------------------
    // Story: pgvector: two table names that PostgreSQL cuts to the same 63
    // bytes are treated as one table
    // (plans/stories/pgvector-long-names-one-table.md), Acceptance,
    // PgIdentifierSpec. PostgreSQL keeps the first 63 UTF-8 bytes of a name
    // (NAMEDATALEN - 1) and never splits a multi-byte character. Assumed:
    // {{{
    // object PgIdentifier {
    //   val MaxBytes: Int                 // 63
    //   def kept(name: String): String    // the first 63 bytes, on a character boundary
    //   def tooLong(name: String): Boolean
    // }
    // }}}
    // The members are reached by reflection so this file compiles before
    // they exist; a missing one fails its test naming it.

    private def kept(name: String): String = QuerySafetyReflect.call(PgIdentifier, "kept", name).asInstanceOf[String]
    private def tooLong(name: String): Boolean =
        QuerySafetyReflect.call(PgIdentifier, "tooLong", name).asInstanceOf[java.lang.Boolean].booleanValue
    private def maxBytes: Int = QuerySafetyReflect.invoke(PgIdentifier, QuerySafetyReflect.method(PgIdentifier, "MaxBytes")).asInstanceOf[Integer].intValue

    private def bytes(s: String): Int = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length

    // two-byte, three-byte and four-byte (surrogate pair) letters
    private val eAcute = "é"
    private val euro = "€"
    private val smiley = "😀"

    test("kept returns a name of 63 bytes or fewer unchanged; a 64-byte ASCII name loses its last byte") {
        val short = Seq("a", "documents", "My Docs", "a" * 62, "a" * 63, euro * 21, "a" * 61 + eAcute, "a" * 59 + smiley, eAcute * 31 + "a")
        for (n <- short) {
            assert(bytes(n) <= 63, n)
            assert(kept(n) == n, n)
        }
        assert(kept("a" * 63 + "b") == "a" * 63)
        assert(bytes(kept("a" * 63 + "b")) == 63)
        assert(kept("a" * 100) == "a" * 63)
        // two names that differ only after byte 63 are kept the same
        assert(kept("x" * 63 + "_one") == kept("x" * 63 + "_two"))
        // and two that differ within 63 bytes are not
        assert(kept("x" * 62 + "a_one") != kept("x" * 62 + "b_one"))
        // letter case is kept as written (identifiers are double-quoted)
        assert(kept("A" * 70) == "A" * 63)
        // cutting twice changes nothing
        for (n <- Seq("a" * 100, "a" * 62 + eAcute + "zz", euro * 30))
            assert(kept(kept(n)) == kept(n), n)
    }

    test("kept never splits a multi-byte character") {
        // a 62-byte prefix followed by a two-byte letter keeps 62 bytes
        val p62 = "a" * 62
        assert(bytes(p62 + eAcute) == 64)
        assert(kept(p62 + eAcute) == p62)
        assert(kept(p62 + eAcute + "tail") == p62)
        assert(bytes(kept(p62 + eAcute)) == 62)
        // a three-byte letter starting at byte 62 or 63
        assert(kept("a" * 61 + euro) == "a" * 61 + euro) // ends exactly at 63
        assert(kept("a" * 62 + euro) == "a" * 62)
        assert(kept("a" * 61 + "b" + euro) == "a" * 61 + "b")
        // a four-byte letter straddling byte 63: no lone surrogate is kept
        assert(kept("a" * 60 + smiley) == "a" * 60)
        assert(kept("a" * 59 + smiley + "z") == "a" * 59 + smiley)
        assert(kept("a" * 61 + smiley).forall(c => !Character.isSurrogate(c)))
        // all multi-byte
        assert(kept(euro * 22) == euro * 21)
        assert(kept(eAcute * 32) == eAcute * 31)
        // the kept form is always valid UTF-8 that round-trips
        for (n <- Seq(p62 + eAcute, "a" * 61 + smiley, euro * 40, eAcute * 40)) {
            val k = kept(n)
            assert(new String(k.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8) == k, n)
            assert(bytes(k) <= 63 && n.startsWith(k), n)
        }
    }

    test("tooLong is false at 63 bytes and true at 64") {
        assert(maxBytes == 63)
        // one constant: the destination.database rule reads the same limit
        assert(DatabaseDestinationNames.PostgresMaxNameBytes == maxBytes)
        for (n <- Seq("a", "docs", "a" * 62, "a" * 63, euro * 21, "a" * 61 + eAcute, "a" * 59 + smiley))
            assert(!tooLong(n), n + " (" + bytes(n) + " bytes)")
        // bytes, not characters: 32 two-byte letters are 64 bytes
        for (n <- Seq("a" * 64, "a" * 63 + "b", "a" * 62 + eAcute, eAcute * 32, euro * 22, "a" * 60 + smiley, "a" * 200))
            assert(tooLong(n), n + " (" + bytes(n) + " bytes)")
        // tooLong exactly when kept changes the name
        for (n <- Seq("a" * 63, "a" * 64, "a" * 62 + eAcute, euro * 21, euro * 22))
            assert(tooLong(n) == (kept(n) != n), n)
    }
}
