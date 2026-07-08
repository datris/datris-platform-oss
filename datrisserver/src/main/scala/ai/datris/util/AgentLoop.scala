package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{JsonObject, JsonParser}
import ai.datris.model.{AIConfig, DatrisException}
import ai.datris.util.AIUtil.{AIContentBlock, AIStreamEvent, AIToolResponse}
import org.slf4j.{Logger, LoggerFactory}

/** Drives the in-product Assistant's tool-use loop.
  *
  * One call to `run` corresponds to one user turn. Internally we may iterate
  * many times: each iteration is an LLM call that may emit text + thinking +
  * tool_use blocks. If any tool_use blocks are present we execute them via
  * MCPClient, append the assistant turn (verbatim — including thinking blocks
  * for reasoning continuity) and a tool_result user turn, and recurse. We stop
  * when the model returns only text/thinking (no more tools to call) or we hit
  * `maxIterations`.
  *
  * Events from each iteration are pushed to `sink` as they happen so the UI
  * can render thinking, tool calls, and prose in real time.
  */
object AgentLoop {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Per-iteration / per-loop events surfaced to the UI as SSE. AIStreamEvent
      * deltas are forwarded directly; this trait wraps loop-level signals
      * (tool-result, done, error) that AIStreamEvent doesn't model. */
    sealed trait LoopEvent
    object LoopEvent {
        case object IterationStart                                       extends LoopEvent
        case class  ThinkingDelta(text: String)                          extends LoopEvent
        case class  TextDelta(text: String)                              extends LoopEvent
        case class  ToolUseStart(id: String, name: String)               extends LoopEvent
        /** Progress while the model composes a tool call's input — the UI shows
          * a live size counter on the running tool card. */
        case class  InputDelta(id: String, chars: Int)                   extends LoopEvent
        case class  ToolUseComplete(id: String, name: String, input: JsonObject) extends LoopEvent
        case class  ToolResult(id: String, name: String, result: String, isError: Boolean) extends LoopEvent
        /** Synthetic tool: agent is asking the user to provide a tap secret via a UI
          * form. The UI renders an inline credentials form on the matching tool card.
          * The loop ends after this so the user can submit; their next chat message
          * resumes the conversation. */
        case class  SecretRequest(id: String, secretName: String, fieldNames: List[String], reason: String) extends LoopEvent
        /** Transient system message — surfaced to the user as a small inline note,
          * not as part of the assistant's textual response. Currently used to tell
          * the user when the model was downgraded mid-request (e.g., Opus → Sonnet
          * after sustained `overloaded_error` from Anthropic). */
        case class  Notice(message: String)                              extends LoopEvent
        case object Done                                                 extends LoopEvent
        case class  Error(message: String)                               extends LoopEvent
    }

    /** The synthetic tool name the agent calls to ask the user for credentials.
      * Handled by AgentLoop directly — NOT dispatched to the MCP server. */
    val SyntheticSecretTool: String = "request_tap_secret_from_user"

    /** Tools whose `content` (base64 file bytes) we resolve from a staged
      * attachment when the model passes an `attachmentId` instead. The model
      * can't emit a real file's bytes, so it references the staged handle and
      * we substitute the bytes here, right before dispatching to MCP. */
    private val AttachmentTools: Set[String] = Set("create_pipeline", "upload_data", "profile_data", "upload_config")

    /** Max consecutive auto-continues after a max_tokens cutoff before yielding to
      * the user, so a genuinely unbounded answer can't loop indefinitely. */
    private val MaxAutoContinues: Int = 3

    /** Strip thinking blocks that lack a signature before replaying the assistant
      * turn back to Anthropic. A complete thinking block always carries a
      * signature; an unsigned one only appears when max_tokens truncated the turn
      * mid-reasoning, and Anthropic rejects unsigned thinking blocks with a 400.
      * If stripping leaves the turn empty, substitute a minimal text block so the
      * assistant turn stays well-formed. */
    private def sanitizeForReplay(content: List[AIContentBlock]): List[AIContentBlock] = {
        val filtered = content.filter {
            case t: AIContentBlock.ThinkingBlock => t.signature != null && t.signature.trim.nonEmpty
            case _ => true
        }
        if (filtered.isEmpty) List(AIContentBlock.TextBlock("…")) else filtered
    }

    /** Tool-result truncation cap. Anthropic charges by input tokens, and a
      * runaway 100KB result fed back as input for every subsequent iteration
      * blows out cost fast. The UI shows the full result via the SSE event;
      * only the version round-tripped back to the model is capped. */
    private val ToolResultMaxChars: Int = 4000

