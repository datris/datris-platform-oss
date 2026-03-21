package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import io.weaviate.client.{Config => WeaviateClientConfig, WeaviateClient}
import io.weaviate.client.v1.schema.model.{DataType, Property, WeaviateClass}
import ai.datris.model.{JobContext, DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.collection.JavaConverters._

class WeaviateLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil
    private val weaviateConfig = config.destination.weaviate
    private val UPSERT_BATCH_SIZE = 100

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Process started")

        if (jobContext.data.rawBytes == null)
            throw new DatrisException("Weaviate destination requires unstructured file data (PDF, DOC, DOCX, HTML, text). Use 'unstructuredAttributes' in the source configuration.")

        // Extract text from the document
        val filename = if (jobContext.metadata != null) jobContext.metadata.dataFileName else ""
        val documentText = TextExtractorUtil.extractText(jobContext.data.rawBytes, filename)
        if (documentText.isEmpty)
            throw new DatrisException("No text could be extracted from the uploaded file: " + filename)

        statusUtil.info("processing", "Extracted " + documentText.length + " characters from: " + filename)

        // Chunk the document
        val chunkingConfig = if (weaviateConfig.chunking != null) weaviateConfig.chunking
            else new ai.datris.model.ChunkingConfig()
        val chunks = ChunkUtil.chunk(documentText, chunkingConfig)
        statusUtil.info("processing", "Chunked into " + chunks.size + " chunks using strategy: " + chunkingConfig.strategy)

        // Get configs
        val embeddingConfig = EmbeddingUtil.getConfig(weaviateConfig.embeddingSecretName)
        val weaviateSecret = SecretsUtil.getSecretMap(weaviateConfig.weaviateSecretName)
            .getOrElse(throw new DatrisException("Weaviate secret not found: " + weaviateConfig.weaviateSecretName))
        val host = weaviateSecret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Weaviate secret: " + weaviateConfig.weaviateSecretName)
        val port = Option(weaviateSecret.get("port")).getOrElse("8079")
        val scheme = Option(weaviateSecret.get("scheme")).getOrElse("http")
        val apiKey = Option(weaviateSecret.get("apiKey")).getOrElse("")

        statusUtil.info("processing", "Connecting to Weaviate at " + scheme + "://" + host + ":" + port)

        val clientConfig = new WeaviateClientConfig(scheme, host + ":" + port)
        val client = {
            if (apiKey.nonEmpty)
                io.weaviate.client.WeaviateAuthClient.apiKey(clientConfig, apiKey)
            else
                new WeaviateClient(clientConfig)
        }

        // Ensure class exists
        val dimension = EmbeddingUtil.embeddingDimension(embeddingConfig)
        ensureClass(client, weaviateConfig.className, dimension)

        // Batch: embed + upsert
        var totalUpserted = 0
        chunks.zipWithIndex.grouped(UPSERT_BATCH_SIZE).foreach { batch =>
            val texts = batch.map(_._1)
            val embeddings = EmbeddingUtil.generateEmbeddings(texts, embeddingConfig)

            val batcher = client.batch().objectsBatcher()

            batch.zip(embeddings).foreach { case ((chunkText, chunkIdx), embedding) =>
                val objectId = UUID.nameUUIDFromBytes(
                    (jobContext.pipelineToken + "_" + chunkIdx).getBytes
                ).toString

                val properties = new java.util.HashMap[String, AnyRef]()
                properties.put("text", chunkText)
                properties.put("chunk_index", Integer.valueOf(chunkIdx))
                properties.put("source_dataset", config.name)
                properties.put("filename", filename)

                // Static metadata from config
                if (weaviateConfig.metadata != null) {
                    weaviateConfig.metadata.asScala.foreach { case (key, v) =>
                        if (v != null) properties.put(key, v)
                    }
                }

                val weaviateObject = io.weaviate.client.v1.data.model.WeaviateObject.builder()
                    .className(weaviateConfig.className)
                    .id(objectId)
                    .properties(properties)
                    .vector(embedding.map(java.lang.Float.valueOf))
                    .build()

                batcher.withObject(weaviateObject)
            }

            val result = batcher.run()
            if (result.hasErrors)
                throw new DatrisException("Weaviate batch upsert failed: " + result.getError.getMessages.toString)

            totalUpserted += batch.size
            statusUtil.info("processing", "Upserted " + totalUpserted + " of " + chunks.size + " chunks")
        }

        sendNotification()
        statusUtil.info("end", "Process completed, " + totalUpserted + " chunks upserted to class: " + weaviateConfig.className)
    }

    private def ensureClass(client: WeaviateClient, className: String, dimension: Int): Unit = {
        val schemaResult = client.schema().classGetter().withClassName(className).run()
        if (!schemaResult.hasErrors && schemaResult.getResult != null) {
            return
        }

        statusUtil.info("processing", "Creating Weaviate class: " + className + " with dimension: " + dimension)

        val properties = new java.util.ArrayList[Property]()
        properties.add(Property.builder().name("text").dataType(java.util.Arrays.asList(DataType.TEXT)).build())
        properties.add(Property.builder().name("chunk_index").dataType(java.util.Arrays.asList(DataType.INT)).build())
        properties.add(Property.builder().name("source_dataset").dataType(java.util.Arrays.asList(DataType.TEXT)).build())
        properties.add(Property.builder().name("filename").dataType(java.util.Arrays.asList(DataType.TEXT)).build())

        // Add metadata field properties dynamically
        if (weaviateConfig.metadata != null) {
            weaviateConfig.metadata.asScala.keys.foreach { key =>
                properties.add(Property.builder().name(key).dataType(java.util.Arrays.asList(DataType.TEXT)).build())
            }
        }

        val weaviateClass = WeaviateClass.builder()
            .className(className)
            .vectorIndexType("hnsw")
            .properties(properties)
            .build()

        val createResult = client.schema().classCreator().withClass(weaviateClass).run()
        if (createResult.hasErrors)
            throw new DatrisException("Failed to create Weaviate class: " + createResult.getError.getMessages.toString)
    }

    private def sendNotification(): Unit = {
        val attributes = Map(
            "database" -> "",
            "schema" -> "",
            "dataset" -> config.name,
            "destination" -> "weaviate",
            "table" -> weaviateConfig.className
        )
        val notification = Map(
            "dataset" -> config.name,
            "publisherToken" -> jobContext.pipelineToken,
            "pipelineToken" -> jobContext.pipelineToken,
            "destination" -> "weaviate",
            "collection" -> weaviateConfig.className
        )
        val gson = new Gson()
        NotificationUtil.add(DatrisEnvironment.values.datasetTopic, gson.toJson(notification.asJava), attributes)
    }
}
