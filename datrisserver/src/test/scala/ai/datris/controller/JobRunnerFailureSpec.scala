package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.{StagingArea, StatusUtil}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer

/** JobRunner's terminal status events on failure. A loader failure must
  * surface under the loader's name with a destination-facing message; the
  * JobRunner-level event is then info level so the rollup does not see a
  * second, misleading error event. Every terminal failure event carries the
  * message chain in `description` and the full stack trace in `detail`. */
class JobRunnerFailureSpec extends AnyFunSuite {

    /** Captures events instead of writing to Mongo. (processName, state, code, description);
      * `details(i)` is the `detail` of `events(i)` (null when none). */
    private class CapturingStatusUtil extends StatusUtil {
        val events = ListBuffer[(String, String, String, String)]()
        val details = ListBuffer[String]()
        // Mirrors the real StatusUtil: a shared, last-writer-wins process name.
        var current = "SomeLoader"
        // When set, the first "begin" event throws — a failure raised on the job thread itself.
        var failOnBegin: Boolean = false
        override def overrideProcessName(processName: String): Unit = current = processName
        override def info(state: String, description: String): Unit = {
            if (failOnBegin && state == "begin") throw new IllegalStateException("begin boom")
            events += ((current, state, "info", description))
            details += currentDetail
        }
        override def error(state: String, description: String): Unit = {
            events += ((current, state, "error", description))
            details += currentDetail
        }
        override def errorAs(processName: String, state: String, description: String): Unit = {
            events += ((processName, state, "error", description))
            details += currentDetail
        }
    }

    test("loaderErrorMessage is the exception message, never the class name when a message exists") {
        assert(JobRunner.loaderErrorMessage(new RuntimeException("connection refused")) == "connection refused")
        assert(JobRunner.loaderErrorMessage(new RuntimeException("  padded  ")) == "padded")
    }

    test("loaderErrorMessage falls back to the class name when the message is missing or blank") {
        assert(JobRunner.loaderErrorMessage(new IllegalStateException()) == "java.lang.IllegalStateException")
        assert(JobRunner.loaderErrorMessage(new IllegalStateException("   ")) == "java.lang.IllegalStateException")
    }

    test("loader failure: JobRunner writes an info end event with the stack in detail, not a second error event") {
        val su = new CapturingStatusUtil
        val cause = new RuntimeException("relation \"orders\" does not exist")
        val wrapped = new RuntimeException("PostgresLoader failed: java.lang.RuntimeException: " + cause.getMessage, cause)

        val message = JobRunner.reportFailure(su, Some(("PostgresLoader", cause)), wrapped)

        assert(message == "PostgresLoader failed: relation \"orders\" does not exist")
        assert(su.events.size == 1)
        val (process, state, code, description) = su.events.head
        assert(process == "JobRunner", "terminal event is JobRunner's even though a loader last set the shared process name")
        assert(state == "end")
        assert(code == "info", "a JobRunner error event would shadow the loader's own error in the rollup")
        assert(description.startsWith("Process completed, error: PostgresLoader failed: relation \"orders\" does not exist"))
        assert(!description.contains("java.lang.RuntimeException"), "the stack trace is not in the description")
        val detail = su.details.head
        assert(
            detail != null && detail.contains("java.lang.RuntimeException") && detail.contains("\tat "),
            "stack trace stays on the event, in detail, for the detail view"
        )
    }

    test("job-thread failure (no loader): the error event is preserved, its stack moved to detail") {
        val su = new CapturingStatusUtil
        val e = new IllegalStateException("schema mismatch")

        val message = JobRunner.reportFailure(su, None, e)

        assert(su.events.size == 1)
        val (process, state, code, description) = su.events.head
        // Job-thread failures keep the pre-existing attribution (the stage that
        // last set the process name, e.g. DataQuality) — unchanged behaviour.
        assert(process == "SomeLoader" && state == "end" && code == "error")
        assert(description == "Process completed, error: schema mismatch")
        assert(!description.contains("IllegalStateException"), "the exception class from the trace header is not in the description")
        assert(message == "schema mismatch")
        val detail = su.details.head
        assert(detail != null && detail.contains("IllegalStateException: schema mismatch") && detail.contains("\tat "), "full trace in detail")
    }

    // plans/stories/streaming-pipeline.md, Phase 1, Acceptance 8: the run's
    // staging directory is gone after the job ends, on success and on failure.
    private val stagingRoot: Path = Files.createTempDirectory("job-runner-staging")

    private def testEnv: DatrisEnvironment = DatrisEnvironment(
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
        tempDir = stagingRoot.toString
    )

    /** A job with no destinations whose payload is staged under `token`, the way
      * StreamNotifier / FileNotifier stage it. Returns the context and its staging dir. */
    private def stagedJob(token: String, status: StatusUtil): (JobContext, Path) = {
        val env = testEnv
        TenantContext.set(env)
        try {
            val data = StagingArea.withToken(token)(Data(6L, List("id"), List(SchemaField("id", "string")), List("1", "2"), null))
            val dir = StagingArea.root.resolve(token)
            assert(Files.isDirectory(dir) && Files.list(dir).count() == 1, "the payload is staged under the run token before the job starts")
            val config = new com.google.gson.Gson().fromJson("""{"name":"staged_pipe","destination":{}}""", classOf[PipelineConfig])
            (
                JobContext(
                    token,
                    PipelineMetadata("staged_pipe", "f.csv", null, token, bulkUpload = false),
                    data,
                    config,
                    null,
                    INITIALIZED,
                    null,
                    status,
                    env
                ),
                dir
            )
        } finally TenantContext.clear()
    }

