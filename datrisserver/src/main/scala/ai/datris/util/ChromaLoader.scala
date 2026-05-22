package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.model.{JobContext, DatrisEnvironment, DatrisException}
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.collection.JavaConverters._

class ChromaLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil
    private val chromaConfig = config.destination.chroma
    private val UPSERT_BATCH_SIZE = 100

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Process started")

        if (jobContext.data.rawBytes == null)
            throw new DatrisException("Chroma destination requires unstructured file data (PDF, DOC, DOCX, HTML, text). Use 'unstructuredAttributes' in the source configuration.")

        // Extract text from the document
        val filename = if (jobContext.metadata != null) jobContext.metadata.dataFileName else ""
        val documentText = TextExtractorUtil.extractText(jobContext.data.rawBytes, filename)
        if (documentText.isEmpty)
            throw new DatrisException("No text could be extracted from the uploaded file: " + filename)

        statusUtil.info("processing", "Extracted " + documentText.length + " characters from: " + filename)

        // Chunk the document
        val chunkingConfig = if (chromaConfig.chunking != null) chromaConfig.chunking
            else new ai.datris.model.ChunkingConfig()
        val chunks = ChunkUtil.chunk(documentText, chunkingConfig)
        statusUtil.info("processing", "Chunked into " + chunks.size + " chunks using strategy: " + chunkingConfig.strategy)

        // Get configs — use tenant secret names if in multi-tenant mode
        val embeddingSecretName = if (DatrisEnvironment.current.embeddingSecretName != null) DatrisEnvironment.current.embeddingSecretName else chromaConfig.embeddingSecretName
        val chromaSecretName = if (DatrisEnvironment.current.chromaSecretName != null) DatrisEnvironment.current.chromaSecretName else chromaConfig.chromaSecretName
        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val chromaSecret = SecretsUtil.getSecretMap(chromaSecretName)
            .getOrElse(throw new DatrisException("Chroma secret not found: " + chromaSecretName))
        val host = chromaSecret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Chroma secret: " + chromaConfig.chromaSecretName)
        val port = Option(chromaSecret.get("port")).getOrElse("8000")
        val baseUrl = "http://" + host + ":" + port

        val collectionsPath = baseUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections"
        statusUtil.info("processing", "Connecting to Chroma at " + baseUrl)

        val client = HttpClients.createDefault()

        try {
            // Ensure collection exists
            val collectionId = ensureCollection(client, collectionsPath, chromaConfig.collectionName)

            // Verify embedding dim matches any vectors already in the collection.
            // Chroma doesn't expose a fixed dim in metadata — it's inferred from
            // the first upsert. Probe one object and compare. Empty collection =>
            // skip (first write will set the dim naturally).
            val dimension = EmbeddingUtil.embeddingDimension(embeddingConfig)
            verifyCollectionDimension(client, collectionsPath, collectionId, chromaConfig.collectionName, dimension)

            // Batch: embed + upsert. globalChunkIdx is the row's chunk_index AND
            // part of the deterministic PK seed; it advances per fitted chunk
            // because TokenGuard's split mode can fan one input chunk into N.
            var totalUpserted = 0
            var globalChunkIdx = 0
            chunks.grouped(UPSERT_BATCH_SIZE).foreach { batch =>
                val embedded = EmbeddingUtil.generateEmbeddings(batch, embeddingConfig)

                val idsArray = new JsonArray()
                val embeddingsArray = new JsonArray()
                val documentsArray = new JsonArray()
                val metadatasArray = new JsonArray()

                embedded.foreach { case EmbeddingUtil.EmbeddedChunk(chunkText, embedding) =>
                    val chunkIdx = globalChunkIdx
                    val objectId = UUID.nameUUIDFromBytes(
                        (jobContext.pipelineToken + "_" + chunkIdx).getBytes
                    ).toString

                    idsArray.add(objectId)
                    documentsArray.add(chunkText)

                    // Embedding array
                    val embArray = new JsonArray()
                    embedding.foreach(v => embArray.add(v))
                    embeddingsArray.add(embArray)

                    // Metadata
                    val meta = new JsonObject()
                    meta.addProperty("chunk_index", chunkIdx)
                    meta.addProperty("source_pipeline", config.name)
                    meta.addProperty("filename", filename)
                    if (chromaConfig.metadata != null) {
                        chromaConfig.metadata.asScala.foreach { case (key, v) =>
                            if (v != null) meta.addProperty(key, v)
                        }
                    }
                    metadatasArray.add(meta)
                    globalChunkIdx += 1
                }

                val payload = new JsonObject()
                payload.add("ids", idsArray)
                payload.add("embeddings", embeddingsArray)
                payload.add("documents", documentsArray)
                payload.add("metadatas", metadatasArray)

                val post = new HttpPost(collectionsPath + "/" + collectionId + "/upsert")
                post.setHeader("Content-Type", "application/json")
                post.setEntity(new StringEntity(payload.toString, "UTF-8"))

                val response = client.execute(post)
                try {
                    val statusCode = response.getStatusLine.getStatusCode
                    if (statusCode < 200 || statusCode >= 300) {
                        val body = EntityUtils.toString(response.getEntity)
                        throw new DatrisException("Chroma upsert failed (HTTP " + statusCode + "): " + body)
                    }
                } finally {
                    response.close()
                }

                totalUpserted += embedded.size
                statusUtil.info("processing", "Upserted " + totalUpserted + " chunks (input chunks: " + chunks.size + ")")
            }

            sendNotification()
            statusUtil.info("end", "Process completed, " + totalUpserted + " chunks upserted to collection: " + chromaConfig.collectionName)
        } finally {
            client.close()
        }
    }

    private def ensureCollection(client: org.apache.http.impl.client.CloseableHttpClient, collectionsPath: String, collectionName: String): String = {
        // Try to get collection first
        getCollectionId(client, collectionsPath, collectionName).foreach(id => return id)

        // Create collection. Use get_or_create=true so Chroma servers that support
        // the flag return the existing collection's id when another concurrent
        // JobRunner (document taps fire many docs in parallel) already created it.
        // Older Chroma servers ignore the flag, so we also fall back to a re-GET
        // on any non-2xx response to cover the race.
        statusUtil.info("processing", "Ensuring Chroma collection: " + collectionName)
        val payload = new JsonObject()
        payload.addProperty("name", collectionName)
        payload.addProperty("get_or_create", true)

        val metadataObj = new JsonObject()
        metadataObj.addProperty("hnsw:space", "cosine")
        payload.add("metadata", metadataObj)

        val post = new HttpPost(collectionsPath)
        post.setHeader("Content-Type", "application/json")
        post.setEntity(new StringEntity(payload.toString, "UTF-8"))

        val response = client.execute(post)
        try {
            val statusCode = response.getStatusLine.getStatusCode
            val body = EntityUtils.toString(response.getEntity)
            if (statusCode >= 200 && statusCode < 300) {
                val json = JsonParser.parseString(body).getAsJsonObject
                return json.get("id").getAsString
            }
            // Create failed — another runner may have raced us in. Re-check before erroring.
            getCollectionId(client, collectionsPath, collectionName) match {
                case Some(id) => id
                case None     => throw new DatrisException("Failed to create Chroma collection: " + body)
            }
        } finally {
            response.close()
        }
    }

    private def verifyCollectionDimension(
        client: org.apache.http.impl.client.CloseableHttpClient,
        collectionsPath: String,
        collectionId: String,
        collectionName: String,
        dimension: Int
    ): Unit = {
        val payload = new JsonObject()
        payload.addProperty("limit", 1)
        val include = new JsonArray()
        include.add("embeddings")
        payload.add("include", include)

        val post = new HttpPost(collectionsPath + "/" + collectionId + "/get")
        post.setHeader("Content-Type", "application/json")
        post.setEntity(new StringEntity(payload.toString, "UTF-8"))
        val response = try client.execute(post) catch { case _: Exception => return }
        try {
            if (response.getStatusLine.getStatusCode != 200) return
            val body = EntityUtils.toString(response.getEntity)
            val json = JsonParser.parseString(body).getAsJsonObject
            val embeddings = Option(json.get("embeddings")).filter(_.isJsonArray).map(_.getAsJsonArray)
            embeddings.filter(_.size > 0).foreach { arr =>
                val first = arr.get(0)
                if (first.isJsonArray) {
                    val existing = first.getAsJsonArray.size
                    if (existing > 0 && existing != dimension) {
                        throw new DatrisException(
                            "Embedding dimension mismatch on collection \"" + collectionName +
                            "\": existing is vector(" + existing + "), configured embedding provider produces vector(" + dimension +
                            "). The stored vectors are incompatible with the new provider. Either drop collection \"" +
                            collectionName + "\" and re-ingest, or point this pipeline at a new collection."
                        )
                    }
                }
            }
        } finally {
            response.close()
        }
    }

    private def getCollectionId(client: org.apache.http.impl.client.CloseableHttpClient, collectionsPath: String, collectionName: String): Option[String] = {
        val get = new HttpGet(collectionsPath + "/" + collectionName)
        val response = client.execute(get)
        try {
            if (response.getStatusLine.getStatusCode == 200) {
                val body = EntityUtils.toString(response.getEntity)
                val json = JsonParser.parseString(body).getAsJsonObject
                Some(json.get("id").getAsString)
            } else None
        } catch { case _: Exception => None }
        finally { response.close() }
    }

    private def sendNotification(): Unit = {
        val attributes = Map(
            "database" -> "",
            "schema" -> "",
            "pipeline" -> config.name,
            "destination" -> "chroma",
            "table" -> chromaConfig.collectionName
        )
        val notification = Map(
            "pipeline" -> config.name,
            "publisherToken" -> jobContext.pipelineToken,
            "pipelineToken" -> jobContext.pipelineToken,
            "destination" -> "chroma",
            "collection" -> chromaConfig.collectionName
        )
        val gson = new Gson()
        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, gson.toJson(notification.asJava), attributes)
    }
}
