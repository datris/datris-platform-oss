package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, SchemaField}

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

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
      * appended (before any `?` query). A trailing `/` with an empty path
      * names no database: the name goes after it. Plain names (letters, digits, `_`,
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
            val query = if (q >= 0) rest.substring(q) else ""
            val slash = authority.indexOf('/')
            if (slash < 0) Prefix + authority + "/" + encode(database) + query
            // A trailing '/' with nothing after it names no database.
            else if (slash == authority.length - 1) Prefix + authority + encode(database) + query
            else baseUrl
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

    /** The loader's `primary key (...)` clause. A key matching
      * `[A-Za-z0-9_]+` is written bare, exactly as the loader always wrote
      * it (so it folds to lower case as before); any other key is quoted. */
    def primaryKey(keyFields: Seq[String]): String =
        "primary key (" + keyFields.map(k => if (k != null && k.matches("[A-Za-z0-9_]+")) k else PgIdentifier.quote(k)).mkString(", ") + ")"

    /** What the loader's create table appends after the column definitions:
      * `", " + primaryKey(keys)`, or nothing when `keyFields` is null or
      * empty (an empty list names no key, so the table has none). */
    def primaryKeyClause(keyFields: java.util.List[String]): String =
        if (keyFields == null || keyFields.isEmpty) "" else ", " + primaryKey(keyFields.asScala.toList)

    /** One column name. */
    def column(name: String): String = PgIdentifier.quote(name)

    /** `a, b, c`, each quoted. */
    def columnList(columns: Seq[String]): String = columns.map(PgIdentifier.quote).mkString(", ")

    /** The Postgres type texts the loader and the destination-type migration
      * produce by mapping ([[columnType]], [[addColumnType]],
      * `DestSchemaApply.postgresType`); every other type text they send passes
      * [[ColumnTypeText.safe]]. */
    private val MappedTypes: Set[String] = Set("json", "xml", "int2", "float4", "float8", "text")

    /** The column type the loader's create table writes for `field`: `_json`
      * -> `json` and `_xml` -> `xml` by name (whatever the type holds);
      * `tinyint` / `smallint` -> `int2`, `float` -> `float4`, `double` ->
      * `float8`, `string` -> `text`; every other type as
      * [[ColumnTypeText.loadText]] gives it (the stored text when it passes
      * [[ColumnTypeText.safe]]). `DatrisException` (naming the field) for any
      * other type text, null included: it is never sent. */
    def columnType(field: SchemaField): String = {
        if (field.name != null && field.name.compareToIgnoreCase("_json") == 0) "json"
        else if (field.name != null && field.name.compareToIgnoreCase("_xml") == 0) "xml"
        else {
            val t = ColumnTypeText.loadText(field.`type`).getOrElse(refuse(field))
            if (t.compareToIgnoreCase("tinyint") == 0) "int2"
            else if (t.compareToIgnoreCase("smallint") == 0) "int2"
            else if (t.compareToIgnoreCase("float") == 0) "float4"
            else if (t.compareToIgnoreCase("double") == 0) "float8"
            else if (t.compareToIgnoreCase("string") == 0) "text"
            else t
        }
    }

    /** The column type the loader's additive schema evolution writes for
      * `field`: the text earlier versions wrote (`string` -> `text`, every
      * other type as written, via [[ColumnTypeText.loadText]]), except
      * `tinyint` -> `int2` and `double` -> `float8`, which earlier versions
      * sent as written and PostgreSQL refused (`smallint` and `float` stay as
      * written). A document column whose type is not `string` gets its
      * [[columnType]] by name; type text with no
      * [[ColumnTypeText.loadText]] is a `DatrisException`. */
    def addColumnType(field: SchemaField): String = {
        val raw = field.`type`
        if (raw != null && raw.equalsIgnoreCase("string")) "text"
        else if (ColumnTypeText.isDocumentColumn(field)) columnType(field)
        else {
            val t = ColumnTypeText.loadText(raw).getOrElse(refuse(field))
            if (t.equalsIgnoreCase("tinyint")) "int2"
            else if (t.equalsIgnoreCase("double")) "float8"
            else t
        }
    }

    /** The loader's create step: `CREATE SCHEMA` (when `schema` is set) and
      * the create table, or nothing when a field's stored type has no
      * [[ColumnTypeText.loadText]] (see [[ColumnTypeText.create]]). */
    def createPlan(
        database: String,
        schema: String,
        table: String,
        fields: Seq[SchemaField],
        keyFields: java.util.List[String]
    ): ColumnTypeText.CreatePlan =
        ColumnTypeText.create(
            fields,
            if (schema != null && schema.nonEmpty) Some(createSchema(schema)) else None,
            createTableHead(database, schema, table) + fields.map(f => column(f.name) + " " + columnType(f)).mkString(", ") +
                primaryKeyClause(keyFields) + ");"
        )

    /** The loader's evolve step over the table's existing (lower-cased)
      * columns ([[ColumnTypeText.evolve]]). */
    def evolvePlan(
        database: String,
        schema: String,
        table: String,
        fields: Seq[SchemaField],
        existing: Set[String]
    ): Either[String, ColumnTypeText.EvolvePlan] =
        ColumnTypeText.evolve(fields, existing, f => addColumn(database, schema, table, f.name, addColumnType(f)))

    private def refuse(field: SchemaField): Nothing =
        throw new DatrisException(ColumnTypeText.problem(field, "destination").getOrElse("Invalid field type passed for field '" + field.name + "'"))

    /** Second line behind [[columnType]]: `IllegalArgumentException` unless
      * `t` is one of `allowed` or passes [[ColumnTypeText.safe]]. */
    private def requireTypeText(t: String, allowed: Set[String], what: String): Unit =
        if (t == null || !(allowed.contains(t) || ColumnTypeText.safe(t)))
            throw new IllegalArgumentException(
                what + ": not a supported column type: " + (if (t == null) "null"
                                                            else t.take(ColumnTypeText.MaxShown).map(c => if (Character.isISOControl(c)) ' ' else c))
            )

    /** The loader's additive schema evolution. `colType` is
      * [[addColumnType]]'s text; `IllegalArgumentException` unless it is one
      * of `json, xml, int2, float4, float8, text` or passes
      * [[ColumnTypeText.safe]]. */
    def addColumn(database: String, schema: String, table: String, columnName: String, colType: String): String = {
        requireTypeText(colType, MappedTypes, "addColumn")
        "ALTER TABLE " + tableRef(database, schema, table) + " ADD COLUMN IF NOT EXISTS " + PgIdentifier.quote(columnName) + " " + colType
    }

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
      * Postgres type name (`DestSchemaApply.postgresType`);
      * `IllegalArgumentException` unless it is one of `json, xml, int2,
      * float4, float8, text, numeric, int4` or passes [[ColumnTypeText.safe]]. */
    def alterColumnType(schema: String, table: String, columnName: String, pgType: String): String = {
        requireTypeText(pgType, MappedTypes ++ Set("numeric", "int4"), "alterColumnType")
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
