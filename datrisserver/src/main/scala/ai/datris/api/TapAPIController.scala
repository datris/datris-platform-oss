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
            // Include script content from MinIO
            val scriptContent = if (config.scriptPath != null) {
                try {
                    val env = DatrisEnvironment.current.environment
                    ObjectStoreUtil.readBucketObject(env + "-config", config.scriptPath).getOrElse(null)
                } catch { case _: Exception => null }
            } else null
            val response = gson.fromJson(gson.toJson(config), classOf[java.util.Map[String, Any]])
            response.put("script", scriptContent)
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

            TapConfigIO.delete(DatrisEnvironment.current.tapTableName, name)
            new ResponseEntity[String]("{\"message\": \"Tap deleted: " + name + "\"}", HttpStatus.OK)
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
            val cron = AIUtil.extractText(responseText).trim.replaceAll("[\"'`]", "")

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

            if (messagesRaw == null || messagesRaw.isEmpty)
                throw new DatrisException("messages array is required")

            val messages = messagesRaw.asScala.map { m =>
                (m.get("role"), m.get("content"))
            }.toSeq

            val systemPrompt =
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
                  |- Database name (always available as DATRIS_DATABASE env var)
                  |- Schema name (default to "public" for postgres, or have the script call /api/v1/metadata/postgres/schemas to find it)
                  |- Whether a table exists or what columns it has (the script will call /api/v1/metadata/postgres/columns at runtime to discover the schema)
                  |- Exact column types or names — the script can introspect them
                  |
                  |Only ask the user for things the platform CANNOT discover: which external API to use, time ranges, filters, business logic, or credentials. When the user mentions a Datris table by name, just confirm and write the instruction — the generated script will handle metadata discovery on its own.
                  |
                  |CREDENTIALS — Many external data sources need API keys, tokens, or other secrets. The Datris tap runner injects environment variables into the script at runtime. Common ones include:
                  |- DATRIS_DATABASE, DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT (auto-injected for accessing the Datris platform itself)
                  |- API_KEY, ALPHA_VANTAGE_API_KEY, POLYGON_API_KEY, NEWSAPI_KEY, etc. (must be configured by the user in a "tap secret")
                  |
                  |Whenever the data source needs authentication, you MUST:
                  |1. Tell the user which environment variables the script will need (suggest specific names like ALPHA_VANTAGE_API_KEY)
                  |2. Mention that they should create or select a "tap secret" containing those keys in the Credentials section below the chat
                  |3. Include the env var names in the instruction draft so the script generator references them via os.environ.get()
                  |
                  |For free, no-auth sources (yfinance, Open-Meteo, public RSS feeds), no credentials are needed — say so explicitly.
                  |
                  |Ask ONE focused clarifying question at a time. Suggest specific data sources when relevant (e.g., yfinance for stocks, Alpha Vantage for fundamentals, Open-Meteo for weather, NewsAPI for news). Be concise — 1-2 sentences per turn. When you have enough information, tell the user the instruction is ready and they can proceed.
                  |
                  |After EACH user message, return JSON with three fields:
                  |{
                  |  "reply": "your next message or question",
                  |  "description": "the current best draft of the tap instruction, written as a clear technical directive for code generation",
                  |  "suggestedEnvVars": ["ENV_VAR_NAME_1", "ENV_VAR_NAME_2"]
                  |}
                  |
                  |The description should always reflect everything known so far. If the user hasn't provided enough info yet, the description can be partial. Never leave description empty after the first user message — always provide your best guess. When the user references a Datris table/collection, the description should explicitly say so (e.g., "Query the consumer_discretionary_earnings table via /api/v1/query/postgres to get the ticker list, then fetch...").
                  |
                  |suggestedEnvVars should list any environment variable names the script will need that are NOT auto-injected by Datris (so do NOT include DATRIS_DATABASE, DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT). For example, if the user picks Alpha Vantage, suggest ["ALPHA_VANTAGE_API_KEY"]. For free sources with no auth, return an empty array []. Always return the field, even when empty.
                  |
                  |Return ONLY the JSON object, no markdown fences, no commentary.""".stripMargin

            // Prepend a system-style note about current description if present
            val messagesWithContext: Seq[(String, String)] = if (currentDescription.nonEmpty) {
                val contextNote = (messages.head._1, "[Current instruction draft: " + currentDescription + "]\n\n" + messages.head._2)
                contextNote +: messages.tail
            } else {
                messages
            }

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
            logger.info("API endpoint POST /tap/generate called, tapName: " + tapName)
            APIKeyValidator.validate(apiKey)

            if (description == null || description.isEmpty)
                throw new DatrisException("Description is required")

            val result = TapScriptGenerator.generate(description, tapName, oldScriptPath, secretName)

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
            logger.info("API endpoint POST /tap/fix called, tapName: " + tapName)
            APIKeyValidator.validate(apiKey)

            if (script == null || script.isEmpty)
                throw new DatrisException("Script is required")
            if (diagnosis == null || diagnosis.isEmpty)
                throw new DatrisException("Diagnosis is required")

            val systemPrompt =
                """You are a code generator. You will be given a Python script that has a bug, along with the error output and a diagnosis.
                  |Fix the script and return a JSON object with two fields:
                  |- "script": the complete fixed Python 3 script (must define a `fetch()` function)
                  |- "packages": a list of any pip packages needed beyond the pre-installed set
                  |  (requests, beautifulsoup4, pandas, lxml, feedparser). Use an empty list if none needed.
                  |Return ONLY the JSON object, no markdown fences or commentary.""".stripMargin

            val userPrompt =
                s"""Current script:
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

            val responseText = AIUtil.callAIWithSystem(systemPrompt, userPrompt)
            val extracted = AIUtil.extractText(responseText)
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

    private def cleanAIResponse(response: String): String = {
        var cleaned = response.trim
        if (cleaned.startsWith("```json")) cleaned = cleaned.stripPrefix("```json").trim
        else if (cleaned.startsWith("```")) cleaned = cleaned.stripPrefix("```").trim
        if (cleaned.endsWith("```")) cleaned = cleaned.stripSuffix("```").trim
        cleaned
    }

    @PostMapping(path = Array("/tap/test"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def testTap(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                @RequestBody tapConfig: TapConfig): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /tap/test called for tap: " + tapConfig.name)
            APIKeyValidator.validate(apiKey)

            if (tapConfig.scriptPath == null || tapConfig.scriptPath.isEmpty)
                throw new DatrisException("Script path is required for testing")

            // Run in test mode (no push to pipeline)
            val result = TapRunner.run(tapConfig, pushToPipeline = false)
            val gson = new Gson
            val recordsJson = if (result.records != null) JsonParser.parseString(result.records) else null
            val response = new java.util.HashMap[String, Any]()
            response.put("records", recordsJson)
            response.put("recordCount", Integer.valueOf(result.recordCount))
            response.put("error", result.error)
            response.put("logs", result.logs)
            response.put("dataType", result.dataType)
            response.put("columns", result.columns)

            // AI explanation if there's an error, 0 records, or logs contain error indicators
            val logsHaveErrors = result.logs != null && result.logs.nonEmpty &&
                (result.logs.toLowerCase.contains("error") || result.logs.toLowerCase.contains("exception") ||
                 result.logs.toLowerCase.contains("failed") || result.logs.toLowerCase.contains("forbidden") ||
                 result.logs.toLowerCase.contains("traceback"))
            val needsExplanation = result.error != null || result.recordCount == 0 || logsHaveErrors
            if (needsExplanation) {
                val script = try {
                    val env = DatrisEnvironment.current.environment
                    ObjectStoreUtil.readBucketObject(env + "-config", tapConfig.scriptPath).getOrElse("")
                } catch { case _: Exception => "" }
                val aiExplanation = getAIExplanation(tapConfig.description, script, result)
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

            val pushToPipeline = Option(body.get("pushToPipeline")).exists(_.equalsIgnoreCase("true"))
            val result = TapRunner.run(tapConfig, pushToPipeline = pushToPipeline)

            // Save test run status when not pushing to pipeline
            if (!pushToPipeline) {
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
            val recordsJson = if (result.records != null) JsonParser.parseString(result.records) else null
            val response = new java.util.HashMap[String, Any]()
            response.put("tap", name)
            response.put("description", tapConfig.description)
            response.put("status", if (result.error == null) "success" else "failure")
            response.put("records", recordsJson)
            response.put("recordCount", Integer.valueOf(result.recordCount))
            response.put("error", result.error)
            response.put("logs", result.logs)
            response.put("dataType", result.dataType)
            response.put("columns", result.columns)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
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

            val prompt =
                s"""You are a data engineering assistant. A user created a Tap (a Python script that fetches data from an external source).
                   |The script was tested but returned ${result.recordCount} records${if (result.error != null) " and failed with an error" else ""}.
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
                   |Respond in plain English only — no JSON, no code fences, no markdown formatting.
                   |Explain in 2-3 concise sentences what went wrong and suggest a specific fix for the Python script.
                   |Reference the exact line or function that needs to change. Focus on actionable advice the user can apply immediately.""".stripMargin

            val responseText = AIUtil.callAI(prompt)
            AIUtil.extractText(responseText).trim
        } catch {
            case _: Exception => null
        }
    }
}
