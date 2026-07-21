package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonObject}
import ai.datris.model.{AIConfig, DatrisEnvironment}
import ai.datris.util.aiutil.AIProviders.{defaultEndpointFor, defaultModelFor, usesResponsesApi}

import org.slf4j.{Logger, LoggerFactory}

/** Web-search flow: the WebSearchPlan decision, native tool attachment for
  * Anthropic/OpenAI requests, and the out-of-band search call whose results are
  * injected into the main call's system prompt.
  * Extracted verbatim from AIUtil — AIUtil remains the public facade.
  */
object AIWebSearch {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

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
    private[aiutil] def attachWebSearchToolAnthropic(requestObj: JsonObject, aiConfig: AIConfig, useWebSearch: Boolean): Unit = {
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
    private[aiutil] def attachWebSearchToolResponses(requestObj: JsonObject, aiConfig: AIConfig, useWebSearch: Boolean): Unit = {
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
            val responseText = AIHttp.callAIWithSystem(systemPrompt, query, searchAiConfig, useWebSearch = true)
            val notes = AIResponseParser.extractText(responseText, searchAiConfig)
            val citations = AIResponseParser.extractCitations(responseText, searchAiConfig)
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
}
