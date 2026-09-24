package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{JsonObject, JsonParser}
import ai.datris.config.RequiresRole
import ai.datris.model.{DatrisEnvironment, DatrisException, TenantContext, UserContext}
import ai.datris.policy.PolicyIO
import ai.datris.util.{AgentLoop, APIKeyValidator, ConfigAgentPrompt, ConfigAgentTools, ConfigToolExecutor, ConfigToolFilter, ConfirmationRegistry, SecretsUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation._
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}
import scala.collection.JavaConverters._

/** REST controller for the Configuration chat — the side panel on the
  * Configuration tab.
  *
  * Sibling of the Assistant, Ops, Catalog and Search chat controllers and
  * shares their SSE wire format via `AssistantSseSupport`, plus one event of
  * its own: `confirm_request`.
  *
  * Differences vs. the other controllers:
  *  - Admin only (class-level role gate), like the Configuration tab.
  *  - A Datris-defined tool list (`ConfigAgentTools`), not the MCP catalog,
  *    filtered to the sub-tabs the UI would show (`ConfigToolFilter`).
  *  - Tools run through `ConfigToolExecutor`: a mutating tool is performed
  *    only with a one-shot confirmation token the user approved. Tokens live
  *    in the process-wide `ConfirmationRegistry.shared`, scoped by the
  *    username and the request's `sessionId` (`scopeFor`), so a token issued
  *    in one POST is consumed in the next, and only by the same admin.
  *  - The model sees redacted mutating-tool results; the `tool_result` SSE
  *    event carries the full text for the UI.
  */
@RestController
@RequestMapping(Array("/api/v1"))
@RequiresRole(Array("admin"))
class ConfigChatAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ConfigChatAPIController])

    private val chatExecutor = Executors.newFixedThreadPool(
        16,
        (r: Runnable) => {
            val t = new Thread(r, "config-chat-" + System.nanoTime())
            t.setDaemon(true)
            t
        }
    )

    private val cancelFlags: ConcurrentHashMap[Long, java.util.concurrent.atomic.AtomicBoolean] = new ConcurrentHashMap()

    /** Same resolveUiApiKey contract as the other chat controllers — see
      * AssistantAPIController.resolveUiApiKey. */
    private def resolveUiApiKey(userApiKey: String): String = {
        if (!DatrisEnvironment.values.useApiKeys) return null

        val secretPath = DatrisEnvironment.current.environment + "/ui-api-key"
        SecretsUtil.getSecretMap(secretPath).flatMap(m => Option(m.get("apiKey"))) match {
            case Some(v) if v != null && v.nonEmpty => v
            case _ => userApiKey
        }
    }

    @PostMapping(path = Array("/config-chat/chat"), produces = Array(MediaType.TEXT_EVENT_STREAM_VALUE))
    def chat(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestBody body: String): SseEmitter = {
        val emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30))
        val emitterId = System.identityHashCode(emitter).toLong
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
        cancelFlags.put(emitterId, cancelled)

        emitter.onCompletion(() => { cancelled.set(true); cancelFlags.remove(emitterId); () })
        emitter.onTimeout(() => { cancelled.set(true); cancelFlags.remove(emitterId); emitter.complete(); () })
        emitter.onError(_ => { cancelled.set(true); cancelFlags.remove(emitterId); () })

        // Same ThreadLocal capture rationale as the other chat controllers.
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
                        } catch {
                            case e2: Exception =>
                                logger.debug("Failed to send error SSE event; client likely disconnected", e2)
                        }
                        try emitter.complete()
                        catch {
                            case e2: Exception =>
                                logger.debug("Failed to complete SSE emitter after chat error; client likely disconnected", e2)
                        }
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

        val tab: Option[String] =
            if (req.has("context") && req.get("context").isJsonObject) strOpt(req.getAsJsonObject("context"), "tab")
            else None

        val maxIterations: Int =
            if (req.has("maxIterations") && !req.get("maxIterations").isJsonNull) req.get("maxIterations").getAsInt
            else 50
        val maxTokensPerCall: Int = 32000

        val values = DatrisEnvironment.values
        val env = DatrisEnvironment.current
        val aiConfig = DatrisEnvironment.aiConfigForChat
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the AI primary secret is configured.")

        val uiKey = resolveUiApiKey(apiKey)

        // Without user auth there is no session user; the admin is the actor.
        val username: String =
            if (!values.useUserAuth) "admin"
            else UserContext.get().map(_.username).filter(u => u != null && u.nonEmpty).getOrElse("admin")

        // A token issued in one POST is consumed in the next, so the scope is
        // the admin plus the chat session, never the emitter.
        val scope: String = ConfigChatAPIController.scopeFor(username, strOpt(req, "sessionId").map(_.trim).filter(_.nonEmpty))

        val toolDefs = ConfigToolFilter(
            ConfigAgentTools.readTools ++ ConfigAgentTools.mutatingTools,
            useUserAuth = values.useUserAuth,
            useApiKeys = values.useApiKeys,
            hostedOrTrial = values.hosted || env.isTrial,
            policyEnabled = PolicyIO.enabled
        )

        val executor = new ConfigToolExecutor(scope, username, uiKey, ConfirmationRegistry.shared)

        val systemPrompt = ConfigAgentPrompt.build(env.environment, tab)

        logger.info("Config chat starting: tenant=" + env.environment + ", provider=" + aiConfig.provider +
            ", model=" + aiConfig.model + ", tools=" + toolDefs.size + ", maxIter=" + maxIterations +
            ", tab=" + tab.getOrElse("-"))

        val heartbeat = AssistantSseSupport.startHeartbeat(emitter, cancelled)
        try AgentLoop.run(
                aiConfig = aiConfig,
                system = systemPrompt,
                userMessages = userMessages,
                toolDefs = toolDefs,
                apiKey = uiKey,
                enableThinking = env.extendedThinking,
                maxIterations = maxIterations,
                maxTokensPerCall = maxTokensPerCall,
                cancelled = () => cancelled.get(),
                sink = (evt: AgentLoop.LoopEvent) => {
                    // The loop's tool_result carries what the model saw (redacted);
                    // the UI gets the executor's full text of that same call.
                    val out = evt match {
                        case AgentLoop.LoopEvent.ToolResult(id, name, _, false) =>
                            AgentLoop.LoopEvent.ToolResult(id, name, executor.lastFullResult, isError = false)
                        case other => other
                    }
                    if (!cancelled.get() && !AssistantSseSupport.emitLoopEvent(emitter, out)) cancelled.set(true)
                },
                execute = (n: String, i: JsonObject) => executor.execute(n, i)
            )
        finally heartbeat.cancel(false)

        if (!cancelled.get()) {
            try emitter.complete()
            catch {
                case e: Exception =>
                    logger.debug("Failed to complete SSE emitter; client likely disconnected", e)
            }
        }
    }

    private def strOpt(o: JsonObject, key: String): Option[String] =
        if (o.has(key) && o.get(key).isJsonPrimitive) Some(o.get(key).getAsString) else None
}

object ConfigChatAPIController {

    /** Confirmation-token scope: the admin who proposed the change plus the
      * chat session, so another admin in the same session cannot confirm it. */
    private[datris] def scopeFor(username: String, sessionId: Option[String]): String =
        username + ":" + sessionId.getOrElse("user")
}
