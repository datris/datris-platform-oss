package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException

/** The longest query text the PostgreSQL, Snowflake, SQL Server and
  * Databricks validators accept. Each `validateQuery` calls [[check]] first,
  * before trimming the text and before any pattern, so the length counted is
  * that of the text as received. The limit is a second line: the patterns
  * those validators run are written to take time in proportion to the text's
  * length, and the limit bounds that time and what is logged. A refusal is a
  * plain [[DatrisException]], so each route answers with the status it
  * already gives any other validation refusal. */
object QueryTextLimit {

    /** Characters (`String.length`, UTF-16 units). */
    val MaxLength: Int = 1000000

    /** Refuses `sql` when it is longer than [[MaxLength]]; null passes (the
      * validator refuses an empty query itself). */
    def check(sql: String): Unit =
        if (sql != null && sql.length > MaxLength)
            throw new DatrisException(
                "Query text is too long: " + sql.length + " characters (the limit is " + MaxLength + ")"
            )
}
