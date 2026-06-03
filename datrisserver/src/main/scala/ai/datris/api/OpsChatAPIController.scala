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
  */
@RestController
@RequestMapping(Array("/api/v1"))
class OpsChatAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[OpsChatAPIController])

    // Dedicated executor sized like the assistant's — ops chats are
    // interactive and can spend tens of seconds on a tool sequence.
    private val chatExecutor = Executors.newFixedThreadPool(16, (r: Runnable) => {
        val t = new Thread(r, "ops-chat-" + System.nanoTime())
        t.setDaemon(true)
        t
    })

    private val cancelFlags: ConcurrentHashMap[Long, java.util.concurrent.atomic.AtomicBoolean] = new ConcurrentHashMap()

    /** Same resolveUiApiKey contract as the build-mode controller — see
      * AssistantAPIController.resolveUiApiKey. The UI identity used for
      * outbound MCP calls is the same in either mode. */
    private def resolveUiApiKey(userApiKey: String): String = {
        if (!DatrisEnvironment.values.useApiKeys) return null

        val secretPath = DatrisEnvironment.current.environment + "/ui-api-key"
        SecretsUtil.getSecretMap(secretPath).flatMap(m => Option(m.get("apiKey"))) match {
            case Some(v) if v != null && v.nonEmpty => v
            case _                                  => userApiKey
        }
    }

    @PostMapping(path = Array("/ops-chat/chat"), produces = Array(MediaType.TEXT_EVENT_STREAM_VALUE))
    def chat(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
             @RequestBody body: String): SseEmitter = {
        val emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30))
        val emitterId = System.identityHashCode(emitter).toLong
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
        cancelFlags.put(emitterId, cancelled)

        emitter.onCompletion(() => { cancelled.set(true); cancelFlags.remove(emitterId); () })
        emitter.onTimeout   (() => { cancelled.set(true); cancelFlags.remove(emitterId); emitter.complete(); () })
        emitter.onError     (_  => { cancelled.set(true); cancelFlags.remove(emitterId); () })

        // Same ThreadLocal capture rationale as AssistantAPIController —
        // session-authed requests need UserContext/TenantContext re-set on
        // the worker thread so APIKeyValidator.validate doesn't reject
        // them.
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

        val contextSnapshot: Option[JsonObject] =
            if (req.has("context") && !req.get("context").isJsonNull)
                Some(req.getAsJsonObject("context"))
            else None

        val maxIterations: Int =
            if (req.has("maxIterations") && !req.get("maxIterations").isJsonNull) req.get("maxIterations").getAsInt
            else 50
        val maxTokensPerCall: Int = 16000

        val env = DatrisEnvironment.current
        val aiConfig = DatrisEnvironment.aiConfigForCodegen
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the codegen secret is configured.")

        val uiKey = resolveUiApiKey(apiKey)

        // Operational tools surface first so the agent reaches for run_tap /
        // get_pipeline_status / kill_job before less-relevant build tools.
        // We do NOT filter the catalog — the system prompt steers behavior
        // and we'd rather the agent fall back to a build tool than refuse a
        // legitimate "create a new tap to retry this with a different
        // approach" ask. Decision recorded in the plan.
        val toolDefs = reorderToolsOpsFirst(MCPClient.listTools(uiKey))

        val systemPrompt = buildOpsSystemPrompt(env.environment)

        // Re-inject the dashboard snapshot on every turn as a leading user
        // message. Cheapest possible cadence — ship dumb, optimize if
        // telemetry shows it wastes tokens (decision recorded in the plan).
        val withContext: List[(String, String)] = contextSnapshot match {
            case Some(ctx) => ("user", renderContextMessage(ctx)) :: userMessages
            case None      => userMessages
        }

        logger.info("Ops chat starting: tenant=" + env.environment + ", provider=" + aiConfig.provider +
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
        if (!cancelled.get()) { try emitter.complete() catch { case _: Exception => () } }
    }

    /** Operational tools first, then the rest. This isn't filtering — the
      * agent can still call build-mode tools when warranted (e.g. operator
      * legitimately wants a new tap to recover a use case). Just biases the
      * tool listing the model sees so the *first* tool it considers when
      * recovering from a failure is the right one. */
    private def reorderToolsOpsFirst(tools: List[JsonObject]): List[JsonObject] = {
        val opsToolNames = Set(
            "run_tap",
            "get_pipeline_status",
            "get_job_status",
            "kill_job",
            "get_tap_logs",
            "get_tap",
            "get_pipeline",
            "list_taps",
            "list_pipelines",
            "list_tap_secrets",
            "get_tap_secret_fields",
            "update_tap",
            "update_secret",
            "wait_seconds"
        )
        val (ops, rest) = tools.partition { t =>
            val n = Option(t.get("name")).map(_.getAsString).getOrElse("")
            opsToolNames.contains(n)
        }
        ops ++ rest
    }

    /** Render the dashboard context snapshot as a compact, human-readable
      * leading user message. We send it as `user` (not `system`) so the
      * agent treats it as "what the operator is currently looking at" —
      * grounding context for the operator's actual question that follows. */
    private def renderContextMessage(ctx: JsonObject): String = {
        val sb = new StringBuilder
        sb.append("(Current Ops dashboard snapshot — what I'm looking at right now)\n\n")

        val window = Option(ctx.get("window")).map(_.getAsString).getOrElse("24h")
        sb.append("Window: ").append(window).append("\n\n")

        val failing = Option(ctx.getAsJsonArray("failingItems")).map(_.asScala.toList).getOrElse(Nil)
        if (failing.isEmpty) {
            sb.append("Failures: none in window.\n\n")
        } else {
            sb.append("Failures (").append(failing.size).append("):\n")
            failing.foreach { el =>
                val f = el.getAsJsonObject
                val kind = strOpt(f, "kind").getOrElse("?")
                val name = strOpt(f, "name").getOrElse("?")
                val catalog = strOpt(f, "catalog").map(c => " [" + c + "]").getOrElse("")
                val reason = strOpt(f, "reason").getOrElse("")
                val time = strOpt(f, "timeIso").getOrElse("")
                val recovered = boolOpt(f, "recovered").getOrElse(false)
                val count = intOpt(f, "failureCount").getOrElse(1)
                val related = strOpt(f, "relatedTapName").map(t => ", upstream tap=" + t).getOrElse("")
                val token = strOpt(f, "pipelineToken").map(t => ", pipeline_token=" + t).getOrElse("")
                val recoveredLabel = if (recovered) " [RECOVERED]" else ""
                sb.append("  - ").append(kind).append(" `").append(name).append("`").append(catalog)
                  .append(": ").append(reason)
                  .append(" (").append(count).append("x")
                  .append(if (time.nonEmpty) ", " + time else "")
                  .append(related).append(token).append(")").append(recoveredLabel).append("\n")
            }
            sb.append("\n")
        }

        val stale = Option(ctx.getAsJsonArray("staleTaps")).map(_.asScala.toList).getOrElse(Nil)
        if (stale.nonEmpty) {
            sb.append("Stale taps (").append(stale.size).append("):\n")
            stale.foreach { el =>
                val s = el.getAsJsonObject
                val name = strOpt(s, "name").getOrElse("?")
                val cadence = strOpt(s, "cadenceLabel").getOrElse("?")
                val lastRun = strOpt(s, "lastRunIso").getOrElse("never")
                sb.append("  - `").append(name).append("` expects ").append(cadence)
                  .append(", last ran ").append(lastRun).append("\n")
            }
            sb.append("\n")
        }

        val vols = Option(ctx.getAsJsonArray("pipelineVolumes")).map(_.asScala.toList).getOrElse(Nil)
        if (vols.nonEmpty) {
            sb.append("Pipeline volume anomalies (top ").append(vols.size)
              .append(" by |delta| this ").append(window).append(" vs the prior ").append(window).append("):\n")
            vols.foreach { el =>
                val v = el.getAsJsonObject
                val name = strOpt(v, "name").getOrElse("?")
                val current = intOpt(v, "current").getOrElse(0)
                val prior = intOpt(v, "prior").getOrElse(0)
                val delta = if (v.has("deltaPct") && !v.get("deltaPct").isJsonNull) v.get("deltaPct").getAsInt.toString + "%" else "n/a"
                sb.append("  - `").append(name).append("`: this ").append(window).append("=").append(current)
                  .append(", prior ").append(window).append("=").append(prior).append(", vs prior=").append(delta).append("\n")
            }
            sb.append("\n")
        }

        sb.append("Use this snapshot to ground your answers. Refer to taps and pipelines by name. ")
            .append("If the operator asks about \"the failure\" or \"that one,\" pick the most recent unrecovered failure from the list above.")
        sb.toString
    }

    private def strOpt(o: JsonObject, key: String): Option[String] =
        if (o.has(key) && !o.get(key).isJsonNull) Some(o.get(key).getAsString) else None

    private def intOpt(o: JsonObject, key: String): Option[Int] =
        if (o.has(key) && !o.get(key).isJsonNull) Some(o.get(key).getAsInt) else None

    private def boolOpt(o: JsonObject, key: String): Option[Boolean] =
        if (o.has(key) && !o.get(key).isJsonNull) Some(o.get(key).getAsBoolean) else None

    private def buildOpsSystemPrompt(tenantEnv: String): String = {
        val sb = new StringBuilder
        sb.append("# Datris Ops Assistant\n\n")
        sb.append("You are the Ops chat assistant for tenant `").append(tenantEnv).append("`. ")
        sb.append("The operator is looking at a live Activity dashboard inside the Datris UI — failures, recovered taps, stale taps, per-pipeline volume anomalies. ")
        sb.append("When a dashboard snapshot is provided as the leading user message in this conversation, treat it as ground truth for what the operator is looking at *right now*. ")
        sb.append("When no snapshot is provided (the operator may be on a sub-tab that doesn't publish one), call `list_taps` and `list_pipelines` if you need to discover state, and answer based on what those tools return.\n\n")

        sb.append("## Mission\n\n")
        sb.append("You are NOT building new pipelines from scratch — there is a separate build-mode Assistant for that. Your job is to help the operator understand and recover the data flows that already exist:\n")
        sb.append("- Explain failures in plain English. Cite the actual error and what likely caused it.\n")
        sb.append("- Recover when asked. Re-run taps, kill stuck jobs, rotate secrets, fix scripts.\n")
        sb.append("- Diagnose anomalies. When a pipeline's today-vs-7d-average is sharply off, walk through the likely causes (upstream slowdown, schedule miss, data shape change) and propose the next check.\n")
        sb.append("- Triage stale taps. A tap that hasn't run in 2× its cadence is either upstream-broken or scheduler-broken — figure out which and propose the fix.\n\n")

        sb.append("## Behavior rules\n\n")
        sb.append("- **Act on items by name.** The snapshot above gives you tap and pipeline names. When the operator says \"why did it fail?\" or \"re-run that one,\" pick the most recent unrecovered failure from the snapshot — don't ask the operator to paste a name they can see on screen.\n")
        sb.append("- **Use `pipeline_token` from the snapshot to read root causes.** Each pipeline-kind failure in the snapshot includes a `pipeline_token=<UUID>`. To explain *why* a job failed — including for [RECOVERED] failures whose original error has been superseded by a successful retry in the rollup — call `get_pipeline_status(pipeline_token=<that UUID>)`. The per-job response contains the full event trail, the platform's `error` and `warning` rows, and the AI explanation. Do NOT tell the operator you can't see the original error; the token is in the snapshot, use it.\n")
        sb.append("- **Never act on a [RECOVERED] item without an explicit, item-specific request.** Items marked [RECOVERED] in the snapshot are healthy right now — the platform's own retry already fixed them. Diagnostic questions about a recovered item (\"why did it fail?\", \"what happened?\") get an explanation, NOT a fresh `run_tap`. Only re-run a recovered item if the operator explicitly says \"run it again\" / \"re-run X\" naming that specific item — and even then, push back once (\"the tap looks healthy; want me to run it anyway?\") before acting. The platform's spinner cost is non-trivial and an unrequested re-run is worse than no action.\n")
        sb.append("- **Take side-effecting actions ONLY when the operator's most recent message names the action or unambiguously authorizes it.** `run_tap`, `update_tap`, `update_secret`, `kill_job`, `delete_*` all side-effect the platform. The trigger phrase has to be something like \"run X\", \"re-run\", \"go ahead\", \"do it\", \"yes\" (in response to a question you just asked) — applied to a specific named target. Vague follow-ups (\"try again\", \"retry\", \"and?\", \"keep going\") refer to **the previous diagnostic step**, not a new action. If the prior turn ended in an error reading data, \"try again\" means retry that read, not \"take an action you never proposed.\" When in doubt, ask one clarifying question (\"do you want me to retry the diagnostic, or actually re-run the tap?\") rather than guessing.\n")
        sb.append("- **Prefer the recovery path over a redesign — but only when there's something to recover.** When a tap IS currently failing (unrecovered in the snapshot) on a transient cause (rate limit, network blip, missing API quota), the right action is usually `run_tap` again — not rebuilding the tap. Structural failures (API shape changed, credentials revoked, schema mismatch) need a rebuild. If the failure is already [RECOVERED], nothing needs recovering at all.\n")
        sb.append("- **Confirm before destructive actions.** `kill_job`, `delete_tap`, `delete_pipeline`, `update_secret` on an existing secret — restate exactly what you'll do and wait for explicit confirmation. \"Yeah\" or \"do it\" counts; ambiguous answers do not.\n")
        sb.append("- **Don't create new taps or pipelines from a blank slate.** If the operator wants new data ingested, route them to the Assistant tab — that's the build-mode chat. The exception is when an *existing* failed flow needs a near-clone with a small change (e.g. \"build the same thing pointing at the new endpoint\") and the operator has explicitly asked for it.\n")
        sb.append("- **Monitor runs you start.** When you call `run_tap` and it returns a `publisher_token`, poll `get_pipeline_status` with adaptive backoff (5, 10, 20, 30, 60, 60, 60s) until `rollup.allDone`. Don't hand off mid-flight with \"check back in a bit.\" Report the terminal outcome.\n")
        sb.append("- **Errors in `get_pipeline_status` are not run failures.** If the status endpoint itself errors, wait briefly and retry. Only a terminal `error` on the run rollup counts as a failure.\n")
        sb.append("- **Be brief.** This is a side-panel chat with limited width. Short paragraphs, no elaborate restatements of what the operator already knows. One line of polling progress per cycle is enough.\n")
        sb.append("- **Tap secrets are tagged `_type=tap`.** You can read and rotate them but you can only modify secrets the platform owns (the MCP layer enforces this). If a rotation fails with an ownership error, surface that clearly — don't retry.\n\n")

        sb.append("## When the snapshot looks empty\n\n")
        sb.append("If the dashboard snapshot has no failures, no stale taps, and no volume anomalies, the platform is healthy. Say so plainly. Offer to spot-check anything the operator names, but don't fabricate problems to solve.\n\n")

        sb.append("## Finish\n\n")
        sb.append("When the action is done — re-run completed, secret rotated, job killed — say so in one or two sentences. The operator can see most of it in the dashboard already; you're the audit trail for what *just happened in this chat*.")
        sb.toString
    }
}