    test("run() success path removes the run's staging directory when the job ends") {
        val su = new CapturingStatusUtil
        val (ctx, dir) = stagedJob("run-success-" + System.nanoTime(), su)

        new JobRunner(ctx).run()

        assert(!Files.exists(dir), "staging dir must be gone after a successful run")
        assert(Files.isDirectory(stagingRoot), "only the run directory is removed, never the root")
        assert(su.events.exists { case (_, state, code, _) => state == "end" && code == "info" })
    }

    test("run() failure path removes the run's staging directory when the job ends") {
        val su = new CapturingStatusUtil
        su.failOnBegin = true
        val (ctx, dir) = stagedJob("run-failure-" + System.nanoTime(), su)

        val e = intercept[DatrisException](new JobRunner(ctx).run())

        assert(e.getMessage.contains("begin boom"))
        assert(!Files.exists(dir), "staging dir must be gone after a failed run")
        assert(su.events.exists { case (_, state, code, _) => state == "end" && code == "error" }, "job-thread failure still writes the JobRunner error event")
    }

    test("run() cleanup does not touch other runs' staging directories") {
        val su = new CapturingStatusUtil
        val (ctx, dir) = stagedJob("run-a-" + System.nanoTime(), su)
        val (_, otherDir) = stagedJob("run-b-" + System.nanoTime(), new CapturingStatusUtil)

        new JobRunner(ctx).run()

        assert(!Files.exists(dir))
        assert(Files.isDirectory(otherDir), "a different run's staged payload is left alone")
    }

    // plans/stories/run-status-message-not-stacktrace.md, Acceptance bullet 2.
    // The terminal status carries the message chain (ErrorText.messageChain:
    // message, then "\ncaused by: <message>" per distinct cause), never a stack
    // trace; reportFailure returns that same chain. The full trace goes to the
    // server log and to FixSuggestionUtil (not observable through a seam here).
    private def assertNoFrames(text: String): Unit = {
        assert(!text.contains("\tat "), s"stack frame in: $text")
        assert(!text.linesIterator.exists(_.trim.startsWith("at ai.datris")), s"stack frame in: $text")
        assert(!text.linesIterator.exists(_.trim.startsWith("at java.")), s"stack frame in: $text")
    }

    test("the terminal status of a failed run contains the message and no stack frames") {
        val su = new CapturingStatusUtil
        val e = new IllegalStateException("schema mismatch", new RuntimeException("column amount missing"))

        val message = JobRunner.reportFailure(su, None, e)

        assert(su.events.size == 1)
        val (_, state, code, description) = su.events.head
        assert(state == "end" && code == "error")
        assert(description.startsWith("Process completed, error: "))
        assert(description.contains("schema mismatch"))
        assertNoFrames(description)
        assert(description == "Process completed, error: schema mismatch\ncaused by: column amount missing")
        assert(message == "schema mismatch\ncaused by: column amount missing", "returned value is the message chain, not a trace")
        assertNoFrames(message)
    }

    test("a loader failure reads '<Loader> failed: <message>' with no stack frames") {
        val su = new CapturingStatusUtil
        val cause = new RuntimeException("relation \"orders\" does not exist")
        val wrapped = new RuntimeException("PostgresLoader failed: java.lang.RuntimeException: " + cause.getMessage, cause)

        val message = JobRunner.reportFailure(su, Some(("PostgresLoader", cause)), wrapped)

        assert(message == "PostgresLoader failed: relation \"orders\" does not exist")
        assert(su.events.size == 1)
        val (process, state, code, description) = su.events.head
        assert(process == "JobRunner" && state == "end" && code == "info")
        assert(description == "Process completed, error: PostgresLoader failed: relation \"orders\" does not exist")
        assertNoFrames(description)
    }

    test("the exception thrown out of a failed run carries the message, not a stack trace") {
        val su = new CapturingStatusUtil
        su.failOnBegin = true
        val (ctx, _) = stagedJob("run-msg-" + System.nanoTime(), su)

        val e = intercept[DatrisException](new JobRunner(ctx).run())

        assert(e.getMessage == "Pipeline error: begin boom")
        assertNoFrames(e.getMessage)
        val ends = su.events.filter { case (_, state, code, _) => state == "end" && code == "error" }
        assert(ends.size == 1)
        assert(ends.head._4 == "Process completed, error: begin boom")
    }

    test("deriveCountAndType keeps (0, \"record\") for an empty JSON array, as when rawData held \"[]\"") {
        TenantContext.set(testEnv)
        try {
            val empty = Data(2L, null, null, null, "[]")
            assert(empty.staged.arraySource && empty.staged.bytes == 0L)
            assert(JobRunner.deriveCountAndType(empty) == ((0, "record")))
            assert(JobRunner.deriveCountAndType(Data(0L, null, null, null, null, rawBytes = null)) == ((0, null)))
        } finally TenantContext.clear()
    }
}
