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

/** REST controller for the Catalog curation chat — the right-rail assistant
  * inside the Catalog page.
  *
  * Sibling of `AssistantAPIController`, `OpsChatAPIController`, and
  * `SearchChatAPIController`, not a mode flag on any of them: a user flipping
  * between build-mode, ops, search, and catalog curation in one session can't
  * cross-contaminate the server-side prompt/tool context. All four share the
  * SSE wire format via `AssistantSseSupport` so the UI parses them with one
  * parser.
  *
  * Differences vs. the other controllers:
  *  - Curation system prompt: organize the inventory — group, move, rename,
  *    and describe taps and pipelines. It defers discovery to the Search chat
  *    and operations (run/kill/recover) to the Ops chat.
  *  - FULL tool catalog with prompt steering (like Ops/Search-traditional, NOT
  *    the read-only filter Search-chat uses). `set_catalog` and the catalog
  *    read tools sort to the front so the agent reaches for them first; we
  *    deliberately do NOT filter, so a legitimate adjacent ask still works.
  *  - Accepts an optional `context` object — a catalog inventory snapshot —
  *    that becomes a leading user message so the agent grounds answers in the
  *    catalogs and items the user is looking at right now.
  */
@RestController
@RequestMapping(Array("/api/v1"))
class CatalogChatAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[CatalogChatAPIController])

    // Dedicated executor sized like the other chats — interactive, and a
    // curation pass can spend tens of seconds across a list + move sequence.
    private val chatExecutor = Executors.newFixedThreadPool(
        16,
        (r: Runnable) => {
            val t = new Thread(r, "catalog-chat-" + System.nanoTime())
            t.setDaemon(true)
            t
        }
    )

    private val cancelFlags: ConcurrentHashMap[Long, java.util.concurrent.atomic.AtomicBoolean] = new ConcurrentHashMap()

    /** Same resolveUiApiKey contract as the other chat controllers — see
      * AssistantAPIController.resolveUiApiKey. The UI identity used for
      * outbound MCP calls is the same across all chat modes. */
    private def resolveUiApiKey(userApiKey: String): String = {
        if (!DatrisEnvironment.values.useApiKeys) return null

        val secretPath = DatrisEnvironment.current.environment + "/ui-api-key"
        SecretsUtil.getSecretMap(secretPath).flatMap(m => Option(m.get("apiKey"))) match {
            case Some(v) if v != null && v.nonEmpty => v
            case _ => userApiKey
        }
    }

    @PostMapping(path = Array("/catalog-chat/chat"), produces = Array(MediaType.TEXT_EVENT_STREAM_VALUE))
    def chat(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestBody body: String): SseEmitter = {
        val emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30))
        val emitterId = System.identityHashCode(emitter).toLong
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
        cancelFlags.put(emitterId, cancelled)

        emitter.onCompletion(() => { cancelled.set(true); cancelFlags.remove(emitterId); () })
        emitter.onTimeout(() => { cancelled.set(true); cancelFlags.remove(emitterId); emitter.complete(); () })
        emitter.onError(_ => { cancelled.set(true); cancelFlags.remove(emitterId); () })

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
                            AssistantSseSupport.sendEvent(emitter, "error", AssistantSseSupport.makeEvent("error", "message", e.getMessage))
                        } catch { case _: Exception => () }
                        try emitter.complete()
                        catch { case _: Exception => () }
                } finally {
                    UserContext.clear()
                    TenantContext.clear()
                }
            }
        })

        emitter
    }

    private def runChat(apiKey: String, body: String, emitter: SseEmitter, cancelled: java.util.concurrent.atomic.AtomicBoolean): Unit = {
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

        val contextSnapshot: Option[JsonObject] =
            if (req.has("context") && !req.get("context").isJsonNull)
                Some(req.getAsJsonObject("context"))
            else None

        val maxIterations: Int =
            if (req.has("maxIterations") && !req.get("maxIterations").isJsonNull) req.get("maxIterations").getAsInt
            else 50
        val maxTokensPerCall: Int = 32000

        val env = DatrisEnvironment.current
        val aiConfig = DatrisEnvironment.aiConfigForChat
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the AI primary secret is configured.")

        val uiKey = resolveUiApiKey(apiKey)

        // Curation tools surface first so the agent reaches for set_catalog and
        // the catalog read tools before less-relevant build/ops tools. We do
        // NOT filter the catalog — the system prompt steers behavior and we'd
        // rather the agent fall back to an adjacent tool than refuse a
        // legitimate ask. (Matches the Ops controller's reasoning.)
        val toolDefs = reorderToolsCatalogFirst(MCPClient.listTools(uiKey))

        val systemPrompt = buildCatalogSystemPrompt(env.environment)

        // Re-inject the catalog inventory snapshot on every turn as a leading
        // user message. Cheapest possible cadence — the inventory is small.
        val withContext: List[(String, String)] = contextSnapshot match {
            case Some(ctx) => ("user", renderContextMessage(ctx)) :: userMessages
            case None => userMessages
        }

        logger.info("Catalog chat starting: tenant=" + env.environment + ", provider=" + aiConfig.provider +
            ", model=" + aiConfig.model + ", tools=" + toolDefs.size + ", maxIter=" + maxIterations +
            ", hasContext=" + contextSnapshot.isDefined)

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
                // Stop emitting once the client disconnects and flip the cancel
                // flag so the agent loop unwinds — avoids a broken-pipe write
                // per remaining token delta.
                if (!cancelled.get() && !AssistantSseSupport.emitLoopEvent(emitter, evt)) cancelled.set(true)
            }
        )

        // If the client already disconnected (a failed write flipped the
        // cancel flag), skip complete() — flushing to a dead socket would log
        // another spurious broken pipe. The container finalizes the response.
        if (!cancelled.get()) {
            try emitter.complete()
            catch { case _: Exception => () }
        }
    }

    /** Curation tools first, then the rest. This isn't filtering — the agent
      * can still call other tools when warranted. It just biases the listing so
      * the first tool the model considers when organizing is the right one. */
    private def reorderToolsCatalogFirst(tools: List[JsonObject]): List[JsonObject] = {
        val catalogToolNames = Set(
            "set_catalog",
            "list_taps",
            "list_pipelines",
            "get_tap",
            "get_pipeline",
            "get_tap_logs",
            "update_tap"
        )
        val (curation, rest) = tools.partition { t =>
            val n = Option(t.get("name")).map(_.getAsString).getOrElse("")
            catalogToolNames.contains(n)
        }
        curation ++ rest
    }

    /** Render the catalog inventory snapshot as a compact, human-readable
      * leading user message. Sent as `user` (not `system`) so the agent treats
      * it as "what the user is currently looking at" — grounding context for
      * the request that follows. */
    private def renderContextMessage(ctx: JsonObject): String = {
        val sb = new StringBuilder
        sb.append("(Current Catalog page snapshot — what I'm looking at right now)\n\n")

        val catalogs = Option(ctx.getAsJsonArray("catalogs")).map(_.asScala.toList).getOrElse(Nil)
        if (catalogs.isEmpty) {
            sb.append("No catalogs yet.\n\n")
        } else {
            sb.append("Catalogs (").append(catalogs.size).append("):\n")
            catalogs.foreach { el =>
                val c = el.getAsJsonObject
                val name = strOpt(c, "name").getOrElse("?")
                val tapCount = intOpt(c, "tapCount").getOrElse(0)
                val pipelineCount = intOpt(c, "pipelineCount").getOrElse(0)
                sb.append("  - `").append(name).append("` (")
                    .append(tapCount).append(" tap").append(if (tapCount != 1) "s" else "")
                    .append(", ").append(pipelineCount).append(" pipeline").append(if (pipelineCount != 1) "s" else "")
                    .append(")\n")
                val taps = Option(c.getAsJsonArray("taps")).map(_.asScala.toList.map(_.getAsString)).getOrElse(Nil)
                if (taps.nonEmpty) sb.append("      taps: ").append(taps.mkString(", ")).append("\n")
                val pipelines = Option(c.getAsJsonArray("pipelines")).map(_.asScala.toList.map(_.getAsString)).getOrElse(Nil)
                if (pipelines.nonEmpty) sb.append("      pipelines: ").append(pipelines.mkString(", ")).append("\n")
            }
            sb.append("\n")
        }

        if (ctx.has("focus") && !ctx.get("focus").isJsonNull) {
            val focus = ctx.getAsJsonObject("focus")
            strOpt(focus, "name").foreach { fname =>
                sb.append("The user opened this chat focused on the `").append(fname).append("` catalog. ")
                    .append("Treat that catalog as the subject of their request unless they say otherwise.\n\n")
            }
        }

        sb.append("Use this snapshot to ground your answers. Refer to catalogs, taps, and pipelines by name. ")
            .append("To reassign an item's catalog, call `set_catalog` with exactly one of `tap` or `pipeline` plus the target `catalog` ")
            .append("(omit `catalog` to move it to Uncataloged) — but only after the user has explicitly approved that move.")
        sb.toString
    }

    private def strOpt(o: JsonObject, key: String): Option[String] =
        if (o.has(key) && !o.get(key).isJsonNull) Some(o.get(key).getAsString) else None

    private def intOpt(o: JsonObject, key: String): Option[Int] =
        if (o.has(key) && !o.get(key).isJsonNull) Some(o.get(key).getAsInt) else None

    private def buildCatalogSystemPrompt(tenantEnv: String): String = {
        val sb = new StringBuilder
        sb.append("# Datris Catalog Assistant\n\n")
        sb.append("You are the Catalog curation assistant for tenant `").append(tenantEnv).append("`. ")
        sb.append(
            "The user is looking at the Catalog page inside the Datris UI — their taps and pipelines grouped into named catalogs, plus an `Uncataloged` group for anything unassigned. "
        )
        sb.append(
            "When a catalog snapshot is provided as the leading user message in this conversation, treat it as ground truth for what the user is looking at *right now*. "
        )
        sb.append("When no snapshot is provided, call `list_taps` and `list_pipelines` to discover what exists.\n\n")

        sb.append("## Mission\n\n")
        sb.append("Your job is to help the user ORGANIZE what already exists — not to build, run, or search data:\n")
        sb.append("- **Describe & summarize.** Explain what's in a catalog, what's sitting in Uncataloged, and how the inventory is structured.\n")
        sb.append("- **Propose groupings.** Suggest sensible catalogs (by source, domain, owner, lifecycle) and which taps/pipelines belong in each.\n")
        sb.append(
            "- **Move & rename on request.** Reassign taps and pipelines between catalogs with `set_catalog`. Suggest renames where names are unclear or inconsistent.\n"
        )
        sb.append("- **Tidy up.** Flag redundant, near-empty, or inconsistently named catalogs and propose how to consolidate.\n\n")

        sb.append("## Stay in your lane\n\n")
        sb.append(
            "- **Discovery/answering questions about the *data itself* is the Search tab's job.** If the user wants to query rows, search documents, or get an answer from their data, point them to Search rather than doing it here.\n"
        )
        sb.append(
            "- **Running, killing, retrying, and recovering pipelines is the Ops tab's job.** If the user wants to run a tap, kill a job, or diagnose a failure, point them to the Ops chat.\n"
        )
        sb.append("- **Building new taps/pipelines from scratch is the Assistant tab's job.** You organize existing items; you don't create new data flows.\n")
        sb.append(
            "You technically have other tools available — use them only if an organizing task genuinely needs a quick read (e.g. `get_tap` to see a tap's source before suggesting a grouping). Don't drift into the other modes' work.\n\n"
        )

        sb.append("## Behavior rules\n\n")
        sb.append(
            "- **Propose before you move.** Catalogs are a user-chosen convention. Lay out your suggested grouping in plain language FIRST and let the user approve it. Do NOT call `set_catalog` proactively or speculatively — assigning a taxonomy the user didn't ask for is worse than doing nothing.\n"
        )
        sb.append(
            "- **Mutate only on an explicit, item-specific go-ahead.** `set_catalog` changes the user's organization. Call it only when the user's most recent message clearly authorizes that specific move — \"yes, do it\", \"move X into Y\", \"go ahead with that plan\". Vague replies (\"sounds good\", \"and?\", \"what else\") are NOT authorization to start moving things; ask which moves to apply.\n"
        )
        sb.append(
            "- **`set_catalog` takes exactly one of `tap` or `pipeline`.** Pass the item name and the target `catalog`. Omit `catalog` (or pass an empty string) to clear it — that moves the item to Uncataloged. One call per item; report what you moved.\n"
        )
        sb.append(
            "- **Watch for name clashes.** A catalog the user browses shouldn't contain two items with the same name. If a proposed move would collide with an existing item in the target catalog, call it out and suggest a rename instead of moving blindly.\n"
        )
        sb.append(
            "- **Renaming a catalog = moving every item into the new name.** There's no first-class rename; to rename catalog A to B, `set_catalog` each of A's items to B. Confirm the full list with the user before doing a batch like this, and report progress.\n"
        )
        sb.append(
            "- **Be brief.** This is a side-panel chat with limited width. Short paragraphs. When proposing a grouping, a compact bulleted plan beats prose.\n\n"
        )

        sb.append("## Don't stall mid-task\n\n")
        sb.append(
            "- **If your reply ends by announcing work you have NOT done yet — \"Let me check X\", \"Now I'll Y\", \"Let me look at Z\" — make those tool calls in the SAME turn instead of ending.** Announcing the next step and then stopping forces the user to type \"continue\" to get work they already asked for. The sentence that narrates an action and the tool call that performs it belong in the same turn.\n"
        )
        sb.append(
            "- **End your turn only when** the task is complete, OR you need a decision/approval/confirmation only the user can give, OR you are waiting on input the user must provide. In every other case — including right after you've described your next step — keep going and do it.\n"
        )
        sb.append(
            "- This narrows nothing in the rules above: keep asking, proposing, confirming, and waiting exactly where they tell you to — scope/source choices, a plan to approve, destructive-action confirmation, acting only when explicitly authorized. The point is only this: once the next step is already decided or authorized and you are merely narrating it, perform it instead of ending the turn.\n\n"
        )

        sb.append("## Finish\n\n")
        sb.append(
            "When moves are done, say what changed in one or two sentences — the user can see most of it in the tree, which refreshes automatically. You're the audit trail for what *just happened in this chat*."
        )
        sb.toString
    }
}
