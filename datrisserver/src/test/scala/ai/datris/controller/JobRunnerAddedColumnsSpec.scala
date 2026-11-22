package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.{StagingArea, StatusUtil, TransformedColumns}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeoutException
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

/** JobRunner stores the columns this run's transformation added (on a
  * pipeline with a SQL table: `JobContext.transformationColumns`) only after
  * every destination loaded: never after a loader failure, a destination
  * timeout, or in a rehearsal. The context below carries the
  * `RunColumns` the transformation stage would have attached. */
class JobRunnerAddedColumnsSpec extends AnyFunSuite {

    private val root: Path = Files.createTempDirectory("jobrunner-added-columns-spec")

    private class QuietStatusUtil extends StatusUtil {
        val warns = ListBuffer[String]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = ()
        override def warn(state: String, description: String): Unit = warns += description
        override def error(state: String, description: String): Unit = ()
        override def errorAs(processName: String, state: String, description: String): Unit = ()
    }

    private def env: DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = null,
        archivedMetadataTableName = null,
        pipelineStatusTableName = null,
        fileNotifierMessageTableName = null,
        dataPullTableName = null,
        useApiKeys = false,
        apiKeysSecretName = null,
        postgresSecretName = null,
        mongoDbSecretName = null,
        kafkaProducerSecretName = null,
        kafkaConsumerConfig = null,
        mongoDbConfig = null,
        minIOConfig = null,
        activeMQConfig = null,
        aiConfig = null,
        aiEnabled = false,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false,
        tempDir = root.toString
    )

    private type Stored = ListBuffer[(String, List[String])]

    private def runColumns(stored: Stored): TransformedColumns.RunColumns =
        new TransformedColumns.RunColumns(List(SchemaField("score", "string")), store = true, (n, f) => stored += ((n, f.map(_.name))))

    /** A job whose rows are staged and whose context carries `score` as added by the transformation. */
    private def job(destinationJson: String, stored: Stored, rehearsal: Boolean = false): JobContext = {
        val e = env
        TenantContext.set(e)
        try {
            val token = "added-columns-" + System.nanoTime()
            val data = StagingArea.withToken(token)(Data(
                6L,
                List("id", "score"),
                List(SchemaField("id", "string"), SchemaField("score", "string")),
                List("1,7"),
                null
            ))
            val config = new com.google.gson.Gson().fromJson(s"""{"name":"weather","destination":$destinationJson}""", classOf[PipelineConfig])
            JobContext(
                token,
                PipelineMetadata("weather", "f.csv", null, token, bulkUpload = false, rehearsal = rehearsal),
                data,
                config,
                null,
                INITIALIZED,
                null,
                new QuietStatusUtil,
                e,
                transformationColumns = runColumns(stored)
            )
        } finally TenantContext.clear()
    }

    test("a run whose destinations all loaded stores the added columns") {
        val stored: Stored = ListBuffer()
        new JobRunner(job("{}", stored)).run()
        assert(stored.toList == List("weather" -> List("score")))
    }

    test("a run whose destination loader failed stores nothing") {
        val stored: Stored = ListBuffer()
        val ctx = job("""{"restEndpoint":{"endpoint":"http://127.0.0.1:1/never","timeoutMs":2000}}""", stored)
        intercept[DatrisException](new JobRunner(ctx).run())
        assert(stored.isEmpty)
    }

    test("a rehearsal stores nothing, even when every destination loaded") {
        val stored: Stored = ListBuffer()
        new JobRunner(job("{}", stored, rehearsal = true)).run()
        assert(stored.isEmpty)
    }

    test("awaitDestinations stores after the loaders complete, and not after a failure or a timeout") {
        implicit val ec: ExecutionContext = ExecutionContext.global
        val status = new QuietStatusUtil
        def ctx(stored: Stored): JobContext =
            JobContext("t", null, null, PipelineConfig(name = "weather"), null, INITIALIZED, null, status, null, transformationColumns = runColumns(stored))

        val ok: Stored = ListBuffer()
        JobRunner.awaitDestinations(List(Future.successful(()), Future(())), 5.seconds, ctx(ok), storeColumns = true, status, "JobRunner")
        assert(ok.toList == List("weather" -> List("score")))

        val failed: Stored = ListBuffer()
        intercept[RuntimeException](
            JobRunner.awaitDestinations(
                List(Future.successful(()), Future.failed(new RuntimeException("PostgresLoader failed"))),
                5.seconds,
                ctx(failed),
                true,
                status,
                "JobRunner"
            )
        )
        assert(failed.isEmpty)

        val slow: Stored = ListBuffer()
        intercept[TimeoutException](JobRunner.awaitDestinations(List(Promise[Unit]().future), 50.millis, ctx(slow), storeColumns = true, status, "JobRunner"))
        assert(slow.isEmpty)

        val rehearsal: Stored = ListBuffer()
        JobRunner.awaitDestinations(List(Future.successful(())), 5.seconds, ctx(rehearsal), storeColumns = false, status, "JobRunner")
        assert(rehearsal.isEmpty)
    }
}
