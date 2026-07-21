package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import io.weaviate.client.{Config => WeaviateClientConfig, WeaviateClient}
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument
import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

object WeaviateSearchUtil extends VectorSearchUtility {

    override def storeType: String = "weaviate"
    override def containerParam: String = "className"
    override def containerDefault: String = "Documents"
    override def tenantSecretName: String = DatrisEnvironment.current.weaviateSecretName

    override def searchStore(
        query: String,
        container: String,
        embeddingSecretName: String,
        secretName: String,
        topK: Int,
        requestBody: java.util.Map[String, Any]
    ): java.util.List[java.util.Map[String, Any]] =
        search(query, container, embeddingSecretName, secretName, topK)
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def search(
        query: String,
        className: String,
        embeddingSecretName: String,
        weaviateSecretName: String,
        topK: Int = 5
    ): java.util.List[java.util.Map[String, Any]] = {

        if (query == null || query.trim.isEmpty)
            throw new DatrisException("Search query cannot be empty")

        // Get embedding for the query
        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val queryEmbedding = EmbeddingUtil.generateVectors(List(query), embeddingConfig).head

        // Get Weaviate connection details
        val weaviateSecret = SecretsUtil.getSecretMap(weaviateSecretName)
            .getOrElse(throw new DatrisException("Weaviate secret not found: " + weaviateSecretName))
        val host = weaviateSecret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Weaviate secret: " + weaviateSecretName)
        val port = Option(weaviateSecret.get("port")).getOrElse("8079")
        val scheme = Option(weaviateSecret.get("scheme")).getOrElse("http")
        val apiKey = Option(weaviateSecret.get("apiKey")).getOrElse("")

        logger.info("Searching Weaviate class: " + className + " at " + scheme + "://" + host + ":" + port)

        val clientConfig = new WeaviateClientConfig(scheme, host + ":" + port)
        val client = {
            if (apiKey.nonEmpty)
                io.weaviate.client.WeaviateAuthClient.apiKey(clientConfig, apiKey)
            else
                new WeaviateClient(clientConfig)
        }

        // Build nearVector argument
        val vectorArray = queryEmbedding.map(Float.box)
        val nearVector = NearVectorArgument.builder()
            .vector(vectorArray)
            .build()

        // Get all property names from the class schema
        val schemaResult = client.schema().classGetter().withClassName(className).run()
        if (schemaResult.hasErrors)
            throw new DatrisException("Failed to get Weaviate class schema: " + schemaResult.getError.getMessages.asScala.mkString(", "))

        val propertyNames = schemaResult.getResult.getProperties.asScala
            .map(_.getName)
            .toArray

        // Execute the search
        import io.weaviate.client.v1.graphql.query.fields.{Field => WField}

        val distanceField = WField.builder().name("distance").build()
        val additionalField = WField.builder()
            .name("_additional")
            .fields(Array(distanceField): _*)
            .build()
        val allFields =
            propertyNames.map(name => WField.builder().name(name).build()) :+ additionalField

        val result = client.graphQL().get()
            .withClassName(className)
            .withFields(allFields: _*)
            .withNearVector(nearVector)
            .withLimit(topK)
            .run()

        if (result.hasErrors)
            throw new DatrisException("Weaviate search failed: " + result.getError.getMessages.asScala.mkString(", "))

        // Parse GraphQL response
        val gson = new com.google.gson.Gson()
        val data = result.getResult.getData
        val json = gson.toJsonTree(data).getAsJsonObject
        val getObj = json.getAsJsonObject("Get")
        val items = getObj.getAsJsonArray(className)

        val results = new java.util.ArrayList[java.util.Map[String, Any]]()
        if (items != null) {
            items.asScala.foreach { element =>
                val obj = element.getAsJsonObject
                val row = new java.util.LinkedHashMap[String, Any]()

                propertyNames.foreach { name =>
                    if (obj.has(name) && !obj.get(name).isJsonNull)
                        row.put(name, obj.get(name).getAsString)
                }

                // Extract distance and convert to similarity score
                if (obj.has("_additional")) {
                    val additional = obj.getAsJsonObject("_additional")
                    if (additional.has("distance")) {
                        val distance = additional.get("distance").getAsDouble
                        row.put("_score", 1.0 - distance)
                    }
                }

                results.add(row)
            }
        }

        logger.info("Weaviate search returned " + results.size() + " results")
        results
    }
}
