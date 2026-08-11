package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonObject
import ai.datris.model.{AIConfig, DatrisEnvironment}
import ai.datris.util.SecretsUtil

import org.slf4j.{Logger, LoggerFactory}

/** Provider detection and routing: default endpoints/models, token-limit field
  * selection, Responses-API routing, provider quirk tables (sampling params,
  * extended thinking), API-key resolution, and context sizing.
  * Extracted verbatim from AIUtil — AIUtil remains the public facade.
  */
object AIProviders {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // OpenAI's Responses API (POST /v1/responses) is used by the codex family
    // and is also valid for newer reasoning models. We auto-route when the
    // model name contains "codex" or the configured endpoint already points at
    // /v1/responses. Request/response shapes are different from chat/completions
    // (input + instructions + max_output_tokens; output[].content[].text).
    // Deliberately restricted to provider "openai": Azure's /openai/v1/responses
    // availability is region/model dependent, and azure `model` values are
    // deployment names, so the codex sniff would misfire — azure always speaks
    // chat/completions.
    private[aiutil] def usesResponsesApi(aiConfig: AIConfig): Boolean = {
        if (aiConfig == null || !aiConfig.provider.toLowerCase.equals("openai")) return false
        val model = Option(aiConfig.model).map(_.toLowerCase).getOrElse("")
        val endpoint = Option(aiConfig.endpoint).map(_.toLowerCase).getOrElse("")
        model.contains("codex") || endpoint.contains("/v1/responses")
    }

    private[aiutil] def responsesEndpointFor(aiConfig: AIConfig): String = {
        val ep = aiConfig.endpoint
        if (ep == null || ep.isEmpty) "https://api.openai.com/v1/responses"
        else if (ep.toLowerCase.contains("/v1/responses")) ep
        else ep.replaceFirst("/v1/chat/completions$", "/v1/responses")
            .replaceFirst("/v1/completions$", "/v1/responses")
    }

    // OpenAI reasoning / GPT-5 family models reject `max_tokens` and require
    // `max_completion_tokens`. Detect by model-name prefix so we stay compatible
    // with both the legacy (gpt-4*, gpt-3.5*) and newer parameter contracts.
    private def openAiTokenField(model: String): String = {
        val m = if (model == null) "" else model.toLowerCase
        if (
            m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3") ||
            m.startsWith("o4") || m.startsWith("o5")
        ) "max_completion_tokens"
        else "max_tokens"
    }

    private[aiutil] def addTokenLimit(requestObj: JsonObject, provider: String, model: String, maxTokens: Int): Unit = {
        // Azure always gets max_completion_tokens: the `model` field is a
        // deployment name (arbitrary, so the prefix heuristic can't apply) and
        // Azure has deprecated max_tokens in favor of max_completion_tokens
        // across current API versions.
        val field = provider.toLowerCase match {
            case "openai" => openAiTokenField(model)
            case "azure" => "max_completion_tokens"
            // bedrock speaks the Anthropic Messages shape — explicit for clarity.
            case "bedrock" => "max_tokens"
            case _ => "max_tokens"
        }
        requestObj.addProperty(field, maxTokens)
    }

    /** Providers that speak the Anthropic Messages API wire shape (top-level
      * `system`, content-block responses, Anthropic tool-use). Bedrock serves
      * Claude over this same shape — only auth (SigV4) and endpoint differ,
      * which BedrockSupport handles at request-build time. */
    private[aiutil] def usesAnthropicWire(provider: String): Boolean = {
        val p = if (provider == null) "" else provider.toLowerCase
        p == "anthropic" || p == "bedrock"
    }

    /** Anthropic removed sampling parameters (`temperature`/`top_p`/`top_k`) on the
      * adaptive-thinking-only models — sending any of them returns a 400. Adaptive
      * thinking doesn't need `temperature` anyway, so we simply omit it for these
      * models. Older thinking-capable models (Sonnet 4.6, Opus 4.6, Haiku 4.5) still
      * require/accept `temperature: 1.0` with thinking on, so their behavior is
      * unchanged. Match the families that reject sampling params: Fable, Mythos,
      * Opus 4.7, Opus 4.8, Opus 5 (and later Opus), Sonnet 5 (and later Sonnet). */
    private[aiutil] def rejectsSamplingParams(model: String): Boolean = {
        if (model == null) return false
        val m = model.toLowerCase
        m.contains("fable") || m.contains("mythos") ||
        m.contains("opus-4-7") || m.contains("opus-4-8") ||
        m.contains("opus-5") || m.contains("sonnet-5")
    }

    /** Whether a given AIConfig supports extended thinking — Claude 4.x, whether
      * served directly by Anthropic or through Bedrock. The per-model thinking
      * form (adaptive vs enabled vs none) is still discovered at call time by
      * AIStreaming's fallback ladder, so a non-thinking model behind either
      * provider degrades gracefully. */
    def supportsExtendedThinking(aiConfig: AIConfig): Boolean = {
        if (aiConfig == null) return false
        usesAnthropicWire(aiConfig.provider)
    }

