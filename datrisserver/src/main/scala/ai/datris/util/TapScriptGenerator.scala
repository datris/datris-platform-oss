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
          |If the script needs to query or discover data from the Datris platform:
          |- Use os.environ.get('DATRIS_PLATFORM_HOST') and os.environ.get('DATRIS_PLATFORM_PORT') for host/port
          |- Use os.environ.get('DATRIS_DATABASE') for the database name
          |- Base URL: http://{host}:{port}/api/v1
          |
          |Metadata discovery (GET requests, all return JSON arrays):
          |- GET /api/v1/metadata/postgres/databases → list of database names
          |- GET /api/v1/metadata/postgres/schemas?database={db} → list of schema names
          |- GET /api/v1/metadata/postgres/tables?database={db}&schema=public → list of table names
          |- GET /api/v1/metadata/postgres/columns?database={db}&schema=public&table=TABLE → list of {name, type}
          |- GET /api/v1/metadata/mongodb/databases → list of database names
          |- GET /api/v1/metadata/mongodb/collections?database={db} → list of collection names
          |
          |Query endpoints (POST requests):
          |- PostgreSQL: POST /api/v1/query/postgres
          |  Body: {"sql": "SELECT * FROM public.table_name", "database": "{db}"}
          |  Response: {"results": [...list of row dicts...], "count": N}
          |- MongoDB: POST /api/v1/query/mongodb
          |  Body: {"query": "...", "database": "{db}", "collection": "collection_name"}
          |  Response: {"results": [...], "count": N}
          |
          |Where {db} = os.environ.get('DATRIS_DATABASE').
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

        // Parse the JSON response
        val gson = new Gson
        val result = gson.fromJson(cleaned, classOf[java.util.Map[String, Any]])
        if (result == null)
            throw new DatrisException("AI returned invalid response for tap script generation")

        val script = Option(result.get("script")).map(_.toString).getOrElse(
            throw new DatrisException("AI response missing 'script' field")
        )

        val packages: java.util.List[String] = {
            val raw = result.get("packages")
            if (raw == null) new java.util.ArrayList[String]()
            else {
                raw match {
                    case list: java.util.List[_] =>
                        val stringList = new java.util.ArrayList[String]()
                        val it = list.iterator()
                        while (it.hasNext) {
                            stringList.add(it.next().toString)
                        }
                        stringList
                    case _ => new java.util.ArrayList[String]()
                }
            }
        }

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
