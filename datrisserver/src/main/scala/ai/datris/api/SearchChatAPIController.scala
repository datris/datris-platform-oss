package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{JsonObject, JsonParser}
import ai.datris.model.{DatrisEnvironment, DatrisException, TenantContext, UserContext}
import ai.datris.util.{AgentLoop, APIKeyValidator, MCPClient, SecretsUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation._
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import scala.collection.JavaConverters._

/** REST controller for the conversational Search chat — the "Chat" sub-panel
  * inside the Search tab.
  *
  * Sibling of `AssistantAPIController` and `OpsChatAPIController`, not a mode
  * flag on either: keeping a third endpoint separate means a user flipping
  * between the build-mode Assistant, the Ops chat, and search-chat in one
  * session can't cross-contaminate the server-side prompt/tool context. All
  * three share the SSE wire format via `AssistantSseSupport` so the UI parses
  * them with one parser.
  *
  * Differences vs. the other two controllers:
  *  - Discovery/answer system prompt: find the data (cataloged AND uncataloged)
  *    that answers the user's question, query/search it, and answer with
  *    citations. It does NOT build or operate pipelines.
  *  - READ-ONLY tool catalog. We filter `MCPClient.listTools` down to an
  *    allow-list of discovery/query/search tools and drop every mutating tool.
  *    The read-only guarantee is enforced here, server-side, not just by the
  *    prompt — the agent never even sees create/delete/run/update tools.
  *  - Accepts an optional `context` object carrying a catalog scope. When the
  *    user has scoped the Search tab to a named catalog (or "Uncataloged"),
  *    that becomes a leading user message so the agent prefers matching
  *    pipelines/taps.
  */
@RestController
@RequestMapping(Array("/api/v1"))
class SearchChatAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SearchChatAPIController])

    // Dedicated executor, sized like the assistant/ops chats — search chats
    // are interactive and can spend tens of seconds across a discovery +
    // query tool sequence.
    private val chatExecutor = Executors.newFixedThreadPool(16, (r: Runnable) => {
        val t = new Thread(r, "search-chat-" + System.nanoTime())
        t.setDaemon(true)
        t
    })

    private val cancelFlags: ConcurrentHashMap[Long, java.util.concurrent.atomic.AtomicBoolean] = new ConcurrentHashMap()

    /** Same resolveUiApiKey contract as the other chat controllers — see
      * AssistantAPIController.resolveUiApiKey. The UI identity used for
      * outbound MCP calls is the same across all chat modes. */
    private def resolveUiApiKey(userApiKey: String): String = {
        if (!DatrisEnvironment.values.useApiKeys) return null

        val secretPath = DatrisEnvironment.current.environment + "/ui-api-key"
        SecretsUtil.getSecretMap(secretPath).flatMap(m => Option(m.get("apiKey"))) match {
            case Some(v) if v != null && v.nonEmpty => v
            case _                                  => userApiKey
        }
    }

    @PostMapping(path = Array("/search-chat/chat"), produces = Array(MediaType.TEXT_EVENT_STREAM_VALUE))
    def chat(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
             @RequestBody body: String): SseEmitter = {
        val emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30))
        val emitterId = System.identityHashCode(emitter).toLong
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
        cancelFlags.put(emitterId, cancelled)

        emitter.onCompletion(() => { cancelled.set(true); cancelFlags.remove(emitterId); () })
        emitter.onTimeout   (() => { cancelled.set(true); cancelFlags.remove(emitterId); emitter.complete(); () })
        emitter.onError     (_  => { cancelled.set(true); cancelFlags.remove(emitterId); () })

        // Same ThreadLocal capture rationale as the other chat controllers —
        // session-authed requests need UserContext/TenantContext re-set on the
        // worker thread so APIKeyValidator.validate doesn't reject them.
        val capturedUser = UserContext.get()
        val capturedTenant = TenantContext.get()

        chatExecutor.submit(new Runnable {
            override def run(): Unit = {
                capturedUser.foreach(UserContext.set)
                capturedTenant.foreach(TenantContext.set)
                try {
                    APIKeyValidator.validate(apiKey)
                    runChat(apiKey, body, emitter, cancelled)
                } catch {
                    case e: Exception =>
                        try {
                            AssistantSseSupport.sendEvent(emitter, "error",
                                AssistantSseSupport.makeEvent("error", "message", e.getMessage))
                        } catch { case _: Exception => () }
                        try emitter.complete() catch { case _: Exception => () }
                } finally {
                    UserContext.clear()
                    TenantContext.clear()
                }
            }
        })

        emitter
    }

    private def runChat(apiKey: String,
                        body: String,
                        emitter: SseEmitter,
                        cancelled: java.util.concurrent.atomic.AtomicBoolean): Unit = {
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

        // Optional { catalog: "<name>" | "Uncataloged" | "All" } scope.
        val catalogScope: Option[String] =
            if (req.has("context") && !req.get("context").isJsonNull) {
                val ctx = req.getAsJsonObject("context")
                if (ctx.has("catalog") && !ctx.get("catalog").isJsonNull) {
                    val c = ctx.get("catalog").getAsString.trim
                    if (c.isEmpty || c.equalsIgnoreCase("All")) None else Some(c)
                } else None
            } else None

        val maxIterations: Int =
            if (req.has("maxIterations") && !req.get("maxIterations").isJsonNull) req.get("maxIterations").getAsInt
            else 50
        val maxTokensPerCall: Int = 16000

        val env = DatrisEnvironment.current
        val aiConfig = DatrisEnvironment.aiConfigForCodegen
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the codegen secret is configured.")

        val uiKey = resolveUiApiKey(apiKey)

        // READ-ONLY: filter the MCP catalog to discovery/query/search tools.
        // Enforced here so the agent physically cannot create, delete, run, or
        // mutate anything — the read-only contract is a server guarantee, not a
        // prompt request the model could be talked out of.
        val toolDefs = filterToolsReadOnly(MCPClient.listTools(uiKey))

        val systemPrompt = buildSearchSystemPrompt(env.environment)

        // Prepend the catalog scope (if any) as a leading user message, mirroring
        // the dashboard-snapshot injection in OpsChatAPIController.
        val withContext: List[(String, String)] = catalogScope match {
            case Some(cat) => ("user", renderCatalogScopeMessage(cat)) :: userMessages
            case None      => userMessages
        }

        logger.info("Search chat starting: tenant=" + env.environment + ", provider=" + aiConfig.provider +
            ", model=" + aiConfig.model + ", tools=" + toolDefs.size + ", maxIter=" + maxIterations +
            ", catalogScope=" + catalogScope.getOrElse("(all)"))

        AgentLoop.run(
            aiConfig = aiConfig,
            system = systemPrompt,
            userMessages = withContext,
            toolDefs = toolDefs,
            apiKey = uiKey,
            enableThinking = env.extendedThinking,
            maxIterations = maxIterations,
            maxTokensPerCall = maxTokensPerCall,
            cancelled = () => cancelled.get(),
            sink = (evt: AgentLoop.LoopEvent) => {
                // Once the client is gone, stop emitting and flip the cancel
                // flag so the agent loop unwinds at its next checkpoint —
                // avoids a broken-pipe write per remaining token delta.
                if (!cancelled.get() && !AssistantSseSupport.emitLoopEvent(emitter, evt)) cancelled.set(true)
            }
        )

        // If the client already disconnected (a failed write flipped the
        // cancel flag), skip complete() — flushing to a dead socket would log
        // another spurious broken pipe. The container finalizes the response.
        if (!cancelled.get()) { try emitter.complete() catch { case _: Exception => () } }
    }

    /** Read-only tools the search agent is allowed to call. Anything not on
      * this list (or matching a read-only prefix) is dropped from the catalog
      * the model sees. Every entry is a pure read: discovery, metadata, query,
      * semantic search, and answer synthesis. */
    private val readOnlyToolNames: Set[String] = Set(
        // Discovery
        "list_pipelines",
        "list_taps",
        "get_pipeline",
        "get_tap",
        // Metadata
        "list_postgres_databases",
        "list_postgres_schemas",
        "list_postgres_tables",
        "list_postgres_columns",
        "list_mongodb_databases",
        "list_mongodb_collections",
        "list_qdrant_collections",
        "list_weaviate_classes",
        "list_milvus_collections",
        "list_chroma_collections",
        // Query / search / answer
        "query_postgres",
        "query_mongodb",
        "query_objectstore",
        "query_natural",
        "ai_answer"
    )

    /** Keep only read-only tools. The explicit allow-list above covers the
      * named query/discovery tools; the `list_`/`search_` prefixes future-proof
      * the filter so a newly-added read-only metadata or vector-search tool is
      * allowed without a code change, while every mutating verb
      * (create_/delete_/update_/run_/kill_/upload_/set_/test_) is excluded by
      * simply not matching. */
    private def filterToolsReadOnly(tools: List[JsonObject]): List[JsonObject] = {
        val kept = tools.filter { t =>
            val n = Option(t.get("name")).map(_.getAsString).getOrElse("")
            // Secret discovery is out of scope for a data-search assistant and
            // mildly leaky (it enumerates secret names), so deny anything
            // secret-related even though it's technically read-only.
            if (n.contains("secret")) false
            else readOnlyToolNames.contains(n) || n.startsWith("list_") || n.startsWith("search_")
        }
        val dropped = tools.size - kept.size
        if (dropped > 0) logger.debug("Search chat dropped " + dropped + " non-read-only tools from catalog")
        kept
    }

    /** Render the catalog scope as a compact leading user message. Sent as
      * `user` (not `system`) so the agent treats it as "what the user is
      * currently scoped to" — a preference, not a hard filter, since uncataloged
      * data the user cares about may live just outside the chosen catalog. */
    private def renderCatalogScopeMessage(catalog: String): String = {
        val sb = new StringBuilder
        sb.append("(Search scope — the user has the Search tab scoped to ")
        if (catalog.equalsIgnoreCase("Uncataloged"))
            sb.append("**Uncataloged** data — pipelines and taps that have not been assigned to any catalog.)\n\n")
        else
            sb.append("the **").append(catalog).append("** catalog.)\n\n")
        sb.append("Prefer pipelines and taps that match this scope when discovering where to look. ")
        sb.append("If nothing in scope can answer the question, say so and only then broaden to other data, ")
        sb.append("noting that you looked outside the selected scope.")
        sb.toString
    }

    private def buildSearchSystemPrompt(tenantEnv: String): String = {
        val sb = new StringBuilder
        sb.append("# Datris Search Assistant\n\n")
        sb.append("You are the conversational Search assistant for tenant `").append(tenantEnv).append("`. ")
        sb.append("A user is asking a question and wants you to find the answer in the data this platform already holds. ")
        sb.append("Your job is discovery and retrieval: figure out where the relevant data lives, query or search it, and answer in plain language.\n\n")

        sb.append("## You are READ-ONLY\n\n")
        sb.append("You can only discover, inspect, query, and search. You have no tools to create, modify, delete, run, or ingest anything — and you must not claim you can. ")
        sb.append("If the user asks to build a pipeline, ingest new data, run a tap, or change configuration, tell them that's the Assistant tab's job and stay on the search task.\n\n")

        sb.append("## How to work\n\n")
        sb.append("1. **Learn what exists first.** Before answering any data question, call `list_pipelines` and `list_taps` so you know what data sources are available. Treat cataloged and uncataloged sources equally — data with no catalog assigned is just as real and queryable; never skip it.\n")
        sb.append("2. **Inspect structure before querying.** Use the metadata tools (`list_postgres_schemas`/`list_postgres_tables`/`list_postgres_columns`, `list_mongodb_collections`, and the vector `list_*` tools) to confirm the shape of a source before you query it.\n")
        sb.append("3. **Pick the right access path for the question:**\n")
        sb.append("   - Structured/analytical questions over a relational table → `query_natural` (you give it the question + table) or `query_postgres` (you write the read-only SQL).\n")
        sb.append("   - Document/collection lookups → `query_mongodb`.\n")
        sb.append("   - File-based sources behind a pipeline → `query_objectstore`.\n")
        sb.append("   - Semantic / meaning-based retrieval ('find things about X') → the `search_*` tool matching the available vector store.\n")
        sb.append("   - To turn retrieved rows or chunks into a written answer → `ai_answer`.\n")
        sb.append("4. **Answer with citations.** State which pipeline, table, or collection each part of your answer came from, so the user can trust and reproduce it. If you ran SQL, show it briefly.\n\n")

        sb.append("## Behavior rules\n\n")
        sb.append("- **Don't guess at data you didn't read.** If a query returns nothing or the relevant source doesn't exist, say so plainly rather than inventing an answer.\n")
        sb.append("- **Prefer the scoped catalog when one is provided** (see the leading scope message, if any), but say when you had to look outside it.\n")
        sb.append("- **Be concise.** This is a chat panel. Lead with the answer, then a short note on where it came from. Avoid dumping raw result sets unless the user asks.\n")
        sb.append("- **Ask one clarifying question only when genuinely ambiguous.** If the question maps cleanly onto an available source, just answer it.\n\n")

        sb.append("## When you can't answer\n\n")
        sb.append("If, after discovery, no available data source can answer the question, say so directly and name what you checked. Suggest what data would need to be ingested (pointing the user to the Assistant tab to build it) rather than fabricating a result.")
        sb.toString
    }
}
