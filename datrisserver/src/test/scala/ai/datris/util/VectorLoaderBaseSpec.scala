package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.collection.mutable.ListBuffer

/** Exercises the shared VectorLoaderBase skeleton with a fake destination:
  * chunk indexing, deterministic ids, batch-of-100 grouping, status lifecycle,
  * guard behavior, and the notification payload — no Vault, Mongo, or
  * embeddings API involved.
  */
class VectorLoaderBaseSpec extends AnyFunSuite {

    private val TOKEN = "test-pipeline-token"

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += ((state, description))
        override def error(state: String, description: String): Unit = messages += ((state, description))
    }

    private class FakeLoader(jobContext: JobContext, failUpsert: Boolean = false) extends VectorLoaderBase(jobContext) {
        import VectorLoaderBase.EmbeddedRow

        val upsertedBatches = new ListBuffer[List[EmbeddedRow]]()
        var ensuredDimension: Int = -1
        var clientClosed = false
        var notificationJson: String = _
        var notificationAttributes: Map[String, String] = _

        override type Client = String

        override protected def destinationType: String = "fake"
        override protected def secretDisplayName: String = "Fake"
        override protected def collectionName: String = "fake_collection"
        override protected def configuredChunking: ChunkingConfig =
            // "none" + maxChunkTokens with 1 char = 1 token slices the doc into
            // predictable fixed-size chunks without a real chunking strategy.
            ChunkingConfig("none", chunkOverlap = 0, maxChunkTokens = 10, tokensPerCharRatio = 1.0)
        override protected def embeddingSecretNameFromConfig: String = "cfg/embedding"
        override protected def destinationSecretNameFromConfig: String = "cfg/fake"
        override protected def tenantSecretNameOverride: String = null

        override protected def tenantEmbeddingSecretName: String = null
        override protected def resolveEmbeddingConfig(secretName: String): EmbeddingUtil.EmbeddingConfig = null
        override protected def fetchDestinationSecret(name: String): Option[java.util.Map[String, String]] =
            Some(new java.util.HashMap[String, String]())
        override protected def embed(batch: List[String], cfg: EmbeddingUtil.EmbeddingConfig): List[EmbeddingUtil.EmbeddedChunk] =
            batch.map(t => EmbeddingUtil.EmbeddedChunk(t, Array(1.0f, 2.0f)))
        override protected def embeddingDimension(cfg: EmbeddingUtil.EmbeddingConfig): Int = 2

        override protected def openClient(secret: java.util.Map[String, String]): String = "client"
        override protected def ensureCollection(client: String, dimension: Int): Unit = ensuredDimension = dimension
        override protected def upsertBatch(client: String, rows: List[EmbeddedRow], filename: String): Unit = {
            if (failUpsert) throw new DatrisException("upsert boom")
            upsertedBatches += rows
        }
        override protected def closeClient(client: String): Unit = clientClosed = true

        override protected def publishNotification(notificationJson: String, attributes: Map[String, String]): Unit = {
            this.notificationJson = notificationJson
            this.notificationAttributes = attributes
        }
    }

    private def jobContext(status: StatusUtil, text: String): JobContext =
        JobContext(
            pipelineToken = TOKEN,
            metadata = PipelineMetadata("p", "doc.txt", "/tmp/doc.txt", "pub", bulkUpload = false),
            data = Data(
                size = text.length.toLong,
                header = null,
                headerWithSchema = null,
                rows = null,
                rawData = null,
                rawBytes = text.getBytes(StandardCharsets.UTF_8)
            ),
            config = new com.google.gson.Gson().fromJson("""{"name":"vector_pipe"}""", classOf[PipelineConfig]),
            pipelineProperties = null,
            state = null,
            thread = null,
            statusUtil = status
        )

    test("rows carry sequential chunk indices and deterministic name-UUID ids") {
        val loader = new FakeLoader(jobContext(new RecordingStatusUtil, "a" * 25))
        loader.process()

        val rows = loader.upsertedBatches.flatten.toList
        // 25 chars / 10-token cap with ratio 1.0 → chunks of ≤10 chars, 3 chunks
        assert(rows.map(_.chunkIndex) == List(0, 1, 2))
        rows.foreach { row =>
            assert(row.id == UUID.nameUUIDFromBytes((TOKEN + "_" + row.chunkIndex).getBytes))
        }
        assert(rows.map(_.text).mkString("") == "a" * 25)
        assert(loader.ensuredDimension == 2)
    }

    test("chunks are upserted in batches of at most 100") {
        // 1500 chars / 9-char pieces (10-token cap × 0.90 safety) → 167 chunks
        val loader = new FakeLoader(jobContext(new RecordingStatusUtil, "b" * 1500))
        loader.process()

        assert(loader.upsertedBatches.map(_.size).toList == List(100, 67))
        // Indices continue across batches
        assert(loader.upsertedBatches.flatten.map(_.chunkIndex).toList == (0 until 167).toList)
    }

    test("notification payload carries destination, collection, and tokens") {
        val loader = new FakeLoader(jobContext(new RecordingStatusUtil, "hello world"))
        loader.process()

        assert(loader.notificationAttributes == Map(
            "database" -> "",
            "schema" -> "",
            "pipeline" -> "vector_pipe",
            "destination" -> "fake",
            "table" -> "fake_collection"
        ))
        val gson = new com.google.gson.Gson()
        val payload = gson.fromJson(loader.notificationJson, classOf[java.util.Map[String, String]])
        assert(payload.get("destination") == "fake")
        assert(payload.get("collection") == "fake_collection")
        assert(payload.get("pipelineToken") == TOKEN)
        assert(payload.get("publisherToken") == TOKEN)
        assert(payload.get("pipeline") == "vector_pipe")
    }

    test("status lifecycle runs begin → processing → end") {
        val status = new RecordingStatusUtil
        val loader = new FakeLoader(jobContext(status, "hello"))
        loader.process()

        assert(status.messages.head == (("begin", "Process started")))
        assert(status.messages.last._1 == "end")
        assert(status.messages.last._2.contains("1 chunks upserted to collection: fake_collection"))
        assert(loader.clientClosed)
    }

    test("missing rawBytes fails with the destination guard message") {
        val ctx = jobContext(new RecordingStatusUtil, "x").copy(data = Data(0, null, null, null, null, rawBytes = null))
        val e = intercept[DatrisException] { new FakeLoader(ctx).process() }
        assert(e.getMessage.startsWith("Fake destination requires unstructured file data"))
    }

    test("client is closed even when the upsert fails") {
        val loader = new FakeLoader(jobContext(new RecordingStatusUtil, "some text"), failUpsert = true)
        intercept[DatrisException] { loader.process() }
        assert(loader.clientClosed)
        assert(loader.notificationJson == null) // no completion notification on failure
    }
}