    /** Resolve an apiKey for an AI provider section. Used by every AI-config loader
      * (ai-primary, codegen, embedding, web-search) so the same fallback applies
      * uniformly, in priority order:
      *
      *   1. The shared per-provider key store at `{env}/ai-keys` (fields
      *      `anthropicApiKey` / `openaiApiKey` / `azureApiKey`). This is the authoritative home for
      *      provider keys — they live here independent of which slot uses each
      *      provider, so switching a slot's provider back and forth never loses the
      *      other provider's key. Matches the UI's "enter each key once" model.
      *   2. The slot secret's own inline `apiKey` if non-empty — legacy / pre-store
      *      deployments that stored the key on the slot itself.
      *   3. The matching `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` /
      *      `AZURE_OPENAI_API_KEY` env var, but ONLY
      *      in single-tenant mode — env vars hold the platform's keys, and in
      *      multi-tenant deployments those keys belong to Datris, not to each
      *      tenant. Multi-tenant tenants must provide their own keys explicitly.
      *
      * Returns the empty string when none is available; callers decide whether
      * that's fatal (ai-primary) or skippable (web-search). */
    def resolveApiKey(rawKey: String, provider: String, multiTenant: Boolean, env: String): String = {
        val storeKey = providerKeyFromStore(env, provider)
        if (storeKey.nonEmpty) return storeKey
        if (rawKey != null && rawKey.nonEmpty) return rawKey
        if (multiTenant) return ""
        provider.toLowerCase match {
            case "anthropic" => sys.env.getOrElse("ANTHROPIC_API_KEY", "")
            case "openai" => sys.env.getOrElse("OPENAI_API_KEY", "")
            case "azure" => sys.env.getOrElse("AZURE_OPENAI_API_KEY", "")
            case _ => ""
        }
    }

    /** Read a provider's key from the shared per-provider key store `{env}/ai-keys`.
      * Field names are `anthropicApiKey` / `openaiApiKey` / `azureApiKey`. Returns "" when the store
      * doesn't exist, the field is absent/empty, or the provider has no shared key
      * concept (e.g. Ollama). Never throws — a Vault hiccup just falls through to the
      * next resolution tier. */
    def providerKeyFromStore(env: String, provider: String): String = {
        val field = provider.toLowerCase match {
            case "anthropic" => "anthropicApiKey"
            case "openai" => "openaiApiKey"
            case "azure" => "azureApiKey"
            case _ => return ""
        }
        try {
            SecretsUtil.getSecretMap(env + "/ai-keys")
                .flatMap(m => Option(m.get(field)))
                .filter(_.nonEmpty)
                .getOrElse("")
        } catch {
            case e: Exception =>
                logger.debug("Could not read shared " + provider + " key from " + env + "/ai-keys — falling through to next resolution tier", e)
                ""
        }
    }

    private[aiutil] def defaultEndpointFor(provider: String): String = provider.toLowerCase match {
        case "anthropic" => "https://api.anthropic.com/v1/messages"
        case "openai" => "https://api.openai.com/v1/responses"
        // azure has no universal default — the endpoint embeds the customer's
        // resource name (https://{resource}.openai.azure.com/openai/v1/...).
        // bedrock has no static default either — the invoke URL is derived from
        // the resolved AWS region + model at request time (BedrockSupport).
        case _ => ""
    }

    /** Default model for web-search runs. Both providers are picked for SPEED of
      * summarization, not reasoning depth — the task is "read N web pages and
      * write a useful research note." Codex / reasoning models add 30-60s with
      * no quality lift for this. Override via the Web Search section's Advanced
      * model field if you want a different one. */
    private[aiutil] def defaultModelFor(provider: String): String = provider.toLowerCase match {
        case "anthropic" => "claude-sonnet-4-6"
        case "openai" => "gpt-5.5"
        case _ => ""
    }

    def maxInputChars(): Int = {
        val aiConfig = DatrisEnvironment.current.aiConfig
        val maxInputTokens = aiConfig.provider.toLowerCase match {
            case "ollama" => 100000
            case "openai" => 100000
            case "azure" => 100000
            case _ => 150000
        }
        maxInputTokens * 4
    }

    def fitsInContext(text: String): Boolean = {
        text.length < maxInputChars()
    }

    def calculateBatchSize(rows: List[String], promptOverheadChars: Int): Int = {
        if (rows.isEmpty) return 1
        val avgRowChars = rows.map(_.length).sum / rows.size
        val availableChars = maxInputChars() - promptOverheadChars
        val batchSize = availableChars / Math.max(avgRowChars, 1)
        Math.max(batchSize, 1)
    }
}
