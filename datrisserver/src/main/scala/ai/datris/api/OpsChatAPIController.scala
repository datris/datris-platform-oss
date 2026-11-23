package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonObject, JsonParser}
import ai.datris.auth.{Allow, ChatCaller, ChatIdentity, Refuse}
import ai.datris.model.{DatrisEnvironment, DatrisException, TenantContext}
import ai.datris.incident.{Incident, IncidentIO}
import ai.datris.util.{AgentLoop, APIKeyValidator, MCPClient, OpsAgentPrompt}
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation._
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import scala.collection.JavaConverters._

/** REST controller for the Ops chat side panel (the right-rail assistant
  * inside the Ops shell).
  *
  * Sibling of `AssistantAPIController`, NOT a mode flag on it: keeping the
  * two endpoints separate means a user flipping between the build-mode
  * Assistant tab and the Ops chat in one session can't confuse the
  * server-side prompt context. They share the SSE wire format via
  * `AssistantSseSupport` so the UI parses both with one parser.
  *
  * Differences vs. the build-mode controller:
  *  - Operational system prompt (explain failures, act on rows by name, do
  *    not start new taps from a blank slate unless explicitly asked).
  *  - Accepts an optional `context` object — a dashboard snapshot — that
  *    becomes a leading user message so the agent grounds answers in the
  *    failures and volumes the operator is staring at right now.
  *  - Operational tools (`run_tap`, `get_pipeline_status`, `kill_job`, etc.)
  *    sort to the front of the catalog so the agent reaches for them first.
  *  - With the recovery agent on, open incidents are appended to the context
  *    message only for a caller allowed to read incidents
  *    (`ChatCaller.mayReadIncidents`: a user whose role bundle holds
  *    `incident:read`, today an admin; a key with full access or unscoped
  *    `incident:read`); for anyone else the incident store is not read (see
  *    `OpsChatAPIController.incidentsFor`).
  *
  * The caller is decided on the servlet thread by `ChatCaller.from` and the
  * resulting `ChatIdentity` is handed to the worker as a value.
  */
@RestController
@RequestMapping(Array("/api/v1"))
class OpsChatAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[OpsChatAPIController])

    // Dedicated executor sized like the assistant's — ops chats are
    // interactive and can spend tens of seconds on a tool sequence.
    private val chatExecutor = Executors.newFixedThreadPool(
        16,
        (r: Runnable) => {
            val t = new Thread(r, "ops-chat-" + System.nanoTime())
            t.setDaemon(true)
            t
        }
    )

    private val cancelFlags: ConcurrentHashMap[Long, java.util.concurrent.atomic.AtomicBoolean] = new ConcurrentHashMap()

    @PostMapping(path = Array("/ops-chat/chat"), produces = Array(MediaType.TEXT_EVENT_STREAM_VALUE))
    def chat(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestBody body: String,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): SseEmitter = {
        // Decide who the caller is on the servlet thread, before the stream
        // opens; a refusal is written directly and null tells Spring the
        // request is handled.
        val identity: ChatIdentity = ChatCaller.from(request) match {
            case Allow(id) => id
            case r: Refuse =>
                ChatCaller.refuse(request, response, r)
                return null
        }

        val emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30))
        val emitterId = System.identityHashCode(emitter).toLong
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
        cancelFlags.put(emitterId, cancelled)

        emitter.onCompletion(() => { cancelled.set(true); cancelFlags.remove(emitterId); () })
        emitter.onTimeout(() => { cancelled.set(true); cancelFlags.remove(emitterId); emitter.complete(); () })
        emitter.onError(_ => { cancelled.set(true); cancelFlags.remove(emitterId); () })

        // The worker runs as the identity decided above (its user only) and
        // the tenant captured here; it never reads this thread's state.
        val capturedTenant = TenantContext.get()

        chatExecutor.submit(new Runnable {
            override def run(): Unit = ChatCaller.onWorker(identity, capturedTenant) {
                try {
                    APIKeyValidator.validate(apiKey)
                    runChat(apiKey, body, emitter, cancelled, identity)
                } catch {
                    case e: Exception =>
                        try {
                            AssistantSseSupport.sendEvent(emitter, "error", AssistantSseSupport.makeEvent("error", "message", e.getMessage))
                        } catch {
                            case e2: Exception =>
                                logger.debug("Failed to send error SSE event; client likely disconnected", e2)
                        }
                        try emitter.complete()
                        catch {
                            case e2: Exception =>
                                logger.debug("Failed to complete SSE emitter after chat error; client likely disconnected", e2)
                        }
                }
            }
        })

        emitter
    }

    private def runChat(
        apiKey: String,
        body: String,
        emitter: SseEmitter,
        cancelled: java.util.concurrent.atomic.AtomicBoolean,
        identity: ChatIdentity
    ): Unit = {
        val req = JsonParser.parseString(body).getAsJsonObject
        val messagesArr = req.getAsJsonArray("messages")
        if (messagesArr == null || messagesArr.size() == 0)
            throw new DatrisException("Request body must include a non-empty `messages` array")

        val userMessages: List[(String, String)] = ChatMessages.textHistory(messagesArr)

        val contextSnapshot: Option[JsonObject] =
            if (req.has("context") && !req.get("context").isJsonNull)
                Some(req.getAsJsonObject("context"))
            else None

        // A request may lower the iteration budget, never raise it past the default.
        val maxIterations: Int = ChatCaller.maxIterations(req)
        val maxTokensPerCall: Int = 32000

        val env = DatrisEnvironment.current
        val aiConfig = DatrisEnvironment.aiConfigForChat
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the AI primary secret is configured.")

        // The ui key for users and full-access callers; any other key's own
        // presented key, so its tools are exactly what that key may do.
        val uiKey = ChatCaller.toolKey(identity, apiKey, () => ChatCaller.uiApiKey(apiKey))

        // Operational tools surface first so the agent reaches for run_tap /
        // get_pipeline_status / kill_job before less-relevant build tools.
        // We do NOT filter the catalog — the system prompt steers behavior
        // and we'd rather the agent fall back to a build tool than refuse a
        // legitimate "create a new tap to retry this with a different
        // approach" ask. Decision recorded in the plan.
        val toolDefs = OpsAgentPrompt.reorderToolsOpsFirst(MCPClient.listTools(uiKey))

        val systemPrompt = OpsAgentPrompt.buildOpsSystemPrompt(env.environment)

        // Re-inject the dashboard snapshot on every turn as a leading user
        // message. Cheapest possible cadence — ship dumb, optimize if
        // token usage shows it is wasteful (decision recorded in the plan).
        val withContext: List[(String, String)] = contextSnapshot match {
            case Some(ctx) =>
                ("user", OpsChatAPIController.contextMessage(identity, ctx, () => openIncidents())) :: userMessages
            case None => userMessages
        }

        logger.info("Ops chat starting: tenant=" + env.environment + ", provider=" + aiConfig.provider +
            ", model=" + aiConfig.model + ", tools=" + toolDefs.size + ", maxIter=" + maxIterations +
            ", hasContext=" + contextSnapshot.isDefined)

        // Heartbeat comments keep proxies from closing the SSE socket while a
        // tool call blocks (see AssistantSseSupport.startHeartbeat).
        val heartbeat = AssistantSseSupport.startHeartbeat(emitter, cancelled)
        try AgentLoop.run(
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
        finally heartbeat.cancel(false)

        // If the client already disconnected (a failed write flipped the
        // cancel flag), skip complete() — flushing to a dead socket would log
        // another spurious broken pipe. The container finalizes the response.
        if (!cancelled.get()) {
            try emitter.complete()
            catch {
                case e: Exception =>
                    logger.debug("Failed to complete SSE emitter; client likely disconnected", e)
            }
        }
    }

    /** Open incidents for the context message — the platform's own recovery
      * work the operator should hear about instead of a re-diagnosis. Empty
      * when the recovery agent is off or incidents can't be read. Only ever
      * called through `OpsChatAPIController.contextMessage`, which skips it for
      * a caller not allowed to read incidents. */
    private def openIncidents(): List[Incident] =
        try {
            if (DatrisEnvironment.values != null && DatrisEnvironment.values.recoveryAgentEnabled) IncidentIO.listOpen()
            else Nil
        } catch {
            case _: Exception => Nil
        }
}