    /** Run the agent loop until completion. Blocks until done; emits events to
      * `sink` along the way. */
    def run(
        aiConfig: AIConfig,
        system: String,
        userMessages: Seq[(String, String)],
        toolDefs: List[JsonObject],
        apiKey: String,
        enableThinking: Boolean,
        maxIterations: Int,
        maxTokensPerCall: Int,
        cancelled: () => Boolean,
        sink: LoopEvent => Unit,
        attachments: Map[String, (String, Array[Byte])] = Map.empty
    ): Unit = {
        // Materialize the initial message list as content-block messages. We
        // grow it as iterations produce assistant turns + tool_result user turns.
        var messages: List[(String, List[AIContentBlock])] = userMessages.toList.map {
            case (role, text) => (role, List(AIContentBlock.TextBlock(text)))
        }

        // Convert MCP tool definitions to Anthropic's {name, description, input_schema}
        // shape. MCP already uses inputSchema (camelCase) — we just rename to input_schema
        // and pick the fields Anthropic accepts.
        val anthropicTools: List[JsonObject] = toolDefs.map(mcpToAnthropicTool)

        try {
            var iter = 0
            var continue = true
            // Consecutive auto-continues triggered by max_tokens cutoffs. Bounded so a
            // pathologically long answer can't loop forever; reset whenever the model
            // makes real progress (emits a tool call).
            var autoContinues = 0
            while (continue && iter < maxIterations && !cancelled()) {
                iter += 1
                sink(LoopEvent.IterationStart)

                val response: AIToolResponse = callWithRetryAndFallback(
                    aiConfig = aiConfig,
                    system = system,
                    messages = messages,
                    tools = anthropicTools,
                    enableThinking = enableThinking,
                    maxTokens = maxTokensPerCall,
                    sink = streamSinkAdapter(sink),
                    cancelled = cancelled,
                    notice = (msg: String) => sink(LoopEvent.Notice(msg))
                )

                if (cancelled()) {
                    sink(LoopEvent.Error("Cancelled by user"))
                    return
                }

                // Append the assistant turn (preserves thinking signatures for
                // Anthropic reasoning continuity). On Anthropic, drop any thinking
                // block missing a signature — a turn cut off mid-reasoning by
                // max_tokens yields an unsigned thinking block, which the next
                // request would reject with a 400.
                val assistantContent =
                    if (aiConfig.provider != null && aiConfig.provider.toLowerCase == "anthropic")
                        sanitizeForReplay(response.content)
                    else response.content
                messages = messages :+ ("assistant", assistantContent)

                // If the model wants to use tools, execute each one and append a
                // user turn with tool_result blocks. Otherwise we're done — UNLESS
                // the turn was cut off at the output-length limit, in which case we
                // auto-continue (bounded) instead of silently returning control to
                // the user, who would otherwise have to type "continue".
                val toolUses = response.content.collect { case t: AIContentBlock.ToolUseBlock => t }
                if (toolUses.isEmpty) {
                    if (response.stopReason == "max_tokens" && autoContinues < MaxAutoContinues) {
                        autoContinues += 1
                        sink(LoopEvent.Notice("Response reached the length limit — continuing automatically…"))
                        messages = messages :+ ("user", List[AIContentBlock](AIContentBlock.TextBlock(
                            "Your previous response was cut off at the output length limit. " +
                            "Continue exactly where you left off — do not repeat what you already said.")))
                    } else {
                        continue = false
                    }
                } else {
                    autoContinues = 0
                    var stopAfterBatch = false
                    val resultBlocks = toolUses.map { t =>
                        if (cancelled()) {
                            AIContentBlock.ToolResultBlock(t.id, "Cancelled by user", isError = true)
                        } else if (t.name == SyntheticSecretTool) {
                            // Synthetic tool — intercept rather than dispatch to MCP.
                            // Emit a SecretRequest event so the UI can render an inline
                            // form; record a synthetic tool_result so the message
                            // history stays well-formed for Anthropic's pairing rule;
                            // end the loop after this batch so the user can act.
                            val secretName = if (t.input.has("name")) t.input.get("name").getAsString else ""
                            val reason = if (t.input.has("reason")) t.input.get("reason").getAsString else ""
                            val fieldNames: List[String] =
                                if (t.input.has("fields") && t.input.get("fields").isJsonArray) {
                                    val arr = t.input.getAsJsonArray("fields")
                                    val buf = scala.collection.mutable.ListBuffer.empty[String]
                                    val it = arr.iterator()
                                    while (it.hasNext) buf += it.next().getAsString
                                    buf.toList
                                } else Nil
                            sink(LoopEvent.SecretRequest(t.id, secretName, fieldNames, reason))
                            stopAfterBatch = true
                            AIContentBlock.ToolResultBlock(
                                t.id,
                                "Credentials request displayed to the user. The user's next message will tell you whether they provided a new secret, picked an existing one, or declined. Wait for their reply before continuing.",
                                isError = false)
                        } else {
                            try {
                                // Substitute staged file bytes for an attachmentId before
                                // dispatching — the model never carries base64 in its tool
                                // input, only the short handle. The SSE tool_use event shows
                                // the model's original input (with attachmentId), so the
                                // bytes never reach the UI transcript either.
                                val toolInput = resolveAttachment(t.name, t.input, attachments)
                                val raw = MCPClient.callTool(t.name, toolInput, apiKey)
                                sink(LoopEvent.ToolResult(t.id, t.name, raw, isError = false))
                                AIContentBlock.ToolResultBlock(t.id, truncate(raw), isError = false)
                            } catch {
                                case e: Exception =>
                                    val msg = "Tool '" + t.name + "' failed: " + e.getMessage
                                    logger.warn("AgentLoop tool failure: " + msg)
                                    sink(LoopEvent.ToolResult(t.id, t.name, msg, isError = true))
                                    AIContentBlock.ToolResultBlock(t.id, truncate(msg), isError = true)
                            }
                        }
                    }
                    messages = messages :+ ("user", resultBlocks)
                    if (stopAfterBatch) continue = false
                }
            }

            if (cancelled()) {
                sink(LoopEvent.Error("Cancelled by user"))
            } else if (iter >= maxIterations) {
                sink(LoopEvent.Error(
                    "I've used " + maxIterations + " iterations on this turn and need to pause so I don't run away on cost. " +
                    "I haven't failed — I just hit the per-turn iteration cap. " +
                    "Send a follow-up (\"keep going\", \"continue\", or specific next-step instructions) and I'll pick up where I left off."))
                sink(LoopEvent.Done)
            } else {
                sink(LoopEvent.Done)
            }
        } catch {
            case e: DatrisException =>
                logger.warn("AgentLoop error: " + e.getMessage)
                sink(LoopEvent.Error(e.getMessage))
            case e: Exception =>
                logger.warn("AgentLoop unexpected error: " + e.getClass.getSimpleName + ": " + e.getMessage)
                sink(LoopEvent.Error(e.getClass.getSimpleName + ": " + e.getMessage))
        }
    }

