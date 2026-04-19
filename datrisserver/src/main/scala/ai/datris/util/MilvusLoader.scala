package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{Gson, JsonObject}
import io.milvus.v2.client.{ConnectConfig, MilvusClientV2}
import io.milvus.v2.common.DataType
import io.milvus.v2.common.IndexParam.MetricType
import io.milvus.v2.service.collection.request.{AddFieldReq, CreateCollectionReq}
import io.milvus.v2.service.vector.request.InsertReq
import ai.datris.model.{JobContext, DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.collection.JavaConverters._

class MilvusLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil
    private val milvusConfig = config.destination.milvus
    private val UPSERT_BATCH_SIZE = 100

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Process started")

        if (jobContext.data.rawBytes == null)
            throw new DatrisException("Milvus destination requires unstructured file data (PDF, DOC, DOCX, HTML, text). Use 'unstructuredAttributes' in the source configuration.")

        // Extract text from the document
        val filename = if (jobContext.metadata != null) jobContext.metadata.dataFileName else ""
        val documentText = TextExtractorUtil.extractText(jobContext.data.rawBytes, filename)
        if (documentText.isEmpty)
            throw new DatrisException("No text could be extracted from the uploaded file: " + filename)

        statusUtil.info("processing", "Extracted " + documentText.length + " characters from: " + filename)

        // Chunk the document
        val chunkingConfig = if (milvusConfig.chunking != null) milvusConfig.chunking
            else new ai.datris.model.ChunkingConfig()
        val chunks = ChunkUtil.chunk(documentText, chunkingConfig)
        statusUtil.info("processing", "Chunked into " + chunks.size + " chunks using strategy: " + chunkingConfig.strategy)

        // Get configs — use tenant secret names if in multi-tenant mode
        val embeddingSecretName = if (DatrisEnvironment.current.embeddingSecretName != null) DatrisEnvironment.current.embeddingSecretName else milvusConfig.embeddingSecretName
        val milvusSecretName = if (DatrisEnvironment.current.milvusSecretName != null) DatrisEnvironment.current.milvusSecretName else milvusConfig.milvusSecretName
        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val milvusSecret = SecretsUtil.getSecretMap(milvusSecretName)
            .getOrElse(throw new DatrisException("Milvus secret not found: " + milvusSecretName))
        val host = milvusSecret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Milvus secret: " + milvusConfig.milvusSecretName)
        val port = Option(milvusSecret.get("port")).getOrElse("19530")
        val apiKey = Option(milvusSecret.get("apiKey")).getOrElse("")

        statusUtil.info("processing", "Connecting to Milvus at " + host + ":" + port)

        val connectBuilder = ConnectConfig.builder().uri("http://" + host + ":" + port)
        if (apiKey.nonEmpty) connectBuilder.token(apiKey)
        val client = new MilvusClientV2(connectBuilder.build())

        try {
            // Ensure collection exists
            val dimension = EmbeddingUtil.embeddingDimension(embeddingConfig)
            ensureCollection(client, milvusConfig.collectionName, dimension)

            // Batch: embed + upsert
            var totalUpserted = 0
            chunks.zipWithIndex.grouped(UPSERT_BATCH_SIZE).foreach { batch =>
                val texts = batch.map(_._1)
                val embeddings = EmbeddingUtil.generateEmbeddings(texts, embeddingConfig)

                val rows = new java.util.ArrayList[JsonObject]()

                batch.zip(embeddings).foreach { case ((chunkText, chunkIdx), embedding) =>
                    val objectId = UUID.nameUUIDFromBytes(
                        (jobContext.pipelineToken + "_" + chunkIdx).getBytes
                    ).toString

                    val row = new JsonObject()
                    row.addProperty("id", objectId)
                    row.addProperty("text", chunkText)
                    row.addProperty("chunk_index", chunkIdx)
                    row.addProperty("source_pipeline", config.name)
                    row.addProperty("filename", filename)

                    // Static metadata from config
                    if (milvusConfig.metadata != null) {
                        milvusConfig.metadata.asScala.foreach { case (key, v) =>
                            if (v != null) row.addProperty(key, v)
                        }
                    }

                    // Embedding as JSON array
                    val embeddingArray = new com.google.gson.JsonArray()
                    embedding.foreach(v => embeddingArray.add(v))
                    row.add("embedding", embeddingArray)

                    rows.add(row)
                }

                val insertReq = InsertReq.builder()
                    .collectionName(milvusConfig.collectionName)
                    .data(rows)
                    .build()

                client.insert(insertReq)
                totalUpserted += batch.size
                statusUtil.info("processing", "Upserted " + totalUpserted + " of " + chunks.size + " chunks")
            }

            sendNotification()
            statusUtil.info("end", "Process completed, " + totalUpserted + " chunks upserted to collection: " + milvusConfig.collectionName)
        } finally {
            client.close()
        }
    }

    private def ensureCollection(client: MilvusClientV2, collectionName: String, dimension: Int): Unit = {
        val collectionsResp = client.listCollections()
        if (collectionsResp.getCollectionNames.contains(collectionName)) return

        statusUtil.info("processing", "Ensuring Milvus collection: " + collectionName + " with dimension: " + dimension)

        val schema = client.createSchema()
        schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.VarChar).isPrimaryKey(true).maxLength(36).build())
        schema.addField(AddFieldReq.builder().fieldName("text").dataType(DataType.VarChar).maxLength(65535).build())
        schema.addField(AddFieldReq.builder().fieldName("chunk_index").dataType(DataType.Int32).build())
        schema.addField(AddFieldReq.builder().fieldName("source_pipeline").dataType(DataType.VarChar).maxLength(256).build())
        schema.addField(AddFieldReq.builder().fieldName("filename").dataType(DataType.VarChar).maxLength(256).build())
        schema.addField(AddFieldReq.builder().fieldName("embedding").dataType(DataType.FloatVector).dimension(dimension).build())
        // Dynamic fields enabled for metadata
        // schema.setEnableDynamicField(true) — dynamic fields enabled by default in Milvus v2

        val indexParams = new java.util.ArrayList[io.milvus.v2.common.IndexParam]()
        indexParams.add(io.milvus.v2.common.IndexParam.builder()
            .fieldName("embedding")
            .metricType(MetricType.COSINE)
            .build())
        indexParams.add(io.milvus.v2.common.IndexParam.builder()
            .fieldName("id")
            .build())

        val createReq = CreateCollectionReq.builder()
            .collectionName(collectionName)
            .collectionSchema(schema)
            .indexParams(indexParams)
            .build()

        try {
            client.createCollection(createReq)
        } catch {
            case e: Exception =>
                // Race: a concurrent JobRunner (document taps feed many docs
                // simultaneously) may have created the collection between our
                // listCollections check and our createCollection call. Re-check
                // and swallow if it's there now.
                val racedIn = try {
                    client.listCollections().getCollectionNames.contains(collectionName)
                } catch { case _: Exception => false }
                if (!racedIn) throw e
        }
    }

    private def sendNotification(): Unit = {
        val attributes = Map(
            "database" -> "",
            "schema" -> "",
            "pipeline" -> config.name,
            "destination" -> "milvus",
            "table" -> milvusConfig.collectionName
        )
        val notification = Map(
            "pipeline" -> config.name,
            "publisherToken" -> jobContext.pipelineToken,
            "pipelineToken" -> jobContext.pipelineToken,
            "destination" -> "milvus",
            "collection" -> milvusConfig.collectionName
        )
        val gson = new Gson()
        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, gson.toJson(notification.asJava), attributes)
    }
}
