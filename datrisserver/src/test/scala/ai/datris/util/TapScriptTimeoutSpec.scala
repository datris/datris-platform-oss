package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}

/** Story: tap timeouts keep the script's logs, report progress and a partial
  * record count (plans/stories/tap-timeout-diagnostics.md).
  *
  * Today a timed-out tap throws `Tap script timed out after N seconds (...)`
  * BEFORE the partial stdout/stderr are read, so the tap log entry has the
  * error and nothing else and `recordCount` is 0 even when millions of rows
  * reached the staging file. After this story the failure carries the script's
  * own output and the number of records streamed before the kill.
  *
  * Seams this spec relies on (the timeout text itself already comes from the
  * run-timeout story's `timedOutMessage`; the new helper BUILDS ON it and must
  * not restate the prefix by hand):
  *
  * {{{
  * object TapScriptRunner {
  *     // Already here (tap-run-timeout story):
  *     case class TapTimeout(seconds: Int, envVar: String, label: String)
  *     private[util] def timeoutFor(mode: String): TapTimeout
  *     private[util] def timedOutMessage(t: TapTimeout): String
  *
  *     // New: the exception both timeout throw sites (the sidecar's `timedOut`
  *     // trailer and the in-process deadline) raise once they have read the
  *     // partial output. `logs` is the masked stderr (then stdout when
  *     // non-empty), in the same order a successful run reports them;
  *     // `partialRecords` is the newline count of the staged output file at the
  *     // time of the kill.
  *     class TapTimeoutException(message: String, val logs: String, val partialRecords: Long)
  *         extends DatrisException(message)
  *
  *     // Takes the TapTimeout in force (NOT a bare seconds Int) so the message
  *     // keeps the mode/env-var clause the run-timeout story added:
  *     //   timedOutMessage(t)                                  <- unchanged prefix + clause
  *     //   + "; P records were streamed before the kill (partial, nothing landed). Last output:\n"
  *     //   + the last 20 lines of the masked logs
  *     // Zero streamed -> "no records had been streamed yet".
  *     private[util] def timeoutFailure(
  *         timeout: TapTimeout,
  *         stdout: String,
  *         stderr: String,
  *         partialRecords: Long,
  *         secretValues: Seq[String]
  *     ): TapTimeoutException
  * }
  * }}}
  *
  * `runScriptStaged`'s catch turns that exception into
  * `TapScriptResult(null, partialRecords.toInt, maskedMessage, logs = maskedLogs)`,
  * which `TapRunner` already persists as `recordCount` / `logs` / `error`.
  *
  * The execution tests take the in-process lane (USE_TAP_RUNNER unset under
  * sbt), the same harness as TapStagingSpec / TapTimeoutSelectionSpec, and skip
  * when python3 is absent. The sidecar half of the contract is E2E-only.
  *
  * Companion spec: TapTimeoutSelectionSpec (which ceiling each mode picks) must
  * stay green — this spec never re-tests the selection, only the diagnostics.
  */
class TapScriptTimeoutSpec extends AnyFunSuite with BeforeAndAfterAll {

    private val root: Path = Files.createTempDirectory("tap-timeout-diagnostics-spec")

    private lazy val pythonAvailable: Boolean =
        try new ProcessBuilder("python3", "--version").start().waitFor() == 0
        catch { case _: Exception => false }

