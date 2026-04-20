package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.grpc.Collections.{Distance, VectorParams}
import io.qdrant.client.grpc.Points.PointStruct
import ai.datris.model.{JobContext, DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.collection.JavaConverters._

class QdrantLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil
    private val qdrantConfig = config.destination.qdrant
    private val UPSERT_BATCH_SIZE = 100

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Process started")

        if (jobContext.data.rawBytes == null)
            throw new DatrisException("Qdrant destination requires unstructured file data (PDF, text). Use 'unstructuredAttributes' in the source configuration.")

        // Extract text from the document
        val filename = if (jobContext.metadata != null) jobContext.metadata.dataFileName else ""
        val documentText = TextExtractorUtil.extractText(jobContext.data.rawBytes, filename)
        if (documentText.isEmpty)
            throw new DatrisException("No text could be extracted from the uploaded file: " + filename)

        statusUtil.info("processing", "Extracted " + documentText.length + " characters from: " + filename)

        // Chunk the document
        val chunkingConfig = if (qdrantConfig.chunking != null) qdrantConfig.chunking
            else new ai.datris.model.ChunkingConfig()
        val chunks = ChunkUtil.chunk(documentText, chunkingConfig)
        statusUtil.info("processing", "Chunked into " + chunks.size + " chunks using strategy: " + chunkingConfig.strategy)

        // Get configs — use tenant secret names if in multi-tenant mode
        val embeddingSecretName = if (DatrisEnvironment.current.embeddingSecretName != null) DatrisEnvironment.current.embeddingSecretName else qdrantConfig.embeddingSecretName
        val qdrantSecretName = if (DatrisEnvironment.current.qdrantSecretName != null) DatrisEnvironment.current.qdrantSecretName else qdrantConfig.qdrantSecretName
        val embeddingConfig = EmbeddingUtil.getConfig(embeddingSecretName)
        val qdrantSecret = SecretsUtil.getSecretMap(qdrantSecretName)
            .getOrElse(throw new DatrisException("Qdrant secret not found: " + qdrantSecretName))
        val host = qdrantSecret.get("host")
        if (host == null) throw new DatrisException("'host' not found in Qdrant secret: " + qdrantConfig.qdrantSecretName)
        val port = Option(qdrantSecret.get("port")).map(_.toInt).getOrElse(6334)
        val apiKey = Option(qdrantSecret.get("apiKey")).getOrElse("")

        statusUtil.info("processing", "Connecting to Qdrant at " + host + ":" + port)

        val grpcClientBuilder = QdrantGrpcClient.newBuilder(host, port, false)
        if (apiKey.nonEmpty) grpcClientBuilder.withApiKey(apiKey)
        val client = new QdrantClient(grpcClientBuilder.build())

        try {
            // Ensure collection exists
            val dimension = EmbeddingUtil.embeddingDimension(embeddingConfig)
            ensureCollection(client, qdrantConfig.collectionName, dimension)

            // Batch: embed + upsert
            var totalUpserted = 0
            chunks.zipWithIndex.grouped(UPSERT_BATCH_SIZE).foreach { batch =>
                val texts = batch.map(_._1)
                val embeddings = EmbeddingUtil.generateEmbeddings(texts, embeddingConfig)

                val points = batch.zip(embeddings).map { case ((chunkText, chunkIdx), embedding) =>
                    val pointId = UUID.nameUUIDFromBytes(
                        (jobContext.pipelineToken + "_" + chunkIdx).getBytes
                    )

                    val payload = new java.util.HashMap[String, io.qdrant.client.grpc.JsonWithInt.Value]()
                    payload.put("text", value(chunkText))
                    payload.put("chunk_index", value(chunkIdx.toLong))
                    payload.put("source_pipeline", value(config.name))
                    payload.put("filename", value(filename))

                    // Static metadata from config
                    if (qdrantConfig.metadata != null) {
                        qdrantConfig.metadata.asScala.foreach { case (key, v) =>
                            if (v != null) payload.put(key, value(v))
                        }
                    }

                    PointStruct.newBuilder()
                        .setId(id(pointId))
                        .setVectors(vectors(embedding.map(_.toFloat): _*))
                        .putAllPayload(payload)
                        .build()
                }

                client.upsertAsync(qdrantConfig.collectionName, points.asJava).get()
                totalUpserted += batch.size
                statusUtil.info("processing", "Upserted " + totalUpserted + " of " + chunks.size + " chunks")
            }

            sendNotification()
            statusUtil.info("end", "Process completed, " + totalUpserted + " chunks upserted to collection: " + qdrantConfig.collectionName)
        } finally {
            client.close()
        }
    }

    private def ensureCollection(client: QdrantClient, collectionName: String, dimension: Int): Unit = {
        val collections = client.listCollectionsAsync().get()
        val exists = collections.asScala.exists(_ == collectionName)

        if (exists) {
            // Existing collection — verify the vector size matches. Otherwise the
            // first upsert fails with a cryptic Qdrant "wrong vector size" error.
            verifyCollectionDimension(client, collectionName, dimension)
        } else {
            statusUtil.info("processing", "Ensuring Qdrant collection: " + collectionName + " with dimension: " + dimension)
            try {
                client.createCollectionAsync(collectionName,
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
                    val racedIn = try {
                        client.listCollectionsAsync().get().asScala.exists(_ == collectionName)
                    } catch { case _: Exception => false }
                    if (!racedIn) throw e
                    // If a racing session won, still verify its dimension matches ours.
                    verifyCollectionDimension(client, collectionName, dimension)
            }
        }
    }

    private def verifyCollectionDimension(client: QdrantClient, collectionName: String, dimension: Int): Unit = {
        val info = try {
            client.getCollectionInfoAsync(collectionName).get()
        } catch {
            case _: Exception => return  // can't read config — let the upsert surface the real error
        }
        val vectorsConfig = info.getConfig.getParams.getVectorsConfig
        val existing: Long =
            if (vectorsConfig.hasParams) vectorsConfig.getParams.getSize
            else -1L  // named-vector config — skip check
        if (existing > 0 && existing != dimension.toLong) {
            throw new DatrisException(
                "Embedding dimension mismatch on collection \"" + collectionName +
                "\": existing is vector(" + existing + "), configured embedding provider produces vector(" + dimension +
                "). The stored vectors are incompatible with the new provider. Either drop collection \"" +
                collectionName + "\" and re-ingest, or point this pipeline at a new collection."
            )
        }
    }

    private def sendNotification(): Unit = {
        val attributes = Map(
            "database" -> "",
            "schema" -> "",
            "pipeline" -> config.name,
            "destination" -> "qdrant",
            "table" -> qdrantConfig.collectionName
        )
        val notification = Map(
            "pipeline" -> config.name,
            "publisherToken" -> jobContext.pipelineToken,
            "pipelineToken" -> jobContext.pipelineToken,
            "destination" -> "qdrant",
            "collection" -> qdrantConfig.collectionName
        )
        val gson = new Gson()
        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, gson.toJson(notification.asJava), attributes)
    }
}
