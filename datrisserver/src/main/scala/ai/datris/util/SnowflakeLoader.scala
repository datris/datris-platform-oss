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
import java.sql.{Connection, DriverManager, Statement}
import java.util.{Properties, UUID}
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** Snowflake SQL destination. Mirrors [[PostgresLoader]] — same create-table /
 *  truncate / raw-vs-upsert routing and the same dest-schema projection — but
 *  loads through Snowflake's bulk path: stage the transform output as a local
 *  CSV, `PUT` it onto the target table's implicit table stage (`@%<table>`),
 *  then `COPY INTO` for inserts or `MERGE` (via a session temp table) for
 *  upserts. v1 uses the internal table stage only; no `CREATE STAGE` ever runs.
 *
 *  Credentials are a Platform-tab secret named by `Database.credentialsSecret`
 *  (resolved by [[CredentialResolver.resolveSnowflake]]); `warehouse`/`role`
 *  are routing knobs on the config. */
class SnowflakeLoader(initialContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SnowflakeLoader])
    // The run's context, narrowed by [[prepareTable]] when a column this
    // run's transformation added is left out of this table.
    private var jobContext: JobContext = initialContext
    private def config: PipelineConfig = jobContext.config
    private val statusUtil = jobContext.statusUtil
    private val db = config.destination.database

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Loading the data into Snowflake database: " + db.dbName + ", schema: " + db.schema + ", table: " + db.table)

        try {
            SnowflakeConnectionUtil.withConnection(db, msg => statusUtil.info("processing", msg)) { conn =>
                if (db.useTransaction)
                    conn.setAutoCommit(false)
                val statement = conn.createStatement()
                try {
                    dataFile = createStagingFile()
                    loadData(statement)
                    if (db.useTransaction)
                        conn.commit()
                } catch {
                    case e: Exception =>
                        if (db.useTransaction)
                            Try(conn.rollback())
                        throw e
                } finally {
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
     *  Postgres (which streams straight into COPY), Snowflake's PUT needs a
     *  local file path. The CSV is streamed from the staged payload piece by
     *  piece (DestSchemaProjector.csvBody) — the same bytes Postgres feeds to
     *  COPY, never held whole in heap. The temp file is removed if the write
     *  fails; on success `process()` removes it after the PUT. */
    private def createStagingFile(): Path = {
        val file = Files.createTempFile("snowflake-load-", ".csv")
        file.toFile.deleteOnExit()
        try {
            val body = DestSchemaProjector.csvBody(jobContext)
            try {
                val writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)
                try body.foreach(writer.write)
                finally writer.close()
            } finally body.close()
        } catch {
            case e: Throwable =>
                Try(Files.deleteIfExists(file))
                throw e
        }
        file
    }

    /** The local CSV for PUT; replaced when [[prepareTable]] leaves a column
      * out, because COPY reads the file by position. */
    private var dataFile: Path = null

    private def loadData(statement: Statement): Unit = {
        statusUtil.info("processing", "Loading data into " + db.table)

        if (prepareTable(statement).nonEmpty) {
            Try(Files.deleteIfExists(dataFile))
            dataFile = null
            dataFile = createStagingFile()
        }

        // Truncate AFTER create-if-not-exists so first-run pipelines with
        // truncateBeforeWrite=true don't fail against a not-yet-existing table.
        if (db.truncateBeforeWrite) {
            if (db.useTransaction) {
                // TRUNCATE is DDL in Snowflake and implicitly commits, so a COPY
                // failure after it would leave the table empty. DELETE is DML and
                // rolls back with the rest of the load.
                statusUtil.info("processing", "'truncateBeforeWrite' is set, deleting existing rows (DELETE — rolls back if the load fails)")
                statement.execute("DELETE FROM " + qualifiedTable())
            } else {
                statusUtil.info("processing", "'truncateBeforeWrite' is set, truncating table")
                statement.execute("TRUNCATE TABLE " + qualifiedTable())
            }
        }

        // Same routing as Postgres: keyFields + not-truncating => upsert (MERGE);
        // otherwise straight insert (faster, duplicates fail as the contract).
        val useUpsert = !db.truncateBeforeWrite &&
            db.keyFields != null && !db.keyFields.isEmpty

        if (useUpsert) mergeInto(statement, dataFile)
        else copyInsert(statement, dataFile)
    }

    /** Insert path: PUT onto the table stage, COPY INTO the target. */
    private def copyInsert(statement: Statement, dataFile: Path): Unit = {
        val stage = "@%" + ident(db.table)
        putFile(statement, dataFile, stage)
        val copied = copyStagedInto(statement, qualifiedTable(), stage, copyFields())
        statusUtil.info("processing", "Rows loaded into target: " + copied)
    }

    /** Upsert path: stage into a session TEMP table that mirrors the target, then
     *  MERGE on keyFields. The temp table is session-scoped and auto-dropped. */
    private def mergeInto(statement: Statement, dataFile: Path): Unit = {
        val targetRef = qualifiedTable()
        val stgName = "DATRIS_STG_" + UUID.randomUUID().toString.replace("-", "_")
        val stgRef = ident(stgName)
        try {
            statement.execute(s"CREATE TEMPORARY TABLE $stgRef LIKE $targetRef")
            statusUtil.info("processing", "Staging table created: " + stgName)

            val fields = copyFields()
            putFile(statement, dataFile, "@%" + stgRef)
            val copied = copyStagedInto(statement, stgRef, "@%" + stgRef, fields)
            statusUtil.info("processing", "Rows copied to staging: " + copied)

            val merge = SnowflakeLoader.mergeSql(targetRef, stgRef, fields.map(_.name), db.keyFields.asScala.toList)

            statusUtil.info("processing", "Merge command: " + merge)
            val merged = statement.executeUpdate(merge)
            statusUtil.info("processing", "Rows merged into target: " + merged)
        } finally {
            Try(statement.execute(s"DROP TABLE IF EXISTS $stgRef"))
        }
    }

    /** PUT the local CSV onto a stage. AUTO_COMPRESS gzips on the wire (COPY's
     *  COMPRESSION=AUTO decompresses), so we stage the plain CSV — no manual gzip. */
    private def putFile(statement: Statement, dataFile: Path, stage: String): Unit = {
        val put = s"PUT 'file://${dataFile.toAbsolutePath.toString}' $stage AUTO_COMPRESS=TRUE OVERWRITE=TRUE"
        statusUtil.info("processing", "PUT command: " + put)
        statement.execute(put)
    }

    /** COPY a staged file into `tableRef`. When a VARIANT column is present
     *  (`_json`), uses a SELECT-transform COPY so the JSON text is PARSE_JSON'd
     *  into the VARIANT; otherwise a straight positional COPY. PURGE clears the
     *  stage afterward. Returns rows loaded. */
    private def copyStagedInto(statement: Statement, tableRef: String, stage: String, fields: Seq[SchemaField]): Long = {
        val copy = SnowflakeLoader.copySql(tableRef, stage, fields, csvDelimiter())

        statusUtil.info("processing", "COPY command: " + copy)
        val rs = statement.executeQuery(copy)
        // COPY INTO returns a result set; sum the rows_loaded column when present.
        var loaded = 0L
        try {
            val meta = rs.getMetaData
            val cols = (1 to meta.getColumnCount).map(i => meta.getColumnLabel(i).toLowerCase)
            val idx = cols.indexOf("rows_loaded")
            while (rs.next()) {
                if (idx >= 0) loaded += rs.getLong(idx + 1)
            }
        } finally {
            rs.close()
        }
        loaded
    }

    /** Destination fields actually present in the load (handles dropped columns),
     *  in destination-schema order — matches the CSV column order. */
    private def copyFields(): Seq[SchemaField] = {
        val destFields = config.destination.schemaProperties.fields.asScala
        if (jobContext.data.header != null && jobContext.data.header.nonEmpty) {
            val headerSet = jobContext.data.header.map(_.toLowerCase).toSet
            destFields.filter(f => headerSet.contains(f.name.toLowerCase)).toSeq
        } else destFields.toSeq
    }

    /** Create the table when it is missing, then add any configured column
      * it lacks. When every non-document field's type passes
      * [[ColumnTypeText.loadText]] the statements are those earlier versions ran,
      * in the same order. Otherwise the existing columns are read first and
      * the run fails before any statement when a stored type that is not safe
      * would be needed ([[ColumnTypeText.loadProblem]]); else the table is not
      * created again and only missing columns are added. A column this run's
      * transformation added is handled by [[TransformedColumns.evolve]]
      * (left out of this table, names returned lower-cased, instead of
      * failing the load). Snowflake runs DDL in its own transaction and a
      * failed statement does not end the open one, so no savepoint is used. */
    private def createTableIfUndefined(statement: Statement): Set[String] = {
        val fields = config.destination.schemaProperties.fields.asScala.toList

        val create = SnowflakeLoader.createPlan(db, fields)
        create.schemaSql.foreach(sql => statement.execute(sql))
        create.tableSql.foreach { sql =>
            statusUtil.info("processing", "Snowflake create table statement: " + sql)
            statement.execute(sql)
        }

        // Additive schema evolution: add any new columns the table doesn't have yet.
        // Match information_schema on the effective stored names (unquoted
        // identifiers fold to uppercase, quoted ones keep exact case); read the
        // result by position — the Snowflake driver matches result labels
        // case-sensitively and returns COLUMN_NAME.
        val existing = scala.collection.mutable.Set[String]()
        val rs = statement.executeQuery(
            s"""SELECT column_name FROM ${ident(db.dbName)}.information_schema.columns
               |WHERE table_schema = '${sqlLiteral(effectiveName(db.schema))}' AND table_name = '${sqlLiteral(effectiveName(db.table))}'""".stripMargin
        )
        try {
            while (rs.next()) existing.add(rs.getString(1).toLowerCase)
        } finally {
            rs.close()
        }

        SnowflakeLoader.evolvePlan(db, fields, existing.toSet) match {
            case Left(problem) => throw new DatrisException(problem)
            case Right(plan) =>
                plan.statusLine.foreach(line => statusUtil.info("processing", line))
                TransformedColumns.evolve(
                    jobContext,
                    destinationLabel,
                    TransformedColumns.Snowflake,
                    ColumnTypeText.missingFields(fields, existing.toSet).zip(plan.addColumns),
                    existing.toSet,
                    alter => statement.execute(alter),
                    alter => TransformedColumns.attempt(statement.getConnection, savepoint = false)(statement.execute(alter)),
                    () => readColumnTypes(statement)
                )
        }
    }

    /** The context the load uses: the run's, less any column this run's
      * transformation added that [[prepareTable]] left out of this table. */
    private[util] def loadContext: JobContext = jobContext

    /** Create or extend the table, or check a manually managed one against
      * the columns this run's transformation added; then leave out of this
      * load every such column the table cannot take. Returns those names,
      * lower-cased. */
    private[util] def prepareTable(statement: Statement): Set[String] = {
        val left =
            if (!db.manageTableManually) createTableIfUndefined(statement)
            else TransformedColumns.manualTable(jobContext, destinationLabel, TransformedColumns.Snowflake, () => readColumnTypes(statement))
        jobContext = TransformedColumns.withoutColumns(jobContext, left)
        left
    }

    private def destinationLabel: String = "the Snowflake table " + db.dbName + "." + db.schema + "." + db.table

    /** The table's columns with their types by lower-cased name, read by
      * position (see [[createTableIfUndefined]]). */
    private def readColumnTypes(statement: Statement): Either[Throwable, Seq[TransformedColumns.StoreColumn]] =
        TransformedColumns.attemptRead(statement.getConnection, savepoint = false) {
            val rs = statement.executeQuery(SnowflakeLoader.columnTypesSql(db))
            try {
                val out = ListBuffer[TransformedColumns.StoreColumn]()
                while (rs.next()) {
                    val len = rs.getObject(3) match {
                        case n: Number => Some(n.longValue)
                        case _ => None
                    }
                    out += TransformedColumns.StoreColumn(
                        rs.getString(1),
                        rs.getString(2),
                        len,
                        TransformedColumns.yesNo(rs.getString(4)),
                        TransformedColumns.yesNo(rs.getString(5))
                    )
                }
                out.toList
            } finally rs.close()
        }

    private def csvDelimiter(): String = CsvAttributes.delimiterOf(config)

    // Identifier emission and paste-shape normalization live in
    // SnowflakeConnectionUtil, shared with the query path.
    private def ident(identifier: String): String = SnowflakeConnectionUtil.ident(identifier)
    private def effectiveName(s: String): String = SnowflakeConnectionUtil.effectiveName(s)
    private def sqlLiteral(value: String): String = value.replace("'", "''")
    private def qualifiedTable(): String = SnowflakeConnectionUtil.qualifiedTable(db)

    private def sendNotification(): Unit = {
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "snowflake",
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
        attributes.put("destination", "snowflake")
        attributes.put("schema", db.schema)
        attributes.put("database", db.dbName)
        attributes.put("table", db.table)

        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}

/** The Snowflake load statements, built from the destination config and
  * schema. Pure, no I/O. Every column, key and table name goes through
  * [[SnowflakeConnectionUtil.ident]]; column type text comes from
  * [[snowflakeType]]. */
object SnowflakeLoader {
    private def ident(identifier: String): String = SnowflakeConnectionUtil.ident(identifier)

    /** `CREATE TABLE IF NOT EXISTS <db.schema.table> (<col> <type>, ...[, PRIMARY KEY (<keys>)])` */
    def createTableSql(db: Database, fields: Seq[SchemaField]): String = {
        val sql = new StringBuilder()
        sql.append("CREATE TABLE IF NOT EXISTS " + SnowflakeConnectionUtil.qualifiedTable(db) + " (")
        sql.append(fields.map(field => ident(field.name) + " " + snowflakeType(field)).mkString(", "))
        if (db.keyFields != null && !db.keyFields.isEmpty) {
            val keys = db.keyFields.asScala.map(ident).mkString(", ")
            sql.append(", PRIMARY KEY (" + keys + ")")
        }
        sql.append(")")
        sql.toString()
    }

    /** The loader's create step: `CREATE SCHEMA IF NOT EXISTS` (when the
      * schema is set) and [[createTableSql]], or nothing when a field's stored
      * type has no [[ColumnTypeText.loadText]] ([[ColumnTypeText.create]]). */
    def createPlan(db: Database, fields: Seq[SchemaField]): ColumnTypeText.CreatePlan =
        ColumnTypeText.create(
            fields,
            if (db.schema != null && db.schema.nonEmpty) Some("CREATE SCHEMA IF NOT EXISTS " + ident(db.dbName) + "." + ident(db.schema)) else None,
            createTableSql(db, fields)
        )

    /** The loader's evolve step over the table's existing (lower-cased)
      * columns ([[ColumnTypeText.evolve]]). */
    def evolvePlan(db: Database, fields: Seq[SchemaField], existing: Set[String]): Either[String, ColumnTypeText.EvolvePlan] =
        ColumnTypeText.evolve(fields, existing, f => addColumnSql(db, f))

    /** The table's columns, by position: name, type, character length,
      * is_nullable, is_identity (Snowflake has no other generated column);
      * matched on the effective stored names as the loader's column read is. */
    def columnTypesSql(db: Database): String =
        s"""SELECT column_name, data_type, character_maximum_length, is_nullable, is_identity FROM ${ident(db.dbName)}.information_schema.columns
           |WHERE table_schema = '${SnowflakeConnectionUtil.effectiveName(db.schema).replace("'", "''")}' AND table_name = '${SnowflakeConnectionUtil
                .effectiveName(db.table)
                .replace("'", "''")}'""".stripMargin

    /** `ALTER TABLE <db.schema.table> ADD COLUMN IF NOT EXISTS <col> <type>` */
    def addColumnSql(db: Database, field: SchemaField): String =
        s"ALTER TABLE ${SnowflakeConnectionUtil.qualifiedTable(db)} ADD COLUMN IF NOT EXISTS ${ident(field.name)} ${snowflakeType(field)}"

    /** COPY a staged file into `tableRef`. When a VARIANT column is present
      * (`_json`), a SELECT-transform COPY PARSE_JSONs the JSON text into the
      * VARIANT; otherwise a straight positional COPY. PURGE clears the stage
      * afterward. */
    def copySql(tableRef: String, stage: String, fields: Seq[SchemaField], delimiter: String): String = {
        val colList = fields.map(f => ident(f.name)).mkString(", ")
        val hasVariant = fields.exists(f => f.name.equalsIgnoreCase("_json"))

        val fileFormat = "FILE_FORMAT = (TYPE = CSV FIELD_OPTIONALLY_ENCLOSED_BY = '\"' " +
            "FIELD_DELIMITER = '" + delimiter + "' EMPTY_FIELD_AS_NULL = TRUE COMPRESSION = AUTO)"

        if (hasVariant) {
            val selectList = fields.zipWithIndex.map { case (f, i) =>
                val pos = "$" + (i + 1)
                if (f.name.equalsIgnoreCase("_json")) s"PARSE_JSON($pos)" else pos
            }.mkString(", ")
            s"COPY INTO $tableRef ($colList) FROM (SELECT $selectList FROM $stage) $fileFormat PURGE = TRUE"
        } else {
            s"COPY INTO $tableRef ($colList) FROM $stage $fileFormat PURGE = TRUE"
        }
    }

    /** MERGE the staging table into the target on `keyFields`; non-key
      * columns (compared case-insensitively) are overwritten on a match. */
    def mergeSql(targetRef: String, stagingRef: String, columns: Seq[String], keyFields: Seq[String]): String = {
        val keySet = keyFields.map(_.toLowerCase).toSet
        val nonKey = columns.filterNot(c => keySet.contains(c.toLowerCase))

        val onClause = keyFields.map(k => s"t.${ident(k)} = s.${ident(k)}").mkString(" AND ")
        val insertCols = columns.map(ident).mkString(", ")
        val insertVals = columns.map(c => "s." + ident(c)).mkString(", ")

        val merge = new StringBuilder()
        merge.append(s"MERGE INTO $targetRef t USING $stagingRef s ON $onClause ")
        if (nonKey.nonEmpty) {
            val setClause = nonKey.map(c => s"t.${ident(c)} = s.${ident(c)}").mkString(", ")
            merge.append(s"WHEN MATCHED THEN UPDATE SET $setClause ")
        }
        merge.append(s"WHEN NOT MATCHED THEN INSERT ($insertCols) VALUES ($insertVals)")
        merge.toString()
    }

    /** Platform type -> Snowflake DDL type. Mirrors [[PostgresSql.columnType]];
     *  `_json` -> VARIANT is the analog of Postgres' `_json` -> json. Any other
     *  type is passed as written only when it has a [[ColumnTypeText.loadText]] (sent as that text)
     *  (e.g. decimal(p,s), which Snowflake accepts as a NUMBER synonym);
     *  every other type text, null included, is a `DatrisException`. */
    private[util] def snowflakeType(field: SchemaField): String = {
        if (field.name.equalsIgnoreCase("_json")) "VARIANT"
        else if (field.name.equalsIgnoreCase("_xml")) "VARCHAR"
        else {
            val t = ColumnTypeText.loadTextOrThrow(field)
            t.toLowerCase(java.util.Locale.ROOT) match {
                case "string" => "VARCHAR"
                case "int" | "integer" | "tinyint" |
                    "smallint" | "bigint" => "NUMBER(38,0)"
                case "float" | "double" => "FLOAT"
                case "boolean" => "BOOLEAN"
                case "date" => "DATE"
                case "timestamp" => "TIMESTAMP_NTZ"
                case _ => t
            }
        }
    }
}
