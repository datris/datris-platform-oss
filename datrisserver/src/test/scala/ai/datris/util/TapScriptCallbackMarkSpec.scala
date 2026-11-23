package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.TapRunTokens
import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Story: Tap runs and owner access, report-only release
  * (plans/stories/tap-runs-hold-owner-scope.md), Design "Callback use":
  * `TapScriptRunner` revokes the run's token when the run ends and carries
  * what `TapRunTokens.revoke` answered on `TapScriptResult.usedCallback`.
  *
  * Exercised with the real wrapper and python3, in-process (no sidecar), like
  * TapStagingSpec. A real callback would need the platform listening on
  * localhost:8080, so the spec stands in for it: the script hands its
  * DATRIS_PLATFORM_TOKEN to the test through a file and waits; the test
  * presents the token the way every callback request does
  * (`TapRunTokens.resolve`, which APIKeyValidator.resolveKey calls) and lets
  * the script finish. Skipped when python3 is absent.
  *
  * Pinned (does not exist at 4f38ae31):
  * {{{
  * case class TapScriptResult(..., usedCallback: Boolean = false)
  * }}}
  */
class TapScriptCallbackMarkSpec extends AnyFunSuite {

    private implicit val ec: ExecutionContext = ExecutionContext.global

    private val root: Path = Files.createTempDirectory("tap-callback-mark-spec")

    private lazy val pythonAvailable: Boolean =
        try new ProcessBuilder("python3", "--version").start().waitFor() == 0
        catch { case _: Exception => false }

    private val env: DatrisEnvironment = DatrisEnvironment(
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
        tapScriptTimeoutSeconds = 60,
        tempDir = root.toString
    )

    private def scriptTap(name: String): TapConfig =
        TapConfig(name = name, description = "spec", targetPipeline = null, scriptPath = "tap-scripts/unused.py")

    private def dropStaged(result: TapScriptResult): Unit =
        if (result != null && result.staged != null && result.staged.path != null) {
            val p = Paths.get(result.staged.path)
            Files.deleteIfExists(p)
            StagingArea.delete(p.getParent.getFileName.toString)
        }

    /** Writes the token to $DATRIS_TAP_PARAM_HANDOFF, then waits (up to 20 s)
      * until the test removes the file, then returns one record. */
    private val HandOffScript =
        """import os, time
          |def fetch():
          |    path = os.environ["DATRIS_TAP_PARAM_HANDOFF"]
          |    with open(path + ".part", "w") as f:
          |        f.write(os.environ.get("DATRIS_PLATFORM_TOKEN", ""))
          |    os.rename(path + ".part", path)
          |    for _ in range(400):
          |        if not os.path.exists(path):
          |            break
          |        time.sleep(0.05)
          |    return [{"id": 1}]
          |""".stripMargin

    private val QuietScript =
        """def fetch():
          |    return [{"id": 1}]
          |""".stripMargin

    private def withEnv[T](body: => T): T = {
        TenantContext.set(env)
        try body
        finally TenantContext.clear()
    }

    test("a run whose script presented its token on the callback reports usedCallback = true") {
        assume(pythonAvailable, "python3 not available")
        val handoff = root.resolve("handoff-" + System.nanoTime())
        val run = Future(withEnv(TapScriptRunner.runScript(scriptTap("mark-used"), HandOffScript, params = Map("HANDOFF" -> handoff.toString))))
        val deadline = System.currentTimeMillis() + 30000
        while (!Files.exists(handoff) && System.currentTimeMillis() < deadline && !run.isCompleted) Thread.sleep(20)
        assert(Files.exists(handoff), "the script never handed its token over: " + run.value)
        val token = new String(Files.readAllBytes(handoff), StandardCharsets.UTF_8).trim
        // what a callback request does with the token
        assert(TapRunTokens.resolve(token).map(_.label).contains("tap:mark-used"), "the token is live during the run")
        Files.delete(handoff)
        val result = Await.result(run, 60.seconds)
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.recordCount == 1)
            assert(result.usedCallback, "the callback was used during the run")
            assert(TapRunTokens.lookup(token).isEmpty, "the token is revoked when the run ends, as before")
        } finally dropStaged(result)
    }

    test("a run whose script never called back reports usedCallback = false, and its output is unchanged") {
        assume(pythonAvailable, "python3 not available")
        val result = withEnv(TapScriptRunner.runScript(scriptTap("mark-quiet"), QuietScript))
        try {
            assert(result.error == null, String.valueOf(result.error))
            assert(result.recordCount == 1 && result.dataType == "json")
            assert(!result.usedCallback)
        } finally dropStaged(result)
    }

    test("a failing script reports the mark too, and still fails as before") {
        assume(pythonAvailable, "python3 not available")
        val result = withEnv(TapScriptRunner.runScript(scriptTap("mark-fail"), "def fetch():\n    raise RuntimeError('boom')\n"))
        assert(result.error != null && result.error.contains("boom"), String.valueOf(result.error))
        assert(!result.usedCallback)
    }
}
