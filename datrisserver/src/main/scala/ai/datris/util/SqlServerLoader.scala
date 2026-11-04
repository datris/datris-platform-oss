package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model._
import com.microsoft.sqlserver.jdbc.ISQLServerConnection
import org.apache.commons.csv.{CSVFormat, CSVRecord}
import org.slf4j.{Logger, LoggerFactory}

import java.io.{InputStreamReader, Reader}
import java.nio.charset.StandardCharsets
import java.sql.{Connection, PreparedStatement, Statement}
import java.util.UUID
import scala.collection.JavaConverters._
import scala.util.Try

/** Microsoft SQL Server / Azure SQL destination. Mirrors [[SnowflakeLoader]] —
 *  same create-table / additive-column / truncate / raw-vs-upsert routing and
 *  the same dest-schema projection — but loads through a JDBC batch: the
 *  projected CSV body ([[DestSchemaProjector.csvBody]], the same bytes Postgres
 *  feeds to COPY) is parsed by commons-csv and bound to a `PreparedStatement`
 *  batch, which the driver sends through its bulk-copy API
 *  (`useBulkCopyForBatchInsert=true`). Inserts target the table; upserts land
 *  in a `#DATRIS_STG_<uuid>` session temp table, then `MERGE` on keyFields.
 *
 *  JSON and XML sources arrive as one CSV cell holding the whole document, so
 *  they land as a single `_json` (NVARCHAR(MAX) with an ISJSON check) or
 *  `_xml` (XML) column, exactly as Postgres' json/xml columns do.
 *
 *  Credentials are a Platform-tab secret named by `Database.credentialsSecret`
 *  (resolved by [[CredentialResolver.resolveSqlServer]]). */
class SqlServerLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SqlServerLoader])
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil
    private val db = config.destination.database

    import SqlServerConnectionUtil.{ident, nLiteral}

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info(
            "begin",
            "Loading the data into SQL Server database: " + db.dbName + ", schema: " + SqlServerConnectionUtil.schemaName(db) + ", table: " + db.table
        )

        // Fail on an empty payload BEFORE any DDL or TRUNCATE, as Postgres does.
        DestSchemaProjector.requireCsvBody(jobContext)

        SqlServerConnectionUtil.withConnection(db, msg => statusUtil.info("processing", msg)) { conn =>
            if (db.useTransaction)
                conn.setAutoCommit(false)
            val statement = conn.createStatement()
            try {
                loadData(conn, statement)
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
    }

    private def loadData(conn: Connection, statement: Statement): Unit = {
        statusUtil.info("processing", "Loading data into " + db.table)

        if (!db.manageTableManually)
            createTableIfUndefined(statement)

        // Truncate AFTER create-if-not-exists so first-run pipelines with
        // truncateBeforeWrite=true don't fail against a not-yet-existing table.
        if (db.truncateBeforeWrite) {
            if (db.useTransaction) {
                statusUtil.info("processing", "'truncateBeforeWrite' is set, deleting existing rows (DELETE — rolls back if the load fails)")
                statement.execute("DELETE FROM " + qualifiedTable())
            } else {
                statusUtil.info("processing", "'truncateBeforeWrite' is set, truncating table")
                statement.execute("TRUNCATE TABLE " + qualifiedTable())
            }
        }

        // Same routing as Postgres: keyFields + not-truncating => upsert (MERGE);
        // otherwise straight insert (duplicates fail as the contract).
        val useUpsert = !db.truncateBeforeWrite &&
            db.keyFields != null && !db.keyFields.isEmpty

        if (useUpsert) mergeInto(conn, statement)
        else {
            val inserted = batchInsert(conn, qualifiedTable(), copyFields())
            statusUtil.info("processing", "Rows inserted into table: " + inserted)
        }
    }

    /** Upsert path: stage into a session temp table shaped like the target's
     *  load columns, then MERGE on keyFields. Temp tables are session-scoped
     *  and dropped when the connection closes; the explicit drop below handles
     *  the in-flight cleanup. */
    private def mergeInto(conn: Connection, statement: Statement): Unit = {
        val targetRef = qualifiedTable()
        val stgRef = "#DATRIS_STG_" + UUID.randomUUID().toString.replace("-", "_")
        val fields = copyFields()
        val colNames = fields.map(_.name)
        try {
            statement.execute(s"SELECT TOP 0 ${colNames.map(ident).mkString(", ")} INTO $stgRef FROM $targetRef")
            statusUtil.info("processing", "Staging table created: " + stgRef)

            val staged = batchInsert(conn, stgRef, fields)
            statusUtil.info("processing", "Rows copied to staging: " + staged)

            val merge = SqlServerLoader.mergeSql(targetRef, stgRef, colNames, db.keyFields.asScala.toList)
            statusUtil.info("processing", "Merge command: " + merge)
            val merged = statement.executeUpdate(merge)
            statusUtil.info("processing", "Rows merged into target: " + merged)
        } finally {
            Try(statement.execute(s"IF OBJECT_ID('tempdb..$stgRef') IS NOT NULL DROP TABLE $stgRef"))
        }
    }

    /** Stream the projected CSV body through commons-csv into a prepared
     *  INSERT batch on `tableRef`. Empty cells bind as NULL (Snowflake's
     *  EMPTY_FIELD_AS_NULL, Postgres' unquoted-empty). Returns rows sent. */
    private def batchInsert(conn: Connection, tableRef: String, fields: Seq[SchemaField]): Long = {
        val cols = fields.map(f => ident(f.name))
        val sql = s"INSERT INTO $tableRef (${cols.mkString(", ")}) VALUES (${cols.map(_ => "?").mkString(", ")})"
        statusUtil.info("processing", "Insert command: " + sql)
        val isXml = fields.map(_.name.equalsIgnoreCase("_xml")).toArray
        // The driver's bulk-copy path skips CHECK constraints, which would let
        // a non-JSON document past the _json column's ISJSON check, and its
        // type conversion into an XML column is stricter than a plain INSERT.
        // A JSON or XML source is one row (the whole document), so plain
        // inserts cost nothing. Failing to switch bulk copy off fails the
        // load rather than silently skipping the ISJSON check.
        if (fields.exists(f => f.name.equalsIgnoreCase("_json") || f.name.equalsIgnoreCase("_xml"))) {
            try conn.unwrap(classOf[ISQLServerConnection]).setUseBulkCopyForBatchInsert(false)
            catch {
                case e: Exception =>
                    val failure = new DatrisException(
                        "Could not switch off bulk copy for a JSON/XML document load on SQL Server (needed so the _json ISJSON check is enforced): " + e.getMessage
                    )
                    failure.initCause(e)
                    throw failure
            }
        }

        val input = new StagedRows.TextChunkInputStream(DestSchemaProjector.csvBody(jobContext))
        val reader: Reader = new InputStreamReader(input, StandardCharsets.UTF_8)
        var ps: PreparedStatement = null
        var rows = 0L
        try {
            ps = conn.prepareStatement(sql)
            val format = CSVFormat.DEFAULT.builder()
                .setDelimiter(DestSchemaProjector.delimiter(jobContext))
                .setNullString("")
                .build()
            val parser = format.parse(reader)
            var pending = 0
            try {
                parser.iterator().asScala.foreach { rec: CSVRecord =>
                    var i = 0
                    while (i < cols.size) {
                        val v = if (i < rec.size()) rec.get(i) else null
                        if (v == null) ps.setNull(i + 1, java.sql.Types.NVARCHAR)
                        else ps.setString(i + 1, if (isXml(i)) SqlServerLoader.stripXmlDeclaration(v) else v)
                        i += 1
                    }
                    ps.addBatch()
                    pending += 1
                    rows += 1
                    if (pending >= SqlServerLoader.BatchSize) {
                        ps.executeBatch()
                        pending = 0
                    }
                }
            } catch {
                case e: Exception if input.failure != null =>
                    val failure = new DatrisException("Failed while projecting rows for SQL Server insert: " + input.failure.getMessage)
                    failure.initCause(input.failure)
                    throw failure
            }
            if (pending > 0) ps.executeBatch()
            rows
        } finally {
            if (ps != null) Try(ps.close())
            Try(reader.close())
        }
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

    private def createTableIfUndefined(statement: Statement): Unit = {
        val schema = SqlServerConnectionUtil.schemaName(db)
        // CREATE SCHEMA must be the only statement in its batch, hence EXEC.
        statement.execute(
            s"IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = ${nLiteral(schema)}) EXEC(${nLiteral("CREATE SCHEMA " + ident(schema))})"
        )

        val fields = config.destination.schemaProperties.fields.asScala.toSeq
        val ddl = SqlServerLoader.createTableSql(db, fields)
        statusUtil.info("processing", "SQL Server create table statement: " + ddl)
        statement.execute(ddl)

        // Additive schema evolution: add any new columns the table doesn't have yet.
        val existing = scala.collection.mutable.Set[String]()
        val rs = statement.executeQuery(
            s"SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ${nLiteral(schema)} AND TABLE_NAME = ${nLiteral(db.table)}"
        )
        try {
            while (rs.next()) existing.add(rs.getString(1).toLowerCase)
        } finally {
            rs.close()
        }

        fields.foreach { field =>
            if (!existing.contains(field.name.toLowerCase)) {
                // Added columns are nullable and never part of the key.
                val alter = s"ALTER TABLE ${qualifiedTable()} ADD ${SqlServerLoader.columnDef(field, isKey = false)}"
                statusUtil.info("processing", "Schema evolution: " + alter)
                statement.execute(alter)
            }
        }
    }

    private def qualifiedTable(): String = SqlServerConnectionUtil.qualifiedTable(db)

    private def sendNotification(): Unit = {
        val schema = SqlServerConnectionUtil.schemaName(db)
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "sqlserver",
            null,
            null,
            null,
            schema,
            db.dbName,
            db.table,
            null
        )
        val gson = new Gson
        val jsonNotification = gson.toJson(notification)

        val attributes = new java.util.HashMap[String, String]
        attributes.put("pipeline", config.name)
        attributes.put("destination", "sqlserver")
        attributes.put("schema", schema)
        attributes.put("database", db.dbName)
        attributes.put("table", db.table)

        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}

/** Statement builders, public so the DDL / MERGE shapes can be checked offline. */
object SqlServerLoader {
    import SqlServerConnectionUtil.{ident, nLiteral}

    val BatchSize = 5000

    /** Platform type -> SQL Server DDL type. Mirrors the inline mapping in
     *  PostgresLoader.createTableIfUndefined: `_json` -> NVARCHAR(MAX) (with an
     *  ISJSON check, see [[columnDef]]) is the analog of Postgres' json, `_xml`
     *  -> XML. A string KEY column is NVARCHAR(450) because an index key over
     *  NVARCHAR(MAX) is not allowed (900-byte clustered-key limit). Unknown
     *  types pass through (e.g. decimal(p,s)). */
    def sqlServerType(field: SchemaField, isKey: Boolean = false): String = {
        if (field.name.equalsIgnoreCase("_json")) "NVARCHAR(MAX)"
        else if (field.name.equalsIgnoreCase("_xml")) "XML"
        else Option(field.`type`).getOrElse("string").toLowerCase match {
            case "string" => if (isKey) "NVARCHAR(450)" else "NVARCHAR(MAX)"
            case "int" | "integer" | "smallint" | "tinyint" => "INT"
            case "bigint" => "BIGINT"
            case "float" | "double" => "FLOAT"
            case "boolean" => "BIT"
            case "date" => "DATE"
            case "timestamp" => "DATETIME2"
            case _ => field.`type`
        }
    }

    /** `name TYPE [NOT NULL] [CHECK (ISJSON(name) = 1)]`. */
    def columnDef(field: SchemaField, isKey: Boolean): String = {
        val name = ident(field.name)
        val base = name + " " + sqlServerType(field, isKey) + (if (isKey) " NOT NULL" else "")
        if (field.name.equalsIgnoreCase("_json")) base + " CHECK (ISJSON(" + name + ") = 1)" else base
    }

    /** `IF NOT EXISTS (SELECT 1 FROM sys.tables ...) CREATE TABLE schema.table (...)`,
     *  with a PRIMARY KEY on keyFields when they are set. */
    def createTableSql(db: Database, fields: Seq[SchemaField]): String = {
        val schema = SqlServerConnectionUtil.schemaName(db)
        val keys = Option(db.keyFields).map(_.asScala.toList).getOrElse(Nil)
        val keySet = keys.map(_.toLowerCase).toSet
        val cols = fields.map(f => columnDef(f, keySet.contains(f.name.toLowerCase)))
        val pk =
            if (keys.nonEmpty) Seq("PRIMARY KEY (" + keys.map(ident).mkString(", ") + ")")
            else Nil
        "IF NOT EXISTS (SELECT 1 FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id" +
            " WHERE s.name = " + nLiteral(schema) + " AND t.name = " + nLiteral(db.table) + ")" +
            " CREATE TABLE " + SqlServerConnectionUtil.qualifiedTable(db) + " (" + (cols ++ pk).mkString(", ") + ")"
    }

    /** MERGE `stagingRef` into `targetRef` on `keyFields` (ANDed). Matched rows
     *  get every non-key column overwritten (key match is case-insensitive);
     *  unmatched rows insert every column. Terminated by `;`, which SQL Server
     *  requires for MERGE. */
    def mergeSql(targetRef: String, stagingRef: String, columns: Seq[String], keyFields: Seq[String]): String = {
        val keySet = keyFields.map(_.toLowerCase).toSet
        val nonKey = columns.filterNot(c => keySet.contains(c.toLowerCase))
        val onClause = keyFields.map(k => s"t.${ident(k)} = s.${ident(k)}").mkString(" AND ")
        val insertCols = columns.map(ident).mkString(", ")
        val insertVals = columns.map(c => "s." + ident(c)).mkString(", ")

        val sb = new StringBuilder()
        sb.append(s"MERGE INTO $targetRef AS t USING $stagingRef AS s ON $onClause ")
        if (nonKey.nonEmpty)
            sb.append("WHEN MATCHED THEN UPDATE SET " + nonKey.map(c => s"t.${ident(c)} = s.${ident(c)}").mkString(", ") + " ")
        sb.append(s"WHEN NOT MATCHED BY TARGET THEN INSERT ($insertCols) VALUES ($insertVals);")
        sb.toString
    }

    /** SQL Server's XML type rejects an `encoding="UTF-8"` declaration on a
     *  Unicode (NVARCHAR) value ("unable to switch the encoding"), and it does
     *  not keep the declaration anyway, so drop it before binding. */
    def stripXmlDeclaration(doc: String): String =
        doc.replaceFirst("^\\uFEFF?\\s*<\\?xml[^?]*\\?>", "")
}
