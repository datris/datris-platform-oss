package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{ChunkingConfig, DatrisEnvironment, DatrisException, JobContext}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.collection.JavaConverters._

object VectorLoaderBase {

    /** One persisted row: deterministic id + chunk index + fitted text + vector.
      * The id seed is (pipelineToken + "_" + chunkIndex) so re-ingesting a document
      * upserts in place instead of duplicating.
      */
    case class EmbeddedRow(id: UUID, chunkIndex: Int, text: String, embedding: Array[Float])
}

/** Template-method base for the vector-store document loaders (Qdrant, Weaviate,
  * Milvus, Chroma). `process()` owns the shared skeleton — status lifecycle,
  * unstructured-data guard, text extraction, chunking, multi-tenant secret
  * resolution, embedding, batch-of-100 upsert loop with the deterministic PK
  * scheme, and the completion notification. Subclasses supply the destination
  * client lifecycle and the batch marshalling.
  *
  * PGVectorLoader intentionally does NOT extend this class: its transaction
  * semantics (single connection-wide transaction with rollback), JDBC secret
  * shape, and schema-qualified notifications diverge from the client/collection
  * model shared by the other four.
  *
  * The `resolve*`/`embed*` hooks default to the production singletons and exist
  * so tests can substitute fakes without Vault or an embeddings API.
  */
abstract class VectorLoaderBase(jobContext: JobContext) {
    import VectorLoaderBase.EmbeddedRow

    protected val logger: Logger = LoggerFactory.getLogger(getClass)
    protected val config = jobContext.config
    protected val statusUtil = jobContext.statusUtil
    protected val UPSERT_BATCH_SIZE = 100

    /** Destination-specific connection handle (client, session, …). */
    type Client

    /** Lowercase destination key used in notifications ("qdrant", "chroma", …). */
    protected def destinationType: String

    /** Display name used in error messages ("Qdrant secret not found: …"). */
    protected def secretDisplayName: String

    /** What the destination calls its container — "collection" everywhere except
      * Weaviate's "class". Used in the end-of-run status message.
      */
    protected def containerLabel: String = "collection"

    /** Collection/class name at the destination. */
    protected def collectionName: String

    /** Chunking config from the pipeline config; may be null (defaults applied). */
    protected def configuredChunking: ChunkingConfig

    protected def embeddingSecretNameFromConfig: String
    protected def destinationSecretNameFromConfig: String

    /** Multi-tenant override for the destination secret name, or null. */
    protected def tenantSecretNameOverride: String

    /** Config-level secret name used in the 'host not found' style errors. */
    protected def guardMessage: String =
        secretDisplayName + " destination requires unstructured file data (PDF, DOC, DOCX, HTML, text). Use 'unstructuredAttributes' in the source configuration."

    protected def openClient(secret: java.util.Map[String, String]): Client
    protected def ensureCollection(client: Client, dimension: Int): Unit
    protected def upsertBatch(client: Client, rows: List[EmbeddedRow], filename: String): Unit
    protected def closeClient(client: Client): Unit

    // ---- test seams: default to the production singletons ----
    protected def tenantEmbeddingSecretName: String = DatrisEnvironment.current.embeddingSecretName
    protected def resolveEmbeddingConfig(secretName: String): EmbeddingUtil.EmbeddingConfig = EmbeddingUtil.getConfig(secretName)
    protected def fetchDestinationSecret(name: String): Option[java.util.Map[String, String]] = SecretsUtil.getSecretMap(name)
    protected def embed(batch: List[String], embeddingConfig: EmbeddingUtil.EmbeddingConfig): List[EmbeddingUtil.EmbeddedChunk] =
        EmbeddingUtil.generateEmbeddings(batch, embeddingConfig)
    protected def embeddingDimension(embeddingConfig: EmbeddingUtil.EmbeddingConfig): Int =
        EmbeddingUtil.embeddingDimension(embeddingConfig)

    final def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Process started")

        if (jobContext.data.rawBytes == null)
            throw new DatrisException(guardMessage)

        // Extract text from the document
        val filename = if (jobContext.metadata != null) jobContext.metadata.dataFileName else ""
        val documentText = TextExtractorUtil.extractText(jobContext.data.rawBytes, filename)
        if (documentText.isEmpty)
            throw new DatrisException("No text could be extracted from the uploaded file: " + filename)

        statusUtil.info("processing", "Extracted " + documentText.length + " characters from: " + filename)

        // Chunk the document
        val chunkingConfig = if (configuredChunking != null) configuredChunking else new ChunkingConfig()
        val chunks = ChunkUtil.chunk(documentText, chunkingConfig)
        statusUtil.info("processing", "Chunked into " + chunks.size + " chunks using strategy: " + chunkingConfig.strategy)

        // Get configs — use tenant secret names if in multi-tenant mode
        val embeddingSecretName =
            if (tenantEmbeddingSecretName != null) tenantEmbeddingSecretName else embeddingSecretNameFromConfig
        val destinationSecretName =
            if (tenantSecretNameOverride != null) tenantSecretNameOverride else destinationSecretNameFromConfig
        val embeddingConfig = resolveEmbeddingConfig(embeddingSecretName)
        val destinationSecret = fetchDestinationSecret(destinationSecretName)
            .getOrElse(throw new DatrisException(secretDisplayName + " secret not found: " + destinationSecretName))

        val client = openClient(destinationSecret)
        try {
            val dimension = embeddingDimension(embeddingConfig)
            ensureCollection(client, dimension)

            // Batch: embed + upsert. globalChunkIdx is the row's chunk_index AND
            // part of the deterministic PK seed; it advances per fitted chunk
            // because TokenGuard's split mode can fan one input chunk into N.
            var totalUpserted = 0
            var globalChunkIdx = 0
            chunks.grouped(UPSERT_BATCH_SIZE).foreach { batch =>
                val embedded = embed(batch, embeddingConfig)

                val rows = embedded.map { case EmbeddingUtil.EmbeddedChunk(chunkText, embedding) =>
                    val row = EmbeddedRow(
                        UUID.nameUUIDFromBytes((jobContext.pipelineToken + "_" + globalChunkIdx).getBytes),
                        globalChunkIdx,
                        chunkText,
                        embedding
                    )
                    globalChunkIdx += 1
                    row
                }

                upsertBatch(client, rows, filename)
                totalUpserted += embedded.size
                statusUtil.info("processing", "Upserted " + totalUpserted + " chunks (input chunks: " + chunks.size + ")")
            }

            sendNotification()
            statusUtil.info("end", "Process completed, " + totalUpserted + " chunks upserted to " + containerLabel + ": " + collectionName)
        } finally {
            closeClient(client)
        }
    }

    private def sendNotification(): Unit = {
        val attributes = Map(
            "database" -> "",
            "schema" -> "",
            "pipeline" -> config.name,
            "destination" -> destinationType,
            "table" -> collectionName
        )
        val notification = Map(
            "pipeline" -> config.name,
            "publisherToken" -> jobContext.pipelineToken,
            "pipelineToken" -> jobContext.pipelineToken,
            "destination" -> destinationType,
            "collection" -> collectionName
        )
        val gson = new Gson()
        publishNotification(gson.toJson(notification.asJava), attributes)
    }

    /** Test seam: production publishes to the pipeline topic. */
    protected def publishNotification(notificationJson: String, attributes: Map[String, String]): Unit =
        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, notificationJson, attributes)
}
