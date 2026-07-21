package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import io.weaviate.client.{Config => WeaviateClientConfig, WeaviateClient}
import io.weaviate.client.v1.schema.model.{DataType, Property, WeaviateClass}
import ai.datris.model.{ChunkingConfig, DatrisEnvironment, DatrisException, JobContext}

import scala.collection.JavaConverters._

class WeaviateLoader(jobContext: JobContext) extends VectorLoaderBase(jobContext) {
    import VectorLoaderBase.EmbeddedRow

    private val weaviateConfig = config.destination.weaviate

    override type Client = WeaviateClient

    override protected def destinationType: String = "weaviate"
    override protected def secretDisplayName: String = "Weaviate"
    override protected def containerLabel: String = "class"
    override protected def collectionName: String = weaviateConfig.className
    override protected def configuredChunking: ChunkingConfig = weaviateConfig.chunking
    override protected def embeddingSecretNameFromConfig: String = weaviateConfig.embeddingSecretName
    override protected def destinationSecretNameFromConfig: String = weaviateConfig.weaviateSecretName
    override protected def tenantSecretNameOverride: String = DatrisEnvironment.current.weaviateSecretName

    override protected def openClient(secret: java.util.Map[String, String]): WeaviateClient = {
        val host = secret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Weaviate secret: " + weaviateConfig.weaviateSecretName)
        val port = Option(secret.get("port")).getOrElse("8079")
        val scheme = Option(secret.get("scheme")).getOrElse("http")
        val apiKey = Option(secret.get("apiKey")).getOrElse("")

        statusUtil.info("processing", "Connecting to Weaviate at " + scheme + "://" + host + ":" + port)

        val clientConfig = new WeaviateClientConfig(scheme, host + ":" + port)
        if (apiKey.nonEmpty)
            io.weaviate.client.WeaviateAuthClient.apiKey(clientConfig, apiKey)
        else
            new WeaviateClient(clientConfig)
    }

    // The Weaviate HTTP client holds no pooled resources needing release.
    override protected def closeClient(client: WeaviateClient): Unit = ()

    override protected def upsertBatch(client: WeaviateClient, rows: List[EmbeddedRow], filename: String): Unit = {
        val batcher = client.batch().objectsBatcher()

        rows.foreach { row =>
            val properties = new java.util.HashMap[String, AnyRef]()
            properties.put("text", row.text)
            properties.put("chunk_index", Integer.valueOf(row.chunkIndex))
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
                .id(row.id.toString)
                .properties(properties)
                .vector(row.embedding.map(java.lang.Float.valueOf))
                .build()

            batcher.withObject(weaviateObject)
        }

        val result = batcher.run()
        if (result.hasErrors)
            throw new DatrisException("Weaviate batch upsert failed: " + result.getError.getMessages.toString)
    }

    override protected def ensureCollection(client: WeaviateClient, dimension: Int): Unit = {
        val className = weaviateConfig.className
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
                } catch {
                    case e: Exception =>
                        logger.debug("Re-check of Weaviate class \"" + className + "\" after create failure threw — assuming no race", e)
                        false
                }
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
                case e: Exception =>
                    logger.debug("Dimension-probe of Weaviate class \"" + className + "\" failed — skipping dimension verification", e)
                    return
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
}