    /** Adapt AIUtil's stream events to the agent-loop's event vocabulary. They
      * overlap mostly 1:1 — the loop adds tool_result, done, error. */
    private def streamSinkAdapter(out: LoopEvent => Unit): AIStreamEvent => Unit = {
        case AIStreamEvent.IterationStart                  => // already emitted at iteration top
        case AIStreamEvent.ThinkingDelta(t)                => out(LoopEvent.ThinkingDelta(t))
        case AIStreamEvent.TextDelta(t)                    => out(LoopEvent.TextDelta(t))
        case AIStreamEvent.ToolUseStart(id, name)          => out(LoopEvent.ToolUseStart(id, name))
        case AIStreamEvent.InputDelta(id, chars)           => out(LoopEvent.InputDelta(id, chars))
        case AIStreamEvent.ToolUseComplete(id, name, in)   => out(LoopEvent.ToolUseComplete(id, name, in))
        case AIStreamEvent.Error(msg)                      => out(LoopEvent.Error(msg))
    }

    /** Substitute a staged file's real base64 `content` (and `filename` if
      * absent) before dispatching a file tool to MCP.
      *
      * The model references a staged file by its short `attachmentId` handle.
      * It can land in the tool input two ways: as an explicit `attachmentId`
      * field, or — far more common, since the MCP schema only advertises
      * `content` — as the `content` value set to the handle string (raw, or
      * base64-wrapped). We recognize all three so a literal "put the id where
      * content goes" never reaches the schema endpoint as garbage bytes.
      *
      * Returns the input unchanged when there's nothing to resolve, so non-file
      * tools and genuine direct-content calls pass straight through. */
    private def resolveAttachment(
        toolName: String,
        input: JsonObject,
        attachments: Map[String, (String, Array[Byte])]
    ): JsonObject = {
        if (attachments.isEmpty || !AttachmentTools.contains(toolName)) return input

        val id: Option[String] = attachmentIdFrom(input, attachments)
        id.flatMap(i => attachments.get(i).map(i -> _)) match {
            case Some((_, (filename, bytes))) =>
                val out = input.deepCopy()
                out.remove("attachmentId")
                out.addProperty("content", java.util.Base64.getEncoder.encodeToString(bytes))
                val hasFilename = out.has("filename") && !out.get("filename").isJsonNull && out.get("filename").getAsString.nonEmpty
                if (!hasFilename) out.addProperty("filename", filename)
                out
            case None =>
                // The model named an explicit attachmentId we don't hold — warn.
                // (A `content` that simply isn't a handle is the normal
                //  direct-content path and is silently left alone.)
                if (input.has("attachmentId") && !input.get("attachmentId").isJsonNull)
                    logger.warn("AgentLoop: tool '" + toolName + "' referenced unknown attachmentId '" +
                        input.get("attachmentId").getAsString + "'")
                input
        }
    }

