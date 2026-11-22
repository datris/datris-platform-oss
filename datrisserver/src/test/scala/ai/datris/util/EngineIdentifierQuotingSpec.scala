package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

/** Story: Column names from ingested data: checked once at ingest, quoted at
  * every destination, never able to change what a load does
  * (plans/stories/ingest-column-name-escaping.md), Decision 1.
  *
  * The engines' own identifier helpers, which every Snowflake, Databricks,
  * SQL Server and Iceberg statement routes column names through. No change
  * in behaviour; pinned so the call-site specs can lean on them. The Iceberg
  * one becomes readable from the package:
  * {{{
  * object IcebergWriter { private[util] def quote(name: String): String }   // was private
  * }}} */
class EngineIdentifierQuotingSpec extends AnyFunSuite {

    private case class Engine(label: String, open: String, close: String, quoteIt: String => String, alwaysQuotes: Boolean)

    private val engines = Seq(
        Engine("SnowflakeConnectionUtil.ident", "\"", "\"", SnowflakeConnectionUtil.ident, alwaysQuotes = false),
        Engine("SnowflakeConnectionUtil.quote", "\"", "\"", SnowflakeConnectionUtil.quote, alwaysQuotes = true),
        Engine("DatabricksConnectionUtil.ident", "`", "`", DatabricksConnectionUtil.ident, alwaysQuotes = false),
        Engine("DatabricksConnectionUtil.quote", "`", "`", DatabricksConnectionUtil.quote, alwaysQuotes = true),
        Engine("SqlServerConnectionUtil.ident", "[", "]", SqlServerConnectionUtil.ident, alwaysQuotes = false),
        Engine("IcebergWriter.quote", "`", "`", IcebergWriter.quote, alwaysQuotes = true)
    )

    /** `quoted` is exactly one identifier: opens and closes with the engine's
      * delimiters and every inner closing delimiter is doubled. Returns the
      * unescaped name. */
    private def unquote(e: Engine, quoted: String): String = {
        assert(quoted.startsWith(e.open) && quoted.endsWith(e.close) && quoted.length >= 2, e.label + ": " + quoted)
        val inner = quoted.substring(e.open.length, quoted.length - e.close.length)
        val sb = new StringBuilder
        var i = 0
        while (i < inner.length) {
            if (inner.startsWith(e.close, i)) {
                assert(inner.startsWith(e.close + e.close, i), e.label + ": an undoubled closing delimiter inside " + quoted)
                sb.append(e.close)
                i += 2 * e.close.length
            } else { sb.append(inner.charAt(i)); i += 1 }
        }
        sb.toString
    }

    test("a plain name is returned as today") {
        for (e <- engines; n <- Seq("amount", "order_id", "_json", "A1")) {
            val out = e.quoteIt(n)
            if (e.alwaysQuotes) assert(out == e.open + n + e.close, e.label + ": " + out)
            else assert(out == n, e.label + ": " + out)
        }
        // today's quoting of non-simple plain-looking names
        assert(SnowflakeConnectionUtil.ident("unit price") == "\"unit price\"")
        assert(SnowflakeConnectionUtil.ident("amount$2") == "amount$2")
        assert(DatabricksConnectionUtil.ident("unit price") == "`unit price`")
        assert(SqlServerConnectionUtil.ident("unit price") == "[unit price]")
        assert(SqlServerConnectionUtil.ident("order") == "[order]", "a reserved word is bracketed")
        assert(IcebergWriter.quote("unit price") == "`unit price`")
    }

    test("the engine's closing delimiter is doubled") {
        for (e <- engines) {
            val n = "a" + e.close + "b" + e.close + e.close + "c"
            val out = e.quoteIt(n)
            assert(unquote(e, out) == n, e.label + ": " + out)
            assert(out == e.open + n.replace(e.close, e.close + e.close) + e.close, e.label + ": " + out)
        }
        assert(SnowflakeConnectionUtil.ident("a\"b") == "\"a\"\"b\"")
        assert(DatabricksConnectionUtil.ident("a`b") == "`a``b`")
        assert(SqlServerConnectionUtil.ident("a]b") == "[a]]b]")
        assert(SqlServerConnectionUtil.ident("a[b") == "[a[b]", "an opening bracket needs no escaping")
        assert(IcebergWriter.quote("a`b") == "`a``b`")
    }

    test("semicolons, comment markers, the other engines' delimiters and spaces stay inside the quotes") {
        val hostile = Seq(
            "x; DROP TABLE canary; --",
            "x /* c */ y",
            "a\"b`c[d]e",
            "  spaced  out  ",
            "x'; select 1; --",
            "a\nb"
        )
        for (e <- engines; n <- hostile) {
            val out = e.quoteIt(n)
            assert(unquote(e, out) == n, e.label + ": " + n + " -> " + out)
        }
    }
}
