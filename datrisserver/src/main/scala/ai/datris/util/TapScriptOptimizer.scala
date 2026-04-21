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
        """You are a Python performance tuner. You will receive a WORKING Python tap script that successfully fetched data, along with its runtime metrics and captured script output. Your job is to restructure the script so it runs faster while producing IDENTICAL output.
          |
          |STEP 1 — READ THE SCRIPT OUTPUT FIRST.
          |Before touching the code, scan "Recent script output" for signals that constrain what optimizations are safe:
          |- Rate-limit warnings ("rate limit", "burst pattern", "too many requests", "please spread requests", "429", "quota", "throttle") → the upstream is already complaining; do NOT add parallelism. Instead ADD throttling (time.sleep between calls, lower concurrency, or request pacing) and note it in "changes".
          |- Retry-After / backoff messages → honor them explicitly with a sleep; never ignore.
          |- Deprecation warnings → replace the deprecated call with the current one.
          |- Auth/permission warnings → leave auth logic alone and note the issue in "changes" rather than working around it.
          |- Pagination/next-page hints → prefer fewer, larger requests over many small ones.
          |- Any "retrying", "timeout", "connection reset" → the API is stressed; err on slower/safer, not faster.
          |If the logs show a source explicitly asking the client to slow down, your optimization MUST reduce request pressure, even if that means the script runs longer. Correctness and politeness beat speed.
          |
          |STEP 2 — decide on a safe optimization consistent with step 1, then output.
          |
          |Return a JSON object with three fields:
          |- "script": the complete optimized Python 3 script (must still define a `fetch()` function)
          |- "packages": list of EXACT pip install package names needed beyond the pre-installed set (requests, beautifulsoup4, pandas, lxml, feedparser, boto3, pyyaml, openpyxl, python-dateutil, pytz, google-cloud-storage, azure-storage-blob). Empty list if none.
          |- "changes": array of 1-5 short bullets describing what you changed (e.g. "Parallelized ticker fetches with ThreadPoolExecutor(10)", "Removed 0.25s per-item sleep", "Added 0.25s sleep between calls — source reported burst-pattern warning"). If the logs make optimization unsafe or the script is already well-tuned, return an empty array and the script unchanged.
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
          |- Do NOT suppress or strip the warning/log lines the source emits — they're useful diagnostics.
          |
          |ALLOWED OPTIMIZATION MOVES (only when the log output doesn't forbid them):
          |- Parallelize independent HTTP calls with `concurrent.futures.ThreadPoolExecutor(max_workers=N)`. Default cap is 10, but LOWER it if the logs show rate-limit or burst warnings (e.g. 3 workers + a small sleep). NEVER raise concurrency past what the source has tolerated in this run.
          |- Reuse a single `requests.Session()` across calls if the script doesn't already.
          |- Drop per-item `time.sleep()` calls that are not protecting against a specific documented rate limit AND the logs show no rate-limit warnings.
          |- Replace serial per-item calls with a bulk/batch endpoint when the target API obviously exposes one.
          |- Cache repeated lookups within a single `fetch()` invocation.
          |- Use `pandas` vectorized operations instead of Python for-loops over DataFrames.
          |- ADD throttling (small sleep, lower max_workers, adaptive backoff) when the logs indicate the source is being stressed.
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

        // Keep head + tail so early warnings (e.g. "using deprecated endpoint",
        // "burst pattern detected") survive truncation alongside the recent lines.
        val logsSection = {
            val trimmed = Option(logs).getOrElse("").trim
            if (trimmed.isEmpty) "Recent script output:\n(no output captured)\n"
            else {
                val lines = trimmed.split("\n")
                val combined =
                    if (lines.length <= 80) trimmed
                    else (lines.take(20).mkString("\n") + "\n...\n" + lines.takeRight(60).mkString("\n"))
                s"Recent script output (SCAN THIS FIRST for rate-limit / burst / deprecation / retry warnings):\n$combined\n"
            }
        }

        val userPrompt =
            s"""$logsSection
               |Current tap script (working — returned $recordCount records in $durationMs ms$rateStats):
               |$script
               |
               |Produce an optimization that is consistent with any warnings in the script output above. If the logs show the source asking to slow down, ADD throttling rather than parallelism. If the script is already well-tuned for its workload, return it unchanged with an empty "changes" array.""".stripMargin

        val codegenCfg = DatrisEnvironment.aiConfigForCodegen
        val augmentedSystemPrompt = TapPromptInjector.augment(SYSTEM_PROMPT, script)
        val responseText = AIUtil.callAIWithSystem(augmentedSystemPrompt, userPrompt, codegenCfg)
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
