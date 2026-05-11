package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.util.{AIUtil, AgentLoop, APIKeyValidator, MCPClient}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import scala.collection.JavaConverters._

/** REST controller for the in-product Assistant tab.
  *
  * Two endpoints:
  *  - GET  /api/v1/assistant/init  — UI mount; warms MCP client caches and returns
  *                                   the tool catalog + workflow reference resource.
  *  - POST /api/v1/assistant/chat  — Server-Sent Events stream of agent-loop events.
  *
  * The chat endpoint runs the AgentLoop on a worker thread and pushes each
  * AgentLoop.LoopEvent to the SseEmitter. The client disconnects to cancel.
  */
@RestController
@RequestMapping(Array("/api/v1"))
class AssistantAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[AssistantAPIController])

    // Dedicated executor for chat sessions. Each session blocks one thread for the
    // duration of the agent loop (potentially 30-60s of LLM + tool calls), so we
    // size generously enough to handle bursty interactive use but not unbounded.
    private val chatExecutor = Executors.newFixedThreadPool(16, (r: Runnable) => {
        val t = new Thread(r, "assistant-chat-" + System.nanoTime())
        t.setDaemon(true)
        t
    })

    /** Active session cancellation flags, keyed by emitter identity hash. The
      * SseEmitter's onCompletion / onTimeout callbacks flip this when the client
      * disconnects, so the agent loop can bail at its next checkpoint. */
    private val cancelFlags: ConcurrentHashMap[Long, java.util.concurrent.atomic.AtomicBoolean] = new ConcurrentHashMap()

    // ------------------------------------------------------------------
    // GET /api/v1/assistant/init — warm caches, return tool catalog
    // ------------------------------------------------------------------

    @GetMapping(path = Array("/assistant/init"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def init(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)

            val tools = MCPClient.listTools(apiKey)
            val toolNames = tools.flatMap(t => Option(t.get("name")).map(_.getAsString)).toList

            val workflowReference: String =
                try MCPClient.readResource("datris://pipeline-config-reference", apiKey)
                catch {
                    case e: Exception =>
                        logger.warn("Assistant init: could not read pipeline-config-reference resource: " + e.getMessage)
                        ""
                }

            val payload = new JsonObject()
            payload.addProperty("toolCount", toolNames.size)
            val toolNamesArr = new JsonArray()
            toolNames.foreach(toolNamesArr.add)
            payload.add("toolNames", toolNamesArr)
            payload.addProperty("workflowReference", workflowReference)
            payload.addProperty("provider", Option(DatrisEnvironment.aiConfigForCodegen)
                .map(_.provider.toLowerCase).getOrElse("unknown"))
            payload.addProperty("model", Option(DatrisEnvironment.aiConfigForCodegen)
                .map(_.model).getOrElse(""))
            payload.addProperty("extendedThinking", DatrisEnvironment.current.extendedThinking)

            new ResponseEntity[String](new Gson().toJson(payload), HttpStatus.OK)
        } catch {
            case e: DatrisException =>
                new ResponseEntity[String]("{\"error\":\"" + escape(e.getMessage) + "\"}", HttpStatus.BAD_REQUEST)
            case e: Exception =>
                logger.warn("Assistant init failed: " + e.getMessage)
                new ResponseEntity[String]("{\"error\":\"" + escape(e.getMessage) + "\"}", HttpStatus.INTERNAL_SERVER_ERROR)
        }
    }

    // ------------------------------------------------------------------
    // POST /api/v1/assistant/chat — SSE stream of agent-loop events
    // ------------------------------------------------------------------

    @PostMapping(path = Array("/assistant/chat"), produces = Array(MediaType.TEXT_EVENT_STREAM_VALUE))
    def chat(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
             @RequestBody body: String): SseEmitter = {
        // 30-minute timeout — sessions are interactive and can be long with many
        // tool calls, but anything beyond this is almost certainly a stuck loop.
        val emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30))
        val emitterId = System.identityHashCode(emitter).toLong
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
        cancelFlags.put(emitterId, cancelled)

        emitter.onCompletion(() => { cancelled.set(true); cancelFlags.remove(emitterId); () })
        emitter.onTimeout   (() => { cancelled.set(true); cancelFlags.remove(emitterId); emitter.complete(); () })
        emitter.onError     (_  => { cancelled.set(true); cancelFlags.remove(emitterId); () })

        chatExecutor.submit(new Runnable {
            override def run(): Unit = {
                try {
                    APIKeyValidator.validate(apiKey)
                    runChat(apiKey, body, emitter, cancelled)
                } catch {
                    case e: Exception =>
                        try {
                            sendEvent(emitter, "error", makeEvent("error", "message", e.getMessage))
                        } catch { case _: Exception => () }
                        try emitter.complete() catch { case _: Exception => () }
                }
            }
        })

        emitter
    }

    private def runChat(apiKey: String,
                        body: String,
                        emitter: SseEmitter,
                        cancelled: java.util.concurrent.atomic.AtomicBoolean): Unit = {
        // Parse request body: { messages: [{role, content}], maxIterations?: Int }.
        val req = JsonParser.parseString(body).getAsJsonObject
        val messagesArr = req.getAsJsonArray("messages")
        if (messagesArr == null || messagesArr.size() == 0)
            throw new DatrisException("Request body must include a non-empty `messages` array")

        val userMessages: List[(String, String)] = messagesArr.asScala.toList.map { el =>
            val m = el.getAsJsonObject
            val role = if (m.has("role")) m.get("role").getAsString else "user"
            val content = if (m.has("content")) m.get("content").getAsString else ""
            (role, content)
        }

        val maxIterations: Int =
            if (req.has("maxIterations") && !req.get("maxIterations").isJsonNull) req.get("maxIterations").getAsInt
            else 25
        val maxTokensPerCall: Int = 16000

        val env = DatrisEnvironment.current
        val aiConfig = DatrisEnvironment.aiConfigForCodegen
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the codegen secret is configured.")

        // Fetch MCP tool catalog + workflow reference resource (both cached).
        // Append the synthetic `request_tap_secret_from_user` tool. The agent
        // sees it like any other tool, but AgentLoop intercepts the call
        // instead of dispatching to MCP — it emits a SecretRequest SSE event
        // that the UI renders as an inline credentials form.
        val toolDefs = MCPClient.listTools(apiKey) :+ syntheticSecretToolDef()
        val workflowReference =
            try MCPClient.readResource("datris://pipeline-config-reference", apiKey)
            catch { case _: Exception => "" }

        val systemPrompt = buildSystemPrompt(workflowReference, env.environment)

        logger.info("Assistant chat starting: tenant=" + env.environment + ", provider=" + aiConfig.provider +
            ", model=" + aiConfig.model + ", tools=" + toolDefs.size + ", maxIter=" + maxIterations +
            ", thinking=" + env.extendedThinking)

        AgentLoop.run(
            aiConfig = aiConfig,
            system = systemPrompt,
            userMessages = userMessages,
            toolDefs = toolDefs,
            apiKey = apiKey,
            enableThinking = env.extendedThinking,
            maxIterations = maxIterations,
            maxTokensPerCall = maxTokensPerCall,
            cancelled = () => cancelled.get(),
            sink = (evt: AgentLoop.LoopEvent) => emitLoopEvent(emitter, evt)
        )

        try emitter.complete() catch { case _: Exception => () }
    }

    private def emitLoopEvent(emitter: SseEmitter, evt: AgentLoop.LoopEvent): Unit = {
        evt match {
            case AgentLoop.LoopEvent.IterationStart =>
                sendEvent(emitter, "iteration_start", makeEvent("iteration_start"))
            case AgentLoop.LoopEvent.ThinkingDelta(t) =>
                sendEvent(emitter, "thinking_delta", makeEvent("thinking_delta", "text", t))
            case AgentLoop.LoopEvent.TextDelta(t) =>
                sendEvent(emitter, "text_delta", makeEvent("text_delta", "text", t))
            case AgentLoop.LoopEvent.ToolUseStart(id, name) =>
                val obj = new JsonObject()
                obj.addProperty("type", "tool_use_start")
                obj.addProperty("id", id)
                obj.addProperty("name", name)
                sendEvent(emitter, "tool_use_start", obj)
            case AgentLoop.LoopEvent.ToolUseComplete(id, name, input) =>
                val obj = new JsonObject()
                obj.addProperty("type", "tool_use")
                obj.addProperty("id", id)
                obj.addProperty("name", name)
                obj.add("input", input)
                sendEvent(emitter, "tool_use", obj)
            case AgentLoop.LoopEvent.ToolResult(id, name, result, isError) =>
                val obj = new JsonObject()
                obj.addProperty("type", "tool_result")
                obj.addProperty("id", id)
                obj.addProperty("name", name)
                obj.addProperty("result", result)
                obj.addProperty("isError", isError)
                sendEvent(emitter, "tool_result", obj)
            case AgentLoop.LoopEvent.SecretRequest(id, secretName, fieldNames, reason) =>
                val obj = new JsonObject()
                obj.addProperty("type", "secret_request")
                obj.addProperty("id", id)
                obj.addProperty("secretName", secretName)
                val fieldsArr = new JsonArray()
                fieldNames.foreach(fieldsArr.add)
                obj.add("fieldNames", fieldsArr)
                obj.addProperty("reason", reason)
                sendEvent(emitter, "secret_request", obj)
            case AgentLoop.LoopEvent.Done =>
                sendEvent(emitter, "done", makeEvent("done"))
            case AgentLoop.LoopEvent.Error(msg) =>
                sendEvent(emitter, "error", makeEvent("error", "message", msg))
        }
    }

    private def makeEvent(t: String, kvs: String*): JsonObject = {
        val obj = new JsonObject()
        obj.addProperty("type", t)
        kvs.grouped(2).foreach { pair =>
            if (pair.size == 2) obj.addProperty(pair(0), pair(1))
        }
        obj
    }

    private def sendEvent(emitter: SseEmitter, name: String, payload: JsonObject): Unit = {
        try {
            emitter.send(SseEmitter.event().name(name).data(payload.toString))
        } catch {
            case _: Exception => // client disconnected; cancellation flag will fire shortly
        }
    }

    private def buildSystemPrompt(workflowReference: String, tenantEnv: String): String = {
        val sb = new StringBuilder
        if (workflowReference != null && workflowReference.nonEmpty) {
            sb.append(workflowReference)
            sb.append("\n\n---\n\n")
        }
        sb.append("# In-Product Assistant\n\n")
        sb.append("You are the in-product Datris Assistant for tenant `").append(tenantEnv).append("`. ")
        sb.append("The user is interacting with you in a chat tab inside the Datris UI. They can see your tool calls and reasoning in real time as you work.\n\n")
        sb.append("## Behavior rules\n\n")
        sb.append("- When the user asks for data from an external source, find a suitable source (web_search if available, or your own knowledge), then create the tap and pipeline that delivers that data. Don't just describe what to do — do it.\n")
        sb.append("- Always check existing taps/pipelines first via `list_taps` and `list_pipelines` before creating new ones.\n")
        sb.append("- Tap secrets must be tagged `_type=tap`. When you call `create_tap_secret`, the platform sets that automatically — don't try to set `_type` yourself.\n")
        sb.append("- **Reuse existing tap secrets before creating new ones.** ALWAYS call `list_tap_secrets` before asking the user for credentials. If a candidate already exists, call `get_tap_secret_fields` to confirm it has the keys your tap script needs (field names only — values are never returned to you). When a matching secret exists, just pass its name as `secret_name` to `create_tap` and move on.\n")
        sb.append("- **When no existing secret fits, ask the user via the credentials form.** Call `request_tap_secret_from_user` with the proposed secret name, the list of required field NAMES (not values), and a one-sentence reason. The UI will render a credentials form inline — the user fills it in (or picks an existing secret from a dropdown), the values go straight to Vault, and the conversation resumes via the user's next message. Do NOT type credential prompts in plain text and ask the user to paste values into chat — credential values must never enter the chat content. Do NOT call `create_tap_secret` with values you don't have.\n")
        sb.append("- Placeholder values like `DATABASE_NAME`, `SCHEMA_NAME` in pipeline configs are substituted automatically by the platform. Don't worry about filling them in literally.\n")
        sb.append("\n")
        sb.append("## Destination defaults (apply unless the user explicitly asks for something else)\n\n")
        sb.append("- **Structured / semi-structured taps** (CSV, JSON, XML, API responses, table-shaped data) → **MongoDB**. The schema is flexible and tap output shape often varies across runs; MongoDB tolerates that gracefully. Do NOT default to PostgreSQL — only use it when the user explicitly requests SQL.\n")
        sb.append("- **Document taps** (PDF, DOCX, HTML, plain text, anything destined for retrieval/RAG) → a **vector store** (pgvector, qdrant, weaviate, milvus, or chroma). Pick whichever the tenant already has configured; if multiple are available, pick pgvector by default.\n")
        sb.append("- When you propose a destination, state the destination type and the proposed name explicitly so the user can correct you before you build it.\n")
        sb.append("\n")
        sb.append("## Stay generic — don't assume scope or domain\n\n")
        sb.append("- When the user asks for data without specifying scope (which records, what date range, what filters, what frequency, what region, **which source / provider**), **ASK** rather than guess. Do not propose specific subsets, lists, shortlists, or default providers drawn from your training data — your guess biases the user toward a particular view of the domain that may not match their needs, and the user will accept the suggestion just because it's there.\n")
        sb.append("- **Source selection is a scope question.** Treat the choice of API / library / data source the same way you treat the choice of scope: present the options briefly, then wait. Do NOT pick one for the user. Do NOT bake the source name into the tap name, pipeline name, or any other artifact until the user has chosen it explicitly.\n")
        sb.append("- **Partial answers are not full answers.** If you asked N clarifying questions and the user answered K of them, the remaining N-K are still pending. Repeat the unanswered ones in plain language and wait — do not infer them from context, the user's tone, or the most popular choice in your training data. Only proceed once every open question is closed or the user has explicitly told you to pick a default.\n")
        sb.append("- If the user says \"use a sensible default\" or \"you pick,\" choose ONE obviously-placeholder value so they can verify the shape works, and tell them plainly that it's a placeholder. Do not pad the placeholder with a recognizable canonical list.\n")
        sb.append("- Phrase questions about scope in neutral, domain-appropriate terms. Avoid loaded shortcuts that signal a specific industry or framing.\n")
        sb.append("- The same rule applies to schedules, batch sizes, retention windows, refresh cadences, and other tunables — ask, don't assume.\n")
        sb.append("\n")
        sb.append("## Safety + finish\n\n")
        sb.append("- **Destructive operations gate**: NEVER call `delete_tap`, `delete_pipeline`, `delete_tap_secret`, or `update_secret` on an existing secret without explicit user confirmation in the chat. If the user asks to delete or overwrite something, restate what will be removed and ask the user to confirm before proceeding.\n")
        sb.append("- When you finish, say so plainly in one or two sentences. The UI will surface clickable links to any tap or pipeline you created.\n")
        sb.toString
    }

    private def escape(s: String): String =
        if (s == null) ""
        else s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    /** Synthetic MCP-shaped tool definition for `request_tap_secret_from_user`.
      * This is NOT registered on the MCP server — it's added to the catalog
      * only on the Assistant codepath. AgentLoop intercepts calls to it
      * locally instead of dispatching them. */
    private def syntheticSecretToolDef(): JsonObject = {
        val tool = new JsonObject()
        tool.addProperty("name", AgentLoop.SyntheticSecretTool)
        tool.addProperty("description",
            "Ask the user to provide credentials for a tap, via a credentials form rendered in the chat UI. " +
            "Use this when you need credentials and `list_tap_secrets` did not surface a usable existing secret — " +
            "NEVER call `create_tap_secret` with values you don't have. " +
            "Pass the proposed secret `name`, the list of required `fields` (field names only — names like API_KEY, USER_AGENT — NOT values), " +
            "and a brief `reason` shown to the user explaining why the credentials are needed. " +
            "The platform shows the user a form (with an option to pick an existing tap secret instead), collects the values, " +
            "stores them in Vault, and returns control to the conversation via the user's next chat message. " +
            "You will never see the values themselves.")
        val schema = new JsonObject()
        schema.addProperty("type", "object")
        val props = new JsonObject()
        val nameProp = new JsonObject()
        nameProp.addProperty("type", "string")
        nameProp.addProperty("description", "Proposed name for the new tap secret. Lowercase-hyphenated convention, e.g. `noaa-nws`.")
        props.add("name", nameProp)
        val fieldsProp = new JsonObject()
        fieldsProp.addProperty("type", "array")
        val itemsObj = new JsonObject()
        itemsObj.addProperty("type", "string")
        fieldsProp.add("items", itemsObj)
        fieldsProp.addProperty("description",
            "Required field NAMES only — the keys that will be injected as env vars in the tap script. " +
            "Example: [\"API_KEY\", \"USER_AGENT\"]. Do not pass any values.")
        props.add("fields", fieldsProp)
        val reasonProp = new JsonObject()
        reasonProp.addProperty("type", "string")
        reasonProp.addProperty("description", "One-sentence explanation of what the credentials are for, shown to the user above the form.")
        props.add("reason", reasonProp)
        schema.add("properties", props)
        val req = new JsonArray()
        req.add("name"); req.add("fields"); req.add("reason")
        schema.add("required", req)
        tool.add("input_schema", schema)
        tool
    }
}
