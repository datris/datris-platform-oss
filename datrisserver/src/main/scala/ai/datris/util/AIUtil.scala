package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.model.{AIConfig, DatrisEnvironment, DatrisException}
import org.apache.http.HttpHeaders
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils

import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.JavaConverters._

object AIUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // Reusable HTTP clients — one lightweight client for Ollama (no SSL), one with SSL for cloud providers
    private lazy val ollamaClient: CloseableHttpClient = HttpClients.createDefault()
    private lazy val sslClient: CloseableHttpClient = {
        val sslsf = new SSLConnectionSocketFactory(
            SSLContext.getDefault,
            Array("TLSv1.2"),
            null,
            SSLConnectionSocketFactory.getDefaultHostnameVerifier
        )
        HttpClients.custom().setSSLSocketFactory(sslsf).build()
    }

    private def getClient(provider: String): CloseableHttpClient = {
        provider.toLowerCase match {
            case "ollama" => ollamaClient
            case _ => sslClient
        }
    }

    /**
     * Execute an HTTP POST against the AI provider with consistent retry behavior.
     * Retries up to 5 times on transient status codes (429, 503, 529) with linear backoff.
     * Throws DatrisException on any other non-200 status.
     */
    /** Translate a non-200 AI-provider error into a message a user or the
      * Assistant agent can act on, naming the model where the failure is
      * model-specific. Falls back to the raw status + body when the shape isn't
      * recognized, so nothing is ever swallowed. `body` is the provider's raw
      * response; `model` is the configured model id (may be null). */
    private def explainAIError(status: Int, body: String, model: String): String = {
        val b = if (body == null) "" else body
        val lower = b.toLowerCase
        val m = if (model == null || model.isEmpty) "the selected model" else "'" + model + "'"
        if (status == 400 && lower.contains("retention")) {
            "Model " + m + " requires standard (30-day) data retention on the Anthropic account and is not " +
                "available under zero-data-retention. Pick a different model, or enable standard data retention " +
                "on the Anthropic organization that owns this API key. (Provider response: " + b.take(400) + ")"
        } else if (status == 400 && (lower.contains("temperature") || lower.contains("top_p") || lower.contains("top_k"))) {
            "Model " + m + " does not accept sampling parameters (temperature/top_p/top_k). This is a request " +
                "configuration issue, not a problem with your input. (Provider response: " + b.take(400) + ")"
        } else if (status == 400 && (lower.contains("budget_tokens") || lower.contains("thinking"))) {
            "Model " + m + " rejected the extended-thinking settings in this request. This is a request " +
                "configuration issue, not a problem with your input. (Provider response: " + b.take(400) + ")"
        } else if (status == 401 || status == 403) {
            "The AI provider rejected the API key (status " + status + ") for model " + m + ". Check that the " +
                "configured key is valid and has access to this model. (Provider response: " + b.take(400) + ")"
        } else {
            "AI API returned error status: " + status + ", body: " + b
        }
    }

    private def executeWithRetry(client: CloseableHttpClient, httpPostFactory: () => HttpPost, modelLabel: String = null): String = {
        val maxRetries = 5
        var attempt = 0
        var result: String = null
        while (result == null) {
            val httpPost = httpPostFactory()
            val startTime = System.currentTimeMillis()
            val response = client.execute(httpPost)
            val elapsedMs = System.currentTimeMillis() - startTime
            val statusCode = response.getStatusLine.getStatusCode
            if ((statusCode == 429 || statusCode == 529 || statusCode == 503) && attempt < maxRetries) {
                EntityUtils.consume(response.getEntity)
                attempt += 1
                val waitSeconds = 5 * attempt
                logger.warn("AI API returned " + statusCode + " (transient), waiting " + waitSeconds + "s before retry " + attempt + " of " + maxRetries)
                Thread.sleep(waitSeconds * 1000L)
            } else if (statusCode != 200) {
                throw new DatrisException(explainAIError(statusCode, EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8), modelLabel))
            } else {
                result = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
                logger.info("AI API responded in " + elapsedMs + "ms, response length: " + result.length + " chars")
            }
        }
        result
    }

    /**
     * Build an HttpPost with provider-specific auth headers and content-type.
     * Pulled out so all three callAI* methods can share the same logic. The
     * endpoint is passed explicitly so Responses-API callers can override the
     * stored chat-completions URL without mutating AIConfig.
     */
    private def buildHttpPost(aiConfig: AIConfig, jsonBody: String, endpoint: String): HttpPost = {
        val httpPost = new HttpPost(endpoint)
        aiConfig.provider.toLowerCase match {
            case "openai" =>
                httpPost.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + aiConfig.apiKey)
            case "ollama" =>
                if (aiConfig.apiKey != null && aiConfig.apiKey.nonEmpty)
                    httpPost.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + aiConfig.apiKey)
            case _ => // anthropic
                httpPost.addHeader("x-api-key", aiConfig.apiKey)
                val v = if (aiConfig.version != null && aiConfig.version.nonEmpty) aiConfig.version else "2023-06-01"
                httpPost.addHeader("anthropic-version", v)
        }
        httpPost.addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
        httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8))
        httpPost
    }

    // OpenAI's Responses API (POST /v1/responses) is used by the codex family
    // and is also valid for newer reasoning models. We auto-route when the
    // model name contains "codex" or the configured endpoint already points at
    // /v1/responses. Request/response shapes are different from chat/completions
    // (input + instructions + max_output_tokens; output[].content[].text).
    private def usesResponsesApi(aiConfig: AIConfig): Boolean = {
        if (aiConfig == null || !aiConfig.provider.toLowerCase.equals("openai")) return false
        val model = Option(aiConfig.model).map(_.toLowerCase).getOrElse("")
        val endpoint = Option(aiConfig.endpoint).map(_.toLowerCase).getOrElse("")
        model.contains("codex") || endpoint.contains("/v1/responses")
    }

    private def responsesEndpointFor(aiConfig: AIConfig): String = {
        val ep = aiConfig.endpoint
        if (ep == null || ep.isEmpty) "https://api.openai.com/v1/responses"
        else if (ep.toLowerCase.contains("/v1/responses")) ep
        else ep.replaceFirst("/v1/chat/completions$", "/v1/responses")
            .replaceFirst("/v1/completions$", "/v1/responses")
    }

    private def callResponsesApi(
        systemPrompt: String,
        messages: Seq[(String, String)],
        aiConfig: AIConfig,
        maxTokens: Int,
        temperature: Double,
        useWebSearch: Boolean = false
    ): String = {
        val endpoint = responsesEndpointFor(aiConfig)
        logger.info("Calling OpenAI Responses API, endpoint: " + endpoint + ", model: " + aiConfig.model + ", messages: " + messages
            .size + ", maxTokens: " + maxTokens + ", webSearch: " + useWebSearch)

        val inputArr = new JsonArray()
        messages.foreach { case (role, content) =>
            val msg = new JsonObject()
            msg.addProperty("role", role)
            msg.addProperty("content", content)
            inputArr.add(msg)
        }

        val requestObj = new JsonObject()
        requestObj.addProperty("model", aiConfig.model)
        if (systemPrompt != null && systemPrompt.nonEmpty)
            requestObj.addProperty("instructions", systemPrompt)
        requestObj.add("input", inputArr)
        requestObj.addProperty("max_output_tokens", maxTokens)
        if (temperature >= 0) requestObj.addProperty("temperature", temperature)

        attachWebSearchToolResponses(requestObj, aiConfig, useWebSearch)

        val jsonBody = requestObj.toString
        val client = getClient(aiConfig.provider)
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody, endpoint), aiConfig.model)
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

    private def addTokenLimit(requestObj: JsonObject, provider: String, model: String, maxTokens: Int): Unit = {
        val field = if (provider.toLowerCase == "openai") openAiTokenField(model) else "max_tokens"
        requestObj.addProperty(field, maxTokens)
    }

    /** Resolve an apiKey for an AI provider section. Used by every AI-config loader
      * (ai-primary, codegen, embedding, web-search) so the same fallback applies
      * uniformly, in priority order:
      *
      *   1. The shared per-provider key store at `{env}/ai-keys` (fields
      *      `anthropicApiKey` / `openaiApiKey`). This is the authoritative home for
      *      provider keys — they live here independent of which slot uses each
      *      provider, so switching a slot's provider back and forth never loses the
      *      other provider's key. Matches the UI's "enter each key once" model.
      *   2. The slot secret's own inline `apiKey` if non-empty — legacy / pre-store
      *      deployments that stored the key on the slot itself.
      *   3. The matching `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` env var, but ONLY
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
            case _ => ""
        }
    }

    /** Read a provider's key from the shared per-provider key store `{env}/ai-keys`.
      * Field names are `anthropicApiKey` / `openaiApiKey`. Returns "" when the store
      * doesn't exist, the field is absent/empty, or the provider has no shared key
      * concept (e.g. Ollama). Never throws — a Vault hiccup just falls through to the
      * next resolution tier. */
    def providerKeyFromStore(env: String, provider: String): String = {
        val field = provider.toLowerCase match {
            case "anthropic" => "anthropicApiKey"
            case "openai" => "openaiApiKey"
            case _ => return ""
        }
        try {
            SecretsUtil.getSecretMap(env + "/ai-keys")
                .flatMap(m => Option(m.get(field)))
                .filter(_.nonEmpty)
                .getOrElse("")
        } catch { case _: Exception => "" }
    }

    /** Whether web search is enabled at all. Independent of which provider runs the
      * main AI call — we either attach the tool natively (when providers match) or
      * run a separate search call and inject the results (when they don't). */
    def webSearchActive: Boolean =
        DatrisEnvironment.current.webSearchConfig.exists(_.enabled)

    /** Whether we can attach the native web-search tool to a request that's about to
      * go out — true only when the request provider is the same as the configured
      * web-search provider AND the request can carry the tool (Anthropic Messages
      * or OpenAI Responses). Used by `attachWebSearchTool*` helpers. */
    private def canAttachNativeWebSearch(aiConfig: AIConfig): Boolean = {
        if (aiConfig == null) return false
        val ws = DatrisEnvironment.current.webSearchConfig
        if (!ws.exists(_.enabled)) return false

        val provider = aiConfig.provider.toLowerCase
        val configProvider = ws.get.provider
        if (configProvider != provider) return false // out-of-band path
        if (provider == "openai" && !usesResponsesApi(aiConfig)) return false // Chat Completions can't carry the tool
        provider == "anthropic" || provider == "openai"
    }

    /** Attach the Anthropic `web_search_20250305` server tool to an outgoing request when
      * the caller opted in AND the request provider matches the configured web-search
      * provider (Anthropic). The mismatched-provider case is handled by `runWebSearch`
      * out of band before the main call. */
    private def attachWebSearchToolAnthropic(requestObj: JsonObject, aiConfig: AIConfig, useWebSearch: Boolean): Unit = {
        if (!useWebSearch || !canAttachNativeWebSearch(aiConfig) || aiConfig.provider.toLowerCase != "anthropic") return
        val tools = new JsonArray()
        val tool = new JsonObject()
        tool.addProperty("type", "web_search_20250305")
        tool.addProperty("name", "web_search")
        tool.addProperty("max_uses", DatrisEnvironment.current.webSearchConfig.get.maxUses)
        tools.add(tool)
        requestObj.add("tools", tools)
    }

    /** Attach the OpenAI Responses-API `web_search` tool. Same gating as the Anthropic helper. */
    private def attachWebSearchToolResponses(requestObj: JsonObject, aiConfig: AIConfig, useWebSearch: Boolean): Unit = {
        if (!useWebSearch || !canAttachNativeWebSearch(aiConfig) || aiConfig.provider.toLowerCase != "openai") return
        val tools = new JsonArray()
        val tool = new JsonObject()
        tool.addProperty("type", "web_search")
        tools.add(tool)
        requestObj.add("tools", tools)
    }

    /** Result of an out-of-band web search pass: the model's research notes plus
      * the citations it consulted. Pass `notes` into the main AI call's system
      * prompt as context, and `citations` into the audit log. */
    case class WebSearchResult(notes: String, citations: List[(String, String)])

    /** What a call site should do for an upcoming AI call when web search is requested.
      * Three states: Off, attach the tool natively, or inject pre-fetched research. */
    sealed trait WebSearchPlan
    object WebSearchPlan {
        case object Off extends WebSearchPlan
        case object Native extends WebSearchPlan
        case class Injected(notes: String, citations: List[(String, String)]) extends WebSearchPlan
    }

    /** Decide how to apply web search for an upcoming AI call.
      *   - Off: web search isn't enabled (or the search itself failed)
      *   - Native: attach the tool to the upcoming call (providers match)
      *   - Injected: a separate search call happened — its result is in the payload,
      *               caller should prepend it to the system prompt and call without
      *               useWebSearch
      *
      * `searchQuery` is what the search-side model sees as the user's request when
      * doing the out-of-band search. Send the user-facing request (description,
      * brainstorm question, etc.) — not the full system prompt or pipeline internals. */
    def planWebSearch(aiConfig: AIConfig, searchQuery: String): WebSearchPlan = {
        if (!webSearchActive) WebSearchPlan.Off
        else if (canAttachNativeWebSearch(aiConfig)) WebSearchPlan.Native
        else runWebSearch(searchQuery) match {
            case Some(r) => WebSearchPlan.Injected(r.notes, r.citations)
            case None => WebSearchPlan.Off
        }
    }

    /** Format the injected research as a system-prompt suffix. Empty string when
      * the plan isn't Injected, so callers can append unconditionally. */
    def renderInjectedContext(plan: WebSearchPlan): String = plan match {
        case WebSearchPlan.Injected(notes, citations) =>
            val sources =
                if (citations.isEmpty) ""
                else "\n\n### Sources consulted\n" +
                    citations.map { case (url, title) => "- " + title + " (" + url + ")" }.mkString("\n")
            "\n\n## Web search context (pre-fetched)\n\nThe following research was gathered to help with the request:\n\n" + notes + sources
        case _ => ""
    }

    /** Whether the upcoming call should attach the native tool. */
    def useNative(plan: WebSearchPlan): Boolean = plan == WebSearchPlan.Native

    /** Run a separate web search call against the configured web-search provider and
      * return research notes + citations. Used when the main AI call's provider differs
      * from the web-search provider (e.g. main=Anthropic, web search=OpenAI), where we
      * can't attach a native tool. The model on the search side decides what to search
      * for based on the supplied query/context.
      *
      * Returns None when web search isn't configured, isn't enabled, or fails — in all
      * those cases the caller proceeds without web context. The apiKey on `ws` was
      * already resolved at load time (Vault apiKey, then env-var fallback for
      * single-tenant deployments). */
    def runWebSearch(query: String): Option[WebSearchResult] = {
        val ws = DatrisEnvironment.current.webSearchConfig.filter(_.enabled).getOrElse(return None)
        if (ws.apiKey == null || ws.apiKey.isEmpty) {
            logger.warn("runWebSearch: web search is enabled but no apiKey is available — set it in the web-search secret or the matching " +
                (if (ws.provider == "anthropic") "ANTHROPIC_API_KEY" else "OPENAI_API_KEY") + " environment variable. Skipping.")
            return None
        }

        val searchAiConfig = AIConfig(
            provider = ws.provider,
            endpoint = if (ws.endpoint.nonEmpty) ws.endpoint else defaultEndpointFor(ws.provider),
            model = if (ws.model.nonEmpty) ws.model else defaultModelFor(ws.provider),
            apiKey = ws.apiKey,
            version = ws.version
        )

        val systemPrompt =
            "You are a research assistant. Use the web_search tool to gather current, accurate information " +
                "relevant to the user's request. Return a concise summary of what you found, with the most useful " +
                "facts called out plainly. Always cite your sources via the tool's citation mechanism."

        try {
            logger.info("runWebSearch: making out-of-band search call, provider=" + ws.provider + ", model=" + searchAiConfig.model)
            val responseText = callAIWithSystem(systemPrompt, query, searchAiConfig, useWebSearch = true)
            val notes = extractText(responseText, searchAiConfig)
            val citations = extractCitations(responseText, searchAiConfig)
            if (citations.nonEmpty)
                logger.info("runWebSearch: consulted " + citations.size + " source(s): " +
                    citations.map { case (url, title) => "[" + title + "](" + url + ")" }.mkString(", "))
            else
                logger.info("runWebSearch: completed (no citations returned by the model)")
            Some(WebSearchResult(notes, citations))
        } catch {
            case e: Exception =>
                logger.warn("runWebSearch: failed (" + e.getClass.getSimpleName + "): " + e.getMessage + " — main AI call will proceed without web context")
                None
        }
    }

    private def defaultEndpointFor(provider: String): String = provider.toLowerCase match {
        case "anthropic" => "https://api.anthropic.com/v1/messages"
        case "openai" => "https://api.openai.com/v1/responses"
        case _ => ""
    }

    /** Default model for web-search runs. Both providers are picked for SPEED of
      * summarization, not reasoning depth — the task is "read N web pages and
      * write a useful research note." Codex / reasoning models add 30-60s with
      * no quality lift for this. Override via the Web Search section's Advanced
      * model field if you want a different one. */
    private def defaultModelFor(provider: String): String = provider.toLowerCase match {
        case "anthropic" => "claude-sonnet-4-6"
        case "openai" => "gpt-5.5"
        case _ => ""
    }

    def maxInputChars(): Int = {
        val aiConfig = DatrisEnvironment.current.aiConfig
        val maxInputTokens = aiConfig.provider.toLowerCase match {
            case "ollama" => 100000
            case "openai" => 100000
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

    def callAIWithSystem(systemPrompt: String, userPrompt: String): String =
        callAIWithSystem(systemPrompt, userPrompt, DatrisEnvironment.current.aiConfig, useWebSearch = false)

    def callAIWithSystem(systemPrompt: String, userPrompt: String, aiConfig: AIConfig): String =
        callAIWithSystem(systemPrompt, userPrompt, aiConfig, useWebSearch = false)

    def callAIWithSystem(systemPrompt: String, userPrompt: String, aiConfig: AIConfig, useWebSearch: Boolean): String = {
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the Vault secret is configured.")

        if (usesResponsesApi(aiConfig))
            return callResponsesApi(systemPrompt, Seq("user" -> userPrompt), aiConfig, 8192, -1.0, useWebSearch)

        logger.info("Calling AI with custom system prompt, endpoint: " + aiConfig.endpoint + ", provider: " + aiConfig.provider + ", model: " + aiConfig
            .model + ", webSearch: " + useWebSearch)

        val messagesArr = new JsonArray()

        if (!aiConfig.provider.toLowerCase.equals("anthropic")) {
            val systemMsg = new JsonObject()
            systemMsg.addProperty("role", "system")
            systemMsg.addProperty("content", systemPrompt)
            messagesArr.add(systemMsg)
        }

        val messageObj = new JsonObject()
        messageObj.addProperty("role", "user")
        messageObj.addProperty("content", userPrompt)
        messagesArr.add(messageObj)

        val requestObj = new JsonObject()
        requestObj.addProperty("model", aiConfig.model)
        addTokenLimit(requestObj, aiConfig.provider, aiConfig.model, 8192)
        requestObj.add("messages", messagesArr)

        if (aiConfig.provider.toLowerCase.equals("anthropic")) {
            requestObj.addProperty("system", systemPrompt)
        }

        attachWebSearchToolAnthropic(requestObj, aiConfig, useWebSearch)

        val jsonBody = requestObj.toString
        val client = getClient(aiConfig.provider)
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody, aiConfig.endpoint), aiConfig.model)
    }

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)]): String =
        callAIWithMessages(systemPrompt, messages, DatrisEnvironment.current.aiConfig, 8192, -1.0, useWebSearch = false)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig): String =
        callAIWithMessages(systemPrompt, messages, aiConfig, 8192, -1.0, useWebSearch = false)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], maxTokens: Int): String =
        callAIWithMessages(systemPrompt, messages, DatrisEnvironment.current.aiConfig, maxTokens, -1.0, useWebSearch = false)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], maxTokens: Int, temperature: Double): String =
        callAIWithMessages(systemPrompt, messages, DatrisEnvironment.current.aiConfig, maxTokens, temperature, useWebSearch = false)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig, maxTokens: Int): String =
        callAIWithMessages(systemPrompt, messages, aiConfig, maxTokens, -1.0, useWebSearch = false)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig, maxTokens: Int, temperature: Double): String =
        callAIWithMessages(systemPrompt, messages, aiConfig, maxTokens, temperature, useWebSearch = false)

    def callAIWithMessages(
        systemPrompt: String,
        messages: Seq[(String, String)],
        aiConfig: AIConfig,
        maxTokens: Int,
        temperature: Double,
        useWebSearch: Boolean
    ): String = {
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the Vault secret is configured.")

        if (usesResponsesApi(aiConfig))
            return callResponsesApi(systemPrompt, messages, aiConfig, maxTokens, temperature, useWebSearch)

        logger.info("Calling AI with conversation, " + messages.size + " messages, endpoint: " + aiConfig.endpoint + ", provider: " + aiConfig
            .provider + ", model: " + aiConfig.model + ", maxTokens: " + maxTokens + ", webSearch: " + useWebSearch)

        val messagesArr = new JsonArray()

        // For OpenAI/Ollama, system instruction goes as the first system role message
        if (!aiConfig.provider.toLowerCase.equals("anthropic")) {
            val systemMsg = new JsonObject()
            systemMsg.addProperty("role", "system")
            systemMsg.addProperty("content", systemPrompt)
            messagesArr.add(systemMsg)
        }

        messages.foreach { case (role, content) =>
            val msgObj = new JsonObject()
            msgObj.addProperty("role", role)
            msgObj.addProperty("content", content)
            messagesArr.add(msgObj)
        }

        val requestObj = new JsonObject()
        requestObj.addProperty("model", aiConfig.model)
        addTokenLimit(requestObj, aiConfig.provider, aiConfig.model, maxTokens)
        // Fable/Opus 4.8/4.7 (and Mythos) reject sampling params — never send temperature there.
        if (temperature >= 0 && !rejectsSamplingParams(aiConfig.model)) requestObj.addProperty("temperature", temperature)
        requestObj.add("messages", messagesArr)

        // For Anthropic, system instruction goes as a top-level field
        if (aiConfig.provider.toLowerCase.equals("anthropic")) {
            requestObj.addProperty("system", systemPrompt)
        }

        attachWebSearchToolAnthropic(requestObj, aiConfig, useWebSearch)

        val jsonBody = requestObj.toString
        val client = getClient(aiConfig.provider)
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody, aiConfig.endpoint), aiConfig.model)
    }

    def callAI(prompt: String): String =
        callAI(prompt, DatrisEnvironment.current.aiConfig, useWebSearch = false)

    def callAI(prompt: String, aiConfig: AIConfig): String =
        callAI(prompt, aiConfig, useWebSearch = false)

    def callAI(prompt: String, useWebSearch: Boolean): String =
        callAI(prompt, DatrisEnvironment.current.aiConfig, useWebSearch)

    def callAI(prompt: String, aiConfig: AIConfig, useWebSearch: Boolean): String = {
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the Vault secret is configured.")

        // The default callAI path is used by data-validation callers that expect a JSON
        // array. Web-search-enabled callers (tap diagnosis) want plain English instead;
        // swap the system instruction when web search is on so the model isn't forced
        // into JSON-only mode while it's also citing sources.
        val systemInstruction =
            if (useWebSearch)
                "You are a helpful diagnostic assistant. Answer the user's question directly."
            else
                "You are a data validation engine. Output ONLY valid JSON arrays. Never describe, summarize, or ask questions about the data."

        if (usesResponsesApi(aiConfig))
            return callResponsesApi(systemInstruction, Seq("user" -> prompt), aiConfig, 8192, -1.0, useWebSearch)

        logger.info("Calling AI endpoint: " + aiConfig.endpoint + ", provider: " + aiConfig.provider + ", model: " + aiConfig
            .model + ", prompt length: " + prompt.length + " chars, webSearch: " + useWebSearch)

        val messagesArr = new JsonArray()

        // For OpenAI/Ollama, system instruction goes as a system role message
        if (!aiConfig.provider.toLowerCase.equals("anthropic")) {
            val systemMsg = new JsonObject()
            systemMsg.addProperty("role", "system")
            systemMsg.addProperty("content", systemInstruction)
            messagesArr.add(systemMsg)
        }

        val messageObj = new JsonObject()
        messageObj.addProperty("role", "user")
        messageObj.addProperty("content", prompt)
        messagesArr.add(messageObj)

        val requestObj = new JsonObject()
        requestObj.addProperty("model", aiConfig.model)
        addTokenLimit(requestObj, aiConfig.provider, aiConfig.model, 8192)
        requestObj.add("messages", messagesArr)

        // For Anthropic, system instruction goes as a top-level field
        if (aiConfig.provider.toLowerCase.equals("anthropic")) {
            requestObj.addProperty("system", systemInstruction)
        }

        attachWebSearchToolAnthropic(requestObj, aiConfig, useWebSearch)

        val jsonBody = requestObj.toString
        val client = getClient(aiConfig.provider)
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody, aiConfig.endpoint), aiConfig.model)
    }

    /** Pull the web-search URLs the model consulted for this response, if any.
      * Returns an empty list when the request didn't use web search or the provider
      * didn't surface citations. Both Anthropic and OpenAI surface URLs differently;
      * we normalize to (url, title) tuples. */
    def extractCitations(apiResponse: String, aiConfig: AIConfig): List[(String, String)] = {
        try {
            val gson = new Gson()
            val responseMap = gson.fromJson(apiResponse, classOf[java.util.Map[String, Any]])
            if (usesResponsesApi(aiConfig)) extractResponsesApiCitations(responseMap)
            else aiConfig.provider.toLowerCase match {
                case "anthropic" => extractAnthropicCitations(responseMap)
                case _ => Nil
            }
        } catch { case _: Exception => Nil }
    }

    private def extractAnthropicCitations(responseMap: java.util.Map[String, Any]): List[(String, String)] = {
        // Anthropic surfaces citations on `text` content blocks via a `citations` array,
        // each entry having `url` and `title`. Multiple text blocks may carry citations;
        // dedupe by URL while preserving order.
        val contentList = responseMap.get("content").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (contentList == null) return Nil
        val seen = scala.collection.mutable.LinkedHashMap.empty[String, String]
        contentList.asScala.foreach { block =>
            val cites = block.get("citations").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
            if (cites != null) cites.asScala.foreach { c =>
                val url = Option(c.get("url")).map(_.toString).getOrElse("")
                val title = Option(c.get("title")).map(_.toString).getOrElse(url)
                if (url.nonEmpty && !seen.contains(url)) seen.put(url, title)
            }
        }
        seen.toList
    }

    private def extractResponsesApiCitations(responseMap: java.util.Map[String, Any]): List[(String, String)] = {
        // OpenAI Responses surfaces citations as `url_citation` annotations on the
        // message's text content. Same dedupe-by-URL.
        val output = responseMap.get("output").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (output == null) return Nil
        val seen = scala.collection.mutable.LinkedHashMap.empty[String, String]
        output.asScala.foreach { item =>
            val content = item.get("content").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
            if (content != null) content.asScala.foreach { c =>
                val annotations = c.get("annotations").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
                if (annotations != null) annotations.asScala.foreach { a =>
                    val t = Option(a.get("type")).map(_.toString).getOrElse("")
                    if (t == "url_citation") {
                        val url = Option(a.get("url")).map(_.toString).getOrElse("")
                        val title = Option(a.get("title")).map(_.toString).getOrElse(url)
                        if (url.nonEmpty && !seen.contains(url)) seen.put(url, title)
                    }
                }
            }
        }
        seen.toList
    }

    def extractText(apiResponse: String): String =
        extractText(apiResponse, DatrisEnvironment.current.aiConfig)

    def extractText(apiResponse: String, aiConfig: AIConfig): String = {
        val gson = new Gson()
        val responseMap = gson.fromJson(apiResponse, classOf[java.util.Map[String, Any]])

        val text =
            if (usesResponsesApi(aiConfig)) extractResponsesApiText(responseMap)
            else aiConfig.provider.toLowerCase match {
                case "openai" | "ollama" =>
                    val choices = responseMap.get("choices").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
                    if (choices == null || choices.isEmpty)
                        throw new DatrisException("OpenAI/Ollama response contained no choices")
                    val message = choices.get(0).get("message").asInstanceOf[java.util.Map[String, Any]]
                    if (message == null)
                        throw new DatrisException("OpenAI/Ollama response choice had no message")
                    message.get("content").asInstanceOf[String]
                case _ =>
                    val contentList = responseMap.get("content").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
                    if (contentList == null || contentList.isEmpty)
                        throw new DatrisException("Anthropic response contained no content")
                    // Anthropic returns a list of content blocks. Without tools the first
                    // block is always `text`. With server tools enabled (web_search), the
                    // list interleaves `text`, `server_tool_use`, and `web_search_tool_result`
                    // blocks — we want every text block concatenated, in order, so the
                    // model's narrative around tool calls is preserved for the caller.
                    val texts = contentList.asScala.flatMap { block =>
                        val t = Option(block.get("type")).map(_.toString).getOrElse("text")
                        if (t == "text") Option(block.get("text")).map(_.toString) else None
                    }
                    if (texts.isEmpty)
                        throw new DatrisException("Anthropic response had no text blocks")
                    texts.mkString("\n")
            }

        if (text == null || text.trim.isEmpty)
            throw new DatrisException("AI response text was empty")

        text.trim
    }

    // Responses API shape: { output: [ { type: "message", content: [ { type: "output_text", text: "..." } ] }, ... ] }.
    // Reasoning models may also include "reasoning" items in output — we want the first message's first output_text.
    private def extractResponsesApiText(responseMap: java.util.Map[String, Any]): String = {
        val output = responseMap.get("output").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (output == null || output.isEmpty)
            throw new DatrisException("OpenAI Responses API response contained no output")
        val message = output.asScala.find { item =>
            val t = Option(item.get("type")).map(_.toString).getOrElse("")
            t == "message"
        }.getOrElse(throw new DatrisException("OpenAI Responses API output contained no message item"))
        val contentList = message.get("content").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (contentList == null || contentList.isEmpty)
            throw new DatrisException("OpenAI Responses API message had no content")
        val textItem = contentList.asScala.find { c =>
            val t = Option(c.get("type")).map(_.toString).getOrElse("")
            t == "output_text" || t == "text"
        }.getOrElse(throw new DatrisException("OpenAI Responses API content had no output_text"))
        textItem.get("text").asInstanceOf[String]
    }

    // ============================================================================
    // Tool-use streaming — used by the in-product Assistant (agent loop).
    //
    // Adds an Anthropic Messages API streaming call that can carry tool
    // definitions and extended-thinking. Parses content blocks (text, thinking,
    // tool_use) and forwards deltas to a sink as they arrive so the UI can render
    // the model's reasoning + tool calls in real time.
    //
    // For OpenAI tenants we fall back to a non-streaming Responses API call with
    // reasoning summary turned on. The sink still receives synthesized events at
    // the end so the UI rendering code stays uniform.
    // ============================================================================

    /** A single block of model output. Mirrors Anthropic's content-block shape. */
    sealed trait AIContentBlock
    object AIContentBlock {
        case class TextBlock(text: String) extends AIContentBlock
        case class ThinkingBlock(thinking: String, signature: String) extends AIContentBlock
        case class ToolUseBlock(id: String, name: String, input: JsonObject) extends AIContentBlock
        case class ToolResultBlock(toolUseId: String, content: String, isError: Boolean) extends AIContentBlock
    }

    /** Events emitted to the sink during a streaming agent call. The UI maps each
      * one to an SSE event of the same name. */
    sealed trait AIStreamEvent
    object AIStreamEvent {
        case object IterationStart extends AIStreamEvent
        case class ThinkingDelta(text: String) extends AIStreamEvent
        case class TextDelta(text: String) extends AIStreamEvent
        case class ToolUseStart(id: String, name: String) extends AIStreamEvent

        /** Progress while the model composes a tool call's input (Anthropic
          * `input_json_delta`). Carries only the delta size — enough for the UI
          * to show a live byte counter on the running tool card during long
          * compositions (big samples, generated scripts). */
        case class InputDelta(id: String, chars: Int) extends AIStreamEvent
        case class ToolUseComplete(id: String, name: String, input: JsonObject) extends AIStreamEvent
        case class Error(message: String) extends AIStreamEvent
    }

    /** Final result of a single streaming call: the assembled content blocks plus
      * a flag indicating whether the model wants to call tools (stop_reason ==
      * "tool_use" for Anthropic, presence of tool_use blocks for OpenAI). The
      * AgentLoop uses `wantsToolUse` to decide whether to recurse.
      *
      * `stopReason` carries the provider's normalized stop reason so the loop can
      * tell a genuine end-of-turn (`end_turn` / "") from an output-length cutoff
      * (`max_tokens`). The latter must NOT be treated as "done" — with extended
      * thinking, the model can exhaust the token budget mid-reasoning before
      * emitting any text or tool call, producing an empty turn that would
      * otherwise look finished. Defaults to "" so non-streaming/legacy
      * construction sites compile unchanged. */
    case class AIToolResponse(content: List[AIContentBlock], wantsToolUse: Boolean, stopReason: String = "")

    /** Whether a given AIConfig supports extended thinking — Anthropic Claude 4.x. */
    def supportsExtendedThinking(aiConfig: AIConfig): Boolean = {
        if (aiConfig == null) return false
        aiConfig.provider.toLowerCase == "anthropic"
    }

    /** Streaming agent call. Builds an Anthropic Messages API request with tools
      * attached (and optionally extended thinking), opens a streaming connection,
      * and forwards `thinking_delta` / `text_delta` / `tool_use` events to `sink`
      * as the model produces them.
      *
      * For OpenAI tenants we fall back to non-streaming Responses API with
      * reasoning summary; the sink still gets synthesized events so callers don't
      * need to branch on provider.
      *
      * @param messages prior conversation as a list of (role, content-blocks)
      *                 tuples. Assistant turns that originally contained
      *                 thinking/tool_use blocks MUST be preserved verbatim
      *                 (signature included) for Anthropic reasoning continuity.
      * @param tools    MCP tools, already mapped to Anthropic's
      *                 {name, description, input_schema} shape.
      * @param sink     event callback. Called from the same thread that drives the
      *                 HTTP read loop, so blocking work in the sink will pause the
      *                 stream — keep it cheap (just forward to an SseEmitter).
      */
    def callAIWithToolsStreaming(
        aiConfig: AIConfig,
        system: String,
        messages: Seq[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        enableThinking: Boolean,
        maxTokens: Int,
        sink: AIStreamEvent => Unit,
        cancelled: () => Boolean = () => false
    ): AIToolResponse = {
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized.")

        sink(AIStreamEvent.IterationStart)

        aiConfig.provider.toLowerCase match {
            case "anthropic" => anthropicStreamingCall(aiConfig, system, messages, tools, enableThinking, maxTokens, sink, cancelled)
            case "openai" => openaiNonStreamingCall(aiConfig, system, messages, tools, maxTokens, sink)
            case other =>
                throw new DatrisException("Provider '" + other + "' is not yet supported for chat. " +
                    "The chat assistants run on the AI Primary provider — set it to Anthropic or OpenAI in Configuration. " +
                    "(Ollama chat support is planned; Ollama already works for the CodeGen provider.)")
        }
    }

    // ---------- Anthropic streaming path ----------

    /** Which form of the thinking API a given model accepts. Anthropic shipped
      * two incompatible shapes:
      *   - Adaptive: `thinking.type: "adaptive"` + `output_config.effort` —
      *               Claude 4.7+
      *   - Enabled:  `thinking.type: "enabled"` + `budget_tokens` —
      *               Claude 3.7 and 4.0–4.6
      *   - Unsupported: model has no extended thinking
      * Discovered per (provider, model) on first call, then cached for the
      * lifetime of the JVM. */
    private sealed trait ThinkingForm
    private object ThinkingForm {
        case object Adaptive extends ThinkingForm
        case object Enabled extends ThinkingForm
        case object Unsupported extends ThinkingForm
    }
    private val thinkingFormCache: java.util.concurrent.ConcurrentHashMap[String, ThinkingForm] =
        new java.util.concurrent.ConcurrentHashMap()

    private def thinkingCacheKey(aiConfig: AIConfig): String =
        aiConfig.provider.toLowerCase + ":" + Option(aiConfig.model).getOrElse("")

    /** Driver around anthropicStreamingCallOnce that runs the model-form
      * fallback ladder (adaptive → enabled → no-thinking) on the first call
      * per (provider, model), then caches the working form for subsequent
      * calls. When thinking is disabled by the caller, we skip the ladder
      * entirely. */
    private def anthropicStreamingCall(
        aiConfig: AIConfig,
        system: String,
        messages: Seq[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        enableThinking: Boolean,
        maxTokens: Int,
        sink: AIStreamEvent => Unit,
        cancelled: () => Boolean
    ): AIToolResponse = {
        if (!enableThinking) {
            return anthropicStreamingCallOnce(aiConfig, system, messages, tools, ThinkingForm.Unsupported, maxTokens, sink, cancelled)
        }

        val key = thinkingCacheKey(aiConfig)
        Option(thinkingFormCache.get(key)) match {
            case Some(form) =>
                anthropicStreamingCallOnce(aiConfig, system, messages, tools, form, maxTokens, sink, cancelled)
            case None =>
                // Cold cache — try the new (adaptive) form first, then fall back.
                val ladder: List[ThinkingForm] = List(ThinkingForm.Adaptive, ThinkingForm.Enabled, ThinkingForm.Unsupported)
                var lastEx: Option[DatrisException] = None
                var iter = ladder.iterator
                while (iter.hasNext) {
                    val form = iter.next()
                    try {
                        val r = anthropicStreamingCallOnce(aiConfig, system, messages, tools, form, maxTokens, sink, cancelled)
                        thinkingFormCache.put(key, form)
                        logger.info("Assistant: cached thinking form for " + key + " = " + form)
                        return r
                    } catch {
                        case e: DatrisException if isThinkingApiError(e.getMessage) =>
                            logger.info("Assistant: " + form + " rejected by " + key + " (" + e.getMessage.take(200) + "), trying next form")
                            lastEx = Some(e)
                        case e: DatrisException =>
                            // Not a thinking-API error — surface immediately.
                            throw e
                    }
                }
                throw lastEx.getOrElse(new DatrisException("Anthropic call failed with no fallback form succeeding"))
        }
    }

    /** Does this error message look like an Anthropic thinking-API rejection?
      * Anthropic phrases these around the `thinking.type` field. Examples seen:
      *   - "thinking.type.enabled is not supported for this model"
      *   - "thinking.type.adaptive is not supported for this model"
      *   - "thinking is not supported on this model"  (hypothetical older Sonnet)
      * We match conservatively so a generic 400 (bad messages, etc.) bubbles up
      * instead of looping through fallbacks. */
    private def isThinkingApiError(msg: String): Boolean = {
        if (msg == null) return false
        val m = msg.toLowerCase
        m.contains("thinking.type") ||
        (m.contains("thinking") && m.contains("not supported")) ||
        m.contains("output_config")
    }

    /** Anthropic removed sampling parameters (`temperature`/`top_p`/`top_k`) on the
      * adaptive-thinking-only models — sending any of them returns a 400. Adaptive
      * thinking doesn't need `temperature` anyway, so we simply omit it for these
      * models. Older thinking-capable models (Sonnet 4.6, Opus 4.6, Haiku 4.5) still
      * require/accept `temperature: 1.0` with thinking on, so their behavior is
      * unchanged. Match the families that reject sampling params: Fable, Mythos,
      * Opus 4.7, Opus 4.8 (and later Opus), Sonnet 5 (and later Sonnet). */
    private def rejectsSamplingParams(model: String): Boolean = {
        if (model == null) return false
        val m = model.toLowerCase
        m.contains("fable") || m.contains("mythos") ||
        m.contains("opus-4-7") || m.contains("opus-4-8") ||
        m.contains("sonnet-5")
    }

    /** Issue exactly one Anthropic streaming request with the given thinking
      * form. Surfaces a DatrisException on non-200 status so the driver can
      * decide whether to retry with a different form. */
    private def anthropicStreamingCallOnce(
        aiConfig: AIConfig,
        system: String,
        messages: Seq[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        form: ThinkingForm,
        maxTokens: Int,
        sink: AIStreamEvent => Unit,
        cancelled: () => Boolean
    ): AIToolResponse = {
        val req = new JsonObject()
        req.addProperty("model", aiConfig.model)
        req.addProperty("max_tokens", maxTokens)
        req.addProperty("stream", true)
        if (system != null && system.nonEmpty) req.addProperty("system", system)
        req.add("messages", anthropicMessagesArray(messages))
        if (tools.nonEmpty) {
            val toolsArr = new JsonArray()
            tools.foreach(t => toolsArr.add(t))
            req.add("tools", toolsArr)
        }

        form match {
            case ThinkingForm.Adaptive =>
                val thinkingObj = new JsonObject()
                thinkingObj.addProperty("type", "adaptive")
                req.add("thinking", thinkingObj)
                val outputCfg = new JsonObject()
                outputCfg.addProperty("effort", "medium")
                req.add("output_config", outputCfg)
                if (!rejectsSamplingParams(aiConfig.model)) req.addProperty("temperature", 1.0)
            case ThinkingForm.Enabled =>
                val thinkingObj = new JsonObject()
                thinkingObj.addProperty("type", "enabled")
                val budget = Math.min(5000, Math.max(1024, maxTokens / 4))
                thinkingObj.addProperty("budget_tokens", budget)
                req.add("thinking", thinkingObj)
                if (!rejectsSamplingParams(aiConfig.model)) req.addProperty("temperature", 1.0)
            case ThinkingForm.Unsupported =>
            // No thinking field at all.
        }

        val jsonBody = req.toString
        val client = sslClient
        val httpPost = buildHttpPost(aiConfig, jsonBody, aiConfig.endpoint)
        logger.info("Assistant: streaming Anthropic call, model=" + aiConfig.model + ", tools=" + tools.size +
            ", thinkingForm=" + form + ", maxTokens=" + maxTokens + ", messages=" + messages.size)

        val response = client.execute(httpPost)
        try {
            val status = response.getStatusLine.getStatusCode
            if (status != 200) {
                val body = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
                throw new DatrisException(explainAIError(status, body, aiConfig.model))
            }
            val stream = response.getEntity.getContent
            try parseAnthropicSseStream(stream, sink, cancelled, httpPost)
            finally stream.close()
        } finally {
            response.close()
        }
    }

    /** Convert our message tuples back into Anthropic's JSON message format.
      * Assistant turns with content blocks (thinking, tool_use, etc.) MUST be
      * preserved verbatim — re-stringifying them would drop the signature on
      * thinking blocks and break reasoning continuity. */
    private def anthropicMessagesArray(messages: Seq[(String, List[AIContentBlock])]): JsonArray = {
        val arr = new JsonArray()
        messages.foreach { case (role, blocks) =>
            val msgObj = new JsonObject()
            msgObj.addProperty("role", role)
            // Single-text optimization: when there's only a TextBlock, Anthropic accepts
            // a plain string for content. For everything else (or any assistant turn
            // with thinking/tool_use), we send the full content array.
            val onlyText = blocks.size == 1 && blocks.head.isInstanceOf[AIContentBlock.TextBlock]
            if (onlyText && role == "user") {
                msgObj.addProperty("content", blocks.head.asInstanceOf[AIContentBlock.TextBlock].text)
            } else {
                val contentArr = new JsonArray()
                blocks.foreach { b => contentArr.add(blockToAnthropicJson(b)) }
                msgObj.add("content", contentArr)
            }
            arr.add(msgObj)
        }
        arr
    }

    private def blockToAnthropicJson(b: AIContentBlock): JsonObject = {
        val obj = new JsonObject()
        b match {
            case AIContentBlock.TextBlock(text) =>
                obj.addProperty("type", "text")
                obj.addProperty("text", text)
            case AIContentBlock.ThinkingBlock(thinking, signature) =>
                obj.addProperty("type", "thinking")
                obj.addProperty("thinking", thinking)
                if (signature != null && signature.nonEmpty) obj.addProperty("signature", signature)
            case AIContentBlock.ToolUseBlock(id, name, input) =>
                obj.addProperty("type", "tool_use")
                obj.addProperty("id", id)
                obj.addProperty("name", name)
                obj.add("input", input)
            case AIContentBlock.ToolResultBlock(toolUseId, content, isError) =>
                obj.addProperty("type", "tool_result")
                obj.addProperty("tool_use_id", toolUseId)
                obj.addProperty("content", content)
                if (isError) obj.addProperty("is_error", true)
        }
        obj
    }

    /** Read the Anthropic SSE response stream line-by-line, accumulate content
      * blocks, and forward deltas to the sink. Each Anthropic event is a pair of
      * lines: `event: <name>\ndata: <json>` separated by a blank line. We only
      * need the `data:` line — the `data` payload's `type` field tells us what
      * kind of event it is.
      */
    private def parseAnthropicSseStream(
        stream: java.io.InputStream,
        sink: AIStreamEvent => Unit,
        cancelled: () => Boolean,
        httpPost: org.apache.http.client.methods.HttpPost
    ): AIToolResponse = {
        val reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))

        // Per-block accumulators, keyed by Anthropic's `index` field.
        val builders = scala.collection.mutable.Map.empty[Int, AnthropicBlockBuilder]
        var stopReason: String = ""

        // Cancellation check up front — short-circuit before we touch the stream.
        // After that, check between Anthropic SSE lines so we stop consuming tokens
        // (and Anthropic stops generating them) as soon as the client disconnects.
        // httpPost.abort() forces the HTTP connection to close immediately, which
        // Anthropic detects within ~one packet and uses to halt token generation —
        // without it, the connection might linger on a socket buffer.
        if (cancelled()) {
            try httpPost.abort()
            catch { case _: Throwable => () }
            throw new DatrisException("Cancelled by user")
        }

        var line = reader.readLine()
        while (line != null) {
            if (cancelled()) {
                logger.info("Anthropic streaming cancelled mid-response — aborting connection to stop token generation")
                try httpPost.abort()
                catch { case _: Throwable => () }
                throw new DatrisException("Cancelled by user")
            }
            if (line.startsWith("data:")) {
                val payload = line.substring(5).trim
                if (payload.nonEmpty) {
                    try {
                        val evt = JsonParser.parseString(payload).getAsJsonObject
                        val evtType = if (evt.has("type")) evt.get("type").getAsString else ""
                        evtType match {
                            case "content_block_start" =>
                                val idx = evt.get("index").getAsInt
                                val cb = evt.getAsJsonObject("content_block")
                                val cbType = cb.get("type").getAsString
                                val builder = new AnthropicBlockBuilder(cbType)
                                cbType match {
                                    case "tool_use" =>
                                        builder.toolId = cb.get("id").getAsString
                                        builder.toolName = cb.get("name").getAsString
                                        sink(AIStreamEvent.ToolUseStart(builder.toolId, builder.toolName))
                                    case _ => // text, thinking — no per-start emission; deltas carry the data
                                }
                                builders.put(idx, builder)

                            case "content_block_delta" =>
                                val idx = evt.get("index").getAsInt
                                val delta = evt.getAsJsonObject("delta")
                                val deltaType = if (delta.has("type")) delta.get("type").getAsString else ""
                                val b = builders.getOrElseUpdate(idx, new AnthropicBlockBuilder("unknown"))
                                deltaType match {
                                    case "text_delta" =>
                                        val t = delta.get("text").getAsString
                                        b.text.append(t)
                                        sink(AIStreamEvent.TextDelta(t))
                                    case "thinking_delta" =>
                                        val t = delta.get("thinking").getAsString
                                        b.text.append(t)
                                        sink(AIStreamEvent.ThinkingDelta(t))
                                    case "signature_delta" =>
                                        b.signature.append(delta.get("signature").getAsString)
                                    case "input_json_delta" =>
                                        val pj = delta.get("partial_json").getAsString
                                        b.text.append(pj)
                                        if (b.kind == "tool_use" && b.toolId != null)
                                            sink(AIStreamEvent.InputDelta(b.toolId, pj.length))
                                    case _ => // ignore
                                }

                            case "content_block_stop" =>
                                val idx = evt.get("index").getAsInt
                                builders.get(idx).foreach { b =>
                                    if (b.kind == "tool_use") {
                                        val input =
                                            try JsonParser.parseString(if (b.text.isEmpty) "{}" else b.text.toString).getAsJsonObject
                                            catch { case _: Exception => new JsonObject() }
                                        b.toolInput = input
                                        sink(AIStreamEvent.ToolUseComplete(b.toolId, b.toolName, input))
                                    }
                                }

                            case "message_delta" =>
                                val delta = evt.getAsJsonObject("delta")
                                if (delta != null && delta.has("stop_reason"))
                                    stopReason = delta.get("stop_reason").getAsString

                            case "message_stop" => // handled by loop exit
                            case "error" =>
                                val err = if (evt.has("error")) evt.getAsJsonObject("error").toString else payload
                                sink(AIStreamEvent.Error(err))
                                throw new DatrisException("Anthropic streaming error: " + err)

                            case _ => // ping, message_start — ignore
                        }
                    } catch {
                        case e: DatrisException => throw e
                        case _: Exception => // skip malformed event line; the next one will likely be fine
                    }
                }
            }
            line = reader.readLine()
        }

        // Materialize blocks in index order.
        val content = builders.toSeq.sortBy(_._1).map { case (_, b) =>
            b.kind match {
                case "text" => AIContentBlock.TextBlock(b.text.toString)
                case "thinking" => AIContentBlock.ThinkingBlock(b.text.toString, b.signature.toString)
                case "tool_use" =>
                    AIContentBlock.ToolUseBlock(b.toolId, b.toolName, b.toolInput)
                case _ => AIContentBlock.TextBlock(b.text.toString)
            }
        }.toList

        AIToolResponse(content, wantsToolUse = stopReason == "tool_use", stopReason = stopReason)
    }

    /** Mutable accumulator for a single content block while streaming. */
    private class AnthropicBlockBuilder(val kind: String) {
        val text: StringBuilder = new StringBuilder
        val signature: StringBuilder = new StringBuilder
        var toolId: String = ""
        var toolName: String = ""
        var toolInput: JsonObject = new JsonObject()
    }

    // ---------- OpenAI non-streaming fallback ----------

    /** Per-model cache of whether OpenAI accepts `reasoning.effort` for this
      * model. True = supported (attach), False = unsupported (skip). Discovered
      * on first call or guessed from the model-name prefix. */
    private val openaiReasoningSupportedCache: java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean] =
        new java.util.concurrent.ConcurrentHashMap()

    /** Optimistic prefix hint — these models are known to support reasoning
      * effort on the Responses API. When the model name matches, we skip the
      * cold-cache discovery round-trip. Unknown prefixes still try with
      * reasoning first and fall back on rejection. Same prefix list as
      * `openAiTokenField` for max_completion_tokens. */
    private def likelyReasoningModel(model: String): Boolean = {
        if (model == null) return false
        val m = model.toLowerCase
        m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3") ||
        m.startsWith("o4") || m.startsWith("o5")
    }

    private def isOpenAiReasoningError(msg: String): Boolean = {
        if (msg == null) return false
        val m = msg.toLowerCase
        // Examples Anthropic-style errors phrase these around the unknown
        // `reasoning` parameter or unsupported `effort` value:
        //   "Unknown parameter: 'reasoning'."
        //   "reasoning.effort is not supported on this model"
        //   "This model does not support reasoning"
        (m.contains("reasoning") && (m.contains("not support") || m.contains("unknown") || m.contains("invalid")))
    }

    /** OpenAI Responses API call with tools + (optional) reasoning summary.
      * Non-streaming for v1 — synthesizes the same sink events at the end so
      * the UI rendering code stays provider-agnostic.
      *
      * Reasoning handling: try with `reasoning.effort` first when the model is
      * a known reasoning model (or unknown). On rejection, retry without
      * reasoning and cache "unsupported" for this model. Known non-reasoning
      * models skip the field on the first call. */
    private def openaiNonStreamingCall(
        aiConfig: AIConfig,
        system: String,
        messages: Seq[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        maxTokens: Int,
        sink: AIStreamEvent => Unit
    ): AIToolResponse = {
        val cacheKey = aiConfig.provider.toLowerCase + ":" + Option(aiConfig.model).getOrElse("")
        val cached = Option(openaiReasoningSupportedCache.get(cacheKey))
        val attachReasoningFirstTry: Boolean = cached match {
            case Some(b) => b.booleanValue()
            case None => likelyReasoningModel(aiConfig.model)
        }

        try {
            val r = openaiNonStreamingCallOnce(aiConfig, system, messages, tools, maxTokens, sink, attachReasoning = attachReasoningFirstTry)
            // First-call success: cache what we just used.
            if (cached.isEmpty) openaiReasoningSupportedCache.put(cacheKey, java.lang.Boolean.valueOf(attachReasoningFirstTry))
            r
        } catch {
            case e: DatrisException if attachReasoningFirstTry && isOpenAiReasoningError(e.getMessage) =>
                // Model rejected the reasoning field. Retry without it and cache.
                logger.info("Assistant: " + cacheKey + " rejected reasoning.effort (" + e.getMessage.take(200) + "), retrying without")
                openaiReasoningSupportedCache.put(cacheKey, java.lang.Boolean.FALSE)
                openaiNonStreamingCallOnce(aiConfig, system, messages, tools, maxTokens, sink, attachReasoning = false)
        }
    }

    private def openaiNonStreamingCallOnce(
        aiConfig: AIConfig,
        system: String,
        messages: Seq[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        maxTokens: Int,
        sink: AIStreamEvent => Unit,
        attachReasoning: Boolean
    ): AIToolResponse = {
        val endpoint = responsesEndpointFor(aiConfig)
        val req = new JsonObject()
        req.addProperty("model", aiConfig.model)
        if (system != null && system.nonEmpty) req.addProperty("instructions", system)
        req.add("input", openaiInputArray(messages))
        req.addProperty("max_output_tokens", maxTokens)

        if (tools.nonEmpty) {
            val toolsArr = new JsonArray()
            tools.foreach { t =>
                // OpenAI Responses API tool shape: {type: "function", name, description, parameters}
                val ot = new JsonObject()
                ot.addProperty("type", "function")
                ot.addProperty("name", t.get("name").getAsString)
                if (t.has("description")) ot.addProperty("description", t.get("description").getAsString)
                if (t.has("input_schema")) ot.add("parameters", t.get("input_schema"))
                toolsArr.add(ot)
            }
            req.add("tools", toolsArr)
        }

        // Reasoning summary — only for reasoning-capable models. Non-reasoning
        // models 400 on this field; we discover that and cache it.
        if (attachReasoning) {
            val reasoning = new JsonObject()
            reasoning.addProperty("effort", "medium")
            req.add("reasoning", reasoning)
        }

        logger.info("Assistant: OpenAI Responses call, model=" + aiConfig.model + ", tools=" + tools.size +
            ", reasoning=" + attachReasoning + ", maxTokens=" + maxTokens + ", messages=" + messages.size)

        val raw = executeWithRetry(sslClient, () => buildHttpPost(aiConfig, req.toString, endpoint), aiConfig.model)
        val response = JsonParser.parseString(raw).getAsJsonObject
        val output = response.getAsJsonArray("output")
        if (output == null) return AIToolResponse(Nil, wantsToolUse = false)

        val blocks = scala.collection.mutable.ListBuffer.empty[AIContentBlock]
        var hasToolUse = false
        output.asScala.foreach { el =>
            val item = el.getAsJsonObject
            val t = if (item.has("type")) item.get("type").getAsString else ""
            t match {
                case "reasoning" =>
                    val sum = if (item.has("summary")) item.getAsJsonArray("summary") else null
                    if (sum != null && sum.size() > 0) {
                        val sb = new StringBuilder
                        sum.asScala.foreach { s =>
                            val so = s.getAsJsonObject
                            if (so.has("text")) sb.append(so.get("text").getAsString)
                        }
                        val text = sb.toString
                        if (text.nonEmpty) {
                            sink(AIStreamEvent.ThinkingDelta(text))
                            blocks += AIContentBlock.ThinkingBlock(text, "")
                        }
                    }
                case "message" =>
                    val content = item.getAsJsonArray("content")
                    if (content != null) content.asScala.foreach { c =>
                        val co = c.getAsJsonObject
                        val ct = if (co.has("type")) co.get("type").getAsString else ""
                        if ((ct == "output_text" || ct == "text") && co.has("text")) {
                            val text = co.get("text").getAsString
                            sink(AIStreamEvent.TextDelta(text))
                            blocks += AIContentBlock.TextBlock(text)
                        }
                    }
                case "function_call" =>
                    val id = if (item.has("call_id")) item.get("call_id").getAsString
                    else if (item.has("id")) item.get("id").getAsString else java.util.UUID.randomUUID().toString
                    val name = if (item.has("name")) item.get("name").getAsString else ""
                    val argsStr = if (item.has("arguments")) item.get("arguments").getAsString else "{}"
                    val args =
                        try JsonParser.parseString(argsStr).getAsJsonObject
                        catch { case _: Exception => new JsonObject() }
                    sink(AIStreamEvent.ToolUseStart(id, name))
                    sink(AIStreamEvent.ToolUseComplete(id, name, args))
                    blocks += AIContentBlock.ToolUseBlock(id, name, args)
                    hasToolUse = true
                case _ => // ignore
            }
        }
        // Normalize OpenAI's truncation signal to the same "max_tokens" reason the
        // Anthropic path uses, so AgentLoop's auto-continue applies uniformly. The
        // Responses API marks a length cutoff as status="incomplete" with
        // incomplete_details.reason="max_output_tokens".
        val openAiStopReason =
            if (
                response.has("status") && response.get("status").getAsString == "incomplete" &&
                response.has("incomplete_details") && !response.get("incomplete_details").isJsonNull &&
                response.getAsJsonObject("incomplete_details").has("reason") &&
                response.getAsJsonObject("incomplete_details").get("reason").getAsString == "max_output_tokens"
            )
                "max_tokens"
            else ""
        AIToolResponse(blocks.toList, wantsToolUse = hasToolUse, stopReason = openAiStopReason)
    }

    private def openaiInputArray(messages: Seq[(String, List[AIContentBlock])]): JsonArray = {
        val arr = new JsonArray()
        messages.foreach { case (role, blocks) =>
            // OpenAI Responses input shape: messages with content array or simple text.
            // For tool_result blocks, OpenAI expects {type: "function_call_output", call_id, output}.
            blocks.foreach {
                case AIContentBlock.ToolResultBlock(toolUseId, content, _) =>
                    val o = new JsonObject()
                    o.addProperty("type", "function_call_output")
                    o.addProperty("call_id", toolUseId)
                    o.addProperty("output", content)
                    arr.add(o)
                case AIContentBlock.ToolUseBlock(id, name, input) =>
                    val o = new JsonObject()
                    o.addProperty("type", "function_call")
                    o.addProperty("call_id", id)
                    o.addProperty("name", name)
                    o.addProperty("arguments", input.toString)
                    arr.add(o)
                case AIContentBlock.TextBlock(text) =>
                    val o = new JsonObject()
                    o.addProperty("role", role)
                    o.addProperty("content", text)
                    arr.add(o)
                case AIContentBlock.ThinkingBlock(_, _) =>
                // Don't forward thinking to OpenAI — it's an Anthropic concept and
                // OpenAI ignores or rejects it. The summary will be re-derived on
                // the next call.
            }
        }
        arr
    }
}
