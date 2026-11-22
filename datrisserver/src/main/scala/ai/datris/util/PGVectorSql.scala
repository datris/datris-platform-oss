package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, PGVectorConfig}

/** Every SQL statement the pgvector loader, the pgvector search and the
  * pipeline delete run against a pgvector table. Pure, no I/O. Each schema,
  * table and column name goes through [[PgIdentifier.quote]], so a statement
  * addresses exactly the one table (and column) named, whatever the name
  * holds. For names without `"` the text is the one earlier versions built. */
object PGVectorSql {

    /** The fixed columns every pgvector table has, in insert order (the
      * metadata columns follow, then `embedding`). */
    val BaseColumns: List[String] = List("id", "text", "chunk_index", "source_pipeline", "filename")

    /** The configured schema; null is `public`. */
    def schemaOf(p: PGVectorConfig): String = Option(p.schemaName).getOrElse("public")

    /** The configured table. Null is the text `null`, the table earlier
      * versions wrote such a pipeline into (the loader warns); an empty name
      * fails with `DestinationTargets.BlankVectorTableRefusal`. */
    def tableOf(p: PGVectorConfig): String =
        if (p.tableName == null) "null"
        else if (p.tableName.isEmpty) throw new DatrisException(DestinationTargets.BlankVectorTableRefusal)
        else p.tableName

    def createSchema(schema: String): String = "CREATE SCHEMA IF NOT EXISTS " + PgIdentifier.quote(schema)

    def createTable(schema: String, table: String, metadataKeys: Seq[String], dimension: Int): String = {
        val metadataColumns = metadataKeys.map(k => ", " + PgIdentifier.quote(k) + " TEXT").mkString
        "CREATE TABLE IF NOT EXISTS " + PgIdentifier.qualified(schema, table) + " (\n" +
            "    id UUID PRIMARY KEY,\n" +
            "    text TEXT,\n" +
            "    chunk_index INTEGER,\n" +
            "    source_pipeline TEXT,\n" +
            "    filename TEXT" + metadataColumns + ",\n" +
            "    embedding vector(" + dimension + ")\n" +
            ")"
    }

    def addColumn(schema: String, table: String, key: String): String =
        "ALTER TABLE " + PgIdentifier.qualified(schema, table) + " ADD COLUMN IF NOT EXISTS " + PgIdentifier.quote(key) + " TEXT"

    /** The columns [[upsert]] binds, in parameter order. */
    def upsertColumns(metadataKeys: Seq[String]): List[String] = BaseColumns ++ metadataKeys ++ List("embedding")

    def upsert(schema: String, table: String, metadataKeys: Seq[String]): String = {
        val cols = upsertColumns(metadataKeys)
        val placeholders = cols.map(_ => "?").mkString(", ")
        val updateSet = cols.filter(_ != "id").map(c => PgIdentifier.quote(c) + " = EXCLUDED." + PgIdentifier.quote(c)).mkString(", ")
        "INSERT INTO " + PgIdentifier.qualified(schema, table) + " (" + cols.map(PgIdentifier.quote).mkString(", ") + ")\n" +
            "VALUES (" + placeholders + ")\n" +
            "ON CONFLICT (id) DO UPDATE SET " + updateSet
    }

    def dropTable(schema: String, table: String): String = "DROP TABLE IF EXISTS " + PgIdentifier.qualified(schema, table) + " CASCADE"

    /** The similarity search. `vectorLiteral` is the server-built `[n,n,...]`
      * of the query embedding (numbers only), `topK` the row limit. */
    def search(schema: String, table: String, columns: Seq[String], vectorLiteral: String, topK: Int): String =
        "SELECT " + columns.map(PgIdentifier.quote).mkString(", ") + ", 1 - (embedding <=> '" + vectorLiteral + "'::vector) AS similarity" +
            " FROM " + PgIdentifier.qualified(schema, table) +
            " ORDER BY embedding <=> '" + vectorLiteral + "'::vector" +
            " LIMIT " + topK

    /** The table's column names in order; bind the schema, then the table. */
    val ColumnsSql: String =
        "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position"
}
