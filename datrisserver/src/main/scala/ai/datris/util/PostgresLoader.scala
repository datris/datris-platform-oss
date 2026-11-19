package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{Notification, DatrisEnvironment}
import ai.datris.model._
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, Statement}
import scala.collection.JavaConverters._
import scala.util.Try

class PostgresLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PostgresLoader])
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    /** In multi-tenant mode, use the tenant's isolated database; otherwise use the pipeline config value. */
    private val dbName: String = if (DatrisEnvironment.current.multiTenant) DatrisEnvironment.current.environment
    else config.destination.database.dbName

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)

        // Every name is a quoted identifier in the statements below
        // (PostgresSql) and the database name is percent-encoded into the
        // URL. Defense in depth: PipelineValidatorUtil enforces the safe
        // charset at config time; re-check here so a config that reaches the
        // loader by another path still loads only into a plain name.
        PipelineValidatorUtil.validateSqlIdentifier(dbName, "destination.database.dbName")
        PipelineValidatorUtil.validateSqlIdentifier(config.destination.database.schema, "destination.database.schema")
        PipelineValidatorUtil.validateSqlIdentifier(config.destination.database.table, "destination.database.table")

        statusUtil.info("begin", "Loading the data into Postgres database: " + dbName + ", table: " + config.destination.database.table)

        val secrets = SecretsRetrieverUtil.postgresSecrets()

        Class.forName("org.postgresql.Driver")
        statusUtil.info("processing", "Postgres driver loaded successfully")

        val jdbcUrl = PostgresSql.jdbcUrl(secrets.jdbcUrl, dbName)
        statusUtil.info("processing", "jdbc url: " + LogRedactUtil.redactJdbcUrl(jdbcUrl))
        // Pooled: close() below returns the connection to the pool; Hikari
        // resets autoCommit on return, so the transaction toggle is safe.
        PostgresPool.withConnection(jdbcUrl, secrets.username, secrets.password) { conn =>
            statusUtil.info("processing", "Postgres connection acquired")
            if (config.destination.database.useTransaction)
                conn.setAutoCommit(false)
            val statement = conn.createStatement()
            try {
                copyInto(conn, statement)

                if (config.destination.database.useTransaction)
                    conn.commit()
                sendNotification()
                statusUtil.info("end", "Process completed")
            } catch {
                case e: Exception =>
                    if (config.destination.database.useTransaction)
                        Try(conn.rollback())
                    throw e
            } finally {
                statement.close()
            }
        }
    }

    /** The COPY payload, streamed straight from the staged file through
      * [[DestSchemaProjector.csvBody]] — no temp object, no whole-payload
      * String. Opened once per COPY. */
    private def openCopyStream(): StagedRows.TextChunkInputStream =
        new StagedRows.TextChunkInputStream(DestSchemaProjector.csvBody(jobContext))

    /** Run a COPY FROM STDIN fed by [[openCopyStream]]. A failure raised while
      * projecting rows is the loader's error (not the driver's truncated-copy
      * message). Returns the rows the driver reports. */
    private def copyFromStaged(conn: Connection, sql: String): Long = {
        val inputStream = openCopyStream()
        try {
            new CopyManager(conn.unwrap(classOf[BaseConnection])).copyIn(sql, inputStream)
        } catch {
            case e: Exception =>
                if (inputStream.failure != null) {
                    val failure = new DatrisException("Failed while projecting rows for COPY: " + inputStream.failure.getMessage)
                    failure.initCause(inputStream.failure)
                    throw failure
                }
                throw e
        } finally {
            inputStream.close()
        }
    }

    private def copyInto(conn: Connection, statement: Statement): Unit = {
        // Fail on an empty payload BEFORE any DDL or TRUNCATE — the pre-Phase-2
        // staging-file step threw here, so a truncate-and-load whose payload
        // came out empty (e.g. a row function dropped every row) must leave
        // the table untouched, autocommit or not.
        DestSchemaProjector.requireCsvBody(jobContext)

        statusUtil.info("processing", "Copying data into " + config.destination.database.table)

        if (!config.destination.database.manageTableManually)
            createTableIfUndefined(statement, config.destination.database.table)

        // Truncate AFTER create-if-not-exists so first-run pipelines with truncateBeforeWrite=true
        // don't fail against a not-yet-existing table.
        if (config.destination.database.truncateBeforeWrite) {
            statusUtil.info("processing", "'truncateTableBeforeWrite' is set to true, truncating table")
            statement.execute(PostgresSql.truncate(dbName, config.destination.database.schema, config.destination.database.table))
        }

        // Routing: when keyFields are configured AND truncateBeforeWrite is false,
        // duplicates against the natural key are EXPECTED (incremental loads,
        // refresh of recent partitions, backfills over already-loaded windows).
        // Use the staging + INSERT…ON CONFLICT path so they upsert gracefully.
        // Otherwise stay on raw COPY (faster: ~2-5x). Truncate already prevents
        // duplicates, so the slower path adds no value when truncate is true.
        val useUpsert = !config.destination.database.truncateBeforeWrite &&
            config.destination.database.keyFields != null &&
            !config.destination.database.keyFields.isEmpty

        if (useUpsert) upsertInto(conn, statement)
        else rawCopyInto(conn, statement)
    }

    /** Original load path: COPY straight into the target table. Fastest, used when
     *  keyFields aren't set or truncateBeforeWrite=true. Duplicates against any
     *  unique constraint fail the load — that's the contract for non-keyFields
     *  pipelines, and that's what truncate prevents in the truncate case. */
    private def rawCopyInto(conn: Connection, statement: Statement): Unit = {
        // Only include columns that are present in the data (handles dropped columns)
        val destFields = config.destination.schemaProperties.fields.asScala
        val copyFields = if (jobContext.data.header != null && jobContext.data.header.nonEmpty) {
            val headerSet = jobContext.data.header.map(_.toLowerCase).toSet
            destFields.filter(f => headerSet.contains(f.name.toLowerCase))
        } else destFields
        val sql = new StringBuilder()
        sql.append(PostgresSql.copyTarget(config.destination.database.schema, config.destination.database.table, copyFields.map(_.name).toSeq))

        sql.append(" FROM STDIN (")

        // Append the options (i.e. DELIMITER ',', FORMAT csv, etc)
        if (config.destination.database.options != null) {
            val options = config.destination.database.options.asScala.mkString(", ")
            sql.append(options)
        } else {
            // Postgres CSV format treats unquoted empty fields as NULL by default,
            // which is what every standard CSV exporter expects. Sources with
            // non-empty NULL placeholders (e.g. some sources use "." as a NULL placeholder) should normalize them
            // at their own layer, or set destination.database.options explicitly.
            sql.append("FORMAT csv")
        }

        sql.append(")")

        statusUtil.info("processing", "Copy command: " + sql.toString())
        val rowsInserted = copyFromStaged(conn, sql.mkString)
        statusUtil.info("processing", "Rows inserted into table: " + rowsInserted.toString)
    }

    /** Upsert path: COPY into a session-local staging table, then INSERT…SELECT
     *  with ON CONFLICT (keyFields) DO UPDATE SET (non_key_cols) = EXCLUDED.(non_key_cols).
     *  Used when keyFields are configured and truncateBeforeWrite is false —
     *  the combination that, on raw COPY, fails hard on duplicate-key rows.
     *
     *  Semantics: an incoming row with a key match REPLACES the existing row
     *  (full overwrite of non-key columns, including NULLs). Sources that need
     *  "merge non-nulls only" must coalesce upstream. This matches Mongo's
     *  upsertJSON semantics in MongoDBLoader. */
    private def upsertInto(conn: Connection, statement: Statement): Unit = {
        val schema = config.destination.database.schema
        val tableName = config.destination.database.table
        val targetRef = PostgresSql.tableRef(dbName, schema, tableName)

        // Pre-flight: ON CONFLICT (cols) requires a matching unique constraint
        // or unique index. Tables Datris created with keyFields already have a
        // PRIMARY KEY matching them (see createTableIfUndefined), so this is a
        // no-op for new pipelines. The work matters when a user retrofits
        // keyFields onto an existing table that was first loaded without them.
        ensureUniqueIndexForKeyFields(statement, schema, tableName)

        // Staging table mirrors target structure (column list + types only — no
        // indexes, no constraints, no defaults needed for upsert source). Named
        // with a UUID-derived suffix so concurrent loads to the same pipeline
        // (rare but possible) don't collide.
        //
        // NOTE: deliberately NOT using `ON COMMIT DROP`. With autocommit
        // (useTransaction=false) every statement commits on its own, so
        // ON COMMIT DROP fires immediately after CREATE — the table is gone
        // before COPY runs and the COPY fails with "relation does not exist".
        // Postgres TEMP tables are session-scoped regardless, so they get
        // dropped when the connection closes. The explicit DROP IF EXISTS in
        // the finally block below handles the in-flight cleanup for both
        // autocommit and transaction-mode connections.
        val stagingName = "datris_staging_" + java.util.UUID.randomUUID().toString.replace("-", "_")
        val stagingRef = PgIdentifier.quote(stagingName)

        try {
            statement.execute(s"CREATE TEMP TABLE $stagingRef (LIKE $targetRef INCLUDING DEFAULTS)")
            statusUtil.info("processing", "Staging table created: " + stagingName)

            val destFields = config.destination.schemaProperties.fields.asScala
            val copyFields = if (jobContext.data.header != null && jobContext.data.header.nonEmpty) {
                val headerSet = jobContext.data.header.map(_.toLowerCase).toSet
                destFields.filter(f => headerSet.contains(f.name.toLowerCase))
            } else destFields

            val keyFieldNames = config.destination.database.keyFields.asScala.toList
            val (colList, keyList, setClause) = PostgresSql.upsertLists(copyFields.map(_.name).toSeq, keyFieldNames)

            // COPY into staging (identical SQL shape to the raw path, just a different target).
            val copySql = new StringBuilder()
            copySql.append(s"COPY $stagingRef ($colList) FROM STDIN (")
            if (config.destination.database.options != null) {
                copySql.append(config.destination.database.options.asScala.mkString(", "))
            } else {
                copySql.append("FORMAT csv")
            }
            copySql.append(")")

            statusUtil.info("processing", "Copy command (staging): " + copySql.toString())
            val rowsCopied = copyFromStaged(conn, copySql.mkString)
            statusUtil.info("processing", "Rows copied to staging: " + rowsCopied)

            // Upsert: INSERT...SELECT ON CONFLICT. If every column in the load
            // is a key column there's nothing to update on conflict, so we
            // emit DO NOTHING — preserves the existing row, doesn't error.
            val upsertSql = setClause match {
                case None => s"INSERT INTO $targetRef ($colList) SELECT $colList FROM $stagingRef ON CONFLICT ($keyList) DO NOTHING"
                case Some(set) => s"INSERT INTO $targetRef ($colList) SELECT $colList FROM $stagingRef ON CONFLICT ($keyList) DO UPDATE SET $set"
            }

            statusUtil.info("processing", "Upsert command: " + upsertSql)
            val upserted = statement.executeUpdate(upsertSql)
            statusUtil.info("processing", "Rows upserted into target: " + upserted)
        } finally {
            // In-flight cleanup so the staging table doesn't accumulate on
            // long-lived connections. Final safety net: TEMP tables are
            // session-scoped and Postgres drops them when the connection closes.
            // Wrapped in Try so a cleanup failure can't mask the real error
            // from the load itself.
            Try(statement.execute(s"DROP TABLE IF EXISTS $stagingRef"))
        }
    }

    /** Ensure there's a unique constraint or unique index on the target table
     *  whose columns exactly match keyFields. ON CONFLICT (cols) needs this to
     *  exist or it raises "no unique or exclusion constraint matching" at load
     *  time — which would feel like an obscure error to a user who just added
     *  keyFields to their config.
     *
     *  No-op if a matching constraint/index already exists. When we need to add
     *  one and existing data has duplicates against the keyFields combination,
     *  CREATE UNIQUE INDEX fails — we re-throw with a clear remediation message
     *  instead of letting the raw Postgres error bubble up. */
    private def ensureUniqueIndexForKeyFields(statement: Statement, schema: String, tableName: String): Unit = {
        val keyFieldNames = config.destination.database.keyFields.asScala.toList
        val expectedCols = keyFieldNames.map(_.toLowerCase).sorted

        // Scan pg_constraint AND pg_indexes for any unique grouping that matches.
        // pg_constraint covers PRIMARY KEY + UNIQUE constraints; pg_indexes
        // covers bare unique indexes that weren't promoted to constraints.
        // Names bound, never spliced into the text.
        val ps = statement.getConnection.prepareStatement(PostgresSql.ConstraintColumnsSql)
        var found = false
        try {
            ps.setString(1, schema)
            ps.setString(2, tableName)
            val rs = ps.executeQuery()
            try {
                while (rs.next() && !found) {
                    val arr = rs.getArray(1)
                    if (arr != null) {
                        val cols = arr.getArray.asInstanceOf[Array[AnyRef]].map(_.toString.toLowerCase).toList.sorted
                        if (cols == expectedCols) found = true
                    }
                }
            } finally rs.close()
        } finally ps.close()

        if (found) return

        // No matching constraint. Add a unique index. Using a unique INDEX
        // rather than a CONSTRAINT because Postgres' ON CONFLICT works against
        // either, and a bare index is slightly easier to manage if the user
        // later changes keyFields (index is droppable without disturbing
        // anything else).
        val safeColPart = keyFieldNames.map(_.toLowerCase.replaceAll("[^a-z0-9_]", "_")).mkString("_")
        val indexName = ("datris_uniq_" + tableName + "_" + safeColPart).take(63) // Postgres identifier limit
        val createIdx = PostgresSql.uniqueIndex(indexName, dbName, schema, tableName, keyFieldNames)
        statusUtil.info("processing", "Adding unique index to enable keyFields upsert: " + createIdx)
        try {
            statement.execute(createIdx)
        } catch {
            case e: org.postgresql.util.PSQLException =>
                // Most likely "could not create unique index — Key (...) is duplicated."
                // The user retrofitted keyFields onto a table that already has duplicate
                // rows against those keys. We can't silently dedupe (lossy); surface
                // a clear error with remediation hints.
                throw new DatrisException(
                    "Cannot enable keyFields upsert on '" + tableName + "': the existing table " +
                        "already contains rows that violate the proposed unique key (" +
                        keyFieldNames.mkString(", ") + "). Resolve before retrying: " +
                        "(a) deduplicate the existing rows manually, " +
                        "(b) set truncateBeforeWrite=true to wipe and reload, " +
                        "or (c) choose a different keyFields combination that's actually unique. " +
                        "Underlying Postgres error: " + e.getMessage
                )
        }
    }

    /** Create the table when it is missing, then add any configured column
      * the table lacks. When every non-document field's type passes
      * [[ColumnTypeText.loadText]], the statements are those earlier versions ran,
      * in the same order. Otherwise (a stored type the save rule no longer
      * accepts) the existing columns are read first: the run fails before any
      * statement when such a type would be needed to create the table or a
      * column ([[ColumnTypeText.loadProblem]]); else the table is not created
      * again, only missing columns are added, and one status line names the
      * fields whose stored type was not used. */
    private def createTableIfUndefined(statement: Statement, tableName: String): Unit = {
        val schema = config.destination.database.schema
        val fields = config.destination.schemaProperties.fields.asScala.toList

        // Create schema and table (PostgresSql.createPlan: nothing when a
        // stored type would not be sent).
        val create = PostgresSql.createPlan(dbName, schema, tableName, fields, config.destination.database.keyFields)
        create.schemaSql.foreach(sql => statement.execute(sql))
        create.tableSql.foreach { sql =>
            statusUtil.info("processing", "Postgres create table statement: " + sql)
            statement.execute(sql)
        }

        // Additive schema evolution: add any new columns that don't exist in the table yet
        val existingColumns = readExistingColumns(statement, tableName)
        PostgresSql.evolvePlan(dbName, schema, tableName, fields, existingColumns) match {
            case Left(problem) => throw new DatrisException(problem)
            case Right(plan) =>
                plan.statusLine.foreach(line => statusUtil.info("processing", line))
                plan.addColumns.foreach { alterSql =>
                    statusUtil.info("processing", "Schema evolution: " + alterSql)
                    statement.execute(alterSql)
                }
        }
    }

    /** The table's column names, lower-cased (empty when it does not exist). */
    private def readExistingColumns(statement: Statement, tableName: String): Set[String] = {
        val existingColumns = scala.collection.mutable.Set[String]()
        val existingPs = statement.getConnection.prepareStatement(PostgresSql.ExistingColumnsSql)
        try {
            existingPs.setString(1, dbName)
            existingPs.setString(2, config.destination.database.schema)
            existingPs.setString(3, tableName)
            val existingColumnsRs = existingPs.executeQuery()
            try {
                while (existingColumnsRs.next()) {
                    existingColumns.add(existingColumnsRs.getString("column_name").toLowerCase)
                }
            } finally existingColumnsRs.close()
        } finally existingPs.close()
        existingColumns.toSet
    }

    private def sendNotification(): Unit = {
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "postgres",
            null,
            null,
            null,
            config.destination.database.schema,
            dbName,
            config.destination.database.table,
            null
        )
        val gson = new Gson
        val jsonNotification = gson.toJson(notification)

        // Create the message attributes for the notification filter
        val attributes = new java.util.HashMap[String, String]
        attributes.put("pipeline", config.name)
        attributes.put("destination", "postgres")
        attributes.put("schema", config.destination.database.schema)
        attributes.put("database", dbName)
        attributes.put("table", config.destination.database.table)

        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}
