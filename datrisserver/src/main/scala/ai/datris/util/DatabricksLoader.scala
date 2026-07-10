package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model._
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.Statement
import java.util.UUID
import scala.collection.JavaConverters._
import scala.util.Try

/** Databricks (Unity Catalog managed Delta) SQL destination. Mirrors
 *  [[SnowflakeLoader]] — same create-table / truncate / raw-vs-upsert routing
 *  and the same dest-schema projection — but loads through Databricks' bulk
 *  path: stage the transform output as a local CSV, `PUT` it into a Unity
 *  Catalog volume (`datris_staging`, auto-created in the target schema), then
 *  one atomic SQL statement per run: `COPY INTO` for inserts, `MERGE` for
 *  upserts, `INSERT OVERWRITE` for truncate-and-load. Delta has no
 *  multi-statement transactions, so atomicity comes from each load being a
 *  single Delta commit — a failed load never leaves the table truncated.
 *
 *  Credentials are a Platform-tab secret named by `Database.credentialsSecret`
 *  (resolved by [[CredentialResolver.resolveDatabricks]]); `dbName` is the
 *  Unity Catalog catalog and `warehouse` is the SQL warehouse ID. */
class DatabricksLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[DatabricksLoader])
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil
    private val db = config.destination.database

    private val stagingVolumeName = "datris_staging"

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Loading the data into Databricks catalog: " + db.dbName + ", schema: " + db.schema + ", table: " + db.table)

        if (db.useTransaction)
            statusUtil.info("processing", "Databricks (Delta) has no multi-statement transactions; 'useTransaction' is ignored — each load runs as a single atomic Delta commit")

        var dataFile: Path = null
        try {
            DatabricksConnectionUtil.withConnection(db, msg => statusUtil.info("processing", msg)) { conn =>
                val statement = conn.createStatement()
                var stagedPath: String = null
                try {
                    dataFile = createStagingFile()
                    if (!db.manageTableManually)
                        createTableIfUndefined(statement)
                    stagedPath = volumeFilePath()
                    putFile(statement, dataFile, stagedPath)
                    loadData(statement, stagedPath)
                } finally {
                    if (stagedPath != null)
                        Try(statement.execute("REMOVE '" + sqlLiteral(stagedPath) + "'"))
                    Try(statement.close())
                }
            }
            sendNotification()
            statusUtil.info("end", "Process completed")
        } finally {
            if (dataFile != null) Try(Files.deleteIfExists(dataFile))
        }
    }

    /** Write the projected destination CSV to a local temp file for PUT. Unlike
     *  the Snowflake staging file, this one carries a header row of the
     *  destination column names, so every load statement can reference the CSV
     *  columns by name instead of by position. */
    private def createStagingFile(): Path = {
        val header = copyFields().map(f => csvHeaderCell(f.name)).mkString(csvDelimiter())
        val data = if (jobContext.data.rows != null && jobContext.data.rows.nonEmpty)
            header + "\n" + projectRowsToDestSchema(jobContext.data.rows).mkString("\n")
        else if (jobContext.data.rawData != null)
            header + "\n" + "\"" + jobContext.data.rawData.replace("\"", "\"\"") + "\""
        else
            throw new DatrisException("No data to load — both rows and rawData are empty")

        val file = Files.createTempFile("databricks-load-", ".csv")
        file.toFile.deleteOnExit()
        Files.write(file, data.getBytes(StandardCharsets.UTF_8))
        file
    }

    /** Reorder/drop source columns to match the destination schema. Mirrors
     *  PostgresLoader.projectRowsToDestSchema. */
    private def projectRowsToDestSchema(rows: List[String]): List[String] = {
        val sourceFields = config.source.schemaProperties.fields.asScala.toList
        val destFields = config.destination.schemaProperties.fields.asScala.toList

        val delimiter = csvDelimiter()

        if (sourceFields.size == destFields.size && sourceFields.map(_.name.toLowerCase) == destFields.map(_.name.toLowerCase))
            return rows

        val sourceIndex: Map[String, Int] = {
            if (jobContext.data.header != null && jobContext.data.header.nonEmpty)
                jobContext.data.header.zipWithIndex.map { case (name, idx) => name.toLowerCase -> idx }.toMap
            else
                sourceFields.zipWithIndex.map { case (f, idx) => f.name.toLowerCase -> idx }.toMap
        }

        val projectedDest = destFields.filter(f => sourceIndex.contains(f.name.toLowerCase))
        val destColumnIndices = projectedDest.map(f => sourceIndex(f.name.toLowerCase))

        if (projectedDest.size < destFields.size) {
            val missing = destFields.filterNot(f => sourceIndex.contains(f.name.toLowerCase)).map(_.name)
            statusUtil.info("processing", "Dropped columns (will be NULL in destination): " + missing.mkString(", "))
        }

        rows.map { row =>
            val columns = row.split(delimiter, -1).toList
            destColumnIndices.map(idx => if (idx < columns.size) columns(idx) else "").mkString(delimiter)
        }
    }

    private def loadData(statement: Statement, stagedPath: String): Unit = {
        statusUtil.info("processing", "Loading data into " + db.table)

        // Same routing as Postgres/Snowflake: truncate replaces the contents
        // (atomically, via INSERT OVERWRITE); keyFields + not-truncating =>
        // upsert (MERGE); otherwise straight insert (COPY INTO).
        if (db.truncateBeforeWrite) insertOverwrite(statement, stagedPath)
        else if (db.keyFields != null && !db.keyFields.isEmpty) mergeInto(statement, stagedPath)
        else copyInsert(statement, stagedPath)
    }

    /** Insert path: COPY INTO the target from the staged CSV, with an explicit
     *  SELECT-transform so every column is CAST to the destination type (and
     *  `_json` is PARSE_JSON'd into its VARIANT column). */
    private def copyInsert(statement: Statement, stagedPath: String): Unit = {
        val fields = copyFields()
        val copy = s"COPY INTO ${qualifiedTable()} " +
            s"FROM (SELECT ${selectList(fields)} FROM '${sqlLiteral(stagedPath)}') " +
            "FILEFORMAT = CSV " +
            s"FORMAT_OPTIONS (${csvFormatOptions()}) " +
            "COPY_OPTIONS ('mergeSchema' = 'false')"

        statusUtil.info("processing", "COPY command: " + copy)
        val rs = statement.executeQuery(copy)
        // COPY INTO returns a one-row result set; read the inserted-row count.
        var loaded = 0L
        try {
            val meta = rs.getMetaData
            val cols = (1 to meta.getColumnCount).map(i => meta.getColumnLabel(i).toLowerCase)
            val idx = math.max(cols.indexOf("num_inserted_rows"), cols.indexOf("num_affected_rows"))
            while (rs.next()) {
                if (idx >= 0) loaded += rs.getLong(idx + 1)
            }
        } finally {
            rs.close()
        }
        statusUtil.info("processing", "Rows loaded into target: " + loaded)
    }

    /** Upsert path: MERGE straight from the staged CSV (via read_files) — SQL
     *  warehouses have no session temp tables, and a MERGE from a subquery is
     *  one atomic Delta commit, so no staging table is needed. Non-key columns
     *  are fully overwritten (including NULLs), matching the Postgres/Snowflake
     *  contract. */
    private def mergeInto(statement: Statement, stagedPath: String): Unit = {
        val fields = copyFields()
        val keyNames = db.keyFields.asScala.toList
        val keySet = keyNames.map(_.toLowerCase).toSet
        val colNames = fields.map(_.name)
        val nonKey = colNames.filterNot(c => keySet.contains(c.toLowerCase))

        val onClause = keyNames.map(k => s"t.${ident(k)} = s.${ident(k)}").mkString(" AND ")
        val insertCols = colNames.map(ident).mkString(", ")
        val insertVals = colNames.map(c => "s." + ident(c)).mkString(", ")

        val merge = new StringBuilder()
        merge.append(s"MERGE INTO ${qualifiedTable()} t USING (SELECT ${selectList(fields)} FROM ${readFiles(stagedPath)}) s ON $onClause ")
        if (nonKey.nonEmpty) {
            val setClause = nonKey.map(c => s"t.${ident(c)} = s.${ident(c)}").mkString(", ")
            merge.append(s"WHEN MATCHED THEN UPDATE SET $setClause ")
        }
        merge.append(s"WHEN NOT MATCHED THEN INSERT ($insertCols) VALUES ($insertVals)")

        statusUtil.info("processing", "Merge command: " + merge.toString())
        val merged = statement.executeUpdate(merge.toString())
        statusUtil.info("processing", "Rows merged into target: " + merged)
    }

    /** Truncate-and-load path: INSERT OVERWRITE replaces the table contents in
     *  one atomic Delta commit — a failed load leaves the previous rows intact
     *  (no TRUNCATE-then-COPY window, which Snowflake needs a transaction to
     *  paper over). Unlisted table columns become NULL (dropped-column case). */
    private def insertOverwrite(statement: Statement, stagedPath: String): Unit = {
        statusUtil.info("processing", "'truncateBeforeWrite' is set — replacing table contents atomically (INSERT OVERWRITE)")
        val fields = copyFields()
        val colList = fields.map(f => ident(f.name)).mkString(", ")
        val overwrite = s"INSERT OVERWRITE ${qualifiedTable()} ($colList) " +
            s"SELECT ${selectList(fields)} FROM ${readFiles(stagedPath)}"

        statusUtil.info("processing", "INSERT OVERWRITE command: " + overwrite)
        val loaded = statement.executeUpdate(overwrite)
        statusUtil.info("processing", "Rows loaded into target: " + loaded)
    }

    /** Quote a CSV header cell whose column name contains the delimiter, a
     *  quote, or a newline, using the same quote/escape conventions the load
     *  statements declare — otherwise the name would shift or split the
     *  header parse. Plain names pass through untouched. */
    private def csvHeaderCell(name: String): String =
        if (name.contains(csvDelimiter()) || name.contains("\n") || name.contains("\r") || name.indexOf('"') >= 0)
            "\"" + name.replace("\"", "\"\"") + "\""
        else name

    /** PUT the local CSV into the staging volume. */
    private def putFile(statement: Statement, dataFile: Path, stagedPath: String): Unit = {
        val put = s"PUT '${sqlLiteral(dataFile.toAbsolutePath.toString)}' INTO '${sqlLiteral(stagedPath)}' OVERWRITE"
        statusUtil.info("processing", "PUT command: " + put)
        statement.execute(put)
    }

    /** The staged file's volume path. Unique per run: parallel runs can't
     *  collide, and COPY INTO's per-file idempotency tracking (which silently
     *  skips a filename it has already loaded) never suppresses a load. */
    private def volumeFilePath(): String = {
        val safeName = config.name.replaceAll("[^A-Za-z0-9_-]", "_")
        "/Volumes/" + effectiveName(db.dbName) + "/" + effectiveName(db.schema) + "/" + stagingVolumeName +
            "/" + safeName + "_" + UUID.randomUUID().toString + ".csv"
    }

    /** The SELECT list every load path shares: CSV columns (named by the header
     *  row) CAST to their destination types, `_json` parsed into VARIANT. */
    private def selectList(fields: Seq[SchemaField]): String =
        fields.map { f =>
            val src = DatabricksConnectionUtil.quote(f.name)
            if (f.name.equalsIgnoreCase("_json")) s"PARSE_JSON($src) AS ${ident(f.name)}"
            else s"CAST($src AS ${databricksType(f)}) AS ${ident(f.name)}"
        }.mkString(", ")

    /** read_files() source over the staged CSV — used by MERGE and INSERT
     *  OVERWRITE (COPY INTO reads the path directly). Same CSV options as
     *  csvFormatOptions; inference off, all columns arrive as STRING. */
    /** The CSV parse options every load path shares — COPY INTO renders them
     *  as FORMAT_OPTIONS pairs, MERGE/INSERT OVERWRITE as read_files named
     *  arguments — so all three statements parse the staged file identically.
     *  Values are pre-escaped for a single-quoted SQL context. */
    private def csvOptions(): Seq[(String, String)] = Seq(
        "header" -> "true",
        "delimiter" -> sqlLiteral(csvDelimiter()),
        "quote" -> ("" + '"'),
        "escape" -> ("" + '"'),
        "nullValue" -> "",
        "multiLine" -> "true",
        "inferSchema" -> "false")

    private def readFiles(stagedPath: String): String =
        "read_files('" + sqlLiteral(stagedPath) + "', format => 'csv', " +
            csvOptions().map { case (k, v) => k + " => '" + v + "'" }.mkString(", ") +
            ", schemaEvolutionMode => 'none')"

    private def csvFormatOptions(): String =
        csvOptions().map { case (k, v) => "'" + k + "' = '" + v + "'" }.mkString(", ")

    /** Destination fields actually present in the load (handles dropped columns),
     *  in destination-schema order — matches the CSV column order. */
    private def copyFields(): Seq[SchemaField] = {
        val destFields = config.destination.schemaProperties.fields.asScala
        if (jobContext.data.header != null && jobContext.data.header.nonEmpty) {
            val headerSet = jobContext.data.header.map(_.toLowerCase).toSet
            destFields.filter(f => headerSet.contains(f.name.toLowerCase)).toSeq
        } else destFields.toSeq
    }

    private def createTableIfUndefined(statement: Statement): Unit = {
        if (db.schema != null && db.schema.nonEmpty)
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schemaRef())

        // The staging volume lives beside the table it loads. Under
        // manageTableManually this whole method is skipped, so locked-down
        // installs pre-create the volume along with the table.
        statement.execute("CREATE VOLUME IF NOT EXISTS " + schemaRef() + "." + stagingVolumeName)

        val keySet: Set[String] =
            if (db.keyFields != null) db.keyFields.asScala.map(_.toLowerCase).toSet else Set.empty

        val sql = new StringBuilder()
        sql.append("CREATE TABLE IF NOT EXISTS " + qualifiedTable() + " (")
        config.destination.schemaProperties.fields.forEach(field => {
            // Unity Catalog PRIMARY KEY columns must be NOT NULL (the PK itself
            // is informational/unenforced, like Snowflake's).
            val notNull = if (keySet.contains(field.name.toLowerCase)) " NOT NULL" else ""
            sql.append(ident(field.name) + " " + databricksType(field) + notNull + ", ")
        })
        sql.setLength(sql.length - 2)

        if (db.keyFields != null && !db.keyFields.isEmpty) {
            val keys = db.keyFields.asScala.map(ident).mkString(", ")
            val pkName = "pk_" + effectiveName(db.table).replaceAll("[^a-z0-9_]", "_")
            sql.append(", CONSTRAINT " + pkName + " PRIMARY KEY (" + keys + ")")
        }
        sql.append(")")

        statusUtil.info("processing", "Databricks create table statement: " + sql.toString())
        statement.execute(sql.toString())

        // Additive schema evolution: add any new columns the table doesn't have
        // yet. Unity Catalog stores object names lowercase regardless of quoting,
        // so compare lowercase on both sides.
        val existing = scala.collection.mutable.Set[String]()
        val rs = statement.executeQuery(
            s"""SELECT column_name FROM ${ident(db.dbName)}.information_schema.columns
               |WHERE lower(table_schema) = '${sqlLiteral(effectiveName(db.schema))}' AND lower(table_name) = '${sqlLiteral(effectiveName(db.table))}'""".stripMargin)
        try {
            while (rs.next()) existing.add(rs.getString(1).toLowerCase)
        } finally {
            rs.close()
        }

        config.destination.schemaProperties.fields.forEach(field => {
            if (!existing.contains(field.name.toLowerCase)) {
                // No IF NOT EXISTS on ADD COLUMN in Databricks SQL — the
                // information_schema pre-check above is the guard.
                val alter = s"ALTER TABLE ${qualifiedTable()} ADD COLUMN ${ident(field.name)} ${databricksType(field)}"
                statusUtil.info("processing", "Schema evolution: " + alter)
                statement.execute(alter)
            }
        })
    }

    /** Platform type -> Delta SQL DDL type. Mirrors SnowflakeLoader.snowflakeType;
     *  `_json` -> VARIANT is the analog of Snowflake's VARIANT. Unknown types pass
     *  through (e.g. decimal(p,s), which Delta accepts). */
    private def databricksType(field: SchemaField): String = {
        if (field.name.equalsIgnoreCase("_json")) "VARIANT"
        else if (field.name.equalsIgnoreCase("_xml")) "STRING"
        else field.`type`.toLowerCase match {
            case "string"                                 => "STRING"
            case "int" | "integer" | "tinyint" |
                 "smallint"                               => "INT"
            case "bigint"                                 => "BIGINT"
            case "float"                                  => "FLOAT"
            case "double"                                 => "DOUBLE"
            case "boolean"                                => "BOOLEAN"
            case "date"                                   => "DATE"
            case "timestamp"                              => "TIMESTAMP"
            case _                                        => field.`type`
        }
    }

    private def csvDelimiter(): String =
        if (config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null)
            config.source.fileAttributes.csvAttributes.delimiter
        else ","

    // Identifier emission and paste-shape normalization live in
    // DatabricksConnectionUtil, shared with the query path.
    private def ident(identifier: String): String = DatabricksConnectionUtil.ident(identifier)
    private def effectiveName(s: String): String = DatabricksConnectionUtil.effectiveName(s)
    private def sqlLiteral(value: String): String = value.replace("'", "''")
    private def schemaRef(): String = ident(db.dbName) + "." + ident(db.schema)
    private def qualifiedTable(): String = DatabricksConnectionUtil.qualifiedTable(db)

    private def sendNotification(): Unit = {
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "databricks",
            null,
            null,
            null,
            db.schema,
            db.dbName,
            db.table,
            null
        )
        val gson = new Gson
        val jsonNotification = gson.toJson(notification)

        val attributes = new java.util.HashMap[String, String]
        attributes.put("pipeline", config.name)
        attributes.put("destination", "databricks")
        attributes.put("schema", db.schema)
        attributes.put("database", db.dbName)
        attributes.put("table", db.table)

        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}
