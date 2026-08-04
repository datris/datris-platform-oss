package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import com.google.gson.Gson
import org.slf4j.{Logger, LoggerFactory}

case class TapFixResult(script: String, packages: java.util.List[String], scriptPath: String)

object TapScriptFixer {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /**
     * Ask the LLM to fix a broken tap script given a diagnosis and the captured
     * error/log output. Persists the fixed script to MinIO as a new version and
     * returns the new path along with any extra pip packages the fix requires.
     */
    def fix(
        tapName: String,
        script: String,
        diagnosis: String,
        logs: String,
        error: String,
        oldScriptPath: String,
        priorIterations: List[ai.datris.model.IterationRecord] = Nil
    ): TapFixResult = {
        val codegenCfg = DatrisEnvironment.aiConfigForCodegen

        val baseSystemPrompt =
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
              |PRESERVE the incremental-sync state handling if present: the `DATRIS_TAP_STATE` env-var read and the
              |`DATRIS_STATE` module-global assignment are the platform's bookmark contract between runs, not dead code.
              |Keep them working in the fixed script unless the diagnosis says they are the bug.
              |
              |Return ONLY the JSON object, no markdown fences or commentary.""".stripMargin

        // Errors of this shape mean the model's training-data view of the library
        // is stale or wrong (renamed methods, removed packages, version-skewed APIs).
        // Look up the current truth to ground the fix.
        val combinedError = error + " " + diagnosis + " " + logs
        val needsPackageLookup = Seq(
            "has no attribute",
            "AttributeError",
            "ModuleNotFoundError",
            "No module named",
            "No matching distribution",
            "ImportError"
        ).exists(combinedError.contains)

        // Two paths for resolving stale-library errors:
        //   1) Web search is enabled. Either attach the native tool (codegen provider
        //      matches the search provider) or run an out-of-band search and inject
        //      the results. Either way the model gets current PyPI / docs context.
        //   2) Fallback to the legacy hand-rolled scraper that hits pypi.org/json
        //      and pulls the docs URL out of project_urls. Kept verbatim so users
        //      without web search still get something.
        val plan = if (needsPackageLookup) AIUtil.planWebSearch(codegenCfg, "package documentation lookup for: " + diagnosis.take(500))
        else AIUtil.WebSearchPlan.Off
        val nativeFragment = plan match {
            case AIUtil.WebSearchPlan.Native =>
                """
                  |
                  |Web search tool — IMPORTANT for THIS fix:
                  |- The diagnosis indicates a stale or wrong library API. Use the `web_search`
                  |  tool to look up the package's current documentation BEFORE writing the fix:
                  |  the correct PyPI install name, the current method/attribute names, and any
                  |  recent breaking changes.
                  |- Search PyPI directly (`site:pypi.org <package>`) and the project's
                  |  documentation. Cite at least one URL for any non-trivial API contract you
                  |  rely on in the fix.""".stripMargin
            case _ => ""
        }
        val systemPrompt = baseSystemPrompt + nativeFragment + AIUtil.renderInjectedContext(plan)

        // Fall back to the legacy scraper only when web search produced nothing.
        val packageContext: String = plan match {
            case AIUtil.WebSearchPlan.Off if needsPackageLookup => PyPIContextUtil.fetchPackageContextFromPyPI(script)
            case _ => ""
        }

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

        val historyBlock = IterationHistoryPromptBuilder.build(priorIterations)
        val augmentedSystemPrompt = historyBlock + TapPromptInjector.augment(systemPrompt, diagnosis + "\n" + error + "\n" + script)
        val responseText = AIUtil.callAIWithSystem(augmentedSystemPrompt, userPrompt, codegenCfg, useWebSearch = AIUtil.useNative(plan))
        if (AIUtil.useNative(plan)) {
            val citations = AIUtil.extractCitations(responseText, codegenCfg)
            if (citations.nonEmpty)
                logger.info("/tap/fix: native web search consulted " + citations.size + " source(s): " +
                    citations.map { case (url, title) => "[" + title + "](" + url + ")" }.mkString(", "))
        }
        val extracted = AIUtil.extractText(responseText, codegenCfg)
        val cleaned = cleanAIResponse(extracted)

        // Extract JSON from the response — AI may include text before/after the JSON
        val jsonStr = {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
        }

        // Try parsing as JSON first; if that fails, treat the whole response as a script
        val (fixedScript, packages) =
            try {
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
                case e: Exception =>
                    // AI returned raw script instead of JSON — use it directly
                    logger.debug("AI fix response JSON parse failed", e)
                    logger.info("AI fix response was not JSON, treating as raw script")
                    (cleaned, new java.util.ArrayList[String]())
            }

        // Store fixed script in MinIO
        val scriptPath = TapScriptGenerator.storeScript(Option(tapName).getOrElse("tap"), fixedScript, oldScriptPath)

        TapFixResult(fixedScript, packages, scriptPath)
    }

    private def cleanAIResponse(response: String): String = {
        var cleaned = response.trim
        if (cleaned.startsWith("```json")) cleaned = cleaned.stripPrefix("```json").trim
        else if (cleaned.startsWith("```")) cleaned = cleaned.stripPrefix("```").trim
        if (cleaned.endsWith("```")) cleaned = cleaned.stripSuffix("```").trim
        cleaned
    }
}
