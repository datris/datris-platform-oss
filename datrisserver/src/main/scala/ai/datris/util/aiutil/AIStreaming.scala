package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import ai.datris.model.{AIConfig, DatrisException}
import ai.datris.util.aiutil.AIHttp.{buildHttpPost, executeWithRetry, explainAIError, sslClient}
import ai.datris.util.aiutil.AIProviders.{addTokenLimit, rejectsSamplingParams, responsesEndpointFor}
import org.apache.http.util.EntityUtils

import java.nio.charset.StandardCharsets
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.JavaConverters._

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
object AIStreaming {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

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
            case "bedrock" => bedrockNonStreamingCall(aiConfig, system, messages, tools, enableThinking, maxTokens, sink)
            case "openai" => openaiNonStreamingCall(aiConfig, system, messages, tools, maxTokens, sink)
            case "azure" | "grok" => chatCompletionsNonStreamingCall(aiConfig, system, messages, tools, maxTokens, sink)
            case other =>
                throw new DatrisException("Provider '" + other + "' is not yet supported for chat. " +
                    "The chat assistants run on the AI Primary provider — set it to Anthropic, Amazon Bedrock, OpenAI, Azure OpenAI, or Grok in Configuration. " +
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
        // display: "summarized" is sent with the adaptive form; a model that
        // takes adaptive but rejects the display field should fall down the
        // ladder like any other thinking-shape rejection.
        m.contains("thinking.display") ||
        (m.contains("thinking") && m.contains("not supported")) ||
        m.contains("output_config") ||
        // Bedrock validates the invoke body against a per-model schema and
        // phrases unknown-field rejections as "Malformed input request:
        // extraneous key [thinking] is not permitted" — same ladder applies.
        (m.contains("extraneous key") && (m.contains("thinking") || m.contains("output_config")))
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
                // Claude 5-family default is display "omitted": thinking blocks
                // stream with EMPTY text, so the UI's reasoning block/ticker
                // never appears and a long think reads as a hang. "summarized"
                // streams a readable summary; billing is identical either way.
                thinkingObj.addProperty("display", "summarized")
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
                                            catch {
                                                case ex: Exception =>
                                                    logger.warn("Malformed tool input JSON for tool \"" + b.toolName + "\" — substituting empty input", ex)
                                                    new JsonObject()
                                            }
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
                        case e: Exception => // skip malformed event line; the next one will likely be fine
                            logger.debug("Skipping malformed Anthropic stream event line", e)
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

    // ---------- Bedrock non-streaming path ----------

    /** Tool-use call for Claude on Amazon Bedrock. Same Anthropic Messages
      * request/response shape as the streaming path above, but non-streaming:
      * Bedrock's streaming endpoint uses AWS event-stream binary framing (not
      * SSE), so we invoke non-streaming and synthesize the sink events at the
      * end — same pattern as the Azure fallback. buildHttpPost handles the
      * Bedrock specifics (SigV4 signing, model-in-URL, anthropic_version).
      * Runs the same thinking-form fallback ladder as the Anthropic path,
      * sharing thinkingFormCache. */
    private def bedrockNonStreamingCall(
        aiConfig: AIConfig,
        system: String,
        messages: Seq[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        enableThinking: Boolean,
        maxTokens: Int,
        sink: AIStreamEvent => Unit
    ): AIToolResponse = {
        if (!enableThinking) {
            return bedrockNonStreamingCallOnce(aiConfig, system, messages, tools, ThinkingForm.Unsupported, maxTokens, sink)
        }

        val key = thinkingCacheKey(aiConfig)
        Option(thinkingFormCache.get(key)) match {
            case Some(form) =>
                bedrockNonStreamingCallOnce(aiConfig, system, messages, tools, form, maxTokens, sink)
            case None =>
                val ladder: List[ThinkingForm] = List(ThinkingForm.Adaptive, ThinkingForm.Enabled, ThinkingForm.Unsupported)
                var lastEx: Option[DatrisException] = None
                val iter = ladder.iterator
                while (iter.hasNext) {
                    val form = iter.next()
                    try {
                        val r = bedrockNonStreamingCallOnce(aiConfig, system, messages, tools, form, maxTokens, sink)
                        thinkingFormCache.put(key, form)
                        logger.info("Assistant: cached thinking form for " + key + " = " + form)
                        return r
                    } catch {
                        case e: DatrisException if isThinkingApiError(e.getMessage) =>
                            logger.info("Assistant: " + form + " rejected by " + key + " (" + e.getMessage.take(200) + "), trying next form")
                            lastEx = Some(e)
                        case e: DatrisException =>
                            throw e
                    }
                }
                throw lastEx.getOrElse(new DatrisException("Bedrock call failed with no fallback form succeeding"))
        }
    }

    private def bedrockNonStreamingCallOnce(
        aiConfig: AIConfig,
        system: String,
        messages: Seq[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        form: ThinkingForm,
        maxTokens: Int,
        sink: AIStreamEvent => Unit
    ): AIToolResponse = {
        val req = new JsonObject()
        // `model` is moved into the invoke URL (and stripped from the body) by
        // BedrockSupport at request-build time; no `stream` — non-streaming only.
        req.addProperty("model", aiConfig.model)
        req.addProperty("max_tokens", maxTokens)
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
                // Claude 5-family default is display "omitted": thinking blocks
                // stream with EMPTY text, so the UI's reasoning block/ticker
                // never appears and a long think reads as a hang. "summarized"
                // streams a readable summary; billing is identical either way.
                thinkingObj.addProperty("display", "summarized")
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

        logger.info("Assistant: Bedrock invoke call, model=" + aiConfig.model + ", tools=" + tools.size +
            ", thinkingForm=" + form + ", maxTokens=" + maxTokens + ", messages=" + messages.size)

        val raw = executeWithRetry(sslClient, () => buildHttpPost(aiConfig, req.toString, aiConfig.endpoint), aiConfig.model)
        val response = JsonParser.parseString(raw).getAsJsonObject

        val stopReason =
            if (response.has("stop_reason") && !response.get("stop_reason").isJsonNull) response.get("stop_reason").getAsString else ""
        // Claude Fable 5's safety classifiers decline with HTTP 200 +
        // stop_reason "refusal" (empty or partial content) — not an HTTP error,
        // so executeWithRetry never sees it. Surface it legibly instead of
        // returning an empty turn.
        if (stopReason == "refusal") {
            val msg = "Model '" + aiConfig.model + "' declined this request (stop_reason: refusal). " +
                "This can be a safety-classifier false positive — rephrase the request or switch the slot to a different model."
            sink(AIStreamEvent.Error(msg))
            throw new DatrisException(msg)
        }

        val blocks = scala.collection.mutable.ListBuffer.empty[AIContentBlock]
        var hasToolUse = false
        val content = if (response.has("content") && response.get("content").isJsonArray) response.getAsJsonArray("content") else new JsonArray()
        content.asScala.map(_.getAsJsonObject).foreach { block =>
            val t = if (block.has("type")) block.get("type").getAsString else "text"
            t match {
                case "text" =>
                    val text = if (block.has("text")) block.get("text").getAsString else ""
                    if (text.nonEmpty) {
                        sink(AIStreamEvent.TextDelta(text))
                        blocks += AIContentBlock.TextBlock(text)
                    }
                case "thinking" =>
                    val thinking = if (block.has("thinking")) block.get("thinking").getAsString else ""
                    val signature = if (block.has("signature")) block.get("signature").getAsString else ""
                    if (thinking.nonEmpty) sink(AIStreamEvent.ThinkingDelta(thinking))
                    blocks += AIContentBlock.ThinkingBlock(thinking, signature)
                case "tool_use" =>
                    val id = if (block.has("id")) block.get("id").getAsString else java.util.UUID.randomUUID().toString
                    val name = if (block.has("name")) block.get("name").getAsString else ""
                    val input =
                        if (block.has("input") && block.get("input").isJsonObject) block.getAsJsonObject("input")
                        else new JsonObject()
                    sink(AIStreamEvent.ToolUseStart(id, name))
                    sink(AIStreamEvent.ToolUseComplete(id, name, input))
                    blocks += AIContentBlock.ToolUseBlock(id, name, input)
                    hasToolUse = true
                case _ => // redacted_thinking etc. — nothing renderable; drop
            }
        }

        AIToolResponse(blocks.toList, wantsToolUse = stopReason == "tool_use" || hasToolUse, stopReason = stopReason)
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
                        catch {
                            case e: Exception =>
                                logger.warn("Malformed function_call arguments for tool \"" + name + "\" — substituting empty input", e)
                                new JsonObject()
                        }
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

    // ---------- Generic chat/completions fallback (Azure OpenAI) ----------

    /** Tool-use call over the plain chat/completions wire. Non-streaming —
      * synthesizes the same sink events at the end so the UI rendering code
      * stays provider-agnostic. Used for providers that shouldn't touch the
      * OpenAI Responses API: Azure's /openai/v1/responses availability is
      * region/model dependent, so azure always speaks chat/completions against
      * the configured endpoint. Endpoint, auth, and token field all come from
      * the AIConfig/provider, so this path is reusable for any future
      * OpenAI-wire-compatible provider. */
    private def chatCompletionsNonStreamingCall(
        aiConfig: AIConfig,
        system: String,
        messages: Seq[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        maxTokens: Int,
        sink: AIStreamEvent => Unit
    ): AIToolResponse = {
        val req = new JsonObject()
        req.addProperty("model", aiConfig.model)
        addTokenLimit(req, aiConfig.provider, aiConfig.model, maxTokens)
        req.add("messages", chatCompletionsMessages(system, messages))

        if (tools.nonEmpty) {
            val toolsArr = new JsonArray()
            tools.foreach { t =>
                // chat/completions tool shape: {type:"function", function:{name, description, parameters}}
                val fn = new JsonObject()
                fn.addProperty("name", t.get("name").getAsString)
                if (t.has("description")) fn.addProperty("description", t.get("description").getAsString)
                if (t.has("input_schema")) fn.add("parameters", t.get("input_schema"))
                val ot = new JsonObject()
                ot.addProperty("type", "function")
                ot.add("function", fn)
                toolsArr.add(ot)
            }
            req.add("tools", toolsArr)
        }

        logger.info("Assistant: " + aiConfig.provider + " chat/completions call, model=" + aiConfig.model + ", tools=" + tools.size +
            ", maxTokens=" + maxTokens + ", messages=" + messages.size)

        val raw = executeWithRetry(sslClient, () => buildHttpPost(aiConfig, req.toString, aiConfig.endpoint), aiConfig.model)
        val response = JsonParser.parseString(raw).getAsJsonObject
        val choices = response.getAsJsonArray("choices")
        if (choices == null || choices.size() == 0) return AIToolResponse(Nil, wantsToolUse = false)
        val choice = choices.get(0).getAsJsonObject
        val message = choice.getAsJsonObject("message")
        if (message == null) return AIToolResponse(Nil, wantsToolUse = false)

        val blocks = scala.collection.mutable.ListBuffer.empty[AIContentBlock]
        var hasToolUse = false

        if (message.has("content") && !message.get("content").isJsonNull) {
            val text = message.get("content").getAsString
            if (text.nonEmpty) {
                sink(AIStreamEvent.TextDelta(text))
                blocks += AIContentBlock.TextBlock(text)
            }
        }
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray) {
            message.getAsJsonArray("tool_calls").asScala.foreach { tc =>
                val tco = tc.getAsJsonObject
                val id = if (tco.has("id")) tco.get("id").getAsString else java.util.UUID.randomUUID().toString
                val fn = tco.getAsJsonObject("function")
                val name = if (fn != null && fn.has("name")) fn.get("name").getAsString else ""
                val argsStr = if (fn != null && fn.has("arguments")) fn.get("arguments").getAsString else "{}"
                val args =
                    try JsonParser.parseString(argsStr).getAsJsonObject
                    catch {
                        case e: Exception =>
                            logger.warn("Malformed tool_call arguments for tool \"" + name + "\" — substituting empty input", e)
                            new JsonObject()
                    }
                sink(AIStreamEvent.ToolUseStart(id, name))
                sink(AIStreamEvent.ToolUseComplete(id, name, args))
                blocks += AIContentBlock.ToolUseBlock(id, name, args)
                hasToolUse = true
            }
        }

        // Normalize finish_reason to the shared stop reasons: "length" → "max_tokens"
        // so AgentLoop's auto-continue applies uniformly.
        val finish =
            if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull) choice.get("finish_reason").getAsString else ""
        val stopReason = if (finish == "length") "max_tokens" else ""
        AIToolResponse(blocks.toList, wantsToolUse = hasToolUse, stopReason = stopReason)
    }

    /** Build a chat/completions `messages` array from agent-loop content blocks:
      * text → {role, content}; assistant tool_use → assistant message carrying
      * `tool_calls`; tool_result → {role:"tool", tool_call_id, content} (emitted
      * before the turn's text so tool outputs directly follow the assistant's
      * tool_calls message); thinking blocks are Anthropic-internal and never
      * forwarded. */
    private def chatCompletionsMessages(system: String, messages: Seq[(String, List[AIContentBlock])]): JsonArray = {
        val arr = new JsonArray()
        if (system != null && system.nonEmpty) {
            val sys = new JsonObject()
            sys.addProperty("role", "system")
            sys.addProperty("content", system)
            arr.add(sys)
        }
        messages.foreach { case (role, blocks) =>
            val text = new StringBuilder
            val toolCalls = new JsonArray()
            blocks.foreach {
                case AIContentBlock.TextBlock(t) => text.append(t)
                case AIContentBlock.ToolUseBlock(id, name, input) =>
                    val fn = new JsonObject()
                    fn.addProperty("name", name)
                    fn.addProperty("arguments", input.toString)
                    val tc = new JsonObject()
                    tc.addProperty("id", id)
                    tc.addProperty("type", "function")
                    tc.add("function", fn)
                    toolCalls.add(tc)
                case AIContentBlock.ToolResultBlock(toolUseId, content, _) =>
                    val o = new JsonObject()
                    o.addProperty("role", "tool")
                    o.addProperty("tool_call_id", toolUseId)
                    o.addProperty("content", content)
                    arr.add(o)
                case AIContentBlock.ThinkingBlock(_, _) => // never forwarded
            }
            if (text.nonEmpty || toolCalls.size() > 0) {
                val m = new JsonObject()
                m.addProperty("role", role)
                m.addProperty("content", text.toString)
                if (toolCalls.size() > 0) m.add("tool_calls", toolCalls)
                arr.add(m)
            }
        }
        arr
    }
}
