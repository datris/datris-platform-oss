package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.scalatest.funsuite.AnyFunSuite

/** Story: agents size a tap run against the budgets actually in force, not the
  * documented defaults (plans/stories/tap-sizing-effective-budgets.md).
  *
  * Today the disk budget is resolved inside `StagingArea.payloadBudgetMB` and
  * the two timeout ceilings sit on `DatrisEnvironment`, but nothing can say
  * WHERE a value came from, so every prompt quotes the documented default. This
  * story adds one place both channels (Assistant prompt, `/api/v1/version`)
  * read from.
  *
  * Seams this spec relies on (story Files / Steps 1-2):
  *
  * {{{
  * case class DatrisEnvironment(
  *     ...,
  *     tapScriptTimeoutSecondsSet: Boolean = false,   // TAP_SCRIPT_TIMEOUT_SECONDS was explicit
  *     tapRunTimeoutSecondsSet: Boolean = false,      // TAP_RUN_TIMEOUT_SECONDS was explicit
  *     ...)
  *
  * object TapBudgets {
  *     case class Effective(
  *         pipelineMaxPayloadMB: Int,
  *         pipelineMaxPayloadMBSource: String,        // "env" | "deprecated-alias" | "default"
  *         tapScriptTimeoutSeconds: Int,
  *         tapScriptTimeoutSecondsSource: String,     // "env" | "default"
  *         tapRunTimeoutSeconds: Int,
  *         tapRunTimeoutSecondsSource: String)        // "env" | "default"
  *
  *     def effective(env: DatrisEnvironment): Effective
  *     def describeDisk(b: Effective): String
  *     def describeRun(b: Effective): String
  * }
  * }}}
  *
  * Disk precedence is the one already in `StagingArea.payloadBudgetMB`
  * (StagingArea.scala l.63-71): explicit `pipelineMaxPayloadMB` when >= 0, else
  * the deprecated `tapMaxOutputMB` when >= 0, else
  * `StagingArea.DefaultPayloadBudgetMB` (4096). `0` keeps meaning unlimited and
  * is an explicit value, not "unset". No default changes (story Out of scope).
  */
class TapBudgetsSpec extends AnyFunSuite {

    private def env(
        payloadMB: Int = -1,
        maxOutputMB: Int = -1,
        scriptTimeout: Int = 300,
        scriptSet: Boolean = false,
        runTimeout: Int = 3600,
        runSet: Boolean = false
    ): DatrisEnvironment = DatrisEnvironment(
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
        tapScriptTimeoutSecondsSet = scriptSet,
        tapRunTimeoutSeconds = runTimeout,
        tapRunTimeoutSecondsSet = runSet,
        tapMaxOutputMB = maxOutputMB,
        pipelineMaxPayloadMB = payloadMB
    )

    // ------------------------------------------------------------- disk ---

    test("an explicit PIPELINE_MAX_PAYLOAD_MB is the value in force, sourced from env") {
        val b = TapBudgets.effective(env(payloadMB = 16384))
        assert(b.pipelineMaxPayloadMB == 16384)
        assert(b.pipelineMaxPayloadMBSource == "env")
    }

    test("the deprecated TAP_MAX_OUTPUT_MB alias is used when the new variable is unset") {
        val b = TapBudgets.effective(env(payloadMB = -1, maxOutputMB = 2048))
        assert(b.pipelineMaxPayloadMB == 2048)
        assert(b.pipelineMaxPayloadMBSource == "deprecated-alias")
    }

    test("neither variable set falls back to the documented default, sourced from default") {
        val b = TapBudgets.effective(env(payloadMB = -1, maxOutputMB = -1))
        assert(b.pipelineMaxPayloadMB == StagingArea.DefaultPayloadBudgetMB)
        assert(b.pipelineMaxPayloadMB == 4096)
        assert(b.pipelineMaxPayloadMBSource == "default")
    }

    test("an explicit 0 is unlimited and still an explicit value") {
        val b = TapBudgets.effective(env(payloadMB = 0))
        assert(b.pipelineMaxPayloadMB == 0)
        assert(b.pipelineMaxPayloadMBSource == "env")
        assert(TapBudgets.describeDisk(b).toLowerCase.contains("unlimited"))
    }

    test("describeDisk names the value in force and keeps 4096 as the default") {
        val text = TapBudgets.describeDisk(TapBudgets.effective(env(payloadMB = 16384)))
        assert(text.contains("currently 16384 MB on this install"))
        assert(text.contains("default 4096"))
    }

    // --------------------------------------------------------- timeouts ---

    test("an unset run timeout is sourced from default, an explicit one from env") {
        val unset = TapBudgets.effective(env(runTimeout = 3600, runSet = false))
        assert(unset.tapRunTimeoutSeconds == 3600)
        assert(unset.tapRunTimeoutSecondsSource == "default")

        val set = TapBudgets.effective(env(runTimeout = 7200, runSet = true))
        assert(set.tapRunTimeoutSeconds == 7200)
        assert(set.tapRunTimeoutSecondsSource == "env")
    }

    test("an unset script timeout is sourced from default, an explicit one from env") {
        val unset = TapBudgets.effective(env(scriptTimeout = 300, scriptSet = false))
        assert(unset.tapScriptTimeoutSeconds == 300)
        assert(unset.tapScriptTimeoutSecondsSource == "default")

        val set = TapBudgets.effective(env(scriptTimeout = 900, scriptSet = true))
        assert(set.tapScriptTimeoutSeconds == 900)
        assert(set.tapScriptTimeoutSecondsSource == "env")
    }

    test("describeRun names the value in force and keeps 3600 as the default") {
        val text = TapBudgets.describeRun(TapBudgets.effective(env(runTimeout = 7200, runSet = true)))
        assert(text.contains("currently 7200 s on this install"))
        assert(text.contains("default 3600"))
    }
}
