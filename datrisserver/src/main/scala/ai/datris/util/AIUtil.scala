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
     * Pulled out so all three callAI* methods can share the same logic.
     */
    private def buildHttpPost(aiConfig: AIConfig, jsonBody: String): HttpPost = {
        val httpPost = new HttpPost(aiConfig.endpoint)
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
        callAIWithSystem(systemPrompt, userPrompt, DatrisEnvironment.current.aiConfig)

    def callAIWithSystem(systemPrompt: String, userPrompt: String, aiConfig: AIConfig): String = {
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the Vault secret is configured.")

        logger.info("Calling AI with custom system prompt, endpoint: " + aiConfig.endpoint + ", provider: " + aiConfig.provider + ", model: " + aiConfig.model)

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
        requestObj.addProperty("max_tokens", 8192)
        requestObj.add("messages", messagesArr)

        if (aiConfig.provider.toLowerCase.equals("anthropic")) {
            requestObj.addProperty("system", systemPrompt)
        }

        val jsonBody = requestObj.toString
        val client = getClient(aiConfig.provider)
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody))
    }

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)]): String =
        callAIWithMessages(systemPrompt, messages, DatrisEnvironment.current.aiConfig, 8192)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig): String =
        callAIWithMessages(systemPrompt, messages, aiConfig, 8192)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], maxTokens: Int): String =
        callAIWithMessages(systemPrompt, messages, DatrisEnvironment.current.aiConfig, maxTokens, -1.0)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], maxTokens: Int, temperature: Double): String =
        callAIWithMessages(systemPrompt, messages, DatrisEnvironment.current.aiConfig, maxTokens, temperature)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig, maxTokens: Int): String =
        callAIWithMessages(systemPrompt, messages, aiConfig, maxTokens, -1.0)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig, maxTokens: Int, temperature: Double): String = {
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the Vault secret is configured.")

        logger.info("Calling AI with conversation, " + messages.size + " messages, endpoint: " + aiConfig.endpoint + ", provider: " + aiConfig.provider + ", model: " + aiConfig.model + ", maxTokens: " + maxTokens)

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
        requestObj.addProperty("max_tokens", maxTokens)
        if (temperature >= 0) requestObj.addProperty("temperature", temperature)
        requestObj.add("messages", messagesArr)

        // For Anthropic, system instruction goes as a top-level field
        if (aiConfig.provider.toLowerCase.equals("anthropic")) {
            requestObj.addProperty("system", systemPrompt)
        }

        val jsonBody = requestObj.toString
        val client = getClient(aiConfig.provider)
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody))
    }

    def callAI(prompt: String): String =
        callAI(prompt, DatrisEnvironment.current.aiConfig)

    def callAI(prompt: String, aiConfig: AIConfig): String = {
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the Vault secret is configured.")

        logger.info("Calling AI endpoint: " + aiConfig.endpoint + ", provider: " + aiConfig.provider + ", model: " + aiConfig.model + ", prompt length: " + prompt.length + " chars")

        val systemInstruction = "You are a data validation engine. Output ONLY valid JSON arrays. Never describe, summarize, or ask questions about the data."

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
        requestObj.addProperty("max_tokens", 8192)
        requestObj.add("messages", messagesArr)

        // For Anthropic, system instruction goes as a top-level field
        if (aiConfig.provider.toLowerCase.equals("anthropic")) {
            requestObj.addProperty("system", systemInstruction)
        }

        val jsonBody = requestObj.toString
        val client = getClient(aiConfig.provider)
        executeWithRetry(client, () => buildHttpPost(aiConfig, jsonBody))
    }

    def extractText(apiResponse: String): String =
        extractText(apiResponse, DatrisEnvironment.current.aiConfig)

    def extractText(apiResponse: String, aiConfig: AIConfig): String = {
        val gson = new Gson()
        val responseMap = gson.fromJson(apiResponse, classOf[java.util.Map[String, Any]])

        val text = aiConfig.provider.toLowerCase match {
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
                contentList.get(0).get("text").asInstanceOf[String]
        }

        if (text == null || text.trim.isEmpty)
            throw new DatrisException("AI response text was empty")

        text.trim
    }
}