    /** Find a staged-attachment handle in a tool input. Checks the explicit
      * `attachmentId` field first, then a `content` value that is itself a
      * known handle (raw or base64-wrapped). Returns the matched key. */
    private def attachmentIdFrom(input: JsonObject, attachments: Map[String, (String, Array[Byte])]): Option[String] = {
        if (input.has("attachmentId") && !input.get("attachmentId").isJsonNull) {
            val a = input.get("attachmentId").getAsString.trim
            if (attachments.contains(a)) return Some(a)
        }
        if (input.has("content") && !input.get("content").isJsonNull) {
            val c = input.get("content").getAsString.trim
            if (attachments.contains(c)) return Some(c)
            // Tolerate a model that base64-wrapped the handle before placing it
            // in `content` (the schema says content is base64, so some will).
            try {
                val decoded = new String(java.util.Base64.getDecoder.decode(c), "UTF-8").trim
                if (attachments.contains(decoded)) return Some(decoded)
            } catch { case _: Exception => () }
        }
        None
    }

    /** Trim a tool result before feeding it back to the model. The UI shows the
      * full version via the SSE event; only the round-tripped copy is capped. */
    private def truncate(s: String): String =
        if (s == null) ""
        else if (s.length <= ToolResultMaxChars) s
        else s.substring(0, ToolResultMaxChars) + "\n\n…[truncated " + (s.length - ToolResultMaxChars) + " chars; full result was shown to the user]"

    /** Map an MCP tool definition to Anthropic's tool shape.
      * MCP uses `inputSchema`; Anthropic uses `input_schema`. Everything else
      * (name, description, JSON Schema) is identical. */
    private def mcpToAnthropicTool(mcp: JsonObject): JsonObject = {
        val out = new JsonObject()
        if (mcp.has("name"))        out.addProperty("name", mcp.get("name").getAsString)
        if (mcp.has("description")) out.addProperty("description", mcp.get("description").getAsString)
        val schema =
            if (mcp.has("inputSchema") && !mcp.get("inputSchema").isJsonNull) mcp.get("inputSchema")
            else if (mcp.has("input_schema") && !mcp.get("input_schema").isJsonNull) mcp.get("input_schema")
            else JsonParser.parseString("""{"type":"object","properties":{}}""")
        out.add("input_schema", schema)
        out
    }

    /** Backoff schedule for `overloaded_error` retries. Anthropic's load-shedding
      * typically clears within a few seconds, and the heaviest shapes (Opus +
      * extended thinking + many tools + streaming) sit in the most-shed lane. */
    private val OverloadBackoffMs: List[Long] = List(1000L, 3000L)

