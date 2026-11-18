package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.nio.charset.StandardCharsets

/** Every statement the Postgres loader, the destination-type migration and
  * sample, and the pipeline delete run against a Postgres destination table,
  * plus the JDBC URL they connect with. Pure, no I/O.
  *
  * Each database, schema, table and column name in a statement goes through
  * [[PgIdentifier.quote]], so a statement addresses exactly the objects
  * named, whatever the names hold; for names without `"` the text is the one
  * earlier versions built. Catalog lookups are constants with placeholders
  * only: bind the names. A database name reaches the URL only through
  * [[jdbcUrl]], percent-encoded, so it can never add connection settings. */
object PostgresSql {

    private val Prefix = "jdbc:postgresql://"

    /** `baseUrl` (the platform secret's URL) when it already names a
      * database; otherwise `baseUrl` with `/` + `database` percent-encoded
      * appended (before any `?` query). Plain names (letters, digits, `_`,
      * `-`, `.`, `~`) are appended unchanged. `IllegalArgumentException` for
      * a null or empty name or one containing NUL. */
    def jdbcUrl(baseUrl: String, database: String): String = {
        if (database == null) throw new IllegalArgumentException("A PostgreSQL database name must not be null")
        if (database.isEmpty) throw new IllegalArgumentException("A PostgreSQL database name must not be empty")
        if (database.indexOf('\u0000') >= 0) throw new IllegalArgumentException("A PostgreSQL database name must not contain NUL")
        if (!baseUrl.startsWith(Prefix)) {
            // Not the expected shape: the earlier rule (any '/' means a database is named).
            if (baseUrl.contains("/")) baseUrl else baseUrl + "/" + encode(database)
        } else {
            val rest = baseUrl.substring(Prefix.length)
            val q = rest.indexOf('?')
            val authority = if (q >= 0) rest.substring(0, q) else rest
            if (authority.contains("/")) baseUrl
            else Prefix + authority + "/" + encode(database) + (if (q >= 0) rest.substring(q) else "")
        }
    }

    /** RFC 3986 percent-encoding of every byte outside the unreserved set
      * (`+` too, which the driver's decoder would read as a space). */
    private def encode(name: String): String = {
        val sb = new StringBuilder
        name.getBytes(StandardCharsets.UTF_8).foreach { b =>
            val c = (b & 0xff).toChar
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~')
                sb.append(c)
            else sb.append('%').append("%02X".format(b & 0xff))
        }
        sb.toString
    }

    /** `"database"."schema"."table"`. */
    def tableRef(database: String, schema: String, table: String): String =
        PgIdentifier.quote(database) + "." + PgIdentifier.qualified(schema, table)

    /** The delete's drop. */
    def dropTable(schema: String, table: String): String = "DROP TABLE IF EXISTS " + PgIdentifier.qualified(schema, table) + " CASCADE"

    /** The loader's truncateBeforeWrite. */
    def truncate(database: String, schema: String, table: String): String = "truncate table " + tableRef(database, schema, table)

    /** The loader's raw COPY up to (not including) ` FROM STDIN`. */
    def copyTarget(schema: String, table: String, columns: Seq[String]): String =
        "COPY " + PgIdentifier.qualified(schema, table) + " (" + columnList(columns) + ")"

    def createSchema(schema: String): String = "CREATE SCHEMA IF NOT EXISTS " + PgIdentifier.quote(schema) + ";"

    /** The loader's create table, up to and including the `(` that opens the
      * column list. */
    def createTableHead(database: String, schema: String, table: String): String =
        "create table if not exists " + tableRef(database, schema, table) + " ("

    /** One column name. */
    def column(name: String): String = PgIdentifier.quote(name)

    /** `a, b, c`, each quoted. */
    def columnList(columns: Seq[String]): String = columns.map(PgIdentifier.quote).mkString(", ")

    /** The loader's additive schema evolution. `colType` is the loader's
      * mapped type, never a name. */
    def addColumn(database: String, schema: String, table: String, columnName: String, colType: String): String =
        "ALTER TABLE " + tableRef(database, schema, table) + " ADD COLUMN IF NOT EXISTS " + PgIdentifier.quote(columnName) + " " + colType

    /** The loader's keyFields unique index. */
    def uniqueIndex(indexName: String, database: String, schema: String, table: String, keyFields: Seq[String]): String =
        "CREATE UNIQUE INDEX IF NOT EXISTS " + PgIdentifier.quote(indexName) + " ON " + tableRef(database, schema, table) +
            " (" + columnList(keyFields) + ")"

    /** The upsert's column list, key list and `SET` clause (None when every
      * column is a key, compared case-insensitively). */
    def upsertLists(columns: Seq[String], keyFields: Seq[String]): (String, String, Option[String]) = {
        val keySet = keyFields.map(_.toLowerCase).toSet
        val nonKey = columns.filterNot(c => keySet.contains(c.toLowerCase))
        val set =
            if (nonKey.isEmpty) None
            else Some(nonKey.map(c => PgIdentifier.quote(c) + " = EXCLUDED." + PgIdentifier.quote(c)).mkString(", "))
        (columnList(columns), columnList(keyFields), set)
    }

    /** The destination-type sample. */
    def sampleSelect(schema: String, table: String): String = "SELECT * FROM " + PgIdentifier.qualified(schema, table)

    /** The destination-type migration of one column. `pgType` is a fixed
      * Postgres type name, never a name from a config. */
    def alterColumnType(schema: String, table: String, columnName: String, pgType: String): String = {
        val c = PgIdentifier.quote(columnName)
        "ALTER TABLE " + PgIdentifier.qualified(schema, table) + " ALTER COLUMN " + c + " TYPE " + pgType + " USING NULLIF(" + c + ", '')::" + pgType
    }

    /** Column sets of the table's primary-key and unique constraints; bind
      * the schema, then the table. */
    val ConstraintColumnsSql: String =
        """SELECT array_agg(a.attname::text ORDER BY a.attname) as cols
          |FROM pg_constraint c
          |JOIN pg_class t ON c.conrelid = t.oid
          |JOIN pg_namespace n ON t.relnamespace = n.oid
          |JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(c.conkey)
          |WHERE n.nspname = ?
          |  AND t.relname = ?
          |  AND c.contype IN ('p', 'u')
          |GROUP BY c.conname""".stripMargin

    /** The table's column names; bind the database, the schema, the table. */
    val ExistingColumnsSql: String =
        """SELECT column_name FROM information_schema.columns
          |WHERE table_catalog = ? AND table_schema = ?
          |AND table_name = ?""".stripMargin

    /** The table's columns and types in order; bind the schema, the table. */
    val ColumnsOfTableSql: String =
        "SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position"

    /** The schema's base tables without an `embedding` column; bind the
      * schema twice. */
    val TablesOfSchemaSql: String =
        "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = ? " +
            "AND table_type = 'BASE TABLE' " +
            "AND table_name NOT IN (" +
            "SELECT DISTINCT table_name FROM information_schema.columns " +
            "WHERE table_schema = ? " +
            "AND column_name = 'embedding'" +
            ") " +
            "ORDER BY table_name"

    /** The schema's base tables with an `embedding` column (pgvector
      * tables); bind the schema. */
    val VectorTablesOfSchemaSql: String =
        "SELECT DISTINCT t.table_name FROM information_schema.tables t " +
            "JOIN information_schema.columns c ON t.table_name = c.table_name AND t.table_schema = c.table_schema " +
            "WHERE t.table_schema = ? " +
            "AND t.table_type = 'BASE TABLE' " +
            "AND c.column_name = 'embedding' " +
            "ORDER BY t.table_name"
}
