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
            throw new DatrisException(
                "Weaviate destination requires unstructured file data (PDF, DOC, DOCX, HTML, text). Use 'unstructuredAttributes' in the source configuration."
            )

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

        // Get configs — use tenant secret names if in multi-tenant mode
        val embeddingSecretName =
            if (DatrisEnvironment.current.embeddingSecretName != null) DatrisEnvironment.current.embeddingSecretName else weaviateConfig.embeddingSecretName
        val weaviateSecretName =
            if (DatrisEnvironment.current.weaviateSecretName != null) DatrisEnvironment.current.weaviateSecretName else weaviateConfig.weaviateSecretName
        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val weaviateSecret = SecretsUtil.getSecretMap(weaviateSecretName)
            .getOrElse(throw new DatrisException("Weaviate secret not found: " + weaviateSecretName))
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

        // Batch: embed + upsert. globalChunkIdx is the row's chunk_index AND
        // part of the deterministic PK seed; it advances per fitted chunk
        // because TokenGuard's split mode can fan one input chunk into N.
        var totalUpserted = 0
        var globalChunkIdx = 0
        chunks.grouped(UPSERT_BATCH_SIZE).foreach { batch =>
            val embedded = EmbeddingUtil.generateEmbeddings(batch, embeddingConfig)

            val batcher = client.batch().objectsBatcher()

            embedded.foreach { case EmbeddingUtil.EmbeddedChunk(chunkText, embedding) =>
                val chunkIdx = globalChunkIdx
                val objectId = UUID.nameUUIDFromBytes(
                    (jobContext.pipelineToken + "_" + chunkIdx).getBytes
                ).toString

                val properties = new java.util.HashMap[String, AnyRef]()
                properties.put("text", chunkText)
                properties.put("chunk_index", Integer.valueOf(chunkIdx))
                properties.put("source_pipeline", config.name)
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
                globalChunkIdx += 1
            }

            val result = batcher.run()
            if (result.hasErrors)
                throw new DatrisException("Weaviate batch upsert failed: " + result.getError.getMessages.toString)

            totalUpserted += embedded.size
            statusUtil.info("processing", "Upserted " + totalUpserted + " chunks (input chunks: " + chunks.size + ")")
        }

        sendNotification()
        statusUtil.info("end", "Process completed, " + totalUpserted + " chunks upserted to class: " + weaviateConfig.className)
    }

    private def ensureClass(client: WeaviateClient, className: String, dimension: Int): Unit = {
        val schemaResult = client.schema().classGetter().withClassName(className).run()
        if (!schemaResult.hasErrors && schemaResult.getResult != null) {
            verifyClassDimension(client, className, dimension)
            return
        }

        statusUtil.info("processing", "Ensuring Weaviate class: " + className + " with dimension: " + dimension)

        val properties = new java.util.ArrayList[Property]()
        properties.add(Property.builder().name("text").dataType(java.util.Arrays.asList(DataType.TEXT)).build())
        properties.add(Property.builder().name("chunk_index").dataType(java.util.Arrays.asList(DataType.INT)).build())
        properties.add(Property.builder().name("source_pipeline").dataType(java.util.Arrays.asList(DataType.TEXT)).build())
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
        if (createResult.hasErrors) {
            // Race: a concurrent JobRunner (document taps feed many docs
            // simultaneously) may have created the class between our classGetter
            // check and our classCreator call. Re-check and swallow if it's there now.
            val racedIn =
                try {
                    val recheck = client.schema().classGetter().withClassName(className).run()
                    !recheck.hasErrors && recheck.getResult != null
                } catch { case _: Exception => false }
            if (!racedIn)
                throw new DatrisException("Failed to create Weaviate class: " + createResult.getError.getMessages.toString)
            // If a racing session won, still verify its existing vector dim matches ours.
            verifyClassDimension(client, className, dimension)
        }
    }

    // Weaviate's class schema doesn't expose a fixed vector dim — the dim is
    // set implicitly by the first object written. Probe one object for its
    // vector length. If the class is empty (no objects yet) we can't verify,
    // so we proceed and let the first write set the dim naturally.
    private def verifyClassDimension(client: WeaviateClient, className: String, dimension: Int): Unit = {
        val probe =
            try {
                client.data().objectsGetter()
                    .withClassName(className)
                    .withLimit(1)
                    .withVector()
                    .run()
            } catch {
                case _: Exception => return
            }
        if (probe.hasErrors || probe.getResult == null) return
        val objects = probe.getResult.asScala
        objects.headOption.flatMap(o => Option(o.getVector)).foreach { vector =>
            val existing = vector.length
            if (existing > 0 && existing != dimension) {
                throw new DatrisException(
                    "Embedding dimension mismatch on class \"" + className +
                        "\": existing is vector(" + existing + "), configured embedding provider produces vector(" + dimension +
                        "). The stored vectors are incompatible with the new provider. Either drop class \"" +
                        className + "\" and re-ingest, or point this pipeline at a new class."
                )
            }
        }
    }

    private def sendNotification(): Unit = {
        val attributes = Map(
            "database" -> "",
            "schema" -> "",
            "pipeline" -> config.name,
            "destination" -> "weaviate",
            "table" -> weaviateConfig.className
        )
        val notification = Map(
            "pipeline" -> config.name,
            "publisherToken" -> jobContext.pipelineToken,
            "pipelineToken" -> jobContext.pipelineToken,
            "destination" -> "weaviate",
            "collection" -> weaviateConfig.className
        )
        val gson = new Gson()
        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, gson.toJson(notification.asJava), attributes)
    }
}