object OpsChatAPIController {

    /** Open incidents the Ops chat context may carry for `identity` (decided
      * on the request thread by `ChatCaller`; the same identity the agent's
      * tools run as, so ambient context and tools follow one caller), with
      * the incident filter read from the live stores
      * ([[IncidentsAPIController.storeListing]], listed once and only for a
      * limited key). */
    def incidentsFor(identity: ChatIdentity, load: () => List[Incident]): List[Incident] = {
        val listing = IncidentsAPIController.storeListing()
        incidentsFor(identity, load, listing.readPipeline, listing.readTap, listing.items)
    }

    /** [[incidentsFor]] with the readers given. A caller
      * `ChatCaller.mayReadIncidents` refuses (a session whose role bundle has
      * no `incident:read` — every editor and viewer, restricted or not — or a
      * key without unscoped `incident:read`) gets `Nil` and `load` is not called:
      * step summaries can name resources outside its reach. A key holding
      * `incident:read` that is limited by a `catalog=` / `owner=` scope
      * ([[ai.datris.auth.CallerVisibility.limited]];
      * plans/stories/scoped-key-read-routes.md, Part B) gets exactly what
      * `GET /incidents` shows it ([[IncidentsAPIController.visibleIncidents]]:
      * names of items outside its scope read `[hidden]` in the free text,
      * `items` called at most once). Everyone else (admins, full-access and
      * unlimited keys, user auth off) gets `load()` itself, no reader called. */
    def incidentsFor(
        identity: ChatIdentity,
        load: () => List[Incident],
        readPipeline: String => ai.datris.model.PipelineConfig,
        readTap: String => ai.datris.model.TapConfig,
        items: () => IncidentsAPIController.StoredItems
    ): List[Incident] =
        if (!ChatCaller.mayReadIncidents(identity)) Nil
        else
            identity.key.filter(ai.datris.auth.CallerVisibility.limited) match {
                // A store that cannot be read gives no incidents (fail closed), as openIncidents does.
                case Some(k) =>
                    try IncidentsAPIController.visibleIncidentsFor(k, load(), readPipeline, readTap, IncidentsAPIController.NoRuns, items)
                    catch { case _: Exception => Nil }
                case None => load()
            }

    /** The leading context message `runChat` sends for a dashboard snapshot
      * `ctx`: the snapshot plus the open incidents `incidentsFor` allows
      * `identity`. `load` is only called when incidents are allowed. */
    def contextMessage(identity: ChatIdentity, ctx: JsonObject, load: () => List[Incident]): String =
        OpsAgentPrompt.renderContextMessage(ctx, incidentsFor(identity, load))
}
