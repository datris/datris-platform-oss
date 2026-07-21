package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Points.{QueryPoints, SearchPoints}
import ai.datris.model.DatrisException
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

object QdrantSearchUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def search(
        query: String,
        collection: String,
        embeddingSecretName: String,
        qdrantSecretName: String,
        topK: Int = 5
    ): java.util.List[java.util.Map[String, Any]] = {

        if (query == null || query.trim.isEmpty)
            throw new DatrisException("Search query cannot be empty")

        // Get embedding for the query
        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val queryEmbedding = EmbeddingUtil.generateVectors(List(query), embeddingConfig).head

        // Get Qdrant connection details
        val qdrantSecret = SecretsUtil.getSecretMap(qdrantSecretName)
            .getOrElse(throw new DatrisException("Qdrant secret not found: " + qdrantSecretName))
        val host = qdrantSecret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Qdrant secret: " + qdrantSecretName)
        val port = Option(qdrantSecret.get("port")).map(_.toInt).getOrElse(6334)
        val apiKey = Option(qdrantSecret.get("apiKey")).getOrElse("")

        logger.info("Searching Qdrant collection: " + collection + " at " + host + ":" + port)

        val grpcClientBuilder = QdrantGrpcClient.newBuilder(host, port, false)
        if (apiKey.nonEmpty) grpcClientBuilder.withApiKey(apiKey)
        val client = new QdrantClient(grpcClientBuilder.build())

        try {
            val vectorList = queryEmbedding.map(Float.box).toList.asJava
            val searchResult = client.searchAsync(
                SearchPoints.newBuilder()
                    .setCollectionName(collection)
                    .addAllVector(vectorList.asInstanceOf[java.util.List[java.lang.Float]])
                    .setLimit(topK)
                    .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                    .build()
            ).get()

            val results = searchResult.asScala.map { point =>
                val row = new java.util.LinkedHashMap[String, Any]()
                point.getPayloadMap.asScala.foreach { case (key, value) =>
                    row.put(key, extractQdrantValue(value))
                }
                row.put("_score", point.getScore.toDouble)
                row.asInstanceOf[java.util.Map[String, Any]]
            }.asJava

            logger.info("Qdrant search returned " + results.size() + " results")
            results
        } finally {
            client.close()
        }
    }

    private def extractQdrantValue(value: io.qdrant.client.grpc.JsonWithInt.Value): Any = {
        import io.qdrant.client.grpc.JsonWithInt.Value.KindCase
        value.getKindCase match {
            case KindCase.STRING_VALUE => value.getStringValue
            case KindCase.INTEGER_VALUE => value.getIntegerValue
            case KindCase.DOUBLE_VALUE => value.getDoubleValue
            case KindCase.BOOL_VALUE => value.getBoolValue
            case _ => value.toString
        }
    }
}
