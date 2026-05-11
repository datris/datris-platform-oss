package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{Gson, JsonArray, JsonObject}
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
            SSLConnectionSocketFactory.getDefaultHostnameVerifier)
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
    private def executeWithRetry(client: CloseableHttpClient, httpPostFactory: () => HttpPost): String = {
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
                throw new DatrisException("AI API returned error status: " + statusCode + ", body: " + EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8))
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
            case _ =>  // anthropic
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
        logger.info("Calling OpenAI Responses API, endpoint: " + endpoint + ", model: " + aiConfig.model + ", messages: " + messages.size + ", maxTokens: " + maxTokens + ", webSearch: " + useWebSearch)

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
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody, endpoint))
    }

    // OpenAI reasoning / GPT-5 family models reject `max_tokens` and require
    // `max_completion_tokens`. Detect by model-name prefix so we stay compatible
    // with both the legacy (gpt-4*, gpt-3.5*) and newer parameter contracts.
    private def openAiTokenField(model: String): String = {
        val m = if (model == null) "" else model.toLowerCase
        if (m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3") ||
            m.startsWith("o4") || m.startsWith("o5")) "max_completion_tokens"
        else "max_tokens"
    }

    private def addTokenLimit(requestObj: JsonObject, provider: String, model: String, maxTokens: Int): Unit = {
        val field = if (provider.toLowerCase == "openai") openAiTokenField(model) else "max_tokens"
        requestObj.addProperty(field, maxTokens)
    }

    /** Resolve an apiKey for an AI provider section. Used by every AI-config loader
      * (ai-primary, codegen, embedding, web-search) so the same fallback applies
      * uniformly:
      *
      *   1. The secret's own `apiKey` if non-empty (Vault-stored, the normal path)
      *   2. The matching `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` env var, but ONLY
      *      in single-tenant mode — env vars hold the platform's keys, and in
      *      multi-tenant deployments those keys belong to Datris, not to each
      *      tenant. Multi-tenant tenants must provide their own keys explicitly.
      *
      * Returns the empty string when neither is available; callers decide whether
      * that's fatal (ai-primary) or skippable (web-search). */
    def resolveApiKey(rawKey: String, provider: String, multiTenant: Boolean): String = {
        if (rawKey != null && rawKey.nonEmpty) return rawKey
        if (multiTenant) return ""
        provider.toLowerCase match {
            case "anthropic" => sys.env.getOrElse("ANTHROPIC_API_KEY", "")
            case "openai"    => sys.env.getOrElse("OPENAI_API_KEY", "")
            case _           => ""
        }
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
        if (configProvider != provider) return false                    // out-of-band path
        if (provider == "openai" && !usesResponsesApi(aiConfig)) return false  // Chat Completions can't carry the tool
        provider == "anthropic" || provider == "openai"
    }

    /** Attach the Anthropic `web_search_20250305` server tool to an outgoing request when
      * the caller opted in AND the request provider matches the configured web-search
      * provider (Anthropic). The mismatched-provider case is handled by `runWebSearch`
      * out of band before the main call. */
    private def attachWebSearchToolAnthropic(requestObj: JsonObject, aiConfig: AIConfig, useWebSearch: Boolean): Unit = {
        if (!useWebSearch || !canAttachNativeWebSearch(aiConfig) || aiConfig.provider.toLowerCase != "anthropic") return
        val tools = new JsonArray()
        val tool  = new JsonObject()
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
        val tool  = new JsonObject()
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
            case None    => WebSearchPlan.Off
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
            model    = if (ws.model.nonEmpty) ws.model else defaultModelFor(ws.provider),
            apiKey   = ws.apiKey,
            version  = ws.version
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
        case "openai"    => "https://api.openai.com/v1/responses"
        case _           => ""
    }

    /** Default model for web-search runs. Both providers are picked for SPEED of
      * summarization, not reasoning depth — the task is "read N web pages and
      * write a useful research note." Codex / reasoning models add 30-60s with
      * no quality lift for this. Override via the Web Search section's Advanced
      * model field if you want a different one. */
    private def defaultModelFor(provider: String): String = provider.toLowerCase match {
        case "anthropic" => "claude-sonnet-4-6"
        case "openai"    => "gpt-5.5"
        case _           => ""
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

        logger.info("Calling AI with custom system prompt, endpoint: " + aiConfig.endpoint + ", provider: " + aiConfig.provider + ", model: " + aiConfig.model + ", webSearch: " + useWebSearch)

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
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody, aiConfig.endpoint))
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

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig, maxTokens: Int, temperature: Double, useWebSearch: Boolean): String = {
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the Vault secret is configured.")

        if (usesResponsesApi(aiConfig))
            return callResponsesApi(systemPrompt, messages, aiConfig, maxTokens, temperature, useWebSearch)

        logger.info("Calling AI with conversation, " + messages.size + " messages, endpoint: " + aiConfig.endpoint + ", provider: " + aiConfig.provider + ", model: " + aiConfig.model + ", maxTokens: " + maxTokens + ", webSearch: " + useWebSearch)

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
        if (temperature >= 0) requestObj.addProperty("temperature", temperature)
        requestObj.add("messages", messagesArr)

        // For Anthropic, system instruction goes as a top-level field
        if (aiConfig.provider.toLowerCase.equals("anthropic")) {
            requestObj.addProperty("system", systemPrompt)
        }

        attachWebSearchToolAnthropic(requestObj, aiConfig, useWebSearch)

        val jsonBody = requestObj.toString
        val client = getClient(aiConfig.provider)
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody, aiConfig.endpoint))
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

        logger.info("Calling AI endpoint: " + aiConfig.endpoint + ", provider: " + aiConfig.provider + ", model: " + aiConfig.model + ", prompt length: " + prompt.length + " chars, webSearch: " + useWebSearch)

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
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody, aiConfig.endpoint))
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
}
