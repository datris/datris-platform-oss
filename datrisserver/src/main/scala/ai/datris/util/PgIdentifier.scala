package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** PostgreSQL quoted identifiers. Pure, no I/O.
  *
  * A quoted identifier is `"` + the name with every `"` doubled + `"`;
  * inside it every other character (case, spaces, dots, `;`, `'`, comment
  * markers, newlines) is part of the name, so the result always addresses
  * exactly the one object named. Postgres accepts no empty quoted
  * identifier and no NUL, so those are refused here. */
object PgIdentifier {

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
