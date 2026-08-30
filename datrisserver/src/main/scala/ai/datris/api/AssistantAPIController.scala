package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.model.{DatrisEnvironment, DatrisException, TenantContext, UserContext}
import ai.datris.util.{AgentLoop, APIKeyValidator, AttachmentStore, DestinationAvailabilityUtil, MCPClient, SecretsUtil, TapPromptInjector}
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
    private val chatExecutor = Executors.newFixedThreadPool(
        16,
        (r: Runnable) => {
            val t = new Thread(r, "assistant-chat-" + System.nanoTime())
            t.setDaemon(true)
            t
        }
    )

    /** Active session cancellation flags, keyed by emitter identity hash. The
      * SseEmitter's onCompletion / onTimeout callbacks flip this when the client
      * disconnects, so the agent loop can bail at its next checkpoint. */
    private val cancelFlags: ConcurrentHashMap[Long, java.util.concurrent.atomic.AtomicBoolean] = new ConcurrentHashMap()

    // ------------------------------------------------------------------
    // GET /api/v1/assistant/init — warm caches, return tool catalog
    // ------------------------------------------------------------------

    /** Resolves the API key the UI identity uses on MCP-bound REST calls
      * driven by the Assistant. The UI is one logical caller — direct REST
      * traffic and Assistant-routed MCP traffic share the same key — so this
      * is just "what does the UI present to the auth layer."
      *
      *  - When `useApiKeys=false`: returns null. Anonymous mode; no
      *    `x-api-key` header is sent on outbound calls.
      *  - When `useApiKeys=true`: returns the `apiKey` field from the
      *    `{env}/ui-api-key` Vault secret. Falls back to the user-supplied
      *    key on the request if the secret is missing or empty (preserves
      *    behavior for deployments upgraded before this secret existed). */
    private def resolveUiApiKey(userApiKey: String): String = {
        if (!DatrisEnvironment.values.useApiKeys) return null

        val secretPath = DatrisEnvironment.current.environment + "/ui-api-key"
        SecretsUtil.getSecretMap(secretPath).flatMap(m => Option(m.get("apiKey"))) match {
            case Some(v) if v != null && v.nonEmpty => v
            case _ => userApiKey
        }
    }

    @GetMapping(path = Array("/assistant/init"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def init(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)

            // MCP-bound calls go out under the UI identity (same as direct
            // REST traffic from the UI tabs).
            val uiKey = resolveUiApiKey(apiKey)

            val tools = MCPClient.listTools(uiKey)
            val toolNames = tools.flatMap(t => Option(t.get("name")).map(_.getAsString)).toList

            val workflowReference: String =
                try MCPClient.readResource("datris://pipeline-config-reference", uiKey)
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
            payload.addProperty(
                "provider",
                Option(DatrisEnvironment.aiConfigForChat)
                    .map(_.provider.toLowerCase).getOrElse("unknown")
            )
            payload.addProperty(
                "model",
                Option(DatrisEnvironment.aiConfigForChat)
                    .map(_.model).getOrElse("")
            )
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
    def chat(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestBody body: String): SseEmitter = {
        // 30-minute timeout — sessions are interactive and can be long with many
        // tool calls, but anything beyond this is almost certainly a stuck loop.
        val emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30))
        val emitterId = System.identityHashCode(emitter).toLong
        val cancelled = new java.util.concurrent.atomic.AtomicBoolean(false)
        cancelFlags.put(emitterId, cancelled)

        emitter.onCompletion(() => { cancelled.set(true); cancelFlags.remove(emitterId); () })
        emitter.onTimeout(() => { cancelled.set(true); cancelFlags.remove(emitterId); emitter.complete(); () })
        emitter.onError(_ => { cancelled.set(true); cancelFlags.remove(emitterId); () })

        // Capture the request-thread ThreadLocals so the worker thread can
        // re-establish them. UserContext drives the session-bypass in
        // APIKeyValidator.validate(); TenantContext routes multi-tenant
        // requests. Without restoring these, the worker thread sees None
        // for both and validate() rejects what was a session-authed call.
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

        // Resolve any files the user dropped into this turn. The UI staged the
        // bytes server-side and sends just the handles here; we look each one
        // up (scoped to this tenant), build a descriptor the model can read,
        // and hand AgentLoop a handle→bytes map so it can substitute the real
        // bytes when the model calls a file tool with the attachmentId.
        val tenantEnv = DatrisEnvironment.current.environment
        val attachmentIds: List[String] =
            if (req.has("attachments") && req.get("attachments").isJsonArray)
                req.getAsJsonArray("attachments").asScala.toList.flatMap { el =>
                    val o = el.getAsJsonObject
                    if (o.has("attachmentId") && !o.get("attachmentId").isJsonNull) Some(o.get("attachmentId").getAsString) else None
                }
            else Nil
        val resolvedAttachments: List[AttachmentStore.Attachment] =
            attachmentIds.flatMap(id => AttachmentStore.get(id, tenantEnv))
        val attachmentMap: Map[String, (String, Array[Byte])] =
            resolvedAttachments.map(a => a.id -> (a.filename, a.bytes)).toMap
        val effectiveMessages: List[(String, String)] =
            if (resolvedAttachments.isEmpty) userMessages
            else appendAttachmentDescriptor(userMessages, buildAttachmentDescriptor(resolvedAttachments))

        val maxIterations: Int =
            if (req.has("maxIterations") && !req.get("maxIterations").isJsonNull) req.get("maxIterations").getAsInt
            else 50
        val maxTokensPerCall: Int = 32000

        val env = DatrisEnvironment.current
        val aiConfig = DatrisEnvironment.aiConfigForChat
        if (aiConfig == null)
            throw new DatrisException("AI configuration is not initialized. Ensure ai.enabled: true and the AI primary secret is configured.")

        // The UI identity for MCP-bound calls — same key the UI sends on
        // direct REST traffic from other tabs.
        val uiKey = resolveUiApiKey(apiKey)

        // Fetch MCP tool catalog + workflow reference resource (both cached).
        // Append the synthetic `request_tap_secret_from_user` tool. The agent
        // sees it like any other tool, but AgentLoop intercepts the call
        // instead of dispatching to MCP — it emits a SecretRequest SSE event
        // that the UI renders as an inline credentials form.
        val toolDefs = MCPClient.listTools(uiKey) :+ syntheticSecretToolDef()
        val workflowReference =
            try MCPClient.readResource("datris://pipeline-config-reference", uiKey)
            catch {
                case e: Exception =>
                    logger.warn("Failed to read pipeline-config-reference MCP resource; assistant prompt will omit the workflow reference", e)
                    ""
            }

        val systemPrompt = buildSystemPrompt(workflowReference, env.environment)

        logger.info("Assistant chat starting: tenant=" + env.environment + ", provider=" + aiConfig.provider +
            ", model=" + aiConfig.model + ", tools=" + toolDefs.size + ", maxIter=" + maxIterations +
            ", thinking=" + env.extendedThinking)

        AgentLoop.run(
            aiConfig = aiConfig,
            system = systemPrompt,
            userMessages = effectiveMessages,
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
            },
            attachments = attachmentMap
        )

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

    /** Prompt-facing display names for the canonical destination names
      * emitted by DestinationAvailabilityUtil. */
    private val destinationDisplayNames = Map(
        "mongodb" -> "MongoDB",
        "postgres" -> "PostgreSQL",
        "objectstore" -> "object store",
        "snowflake" -> "Snowflake",
        "databricks" -> "Databricks"
    )

    /** Longer blurbs used the first time each destination is named in the
      * destination-defaults rule. */
    private val destinationBlurbs = Map(
        "mongodb" -> "**MongoDB** (flexible schema, tolerates shape drift across runs)",
        "postgres" -> "**PostgreSQL**",
        "objectstore" -> "**object store** (columnar files — Parquet or ORC)",
        "snowflake" -> "**Snowflake** (loads the user's own Snowflake account)",
        "databricks" -> "**Databricks** (loads a Unity Catalog managed Delta table in the user's own workspace)"
    )

    /** "A, B, or C" — or-join used inside literal prompt examples. */
    private def orJoin(items: Seq[String]): String = items match {
        case Seq() => ""
        case Seq(a) => a
        case Seq(a, b) => a + " or " + b
        case many => many.init.mkString(", ") + ", or " + many.last
    }

    private def buildSystemPrompt(workflowReference: String, tenantEnv: String): String = {
        // Empty when no registry is configured — the prompt is then byte-identical
        // to the pre-registry prompt (see plans/assistant-data-sources-registry.md).
        val sourcesSection = TapPromptInjector.approvedSourcesSection()
        val sb = new StringBuilder
        if (workflowReference != null && workflowReference.nonEmpty) {
            sb.append(workflowReference)
            sb.append("\n\n---\n\n")
        }
        sb.append("# In-Product Assistant\n\n")
        sb.append("You are the in-product Datris Assistant for tenant `").append(tenantEnv).append("`. ")
        sb.append(
            "The user is interacting with you in a chat tab inside the Datris UI. They can see your tool calls and reasoning in real time as you work.\n\n"
        )
        sb.append("## Behavior rules\n\n")
        sb.append(
            "- **Check existing platform state FIRST — before any recommendation, before listing external sources, before asking clarifying scope questions.** On the first turn of any data-related request (anything that sounds like \"I'm looking for X\", \"can you get me Y\", \"do you have Z\", \"I want to ingest...\"), call `list_pipelines` AND `list_taps` BEFORE generating any text reply. Then anchor your response in what already exists: `\"There's already a `records` pipeline pulling from a public REST API — does that cover what you need, or do you want to extend it / add ids / pick a different source?\"`. Do NOT enumerate external API options until you've confirmed nothing in the platform already covers the ask. The user almost always cares more about what's already running than about a generic options menu drawn from your training data.\n"
        )
        sb.append(
            "- When existing pipelines/taps DO partially cover the request, name them specifically and ask whether to extend, modify, or build alongside. When nothing covers it, then — and only then — propose external sources or ask scope questions.\n"
        )
        sb.append(
            "- When the user asks for data from an external source AND no existing pipeline/tap covers it, find a suitable source (web_search if available, or your own knowledge), then create the tap and pipeline that delivers that data. Don't just describe what to do — do it.\n"
        )
        sb.append("- Always check existing taps/pipelines first via `list_taps` and `list_pipelines` before creating new ones.\n")
        sb.append(
            "- **Set expectations before slow tools.** Calling `create_tap` with an `instruction` triggers server-side AI script generation that typically takes 1–3 minutes with no intermediate output. In the same message that announces the call, tell the user what to expect: \"Generating the tap script with AI — this usually takes a minute or two.\" Same for `create_pipeline` on a large file. Never let a multi-minute tool run start without having told the user roughly how long it takes.\n"
        )
        sb.append(
            "- **If script generation fails, write the script yourself — that IS the standard recovery, not a workaround.** A `create_tap` error like \"doesn't define a fetch() function\" or \"returned narrative text\" means the server-side generator misfired. Do not retry the same instruction (another 1–3 silent minutes for the same likely outcome) and do not just report failure. For any API you know well, compose the Python `fetch()` script directly and call `create_tap` again passing it via `script`. Tell the user in one sentence: generation failed, you wrote the script directly.\n"
        )
        sb.append(
            "- Tap secrets must be tagged `_type=tap`. When you call `create_tap_secret`, the platform sets that automatically — don't try to set `_type` yourself.\n"
        )
        sb.append(
            "- **Reuse existing tap secrets before creating new ones.** ALWAYS call `list_tap_secrets` before asking the user for credentials. If a candidate already exists, call `get_tap_secret_fields` to confirm it has the keys your tap script needs (field names only — values are never returned to you). When a matching secret exists, just pass its name as `secret_name` to `create_tap` and move on.\n"
        )
        sb.append(
            "- **Platform secrets (destination credentials) are read-only for agents.** Some destinations reference a `credentialsSecret` that holds destination credentials — NOT a tap secret. Examples: `objectStore` with `provider=s3` (fields `accessKey`, `secretKey`, `region`), a `database` with `useSnowflake=true` (fields `account`, `user`, and `privateKey` for key-pair auth or `password` as a fallback), and a `database` with `useDatabricks=true` (fields `host`, plus `clientId`/`clientSecret` for service-principal OAuth or `token` for a personal access token). These live on the Platform tab and are owned by the user, not the agent. Use `list_platform_secrets` to discover what's available and `get_platform_secret_fields` to verify a candidate has the required keys. The agent CANNOT create, update, or delete platform secrets — if nothing suitable exists, tell the user plainly: \"You'll need to create a secret on the Configuration → Secrets → Platform tab with fields <list the keys>; then give me the name and I'll wire it into the pipeline.\" Do NOT try `create_tap_secret` for destination credentials — that would tag the secret `_type=tap` and the destination resolver would not find it.\n"
        )
        sb.append(
            "- **When no existing secret fits, ask the user via the credentials form.** Call `request_tap_secret_from_user` with the proposed secret name, the list of required field NAMES (not values), and a one-sentence reason. The UI will render a credentials form inline — the user fills it in (or picks an existing secret from a dropdown), the values go straight to Vault, and the conversation resumes via the user's next message. Do NOT type credential prompts in plain text and ask the user to paste values into chat — credential values must never enter the chat content. Do NOT call `create_tap_secret` with values you don't have.\n"
        )
        sb.append(
            "- **The `fields` list on `request_tap_secret_from_user` is for TRUE SECRETS ONLY.** Include only values the user would refuse to paste into a chat: API keys, passwords, OAuth tokens, signing keys, private certificates. Do NOT include configuration values (regions, locations, account/project/tenant IDs, base URLs, endpoint URLs, container/bucket/database names, table/collection names, schemas, identifiers the user has already shared in conversation) — those belong in the tap script (hardcoded) or as `run_tap(params=...)` (per-run), NOT in the secret. Before composing `fields`, scan the conversation: anything the user has already volunteered belongs in code/config; only the values the user has NOT mentioned and SHOULD NOT type into chat belong on the form. The 'is this a secret?' test: would the user reasonably refuse to type this value into chat? If yes, it's a secret; if no, it's config.\n"
        )
        sb.append(
            "- **Tap scripts read platform data WITHOUT credentials — never ask the user for the platform's own database login.** Every tap run (test, manual, cron) auto-injects `DATRIS_PLATFORM_HOST`, `DATRIS_PLATFORM_PORT`, `DATRIS_POSTGRES_DATABASE`, and `DATRIS_MONGODB_DATABASE` into the script's environment, independent of the tap secret. When a tap's fetch logic depends on data already stored in Datris (an id/key list a pipeline maintains, a lookup table), the script queries it through the platform's own API, which executes with the platform's own credentials: `POST http://{host}:{port}/api/v1/query/postgres` with body `{\"sql\": \"SELECT col FROM public.table_name\", \"database\": <DATRIS_POSTGRES_DATABASE>, \"limit\": -1}` returning `{results, count}`, or `POST /api/v1/query/mongodb` with `{\"query\": ..., \"database\": <DATRIS_MONGODB_DATABASE>, \"collection\": ..., \"limit\": -1}`. Always pass `\"limit\": -1` — omitting it applies a tiny preview default. Therefore: do NOT include Postgres/Mongo host/user/password fields on `request_tap_secret_from_user` for platform access, and do NOT freeze a snapshot of platform data into the script because you believe the script can't reach it — it can, live, on every run. The tap secret carries ONLY the external source's credentials (e.g. an API key). " +
                "This platform-data lane is PYTHON-ONLY: HTTP taps (scriptKind `http`, a user-hosted endpoint) run outside the platform and cannot reach the callback, so never recommend or convert a platform-data-reading tap to HTTP kind.\n"
        )
        sb.append(
            "- Placeholder values like `DATABASE_NAME`, `SCHEMA_NAME` in pipeline configs are substituted automatically by the platform. Don't worry about filling them in literally.\n"
        )
        sb.append("\n")

        // INJECTION, NOT INSTRUCTION (see DestinationAvailabilityUtil): the
        // harness resolves which structured destinations this deployment has
        // and bakes the concrete list into the prompt — including every
        // literal EXAMPLE that enumerates destinations, because the model
        // mimics an example's enumeration over the prose rule. The model is
        // never told to check availability before offering (conditional
        // offers flicker); it unconditionally offers exactly what the prompt
        // names. Fails open to the full five-destination set on any error.
        val structuredDests = DestinationAvailabilityUtil.availableStructuredDestinations()
        val defaultDest = if (structuredDests.contains("mongodb")) "mongodb" else structuredDests.head
        val altDests = structuredDests.filterNot(_ == defaultDest)

        sb.append("## Destination defaults (apply unless the user explicitly asks for something else)\n\n")
        sb.append("- **Structured / semi-structured taps** (CSV, JSON, XML, API responses, table-shaped data) → ")
        sb.append(destinationBlurbs(defaultDest)).append(" by default.")
        if (altDests.nonEmpty) {
            sb.append(" ").append(altDests.map(destinationBlurbs).mkString(", "))
            sb.append(if (altDests.size == 1) " is an equally supported alternative." else " are equally supported alternatives.")
            sb.append(" When proposing a destination for structured data, briefly name every one of these options in the question (e.g. \"")
            sb.append(destinationDisplayNames(defaultDest)).append(" by default; ")
            sb.append(orJoin(altDests.map(destinationDisplayNames)))
            sb.append(" also available — which do you prefer?\") so the user can pick. Do not silently default to ")
            sb.append(destinationDisplayNames(defaultDest))
            sb.append(" without mentioning the alternatives — the user may not know the other options exist.")
        } else {
            sb.append(" It is the only structured destination configured in this deployment, so there are no alternatives to offer.")
        }
        if (structuredDests.contains("snowflake"))
            sb.append(
                " Snowflake requires a `credentialsSecret` platform secret (account/user/key) plus a warehouse and database: when the user picks it, discover the secret via `list_platform_secrets` and ask for the rest — do NOT require any of that to exist before offering it."
            )
        if (structuredDests.contains("databricks"))
            sb.append(
                " Databricks requires a `credentialsSecret` platform secret (host, plus clientId/clientSecret or token) plus a SQL warehouse ID (`warehouse`) and Unity Catalog catalog (`database`): same flow — discover the secret via `list_platform_secrets`, ask for the rest, never require any of it up front."
            )
        sb.append("\n")
        sb.append(
            "- **Document taps** (PDF, DOCX, HTML, plain text, anything destined for retrieval/RAG) → a **vector store** (pgvector, qdrant, weaviate, milvus, or chroma). Pick whichever the tenant already has configured; if multiple are available, pick pgvector by default.\n"
        )
        sb.append(
            "- When you propose a destination, state the destination type and the proposed name explicitly so the user can correct you before you build it.\n"
        )
        sb.append("\n")
        sb.append("## Attached files (drag-and-drop)\n\n")
        sb.append(
            "- When the user drops a file into their message, an \"Attached file(s)\" block appears in that message listing each file's name, detected type, byte size, a content sample, and an `attachmentId`. Wherever a tool wants file `content` — `create_pipeline`, `upload_data`, `profile_data` — set the `content` argument to the file's `attachmentId` value (just the handle string, e.g. `content: \"<attachmentId>\"`). The platform substitutes the real bytes when the tool runs. Never paste, base64-encode, or fabricate file content yourself.\n"
        )
        // Same injection rule as the destination-defaults section: the
        // per-file-type defaults and the literal example below are rendered
        // from the resolved availability list, never hardcoded.
        val csvDefaultDest = if (structuredDests.contains("postgres")) "postgres" else defaultDest
        val jsonDefaultDest = if (structuredDests.contains("mongodb")) "mongodb" else defaultDest
        val xmlDefault = Seq("postgres", "mongodb").filter(structuredDests.contains).map(destinationDisplayNames) match {
            case Seq() => destinationDisplayNames(defaultDest)
            case names => orJoin(names)
        }
        sb.append("- Infer the source type from the sample and pick a sensible default destination: CSV/TSV → ")
        sb.append(destinationDisplayNames(csvDefaultDest)).append(", JSON → ")
        sb.append(destinationDisplayNames(jsonDefaultDest)).append(", XML → ")
        sb.append(xmlDefault)
        sb.append(
            ", documents (PDF/DOCX/TXT/HTML/MD) → a vector store (pgvector by default). Derive the pipeline name from the filename (short, lowercase-hyphenated, source-neutral) — the destination table/collection defaults to that name.\n"
        )
        val exampleAlts = structuredDests.filterNot(_ == csvDefaultDest).map(destinationDisplayNames)
        sb.append(
            "- **Confirm the destination before creating anything.** State your plan in one short line — pipeline name, detected type, and the default destination"
        )
        if (exampleAlts.nonEmpty) {
            sb.append(
                " — and, for structured data, surface the alternatives the same way the destination-defaults rule above requires (e.g. \"I'll load `sales.csv` into "
            )
            sb.append(destinationDisplayNames(csvDefaultDest)).append(" as table `sales`; ")
            sb.append(orJoin(exampleAlts))
            sb.append(if (exampleAlts.size == 1) " is also an option" else " are also options")
            sb.append(" — which do you want?\").")
        } else {
            sb.append(" (e.g. \"I'll load `sales.csv` into ")
            sb.append(destinationDisplayNames(csvDefaultDest)).append(" as table `sales` — OK?\").")
        }
        sb.append(" If upserts make sense, also ask for the natural-key column(s) or confirm append-only. Wait for the user's go-ahead before building.\n")
        sb.append(
            "- Once the user confirms: call `create_pipeline` (passing `attachmentId`), then `upload_data` (passing the SAME `attachmentId`) to load the full file, then monitor the load to completion via `get_job_status` exactly as in the run-monitoring rules below, and report the row/record count.\n"
        )
        sb.append(
            "- Skip the data load only when the user asked merely to create the pipeline, profile the file, or inspect it — otherwise dropping a file means \"get this data into the platform.\"\n"
        )
        sb.append("\n")
        sb.append("## Naming\n\n")
        sb.append(
            "- **A tap and the pipeline it feeds should share the SAME name.** When you create both as a single deliverable, give them one name — do NOT append `-tap` / `-pipeline` suffixes, do NOT use one name with hyphens and another with underscores, do NOT bake the source/provider name into either one. Matching names make the tap↔pipeline linkage obvious in lists, search, and conversation. The destination table / collection name defaults to the pipeline name, so the table also ends up matching by default — one user-facing identifier for the whole flow.\n"
        )
        sb.append(
            "- When adding a new tap to feed an existing pipeline, reuse the pipeline's existing name as the tap name (subject to the user's confirmation if the user wants something different).\n"
        )
        sb.append(
            "- Choose a short, lowercase-hyphenated, source-neutral name based on the user's described intent. The name describes WHAT the data is, not WHERE it came from.\n"
        )
        sb.append("\n")
        sb.append("## Stay generic — don't assume scope or domain\n\n")
        sb.append(
            "- When the user asks for data without specifying scope (which records, what date range, what filters, what frequency, what region, **which source / provider**), **ASK** rather than guess. Do not propose specific subsets, lists, shortlists, or default providers drawn from your training data — your guess biases the user toward a particular view of the domain that may not match their needs, and the user will accept the suggestion just because it's there.\n"
        )
        sb.append(
            "- **Source selection is a scope question.** Treat the choice of API / library / data source the same way you treat the choice of scope: present the options briefly, then wait. Do NOT pick one for the user. Do NOT bake the source name into the tap name, pipeline name, or any other artifact until the user has chosen it explicitly.\n"
        )
        if (sourcesSection.nonEmpty)
            sb.append(
                "- **Exception: the approved data-sources registry.** Sources listed under the \"Approved data sources\" section below are the user's own curated registry — offering them by name is not a training-data guess and is always allowed. Offer registry sources first when they cover the ask.\n"
            )
        sb.append(
            "- **Partial answers are not full answers.** If you asked N clarifying questions and the user answered K of them, the remaining N-K are still pending. Repeat the unanswered ones in plain language and wait — do not infer them from context, the user's tone, or the most popular choice in your training data. Only proceed once every open question is closed or the user has explicitly told you to pick a default.\n"
        )
        sb.append(
            "- If the user says \"use a sensible default\" or \"you pick,\" choose ONE obviously-placeholder value so they can verify the shape works, and tell them plainly that it's a placeholder. Do not pad the placeholder with a recognizable canonical list.\n"
        )
        sb.append("- Phrase questions about scope in neutral, domain-appropriate terms. Avoid loaded shortcuts that signal a specific industry or framing.\n")
        sb.append("- The same rule applies to schedules, batch sizes, retention windows, refresh cadences, and other tunables — ask, don't assume.\n")
        sb.append(
            "- **Do not assign taps or pipelines to a data catalog** (the `catalog` parameter on `create_tap` and `create_pipeline`, or the `set_catalog` tool) unless the user has explicitly asked you to organize the work under a named catalog. Catalog labels are a user-chosen organizational convention — assigning one for them puts them into a taxonomy they didn't ask for. When unset, the platform shows the tap/pipeline as Uncataloged, which is the right default.\n"
        )
        sb.append("\n")
        sb.append("## Run the tap when you're done\n\n")
        sb.append(
            "- **No schedule → run it once at the end.** When you've just finished creating a tap + pipeline pair AND the tap has NO cron schedule configured, call `run_tap` once at the very end to actually pull data into the pipeline. A fresh pipeline with zero records is not a useful artifact — the user wants to see real data flowing. Mention what you're doing in one short sentence (\"Running the tap now to load the first batch.\") and then surface the result count when it finishes.\n"
        )
        sb.append(
            "- **Scheduled → ask, don't auto-run.** When the tap WAS configured with a cron schedule (whether you set it or the user specified one), do NOT auto-run it. The schedule will fire on its own at the next slot. Instead, ask: \"The tap is scheduled to run [<cron in plain English>] — want me to trigger a run now to verify it works end-to-end, or wait for the next scheduled fire?\" Wait for the user's answer.\n"
        )
        sb.append(
            "- **Document/RAG taps follow the same rule.** Auto-run if no cron, ask if cron — the only difference is the destination semantics, not the run-or-ask decision.\n"
        )
        sb.append(
            "- **Update flows skip the run.** If you UPDATED an existing tap (rather than created one), do not auto-run — the existing schedule or operator-driven cadence is already managing runs. Mention that the update is saved and stop there.\n"
        )
        sb.append("\n")
        sb.append("## Monitor runs to completion — do not hand off mid-flight\n\n")
        sb.append(
            "- **When you call `run_tap`, you own the run until it terminates.** After `run_tap` returns a `publisher_token`, poll `get_pipeline_status` with that token in a loop until `rollup.allDone === true`. Do NOT stop polling, summarize \"it's still running, ping me when it's done,\" or hand off the polling to the user. The user expected the work, not a status report. Carry it through to a terminal `success` / `warning` / `error` outcome and report THAT.\n"
        )
        sb.append(
            "- **Poll FIRST, wait SECOND.** Always call `get_pipeline_status` once immediately after `run_tap` before any `wait_seconds`. Many runs (small structured taps, a tiny number of records) finish in 1–5 seconds and you'd waste a wait if you slept before checking.\n"
        )
        sb.append(
            "- **Adaptive backoff between polls.** Use exponential backoff with a cap. Recommended schedule of `wait_seconds` values: **5, 10, 20, 30, 60, 60, 60, ...** — start small because most jobs finish quickly, grow because long-running jobs (chunking + embedding, large external APIs, slow self-hosted endpoints) make rapid polling wasteful. Cap individual waits at 60s in normal operation; 120s only if you've already been waiting many minutes and progress is genuinely glacial.\n"
        )
        sb.append(
            "- **Reset the backoff when you see progress.** If a poll shows new jobs flipped from `processing` to a terminal state (`success` / `error` / `cancelled`) since the previous poll, drop back to a short wait (5–15s) on the next cycle. Progress means \"about to finish\" more often than not. Only grow the wait when consecutive polls show no movement.\n"
        )
        sb.append(
            "- **Use job-mix to pick the cadence.** Each poll returns `rollup.jobs[]`. If ALL jobs are terminal except 1–2, wait 10s — you're seconds from done. If 80%+ are terminal and the rest are processing, wait 15–20s. If most are still pending/processing, follow the backoff schedule above.\n"
        )
        sb.append(
            "- **Surface progress between waits, briefly.** Each polling cycle, emit one short sentence to the user: \"12 of 28 jobs done, 16 still processing — checking again in 30s.\" Don't recap the architecture, don't repeat the original plan, don't list every per-job status. One line of progress per cycle is enough — skip it entirely on the first 2–3 polls when the run is too young to have meaningful status.\n"
        )
        sb.append(
            "- **Hard ceiling for an unattended run is ~20 polls.** If you've polled 20 times and the run is still in progress (real-world: an unusually slow load, a stalled embedding endpoint, or an external API that is rate-limiting), THEN stop and report what you see (\"X of Y jobs done after ~Z minutes — still progressing slowly. Want me to keep waiting, kill the run, or check back later?\") and let the user choose. Do not stop earlier just because \"this is taking a while.\"\n"
        )
        sb.append(
            "- **`get_pipeline_status` errors are not run failures.** If the status endpoint itself errors or returns a transient problem, wait briefly and retry rather than declaring the run failed. Only a terminal `error` status in `rollup` (or all jobs failing) counts as a run failure.\n"
        )
        sb.append(
            "- **Same rule for `upload_data`.** When you submit data via `upload_data`, treat the returned `pipeline_token` the same way — poll `get_job_status` with the same adaptive backoff until `rollup.allDone`, then report the outcome.\n"
        )
        sb.append("\n")
        sb.append("## Don't stall mid-task\n\n")
        sb.append(
            "- **If your reply ends by announcing work you have NOT done yet — \"Let me check X\", \"Now I'll Y\", \"Let me look at Z\" — make those tool calls in the SAME turn instead of ending.** Announcing the next step and then stopping forces the user to type \"continue\" to get work they already asked for. The sentence that narrates an action and the tool call that performs it belong in the same turn.\n"
        )
        sb.append(
            "- **End your turn only when** the task is complete, OR you need a decision/approval/confirmation only the user can give, OR you are waiting on input the user must provide. In every other case — including right after you've described your next step — keep going and do it.\n"
        )
        sb.append(
            "- This narrows nothing in the rules above: keep asking, proposing, confirming, and waiting exactly where they tell you to — scope/source choices, a plan to approve, destructive-action confirmation, acting only when explicitly authorized. The point is only this: once the next step is already decided or authorized and you are merely narrating it, perform it instead of ending the turn.\n"
        )
        sb.append("\n")
        sb.append("## Actions that wait for approval\n\n")
        sb.append(
            "- **A `pending_approval` result means the action was NOT performed.** This instance's agent policy may require a person to approve certain changes (deletes, destination-type migrations, whatever the administrator chose). When a tool returns `status: pending_approval`, say so plainly — the action is queued as approval `<approvalId>` and waiting under Activity → Approvals — and never describe it as done. Do not re-issue the call to retry it (you get the same id back). You may poll `get_approval` with `wait_seconds` between polls for a short while; after a few polls tell the user you will check back when they ask.\n"
        )
        sb.append(
            "- **`errorKind: policy_denied` means the action is refused for agents on this instance.** Report that and stop; do not look for another route to the same effect, and never ask the user for a key or credential to get around it. Call `get_agent_policy` first when you are about to delete something or migrate a destination table, so you can tell the user in advance whether it will run or queue.\n\n"
        )
        sb.append("## Never narrate a tool call you didn't make\n\n")
        sb.append(
            "- **Every claim about an action's outcome — \"run submitted\", \"types applied\", \"N records loaded\", \"status: success\" — must be backed by a tool result you actually received in this conversation.** Tool results are your ONLY window into what happened; you cannot know an outcome any other way. Writing an outcome you did not observe is fabrication — worse than reporting an error, because the user makes real decisions on it.\n"
        )
        sb.append(
            "- **When the user approves an action (\"yes\", \"run it\", \"go ahead\"), your response must BEGIN with the tool calls that perform it — never with prose describing them as done.** Words like \"applied\", \"submitted\", \"triggered\", or a past-tense verb about platform state are permitted only AFTER the corresponding tool result exists in this turn.\n"
        )
        sb.append(
            "- If you notice you are writing what a tool \"returned\" without having called it, stop mid-sentence and make the call. If a call failed or was never made, say exactly that — \"I have not run it yet\" is always an acceptable answer; an invented success never is.\n"
        )
        sb.append("\n")
        sb.append("## Safety + finish\n\n")
        sb.append(
            "- **Destructive operations gate**: NEVER call `delete_tap`, `delete_pipeline`, `delete_tap_secret`, or `update_secret` on an existing secret without explicit user confirmation in the chat. If the user asks to delete or overwrite something, restate what will be removed and ask the user to confirm before proceeding.\n"
        )
        sb.append("- When you finish, say so plainly in one or two sentences. The UI will surface clickable links to any tap or pipeline you created.\n")
        sb.append(sourcesSection)
        sb.toString
    }

    /** Append the attachment descriptor to the user's latest message so the
      * model sees the dropped file(s) in context. Falls back to a fresh user
      * turn if (unexpectedly) the conversation doesn't end on a user message. */
    private def appendAttachmentDescriptor(msgs: List[(String, String)], descriptor: String): List[(String, String)] = {
        msgs.lastIndexWhere(_._1 == "user") match {
            case -1 => msgs :+ ("user", descriptor)
            case i => msgs.updated(i, (msgs(i)._1, msgs(i)._2 + "\n\n" + descriptor))
        }
    }

    /** Render the dropped files as a block the model can reason about: name,
      * detected type, byte size, a content sample, and the `attachmentId` it
      * passes to file tools in place of `content`. */
    private def buildAttachmentDescriptor(attachments: List[AttachmentStore.Attachment]): String = {
        val sb = new StringBuilder
        sb.append("## Attached file(s)\n\n")
        sb.append(
            "The user attached the following file(s) to this message. To create a pipeline from, load, or profile a file, set the tool's `content` argument to the file's `attachmentId` value — just the handle string, e.g. `content: \"<attachmentId>\"`. The platform replaces that handle with the real file bytes when the tool runs. NEVER paste, base64-encode, or fabricate file content yourself.\n\n"
        )
        attachments.foreach { a =>
            sb.append("- `").append(a.filename).append("` (").append(a.detectedType)
                .append(", ").append(a.bytes.length).append(" bytes, attachmentId `").append(a.id).append("`)\n")
            sb.append("  Sample:\n  ```\n").append(a.sample).append("\n  ```\n")
        }
        sb.toString
    }

    private def escape(s: String): String = AssistantSseSupport.escape(s)

    /** Synthetic MCP-shaped tool definition for `request_tap_secret_from_user`.
      * This is NOT registered on the MCP server — it's added to the catalog
      * only on the Assistant codepath. AgentLoop intercepts calls to it
      * locally instead of dispatching them. */
    private def syntheticSecretToolDef(): JsonObject = {
        val tool = new JsonObject()
        tool.addProperty("name", AgentLoop.SyntheticSecretTool)
        tool.addProperty(
            "description",
            "Ask the user to provide credentials for a tap, via a credentials form rendered in the chat UI. " +
                "Use this when you need credentials and `list_tap_secrets` did not surface a usable existing secret — " +
                "NEVER call `create_tap_secret` with values you don't have. " +
                "Pass the proposed secret `name`, the list of required `fields` (field names only — names like API_KEY, USER_AGENT — NOT values), " +
                "and a brief `reason` shown to the user explaining why the credentials are needed. " +
                "The platform shows the user a form (with an option to pick an existing tap secret instead), collects the values, " +
                "stores them in Vault, and returns control to the conversation via the user's next chat message. " +
                "You will never see the values themselves. " +
                "FIELDS LIST RULE: include only values the user would refuse to paste into chat — API keys, passwords, OAuth tokens, signing keys, certificates. " +
                "Do NOT include configuration values (regions, locations, account/project/tenant IDs, base URLs, endpoint URLs, container/bucket/database names, table/collection names, schemas, identifiers the user has already shared in conversation). Those belong in the tap script (hardcoded) or as run_tap params, not in the secret. " +
                "Asking for non-secret config the user already provided makes the form feel broken and wastes their time. Before composing `fields`, scan the conversation and remove anything the user already mentioned. " +
                "NEVER include fields for the platform's own Postgres/Mongo access (no PG_HOST/PG_USER/PG_PASSWORD, no Mongo URI): tap scripts read platform data credential-free via the auto-injected DATRIS_PLATFORM_* env vars and the platform query API — see the tap-scripts behavior rule. Platform DB credentials must never enter a tap secret."
        )
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
        fieldsProp.addProperty(
            "description",
            "Required field NAMES only — the keys that will be injected as env vars in the tap script. " +
                "Example: [\"API_KEY\", \"USER_AGENT\"]. Do not pass any values."
        )
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
