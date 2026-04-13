package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonParser}
import ai.datris.model.{TapConfig, PipelineConfig, Source, Destination, FileAttributes, JsonAttributes, Database, DatrisEnvironment, DatrisException}
import ai.datris.util._
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
class DiscoveryAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[DiscoveryAPIController])

    @PostMapping(path = Array("/discover"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def discover(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                 @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /discover called")
            APIKeyValidator.validate(apiKey)

            val messagesRaw = body.get("messages").asInstanceOf[java.util.List[java.util.Map[String, String]]]

            if (messagesRaw == null || messagesRaw.isEmpty)
                throw new DatrisException("messages array is required")

            val mode = Option(body.get("mode")).map(_.toString).getOrElse("discover")
            val authContext = Option(body.get("authContext")).map(_.toString).getOrElse("")

            val messages = messagesRaw.asScala.map { m =>
                (m.get("role"), m.get("content"))
            }.toSeq

            // Chat mode: conversational, helps user identify data sources. No dataset enumeration.
            if (mode == "chat") {
                val chatPrompt =
                    """You are a data discovery assistant. Help users identify which DATA SOURCES they want to explore.
                      |
                      |Your ONLY job is to help the user identify a specific data source (Python package, API, website, database).
                      |DO NOT ask about parameters, filters, tickers, date ranges, or how they want to use the data.
                      |DO NOT ask about specific stocks, companies, or entities they want to query.
                      |Those details are collected later in a separate step.
                      |
                      |Focus on: What kind of data do they need? Which source provides it? Is it free or paid?
                      |Suggest specific data sources when relevant.
                      |
                      |DO NOT tell the user to install packages (pip install), write code, or give technical setup instructions.
                      |The platform handles all installation and code generation automatically.
                      |
                      |After EACH user message, return a JSON object with these fields:
                      |{
                      |  "reply": "your conversational response",
                      |  "sourceIdentified": true or false
                      |}
                      |
                      |Set "sourceIdentified" to true when the conversation has identified at least one specific, concrete data source
                      |that could be discovered (e.g., a specific Python package, REST API, or website URL).
                      |Set it to false if the conversation is still exploratory or the user hasn't named a specific source yet.
                      |
                      |Be concise — 1-3 sentences per turn. Ask ONE focused question at a time.
                      |Return ONLY the JSON object, no markdown fences.""".stripMargin

                val responseText = AIUtil.callAIWithMessages(chatPrompt, messages)
                val rawText = AIUtil.extractText(responseText).trim

                var cleaned = rawText
                    .replaceAll("(?s)^```(?:json)?\\s*", "")
                    .replaceAll("(?s)\\s*```$", "")
                    .trim
                val firstBrace = cleaned.indexOf('{')
                val lastBrace = cleaned.lastIndexOf('}')
                if (firstBrace >= 0 && lastBrace > firstBrace) {
                    cleaned = cleaned.substring(firstBrace, lastBrace + 1)
                }

                val responseBody = if (cleaned.startsWith("{") && cleaned.contains("\"reply\"")) {
                    cleaned
                } else {
                    val gson = new Gson
                    val result = new com.google.gson.JsonObject()
                    result.addProperty("reply", rawText)
                    result.addProperty("sourceIdentified", false)
                    gson.toJson(result)
                }

                new ResponseEntity[String](responseBody, HttpStatus.OK)
            } else {

            // Discover mode: full dataset enumeration
            val systemPrompt =
                """You are a data discovery assistant. Your job is to help users discover available datasets from any data source — Python packages, REST APIs, websites, databases, or services.
                  |
                  |When the user asks about a data source, enumerate ALL available datasets from that source. Be thorough and specific.
                  |
                  |After EACH user message, return a JSON object with these fields:
                  |{
                  |  "reply": "your conversational response explaining what you found",
                  |  "datasets": [
                  |    {
                  |      "id": "snake_case_unique_id",
                  |      "category": "Category Name",
                  |      "name": "Human-Readable Dataset Name",
                  |      "description": "One-sentence description of what this dataset contains",
                  |      "parameters": [
                  |        {
                  |          "name": "param_name",
                  |          "type": "string|select",
                  |          "label": "Human-Readable Label",
                  |          "placeholder": "Example value (for type=string only)",
                  |          "options": ["opt1", "opt2"] ,
                  |          "default": "default_value",
                  |          "required": true,
                  |          "multiple": true or false
                  |        }
                  |      ],
                  |      "tapInstruction": "A complete, precise plain-English instruction for generating a Python tap script. Include the specific library/API to use, what method/endpoint to call, and how to structure the output. Use {{param_name}} placeholders for user-supplied parameters. The script must define a fetch() function that returns a list of dicts with snake_case keys.",
                  |      "packages": ["pip-package-name"],
                  |      "requiresAuth": false,
                  |      "suggestedEnvVars": []
                  |    }
                  |  ],
                  |  "ready": true
                  |}
                  |
                  |IMPORTANT RULES FOR datasets:
                  |1. Each dataset must have a unique "id" (snake_case, descriptive)
                  |2. Group datasets logically by "category" (e.g., "Price & Volume", "Fundamentals", "Options", "Info & Metadata")
                  |3. The "tapInstruction" is CRITICAL — it must be precise enough that an AI code generator produces a correct, working Python script on the first try. EACH dataset's instruction must:
                  |   - Name the EXACT Python method or API endpoint to call — not a generic description
                  |   - Be completely specific to THIS dataset — do NOT reuse the same method across different datasets
                  |   - Specify the EXACT columns/fields the output should contain — list them explicitly
                  |   - Describe the exact data transformation: how to convert the raw API response into a flat list of dicts
                  |   - Include how to handle multiple items (e.g., "For each item in {{param}}, call ...")
                  |   - State that the function must be named fetch() and return a list of dicts with snake_case keys
                  |   - Include a note like "Do NOT call any other method — only use [specific_method]"
                  |   - For DataFrames: specify whether to transpose, reset index, rename columns, etc.
                  |   - The output columns must be CONSISTENT regardless of input parameters — the schema must be stable
                  |   - If the data source requires authentication, include EXPLICIT instructions to configure the API key/token
                  |     from environment variables (via os.environ.get()) BEFORE making any API calls. The env var names
                  |     must match the suggestedEnvVars array. Credentials are injected as environment variables at runtime.
                  |4. The "parameters" array defines what the user needs to fill in:
                  |   - "string": free text input (use "placeholder" for example values)
                  |   - "select": dropdown with fixed "options" list (include "default" value)
                  |   - "multiple": true if the API accepts multiple values (e.g., a list of tickers, multiple country codes),
                  |     false if the API accepts only a single value. This applies to ANY parameter type.
                  |   - Use CONSISTENT parameter names across all datasets from the same source for the same concept.
                  |     If 3 datasets all need the same type of input, use the same "name" for all 3 — do NOT vary it.
                  |   - Use DIFFERENT parameter names for different types of values, even if they seem similar.
                  |     Parameters that accept different categories of input must have distinct names so they
                  |     are not confused with each other when shared across datasets.
                  |5. "packages" lists the EXACT pip install package names — these must be the names used with `pip install`, NOT the Python import names (they are often different). Verify the correct PyPI package name for each package. Pre-installed packages that do NOT need to be listed: requests, beautifulsoup4, pandas, lxml, feedparser.
                  |6. "requiresAuth": true if the source needs API keys/tokens. Include "suggestedEnvVars" with env var names.
                  |7. "ready" should be true when you have a complete dataset list. Set false if you need more info from the user.
                  |
                  |MULTI-TURN BEHAVIOR:
                  |- If the user's first message is clear and names a specific source, return the full dataset list immediately with ready=true.
                  |- If the user asks a vague question, ask a clarifying question with ready=false and an empty datasets array.
                  |- If the user asks follow-up questions, update the datasets array with any additions/changes.
                  |
                  |Return ONLY the JSON object, no markdown fences, no commentary.""".stripMargin

            val finalPrompt = if (authContext.nonEmpty) {
                systemPrompt + "\n\nAUTHENTICATION CONTEXT — The following describes how this data source authenticates. " +
                "Include these exact instructions in EVERY tapInstruction for datasets that require auth:\n" + authContext
            } else systemPrompt

            val responseText = AIUtil.callAIWithMessages(finalPrompt, messages, 32768, 0.0)
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

            // The LLM returns {"reply": "...", "datasets": [...], "ready": true}.
            // Validate it's well-formed JSON first — the LLM may truncate if it
            // hits max output tokens, producing unterminated strings.
            val responseBody = try {
                JsonParser.parseString(cleaned)
                // Valid JSON — pass through as-is
                cleaned
            } catch {
                case _: Exception =>
                    // Malformed JSON (likely truncated by LLM output limit).
                    // Extract the reply field manually via string search since
                    // we know the structure starts with {"reply": "...".
                    logger.warn("Discovery AI returned malformed JSON (likely truncated), extracting reply manually")
                    val gson = new Gson
                    val result = new com.google.gson.JsonObject()

                    val replyText = try {
                        val replyStart = cleaned.indexOf("\"reply\"")
                        if (replyStart >= 0) {
                            // Find the opening quote of the value
                            val valueStart = cleaned.indexOf('"', replyStart + 7)
                            if (valueStart >= 0) {
                                // Find the closing quote (handle escaped quotes)
                                var i = valueStart + 1
                                val sb = new StringBuilder
                                while (i < cleaned.length) {
                                    val c = cleaned.charAt(i)
                                    if (c == '\\' && i + 1 < cleaned.length) {
                                        sb.append(c)
                                        sb.append(cleaned.charAt(i + 1))
                                        i += 2
                                    } else if (c == '"') {
                                        // end of string
                                        i = cleaned.length // break
                                    } else {
                                        sb.append(c)
                                        i += 1
                                    }
                                }
                                // Unescape common JSON escapes
                                sb.toString.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
                            } else rawText
                        } else rawText
                    } catch { case _: Exception => rawText }

                    // Try to extract whatever datasets parsed successfully
                    val datasets = try {
                        val dsStart = cleaned.indexOf("\"datasets\"")
                        if (dsStart >= 0) {
                            val arrStart = cleaned.indexOf('[', dsStart)
                            if (arrStart >= 0) {
                                // Try progressively shorter substrings until we find valid JSON array
                                var lastGood: com.google.gson.JsonArray = new com.google.gson.JsonArray()
                                var endPos = cleaned.lastIndexOf('}')
                                while (endPos > arrStart) {
                                    val candidate = cleaned.substring(arrStart, endPos) + "]"
                                    try {
                                        val parsed = JsonParser.parseString(candidate)
                                        if (parsed.isJsonArray) {
                                            lastGood = parsed.getAsJsonArray
                                            endPos = 0 // break
                                        } else endPos -= 1
                                    } catch { case _: Exception => endPos -= 1 }
                                }
                                lastGood
                            } else new com.google.gson.JsonArray()
                        } else new com.google.gson.JsonArray()
                    } catch { case _: Exception => new com.google.gson.JsonArray() }

                    result.addProperty("reply", replyText)
                    result.add("datasets", datasets)
                    result.addProperty("ready", datasets.size() > 0)
                    gson.toJson(result)
            }

            new ResponseEntity[String](responseBody, HttpStatus.OK)
            } // end else (discover mode)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/discover/build"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def build(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
              @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /discover/build called")
            APIKeyValidator.validate(apiKey)

            val prefix = Option(body.get("prefix")).map(_.toString).getOrElse("discovery")
            val datasetsRaw = body.get("datasets").asInstanceOf[java.util.List[java.util.Map[String, Any]]]

            if (datasetsRaw == null || datasetsRaw.isEmpty)
                throw new DatrisException("datasets array is required")

            val gson = new Gson
            val results = new java.util.ArrayList[java.util.Map[String, Any]]()

            datasetsRaw.asScala.foreach { ds =>
                val datasetId = Option(ds.get("id")).map(_.toString).getOrElse("unknown")
                val tapName = prefix + "_" + datasetId
                val tapInstruction = Option(ds.get("tapInstruction")).map(_.toString).getOrElse("")
                val createPipeline = Option(ds.get("createPipeline")).exists {
                    case b: java.lang.Boolean => b
                    case s: String => s.equalsIgnoreCase("true")
                    case d: java.lang.Double => d > 0
                    case _ => false
                }
                val secretName = Option(ds.get("secretName")).map(_.toString).orNull

                val result = new java.util.HashMap[String, Any]()
                result.put("id", datasetId)
                result.put("tapName", tapName)

                try {
                    // Generate the tap script
                    val genResult = TapScriptGenerator.generate(tapInstruction, tapName, null, secretName)

                    // Extract packages list
                    val packages: java.util.List[String] = Option(ds.get("packages")) match {
                        case Some(list: java.util.List[_]) =>
                            val stringList = new java.util.ArrayList[String]()
                            list.asScala.foreach(p => stringList.add(p.toString))
                            stringList
                        case _ => genResult.packages
                    }

                    // Create the tap config
                    val sdf = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
                    sdf.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
                    val now = sdf.format(new java.util.Date())

                    val tapConfig = TapConfig(
                        name = tapName,
                        description = tapInstruction,
                        scriptPath = genResult.scriptPath,
                        targetPipeline = if (createPipeline) tapName else "",
                        packages = packages,
                        secretName = Option(secretName).getOrElse(""),
                        cronExpression = "",
                        enabled = false,
                        lastRunStatus = "",
                        lastRunTime = "",
                        lastRunRecordCount = 0,
                        lastRunError = "",
                        lastRunDataType = "",
                        lastRunColumns = new java.util.ArrayList[String](),
                        lastTestRunStatus = "",
                        lastTestRunTime = "",
                        lastTestRunRecordCount = 0,
                        lastTestRunError = "",
                        lastTestRunDataType = "",
                        lastTestRunColumns = new java.util.ArrayList[String](),
                        createdAt = now,
                        updatedAt = now
                    )
                    TapConfigIO.write(tapConfig)

                    // Optionally create a pipeline
                    if (createPipeline) {
                        try {
                            val tableName = tapName.replace("-", "_")
                            val pipelineConfig = PipelineConfig(
                                name = tapName,
                                source = Source(
                                    fileAttributes = FileAttributes(
                                        jsonAttributes = JsonAttributes(everyRowContainsObject = false, encoding = "UTF-8")
                                    )
                                ),
                                destination = Destination(
                                    database = Database(
                                        dbName = "DATABASE_NAME",
                                        table = tableName,
                                        useMongoDB = true,
                                        truncateBeforeWrite = false
                                    )
                                )
                            )

                            PipelineConfigIO.write(pipelineConfig)
                            result.put("pipelineCreated", java.lang.Boolean.TRUE)
                        } catch {
                            case e: Exception =>
                                logger.warn("Failed to create pipeline for tap " + tapName + ": " + e.getMessage)
                                result.put("pipelineCreated", java.lang.Boolean.FALSE)
                                result.put("pipelineError", e.getMessage)
                        }
                    }

                    result.put("status", "success")
                    result.put("script", genResult.script)
                } catch {
                    case e: Exception =>
                        logger.error("Failed to build tap " + tapName + ": " + Throwables.getStackTraceAsString(e))
                        result.put("status", "error")
                        result.put("error", e.getMessage)
                }

                results.add(result)
            }

            val response = new java.util.HashMap[String, Any]()
            response.put("results", results)
            response.put("totalRequested", Int.box(datasetsRaw.size()))
            response.put("totalSuccess", Int.box(results.asScala.count(r => "success" == r.get("status"))))

            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
