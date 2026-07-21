package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import ai.datris.model.{ChunkingConfig, DatrisEnvironment, DatrisException, JobContext}
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils

import scala.collection.JavaConverters._

object ChromaLoader {

    /** Chroma is driven over raw HTTP; the "client" is the pooled HTTP client plus
      * the collections endpoint and the collection id resolved by ensureCollection.
      */
    final class ChromaSession(val http: CloseableHttpClient, val collectionsPath: String) {
        var collectionId: String = _
    }
}

class ChromaLoader(jobContext: JobContext) extends VectorLoaderBase(jobContext) {
    import ChromaLoader.ChromaSession
    import VectorLoaderBase.EmbeddedRow

    private val chromaConfig = config.destination.chroma

    override type Client = ChromaSession

    override protected def destinationType: String = "chroma"
    override protected def secretDisplayName: String = "Chroma"
    override protected def collectionName: String = chromaConfig.collectionName
    override protected def configuredChunking: ChunkingConfig = chromaConfig.chunking
    override protected def embeddingSecretNameFromConfig: String = chromaConfig.embeddingSecretName
    override protected def destinationSecretNameFromConfig: String = chromaConfig.chromaSecretName
    override protected def tenantSecretNameOverride: String = DatrisEnvironment.current.chromaSecretName

    override protected def openClient(secret: java.util.Map[String, String]): ChromaSession = {
        val host = secret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Chroma secret: " + chromaConfig.chromaSecretName)
        val port = Option(secret.get("port")).getOrElse("8000")
        val baseUrl = "http://" + host + ":" + port

        val collectionsPath = baseUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections"
        statusUtil.info("processing", "Connecting to Chroma at " + baseUrl)

        new ChromaSession(HttpClients.createDefault(), collectionsPath)
    }

    override protected def closeClient(client: ChromaSession): Unit = client.http.close()

    override protected def ensureCollection(client: ChromaSession, dimension: Int): Unit = {
        client.collectionId = resolveOrCreateCollection(client, chromaConfig.collectionName)

        // Verify embedding dim matches any vectors already in the collection.
        // Chroma doesn't expose a fixed dim in metadata — it's inferred from
        // the first upsert. Probe one object and compare. Empty collection =>
        // skip (first write will set the dim naturally).
        verifyCollectionDimension(client, chromaConfig.collectionName, dimension)
    }

    override protected def upsertBatch(client: ChromaSession, rows: List[EmbeddedRow], filename: String): Unit = {
        val idsArray = new JsonArray()
        val embeddingsArray = new JsonArray()
        val documentsArray = new JsonArray()
        val metadatasArray = new JsonArray()

        rows.foreach { row =>
            idsArray.add(row.id.toString)
            documentsArray.add(row.text)

            // Embedding array
            val embArray = new JsonArray()
            row.embedding.foreach(v => embArray.add(v))
            embeddingsArray.add(embArray)

            // Metadata
            val meta = new JsonObject()
            meta.addProperty("chunk_index", row.chunkIndex)
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

        val post = new HttpPost(client.collectionsPath + "/" + client.collectionId + "/upsert")
        post.setHeader("Content-Type", "application/json")
        post.setEntity(new StringEntity(payload.toString, "UTF-8"))

        val response = client.http.execute(post)
        try {
            val statusCode = response.getStatusLine.getStatusCode
            if (statusCode < 200 || statusCode >= 300) {
                val body = EntityUtils.toString(response.getEntity)
                throw new DatrisException("Chroma upsert failed (HTTP " + statusCode + "): " + body)
            }
        } finally {
            response.close()
        }
    }

    private def resolveOrCreateCollection(client: ChromaSession, collectionName: String): String = {
        // Try to get collection first
        getCollectionId(client, collectionName).foreach(id => return id)

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

        val post = new HttpPost(client.collectionsPath)
        post.setHeader("Content-Type", "application/json")
        post.setEntity(new StringEntity(payload.toString, "UTF-8"))

        val response = client.http.execute(post)
        try {
            val statusCode = response.getStatusLine.getStatusCode
            val body = EntityUtils.toString(response.getEntity)
            if (statusCode >= 200 && statusCode < 300) {
                val json = JsonParser.parseString(body).getAsJsonObject
                return json.get("id").getAsString
            }
            // Create failed — another runner may have raced us in. Re-check before erroring.
            getCollectionId(client, collectionName) match {
                case Some(id) => id
                case None => throw new DatrisException("Failed to create Chroma collection: " + body)
            }
        } finally {
            response.close()
        }
    }

    private def verifyCollectionDimension(client: ChromaSession, collectionName: String, dimension: Int): Unit = {
        val payload = new JsonObject()
        payload.addProperty("limit", 1)
        val include = new JsonArray()
        include.add("embeddings")
        payload.add("include", include)

        val post = new HttpPost(client.collectionsPath + "/" + client.collectionId + "/get")
        post.setHeader("Content-Type", "application/json")
        post.setEntity(new StringEntity(payload.toString, "UTF-8"))
        val response =
            try client.http.execute(post)
            catch {
                case e: Exception =>
                    logger.debug("Chroma dimension-probe request failed for collection \"" + collectionName + "\" — skipping dimension verification", e)
                    return
            }
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

    private def getCollectionId(client: ChromaSession, collectionName: String): Option[String] = {
        val get = new HttpGet(client.collectionsPath + "/" + collectionName)
        val response = client.http.execute(get)
        try {
            if (response.getStatusLine.getStatusCode == 200) {
                val body = EntityUtils.toString(response.getEntity)
                val json = JsonParser.parseString(body).getAsJsonObject
                Some(json.get("id").getAsString)
            } else None
        } catch {
            case e: Exception =>
                logger.warn("Failed to parse Chroma collection lookup response for \"" + collectionName + "\" — treating collection as absent", e)
                None
        } finally { response.close() }
    }
}
