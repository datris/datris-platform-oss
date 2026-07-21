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
import java.util.UUID
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

        statusUtil.info("begin", "Loading the data into Postgres database: " + dbName + ", table: " + config.destination.database.table)

        val secrets = SecretsRetrieverUtil.postgresSecrets()

        Class.forName("org.postgresql.Driver")
        statusUtil.info("processing", "Postgres driver loaded successfully")

        val jdbcUrl = secrets.jdbcUrl + "/" + dbName
        statusUtil.info("processing", "jdbc url: " + LogRedactUtil.redactJdbcUrl(jdbcUrl))
        // Pooled: close() below returns the connection to the pool; Hikari
        // resets autoCommit on return, so the transaction toggle is safe.
        PostgresPool.withConnection(jdbcUrl, secrets.username, secrets.password) { conn =>
            statusUtil.info("processing", "Postgres connection acquired")
            if (config.destination.database.useTransaction)
                conn.setAutoCommit(false)
            val statement = conn.createStatement()
            try {
                val file = createStagingFile()

                copyInto(conn, statement, file)

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

    private def createStagingFile(): String = {
        // Write the data to a temp location
        val tempUrl = "s3://" + DatrisEnvironment.current.environment + "-temp/data/" + UUID.randomUUID().toString + ".csv"
        val data = if (jobContext.data.rows != null && jobContext.data.rows.nonEmpty)
            projectRowsToDestSchema(jobContext.data.rows).mkString("\n")
        else if (jobContext.data.rawData != null)
            // Wrap rawData in CSV quoting — escape internal quotes by doubling them
            "\"" + jobContext.data.rawData.replace("\"", "\"\"") + "\""
        else
            throw new DatrisException("No data to load — both rows and rawData are empty")
        ObjectStoreUtil.writeBucketObject(ObjectStoreUtil.getBucket(tempUrl), ObjectStoreUtil.getKey(tempUrl), data)
        tempUrl
    }

    private def projectRowsToDestSchema(rows: List[String]): List[String] = {
        val sourceFields = config.source.schemaProperties.fields.asScala.toList
        val destFields = config.destination.schemaProperties.fields.asScala.toList

        // Determine delimiter — CSV uses configured delimiter, fallback to comma
        val delimiter = if (config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null)
            config.source.fileAttributes.csvAttributes.delimiter
        else
            ","

        logger.info("projectRowsToDestSchema: source fields=" + sourceFields.map(_.name).mkString(",") +
            " dest fields=" + destFields.map(_.name).mkString(","))

        // If source and destination schemas have the same fields in the same order, no projection needed
        if (sourceFields.size == destFields.size && sourceFields.map(_.name.toLowerCase) == destFields.map(_.name.toLowerCase)) {
            logger.info("projectRowsToDestSchema: schemas match, no projection needed")
            return rows
        }

        // Build a map from source field name (lowercase) to its position index
        val sourceIndex: Map[String, Int] = {
            if (jobContext.data.header != null && jobContext.data.header.nonEmpty)
                jobContext.data.header.zipWithIndex.map { case (name, idx) => name.toLowerCase -> idx }.toMap
            else
                sourceFields.zipWithIndex.map { case (f, idx) => f.name.toLowerCase -> idx }.toMap
        }

        // For each destination field that exists in source, find its index
        // Missing columns (dropped from CSV) are skipped — Postgres defaults them to NULL
        val projectedDest = destFields.filter(f => sourceIndex.contains(f.name.toLowerCase))
        val destColumnIndices = projectedDest.map(f => sourceIndex(f.name.toLowerCase))

        if (projectedDest.size < destFields.size) {
            val missing = destFields.filterNot(f => sourceIndex.contains(f.name.toLowerCase)).map(_.name)
            statusUtil.info("processing", "Dropped columns (will be NULL in destination): " + missing.mkString(", "))
        }

        statusUtil.info(
            "processing",
            "Projecting " + sourceFields.size + " source columns to " + projectedDest.size + " destination columns: " + projectedDest.map(_.name).mkString(", ")
        )

        rows.map { row =>
            val columns = row.split(delimiter, -1).toList
            destColumnIndices.map(idx => if (idx < columns.size) columns(idx) else "").mkString(delimiter)
        }
    }

    private def copyInto(conn: Connection, statement: Statement, fileUrl: String): Unit = {
        statusUtil.info("processing", "Copying data into " + config.destination.database.table)

        if (!config.destination.database.manageTableManually)
            createTableIfUndefined(statement, config.destination.database.table)

        // Truncate AFTER create-if-not-exists so first-run pipelines with truncateBeforeWrite=true
        // don't fail against a not-yet-existing table.
        if (config.destination.database.truncateBeforeWrite) {
            statusUtil.info("processing", "'truncateTableBeforeWrite' is set to true, truncating table")
            statement.execute("truncate table \"" + dbName + "\".\"" + config.destination.database.schema + "\".\"" + config.destination.database.table + "\"")
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

        if (useUpsert) upsertInto(conn, statement, fileUrl)
        else rawCopyInto(conn, statement, fileUrl)
    }

    /** Original load path: COPY straight into the target table. Fastest, used when
     *  keyFields aren't set or truncateBeforeWrite=true. Duplicates against any
     *  unique constraint fail the load — that's the contract for non-keyFields
     *  pipelines, and that's what truncate prevents in the truncate case. */
    private def rawCopyInto(conn: Connection, statement: Statement, fileUrl: String): Unit = {
        val sql = new StringBuilder()
        sql.append("COPY " + "\"" + config.destination.database.schema + "\"" + "." + "\"" + config.destination.database.table + "\"")

        // Only include columns that are present in the data (handles dropped columns)
        val destFields = config.destination.schemaProperties.fields.asScala
        val copyFields = if (jobContext.data.header != null && jobContext.data.header.nonEmpty) {
            val headerSet = jobContext.data.header.map(_.toLowerCase).toSet
            destFields.filter(f => headerSet.contains(f.name.toLowerCase))
        } else destFields
        sql.append(" (")
        sql.append(copyFields.map(f => "\"" + f.name + "\"").mkString(", "))
        sql.append(")")

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
        val inputStream = ObjectStoreUtil.getInputStream(ObjectStoreUtil.getBucket(fileUrl), ObjectStoreUtil.getKey(fileUrl))
        val rowsInserted = new CopyManager(conn.unwrap(classOf[BaseConnection]))
            .copyIn(sql.mkString, inputStream)
        statusUtil.info("processing", "Rows inserted into table: " + rowsInserted.toString)

        inputStream.close()
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
    private def upsertInto(conn: Connection, statement: Statement, fileUrl: String): Unit = {
        val schema = config.destination.database.schema
        val tableName = config.destination.database.table
        val targetRef = "\"" + dbName + "\".\"" + schema + "\".\"" + tableName + "\""

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
        val stagingRef = "\"" + stagingName + "\""

        try {
            statement.execute(s"CREATE TEMP TABLE $stagingRef (LIKE $targetRef INCLUDING DEFAULTS)")
            statusUtil.info("processing", "Staging table created: " + stagingName)

            val destFields = config.destination.schemaProperties.fields.asScala
            val copyFields = if (jobContext.data.header != null && jobContext.data.header.nonEmpty) {
                val headerSet = jobContext.data.header.map(_.toLowerCase).toSet
                destFields.filter(f => headerSet.contains(f.name.toLowerCase))
            } else destFields

            val colList = copyFields.map(f => "\"" + f.name + "\"").mkString(", ")

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
            val inputStream = ObjectStoreUtil.getInputStream(ObjectStoreUtil.getBucket(fileUrl), ObjectStoreUtil.getKey(fileUrl))
            val rowsCopied =
                try {
                    new CopyManager(conn.unwrap(classOf[BaseConnection])).copyIn(copySql.mkString, inputStream)
                } finally {
                    inputStream.close()
                }
            statusUtil.info("processing", "Rows copied to staging: " + rowsCopied)

            // Upsert: INSERT...SELECT ON CONFLICT. If every column in the load
            // is a key column there's nothing to update on conflict, so we
            // emit DO NOTHING — preserves the existing row, doesn't error.
            val keyFieldNames = config.destination.database.keyFields.asScala.toList
            val keyFieldSet = keyFieldNames.map(_.toLowerCase).toSet
            val copyFieldNames = copyFields.map(_.name).toList
            val nonKeyFields = copyFieldNames.filterNot(f => keyFieldSet.contains(f.toLowerCase))
            val keyList = keyFieldNames.map(k => "\"" + k + "\"").mkString(", ")

            val upsertSql = if (nonKeyFields.isEmpty) {
                s"INSERT INTO $targetRef ($colList) SELECT $colList FROM $stagingRef ON CONFLICT ($keyList) DO NOTHING"
            } else {
                val setClause = nonKeyFields.map(f => "\"" + f + "\" = EXCLUDED.\"" + f + "\"").mkString(", ")
                s"INSERT INTO $targetRef ($colList) SELECT $colList FROM $stagingRef ON CONFLICT ($keyList) DO UPDATE SET $setClause"
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
        val findConstraintSql =
            s"""SELECT array_agg(a.attname::text ORDER BY a.attname) as cols
               |FROM pg_constraint c
               |JOIN pg_class t ON c.conrelid = t.oid
               |JOIN pg_namespace n ON t.relnamespace = n.oid
               |JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(c.conkey)
               |WHERE n.nspname = '$schema'
               |  AND t.relname = '$tableName'
               |  AND c.contype IN ('p', 'u')
               |GROUP BY c.conname""".stripMargin

        val rs = statement.executeQuery(findConstraintSql)
        var found = false
        while (rs.next() && !found) {
            val arr = rs.getArray(1)
            if (arr != null) {
                val cols = arr.getArray.asInstanceOf[Array[AnyRef]].map(_.toString.toLowerCase).toList.sorted
                if (cols == expectedCols) found = true
            }
        }
        rs.close()

        if (found) return

        // No matching constraint. Add a unique index. Using a unique INDEX
        // rather than a CONSTRAINT because Postgres' ON CONFLICT works against
        // either, and a bare index is slightly easier to manage if the user
        // later changes keyFields (index is droppable without disturbing
        // anything else).
        val safeColPart = keyFieldNames.map(_.toLowerCase.replaceAll("[^a-z0-9_]", "_")).mkString("_")
        val indexName = ("datris_uniq_" + tableName + "_" + safeColPart).take(63) // Postgres identifier limit
        val cols = keyFieldNames.map(k => "\"" + k + "\"").mkString(", ")
        val createIdx = s"""CREATE UNIQUE INDEX IF NOT EXISTS "$indexName" ON "$dbName"."$schema"."$tableName" ($cols)"""
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

    private def createTableIfUndefined(statement: Statement, tableName: String): Unit = {
        val sql = new StringBuilder()

        // Begin
        val schema = config.destination.database.schema
        sql.append("create table if not exists \"" + dbName + "\".\"" + schema + "\".\"" + tableName + "\" (")

        // Fields
        config.destination.schemaProperties.fields.forEach(field => {
            sql.append("\"" + field.name + "\" ")
            // Force the semi-structured field type to SUPER
            if (field.name.compareToIgnoreCase("_json") == 0)
                sql.append("json, ")
            else if (field.name.compareToIgnoreCase("_xml") == 0)
                sql.append("xml, ")
            else if (field.`type`.compareToIgnoreCase("tinyint") == 0)
                sql.append("int2, ")
            else if (field.`type`.compareToIgnoreCase("smallint") == 0)
                sql.append("int2, ")
            else if (field.`type`.compareToIgnoreCase("float") == 0)
                sql.append("float4, ")
            else if (field.`type`.compareToIgnoreCase("double") == 0)
                sql.append("float8, ")
            else if (field.`type`.compareToIgnoreCase("string") == 0)
                sql.append("text, ")
            else
                sql.append(field.`type` + ", ")
        })
        sql.setLength(sql.length - 2)

        // Keys?
        if (config.destination.database.keyFields != null) {
            sql.append(", primary key (")
            config.destination.database.keyFields.forEach(field => {
                sql.append(field + ", ")
            })
            sql.setLength(sql.length - 2)
            sql.append(")")
        }

        // End
        sql.append(");")

        // Create schema if it doesn't exist
        if (config.destination.database.schema != null && config.destination.database.schema.nonEmpty) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + config.destination.database.schema + ";")
        }

        statusUtil.info("processing", "Postgres create table statement: " + sql.mkString)
        statement.execute(sql.mkString)

        // Additive schema evolution: add any new columns that don't exist in the table yet
        val existingColumnsRs = statement.executeQuery(
            s"""SELECT column_name FROM information_schema.columns
               |WHERE table_catalog = '$dbName' AND table_schema = '${config.destination.database.schema}'
               |AND table_name = '$tableName'""".stripMargin
        )
        val existingColumns = scala.collection.mutable.Set[String]()
        while (existingColumnsRs.next()) {
            existingColumns.add(existingColumnsRs.getString("column_name").toLowerCase)
        }
        existingColumnsRs.close()

        config.destination.schemaProperties.fields.forEach(field => {
            if (!existingColumns.contains(field.name.toLowerCase)) {
                val colType = if (field.`type`.equalsIgnoreCase("string")) "text" else field.`type`
                val alterSql =
                    s"""ALTER TABLE "$dbName"."${config.destination.database.schema}"."$tableName" ADD COLUMN IF NOT EXISTS "${field.name}" $colType"""
                statusUtil.info("processing", "Schema evolution: " + alterSql)
                statement.execute(alterSql)
            }
        })
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
