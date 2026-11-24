package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.nio.charset.StandardCharsets

/** PostgreSQL quoted identifiers. Pure, no I/O.
  *
  * A quoted identifier is `"` + the name with every `"` doubled + `"`;
  * inside it every other character (case, spaces, dots, `;`, `'`, comment
  * markers, newlines) is part of the name, so the result always addresses
  * exactly the one object named. Postgres accepts no empty quoted
  * identifier and no NUL, so those are refused here.
  *
  * PostgreSQL keeps only the first [[MaxBytes]] UTF-8 bytes of a name
  * (quoted or not) and silently cuts the rest; [[kept]] is the name it
  * actually uses. The count is in UTF-8 bytes, which assumes the database
  * encoding is UTF8 (the bundled database's): PostgreSQL cuts in the
  * database encoding, so in a single-byte encoding such as LATIN1 it keeps
  * a name with non-ASCII letters that [[kept]] cuts. */
object PgIdentifier {

    /** PostgreSQL's identifier limit (NAMEDATALEN - 1), in UTF-8 bytes. */
    val MaxBytes: Int = 63

    /** The name PostgreSQL keeps for `name`: its first [[MaxBytes]] UTF-8
      * bytes, cut on a character boundary (a multi-byte character that does
      * not fit is dropped whole). A name of [[MaxBytes]] bytes or fewer is
      * returned unchanged; null stays null. */
    def kept(name: String): String = keptBytes(name, MaxBytes)

    /** True when PostgreSQL would cut `name` (more than [[MaxBytes]] UTF-8
      * bytes). False for null. */
    def tooLong(name: String): Boolean = name != null && name.getBytes(StandardCharsets.UTF_8).length > MaxBytes

    /** The first `max` UTF-8 bytes of `name`, cut on a character boundary,
      * as PostgreSQL truncates an over-long identifier. The one
      * implementation of the cut ([[kept]], `IngestColumnNames`). */
    private[util] def keptBytes(name: String, max: Int): String = {
        if (name == null) return null
        var bytes = 0
        var i = 0
        var done = false
        while (i < name.length && !done) {
            val cp = name.codePointAt(i)
            val n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length
            if (bytes + n > max) done = true
            else {
                bytes += n
                i += Character.charCount(cp)
            }
        }
        name.substring(0, i)
    }

    /** `name` as one quoted identifier. `IllegalArgumentException` for a
      * null or empty name or one containing NUL. */
    def quote(name: String): String = {
        if (name == null) throw new IllegalArgumentException("A PostgreSQL identifier must not be null")
        if (name.isEmpty) throw new IllegalArgumentException("A PostgreSQL identifier must not be empty")
        if (name.indexOf('\u0000') >= 0) throw new IllegalArgumentException("A PostgreSQL identifier must not contain NUL")
        "\"" + name.replace("\"", "\"\"") + "\""
    }

    /** `quote(schema)` + `.` + `quote(table)`; throws as [[quote]]. */
    def qualified(schema: String, table: String): String = quote(schema) + "." + quote(table)
}
