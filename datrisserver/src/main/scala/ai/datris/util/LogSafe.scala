package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Caller-controlled text written into a log line: control characters (line
  * breaks included), DEL and the Unicode line and paragraph separators are
  * written as a backslash-u escape of four hex digits, so one request can never produce, or forge, more than
  * one log line. Everything else is kept as is. Null reads `null`. */
object LogSafe {

    def text(s: String): String =
        if (s == null) "null"
        else if (!s.exists(unsafe)) s
        else {
            val b = new StringBuilder(s.length + 16)
            s.foreach(c => if (unsafe(c)) b.append("\\u%04x".format(c.toInt)) else b.append(c))
            b.toString
        }

    private def unsafe(c: Char): Boolean =
        c < 0x20 || c == 0x7f || c == '\u0085' || c == ' ' || c == ' '
}
