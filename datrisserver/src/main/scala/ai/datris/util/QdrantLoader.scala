package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.grpc.Collections.{Distance, VectorParams}
import io.qdrant.client.grpc.Points.PointStruct
import ai.datris.model.{ChunkingConfig, DatrisEnvironment, DatrisException, JobContext}

import scala.collection.JavaConverters._

class QdrantLoader(jobContext: JobContext) extends VectorLoaderBase(jobContext) {
    import VectorLoaderBase.EmbeddedRow

    private val qdrantConfig = config.destination.qdrant

    override type Client = QdrantClient

    override protected def destinationType: String = "qdrant"
    override protected def secretDisplayName: String = "Qdrant"
    override protected def collectionName: String = qdrantConfig.collectionName
    override protected def configuredChunking: ChunkingConfig = qdrantConfig.chunking
    override protected def embeddingSecretNameFromConfig: String = qdrantConfig.embeddingSecretName
    override protected def destinationSecretNameFromConfig: String = qdrantConfig.qdrantSecretName
    override protected def tenantSecretNameOverride: String = DatrisEnvironment.current.qdrantSecretName

    override protected def guardMessage: String =
        "Qdrant destination requires unstructured file data (PDF, text). Use 'unstructuredAttributes' in the source configuration."

    override protected def openClient(secret: java.util.Map[String, String]): QdrantClient = {
        val host = secret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Qdrant secret: " + qdrantConfig.qdrantSecretName)
        val port = Option(secret.get("port")).map(_.toInt).getOrElse(6334)
        val apiKey = Option(secret.get("apiKey")).getOrElse("")

        statusUtil.info("processing", "Connecting to Qdrant at " + host + ":" + port)

        val grpcClientBuilder = QdrantGrpcClient.newBuilder(host, port, false)
        if (apiKey.nonEmpty) grpcClientBuilder.withApiKey(apiKey)
        new QdrantClient(grpcClientBuilder.build())
    }

    override protected def closeClient(client: QdrantClient): Unit = client.close()

    override protected def upsertBatch(client: QdrantClient, rows: List[EmbeddedRow], filename: String): Unit = {
        val points = rows.map { row =>
            val payload = new java.util.HashMap[String, io.qdrant.client.grpc.JsonWithInt.Value]()
            payload.put("text", value(row.text))
            payload.put("chunk_index", value(row.chunkIndex.toLong))
            payload.put("source_pipeline", value(config.name))
            payload.put("filename", value(filename))

            // Static metadata from config
            if (qdrantConfig.metadata != null) {
                qdrantConfig.metadata.asScala.foreach { case (key, v) =>
                    if (v != null) payload.put(key, value(v))
                }
            }

            PointStruct.newBuilder()
                .setId(id(row.id))
                .setVectors(vectors(row.embedding.map(_.toFloat): _*))
                .putAllPayload(payload)
                .build()
        }

        client.upsertAsync(qdrantConfig.collectionName, points.asJava).get()
    }

    override protected def ensureCollection(client: QdrantClient, dimension: Int): Unit = {
        val collectionName = qdrantConfig.collectionName
        val collections = client.listCollectionsAsync().get()
        val exists = collections.asScala.exists(_ == collectionName)

        if (exists) {
            // Existing collection — verify the vector size matches. Otherwise the
            // first upsert fails with a cryptic Qdrant "wrong vector size" error.
            verifyCollectionDimension(client, collectionName, dimension)
        } else {
            statusUtil.info("processing", "Ensuring Qdrant collection: " + collectionName + " with dimension: " + dimension)
            try {
                client.createCollectionAsync(
                    collectionName,
                    VectorParams.newBuilder()
                        .setDistance(Distance.Cosine)
                        .setSize(dimension)
                        .build()
                ).get()
            } catch {
                case e: Exception =>
                    // Race: a concurrent JobRunner (document taps feed many docs
                    // simultaneously) may have created the collection between our
                    // listCollections check and our createCollection call. Re-check
                    // and swallow the error if so; otherwise rethrow the real problem.
                    val racedIn =
                        try {
                            client.listCollectionsAsync().get().asScala.exists(_ == collectionName)
                        } catch {
                            case ex: Exception =>
                                logger.debug("Re-check of Qdrant collection \"" + collectionName + "\" after create failure threw — assuming no race", ex)
                                false
                        }
                    if (!racedIn) throw e
                    // If a racing session won, still verify its dimension matches ours.
                    verifyCollectionDimension(client, collectionName, dimension)
            }
        }
    }

    private def verifyCollectionDimension(client: QdrantClient, collectionName: String, dimension: Int): Unit = {
        val info =
            try {
                client.getCollectionInfoAsync(collectionName).get()
            } catch {
                case e: Exception => // can't read config — let the upsert surface the real error
                    logger.debug("Could not read Qdrant collection \"" + collectionName + "\" config — skipping dimension verification", e)
                    return
            }
        val vectorsConfig = info.getConfig.getParams.getVectorsConfig
        val existing: Long =
            if (vectorsConfig.hasParams) vectorsConfig.getParams.getSize
            else -1L // named-vector config — skip check
        if (existing > 0 && existing != dimension.toLong) {
            throw new DatrisException(
                "Embedding dimension mismatch on collection \"" + collectionName +
                    "\": existing is vector(" + existing + "), configured embedding provider produces vector(" + dimension +
                    "). The stored vectors are incompatible with the new provider. Either drop collection \"" +
                    collectionName + "\" and re-ingest, or point this pipeline at a new collection."
            )
        }
    }
}
