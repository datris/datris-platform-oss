package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{JobContext, DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import java.sql.{Connection, DriverManager}
import java.util.{Properties, UUID}
import scala.collection.JavaConverters._

class PGVectorLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil
    private val pgvectorConfig = config.destination.pgvector
    private val UPSERT_BATCH_SIZE = 100

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Process started")

        if (jobContext.data.rawBytes == null)
            throw new DatrisException(
                "pgvector destination requires unstructured file data (PDF, DOC, DOCX, HTML, text). Use 'unstructuredAttributes' in the source configuration."
            )

        // Extract text from the document
        val filename = if (jobContext.metadata != null) jobContext.metadata.dataFileName else ""
        val documentText = TextExtractorUtil.extractText(jobContext.data.rawBytes, filename)
        if (documentText.isEmpty)
            throw new DatrisException("No text could be extracted from the uploaded file: " + filename)

        statusUtil.info("processing", "Extracted " + documentText.length + " characters from: " + filename)

        // Chunk the document
        val chunkingConfig = if (pgvectorConfig.chunking != null) pgvectorConfig.chunking
        else new ai.datris.model.ChunkingConfig()
        val chunks = ChunkUtil.chunk(documentText, chunkingConfig)
        statusUtil.info("processing", "Chunked into " + chunks.size + " chunks using strategy: " + chunkingConfig.strategy)

        // Get configs — use tenant secret names if in multi-tenant mode
        val embeddingSecretName =
            if (DatrisEnvironment.current.embeddingSecretName != null) DatrisEnvironment.current.embeddingSecretName else pgvectorConfig.embeddingSecretName
        val pgvectorSecretName =
            if (DatrisEnvironment.current.pgvectorSecretName != null) DatrisEnvironment.current.pgvectorSecretName else pgvectorConfig.postgresSecretName
        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val pgSecret = SecretsRetrieverUtil.userSecret(pgvectorSecretName)
            .getOrElse(throw new DatrisException("PostgreSQL secret not found: " + pgvectorSecretName))
        val rawJdbcUrl = pgSecret.get("jdbcUrl")
        if (rawJdbcUrl == null) throw new DatrisException("'jdbcUrl' not found in pgvector secret: " + pgvectorConfig.postgresSecretName)
        // In multi-tenant mode, override the database in the JDBC URL to the tenant's isolated database
        val jdbcUrl = if (DatrisEnvironment.current.multiTenant) {
            rawJdbcUrl.replaceFirst("/[^/]*$", "/" + DatrisEnvironment.current.environment)
        } else rawJdbcUrl
        PostgresTlsGuard.validate(jdbcUrl, "pgvector")
        val username = Option(pgSecret.get("username")).getOrElse("postgres")
        val password = Option(pgSecret.get("password")).getOrElse("")

        // Names, checked before connecting. A pipeline saved without a table
        // name keeps writing the table earlier versions wrote (`null`).
        val schemaName = PGVectorSql.schemaOf(pgvectorConfig)
        val tableName = PGVectorSql.tableOf(pgvectorConfig)
        if (pgvectorConfig.tableName == null)
            statusUtil.warn(
                "processing",
                "This pipeline has no pgvector table name, so chunks are written to the table named null as in earlier versions. " +
                    "Set destination.pgvector.tableName."
            )
        requireNames(schemaName, tableName, metadataKeysNow)

        // Connect
        Class.forName("org.postgresql.Driver")
        val props = new Properties()
        props.setProperty("user", username)
        props.setProperty("password", password)

        statusUtil.info("processing", "Connecting to PostgreSQL at " + LogRedactUtil.redactJdbcUrl(jdbcUrl))
        val conn = DriverManager.getConnection(jdbcUrl, props)

        try {
            conn.setAutoCommit(false)

            // Ensure pgvector extension and table exist
            val dimension = EmbeddingUtil.embeddingDimension(embeddingConfig)
            ensureTable(conn, schemaName, tableName, dimension)

            // Build column lists for metadata
            val metadataKeys = metadataKeysNow

            // Batch: embed + upsert. globalChunkIdx is the row's chunk_index AND
            // part of the deterministic PK seed; it advances per fitted chunk
            // because TokenGuard's split mode can fan one input chunk into N.
            var totalUpserted = 0
            var globalChunkIdx = 0
            chunks.grouped(UPSERT_BATCH_SIZE).foreach { batch =>
                val embedded = EmbeddingUtil.generateEmbeddings(batch, embeddingConfig)

                val sql = PGVectorSql.upsert(schemaName, tableName, metadataKeys)

                val stmt = conn.prepareStatement(sql)

                embedded.foreach { case EmbeddingUtil.EmbeddedChunk(chunkText, embedding) =>
                    val chunkIdx = globalChunkIdx
                    val objectId = UUID.nameUUIDFromBytes(
                        (jobContext.pipelineToken + "_" + chunkIdx).getBytes
                    )

                    var paramIdx = 1
                    stmt.setObject(paramIdx, objectId); paramIdx += 1
                    stmt.setString(paramIdx, chunkText); paramIdx += 1
                    stmt.setInt(paramIdx, chunkIdx); paramIdx += 1
                    stmt.setString(paramIdx, config.name); paramIdx += 1
                    stmt.setString(paramIdx, filename); paramIdx += 1

                    // Metadata columns
                    metadataKeys.foreach { key =>
                        stmt.setString(paramIdx, pgvectorConfig.metadata.get(key))
                        paramIdx += 1
                    }

                    // Embedding as vector string: [0.1,0.2,...]
                    val vectorStr = "[" + embedding.mkString(",") + "]"
                    stmt.setObject(paramIdx, vectorStr, java.sql.Types.OTHER)

                    stmt.addBatch()
                    globalChunkIdx += 1
                }

                stmt.executeBatch()
                stmt.close()
                totalUpserted += embedded.size
                statusUtil.info("processing", "Upserted " + totalUpserted + " chunks (input chunks: " + chunks.size + ")")
            }

            conn.commit()
            sendNotification()
            statusUtil.info("end", "Process completed, " + totalUpserted + " chunks upserted to table: " + schemaName + "." + tableName)
        } catch {
            case e: Exception =>
                conn.rollback()
                throw e
        } finally {
            conn.close()
        }
    }

    private def ensureTable(conn: Connection, schemaName: String, tableName: String, dimension: Int): Unit = {
        val stmt = conn.createStatement()
        try {
            // Enable pgvector extension. CREATE EXTENSION IF NOT EXISTS is NOT
            // race-safe: two concurrent sessions can both pass the existence
            // check and race on the pg_extension insert, with the loser hitting
            // "duplicate key ... pg_extension_name_index". Document taps fire
            // many concurrent loaders, so this race surfaces in practice. Take
            // a transaction-scoped advisory lock on a constant so the second
            // session waits, sees the extension exists, and no-ops.
            val extLockStmt = conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")
            try {
                extLockStmt.setString(1, "datris.create_extension.vector")
                val rs = extLockStmt.executeQuery()
                rs.close()
            } finally {
                extLockStmt.close()
            }
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")

            // Create schema if needed
            stmt.execute(PGVectorSql.createSchema(schemaName))

            // Serialize concurrent ensureTable calls on this specific table.
            // CREATE TABLE IF NOT EXISTS alone is NOT race-safe: concurrent
            // sessions (document taps fire many StreamNotifier.process calls at
            // once) can each pass the relation-exists check, then race on the
            // implicit composite type Postgres builds for the table's row type,
            // and the loser hits "duplicate key ... pg_type_typname_nsp_index".
            // A transactional advisory lock keyed on schema.table forces the
            // second session to wait, see the table exists on its turn, and
            // no-op. The lock releases at commit/rollback automatically.
            val lockKey = schemaName + "." + tableName
            val lockStmt = conn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")
            try {
                lockStmt.setString(1, lockKey)
                val rs = lockStmt.executeQuery()
                rs.close()
            } finally {
                lockStmt.close()
            }

            // If the table already exists with a different vector dimension, fail
            // fast with a clear error — otherwise the batch INSERT blows up with
            // a cryptic "expected N dimensions" from Postgres. pgvector stores
            // the declared dimension directly in pg_attribute.atttypmod (-1 means
            // unspecified, which we treat as "can't verify, proceed").
            val dimCheckSql =
                """SELECT a.atttypmod
                  |FROM pg_attribute a
                  |JOIN pg_class c ON c.oid = a.attrelid
                  |JOIN pg_namespace n ON n.oid = c.relnamespace
                  |WHERE n.nspname = ? AND c.relname = ?
                  |  AND a.attname = 'embedding' AND NOT a.attisdropped""".stripMargin
            val dimCheck = conn.prepareStatement(dimCheckSql)
            try {
                dimCheck.setString(1, schemaName)
                dimCheck.setString(2, tableName)
                val rs = dimCheck.executeQuery()
                if (rs.next()) {
                    val existing = rs.getInt(1)
                    if (existing > 0 && existing != dimension) {
                        throw new DatrisException(
                            "Embedding dimension mismatch on table \"" + schemaName + "." + tableName +
                                "\": existing is vector(" + existing + "), configured embedding provider produces vector(" + dimension +
                                "). The stored vectors are incompatible with the new provider. Either drop table \"" +
                                schemaName + "." + tableName + "\" and re-ingest, or point this pipeline at a new table."
                        )
                    }
                }
                rs.close()
            } finally {
                dimCheck.close()
            }

            val createSql = PGVectorSql.createTable(schemaName, tableName, metadataKeysNow, dimension)

            statusUtil.info("processing", "Ensuring table: " + schemaName + "." + tableName + " with vector dimension: " + dimension)
            stmt.execute(createSql)

            // Additive evolution for metadata columns: a table created before a
            // metadata key existed (user-added key, or provenance stamping turned
            // on) must gain the column or the INSERT column list fails.
            metadataKeysNow.foreach(key => stmt.execute(PGVectorSql.addColumn(schemaName, tableName, key)))
            conn.commit()
        } finally {
            stmt.close()
        }
    }

    /** The metadata keys as configured now (each becomes a TEXT column). */
    private def metadataKeysNow: List[String] =
        if (pgvectorConfig.metadata != null) pgvectorConfig.metadata.asScala.keys.toList else List.empty

    /** A schema, table or metadata key Postgres cannot take as a quoted
      * identifier (empty, or holding NUL), or two metadata keys PostgreSQL
      * would cut to one column name ([[PgIdentifier.kept]]), fail the load
      * with a plain message before any statement is built. */
    private def requireNames(schemaName: String, tableName: String, metadataKeys: Seq[String]): Unit = {
        def bad(s: String): Boolean = s == null || s.isEmpty || s.indexOf('\u0000') >= 0
        if (bad(schemaName))
            throw new DatrisException("The pgvector schema name must not be empty or contain NUL characters. Set destination.pgvector.schemaName.")
        if (bad(tableName))
            throw new DatrisException("The pgvector table name must not contain NUL characters. Set destination.pgvector.tableName.")
        if (metadataKeys.exists(bad))
            throw new DatrisException("pgvector metadata keys must not be empty or contain NUL characters. Fix destination.pgvector.metadata.")
        // Each key becomes a column; PostgreSQL keeps only the first 63 bytes
        // of a name, so two keys equal in those bytes would be one column.
        val firstByCut = scala.collection.mutable.LinkedHashMap[String, String]()
        metadataKeys.foreach { k =>
            val cut = PgIdentifier.kept(k)
            firstByCut.get(cut) match {
                case Some(other) if other != k =>
                    throw new DatrisException(
                        "pgvector metadata keys '" + other + "' and '" + k + "' are the same in their first " + PgIdentifier.MaxBytes +
                            " bytes, which is all PostgreSQL keeps of a column name, so they would be one column. " +
                            "Rename one of them in destination.pgvector.metadata."
                    )
                case Some(_) =>
                case None => firstByCut(cut) = k
            }
        }
    }

    private def sendNotification(): Unit = {
        val schemaName = PGVectorSql.schemaOf(pgvectorConfig)
        val attributes = Map(
            "database" -> "",
            "schema" -> schemaName,
            "pipeline" -> config.name,
            "destination" -> "pgvector",
            "table" -> pgvectorConfig.tableName
        )
        val notification = Map(
            "pipeline" -> config.name,
            "publisherToken" -> jobContext.pipelineToken,
            "pipelineToken" -> jobContext.pipelineToken,
            "destination" -> "pgvector",
            "schema" -> schemaName,
            "table" -> pgvectorConfig.tableName
        )
        val gson = new Gson()
        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, gson.toJson(notification.asJava), attributes)
    }
}
