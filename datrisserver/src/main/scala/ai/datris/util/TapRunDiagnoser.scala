package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import org.slf4j.{Logger, LoggerFactory}

object TapRunDiagnoser {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /**
     * Ask the chat model to explain a tap test-run outcome (failure, 0 records,
     * or noisy logs) in 2-3 plain-English sentences. Returns null when AI is
     * disabled or the diagnosis call itself fails — callers omit the explanation.
     */
    def explain(description: String, script: String, result: TapScriptResult): String = {
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
                   |${if (error.nonEmpty) "Error: " + error
                    else ""}
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

            val diagnoseQuery = "Diagnose this tap script error: " + error.take(500) + " | Description: " + description.take(200)
            val plan = AIUtil.planWebSearch(DatrisEnvironment.current.aiConfig, diagnoseQuery)
            val nativeNudge = plan match {
                case AIUtil.WebSearchPlan.Native =>
                    "\n\nA web_search tool is available. Use it ONLY when the diagnosis hinges on external information you don't already know — e.g. an unfamiliar exception message, a third-party API's current behavior, or a library deprecation notice. Skip it for routine Python errors or anything contradicted by the traceback already shown above."
                case _ => ""
            }
            val finalPrompt = prompt + nativeNudge + AIUtil.renderInjectedContext(plan)
            val responseText = AIUtil.callAI(finalPrompt, useWebSearch = AIUtil.useNative(plan))
            AIUtil.extractText(responseText).trim
        } catch {
            case e: Exception =>
                logger.warn("AI tap-run diagnosis failed; omitting explanation", e)
                null
        }
    }
}
