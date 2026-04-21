package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.DatrisEnvironment
import com.google.gson.Gson
import org.slf4j.{Logger, LoggerFactory}

case class TapReviewResult(script: String,
                           packages: java.util.List[String],
                           scriptPath: String,
                           changes: java.util.List[String],
                           rewritten: Boolean)

object TapScriptReviewer {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val SYSTEM_PROMPT =
        """You are a Python tap script reviewer. You receive a WORKING tap script that just passed a test, along with its captured stderr/stdout. Your ONLY job is to look at the script output for signals that the script itself should change, and regenerate it if so. Do NOT make performance changes — a separate step handles that.
          |
          |STEP 1 — SCAN the output for signals. Match liberally, case-insensitively, and on substance rather than exact wording. Paraphrases count. If any of these categories of signal appear, you MUST act:
          |  (a) Rate-limit / throttle / burst — any language about the script sending too many requests, being asked to spread requests out, a per-second/per-minute ceiling, burst patterns, quota exceeded, retry-after, HTTP 429, back-off, "please slow down", "please wait".
          |      -> Regenerate with: `time.sleep(...)` between per-item external requests (use the stated cap when given, e.g. 0.25s for a "no more than 5 requests per second" message; otherwise 0.2-0.5s). If the script uses a ThreadPoolExecutor, lower max_workers to match the stated cap or down to 3 for safety. Add a short adaptive backoff on HTTP 429. Never add or raise concurrency.
          |  (b) Deprecation / migration — "deprecated", "will be removed", "use endpoint v2", "renamed to", "this field will be removed".
          |      -> Regenerate using the recommended replacement API / library / endpoint / field.
          |  (c) Pagination / partial-response — "truncated", "page N of M", "next_cursor", "has_more", "continuation token", "results limited to".
          |      -> Add pagination if missing. Keep fetching until the source says no more.
          |  (d) Schema-drift / auth — "field X removed/renamed", "missing scope", "token expired", "unauthorized", "permission denied", "invalid credentials".
          |      -> Update parsing for schema changes. For auth issues, do NOT guess credentials — add a clear stderr note naming the suspect env var and leave auth paths readable.
          |
          |STEP 2 — DECIDE:
          |  - Signal present -> set "rewritten": true and return the regenerated script.
          |  - No signal at all -> set "rewritten": false and return the input script unchanged.
          |  When borderline: FAVOR regenerating. Adding a small throttle is cheap; leaving the user hitting rate limits is expensive.
          |
          |STEP 3 — RETURN JSON:
          |  {
          |    "rewritten": true|false,
          |    "script": "<full Python 3 script, unchanged if rewritten=false>",
          |    "packages": [...],
          |    "changes": ["one bullet per change, 1-4 total"]
          |  }
          |
          |HARD PRESERVATION RULES:
          |  - Keep the fetch() signature and return shape.
          |  - Keep os.environ.get(...) reads unchanged.
          |  - Keep DATRIS_TAP_TEST_LIMIT / sample_cap / source_limit handling.
          |  - Keep request retries, 404 skip, stderr logging.
          |  - Do NOT change WHAT the script fetches, only HOW (when regenerating).
          |  - Do NOT introduce async/await. Thread-based concurrency only.
          |  - Do NOT silence or strip the source's warning lines — they are diagnostics.
          |  - Do NOT add deps outside the pre-installed set unless listed in "packages": requests, beautifulsoup4, pandas, lxml, feedparser, boto3, pyyaml, openpyxl, python-dateutil, pytz, google-cloud-storage, azure-storage-blob.
          |
          |Return ONLY the JSON object, no markdown fences or commentary.""".stripMargin

    /** Review a working tap script in light of its captured stderr/stdout. If the output
      * contains signals that the script itself should change (rate limits, deprecations,
      * pagination hints, schema/auth warnings), regenerate the script and persist the new
      * version to MinIO. Otherwise return the input script unchanged with rewritten=false. */
    def review(tapName: String,
               script: String,
               recordCount: Int,
               durationMs: Long,
               logs: String,
               oldScriptPath: String): TapReviewResult = {

        val logsSection = {
            val trimmed = Option(logs).getOrElse("").trim
            if (trimmed.isEmpty) "Recent script output:\n(no output captured)\n"
            else {
                val lines = trimmed.split("\n")
                val combined =
                    if (lines.length <= 80) trimmed
                    else (lines.take(20).mkString("\n") + "\n...\n" + lines.takeRight(60).mkString("\n"))
                s"Recent script output (SCAN THIS FIRST for rate-limit / deprecation / pagination / schema warnings):\n$combined\n"
            }
        }

        val userPrompt =
            s"""$logsSection
               |Current tap script (working — returned $recordCount records in $durationMs ms):
               |$script
               |
               |Review the output for signals the script should change. If none, return rewritten=false and the script unchanged.""".stripMargin

        val codegenCfg = DatrisEnvironment.aiConfigForCodegen
        val augmentedSystemPrompt = TapPromptInjector.augment(SYSTEM_PROMPT, script)
        logger.info(s"TapScriptReviewer: reviewing tap '$tapName' (${Option(logs).map(_.length).getOrElse(0)} chars of log output)")
        val responseText = AIUtil.callAIWithSystem(augmentedSystemPrompt, userPrompt, codegenCfg)
        val extracted = AIUtil.extractText(responseText, codegenCfg)
        val cleaned = cleanAIResponse(extracted)

        val jsonStr = {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        }

        val (rewritten, reviewedScript, packages, changes) = try {
            val gson = new Gson
            val result = gson.fromJson(jsonStr, classOf[java.util.Map[String, Any]])
            val r = Option(result.get("rewritten")).exists(_.toString.toLowerCase == "true")
            val s = Option(result.get("script")).map(_.toString).filter(_.trim.nonEmpty).getOrElse(script)
            val p = toStringList(result.get("packages"))
            val c = toStringList(result.get("changes"))
            (r, s, p, c)
        } catch {
            case _: Exception =>
                logger.info("TapScriptReviewer: AI response was not parseable JSON, treating as no-op")
                (false, script, new java.util.ArrayList[String](), new java.util.ArrayList[String]())
        }

        if (!rewritten || reviewedScript == script) {
            logger.info(s"TapScriptReviewer: no functional changes needed for tap '$tapName'")
            return TapReviewResult(script, new java.util.ArrayList[String](), oldScriptPath, new java.util.ArrayList[String](), rewritten = false)
        }

        val newScriptPath = TapScriptGenerator.storeScript(tapName, reviewedScript, oldScriptPath)
        logger.info(s"TapScriptReviewer: regenerated tap '$tapName' from script output signals; new path: $newScriptPath")
        TapReviewResult(reviewedScript, packages, newScriptPath, changes, rewritten = true)
    }

    private def cleanAIResponse(response: String): String = {
        var cleaned = response.trim
        if (cleaned.startsWith("```json")) cleaned = cleaned.stripPrefix("```json").trim
        else if (cleaned.startsWith("```")) cleaned = cleaned.stripPrefix("```").trim
        if (cleaned.endsWith("```")) cleaned = cleaned.stripSuffix("```").trim
        cleaned
    }

    private def toStringList(v: Any): java.util.List[String] = {
        v match {
            case null => new java.util.ArrayList[String]()
            case list: java.util.List[_] =>
                val out = new java.util.ArrayList[String]()
                val it = list.iterator()
                while (it.hasNext) out.add(it.next().toString)
                out
            case _ => new java.util.ArrayList[String]()
        }
    }
}
