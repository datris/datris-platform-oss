package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.Assertions._

/** Test helper (plans/stories/ingest-column-name-escaping.md): splits a
  * statement into its delimited identifiers (unescaped), its single-quoted
  * string literals, and the text outside both, for one engine's identifier
  * delimiters. The closing delimiter is escaped by doubling; a `'` literal by
  * `''`. Fails on an unterminated identifier or literal. */
object SqlIdentifierScan {

    final case class Scan(identifiers: Seq[String], literals: Seq[String], outside: String)

    def scan(sql: String, open: Char, close: Char): Scan = {
        val ids = Seq.newBuilder[String]
        val lits = Seq.newBuilder[String]
        val outside = new StringBuilder
        var i = 0
        def readUntil(end: Char, what: String): String = {
            val sb = new StringBuilder
            var closed = false
            while (i < sql.length && !closed) {
                val c = sql.charAt(i)
                if (c == end) {
                    if (i + 1 < sql.length && sql.charAt(i + 1) == end) { sb.append(end); i += 2 }
                    else { closed = true; i += 1 }
                } else { sb.append(c); i += 1 }
            }
            assert(closed, "unterminated " + what + " in: " + sql)
            sb.toString
        }
        while (i < sql.length) {
            val c = sql.charAt(i)
            if (c == open) { i += 1; ids += readUntil(close, "identifier"); outside.append("<id>") }
            else if (c == '\'') { i += 1; lits += readUntil('\'', "string literal"); outside.append("<lit>") }
            else { outside.append(c); i += 1 }
        }
        Scan(ids.result(), lits.result(), outside.toString)
    }

    /** One statement: no `;` outside identifiers and literals (a single
      * trailing one allowed when `trailingSemicolon`), no comment marker, and
      * every delimited identifier is one of `allowed`; `mustContain` appear
      * among them. */
    def assertOneStatement(
        sql: String,
        open: Char,
        close: Char,
        allowed: Set[String],
        mustContain: Seq[String],
        clue: String,
        trailingSemicolon: Boolean = false
    ): Scan = {
        val s = scan(sql, open, close)
        val body = if (trailingSemicolon) s.outside.stripSuffix(";") else s.outside
        assert(!body.contains(";"), clue + ": a statement separator outside identifiers: " + sql)
        assert(!s.outside.contains("--") && !s.outside.contains("/*"), clue + ": a comment outside identifiers: " + sql)
        s.identifiers.foreach(id => assert(allowed.contains(id), clue + ": unexpected identifier [" + id + "] in " + sql))
        mustContain.foreach(n => assert(s.identifiers.contains(n), clue + ": [" + n + "] is not one delimited identifier in " + sql))
        s
    }
}
