package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonObject
import io.milvus.v2.client.{ConnectConfig, MilvusClientV2}
import io.milvus.v2.common.DataType
import io.milvus.v2.common.IndexParam.MetricType
import io.milvus.v2.service.collection.request.{AddFieldReq, CreateCollectionReq, DescribeCollectionReq}
import io.milvus.v2.service.vector.request.InsertReq
import ai.datris.model.{ChunkingConfig, DatrisEnvironment, DatrisException, JobContext}

import scala.collection.JavaConverters._

class MilvusLoader(jobContext: JobContext) extends VectorLoaderBase(jobContext) {
    import VectorLoaderBase.EmbeddedRow

    private val milvusConfig = config.destination.milvus

    override type Client = MilvusClientV2

    override protected def destinationType: String = "milvus"
    override protected def secretDisplayName: String = "Milvus"
    override protected def collectionName: String = milvusConfig.collectionName
    override protected def configuredChunking: ChunkingConfig = milvusConfig.chunking
    override protected def embeddingSecretNameFromConfig: String = milvusConfig.embeddingSecretName
    override protected def destinationSecretNameFromConfig: String = milvusConfig.milvusSecretName
    override protected def tenantSecretNameOverride: String = DatrisEnvironment.current.milvusSecretName

    override protected def openClient(secret: java.util.Map[String, String]): MilvusClientV2 = {
        val host = secret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Milvus secret: " + milvusConfig.milvusSecretName)
        val port = Option(secret.get("port")).getOrElse("19530")
        val apiKey = Option(secret.get("apiKey")).getOrElse("")

        statusUtil.info("processing", "Connecting to Milvus at " + host + ":" + port)

        val connectBuilder = ConnectConfig.builder().uri("http://" + host + ":" + port)
        if (apiKey.nonEmpty) connectBuilder.token(apiKey)
        new MilvusClientV2(connectBuilder.build())
    }

    override protected def closeClient(client: MilvusClientV2): Unit = client.close()

    override protected def upsertBatch(client: MilvusClientV2, rows: List[EmbeddedRow], filename: String): Unit = {
        val data = new java.util.ArrayList[JsonObject]()

        rows.foreach { embeddedRow =>
            val row = new JsonObject()
            row.addProperty("id", embeddedRow.id.toString)
            row.addProperty("text", embeddedRow.text)
            row.addProperty("chunk_index", embeddedRow.chunkIndex)
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
            embeddedRow.embedding.foreach(v => embeddingArray.add(v))
            row.add("embedding", embeddingArray)

            data.add(row)
        }

        val insertReq = InsertReq.builder()
            .collectionName(milvusConfig.collectionName)
            .data(data)
            .build()

        client.insert(insertReq)
    }

    override protected def ensureCollection(client: MilvusClientV2, dimension: Int): Unit = {
        val collectionName = milvusConfig.collectionName
        val collectionsResp = client.listCollections()
        if (collectionsResp.getCollectionNames.contains(collectionName)) {
            verifyCollectionDimension(client, collectionName, dimension)
            return
        }

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
        indexParams.add(
            io.milvus.v2.common.IndexParam.builder()
                .fieldName("embedding")
                .metricType(MetricType.COSINE)
                .build()
        )
        indexParams.add(
            io.milvus.v2.common.IndexParam.builder()
                .fieldName("id")
                .build()
        )

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
                val racedIn =
                    try {
                        client.listCollections().getCollectionNames.contains(collectionName)
                    } catch {
                        case ex: Exception =>
                            logger.debug("Re-check of Milvus collection \"" + collectionName + "\" after create failure threw — assuming no race", ex)
                            false
                    }
                if (!racedIn) throw e
                // If a racing session won, still verify its embedding dim matches ours.
                verifyCollectionDimension(client, collectionName, dimension)
        }
    }

    private def verifyCollectionDimension(client: MilvusClientV2, collectionName: String, dimension: Int): Unit = {
        val describeResp =
            try {
                client.describeCollection(DescribeCollectionReq.builder().collectionName(collectionName).build())
            } catch {
                case e: Exception => // can't read schema — let insert surface the real error
                    logger.debug("Could not describe Milvus collection \"" + collectionName + "\" — skipping dimension verification", e)
                    return
            }
        val fields = describeResp.getCollectionSchema.getFieldSchemaList.asScala
        val embeddingField = fields.find(_.getName == "embedding")
        embeddingField.flatMap(f => Option(f.getDimension)).foreach { existing =>
            if (existing.intValue() != dimension) {
                throw new DatrisException(
                    "Embedding dimension mismatch on collection \"" + collectionName +
                        "\": existing is vector(" + existing + "), configured embedding provider produces vector(" + dimension +
                        "). The stored vectors are incompatible with the new provider. Either drop collection \"" +
                        collectionName + "\" and re-ingest, or point this pipeline at a new collection."
                )
            }
        }
    }
}