    private def env(scriptTimeout: Int, runTimeout: Int = 3600): DatrisEnvironment = DatrisEnvironment(
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

    private def scriptTap(name: String = "spec-timeout-diag-tap"): TapConfig =
        TapConfig(name = name, description = "spec", targetPipeline = null, scriptPath = "tap-scripts/unused.py")

    private def dropStaged(result: TapScriptResult): Unit =
        if (result != null && result.staged != null && result.staged.path != null) {
            val p = Paths.get(result.staged.path)
            Files.deleteIfExists(p)
            StagingArea.delete(p.getParent.getFileName.toString)
        }

    /** 30 numbered lines after three lines of real script output, as the story's
      * field incident had. */
    private val thirtyNumbered: String = (1 to 30).map(i => "line " + i).mkString("\n")
    private val longStderr: String =
        "[wrapper] calling fetch()\nresolved partition p\nrows=20000000\n" + thirtyNumbered

    // ================================================================
    // Acceptance 1 — prefix, last 20 log lines, partial count
    // ================================================================

    test("the timeout message keeps today's prefix, carries the last 20 log lines and the partial count") {
        val t = TapScriptRunner.TapTimeout(300, "TAP_SCRIPT_TIMEOUT_SECONDS", "test")
        val e = TapScriptRunner.timeoutFailure(t, "", longStderr, 1234567L, Nil)
        val msg = e.getMessage

        // The prefix the UI matches on (tap-create.component.ts: `timed out`) and
        // the mode/env-var clause the run-timeout story added are both intact —
        // the new detail is APPENDED, never a rewrite.
        assert(msg.startsWith("Tap script timed out after 300 seconds"), msg)
        assert(msg.startsWith(TapScriptRunner.timedOutMessage(t)), "the message must be built on timedOutMessage: " + msg)
        assert(msg.contains("test mode") && msg.contains("TAP_SCRIPT_TIMEOUT_SECONDS"), msg)

        // The partial count, labelled as partial (nothing landed).
        assert(msg.contains("1234567"), "the streamed-record count must be in the message: " + msg)
        assert(msg.toLowerCase.contains("partial"), "the count must be labelled partial: " + msg)

        // The tail: the last 20 lines of the logs, not the first of the 30.
        for (i <- 11 to 30) assert(msg.contains("line " + i), s"line $i must be in the last-20 tail: " + msg)
        assert(!msg.contains("line 1\n") && !msg.endsWith("line 1"), "line 1 of 30 must be trimmed off the tail: " + msg)
        assert(!msg.contains("resolved partition p"), "only the LAST 20 lines belong in the message tail: " + msg)

        // ... while `logs` keeps everything the script printed, exactly as a
        // successful run's logs would.
        assert(e.logs != null, "a timeout must carry the script's logs")
        assert(e.logs.contains("[wrapper] calling fetch()"), e.logs)
        assert(e.logs.contains("resolved partition p") && e.logs.contains("rows=20000000"), e.logs)
        assert(e.logs.contains("line 1"), "logs are the FULL output, untrimmed: " + e.logs)
        assert(e.partialRecords == 1234567L, String.valueOf(e.partialRecords))
        assert(e.isInstanceOf[DatrisException], "the timeout failure must stay a DatrisException")
    }

    test("zero streamed records says so instead of reporting 0") {
        val t = TapScriptRunner.TapTimeout(3600, "TAP_RUN_TIMEOUT_SECONDS", "run")
        val msg = TapScriptRunner.timeoutFailure(t, "", "[wrapper] calling fetch()", 0L, Nil).getMessage
        assert(msg.startsWith("Tap script timed out after 3600 seconds"), msg)
        assert(msg.contains("no records had been streamed yet"), msg)
    }

    // ================================================================
    // Acceptance 2 — masking
    // ================================================================

    test("secret values are masked in both the message tail and the logs") {
        val secret = "sk-live-0123456789abcdef"
        val t = TapScriptRunner.TapTimeout(300, "TAP_SCRIPT_TIMEOUT_SECONDS", "test")
        val e = TapScriptRunner.timeoutFailure(
            t,
            "envelope-ish stdout with " + secret,
            "[wrapper] calling fetch()\nauthorization: Bearer " + secret,
            42L,
            Seq(secret)
        )
        assert(!e.getMessage.contains(secret), "the secret leaked into the timeout message: " + e.getMessage)
        assert(!e.logs.contains(secret), "the secret leaked into the persisted logs: " + e.logs)
        assert(e.getMessage.contains("Bearer"), "only the secret is masked, the surrounding line stays: " + e.getMessage)
        assert(e.logs.contains("[wrapper] calling fetch()"), e.logs)
    }

    // ================================================================
    // Acceptance 3 — the in-process lane, real wrapper, real timeout
    // ================================================================

    test("a timed-out run keeps the script's logs and reports the records streamed before the kill") {
        assume(pythonAvailable, "python3 not available")
        // mode = "test" so the short test ceiling (2 s) is the one in force.
        val script =
            """import sys, time
              |
              |def fetch():
              |    print("start marker", file=sys.stderr, flush=True)
              |    i = 0
              |    while True:
              |        i += 1
              |        yield {"id": i, "pad": "p" * 200}
              |""".stripMargin

        val result = withEnv(env(scriptTimeout = 2))(TapScriptRunner.runScript(scriptTap(), script, mode = "test"))
        try {
            assert(result.error != null, "an endless generator must hit the 2 s test ceiling")
            assert(result.error.startsWith("Tap script timed out after 2 seconds"), result.error)
            assert(result.error.contains("start marker"), "the script's own output must survive the kill: " + result.error)
            assert(result.error.toLowerCase.contains("partial"), result.error)

            assert(result.logs != null, "a timed-out run must carry logs, as a successful run does")
            assert(result.logs.contains("start marker"), result.logs)

            assert(result.recordCount > 0, "records reached the staging file before the kill, got " + result.recordCount)
            // The count in the message is the count on the result — one source
            // (the newline count of the staged file), not two estimates.
            assert(result.error.contains(result.recordCount.toString), "message count must equal recordCount: " + result.error)
            assert(result.staged == null, "nothing landed: a timeout is still a failure with no payload")
        } finally dropStaged(result)
    }

    // ================================================================
    // Acceptance 4 — no regression in the non-timeout catch order
    // ================================================================

    test("a script that raises immediately still fails with today's exit-code message, not the timeout one") {
        assume(pythonAvailable, "python3 not available")
        val result = withEnv(env(scriptTimeout = 30))(
            TapScriptRunner.runScript(
                scriptTap(),
                """def fetch():
                  |    raise RuntimeError("boom")
                  |""".stripMargin,
                mode = "test"
            )
        )
        try {
            assert(result.error != null, "a raising script must fail")
            assert(result.error.startsWith("Tap script failed (exit code 1)"), result.error)
            assert(!result.error.contains("timed out"), "a crash must not be reported as a timeout: " + result.error)
            assert(result.recordCount == 0, "only a timeout carries a partial count, got " + result.recordCount)
        } finally dropStaged(result)
    }
}
