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
          |- Be completely self-contained
          |- Include 30-second timeouts for network requests
          |- If authentication is needed, use os.environ.get('KEY_NAME') to access credentials
          |- NEVER hardcode API keys, tokens, or passwords in the script
          |
          |Error handling — IMPORTANT:
          |- Let exceptions propagate from `fetch()`. Do NOT wrap the body of `fetch()` in `try/except: return []`. Do NOT swallow exceptions silently. The platform runs your script in a wrapper that captures the full traceback when `fetch()` raises, and the traceback is the only signal the user (and the AI diagnosis tool) have for debugging.
          |- Only catch an exception if you can actually recover from it AND the recovery does something more useful than `return []`. For example, retrying once with backoff is fine; falling back to an alternate endpoint is fine; suppressing the error and returning empty is NOT fine.
          |- If you must catch an exception in a partial-failure scenario (e.g. one row out of many fails to parse), use `print(f"...", file=sys.stderr)` to log the issue and `continue`. Never suppress without logging.
          |- Never use bare `except:` or `except Exception: pass`. Catch the specific exception type you expect.
          |
          |HTTP requests — IMPORTANT:
          |- Always set a `User-Agent` header on HTTP requests. Many sites (Wikipedia, GitHub raw, etc.) return 403 Forbidden to default Python `requests` user-agents. Use something like `headers={'User-Agent': 'Mozilla/5.0 (compatible; datris-tap/1.0)'}`.
          |- Always check `resp.status_code` or call `resp.raise_for_status()` before parsing the body.
          |
          |Pandas — IMPORTANT (modern API):
          |- The platform runs pandas 2.x. When parsing HTML you have already fetched (e.g. from `requests.get(...).text`), you MUST wrap the string in `io.StringIO`: `pd.read_html(io.StringIO(resp.text), ...)`. Passing the raw string directly was deprecated in pandas 2.1 and now raises a parser error because lxml treats it as a file path. Add `import io` at the top of the script when you do this.
          |- The same rule applies to `pd.read_csv` and `pd.read_json` when given a string of content rather than a path or URL — wrap in `io.StringIO`.
          |- When extracting integer columns from a pandas DataFrame, be aware that any NaN in a numeric column promotes the entire column to `float64`. If you cast a value with `int(x)`, it will produce a Python int — but if you let JSON serialize a `numpy.float64` directly it will emit `2880264.0`. Always cast numeric values to Python `int`/`float`/`str` before adding to the record dict.
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
          |Metadata discovery (GET requests). Response shapes are EXACT and STABLE — trust them, do not probe alternate shapes or keys:
          |- GET /api/v1/metadata/postgres/databases → JSON array of strings (database names)
          |- GET /api/v1/metadata/postgres/schemas?database={pg_db} → JSON array of strings (schema names)
          |- GET /api/v1/metadata/postgres/tables?database={pg_db}&schema=public → JSON array of strings (table names)
          |- GET /api/v1/metadata/postgres/columns?database={pg_db}&schema=public&table=TABLE → JSON array of {name, type} objects
          |- GET /api/v1/metadata/mongodb/databases → JSON array of strings (database names)
          |- GET /api/v1/metadata/mongodb/collections?database={mongo_db} → JSON array of strings (collection names)
          |
          |Query endpoints (POST requests). Response shapes are EXACT:
          |- PostgreSQL: POST /api/v1/query/postgres
          |  Body: {"sql": "SELECT * FROM public.table_name", "database": "{pg_db}", "limit": -1}
          |  Response: object with keys `results` (array of row dicts) and `count` (int)
          |- MongoDB: POST /api/v1/query/mongodb
          |  Body: {"query": "...", "database": "{mongo_db}", "collection": "collection_name", "limit": -1}
          |  Response: object with keys `results` (array of document dicts) and `count` (int)
          |
          |Tap scripts MUST always pass `"limit": -1` on both endpoints (but honor the test-sample env var below):
          |- `-1` tells the server to return every matching row. It is the correct value for real cron/manual runs.
          |- Omitting `limit` makes the server apply a preview default (20 for Mongo, 100 for Postgres) and the tap will silently read a tiny slice. Do not rely on the default.
          |- There is no pagination on these endpoints. A single call returns the full result set; design the rest of the script accordingly (if a source is very large, iterate as you go rather than accumulating every intermediate value).
          |
          |Test-sample environment variable `DATRIS_TAP_TEST_LIMIT`:
          |- When the user enables "Limit test sample" in the Create Tap test UI, the runner injects `DATRIS_TAP_TEST_LIMIT` (an integer string, e.g. "20") into the script process. Cron and manual runs never set this variable.
          |- If `DATRIS_TAP_TEST_LIMIT` is set, the script MUST cap its work at that many records: use it as the `limit` on `/query/*` bodies (instead of -1) AND break out of any per-item iteration (per-ticker, per-user, per-page, etc.) after that many items. If unset or empty, pass `limit: -1` and iterate unbounded.
          |- Required pattern at the top of `fetch()`:
          |    _tl = os.environ.get('DATRIS_TAP_TEST_LIMIT')
          |    sample_cap = int(_tl) if _tl else None            # None = unlimited
          |    source_limit = sample_cap if sample_cap is not None else -1
          |  Use `source_limit` in /query/* request bodies. Use `sample_cap` to bound per-item loops (e.g. `for i, x in enumerate(items): if sample_cap is not None and i >= sample_cap: break`).
          |
          |Where {pg_db} = os.environ.get('DATRIS_POSTGRES_DATABASE') and {mongo_db} = os.environ.get('DATRIS_MONGODB_DATABASE').
          |
          |Do NOT write defensive shape-probing code for platform endpoints:
          |- Do NOT branch on `isinstance(resp.json(), list)` vs dict. The shapes above are contractual.
          |- Do NOT iterate candidate response keys (e.g. trying 'results', then 'data', then 'items'). The documented key is the only key.
          |- Do NOT probe alternate names for objects the user told you exist. If discovery returns nothing matching, raise a clear error that lists what was searched and what was found.
          |- Do NOT guess field names across multiple candidate keys in source documents. If the source's document shape is genuinely unknown, fetch one sample, log its keys to stderr with `print(..., file=sys.stderr)`, and raise with that context so the user can adjust. The AI diagnosis tool reads stderr.
          |- When a response shape surprises you, raise the exception. A clear traceback is better than silent wrong data.
          |
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

        // Call AI to generate the script — use codegen config (falls back to main aiConfig when unset)
        val codegenCfg = DatrisEnvironment.aiConfigForCodegen
        val responseText = AIUtil.callAIWithSystem(SYSTEM_PROMPT, userPrompt, codegenCfg)
        val extracted = AIUtil.extractText(responseText, codegenCfg)
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
                    val retryText = AIUtil.extractText(AIUtil.callAIWithSystem(retrySystemPrompt, retryUserPrompt, codegenCfg), codegenCfg)
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
