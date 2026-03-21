package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{Gson, JsonArray, JsonObject}
import ai.datris.model.DatrisException
import org.apache.http.HttpHeaders
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

object EmbeddingUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val BATCH_SIZE = 100

    case class EmbeddingConfig(endpoint: String, model: String, apiKey: String)

    def getConfig(secretName: String): EmbeddingConfig = {
        val secret = SecretsUtil.getSecretMap(secretName)
            .getOrElse(throw new DatrisException("Embedding secret not found: " + secretName))
        val endpoint = secret.get("endpoint")
        if (endpoint == null) throw new DatrisException("'endpoint' not found in embedding secret: " + secretName)
        val model = secret.get("model")
        if (model == null) throw new DatrisException("'model' not found in embedding secret: " + secretName)
        val apiKey = Option(secret.get("apiKey")).getOrElse("")
        EmbeddingConfig(endpoint, model, apiKey)
    }

    def generateEmbeddings(texts: List[String], config: EmbeddingConfig): List[Array[Float]] = {
        logger.info("Generating embeddings for " + texts.size + " texts using model: " + config.model)

        texts.grouped(BATCH_SIZE).flatMap { batch =>
            callEmbeddingAPI(batch, config)
        }.toList
    }

    def embeddingDimension(config: EmbeddingConfig): Int = {
        val testEmbedding = callEmbeddingAPI(List("test"), config)
        testEmbedding.head.length
    }

    private def callEmbeddingAPI(texts: List[String], config: EmbeddingConfig): List[Array[Float]] = {
        val gson = new Gson()

        val inputArray = new JsonArray()
        texts.foreach(t => inputArray.add(t))

        val requestObj = new JsonObject()
        requestObj.addProperty("model", config.model)
        requestObj.add("input", inputArray)

        val jsonBody = requestObj.toString
        val client = HttpClients.createDefault()

        try {
            val httpPost = new HttpPost(config.endpoint)
            if (config.apiKey.nonEmpty)
                httpPost.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey)
            httpPost.addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8))

            val response = client.execute(httpPost)
            val statusCode = response.getStatusLine.getStatusCode
            if (statusCode != 200) {
                val body = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
                throw new DatrisException("Embedding API returned error status: " + statusCode + ", body: " + body)
            }

            val responseBody = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
            parseEmbeddingResponse(responseBody)
        } finally {
            client.close()
        }
    }

    private def parseEmbeddingResponse(responseBody: String): List[Array[Float]] = {
        val gson = new Gson()
        val responseMap = gson.fromJson(responseBody, classOf[java.util.Map[String, Any]])
        val data = responseMap.get("data").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (data == null || data.isEmpty)
            throw new DatrisException("Embedding API response contained no data")

        data.asScala.map { item =>
            val embedding = item.get("embedding").asInstanceOf[java.util.List[Any]]
            val values = embedding.asScala.map { v =>
                val f: Float = v match {
                    case d: java.lang.Double => d.floatValue()
                    case f: java.lang.Float => f.floatValue()
                    case other => throw new DatrisException("Unexpected embedding value type: " + other.getClass)
                }
                f
            }
            values.toArray
        }.toList
    }
}
