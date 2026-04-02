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
            throw new DatrisException("pgvector destination requires unstructured file data (PDF, DOC, DOCX, HTML, text). Use 'unstructuredAttributes' in the source configuration.")

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
        val embeddingSecretName = if (DatrisEnvironment.current.embeddingSecretName != null) DatrisEnvironment.current.embeddingSecretName else pgvectorConfig.embeddingSecretName
        val pgvectorSecretName = if (DatrisEnvironment.current.pgvectorSecretName != null) DatrisEnvironment.current.pgvectorSecretName else pgvectorConfig.postgresSecretName
        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val pgSecret = SecretsUtil.getSecretMap(pgvectorSecretName)
            .getOrElse(throw new DatrisException("PostgreSQL secret not found: " + pgvectorSecretName))
        val rawJdbcUrl = pgSecret.get("jdbcUrl")
        if (rawJdbcUrl == null) throw new DatrisException("'jdbcUrl' not found in pgvector secret: " + pgvectorConfig.postgresSecretName)
        // In multi-tenant mode, override the database in the JDBC URL to the tenant's isolated database
        val jdbcUrl = if (DatrisEnvironment.current.multiTenant) {
            rawJdbcUrl.replaceFirst("/[^/]*$", "/" + DatrisEnvironment.current.environment)
        } else rawJdbcUrl
        val username = Option(pgSecret.get("username")).getOrElse("postgres")
        val password = Option(pgSecret.get("password")).getOrElse("")

        // Connect
        Class.forName("org.postgresql.Driver")
        val props = new Properties()
        props.setProperty("user", username)
        props.setProperty("password", password)

        statusUtil.info("processing", "Connecting to PostgreSQL at " + jdbcUrl)
        val conn = DriverManager.getConnection(jdbcUrl, props)

        try {
            conn.setAutoCommit(false)

            // Ensure pgvector extension and table exist
            val dimension = EmbeddingUtil.embeddingDimension(embeddingConfig)
            val schemaName = Option(pgvectorConfig.schemaName).getOrElse("public")
            val tableName = pgvectorConfig.tableName
            ensureTable(conn, schemaName, tableName, dimension)

            // Build column lists for metadata
            val metadataKeys = if (pgvectorConfig.metadata != null) pgvectorConfig.metadata.asScala.keys.toList else List.empty

            // Batch: embed + upsert
            var totalUpserted = 0
            chunks.zipWithIndex.grouped(UPSERT_BATCH_SIZE).foreach { batch =>
                val texts = batch.map(_._1)
                val embeddings = EmbeddingUtil.generateEmbeddings(texts, embeddingConfig)

                val allColumns = List("id", "text", "chunk_index", "source_pipeline", "filename") ++ metadataKeys ++ List("embedding")
                val placeholders = allColumns.map(_ => "?").mkString(", ")
                val updateSet = allColumns.filter(_ != "id").map(c => "\"" + c + "\" = EXCLUDED.\"" + c + "\"").mkString(", ")

                val sql = s"""INSERT INTO "$schemaName"."$tableName" (${allColumns.map(c => "\"" + c + "\"").mkString(", ")})
                             |VALUES ($placeholders)
                             |ON CONFLICT (id) DO UPDATE SET $updateSet""".stripMargin

                val stmt = conn.prepareStatement(sql)

                batch.zip(embeddings).foreach { case ((chunkText, chunkIdx), embedding) =>
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
                }

                stmt.executeBatch()
                stmt.close()
                totalUpserted += batch.size
                statusUtil.info("processing", "Upserted " + totalUpserted + " of " + chunks.size + " chunks")
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
            // Enable pgvector extension
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector")

            // Create schema if needed
            stmt.execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"")

            // Build CREATE TABLE with metadata columns
            val metadataColumns = if (pgvectorConfig.metadata != null) {
                pgvectorConfig.metadata.asScala.keys.map(key => "\"" + key + "\" TEXT").mkString(", ", ", ", "")
            } else ""

            val createSql =
                s"""CREATE TABLE IF NOT EXISTS "$schemaName"."$tableName" (
                   |    id UUID PRIMARY KEY,
                   |    text TEXT,
                   |    chunk_index INTEGER,
                   |    source_pipeline TEXT,
                   |    filename TEXT$metadataColumns,
                   |    embedding vector($dimension)
                   |)""".stripMargin

            statusUtil.info("processing", "Ensuring table: " + schemaName + "." + tableName + " with vector dimension: " + dimension)
            stmt.execute(createSql)
            conn.commit()
        } finally {
            stmt.close()
        }
    }

    private def sendNotification(): Unit = {
        val schemaName = Option(pgvectorConfig.schemaName).getOrElse("public")
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
