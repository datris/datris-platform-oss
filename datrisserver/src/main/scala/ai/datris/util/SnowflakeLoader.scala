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
class SnowflakeLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SnowflakeLoader])
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil
    private val db = config.destination.database

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Loading the data into Snowflake database: " + db.dbName + ", schema: " + db.schema + ", table: " + db.table)

        val creds = CredentialResolver.resolveSnowflake(db.credentialsSecret)

        Class.forName("net.snowflake.client.jdbc.SnowflakeDriver")
        statusUtil.info("processing", "Snowflake driver loaded successfully")

        var conn: Connection = null
        var statement: Statement = null
        var keyFile: Path = null
        var dataFile: Path = null

        try {
            val properties = new Properties()
            properties.setProperty("user", creds.user)
            if (db.dbName != null) properties.setProperty("db", db.dbName)
            if (db.schema != null) properties.setProperty("schema", db.schema)
            if (db.warehouse != null) properties.setProperty("warehouse", db.warehouse)
            if (db.role != null) properties.setProperty("role", db.role)

            // Key-pair auth is the default (write the PEM to a temp file and let the
            // driver parse/decrypt it — handles encrypted keys without BouncyCastle).
            // Password is the fallback when no private key is present.
            creds.privateKey match {
                case Some(pem) =>
                    val normalizedPem = normalizePrivateKeyPem(pem, creds.privateKeyPassphrase.isDefined)
                    keyFile = Files.createTempFile("snowflake-key-", ".p8")
                    keyFile.toFile.deleteOnExit()
                    Files.setPosixFilePermissions(keyFile, java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
                    Files.write(keyFile, normalizedPem.getBytes(StandardCharsets.UTF_8))
                    properties.setProperty("private_key_file", keyFile.toAbsolutePath.toString)
                    creds.privateKeyPassphrase.foreach(p => properties.setProperty("private_key_file_pwd", p))
                case None =>
                    properties.setProperty("password", creds.password.get)
            }

            val jdbcUrl = "jdbc:snowflake://" + normalizeAccount(creds.account) + ".snowflakecomputing.com/"
            statusUtil.info("processing", "jdbc url: " + jdbcUrl)
            conn = DriverManager.getConnection(jdbcUrl, properties)
            statusUtil.info("processing", "Snowflake connection acquired")
            if (db.useTransaction)
                conn.setAutoCommit(false)
            statement = conn.createStatement()

            dataFile = createStagingFile()

            loadData(statement, dataFile)

            if (db.useTransaction)
                conn.commit()
            sendNotification()
            statusUtil.info("end", "Process completed")
        } catch {
            case e: Exception =>
                if (db.useTransaction && conn != null)
                    Try(conn.rollback())
                throw e
        } finally {
            if (statement != null) Try(statement.close())
            if (conn != null) Try(conn.close())
            if (keyFile != null) Try(Files.deleteIfExists(keyFile))
            if (dataFile != null) Try(Files.deleteIfExists(dataFile))
        }
    }

    /** Write the projected destination CSV to a local temp file for PUT. Unlike
     *  Postgres (which streams from an S3 temp object), Snowflake's PUT needs a
     *  local file path. The CSV is built exactly as Postgres builds it. */
    private def createStagingFile(): Path = {
        val data = if (jobContext.data.rows != null && jobContext.data.rows.nonEmpty)
            projectRowsToDestSchema(jobContext.data.rows).mkString("\n")
        else if (jobContext.data.rawData != null)
            "\"" + jobContext.data.rawData.replace("\"", "\"\"") + "\""
        else
            throw new DatrisException("No data to load — both rows and rawData are empty")

        val file = Files.createTempFile("snowflake-load-", ".csv")
        file.toFile.deleteOnExit()
        Files.write(file, data.getBytes(StandardCharsets.UTF_8))
        file
    }

    /** Reorder/drop source columns to match the destination schema. Mirrors
     *  PostgresLoader.projectRowsToDestSchema. */
    private def projectRowsToDestSchema(rows: List[String]): List[String] = {
        val sourceFields = config.source.schemaProperties.fields.asScala.toList
        val destFields = config.destination.schemaProperties.fields.asScala.toList

        val delimiter = if (config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null)
            config.source.fileAttributes.csvAttributes.delimiter
        else
            ","

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

    private def loadData(statement: Statement, dataFile: Path): Unit = {
        statusUtil.info("processing", "Loading data into " + db.table)

        if (!db.manageTableManually)
            createTableIfUndefined(statement)

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
        val stage = "@%" + quote(db.table)
        putFile(statement, dataFile, stage)
        val copied = copyStagedInto(statement, qualifiedTable(), stage, copyFields())
        statusUtil.info("processing", "Rows loaded into target: " + copied)
    }

    /** Upsert path: stage into a session TEMP table that mirrors the target, then
     *  MERGE on keyFields. The temp table is session-scoped and auto-dropped. */
    private def mergeInto(statement: Statement, dataFile: Path): Unit = {
        val targetRef = qualifiedTable()
        val stgName = "DATRIS_STG_" + UUID.randomUUID().toString.replace("-", "_")
        val stgRef = quote(stgName)
        try {
            statement.execute(s"CREATE TEMPORARY TABLE $stgRef LIKE $targetRef")
            statusUtil.info("processing", "Staging table created: " + stgName)

            val fields = copyFields()
            putFile(statement, dataFile, "@%" + stgRef)
            val copied = copyStagedInto(statement, stgRef, "@%" + stgRef, fields)
            statusUtil.info("processing", "Rows copied to staging: " + copied)

            val keyNames = db.keyFields.asScala.toList
            val keySet = keyNames.map(_.toLowerCase).toSet
            val colNames = fields.map(_.name)
            val nonKey = colNames.filterNot(c => keySet.contains(c.toLowerCase))

            val onClause = keyNames.map(k => s"t.${quote(k)} = s.${quote(k)}").mkString(" AND ")
            val insertCols = colNames.map(quote).mkString(", ")
            val insertVals = colNames.map(c => "s." + quote(c)).mkString(", ")

            val merge = new StringBuilder()
            merge.append(s"MERGE INTO $targetRef t USING $stgRef s ON $onClause ")
            if (nonKey.nonEmpty) {
                val setClause = nonKey.map(c => s"t.${quote(c)} = s.${quote(c)}").mkString(", ")
                merge.append(s"WHEN MATCHED THEN UPDATE SET $setClause ")
            }
            merge.append(s"WHEN NOT MATCHED THEN INSERT ($insertCols) VALUES ($insertVals)")

            statusUtil.info("processing", "Merge command: " + merge.toString())
            val merged = statement.executeUpdate(merge.toString())
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
        val colList = fields.map(f => quote(f.name)).mkString(", ")
        val hasVariant = fields.exists(f => f.name.equalsIgnoreCase("_json"))

        val fileFormat = "FILE_FORMAT = (TYPE = CSV FIELD_OPTIONALLY_ENCLOSED_BY = '\"' " +
            "FIELD_DELIMITER = '" + csvDelimiter() + "' EMPTY_FIELD_AS_NULL = TRUE COMPRESSION = AUTO)"

        val copy = if (hasVariant) {
            val selectList = fields.zipWithIndex.map { case (f, i) =>
                val pos = "$" + (i + 1)
                if (f.name.equalsIgnoreCase("_json")) s"PARSE_JSON($pos)" else pos
            }.mkString(", ")
            s"COPY INTO $tableRef ($colList) FROM (SELECT $selectList FROM $stage) $fileFormat PURGE = TRUE"
        } else {
            s"COPY INTO $tableRef ($colList) FROM $stage $fileFormat PURGE = TRUE"
        }

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

    private def createTableIfUndefined(statement: Statement): Unit = {
        val sql = new StringBuilder()
        sql.append("CREATE TABLE IF NOT EXISTS " + qualifiedTable() + " (")
        config.destination.schemaProperties.fields.forEach(field => {
            sql.append(quote(field.name) + " " + snowflakeType(field) + ", ")
        })
        sql.setLength(sql.length - 2)

        if (db.keyFields != null && !db.keyFields.isEmpty) {
            val keys = db.keyFields.asScala.map(quote).mkString(", ")
            sql.append(", PRIMARY KEY (" + keys + ")")
        }
        sql.append(")")

        if (db.schema != null && db.schema.nonEmpty)
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schemaRef())

        statusUtil.info("processing", "Snowflake create table statement: " + sql.toString())
        statement.execute(sql.toString())

        // Additive schema evolution: add any new columns the table doesn't have yet.
        val existing = scala.collection.mutable.Set[String]()
        val rs = statement.executeQuery(
            s"""SELECT column_name FROM ${ident(db.dbName)}.information_schema.columns
               |WHERE table_schema = '${db.schema.toUpperCase}' AND table_name = '${db.table.toUpperCase}'""".stripMargin)
        try {
            while (rs.next()) existing.add(rs.getString("column_name").toLowerCase)
        } finally {
            rs.close()
        }

        config.destination.schemaProperties.fields.forEach(field => {
            if (!existing.contains(field.name.toLowerCase)) {
                val alter = s"ALTER TABLE ${qualifiedTable()} ADD COLUMN IF NOT EXISTS ${quote(field.name)} ${snowflakeType(field)}"
                statusUtil.info("processing", "Schema evolution: " + alter)
                statement.execute(alter)
            }
        })
    }

    /** Platform type -> Snowflake DDL type. Mirrors the inline mapping in
     *  PostgresLoader.createTableIfUndefined; `_json` -> VARIANT is the analog of
     *  Postgres' `_json` -> json. Unknown types pass through (e.g. decimal(p,s),
     *  which Snowflake accepts as a NUMBER synonym). */
    private def snowflakeType(field: SchemaField): String = {
        if (field.name.equalsIgnoreCase("_json")) "VARIANT"
        else if (field.name.equalsIgnoreCase("_xml")) "VARCHAR"
        else field.`type`.toLowerCase match {
            case "string"                                 => "VARCHAR"
            case "int" | "integer" | "tinyint" |
                 "smallint" | "bigint"                    => "NUMBER(38,0)"
            case "float" | "double"                       => "FLOAT"
            case "boolean"                                => "BOOLEAN"
            case "date"                                   => "DATE"
            case "timestamp"                              => "TIMESTAMP_NTZ"
            case _                                        => field.`type`
        }
    }

    private def csvDelimiter(): String =
        if (config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null)
            config.source.fileAttributes.csvAttributes.delimiter
        else ","

    /** The secret's `account` field should be a bare account identifier
     *  (`orgname-accountname` or `locator.region`), but Snowsight's copy
     *  buttons hand out the full account URL — and a doubled
     *  `<host>.snowflakecomputing.com.snowflakecomputing.com` still resolves
     *  via wildcard DNS, failing later with an opaque HTTP 404 at session
     *  open. Accept any of the shapes: strip the protocol, the
     *  snowflakecomputing.com suffix, and trailing slashes; swap underscores
     *  for hyphens (required in hostnames per Snowflake's URL rules). */
    private def normalizeAccount(raw: String): String =
        raw.trim
            .replaceFirst("(?i)^[a-z]+://", "")
            .replaceFirst("(?i)\\.snowflakecomputing\\.(com|cn).*$", "")
            .stripSuffix("/")
            .replace('_', '-')

    /** Secret values are entered through single-line form fields, which strip
     *  the newlines out of a pasted PEM — and the driver's PemReader requires
     *  strict PEM framing, so the stored key fails with "readPemObject()
     *  returned null". Rebuild it: honor literal \n sequences, pull the base64
     *  body out from between the BEGIN/END markers (or treat the whole value
     *  as the body when the markers are missing), and re-wrap at 64 columns. */
    private def normalizePrivateKeyPem(raw: String, hasPassphrase: Boolean): String = {
        val withNewlines = raw.trim.replace("\\n", "\n")
        val headerRe = "-----BEGIN ([A-Z0-9 ]+?)-----".r
        val label = headerRe.findFirstMatchIn(withNewlines).map(_.group(1)).getOrElse("PRIVATE KEY")

        if (label == "RSA PRIVATE KEY")
            throw new DatrisException("Snowflake 'privateKey' is a PKCS#1 key (BEGIN RSA PRIVATE KEY), which the Snowflake driver does not accept. Convert it to PKCS#8 first: openssl pkcs8 -topk8 -nocrypt -in <key-file>")
        if (label == "ENCRYPTED PRIVATE KEY" && !hasPassphrase)
            throw new DatrisException("Snowflake 'privateKey' is encrypted (BEGIN ENCRYPTED PRIVATE KEY) but the secret has no 'privateKeyPassphrase' field. Add the passphrase to the secret, or store an unencrypted PKCS#8 key")

        val body = "-----(BEGIN|END) [A-Z0-9 ]+?-----".r
            .replaceAllIn(withNewlines, "")
            .replaceAll("\\s", "")
        if (body.isEmpty)
            throw new DatrisException("Snowflake 'privateKey' has no key material after the PEM header — re-paste the full contents of the key file (the form field may have truncated it)")

        val wrapped = body.grouped(64).mkString("\n")
        s"-----BEGIN $label-----\n$wrapped\n-----END $label-----\n"
    }

    /** Double-quote a Snowflake identifier (preserves case, matches the CSV
     *  header / COPY column list exactly). */
    private def quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
    private def ident(identifier: String): String = quote(identifier)
    private def schemaRef(): String = quote(db.dbName) + "." + quote(db.schema)
    private def qualifiedTable(): String = quote(db.dbName) + "." + quote(db.schema) + "." + quote(db.table)

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
