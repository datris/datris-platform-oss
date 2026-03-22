package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.model.DatrisException
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

object ChromaSearchUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def search(query: String, collection: String, embeddingSecretName: String,
               chromaSecretName: String, topK: Int = 5): java.util.List[java.util.Map[String, Any]] = {

        if (query == null || query.trim.isEmpty)
            throw new DatrisException("Search query cannot be empty")

        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val queryEmbedding = EmbeddingUtil.generateEmbeddings(List(query), embeddingConfig).head

        val chromaSecret = SecretsUtil.getSecretMap(chromaSecretName)
            .getOrElse(throw new DatrisException("Chroma secret not found: " + chromaSecretName))
        val host = chromaSecret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Chroma secret: " + chromaSecretName)
        val port = Option(chromaSecret.get("port")).getOrElse("8000")
        val baseUrl = "http://" + host + ":" + port
        val collectionsPath = baseUrl + "/api/v2/tenants/default_tenant/databases/default_database/collections"

        logger.info("Searching Chroma collection: " + collection + " at " + baseUrl)

        val httpClient = HttpClients.createDefault()

        try {
            // Get collection ID
            val get = new HttpGet(collectionsPath + "/" + collection)
            val getResponse = httpClient.execute(get)
            val collectionId = try {
                val statusCode = getResponse.getStatusLine.getStatusCode
                if (statusCode != 200)
                    throw new DatrisException("Chroma collection not found: " + collection)
                val body = EntityUtils.toString(getResponse.getEntity)
                JsonParser.parseString(body).getAsJsonObject.get("id").getAsString
            } finally {
                getResponse.close()
            }

            // Build query request
            val embeddingsArray = new JsonArray()
            val embArray = new JsonArray()
            queryEmbedding.foreach(v => embArray.add(v))
            embeddingsArray.add(embArray)

            val payload = new JsonObject()
            payload.add("query_embeddings", embeddingsArray)
            payload.addProperty("n_results", topK)
            val includeArray = new JsonArray()
            includeArray.add("documents")
            includeArray.add("metadatas")
            includeArray.add("distances")
            payload.add("include", includeArray)

            val post = new HttpPost(collectionsPath + "/" + collectionId + "/query")
            post.setHeader("Content-Type", "application/json")
            post.setEntity(new StringEntity(payload.toString, "UTF-8"))

            val response = httpClient.execute(post)
            try {
                val statusCode = response.getStatusLine.getStatusCode
                val body = EntityUtils.toString(response.getEntity)
                if (statusCode < 200 || statusCode >= 300)
                    throw new DatrisException("Chroma query failed (HTTP " + statusCode + "): " + body)

                val json = JsonParser.parseString(body).getAsJsonObject
                val results = new java.util.ArrayList[java.util.Map[String, Any]]()

                val documents = json.getAsJsonArray("documents")
                val metadatas = json.getAsJsonArray("metadatas")
                val distances = json.getAsJsonArray("distances")

                if (documents != null && documents.size() > 0) {
                    val docs = documents.get(0).getAsJsonArray
                    val metas = if (metadatas != null && metadatas.size() > 0) metadatas.get(0).getAsJsonArray else null
                    val dists = if (distances != null && distances.size() > 0) distances.get(0).getAsJsonArray else null

                    for (i <- 0 until docs.size()) {
                        val row = new java.util.LinkedHashMap[String, Any]()
                        row.put("text", docs.get(i).getAsString)

                        if (metas != null && i < metas.size()) {
                            val meta = metas.get(i).getAsJsonObject
                            meta.entrySet().asScala.foreach { entry =>
                                val value = entry.getValue
                                if (value.isJsonPrimitive) {
                                    val prim = value.getAsJsonPrimitive
                                    if (prim.isNumber) row.put(entry.getKey, prim.getAsDouble)
                                    else if (prim.isBoolean) row.put(entry.getKey, prim.getAsBoolean)
                                    else row.put(entry.getKey, prim.getAsString)
                                }
                            }
                        }

                        if (dists != null && i < dists.size()) {
                            val distance = dists.get(i).getAsDouble
                            row.put("_score", 1.0 - distance)
                        }

                        results.add(row)
                    }
                }

                logger.info("Chroma search returned " + results.size() + " results")
                results
            } finally {
                response.close()
            }
        } finally {
            httpClient.close()
        }
    }
}
