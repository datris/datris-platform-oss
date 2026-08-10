package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonObject}
import ai.datris.model.{AIConfig, DatrisEnvironment, DatrisException}
import ai.datris.util.aiutil.AIProviders.{addTokenLimit, rejectsSamplingParams, responsesEndpointFor, usesResponsesApi}
import ai.datris.util.aiutil.AIWebSearch.{attachWebSearchToolAnthropic, attachWebSearchToolResponses}
import org.apache.http.HttpHeaders
import org.apache.http.client.methods.HttpPost
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.util.EntityUtils

import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext
import org.slf4j.{Logger, LoggerFactory}

/** HTTP plumbing for the AI providers: shared pooled clients, retry behavior,
  * request construction, error explanation, and the core callAI* entry points.
  * Extracted verbatim from AIUtil — AIUtil remains the public facade.
  */
object AIHttp {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // Reusable HTTP clients — one lightweight client for Ollama (no SSL), one with SSL for cloud providers
    private lazy val ollamaClient: CloseableHttpClient = HttpClients.createDefault()
    private[aiutil] lazy val sslClient: CloseableHttpClient = {
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
    private[aiutil] def explainAIError(status: Int, body: String, model: String): String = {
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

    private[aiutil] def executeWithRetry(client: CloseableHttpClient, httpPostFactory: () => HttpPost, modelLabel: String = null): String = {
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
    private[aiutil] def buildHttpPost(aiConfig: AIConfig, jsonBody: String, endpoint: String): HttpPost = {
        val httpPost = new HttpPost(endpoint)
        aiConfig.provider.toLowerCase match {
            case "openai" =>
                httpPost.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + aiConfig.apiKey)
            case "azure" =>
                // Azure's v1 API accepts either header; legacy deployment-scoped
                // URLs (/openai/deployments/...?api-version=...) accept only
                // api-key. Sending both makes every Azure endpoint shape work.
                httpPost.addHeader("api-key", aiConfig.apiKey)
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
}
