package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException}
import com.google.gson.Gson
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.collection.JavaConverters._

case class TapGenerateResult(script: String, packages: java.util.List[String], scriptPath: String)

object TapScriptGenerator {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val SYSTEM_PROMPT =
        """You are a code generator. Return a JSON object with two fields:
          |- "script": a valid Python 3 script that defines a function called `fetch()`
          |  that takes no arguments and returns a list of dictionaries (records).
          |- "packages": a list of any pip packages needed beyond the pre-installed set
          |  (requests, beautifulsoup4, pandas, lxml, feedparser).
          |  Pre-installed packages do not need to be listed. Use an empty list if none needed.
          |
          |The script must:
          |- Handle errors gracefully with try/except
          |- Include 30-second timeouts for network requests
          |- Return an empty list on failure rather than raising exceptions
          |- Be completely self-contained
          |- If authentication is needed, use os.environ.get('KEY_NAME') to access credentials
          |- NEVER hardcode API keys, tokens, or passwords in the script
          |
          |Column naming for tabular results:
          |- When returning a list of dicts (CSV-shaped data), prefer snake_case keys composed of [a-z0-9_] only.
          |- The platform automatically normalizes column names at runtime (e.g. "EPS Estimate" → "eps_estimate", "Surprise(%)" → "surprise_percent"), but generating clean keys directly is preferred so the user sees them faithfully in the test preview and in the pipeline schema.
          |- If the source returns columns with spaces, parens, or punctuation (common with pandas DataFrames), rename them in the script before adding to the record dict.
          |
          |If the script needs to query or discover data from the Datris platform:
          |- Use os.environ.get('DATRIS_PLATFORM_HOST') for the host (always injected by the platform)
          |- Use os.environ.get('DATRIS_PLATFORM_PORT') for the port (always injected by the platform)
          |- Use os.environ.get('DATRIS_POSTGRES_DATABASE') for the PostgreSQL database name (always injected by the platform)
          |- Use os.environ.get('DATRIS_MONGODB_DATABASE') for the MongoDB database name (always injected by the platform)
          |- These two database names may differ in single-tenant deployments; in multi-tenant mode they resolve to the same tenant name. Always use the variable that matches the backend you are querying.
          |- DO NOT provide fallback defaults for DATRIS_PLATFORM_HOST, DATRIS_PLATFORM_PORT, DATRIS_POSTGRES_DATABASE, or DATRIS_MONGODB_DATABASE — the platform always injects them. Use os.environ['NAME'] or os.environ.get('NAME') with NO second argument.
          |- Base URL: http://{host}:{port}/api/v1
          |
          |Metadata discovery (GET requests, all return JSON arrays):
          |- GET /api/v1/metadata/postgres/databases → list of database names
          |- GET /api/v1/metadata/postgres/schemas?database={pg_db} → list of schema names
          |- GET /api/v1/metadata/postgres/tables?database={pg_db}&schema=public → list of table names
          |- GET /api/v1/metadata/postgres/columns?database={pg_db}&schema=public&table=TABLE → list of {name, type}
          |- GET /api/v1/metadata/mongodb/databases → list of database names
          |- GET /api/v1/metadata/mongodb/collections?database={mongo_db} → list of collection names
          |
          |Query endpoints (POST requests):
          |- PostgreSQL: POST /api/v1/query/postgres
          |  Body: {"sql": "SELECT * FROM public.table_name", "database": "{pg_db}"}
          |  Response: {"results": [...list of row dicts...], "count": N}
          |- MongoDB: POST /api/v1/query/mongodb
          |  Body: {"query": "...", "database": "{mongo_db}", "collection": "collection_name"}
          |  Response: {"results": [...], "count": N}
          |
          |Where {pg_db} = os.environ.get('DATRIS_POSTGRES_DATABASE') and {mongo_db} = os.environ.get('DATRIS_MONGODB_DATABASE').
          |Use metadata endpoints to discover tables and columns dynamically when the user
          |describes data by name rather than providing exact table names.
          |
          |Return ONLY the JSON object, no markdown fences or commentary.""".stripMargin

