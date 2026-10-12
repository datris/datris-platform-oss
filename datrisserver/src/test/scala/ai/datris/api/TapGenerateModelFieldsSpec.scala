package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.{TapGenerateResult, TapGenerationProgress}
import ai.datris.util.TapGenerationProgress.Progress
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

/** Story: Show the AI model in use (plans/stories/show-ai-model.md).
  *
  * `GET /api/v1/tap/generate/status` and `POST /api/v1/tap/generate` gain
  * `provider` and `model` — the CodeGen config that actually generates the
  * script (`DatrisEnvironment.aiConfigForCodegen`, which falls back to the
  * primary when no CodeGen slot is set). Seams this spec relies on:
  *
  * {{{
  * object TapGenerationProgress {
  *     case class Progress(phase: String, attempt: Int, startedAtMs: Long, phaseStartedAtMs: Long,
  *                         provider: String, model: String)
  *     def start(tapName: String, provider: String, model: String): Unit
  * }
  * case class TapGenerateResult(script, packages, scriptPath, injectedPrompts = ...,
  *                              provider: String = "", model: String = "")
  * object TapAPIController {
  *     private[api] def generateStatusBody(p: Option[Progress], nowMs: Long): java.util.Map[String, Any]
  * }
  * }}}
  *
  * Reaching the endpoints themselves needs a servlet request and an AI call;
  * the live responses are covered by the story's manual Verify block. */
class TapGenerateModelFieldsSpec extends AnyFunSuite with BeforeAndAfterEach {

    private def env(primary: AIConfig, codegen: Option[AIConfig]): DatrisEnvironment = DatrisEnvironment(
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
        aiConfig = primary,
        codegenAiConfig = codegen,
        aiEnabled = true,
        embeddingSecretName = null,
        qdrantSecretName = null,
        weaviateSecretName = null,
        milvusSecretName = null,
        chromaSecretName = null,
        pgvectorSecretName = null,
        multiTenant = false
    )

    private val primary = AIConfig("anthropic", "", "claude-fable-5-1", "")
    private val codegen = AIConfig("openai", "", "gpt-5.5", "")

    private def withEnv[T](e: DatrisEnvironment)(body: => T): T = {
        TenantContext.set(e)
        try body
        finally TenantContext.clear()
    }

    override def afterEach(): Unit = TenantContext.clear()

    // ================================================================
    // Acceptance 1 — codegen fallback resolves to the primary
    // ================================================================

    test("with no CodeGen slot, aiConfigForCodegen resolves to the primary (what actually runs)") {
        withEnv(env(primary, None)) {
            val c = DatrisEnvironment.aiConfigForCodegen
            assert(c.provider == "anthropic")
            assert(c.model == "claude-fable-5-1")
        }
    }

    test("with a CodeGen slot, aiConfigForCodegen resolves to it and chat stays on the primary") {
        withEnv(env(primary, Some(codegen))) {
            val c = DatrisEnvironment.aiConfigForCodegen
            assert(c.provider == "openai")
            assert(c.model == "gpt-5.5")
            assert(DatrisEnvironment.aiConfigForChat.model == "claude-fable-5-1")
        }
    }

    // ================================================================
    // Acceptance 1 — status body carries provider/model when active
    // ================================================================

    test("an active status body carries active, provider, model and elapsedSeconds") {
        val t = 1760000000000L
        val body = TapAPIController.generateStatusBody(
            Some(Progress("calling-model", 1, t, t, "openai", "gpt-5.5")),
            t + 5000
        )
        assert(body.get("active") == java.lang.Boolean.TRUE)
        assert(body.get("provider") == "openai")
        assert(body.get("model") == "gpt-5.5")
        assert(body.get("phase") == "calling-model")
        assert(body.get("elapsedSeconds").toString.toLong == 5L)
        assert(body.get("phaseElapsedSeconds").toString.toLong == 5L)
        assert(body.get("attempt").toString.toInt == 1)
    }

    test("an inactive status body is exactly {active=false}, with no provider or model key") {
        val t = 1760000000000L
        val body = TapAPIController.generateStatusBody(None, t)
        assert(body.get("active") == java.lang.Boolean.FALSE)
        assert(!body.containsKey("provider"))
        assert(!body.containsKey("model"))
        assert(body.size() == 1, body.toString)
    }

    // ================================================================
    // Design — provider/model captured at start, visible from the first tick
    // ================================================================

    test("TapGenerationProgress.start records provider and model so the first poll can show them") {
        withEnv(env(primary, Some(codegen))) {
            val tap = "spec-show-ai-model-progress"
            TapGenerationProgress.start(tap, "openai", "gpt-5.5")
            try {
                val p = TapGenerationProgress.get(tap)
                assert(p.isDefined)
                assert(p.get.provider == "openai")
                assert(p.get.model == "gpt-5.5")
                assert(p.get.phase == "calling-model")
                // A phase change keeps the captured provider/model.
                TapGenerationProgress.phase(tap, "retrying-format", 2)
                val q = TapGenerationProgress.get(tap).get
                assert(q.provider == "openai" && q.model == "gpt-5.5")
            } finally TapGenerationProgress.finish(tap)
            assert(TapGenerationProgress.get(tap).isEmpty)
        }
    }

    // ================================================================
    // Backward compat — older callers of TapGenerateResult still compile
    // ================================================================

    test("TapGenerateResult defaults provider and model to empty strings") {
        val r = TapGenerateResult("def fetch(): return []", new java.util.ArrayList[String](), "tap-scripts/x.py")
        assert(r.provider == "")
        assert(r.model == "")
        val withModel = r.copy(provider = "anthropic", model = "claude-opus-5-5")
        assert(withModel.provider == "anthropic" && withModel.model == "claude-opus-5-5")
    }
}
