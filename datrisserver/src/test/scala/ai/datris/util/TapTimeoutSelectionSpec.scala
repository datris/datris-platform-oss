package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}

/** Story: taps get a run timeout separate from the test ceiling, and the
  * timeout error names its knob (plans/stories/tap-run-timeout.md).
  *
  * Today `tapScriptTimeoutSeconds` (300) is the single wall-clock ceiling for
  * mode=test, mode=run, cron runs and HTTP-tap calls, and the failure text
  * (`Tap script timed out after 300 seconds`) names no variable. After this
  * story there are two ceilings and the message says which mode ran and which
  * variable to raise.
  *
  * Seams this spec relies on:
  *
  * {{{
  * case class DatrisEnvironment(..., tapScriptTimeoutSeconds: Int, tapRunTimeoutSeconds: Int = 3600, ...)
  *
  * object TapScriptRunner {
  *     // The ceiling in force for one execution, plus the knob to raise it.
  *     case class TapTimeout(seconds: Int, envVar: String, label: String)
  *
  *     // mode == "test" -> (tapScriptTimeoutSeconds, "TAP_SCRIPT_TIMEOUT_SECONDS", "test")
  *     // anything else  -> (tapRunTimeoutSeconds,    "TAP_RUN_TIMEOUT_SECONDS",    "run")
  *     private[util] def timeoutFor(mode: String): TapTimeout
  *
  *     // The one place both timeout throws (sidecar trailer + in-process) get
  *     // their text from.
  *     private[util] def timedOutMessage(t: TapTimeout): String
  *
  *     // "unset" resolution for the boot-time property (StartupRunner passes
  *     // optionalInt's -1 when TAP_RUN_TIMEOUT_SECONDS / tapRunTimeoutSeconds
  *     // is blank): max(3600, tapScriptTimeoutSeconds), so an install that
  *     // raised the old single knob never gets a SHORTER real-run ceiling.
  *     private[util] def resolveRunTimeoutSeconds(rawRunTimeoutSeconds: Int, scriptTimeoutSeconds: Int): Int
  *
  *     def run(tapConfig: TapConfig, testLimit: Int = 0, params: Map[String, String] = Map.empty,
  *             previousState: String = null, mode: String = "run"): TapScriptResult
  *
  *     private[util] def runScript(tapConfig: TapConfig, scriptContent: String, testLimit: Int = 0,
  *                                 params: Map[String, String] = Map.empty, previousState: String = null,
  *                                 mode: String = "run"): TapScriptResult
  * }
  * }}}
  *
  * Timeout wording (story Step 2):
  * `Tap script timed out after 300 seconds (test mode; raise TAP_SCRIPT_TIMEOUT_SECONDS)`
  * `Tap script timed out after 3600 seconds (run mode; raise TAP_RUN_TIMEOUT_SECONDS, or chunk the source range via run_tap params)`
  *
  * The execution tests take the in-process lane (USE_TAP_RUNNER unset in sbt),
  * same harness as TapStagingSpec, and skip when python3 is absent.
  */
class TapTimeoutSelectionSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("tap-timeout-spec")

    private lazy val pythonAvailable: Boolean =
        try new ProcessBuilder("python3", "--version").start().waitFor() == 0
        catch { case _: Exception => false }

    private def env(scriptTimeout: Int, runTimeout: Int): DatrisEnvironment = DatrisEnvironment(
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
        mongoDbConfig = MongoDBConfig("mongodb://unused", "datris", "datris"),
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
        tapScriptTimeoutSeconds = scriptTimeout,
        tapRunTimeoutSeconds = runTimeout,
        tempDir = root.toString
    )

    private def withEnv[T](e: DatrisEnvironment)(body: => T): T = {
        TenantContext.set(e)
        try body
        finally TenantContext.clear()
    }

    override def afterAll(): Unit = TenantContext.clear()

    private def scriptTap(name: String = "spec-timeout-tap"): TapConfig =
        TapConfig(name = name, description = "spec", targetPipeline = null, scriptPath = "tap-scripts/unused.py")

    private def dropStaged(result: TapScriptResult): Unit =
        if (result != null && result.staged != null && result.staged.path != null) {
            val p = Paths.get(result.staged.path)
            Files.deleteIfExists(p)
            StagingArea.delete(p.getParent.getFileName.toString)
        }

    // ================================================================
    // Acceptance 1 — which ceiling each mode picks
    // ================================================================

    test("mode=test selects tapScriptTimeoutSeconds; mode=run and any other mode select tapRunTimeoutSeconds") {
        withEnv(env(scriptTimeout = 300, runTimeout = 3600)) {
            val t = TapScriptRunner.timeoutFor("test")
            assert(t.seconds == 300, "a test is bounded by the test ceiling")
            assert(t.envVar == "TAP_SCRIPT_TIMEOUT_SECONDS")
            assert(t.label == "test")

            val r = TapScriptRunner.timeoutFor("run")
            assert(r.seconds == 3600, "a real run gets the run ceiling, not the test one")
            assert(r.envVar == "TAP_RUN_TIMEOUT_SECONDS")
            assert(r.label == "run")

            // Cron runs arrive as mode="run" from TapScheduler; anything the
            // callers have not thought of must land on the run ceiling too —
            // never silently on the short test ceiling.
            for (mode <- Seq("cron", "manual", "", null)) {
                val other = TapScriptRunner.timeoutFor(mode)
                assert(other.seconds == 3600, s"mode '$mode' must use the run ceiling")
                assert(other.envVar == "TAP_RUN_TIMEOUT_SECONDS", s"mode '$mode' must name the run variable")
                assert(other.label == "run")
            }
        }
    }

    // ================================================================
    // Acceptance 4 — unset run ceiling never shortens an upgraded install
    // ================================================================

    test("unset tapRunTimeoutSeconds resolves to max(3600, tapScriptTimeoutSeconds)") {
        // Default install: 300 s test ceiling -> 1 h real runs.
        assert(TapScriptRunner.resolveRunTimeoutSeconds(-1, 300) == 3600)
        // An install that raised the old single knob to make long real runs
        // work keeps that longer ceiling on upgrade.
        assert(TapScriptRunner.resolveRunTimeoutSeconds(-1, 7200) == 7200)
        // An explicit value wins, in both directions.
        assert(TapScriptRunner.resolveRunTimeoutSeconds(600, 300) == 600)
        assert(TapScriptRunner.resolveRunTimeoutSeconds(120, 7200) == 120)
    }

    // ================================================================
    // Acceptance 3 — the message names the mode and the knob
    // ================================================================

    test("run-mode timeout text names run mode, TAP_RUN_TIMEOUT_SECONDS and run_tap params") {
        withEnv(env(scriptTimeout = 300, runTimeout = 3600)) {
            val msg = TapScriptRunner.timedOutMessage(TapScriptRunner.timeoutFor("run"))
            assert(msg.contains("timed out after 3600 seconds"), msg)
            assert(msg.contains("run mode"), msg)
            assert(msg.contains("TAP_RUN_TIMEOUT_SECONDS"), msg)
            assert(msg.contains("run_tap params"), msg)
            assert(!msg.contains("TAP_SCRIPT_TIMEOUT_SECONDS"), "a real run must not point at the test knob: " + msg)

            val testMsg = TapScriptRunner.timedOutMessage(TapScriptRunner.timeoutFor("test"))
            assert(testMsg.contains("timed out after 300 seconds"), testMsg)
            assert(testMsg.contains("test mode"), testMsg)
            assert(testMsg.contains("TAP_SCRIPT_TIMEOUT_SECONDS"), testMsg)
            assert(!testMsg.contains("TAP_RUN_TIMEOUT_SECONDS"), "a test must not point at the run knob: " + testMsg)
        }
    }

    test("the sidecar trailer timedOut path produces the same text as the in-process path") {
        // Both throw sites (executeViaRunner's `timedOut: true` trailer and
        // executeWithTimeout's own deadline) must build the message from the
        // shared helper — otherwise an isolated deployment (USE_TAP_RUNNER=true,
        // which is compose/prod) keeps the old variable-less text while sbt
        // shows the new one. The sidecar cannot be exercised in-process, so pin
        // it at the source: no raw literal left, one helper, both call sites.
        val source = new String(
            Files.readAllBytes(repoRoot.resolve("datrisserver/src/main/scala/ai/datris/util/TapScriptRunner.scala")),
            java.nio.charset.StandardCharsets.UTF_8
        )
        val rawThrows = """Tap script timed out after " \+""".r.findAllIn(source).size
        assert(rawThrows == 0, "both timeout throws must use timedOutMessage, not a hand-built string")
        assert(source.contains("def timedOutMessage"), "the shared helper is missing from TapScriptRunner")
        assert(
            "timedOutMessage\\(".r.findAllIn(source).size >= 3,
            "timedOutMessage must be called from both the sidecar-trailer path and the in-process path"
        )
    }

    private def repoRoot: Path = {
        var p = Paths.get("").toAbsolutePath
        while (p != null && !Files.isRegularFile(p.resolve("docker-compose.yml"))) p = p.getParent
        assert(p != null, "could not locate the repository root from " + Paths.get("").toAbsolutePath)
        p
    }

    // ================================================================
    // Acceptance 2 — a real execution stops at the ceiling for its mode
    // ================================================================

    test("a script that sleeps 3 s with a 1 s test ceiling and a 10 s run ceiling fails in test mode and succeeds in run mode") {
        assume(pythonAvailable, "python3 not available")
        val script =
            """import time
              |
              |def fetch():
              |    time.sleep(3)
              |    return [{"id": 1}, {"id": 2}]
              |""".stripMargin

        val testResult = withEnv(env(scriptTimeout = 1, runTimeout = 10))(
            TapScriptRunner.runScript(scriptTap(), script, mode = "test")
        )
        try {
            assert(testResult.error != null, "a 3 s script must not survive a 1 s test ceiling")
            assert(testResult.error.contains("timed out after 1 seconds"), testResult.error)
            assert(testResult.error.contains("test mode"), testResult.error)
            assert(testResult.error.contains("TAP_SCRIPT_TIMEOUT_SECONDS"), testResult.error)
        } finally dropStaged(testResult)

        val runResult = withEnv(env(scriptTimeout = 1, runTimeout = 10))(
            TapScriptRunner.runScript(scriptTap(), script, mode = "run")
        )
        try {
            assert(runResult.error == null, "the same script must finish under the 10 s run ceiling: " + String.valueOf(runResult.error))
            assert(runResult.recordCount == 2)
        } finally dropStaged(runResult)
    }
}