    /** Calls the Anthropic streaming API with retry-and-fallback around
      * `overloaded_error`. Retries the same model 3 times with exponential
      * backoff (1s, 3s, 8s). If still overloaded after that, attempts one
      * final call on a lighter sibling model (Opus → Sonnet) and surfaces
      * a `Notice` event so the UI can show "running on X — Y was overloaded".
      *
      * Stream events emitted by failed attempts are swallowed — the user
      * only sees the successful attempt's stream. Non-overloaded errors
      * propagate immediately (no retry). Cancellation is honored between
      * attempts and during backoff sleeps. */
    private def callWithRetryAndFallback(
        aiConfig: AIConfig,
        system: String,
        messages: List[(String, List[AIContentBlock])],
        tools: List[JsonObject],
        enableThinking: Boolean,
        maxTokens: Int,
        sink: AIStreamEvent => Unit,
        cancelled: () => Boolean,
        notice: String => Unit
    ): AIToolResponse = {
        var emitStreamErrors = false
        val gatedSink: AIStreamEvent => Unit = {
            case AIStreamEvent.Error(_) if !emitStreamErrors => ()
            case other                                       => sink(other)
        }

        var lastOverloadError: Throwable = null

        // Attempt 0 = original call; attempts 1..N = retries with backoff.
        var attempt = 0
        while (attempt <= OverloadBackoffMs.length) {
            if (cancelled()) throw new DatrisException("Cancelled by user")
            if (attempt > 0) {
                val sleepMs = OverloadBackoffMs(attempt - 1)
                logger.warn(
                    "AgentLoop: Anthropic overloaded; retrying in " + sleepMs +
                    "ms (retry " + attempt + " of " + OverloadBackoffMs.length + ")"
                )
                try Thread.sleep(sleepMs)
                catch { case _: InterruptedException => throw new DatrisException("Cancelled by user") }
            }
            try {
                return AIUtil.callAIWithToolsStreaming(
                    aiConfig = aiConfig,
                    system = system,
                    messages = messages,
                    tools = tools,
                    enableThinking = enableThinking && AIUtil.supportsExtendedThinking(aiConfig),
                    maxTokens = maxTokens,
                    sink = gatedSink,
                    cancelled = cancelled
                )
            } catch {
                case e: DatrisException if isOverloadedError(e) =>
                    lastOverloadError = e
                    attempt += 1
                // Non-overloaded errors propagate out of the loop naturally.
            }
        }

        // All in-model retries exhausted. One more attempt on a lighter model.
        sonnetFallbackFor(aiConfig) match {
            case Some(fallbackModel) =>
                val fallbackCfg = aiConfig.copy(model = fallbackModel)
                logger.warn(
                    "AgentLoop: Anthropic overloaded after " + OverloadBackoffMs.length +
                    " retries on " + aiConfig.model + "; falling back to " + fallbackModel + " for this request"
                )
                notice("Running on " + fallbackModel + " — " + aiConfig.model + " is currently overloaded.")
                emitStreamErrors = true
                AIUtil.callAIWithToolsStreaming(
                    aiConfig = fallbackCfg,
                    system = system,
                    messages = messages,
                    tools = tools,
                    enableThinking = enableThinking && AIUtil.supportsExtendedThinking(fallbackCfg),
                    maxTokens = maxTokens,
                    sink = gatedSink,
                    cancelled = cancelled
                )
            case None =>
                // No lighter model to fall back to. Surface the original error.
                val msg = if (lastOverloadError != null && lastOverloadError.getMessage != null)
                    lastOverloadError.getMessage else "Anthropic overloaded"
                sink(AIStreamEvent.Error(msg))
                throw (if (lastOverloadError != null) lastOverloadError else new DatrisException(msg))
        }
    }

    private def isOverloadedError(e: Throwable): Boolean = {
        val msg = if (e == null || e.getMessage == null) "" else e.getMessage
        msg.contains("overloaded_error")
    }

    /** Lighter sibling model to fall back to under sustained overload.
      * Defined for Anthropic's top-tier models (Opus, Fable, Mythos) today —
      * OpenAI and other providers return None and the caller surfaces the
      * original error.
      *
      * The Anthropic default (`claude-sonnet-4-6`) is only consulted on this
      * Anthropic top-tier path, so an OpenAI-only deployment never carries a
      * Claude string in its config surface. Operator-overridable via
      * `ANTHROPIC_OVERLOAD_FALLBACK_MODEL` so the fallback can be bumped
      * when a newer Sonnet ships without a recompile.
      *
      * Returns None when the override equals the current model (avoids a
      * no-op retry if someone misconfigures it to the same model). */
    private def sonnetFallbackFor(cfg: AIConfig): Option[String] = {
        val provider = if (cfg.provider == null) "" else cfg.provider.toLowerCase
        val model    = if (cfg.model == null) "" else cfg.model.toLowerCase
        val isTopTier = model.contains("opus") || model.contains("fable") || model.contains("mythos")
        if (provider == "anthropic" && isTopTier) {
            val fallback = sys.env.getOrElse("ANTHROPIC_OVERLOAD_FALLBACK_MODEL", "claude-sonnet-4-6").trim
            if (fallback.nonEmpty && fallback != cfg.model) Some(fallback) else None
        } else None
    }
}
