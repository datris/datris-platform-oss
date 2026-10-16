package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable

/** Story: Scratch-first verification with evidence thresholds
  * (plans/stories/self-healing-3-scratch-first-verify.md), Acceptance bullet 2.
  *
  * `TapRunner.run(tap, mode = "scratch", scriptOverride = ...)` executes the
  * (unsaved) candidate script in test mode and feeds its output through the
  * real pipeline with the destination swapped for `scratch`. Nothing is
  * stamped on the tap and the cursor is never committed.
  *
  * The runner is driven with fakes only (no Mongo, no MinIO, no sidecar).
  * Contract pinned here (TapScheduler.fireOverride-style seams; the story
  * asks for "TapScheduler-style seams" without naming them, so the names are
  * chosen here — keep them or update this spec):
  *
  * {{{
  * object TapRunner {
  *   def run(tapConfig: TapConfig, mode: String = "run", testLimit: Int = 0,
  *           params: Map[String, String] = Map.empty, trigger: String = "manual",
  *           scriptOverride: String = null): TapScriptResult
  *
  *   /** Every persistent side effect of a run. Production wires TapConfigIO.write,
  *     * TapStateIO.readStateJson / TapStateIO.write, the tap-log table put
  *     * (writeRunLog), PipelineConfigIO.read and
  *     * `new StreamNotifier().process(staged, ..., configOverride)` +
  *     * GlobalJobContext.addJobContext. */
  *   trait Effects {
  *     def writeTap(tap: TapConfig): Unit
  *     def readState(tapName: String): String
  *     def commitState(state: TapState): Unit
  *     def writeRunLog(log: TapRunLog): Unit
  *     def readPipeline(name: String): PipelineConfig
  *     /** Returns the job's pipeline token. `configOverride` null = the stored config. */
  *     def submit(staged: StagedPayload, sizeHint: Long, filename: String, pipeline: String,
  *                publisherToken: String, tapFeed: TapFeedInfo, configOverride: PipelineConfig): String
  *   }
  *   @volatile private[util] var effects: Effects   // default: the production wiring
  * }
  *
  * object TapScriptRunner {
  *   def run(tapConfig, testLimit, params, previousState, mode, scriptOverride: String = null): TapScriptResult
  *   /** The sidecar hand-off seam: when non-null, runScript calls it with
  *     * (tap, the exact script text about to execute, mode) instead of the
  *     * wrapper / venv / sidecar. */
  *   @volatile private[util] var executeOverride: (TapConfig, String, String) => TapScriptResult = null
  * }
  * }}}
  *
  * The tap's script lives at a storage path no spec can read, so if the
  * override were ignored `TapCodeStore.readScript` would fail before the seam
  * is reached. `source` is declared so provenance never reads the script.
  */
class TapRunnerScratchSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("tap-runner-scratch-spec")

    private def testEnv: DatrisEnvironment = DatrisEnvironment(
        initialized = true,
        environment = "test",
        fileNotifierQueue = null,
        ttlFileNotifierQueueMessages = 0,
        pipelineTopic = null,
        pipelineTableName = "test-pipeline",
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
        tempDir = root.toString,
        tapTableName = "test-tap",
        tapLogTableName = "test-tap-log"
    )

    override def beforeAll(): Unit = TenantContext.set(testEnv)
    override def afterAll(): Unit = TenantContext.clear()

    private val CandidateScript = "# candidate script — unsaved\nprint('candidate')\n"

    private val tap = TapConfig(
        name = "prices",
        description = "d",
        targetPipeline = "orders",
        scriptPath = "taps/prices/does-not-exist-in-spec.py",
        source = "source.example",
        lastRunStatus = "failure",
        lastRunTime = "2026-10-16 09:00:00 UTC"
    )

    /** The stored pipeline: a JSON source landing in Postgres. */
    private val storedPipeline = PipelineConfig(
        name = "orders",
        source = Source(
            schemaProperties = SchemaProperties("db", new java.util.ArrayList[SchemaField]()),
            fileAttributes = FileAttributes(jsonAttributes = JsonAttributes())
        ),
        destination = Destination(database = Database(dbName = "db", schema = "public", table = "orders"))
    )

    private class FakeEffects extends TapRunner.Effects {
        val tapWrites = mutable.ArrayBuffer[TapConfig]()
        val commits = mutable.ArrayBuffer[TapState]()
        val runLogs = mutable.ArrayBuffer[TapRunLog]()
        val submits = mutable.ArrayBuffer[(String, PipelineConfig, String)]() // (pipeline, configOverride, publisherToken)
        override def writeTap(t: TapConfig): Unit = synchronized { tapWrites += t; () }
        override def readState(tapName: String): String = """{"cursor":"2026-10-15"}"""
        override def commitState(s: TapState): Unit = synchronized { commits += s; () }
        override def writeRunLog(log: TapRunLog): Unit = synchronized { runLogs += log; () }
        override def readPipeline(name: String): PipelineConfig = if (name == "orders") storedPipeline else null
        override def submit(
            staged: StagedPayload,
            sizeHint: Long,
            filename: String,
            pipeline: String,
            publisherToken: String,
            tapFeed: TapFeedInfo,
            configOverride: PipelineConfig
        ): String = synchronized { submits += ((pipeline, configOverride, publisherToken)); "pt-" + submits.size }
    }

    /** Three NDJSON records staged where a script run would put them. */
    private def stagedResult(): TapScriptResult = {
        val f = Files.createTempFile(root, "staged", ".ndjson")
        val body = """{"id":1,"price":10}""" + "\n" + """{"id":2,"price":11}""" + "\n" + """{"id":3,"price":12}""" + "\n"
        Files.write(f, body.getBytes(StandardCharsets.UTF_8))
        TapScriptResult(
            staged = StagedPayload(f.toString, StagedFormat.NdJson, 3L, Files.size(f), arraySource = true),
            recordCount = 3,
            error = null,
            columns = java.util.Arrays.asList("id", "price"),
            newState = """{"cursor":"2026-10-16"}"""
        )
    }

    /** Runs `body` with the fakes installed; returns the effects and the script texts the sidecar seam saw. */
    private def withFakes[T](body: FakeEffects => T): (FakeEffects, List[(String, String)], T) = {
        val fx = new FakeEffects
        val seen = mutable.ArrayBuffer[(String, String)]() // (script, mode)
        val savedEffects = TapRunner.effects
        val savedExec = TapScriptRunner.executeOverride
        TapRunner.effects = fx
        TapScriptRunner.executeOverride = (_: TapConfig, script: String, mode: String) => {
            seen.synchronized { seen += ((script, mode)) }
            stagedResult()
        }
        try {
            val out = body(fx)
            (fx, seen.toList, out)
        } finally {
            TapRunner.effects = savedEffects
            TapScriptRunner.executeOverride = savedExec
        }
    }

    private def runScratch(): (FakeEffects, List[(String, String)], TapScriptResult) =
        withFakes(_ => TapRunner.run(tap, mode = "scratch", scriptOverride = CandidateScript))

    test("scratch mode never writes lastRunStatus / lastRunTime and never commits the cursor") {
        val (fx, _, result) = runScratch()
        assert(result.error == null, "the scratch run itself succeeded: " + result.error)
        assert(fx.tapWrites.isEmpty, "no TapConfigIO.write in scratch mode (no running/success stamp): " + fx.tapWrites.map(_.lastRunStatus))
        assert(fx.commits.isEmpty, "the incremental cursor is never committed in scratch mode: " + fx.commits)
    }

    test("scratch mode feeds the pipeline with destination.scratch set and every other destination null") {
        val (fx, _, result) = runScratch()
        assert(fx.submits.size == 1, "exactly one pipeline job submitted: " + fx.submits)
        val (pipeline, cfg, publisherToken) = fx.submits.head
        assert(pipeline == "orders")
        assert(cfg != null, "the job runs with a config override, not the stored config")
        assert(cfg.name == "orders", "the override keeps the pipeline's name")
        assert(cfg.source == storedPipeline.source, "only the destination is swapped")
        val d = cfg.destination
        assert(d != null && d.scratch != null, "destination.scratch is set")
        assert(d.database == null, "database destination removed")
        assert(d.objectStore == null && d.restEndpoint == null && d.kafka == null && d.activeMQ == null)
        assert(d.qdrant == null && d.weaviate == null && d.pgvector == null && d.milvus == null && d.chroma == null)
        assert(d.authoritative == null)
        // Returned like a real run so the caller can poll the rollup.
        assert(publisherToken != null && result.publisherToken == publisherToken)
        assert(result.pipelineTokens != null && result.pipelineTokens.contains("pt-1"))
    }

    test("scriptOverride text is exactly what the sidecar seam receives") {
        val (_, seen, _) = runScratch()
        assert(seen.size == 1, "the script ran once: " + seen)
        assert(seen.head._1 == CandidateScript, "the candidate (unsaved) script ran, not the stored one")
    }

    test("the scratch run-log row has mode = scratch") {
        val (fx, _, _) = runScratch()
        assert(fx.runLogs.size == 1, "one run-log row: " + fx.runLogs)
        assert(fx.runLogs.head.mode == "scratch")
        assert(fx.runLogs.head.tapName == "prices")
    }

    // Control: the same fakes observe today's real-run side effects, so the
    // scratch assertions above are not vacuous.
    test("control: mode = run with the same fakes stamps the tap, commits the cursor and keeps the stored destination") {
        val (fx, _, _) = withFakes(_ => TapRunner.run(tap, mode = "run", scriptOverride = CandidateScript))
        assert(fx.tapWrites.map(_.lastRunStatus).toList == List("running", "success"))
        assert(fx.commits.size == 1)
        assert(fx.submits.size == 1 && fx.submits.head._2 == null, "a real run feeds the stored config")
        assert(fx.runLogs.head.mode == "run")
    }
}