    /**
     * Generate a Python fetch() script from a plain-English description.
     * Stores the script in MinIO and returns the result with script path.
     *
     * @param description what data to fetch
     * @param tapName     the tap name (used for the MinIO key)
     * @return TapGenerateResult with script content, packages, and MinIO path
     */
    def generate(description: String, tapName: String, oldScriptPath: String = null, secretName: String = null): TapGenerateResult = {
        logger.info("TapScriptGenerator: generating script for tap: " + tapName)

        if (!DatrisEnvironment.current.aiEnabled)
            throw new DatrisException("AI is not enabled. Set 'ai.enabled: true' in application.yaml")

        // Build user prompt with available secret keys if configured
        val secretKeysHint = if (secretName != null && secretName.nonEmpty) {
            val secretPath = DatrisEnvironment.current.environment + "/" + secretName
            val keys = SecretsUtil.getSecretMap(secretPath).map(_.keySet().asScala.filterNot(_ == "_type").toList).getOrElse(List.empty)
            if (keys.nonEmpty)
                "\n\nThe following environment variables are available for authentication: " +
                    keys.mkString(", ") + ". Access them with os.environ.get('KEY_NAME')."
            else ""
        } else ""

        val userPrompt = "Generate a Python script to: " + description + secretKeysHint

        // Call AI to generate the script
        val responseText = AIUtil.callAIWithSystem(SYSTEM_PROMPT, userPrompt)
        val extracted = AIUtil.extractText(responseText)
        val cleaned = cleanResponse(extracted)

        logger.info("TapScriptGenerator: AI response length: " + cleaned.length + " chars")

        val gson = new Gson

        // Try to parse an LLM response as a {script, packages} JSON object.
        // Returns Some((script, packages)) on success, None on any failure.
        // Handles preamble/suffix text around the JSON and common LLM formatting quirks.
        def tryParseAsJsonObject(text: String): Option[(String, java.util.List[String])] = {
            try {
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                val isolated = if (start >= 0 && end > start) text.substring(start, end + 1) else text
                val result = gson.fromJson(isolated, classOf[java.util.Map[String, Any]])
                Option(result).flatMap { r =>
                    Option(r.get("script")).map(_.toString).filter(_.trim.nonEmpty).map { s =>
                        val p: java.util.List[String] = r.get("packages") match {
                            case null => new java.util.ArrayList[String]()
                            case list: java.util.List[_] =>
                                val stringList = new java.util.ArrayList[String]()
                                val it = list.iterator()
                                while (it.hasNext) stringList.add(it.next().toString)
                                stringList
                            case _ => new java.util.ArrayList[String]()
                        }
                        (s, p)
                    }
                }
            } catch { case _: Exception => None }
        }

        // Attempt 1: parse the original response as a JSON object.
        //
        // Attempt 2 (retry): if attempt 1 failed, the LLM probably returned a raw script or a
        // JSON string literal. Call the AI again with a short format-only prompt that shows
        // the bad response back to the model and asks for the same content reformulated as a
        // valid JSON object. This is cheap on the happy path (never fires) and turns a hard
        // failure into a one-extra-call inconvenience on the unhappy path.
        //
        // Attempt 3 (fallback): if the retry still fails, treat the cleaned response as a
        // raw Python script with no package list — better than crashing, user can add
        // packages manually in Edit & Test.
        val (script, packages): (String, java.util.List[String]) =
            tryParseAsJsonObject(cleaned).orElse {
                logger.warn("TapScriptGenerator: first response did not parse as JSON — retrying with format reminder")
                val preview = if (cleaned.length > 2000) cleaned.take(2000) + "\n... (truncated)" else cleaned
                val retrySystemPrompt =
                    """Return ONLY a JSON object with exactly two fields:
                      |  "script": the complete Python 3 script as a string
                      |  "packages": an array of pip package names (empty array if none)
                      |No markdown fences, no string literals, no commentary — a JSON object.""".stripMargin
                val retryUserPrompt =
                    s"""Your previous response for the task below was not a valid JSON object. Here is what you returned:
                       |
                       |$preview
                       |
                       |Return the same Python script reformulated as a valid JSON object of the form {"script": "...", "packages": [...]}.
                       |
                       |Original task: $userPrompt""".stripMargin
                try {
                    val retryText = AIUtil.extractText(AIUtil.callAIWithSystem(retrySystemPrompt, retryUserPrompt))
                    val retryCleaned = cleanResponse(retryText)
                    logger.info("TapScriptGenerator: retry response length: " + retryCleaned.length + " chars")
                    tryParseAsJsonObject(retryCleaned)
                } catch {
                    case e: Exception =>
                        logger.warn("TapScriptGenerator: retry call failed: " + e.getMessage)
                        None
                }
            }.getOrElse {
                // Final fallback: treat the original cleaned response as a raw script.
                // Try to unwrap a JSON string literal first in case the LLM returned "..." form.
                val unwrapped = try {
                    val s = gson.fromJson(cleaned, classOf[String])
                    if (s != null && s.nonEmpty) s else cleaned
                } catch { case _: Exception => cleaned }
                logger.warn("TapScriptGenerator: retry also failed — treating as raw script (length: " + unwrapped.length + ")")
                (unwrapped, new java.util.ArrayList[String]())
            }

        if (script == null || script.trim.isEmpty)
            throw new DatrisException("AI returned an empty script")

        // Store script in MinIO (cleanup old)
        val scriptPath = storeScript(tapName, script, oldScriptPath)

        logger.info("TapScriptGenerator: script stored at: " + scriptPath + ", packages: " + packages)
        TapGenerateResult(script, packages, scriptPath)
    }

    /**
     * Store a script (user-provided or AI-generated) in MinIO.
     */
    def storeScript(tapName: String, script: String, oldScriptPath: String = null): String = {
        // Delete old script first
        deleteScript(oldScriptPath)

        val env = DatrisEnvironment.current.environment
        val bucketName = env + "-config"
        val uuid = UUID.randomUUID().toString.substring(0, 8)
        val key = "tap-scripts/" + tapName + "_" + uuid + ".py"

        ObjectStoreUtil.writeBucketObject(bucketName, key, script)
        key
    }

    /**
     * Delete a script from MinIO.
     */
    def deleteScript(scriptPath: String): Unit = {
        if (scriptPath != null) {
            val env = DatrisEnvironment.current.environment
            val bucketName = env + "-config"
            try {
                ObjectStoreUtil.deleteBucketObject(bucketName, scriptPath)
            } catch {
                case e: Exception =>
                    logger.warn("Failed to delete tap script from object store: " + scriptPath + ", error: " + e.getMessage)
            }
        }
    }

    private def cleanResponse(response: String): String = {
        var cleaned = response.trim
        if (cleaned.startsWith("```json"))
            cleaned = cleaned.stripPrefix("```json").trim
        else if (cleaned.startsWith("```"))
            cleaned = cleaned.stripPrefix("```").trim
        if (cleaned.endsWith("```"))
            cleaned = cleaned.stripSuffix("```").trim
        cleaned
    }
}
