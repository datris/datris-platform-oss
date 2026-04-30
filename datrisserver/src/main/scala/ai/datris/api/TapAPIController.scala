package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.{Gson, GsonBuilder, JsonParser}
import ai.datris.model.{TapConfig, DatrisEnvironment, DatrisException}
import ai.datris.util._
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.time.Instant
import scala.collection.JavaConverters._

object TapAPIController {
    // mode=test response caps `records` to this many rows. The UI's preview already
    // slices to 20 (tap-run.component.ts), so this matches without losing display
    // fidelity, and prevents large taps from bloating agent context.
    private val testRecordSampleSize = 20

    // Server-side debounce for /tap/run with mode=run. Prevents duplicate pushes when
    // an agent emits parallel tool_use blocks, when a UI button is rapidly clicked,
    // or when MCP transport retries fire concurrent requests. Keyed by tenant + tap
    // name; mode=test bypasses the debounce since previewing has no side-effects.
    private val runDebounceWindowMs: Long = 5000L
    private val recentRunStarts: java.util.concurrent.ConcurrentHashMap[String, java.lang.Long] =
        new java.util.concurrent.ConcurrentHashMap[String, java.lang.Long]()
}

@RestController
@RequestMapping(Array("/api/v1"))
class TapAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[TapAPIController])

    @GetMapping(path = Array("/taps"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTaps(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /taps called")
            APIKeyValidator.validate(apiKey)

            val taps = TapConfigIO.readAll(DatrisEnvironment.current.tapTableName).asJava
            val gson = new Gson
            val json = gson.toJson(taps)
            new ResponseEntity[String](json, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/tap"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTap(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
               @RequestParam name: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap called with name: " + name)
            APIKeyValidator.validate(apiKey)

            val config = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)
            if (config == null)
                throw new DatrisException("Tap: " + name + " not found")
            val gson = new Gson
            // Include script content from MinIO. Track "missing" separately from
            // "never generated" so the UI can tell the user the script was deleted
            // vs. not yet created.
            var scriptMissing = false
            val scriptContent: String = if (config.scriptPath != null && config.scriptPath.nonEmpty) {
                try {
                    val env = DatrisEnvironment.current.environment
                    ObjectStoreUtil.readBucketObject(env + "-config", config.scriptPath) match {
                        case Some(content) => content
                        case None =>
                            scriptMissing = true
                            logger.warn("Tap '" + name + "' has scriptPath=" + config.scriptPath + " but object is missing from storage")
                            null
                    }
                } catch {
                    case e: Exception =>
                        scriptMissing = true
                        logger.warn("Tap '" + name + "' script read failed: " + e.getMessage)
                        null
                }
            } else null
            val response = gson.fromJson(gson.toJson(config), classOf[java.util.Map[String, Any]])
            response.put("script", scriptContent)
            if (scriptMissing) response.put("scriptMissing", java.lang.Boolean.TRUE)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/tap/logs"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapLogs(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                   @RequestParam name: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap/logs called for tap: " + name)
            APIKeyValidator.validate(apiKey)

            val allKeys = NoSQLDbUtil.getItemsKeysByKeyName(DatrisEnvironment.current.tapLogTableName, "key")
            val tapKeys = allKeys.filter(_.startsWith(name + "|")).sorted.reverse.take(50)

            val gson = new Gson
            val logs = tapKeys.flatMap(key => {
                val json = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.tapLogTableName, "key", key, "value").orNull
                if (json != null) {
                    Some(gson.fromJson(json, classOf[ai.datris.model.TapRunLog]))
                } else None
            })

            new ResponseEntity[String](gson.toJson(logs.asJava), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def createOrUpdateTap(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                          @RequestBody tapConfig: TapConfig): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap called with name: " + tapConfig.name)
            APIKeyValidator.validate(apiKey)

            if (tapConfig.name == null || tapConfig.name.isEmpty)
                throw new DatrisException("Tap name is required")

            // Document-tap pipeline compatibility guard: document taps push raw bytes,
            // so the target pipeline must be unstructured + vector-store or the bytes
            // will crash the destination loader (e.g. PDF into MongoDBLoader).
            if (tapConfig.tapType == "document" && tapConfig.targetPipeline != null && tapConfig.targetPipeline.nonEmpty) {
                val pipeline = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, tapConfig.targetPipeline)
                DocumentTapValidator.incompatibilityReason(pipeline) match {
                    case Some(reason) =>
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                            "{\"error\": \"Document tap '" + tapConfig.name + "' cannot target pipeline '" +
                                tapConfig.targetPipeline + "': " + reason.replace("\"", "'") + "\"}")
                    case None => // compatible
                }
            }

            // Script-existence guard: a tap save with a scriptPath that points at
            // a missing file in MinIO would silently succeed here and then fail at
            // run_tap with "scriptMissing". Reject the save with a clear error so
            // the UI can prompt the user to push the script before retrying.
            if (tapConfig.scriptPath != null && tapConfig.scriptPath.nonEmpty) {
                val bucket = DatrisEnvironment.current.environment + "-config"
                val exists = try {
                    ObjectStoreUtil.readBucketObject(bucket, tapConfig.scriptPath).isDefined
                } catch { case _: Exception => false }
                if (!exists) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](
                        "{\"error\": \"Tap script not found in object storage at '" + tapConfig.scriptPath +
                        "'. The script must be uploaded (via the tap wizard's script-store endpoints) before saving the tap config.\"}")
                }
            }

            // Set timestamps
            val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapConfig.name)
            val sdf2 = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
            sdf2.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
            val now = sdf2.format(new java.util.Date())
            val configToSave = if (existing != null)
                tapConfig.copy(createdAt = existing.createdAt, updatedAt = now)
            else
                tapConfig.copy(createdAt = now, updatedAt = now)

            TapConfigIO.write(configToSave)

            val gson = new Gson
            new ResponseEntity[String](gson.toJson(configToSave), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/tap"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteTap(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                  @RequestParam name: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /tap called with name: " + name)
            APIKeyValidator.validate(apiKey)

            // Delete script from MinIO if it exists
            val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)
            if (existing != null) {
                TapScriptGenerator.deleteScript(existing.scriptPath)
            }

            // Document taps: also clean up staged files and ledger entries
            if (existing != null && existing.tapType == "document") {
                try {
                    val ledgerTable = DatrisEnvironment.current.tapLedgerTableName
                    val bucket = DatrisEnvironment.current.environment + "-config"
                    val entries = TapDocumentLedgerIO.readByTap(ledgerTable, name)
                    entries.foreach { e =>
                        if (e.stagedPath != null && e.stagedPath.nonEmpty) {
                            try { ObjectStoreUtil.deleteBucketObject(bucket, e.stagedPath) }
                            catch { case ex: Exception => logger.warn("Failed to delete staged doc " + e.stagedPath + ": " + ex.getMessage) }
                        }
                    }
                    TapDocumentLedgerIO.deleteByTap(ledgerTable, name)
                } catch {
                    case ex: Exception => logger.warn("Tap ledger cleanup failed for " + name + ": " + ex.getMessage)
                }
            }

            // Run history: every TapRunner.writeRunLog inserts a row keyed by
            // "<tapName>|<runTime>" into {env}-tap-log. Without this cleanup, deleting
            // and recreating a tap with the same name would silently surface the old
            // tap's run history under the new tap.
            try {
                val tapLogTable = DatrisEnvironment.current.tapLogTableName
                val prefix = name + "|"
                val allKeys = NoSQLDbUtil.getItemsKeysByKeyName(tapLogTable, "key")
                allKeys.filter(_.startsWith(prefix)).foreach { k =>
                    NoSQLDbUtil.deleteItemJSON(tapLogTable, "key", k)
                }
            } catch {
                case ex: Exception => logger.warn("Tap run-log cleanup failed for " + name + ": " + ex.getMessage)
            }

            TapConfigIO.delete(DatrisEnvironment.current.tapTableName, name)
            new ResponseEntity[String]("{\"message\": \"Tap deleted: " + name + "\"}", HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/script"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def storeScript(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                    @RequestBody body: java.util.Map[String, String]): ResponseEntity[String] = {
        try {
            val tapName = body.get("tapName")
            val script = body.get("script")
            val oldScriptPath = body.get("oldScriptPath")
            logger.info("API endpoint POST /tap/script called, tapName: " + tapName)
            APIKeyValidator.validate(apiKey)

            if (tapName == null || tapName.isEmpty)
                throw new DatrisException("tapName is required")
            if (script == null || script.isEmpty)
                throw new DatrisException("script is required")

            val scriptPath = TapScriptGenerator.storeScript(tapName, script, oldScriptPath)

            // Update scriptPath if tap already exists
            val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)
            if (existing != null) {
                TapConfigIO.write(existing.copy(scriptPath = scriptPath))
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, String]()
            response.put("scriptPath", scriptPath)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/cron"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateCron(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                     @RequestBody body: java.util.Map[String, String]): ResponseEntity[String] = {
        try {
            val description = body.get("description")
            logger.info("API endpoint POST /tap/cron called: " + description)
            APIKeyValidator.validate(apiKey)

            if (description == null || description.isEmpty)
                throw new DatrisException("Description is required")

            val prompt =
                s"""Convert this schedule description to a Quartz CRON expression (6 fields: second minute hour day-of-month month day-of-week).
                   |Return ONLY the CRON expression string, nothing else. No explanation, no quotes, no markdown.
                   |
                   |Examples:
                   |  "every hour" → 0 0 * * * ?
                   |  "every weekday at 4pm" → 0 0 16 ? * MON-FRI
                   |  "every 15 minutes" → 0 */15 * * * ?
                   |  "daily at midnight" → 0 0 0 * * ?
                   |  "twice a day at 8am and 6pm" → 0 0 8,18 * * ?
                   |
                   |Schedule: "$description"""".stripMargin

            val responseText = AIUtil.callAI(prompt)
            // LLMs occasionally wrap the cron in quotes, backticks, brackets, or
            // markdown fences. Strip them all before handing the value back.
            val cron = AIUtil.extractText(responseText).trim
                .replaceAll("(?s)^```(?:\\w+)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .replaceAll("[\"'`\\[\\]]", "")
                .trim

            val gson = new Gson
            val response = new java.util.HashMap[String, String]()
            response.put("cronExpression", cron)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/brainstorm"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def brainstorm(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                   @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap/brainstorm called")
            APIKeyValidator.validate(apiKey)

            val messagesRaw = body.get("messages").asInstanceOf[java.util.List[java.util.Map[String, String]]]
            val currentDescription = Option(body.get("currentDescription")).map(_.toString).getOrElse("")
            val tapType = Option(body.get("tapType")).map(_.toString).getOrElse("structured")

            if (messagesRaw == null || messagesRaw.isEmpty)
                throw new DatrisException("messages array is required")

            val messages = messagesRaw.asScala.map { m =>
                (m.get("role"), m.get("content"))
            }.toSeq

            val documentSystemPrompt =
                """You are a data engineering assistant helping a user describe a DOCUMENT TAP — a Python script whose only job is to DISCOVER source documents (PDFs, Word docs, HTML pages, contracts, manuals, etc.) and hand the raw file bytes to the platform.
                  |
                  |Scope of the tap — critical:
                  |- The tap DISCOVERS and DOWNLOADS documents. That's it.
                  |- The tap does NOT chunk documents, NOT generate embeddings, NOT pick an embedding model, NOT choose a vector store, NOT create tables, NOT run SQL. All of that is handled by the downstream PIPELINE the tap feeds into.
                  |- Do NOT ask the user about chunk size, chunk overlap, embedding model, vector store type (Qdrant / pgvector / Weaviate / Milvus / Chroma), destination table, or collection name. Those are pipeline configuration — the user sets them up when they create or attach the pipeline, not here.
                  |- If the user volunteers details about chunking/embedding/vector store, acknowledge briefly that those are pipeline-level decisions and redirect the conversation to the tap's job: where the documents live and how to list + fetch them.
                  |
                  |What you DO ask about:
                  |1. WHERE the documents live (a web URL listing, a specific site, an S3 bucket, a SharePoint site, an RSS feed, a GitHub repo, etc.).
                  |2. HOW to enumerate them (does the source have an index page, a sitemap, an API that returns a list, a folder listing?).
                  |3. WHICH documents to include (file type filters, folder filters, naming patterns). Do NOT ask about date ranges or "since date" — the tap ledger already dedupes by content hash, so each file is processed once regardless of when it was added.
                  |4. AUTHENTICATION needs (API keys, cookies, tokens) — tell the user the env var names and mention they should configure a tap secret.
                  |
                  |Know when to stop asking. Once you have (a) the source location, (b) auth handled or confirmed public, and (c) a scope answer (even a broad one like "all files"), finalize the instruction. Don't keep drilling for optional filters the user hasn't asked for — broad is fine, the ledger handles dedup.
                  |
                  |Local paths: if the user gives a local filesystem path, remind them that the tap runs inside the Datris container and that path must be mounted in — otherwise suggest the documents be served over HTTP/S3/etc. Do NOT encourage fallback-search-the-filesystem logic.
                  |
                  |Credentials — same as structured taps:
                  |- The platform auto-injects DATRIS_POSTGRES_DATABASE, DATRIS_MONGODB_DATABASE, DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT. The user does NOT need to configure those.
                  |- Any other credential (source API key, SharePoint token, etc.) needs a tap secret with specific env var names. Tell the user which ones and suggest they create/select a tap secret in the Credentials section.
                  |
                  |Ask ONE focused clarifying question at a time. Be concise — 1-2 sentences per turn. When you have enough information, tell the user the instruction is ready.
                  |
                  |After EACH user message, return JSON with three fields:
                  |{
                  |  "reply": "your next message or question",
                  |  "description": "a plain-English statement of what documents the user wants and where they come from — for the user to read, NOT for code generation. No URLs, no code, no implementation detail. Never mention chunking/embeddings/vector store — those belong on the pipeline.",
                  |  "suggestedEnvVars": ["ENV_VAR_NAME_1"]
                  |}
                  |
                  |The description should always reflect everything known so far. Never leave description empty after the first user message.
                  |
                  |Write the description as plain English capturing intent. NEVER include API URLs, HTTP verbs, library names, file paths, or env var names in the description. The script generator already knows HOW to fetch; your job is WHAT.
                  |
                  |suggestedEnvVars lists env var names the script will need that are NOT auto-injected by Datris. For open/public sources return []. Always return the field, even when empty.
                  |
                  |Return ONLY the JSON object, no markdown fences, no commentary.""".stripMargin

            val structuredSystemPrompt =
                """You are a data engineering assistant helping users describe a "tap" — a Python script that fetches data from an external source and returns a list of records.
                  |
                  |Your job is to converse with the user to understand:
                  |1. What data they want
                  |2. The source (external API, website, or the Datris platform itself)
                  |3. Any filters, time range, or specific fields
                  |4. Authentication needs
                  |
                  |IMPORTANT — The Datris platform is the host for this tap. It exposes its own data via REST endpoints that the generated script can call:
                  |- Metadata discovery: /api/v1/metadata/postgres/{databases,schemas,tables,columns} and /api/v1/metadata/mongodb/{databases,collections}
                  |- Queries: POST /api/v1/query/postgres with {sql, database} and POST /api/v1/query/mongodb with {query, database, collection}
                  |- The script can read from existing Datris tables/collections (e.g., to get a list of tickers, IDs, parameters) and use those values to drive an external API fetch.
                  |
                  |So if a user says "get the tickers from the consumer_discretionary_earnings table on Datris", confidently confirm — the script generator knows how to query that table. Do NOT tell the user it's TBD or unknown.
                  |
                  |DO NOT ask the user about things the platform can discover automatically:
                  |- Database name (the postgres database is available as DATRIS_POSTGRES_DATABASE and the mongo database is available as DATRIS_MONGODB_DATABASE — both auto-injected)
                  |- Schema name (default to "public" for postgres, or have the script call /api/v1/metadata/postgres/schemas to find it)
                  |- Whether a table exists or what columns it has (the script will call /api/v1/metadata/postgres/columns at runtime to discover the schema)
                  |- Exact column types or names — the script can introspect them
                  |- The exact table or collection name when the user doesn't name one (the script can list tables via /api/v1/metadata/postgres/tables and pick the one with a matching column like 'ticker' or 'symbol')
                  |
                  |When the user says "the data is on Datris" but doesn't name the table, do NOT ask for it and do NOT ask "should the script look for a column named X?" — just confidently state that the script will discover the right table at runtime by listing tables and matching on a likely column name, write that into the description draft, and move on to the next missing piece (time range, filters, output fields, external API choice). Asking the user to confirm a discovery strategy is still asking — don't do it.
                  |
                  |Only ask the user for things the platform CANNOT discover: which external API to use, time ranges, filters, business logic, or credentials. When the user mentions a Datris table by name, just confirm and write the instruction — the generated script will handle metadata discovery on its own.
                  |
                  |CREDENTIALS — Many external data sources need API keys, tokens, or other secrets. The Datris tap runner injects environment variables into the script at runtime. Common ones include:
                  |- DATRIS_POSTGRES_DATABASE, DATRIS_MONGODB_DATABASE, DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT (auto-injected for accessing the Datris platform itself)
                  |- Custom API keys and tokens (must be configured by the user in a "tap secret")
                  |
                  |Whenever the data source needs authentication, you MUST:
                  |1. Tell the user which environment variables the script will need (suggest specific names)
                  |2. Mention that they should create or select a "tap secret" containing those keys in the Credentials section below the chat
                  |3. Include the env var names in the instruction draft so the script generator references them via os.environ.get()
                  |
                  |For free, no-auth sources, no credentials are needed — say so explicitly.
                  |
                  |Ask ONE focused clarifying question at a time. Suggest specific data sources when relevant. Be concise — 1-2 sentences per turn. When you have enough information, tell the user the instruction is ready and they can proceed.
                  |
                  |After EACH user message, return JSON with three fields:
                  |{
                  |  "reply": "your next message or question",
                  |  "description": "a plain-English statement of what data the user wants and where it comes from — written for the user to read, NOT for code generation. No URLs, no API paths, no Python method names, no implementation detail.",
                  |  "suggestedEnvVars": ["ENV_VAR_NAME_1", "ENV_VAR_NAME_2"]
                  |}
                  |
                  |The description should always reflect everything known so far. If the user hasn't provided enough info yet, the description can be partial. Never leave description empty after the first user message — always provide your best guess.
                  |
                  |Write the description as plain English, the way you'd explain the task to a colleague. NEVER include:
                  |- API URLs or paths (e.g., /api/v1/metadata/postgres/tables)
                  |- HTTP verbs (POST, GET)
                  |- Python library method names
                  |- File paths or environment variable names
                  |The script generator already knows how to call Datris APIs and which Python libraries to use — your job is to capture intent, not implementation. When the user references a Datris table/collection, name it in plain English. When the table is unknown, say so plainly.
                  |
                  |suggestedEnvVars should list any environment variable names the script will need that are NOT auto-injected by Datris (so do NOT include DATRIS_POSTGRES_DATABASE, DATRIS_MONGODB_DATABASE, DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT). For free sources with no auth, return an empty array []. Always return the field, even when empty.
                  |
                  |Return ONLY the JSON object, no markdown fences, no commentary.""".stripMargin

            val baseSystemPrompt = if (tapType == "document") documentSystemPrompt else structuredSystemPrompt

            // Prepend a system-style note about current description if present
            val messagesWithContext: Seq[(String, String)] = if (currentDescription.nonEmpty) {
                val contextNote = (messages.head._1, "[Current instruction draft: " + currentDescription + "]\n\n" + messages.head._2)
                contextNote +: messages.tail
            } else {
                messages
            }

            val scanText = currentDescription + "\n" + messages.map(_._2).mkString("\n")
            val systemPrompt = TapPromptInjector.augment(baseSystemPrompt, scanText)
            val injectedPrompts = TapPromptInjector.matchKeys(scanText)

            val responseText = AIUtil.callAIWithMessages(systemPrompt, messagesWithContext)
            val rawText = AIUtil.extractText(responseText).trim

            // Strip markdown code fences if present
            var cleaned = rawText
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "")
                .trim

            // Extract first JSON object substring if there's surrounding text
            val firstBrace = cleaned.indexOf('{')
            val lastBrace = cleaned.lastIndexOf('}')
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                cleaned = cleaned.substring(firstBrace, lastBrace + 1)
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()

            try {
                val parsed = gson.fromJson(cleaned, classOf[java.util.Map[String, Any]])
                response.put("reply", Option(parsed.get("reply")).map(_.toString).getOrElse(""))
                response.put("description", Option(parsed.get("description")).map(_.toString).getOrElse(currentDescription))
                val envVars = parsed.get("suggestedEnvVars") match {
                    case list: java.util.List[_] => list
                    case _ => new java.util.ArrayList[String]()
                }
                response.put("suggestedEnvVars", envVars)
            } catch {
                case _: Exception =>
                    // LLM didn't return JSON — use raw text as reply, keep current description
                    logger.warn("Brainstorm AI did not return valid JSON, using raw text as reply")
                    response.put("reply", rawText)
                    response.put("description", currentDescription)
                    response.put("suggestedEnvVars", new java.util.ArrayList[String]())
            }
            response.put("injectedPrompts", injectedPrompts)

            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/generate"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def generateScript(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                       @RequestBody body: java.util.Map[String, String]): ResponseEntity[String] = {
        try {
            val description = body.get("description")
            val tapName = Option(body.get("tapName")).getOrElse("tap-" + System.currentTimeMillis())
            val oldScriptPath = body.get("oldScriptPath")
            val secretName = body.get("secretName")
            val tapType = Option(body.get("tapType")).getOrElse("structured")
            logger.info("API endpoint POST /tap/generate called, tapName: " + tapName + ", tapType: " + tapType)
            APIKeyValidator.validate(apiKey)

            if (description == null || description.isEmpty)
                throw new DatrisException("Description is required")

            val result = TapScriptGenerator.generate(description, tapName, oldScriptPath, secretName, tapType)

            // Update scriptPath in MongoDB if tap already exists
            val existing = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)
            if (existing != null) {
                TapConfigIO.write(existing.copy(scriptPath = result.scriptPath))
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", result.script)
            response.put("packages", result.packages)
            response.put("scriptPath", result.scriptPath)
            response.put("injectedPrompts", result.injectedPrompts)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/fix"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def fixScript(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                  @RequestBody body: java.util.Map[String, String]): ResponseEntity[String] = {
        try {
            val tapName = body.get("tapName")
            val script = body.get("script")
            val diagnosis = body.get("diagnosis")
            val logs = Option(body.get("logs")).getOrElse("")
            val error = Option(body.get("error")).getOrElse("")
            val oldScriptPath = body.get("oldScriptPath")
            val priorIterationsJson = Option(body.get("priorIterations")).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            logger.info("API endpoint POST /tap/fix called, tapName: " + tapName +
                ", priorIterations: " + priorIterations.size)
            APIKeyValidator.validate(apiKey)

            if (script == null || script.isEmpty)
                throw new DatrisException("Script is required")
            if (diagnosis == null || diagnosis.isEmpty)
                throw new DatrisException("Diagnosis is required")

            val systemPrompt =
                """You are a code generator. You will be given a Python script that has a bug, along with the error output and a diagnosis.
                  |Fix the script and return a JSON object with two fields:
                  |- "script": the complete fixed Python 3 script (must define a `fetch()` function)
                  |- "packages": a list of the EXACT pip install package names needed beyond the pre-installed set
                  |  (requests, beautifulsoup4, pandas, lxml, feedparser, boto3, pyyaml, openpyxl,
                  |  python-dateutil, pytz, google-cloud-storage, azure-storage-blob). Use an empty list if none needed.
                  |  Package names must be the pip install names, not Python import names (they are often different).
                  |
                  |IMPORTANT fix strategies:
                  |- If the error says a method or attribute does not exist (AttributeError, 'has no attribute'),
                  |  the library's API may have changed between versions. If you are unsure of the correct method name,
                  |  fall back to using the `requests` library to call the API directly via HTTP instead of using the SDK.
                  |  Direct HTTP calls are more reliable than SDK methods that may be version-dependent.
                  |- If pip install failed, verify the correct PyPI package name (pip install name != Python import name).
                  |
                  |Return ONLY the JSON object, no markdown fences or commentary.""".stripMargin

            // Check if error suggests outdated package knowledge
            val combinedError = error + " " + diagnosis + " " + logs
            val needsPackageLookup = Seq("has no attribute", "AttributeError", "ModuleNotFoundError",
                "No module named", "No matching distribution", "ImportError").exists(combinedError.contains)

            val packageContext = if (needsPackageLookup) {
                // Extract package names from import statements in the script
                val importPattern = """(?:import|from)\s+(\w+)""".r
                val imports = importPattern.findAllMatchIn(script).map(_.group(1)).toSet
                val standardLibs = Set("os", "sys", "json", "datetime", "io", "re", "math", "time", "urllib", "collections", "itertools", "functools")
                val thirdPartyImports = imports -- standardLibs

                val contextParts = thirdPartyImports.take(3).flatMap { pkg =>
                    try {
                        logger.info("Fetching PyPI info for package: " + pkg)
                        val client = org.apache.http.impl.client.HttpClients.createDefault()
                        try {
                            val pypiReq = new org.apache.http.client.methods.HttpGet("https://pypi.org/pypi/" + pkg + "/json")
                            pypiReq.setHeader("User-Agent", "datris-platform/1.0")
                            val pypiResp = client.execute(pypiReq)
                            val pypiBody = org.apache.http.util.EntityUtils.toString(pypiResp.getEntity)

                            if (pypiResp.getStatusLine.getStatusCode == 200) {
                                val gson3 = new Gson
                                val pypiData = gson3.fromJson(pypiBody, classOf[java.util.Map[String, Any]])
                                val info = pypiData.get("info").asInstanceOf[java.util.Map[String, Any]]
                                val version = Option(info.get("version")).map(_.toString).getOrElse("unknown")
                                val summary = Option(info.get("summary")).map(_.toString).getOrElse("")
                                val description = Option(info.get("description")).map(_.toString).getOrElse("")
                                val homePage = Option(info.get("home_page")).map(_.toString).getOrElse("")
                                val pipName = Option(info.get("name")).map(_.toString).getOrElse(pkg)

                                // Try to fetch docs page for more detail
                                val docsUrl = {
                                    val projectUrls = Option(info.get("project_urls")).map(_.asInstanceOf[java.util.Map[String, Any]]).getOrElse(new java.util.HashMap())
                                    Option(projectUrls.get("Documentation")).map(_.toString)
                                        .orElse(Option(projectUrls.get("Docs")).map(_.toString))
                                        .orElse(Option(projectUrls.get("Homepage")).map(_.toString))
                                        .orElse(if (homePage.nonEmpty) Some(homePage) else None)
                                }

                                val docsExcerpt = docsUrl.flatMap { url =>
                                    try {
                                        val docsReq = new org.apache.http.client.methods.HttpGet(url)
                                        docsReq.setHeader("User-Agent", "datris-platform/1.0")
                                        val docsResp = client.execute(docsReq)
                                        if (docsResp.getStatusLine.getStatusCode == 200) {
                                            val html = org.apache.http.util.EntityUtils.toString(docsResp.getEntity)
                                            // Strip HTML tags and truncate
                                            val text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim
                                            Some(text.take(2000))
                                        } else None
                                    } catch { case _: Exception => None }
                                }.getOrElse("")

                                // Use description (README) truncated if no docs page
                                val contextText = if (docsExcerpt.nonEmpty) docsExcerpt else description.take(2000)

                                Some(s"PACKAGE INFO: $pipName v$version (pip install $pipName)\n$summary\n$contextText")
                            } else None
                        } finally {
                            client.close()
                        }
                    } catch {
                        case e: Exception =>
                            logger.warn("Failed to fetch PyPI info for " + pkg + ": " + e.getMessage)
                            None
                    }
                }
                if (contextParts.nonEmpty) contextParts.mkString("\n\n") + "\n\n" else ""
            } else ""

            val userPrompt =
                s"""${packageContext}Current script:
                   |$script
                   |
                   |Script output/logs:
                   |$logs
                   |
                   |${if (error.nonEmpty) "Error: " + error else ""}
                   |
                   |Diagnosis: $diagnosis
                   |
                   |Fix the script based on the diagnosis. Return the complete corrected script.""".stripMargin

            val codegenCfg = DatrisEnvironment.aiConfigForCodegen
            val historyBlock = IterationHistoryPromptBuilder.build(priorIterations)
            val augmentedSystemPrompt = historyBlock + TapPromptInjector.augment(systemPrompt, diagnosis + "\n" + error + "\n" + script)
            val responseText = AIUtil.callAIWithSystem(augmentedSystemPrompt, userPrompt, codegenCfg)
            val extracted = AIUtil.extractText(responseText, codegenCfg)
            val cleaned = cleanAIResponse(extracted)

            // Extract JSON from the response — AI may include text before/after the JSON
            val jsonStr = {
                val start = cleaned.indexOf('{')
                val end = cleaned.lastIndexOf('}')
                if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
            }

            // Try parsing as JSON first; if that fails, treat the whole response as a script
            val (fixedScript, packages) = try {
                val gson2 = new Gson
                val result = gson2.fromJson(jsonStr, classOf[java.util.Map[String, Any]])
                val s = Option(result.get("script")).map(_.toString).getOrElse(cleaned)
                val p: java.util.List[String] = {
                    val raw = result.get("packages")
                    if (raw == null) new java.util.ArrayList[String]()
                    else raw match {
                        case list: java.util.List[_] =>
                            val stringList = new java.util.ArrayList[String]()
                            val it = list.iterator()
                            while (it.hasNext) stringList.add(it.next().toString)
                            stringList
                        case _ => new java.util.ArrayList[String]()
                    }
                }
                (s, p)
            } catch {
                case _: Exception =>
                    // AI returned raw script instead of JSON — use it directly
                    logger.info("AI fix response was not JSON, treating as raw script")
                    (cleaned, new java.util.ArrayList[String]())
            }

            // Store fixed script in MinIO
            val scriptPath = TapScriptGenerator.storeScript(Option(tapName).getOrElse("tap"), fixedScript, oldScriptPath)

            // Update scriptPath in MongoDB if tap already exists
            val existingTap = TapConfigIO.read(DatrisEnvironment.current.tapTableName, Option(tapName).getOrElse(""))
            if (existingTap != null) {
                TapConfigIO.write(existingTap.copy(scriptPath = scriptPath))
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", fixedScript)
            response.put("packages", packages)
            response.put("scriptPath", scriptPath)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/review"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def reviewScript(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                     @RequestBody body: java.util.Map[String, Object]): ResponseEntity[String] = {
        try {
            val tapName = Option(body.get("tapName")).map(_.toString).getOrElse("tap")
            val script = Option(body.get("script")).map(_.toString).getOrElse("")
            val recordCount = Option(body.get("recordCount")).map(_.toString.toDouble.toInt).getOrElse(0)
            val durationMs = Option(body.get("durationMs")).map(_.toString.toDouble.toLong).getOrElse(0L)
            val logs = Option(body.get("logs")).map(_.toString).getOrElse("")
            val oldScriptPath = Option(body.get("oldScriptPath")).map(_.toString).orNull
            val priorIterationsJson = Option(body.get("priorIterations")).map(_.toString).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            logger.info(s"API endpoint POST /tap/review called, tapName: $tapName, recordCount: $recordCount, " +
                s"priorIterations: ${priorIterations.size}")
            APIKeyValidator.validate(apiKey)

            if (script.isEmpty)
                throw new DatrisException("Script is required")

            val result = TapScriptReviewer.review(tapName, script, recordCount, durationMs, logs, oldScriptPath, priorIterations)

            // Persist the new scriptPath onto the TapConfig if the tap already exists.
            if (result.rewritten) {
                val existingTap = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)
                if (existingTap != null && result.scriptPath != null && result.scriptPath != oldScriptPath) {
                    TapConfigIO.write(existingTap.copy(scriptPath = result.scriptPath))
                }
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", result.script)
            response.put("packages", result.packages)
            response.put("scriptPath", result.scriptPath)
            response.put("changes", result.changes)
            response.put("rewritten", Boolean.box(result.rewritten))
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/optimize"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def optimizeScript(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                       @RequestBody body: java.util.Map[String, Object]): ResponseEntity[String] = {
        try {
            val tapName = Option(body.get("tapName")).map(_.toString).getOrElse("tap")
            val script = Option(body.get("script")).map(_.toString).getOrElse("")
            val recordCount = Option(body.get("recordCount")).map(_.toString.toDouble.toInt).getOrElse(0)
            val durationMs = Option(body.get("durationMs")).map(_.toString.toDouble.toLong).getOrElse(0L)
            val logs = Option(body.get("logs")).map(_.toString).getOrElse("")
            val oldScriptPath = Option(body.get("oldScriptPath")).map(_.toString).orNull
            val priorIterationsJson = Option(body.get("priorIterations")).map(_.toString).getOrElse("[]")
            val priorIterations = IterationHistoryPromptBuilder.parseFromJson(priorIterationsJson)
            logger.info(s"API endpoint POST /tap/optimize called, tapName: $tapName, recordCount: $recordCount, " +
                s"durationMs: $durationMs, priorIterations: ${priorIterations.size}")
            APIKeyValidator.validate(apiKey)

            if (script.isEmpty)
                throw new DatrisException("Script is required")

            val opt = TapScriptOptimizer.optimize(tapName, script, recordCount, durationMs, logs, oldScriptPath, priorIterations)

            val existingTap = TapConfigIO.read(DatrisEnvironment.current.tapTableName, tapName)
            if (existingTap != null && opt.scriptPath != null && opt.scriptPath != oldScriptPath) {
                TapConfigIO.write(existingTap.copy(scriptPath = opt.scriptPath))
            }

            val gson = new Gson
            val response = new java.util.HashMap[String, Any]()
            response.put("script", opt.script)
            response.put("packages", opt.packages)
            response.put("scriptPath", opt.scriptPath)
            response.put("changes", opt.changes)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    private def cleanAIResponse(response: String): String = {
        var cleaned = response.trim
        if (cleaned.startsWith("```json")) cleaned = cleaned.stripPrefix("```json").trim
        else if (cleaned.startsWith("```")) cleaned = cleaned.stripPrefix("```").trim
        if (cleaned.endsWith("```")) cleaned = cleaned.stripSuffix("```").trim
        cleaned
    }

    @PostMapping(path = Array("/tap/test"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def testTap(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                @RequestParam(required = false) testLimit: Integer,
                @RequestBody tapConfig: TapConfig): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap/test called for tap: " + tapConfig.name + (if (testLimit != null) s" (testLimit=$testLimit)" else ""))
            APIKeyValidator.validate(apiKey)

            if (tapConfig.scriptPath == null || tapConfig.scriptPath.isEmpty)
                throw new DatrisException("Script path is required for testing")

            // Run in test mode (no push to pipeline). testLimit > 0 tells the
            // runner to inject DATRIS_TAP_TEST_LIMIT into the script env so a
            // well-written tap script caps its source reads. Cron/manual runs
            // never set this.
            val testLimitInt: Int = if (testLimit != null && testLimit.intValue() > 0) testLimit.intValue() else 0
            val testStartMs = System.currentTimeMillis()
            val result = TapRunner.run(tapConfig, mode = "test", testLimit = testLimitInt)
            val testDurationMs = System.currentTimeMillis() - testStartMs
            val gson = new Gson
            val recordsJson = if (result.records != null) JsonParser.parseString(result.records) else null
            val response = new java.util.HashMap[String, Any]()
            response.put("records", recordsJson)
            response.put("recordCount", Integer.valueOf(result.recordCount))
            response.put("error", result.error)
            response.put("logs", result.logs)
            response.put("dataType", result.dataType)
            response.put("columns", result.columns)
            response.put("durationMs", java.lang.Long.valueOf(testDurationMs))

            // AI explanation if there's an error, 0 records, or logs contain notable indicators.
            // "deprecat" and "warning" catch cases where the script "succeeded" but the runtime
            // output is trying to tell us something (e.g. DeprecationWarning, urllib3
            // warnings, pandas FutureWarning) — the user shouldn't have to manually ask for a
            // review when the logs are already shouting.
            val logsHaveIssues = result.logs != null && result.logs.nonEmpty && {
                val lower = result.logs.toLowerCase
                lower.contains("error") || lower.contains("exception") ||
                lower.contains("failed") || lower.contains("forbidden") ||
                lower.contains("traceback") || lower.contains("deprecat") ||
                lower.contains("warning")
            }
            val needsExplanation = result.error != null || result.recordCount == 0 || logsHaveIssues
            if (needsExplanation) {
                val script = try {
                    val env = DatrisEnvironment.current.environment
                    ObjectStoreUtil.readBucketObject(env + "-config", tapConfig.scriptPath).getOrElse("")
                } catch { case _: Exception => "" }
                val aiExplanation = getAIExplanation(tapConfig.description, script, result)
                // Swallow the "all clear" response so the UI doesn't show an empty diagnosis
                // panel just because the heuristic fired on a benign warning.
                val isAllClear = aiExplanation != null &&
                    aiExplanation.trim.toLowerCase.stripSuffix(".").stripSuffix("!") == "no issues detected"
                if (aiExplanation != null && !isAllClear)
                    response.put("aiExplanation", aiExplanation)
            }

            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/tap/run"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def runTap(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
               @RequestBody body: java.util.Map[String, String]): ResponseEntity[String] = {
        try {
            val name = body.get("name")
            logger.info("API endpoint POST /tap/run called for tap: " + name)
            APIKeyValidator.validate(apiKey)

            if (name == null || name.isEmpty)
                throw new DatrisException("Tap name is required")

            val tapConfig = TapConfigIO.read(DatrisEnvironment.current.tapTableName, name)
            if (tapConfig == null)
                throw new DatrisException("Tap: " + name + " not found")

            val mode = Option(body.get("mode")).map(_.toLowerCase).getOrElse("test")

            // Debounce push runs to suppress accidental duplicates (parallel tool calls,
            // double-clicks, transport retries). mode=test is read-only, no need to debounce.
            // checkAndAcceptRunDebounce returns Some(ageMs) when the request should be
            // rejected (a recent run is still inside the window), or None to proceed.
            val debouncedAgeMs: Option[Long] =
                if (mode == "run") checkAndAcceptRunDebounce(name) else None

            if (debouncedAgeMs.isDefined) {
                val ageMs = debouncedAgeMs.get
                logger.info("Debounced /tap/run for tap: " + name + " — last run started " + ageMs + "ms ago (window=" + TapAPIController.runDebounceWindowMs + "ms)")
                val gson = new Gson
                val response = new java.util.HashMap[String, Any]()
                response.put("tap", name)
                response.put("description", tapConfig.description)
                response.put("status", "skipped")
                response.put("mode", mode)
                response.put("targetPipeline", tapConfig.targetPipeline)
                response.put("persisted", java.lang.Boolean.FALSE)
                response.put("persistedReason", "debounced")
                response.put("error",
                    "Tap '" + name + "' was triggered " + ageMs + " ms ago; ignoring this duplicate request " +
                    "(debounce window: " + TapAPIController.runDebounceWindowMs + " ms). Wait for the in-flight " +
                    "run to finish, then check `get_pipeline_status` or `get_tap_logs` for the outcome.")
                response.put("recordCount", Integer.valueOf(0))
                new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
            }
            else {

            val result = TapRunner.run(tapConfig, mode = mode)

            // Save test run status when not pushing to pipeline
            if (mode != "run") {
                val sdf = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
                sdf.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
                val now = sdf.format(new java.util.Date())
                val updated = tapConfig.copy(
                    lastTestRunStatus = if (result.error == null) "success" else "failure",
                    lastTestRunTime = now,
                    lastTestRunRecordCount = result.recordCount,
                    lastTestRunError = result.error,
                    lastTestRunDataType = result.dataType,
                    lastTestRunColumns = result.columns
                )
                TapConfigIO.write(updated)
            }

            val gson = new Gson
            val rawRecords = if (result.records != null) JsonParser.parseString(result.records) else null

            // Records policy:
            //   - mode=run: omit records entirely. They are in transit to the destination;
            //     the agent must verify via get_pipeline_status, not from this body.
            //     `recordCount` is enough to summarize what was submitted.
            //   - mode=test: include records as a preview, capped at TapAPIController.testRecordSampleSize.
            //     Set `recordsTruncated=true` when we trimmed it.
            val (recordsToReturn, recordsTruncated) =
                if (mode == "run") (null, false)
                else if (rawRecords != null && rawRecords.isJsonArray) {
                    val arr = rawRecords.getAsJsonArray
                    if (arr.size > TapAPIController.testRecordSampleSize) {
                        val sample = new com.google.gson.JsonArray
                        var i = 0
                        while (i < TapAPIController.testRecordSampleSize) { sample.add(arr.get(i)); i += 1 }
                        (sample: com.google.gson.JsonElement, true)
                    }
                    else (rawRecords: com.google.gson.JsonElement, false)
                }
                else (rawRecords: com.google.gson.JsonElement, false)

            val hasTargetPipeline = tapConfig.targetPipeline != null && tapConfig.targetPipeline.nonEmpty
            val persisted = mode == "run" && hasTargetPipeline && result.error == null && result.recordCount > 0
            val persistedReason: String =
                if (persisted) null
                else if (mode != "run") "test_mode"
                else if (result.error != null) "run_error"
                else if (result.recordCount == 0) "no_records"
                else if (!hasTargetPipeline) "no_target_pipeline"
                else "unknown"
            val response = new java.util.HashMap[String, Any]()
            response.put("tap", name)
            response.put("description", tapConfig.description)
            response.put("status", if (result.error == null) "success" else "failure")
            response.put("mode", mode)
            response.put("targetPipeline", tapConfig.targetPipeline)
            response.put("persisted", java.lang.Boolean.valueOf(persisted))
            if (persistedReason != null) response.put("persistedReason", persistedReason)
            if (result.publisherToken != null) response.put("publisherToken", result.publisherToken)
            if (result.pipelineTokens != null && !result.pipelineTokens.isEmpty) response.put("pipelineTokens", result.pipelineTokens)
            if (recordsToReturn != null) response.put("records", recordsToReturn)
            if (recordsTruncated) response.put("recordsTruncated", java.lang.Boolean.TRUE)
            response.put("recordCount", Integer.valueOf(result.recordCount))
            response.put("error", result.error)
            response.put("logs", result.logs)
            response.put("dataType", result.dataType)
            response.put("columns", result.columns)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
            }  // end else (non-debounced path)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    /** Atomically check + accept the run-debounce window for a tap. Returns Some(ageMs)
     *  when a recent run is still inside the window (caller should reject), or None when
     *  the slot is now reserved for the caller to proceed. */
    private def checkAndAcceptRunDebounce(tapName: String): Option[Long] = {
        val tenant = if (DatrisEnvironment.values.multiTenant) DatrisEnvironment.current.environment else "global"
        val dedupKey = tenant + "::" + tapName
        val now = System.currentTimeMillis()
        val accepted = new java.util.concurrent.atomic.AtomicBoolean(false)
        TapAPIController.recentRunStarts.compute(dedupKey, (_, existing) => {
            if (existing != null && (now - existing.longValue()) < TapAPIController.runDebounceWindowMs)
                existing
            else {
                accepted.set(true)
                java.lang.Long.valueOf(now)
            }
        })
        if (accepted.get()) None
        else Some(now - TapAPIController.recentRunStarts.get(dedupKey).longValue())
    }

    @GetMapping(path = Array("/tap/ledger"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getTapLedger(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                     @RequestParam name: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /tap/ledger called for tap: " + name)
            APIKeyValidator.validate(apiKey)

            val entries = TapDocumentLedgerIO.readByTap(DatrisEnvironment.current.tapLedgerTableName, name).asJava
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(entries), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/tap/ledger"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteTapLedger(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                        @RequestParam name: String,
                        @RequestParam(required = false) uri: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /tap/ledger called for tap: " + name + (if (uri != null) ", uri: " + uri else ""))
            APIKeyValidator.validate(apiKey)

            val ledgerTable = DatrisEnvironment.current.tapLedgerTableName
            val bucket = DatrisEnvironment.current.environment + "-config"

            if (uri != null && uri.nonEmpty) {
                // Delete one entry, plus its staged file
                val entry = TapDocumentLedgerIO.read(ledgerTable, name, uri)
                if (entry != null && entry.stagedPath != null && entry.stagedPath.nonEmpty) {
                    try { ObjectStoreUtil.deleteBucketObject(bucket, entry.stagedPath) }
                    catch { case ex: Exception => logger.warn("Failed to delete staged doc " + entry.stagedPath + ": " + ex.getMessage) }
                }
                TapDocumentLedgerIO.delete(ledgerTable, name, uri)
                new ResponseEntity[String]("{\"message\": \"Ledger entry deleted\"}", HttpStatus.OK)
            } else {
                // Clear the entire ledger for this tap + all staged files
                val entries = TapDocumentLedgerIO.readByTap(ledgerTable, name)
                entries.foreach { e =>
                    if (e.stagedPath != null && e.stagedPath.nonEmpty) {
                        try { ObjectStoreUtil.deleteBucketObject(bucket, e.stagedPath) }
                        catch { case ex: Exception => logger.warn("Failed to delete staged doc " + e.stagedPath + ": " + ex.getMessage) }
                    }
                }
                TapDocumentLedgerIO.deleteByTap(ledgerTable, name)
                new ResponseEntity[String]("{\"message\": \"Ledger cleared: " + entries.size + " entries\"}", HttpStatus.OK)
            }
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    private def getAIExplanation(description: String, script: String, result: TapScriptResult): String = {
        try {
            if (!DatrisEnvironment.current.aiEnabled || DatrisEnvironment.current.aiConfig == null)
                return null

            val logs = Option(result.logs).getOrElse("").take(1000)
            val error = Option(result.error).getOrElse("").take(1000)
            val truncatedScript = if (script.length > 3000) script.take(3000) + "\n... (truncated)" else script

            val outcomeLine = if (result.error != null)
                s"The script failed with an error after returning ${result.recordCount} records."
            else if (result.recordCount == 0)
                "The script ran without raising an exception but returned 0 records."
            else
                s"The script ran successfully and returned ${result.recordCount} records, but the runtime output may suggest improvements."

            val prompt =
                s"""You are a data engineering assistant reviewing a Tap — a Python script that fetches data from an external source and returns a list of records from its `fetch()` function.
                   |
                   |$outcomeLine
                   |
                   |Tap description: $description
                   |
                   |Python script:
                   |$truncatedScript
                   |
                   |Script output/logs:
                   |$logs
                   |
                   |${if (error.nonEmpty) "Error: " + error else ""}
                   |
                   |Analyze the script and its runtime output, and respond with ONE of the following:
                   |  (a) If the script failed or returned 0 records, explain what went wrong and suggest a specific fix.
                   |  (b) If the script succeeded but the logs contain deprecation warnings, the result table looks incomplete (e.g. many NULL/None fields), or the approach relies on deprecated APIs, suggest a concrete improvement — for example, migrating to the recommended API, adding missing fields, or handling edge cases. Be specific about WHICH line/function to change.
                   |  (c) If the script is healthy and the output looks clean, respond with exactly "No issues detected." and nothing else.
                   |
                   |Diagnostic discipline — read before answering:
                   |  1. Ground your diagnosis in the actual traceback and stderr logs. Quote the specific exception type, message, and line number from the error. Do not invent causes the traceback already contradicts.
                   |  2. Respect guards already in the script. If the script would have raised on an earlier condition (e.g. a `raise` when an env var is missing, a `raise_for_status` on a prior request, a guard checking for an empty value), and that earlier exception did NOT fire, do NOT propose that earlier condition as the root cause.
                   |  3. Prefer data-level explanations when the code ran structurally fine. If a request succeeded (no HTTP error) but returned empty data, the most likely cause is that the upstream has no matching data — not that the code has a bug. Say so plainly, and suggest the user verify the source state (e.g. check the database, check the API's filters) before assuming a code defect.
                   |  4. Do not hypothesize about response shape mismatches for Datris platform endpoints (`/api/v1/metadata/*`, `/api/v1/query/*`). Their shapes are contractual and documented; if parsing them raised, the cause is elsewhere.
                   |  5. When you are uncertain, say so and name the one concrete thing the user could check or print to disambiguate — not a laundry list.
                   |  6. If the script queried `/api/v1/query/mongodb` or `/api/v1/query/postgres` and got exactly 20 (Mongo) or 100 (Postgres) rows back, the cause is the server's preview default limit. The fix is to add `"limit": -1` to the request body so the server returns every row.
                   |
                   |Respond in plain English only — no JSON, no code fences, no markdown formatting.
                   |Keep it to 2-3 concise sentences. Reference the exact line, function, or API that needs to change. Focus on actionable advice the user can apply immediately.""".stripMargin

            val responseText = AIUtil.callAI(prompt)
            AIUtil.extractText(responseText).trim
        } catch {
            case _: Exception => null
        }
    }
}
