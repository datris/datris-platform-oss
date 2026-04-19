package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.DatrisEnvironment
import com.google.gson.Gson
import org.slf4j.{Logger, LoggerFactory}

case class TapOptimizeResult(script: String,
                             packages: java.util.List[String],
                             scriptPath: String,
                             changes: java.util.List[String])

object TapScriptOptimizer {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val SYSTEM_PROMPT =
        """You are a Python performance tuner. You will receive a WORKING Python tap script that successfully fetched data, along with its runtime metrics. Your job is to restructure the script so it runs faster while producing IDENTICAL output.
          |
          |Return a JSON object with three fields:
          |- "script": the complete optimized Python 3 script (must still define a `fetch()` function)
          |- "packages": list of EXACT pip install package names needed beyond the pre-installed set (requests, beautifulsoup4, pandas, lxml, feedparser, boto3, pyyaml, openpyxl, python-dateutil, pytz, google-cloud-storage, azure-storage-blob). Empty list if none.
          |- "changes": array of 1-5 short bullets describing what you changed (e.g. "Parallelized ticker fetches with ThreadPoolExecutor(10)", "Removed 0.25s per-item sleep"). If no useful optimization is possible, return an empty array and the script unchanged.
          |
          |HARD PRESERVATION RULES (must not change):
          |- Keep the `fetch()` function signature and its return shape.
          |- Keep all `os.environ.get(...)` reads for Vault-injected secrets.
          |- Keep the `DATRIS_TAP_TEST_LIMIT` env-var handling and the `sample_cap` / `source_limit` convention — test runs must still cap sample size.
          |- Keep existing defensive error handling: request retries, HTTP 429 `Retry-After` honoring, 404 skip, stderr progress logging, session/connection reuse if already present.
          |- Do NOT change WHAT the script fetches — only HOW. If correctness and performance conflict, keep correctness.
          |- Do NOT silence exceptions to appear faster. Do NOT remove the `raise_for_status()` or explicit error checks.
          |- Do NOT rely on response-shape probing for Datris platform endpoints (`/api/v1/metadata/*`, `/api/v1/query/*`) — their shapes are contractual.
          |- Do NOT introduce async/await. Use thread-based concurrency only (the runner is subprocess-based).
          |- Do NOT add new external dependencies outside the pre-installed set unless you list them in the "packages" field.
          |
          |ALLOWED OPTIMIZATION MOVES:
          |- Parallelize independent HTTP calls with `concurrent.futures.ThreadPoolExecutor(max_workers=10)` (cap at 10 to stay polite to upstream APIs).
          |- Reuse a single `requests.Session()` across calls if the script doesn't already.
          |- Drop per-item `time.sleep()` calls that are not protecting against a specific documented rate limit.
          |- Replace serial per-item calls with a bulk/batch endpoint when the target API obviously exposes one.
          |- Cache repeated lookups within a single `fetch()` invocation.
          |- Use `pandas` vectorized operations instead of Python for-loops over DataFrames.
          |
          |Return ONLY the JSON object, no markdown fences or commentary.""".stripMargin

    /**
     * Ask the LLM to optimize a working tap script. Persists the optimized script
     * to MinIO as a new version and returns the new path. The original script at
     * `oldScriptPath` is retained so callers can revert.
     *
     * When no useful optimization is possible, returns the input script unchanged
     * with an empty `changes` list and the same scriptPath.
     */
    def optimize(tapName: String,
                 script: String,
                 recordCount: Int,
                 durationMs: Long,
                 logs: String,
                 oldScriptPath: String): TapOptimizeResult = {
        val rateStats = if (recordCount > 0 && durationMs > 0)
            s" (~${durationMs / recordCount} ms/record)"
        else ""

        val logsTail = {
            val trimmed = Option(logs).getOrElse("").trim
            if (trimmed.isEmpty) ""
            else {
                val lines = trimmed.split("\n")
                val tail = if (lines.length > 40) lines.takeRight(40).mkString("\n") else trimmed
                s"\nRecent script output:\n$tail\n"
            }
        }

        val userPrompt =
            s"""Current tap script (working — returned $recordCount records in $durationMs ms$rateStats):
               |$script
               |$logsTail
               |Produce an optimized version. If the script is already well-optimized for its workload, return it unchanged with an empty "changes" array.""".stripMargin

        val codegenCfg = DatrisEnvironment.aiConfigForCodegen
        val responseText = AIUtil.callAIWithSystem(SYSTEM_PROMPT, userPrompt, codegenCfg)
        val extracted = AIUtil.extractText(responseText, codegenCfg)
        val cleaned = cleanAIResponse(extracted)

        val jsonStr = {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        }

        val (optimizedScript, packages, changes) = try {
            val gson = new Gson
            val result = gson.fromJson(jsonStr, classOf[java.util.Map[String, Any]])
            val s = Option(result.get("script")).map(_.toString).getOrElse(script)
            val p = toStringList(result.get("packages"))
            val c = toStringList(result.get("changes"))
            (s, p, c)
        } catch {
            case _: Exception =>
                logger.info("AI optimize response was not JSON, returning original script unchanged")
                (script, new java.util.ArrayList[String](), new java.util.ArrayList[String]())
        }

        val noChange = changes.isEmpty || optimizedScript == script
        val scriptPath = if (noChange) oldScriptPath
            else TapScriptGenerator.storeScript(tapName, optimizedScript, oldScriptPath)

        TapOptimizeResult(optimizedScript, packages, scriptPath, changes)
    }

    private def cleanAIResponse(response: String): String = {
        var cleaned = response.trim
        if (cleaned.startsWith("```json")) cleaned = cleaned.stripPrefix("```json").trim
        else if (cleaned.startsWith("```")) cleaned = cleaned.stripSuffix("```").trim
        if (cleaned.endsWith("```")) cleaned = cleaned.stripSuffix("```").trim
        cleaned
    }

    private def toStringList(raw: Any): java.util.List[String] = {
        val out = new java.util.ArrayList[String]()
        raw match {
            case list: java.util.List[_] =>
                val it = list.iterator()
                while (it.hasNext) out.add(it.next().toString)
            case _ => ()
        }
        out
    }
}
