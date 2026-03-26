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

        // Get configs
        val embeddingConfig = EmbeddingUtil.getConfig(chromaConfig.embeddingSecretName)
        val chromaSecret = SecretsUtil.getSecretMap(chromaConfig.chromaSecretName)
            .getOrElse(throw new DatrisException("Chroma secret not found: " + chromaConfig.chromaSecretName))
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

            // Batch: embed + upsert
            var totalUpserted = 0
            chunks.zipWithIndex.grouped(UPSERT_BATCH_SIZE).foreach { batch =>
                val texts = batch.map(_._1)
                val embeddings = EmbeddingUtil.generateEmbeddings(texts, embeddingConfig)

                val idsArray = new JsonArray()
                val embeddingsArray = new JsonArray()
                val documentsArray = new JsonArray()
                val metadatasArray = new JsonArray()

                batch.zip(embeddings).foreach { case ((chunkText, chunkIdx), embedding) =>
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

                totalUpserted += batch.size
                statusUtil.info("processing", "Upserted " + totalUpserted + " of " + chunks.size + " chunks")
            }

            sendNotification()
            statusUtil.info("end", "Process completed, " + totalUpserted + " chunks upserted to collection: " + chromaConfig.collectionName)
        } finally {
            client.close()
        }
    }

    private def ensureCollection(client: org.apache.http.impl.client.CloseableHttpClient, collectionsPath: String, collectionName: String): String = {
        // Try to get collection first
        val get = new HttpGet(collectionsPath + "/" + collectionName)
        val getResponse = client.execute(get)
        try {
            if (getResponse.getStatusLine.getStatusCode == 200) {
                val body = EntityUtils.toString(getResponse.getEntity)
                val json = JsonParser.parseString(body).getAsJsonObject
                return json.get("id").getAsString
            }
        } finally {
            getResponse.close()
        }

        // Create collection
        statusUtil.info("processing", "Creating Chroma collection: " + collectionName)
        val payload = new JsonObject()
        payload.addProperty("name", collectionName)

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
            if (statusCode < 200 || statusCode >= 300)
                throw new DatrisException("Failed to create Chroma collection: " + body)
            val json = JsonParser.parseString(body).getAsJsonObject
            json.get("id").getAsString
        } finally {
            response.close()
        }
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
        NotificationUtil.add(DatrisEnvironment.values.pipelineTopic, gson.toJson(notification.asJava), attributes)
    }
}
