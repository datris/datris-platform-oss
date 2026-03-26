package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import io.milvus.v2.client.{ConnectConfig, MilvusClientV2}
import io.milvus.v2.service.vector.request.SearchReq
import io.milvus.v2.service.vector.request.data.FloatVec
import ai.datris.model.DatrisException
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

object MilvusSearchUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def search(query: String, collection: String, embeddingSecretName: String,
               milvusSecretName: String, topK: Int = 5): java.util.List[java.util.Map[String, Any]] = {

        if (query == null || query.trim.isEmpty)
            throw new DatrisException("Search query cannot be empty")

        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val queryEmbedding = EmbeddingUtil.generateEmbeddings(List(query), embeddingConfig).head

        val milvusSecret = SecretsUtil.getSecretMap(milvusSecretName)
            .getOrElse(throw new DatrisException("Milvus secret not found: " + milvusSecretName))
        val host = milvusSecret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Milvus secret: " + milvusSecretName)
        val port = Option(milvusSecret.get("port")).getOrElse("19530")
        val apiKey = Option(milvusSecret.get("apiKey")).getOrElse("")

        logger.info("Searching Milvus collection: " + collection + " at " + host + ":" + port)

        val connectBuilder = ConnectConfig.builder().uri("http://" + host + ":" + port)
        if (apiKey.nonEmpty) connectBuilder.token(apiKey)
        val client = new MilvusClientV2(connectBuilder.build())

        try {
            val floatList = queryEmbedding.map(Float.box).toList.asJava
            val floatVec: io.milvus.v2.service.vector.request.data.BaseVector = new FloatVec(floatList)
            val vectorData: java.util.List[io.milvus.v2.service.vector.request.data.BaseVector] =
                java.util.Collections.singletonList(floatVec)
            val outputFields = java.util.Arrays.asList("text", "chunk_index", "source_pipeline", "filename")

            val searchReq = SearchReq.builder()
                .collectionName(collection)
                .data(vectorData)
                .topK(topK)
                .outputFields(outputFields)
                .build()

            val searchResp = client.search(searchReq)
            val results = new java.util.ArrayList[java.util.Map[String, Any]]()

            searchResp.getSearchResults.asScala.foreach { resultList =>
                resultList.asScala.foreach { hit =>
                    val row = new java.util.LinkedHashMap[String, Any]()
                    val entity = hit.getEntity
                    entity.keySet().asScala.foreach { key =>
                        row.put(key, entity.get(key))
                    }
                    row.put("_score", hit.getScore.toDouble)
                    results.add(row)
                }
            }

            logger.info("Milvus search returned " + results.size() + " results")
            results
        } finally {
            client.close()
        }
    }
}
