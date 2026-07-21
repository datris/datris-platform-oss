package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonObject
import ai.datris.model.AIConfig

/** Facade for the AI utilities, kept for source compatibility with the 100+
  * call sites across the codebase. The implementation lives in the
  * `ai.datris.util.aiutil` package, split by concern:
  *
  *   - [[aiutil.AIHttp]]           — HTTP plumbing (pooled clients, retry, request
  *                                   construction) and the core callAI* entry points
  *   - [[aiutil.AIProviders]]      — provider detection/routing, quirk tables,
  *                                   API-key resolution, context sizing
  *   - [[aiutil.AIWebSearch]]      — web-search planning, native tool attachment,
  *                                   out-of-band search + injection
  *   - [[aiutil.AIResponseParser]] — text and citation extraction
  *   - [[aiutil.AIStreaming]]      — tool-use streaming for the Assistant agent loop
  *
  * Every member here is a pure delegation stub (or type/companion alias) — no
  * behavior lives in this object.
  *
  * NOTE: the sub-package is named `aiutil` (not `ai`) deliberately: a package
  * named `ai` under `ai.datris.util` would shadow the root `ai` package for
  * every file in `ai.datris.util`, breaking their `import ai.datris...` lines.
  */
object AIUtil {

    // ---------- Provider detection / keys / context sizing (aiutil.AIProviders) ----------

    def resolveApiKey(rawKey: String, provider: String, multiTenant: Boolean, env: String): String =
        aiutil.AIProviders.resolveApiKey(rawKey, provider, multiTenant, env)

    def providerKeyFromStore(env: String, provider: String): String =
        aiutil.AIProviders.providerKeyFromStore(env, provider)

    /** Whether a given AIConfig supports extended thinking — Anthropic Claude 4.x. */
    def supportsExtendedThinking(aiConfig: AIConfig): Boolean =
        aiutil.AIProviders.supportsExtendedThinking(aiConfig)

    def maxInputChars(): Int =
        aiutil.AIProviders.maxInputChars()

    def fitsInContext(text: String): Boolean =
        aiutil.AIProviders.fitsInContext(text)

    def calculateBatchSize(rows: List[String], promptOverheadChars: Int): Int =
        aiutil.AIProviders.calculateBatchSize(rows, promptOverheadChars)

    // ---------- Web search (aiutil.AIWebSearch) ----------

    type WebSearchResult = aiutil.AIWebSearch.WebSearchResult
    val WebSearchResult: aiutil.AIWebSearch.WebSearchResult.type = aiutil.AIWebSearch.WebSearchResult

    type WebSearchPlan = aiutil.AIWebSearch.WebSearchPlan
    val WebSearchPlan: aiutil.AIWebSearch.WebSearchPlan.type = aiutil.AIWebSearch.WebSearchPlan

    def webSearchActive: Boolean =
        aiutil.AIWebSearch.webSearchActive

    def planWebSearch(aiConfig: AIConfig, searchQuery: String): WebSearchPlan =
        aiutil.AIWebSearch.planWebSearch(aiConfig, searchQuery)

    def renderInjectedContext(plan: WebSearchPlan): String =
        aiutil.AIWebSearch.renderInjectedContext(plan)

    def useNative(plan: WebSearchPlan): Boolean =
        aiutil.AIWebSearch.useNative(plan)

    def runWebSearch(query: String): Option[WebSearchResult] =
        aiutil.AIWebSearch.runWebSearch(query)

    // ---------- Core AI calls (aiutil.AIHttp) ----------

    def callAIWithSystem(systemPrompt: String, userPrompt: String): String =
        aiutil.AIHttp.callAIWithSystem(systemPrompt, userPrompt)

    def callAIWithSystem(systemPrompt: String, userPrompt: String, aiConfig: AIConfig): String =
        aiutil.AIHttp.callAIWithSystem(systemPrompt, userPrompt, aiConfig)

    def callAIWithSystem(systemPrompt: String, userPrompt: String, aiConfig: AIConfig, useWebSearch: Boolean): String =
        aiutil.AIHttp.callAIWithSystem(systemPrompt, userPrompt, aiConfig, useWebSearch)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)]): String =
        aiutil.AIHttp.callAIWithMessages(systemPrompt, messages)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig): String =
        aiutil.AIHttp.callAIWithMessages(systemPrompt, messages, aiConfig)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], maxTokens: Int): String =
        aiutil.AIHttp.callAIWithMessages(systemPrompt, messages, maxTokens)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], maxTokens: Int, temperature: Double): String =
        aiutil.AIHttp.callAIWithMessages(systemPrompt, messages, maxTokens, temperature)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig, maxTokens: Int): String =
        aiutil.AIHttp.callAIWithMessages(systemPrompt, messages, aiConfig, maxTokens)

    def callAIWithMessages(systemPrompt: String, messages: Seq[(String, String)], aiConfig: AIConfig, maxTokens: Int, temperature: Double): String =
        aiutil.AIHttp.callAIWithMessages(systemPrompt, messages, aiConfig, maxTokens, temperature)

    def callAIWithMessages(
        systemPrompt: String,
        messages: Seq[(String, String)],
        aiConfig: AIConfig,
        maxTokens: Int,
        temperature: Double,
        useWebSearch: Boolean
    ): String =
        aiutil.AIHttp.callAIWithMessages(systemPrompt, messages, aiConfig, maxTokens, temperature, useWebSearch)

    def callAI(prompt: String): String =
        aiutil.AIHttp.callAI(prompt)

    def callAI(prompt: String, aiConfig: AIConfig): String =
        aiutil.AIHttp.callAI(prompt, aiConfig)

    def callAI(prompt: String, useWebSearch: Boolean): String =
        aiutil.AIHttp.callAI(prompt, useWebSearch)

    def callAI(prompt: String, aiConfig: AIConfig, useWebSearch: Boolean): String =
        aiutil.AIHttp.callAI(prompt, aiConfig, useWebSearch)

    // ---------- Response parsing (aiutil.AIResponseParser) ----------

    def extractCitations(apiResponse: String, aiConfig: AIConfig): List[(String, String)] =
        aiutil.AIResponseParser.extractCitations(apiResponse, aiConfig)

    def extractText(apiResponse: String): String =
        aiutil.AIResponseParser.extractText(apiResponse)

    def extractText(apiResponse: String, aiConfig: AIConfig): String =
        aiutil.AIResponseParser.extractText(apiResponse, aiConfig)

    // ---------- Tool-use streaming (aiutil.AIStreaming) ----------

    type AIContentBlock = aiutil.AIStreaming.AIContentBlock
    val AIContentBlock: aiutil.AIStreaming.AIContentBlock.type = aiutil.AIStreaming.AIContentBlock

    type AIStreamEvent = aiutil.AIStreaming.AIStreamEvent
    val AIStreamEvent: aiutil.AIStreaming.AIStreamEvent.type = aiutil.AIStreaming.AIStreamEvent

    type AIToolResponse = aiutil.AIStreaming.AIToolResponse
    val AIToolResponse: aiutil.AIStreaming.AIToolResponse.type = aiutil.AIStreaming.AIToolResponse

    def callAIWithToolsStreaming(
        aiConfig: AIConfig,
        system: String,
        messages: Seq[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        enableThinking: Boolean,
        maxTokens: Int,
        sink: AIStreamEvent => Unit,
        cancelled: () => Boolean = () => false
    ): AIToolResponse =
        aiutil.AIStreaming.callAIWithToolsStreaming(aiConfig, system, messages, tools, enableThinking, maxTokens, sink, cancelled)
}
