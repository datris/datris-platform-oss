package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import com.google.gson.JsonParser
import org.slf4j.{Logger, LoggerFactory}

/** AI fix suggestion for a failed run.
  *
  * `summary` is a one-line plain-English headline sized for a list row;
  * `diagnosis` explains the root cause; `suggestion` says concretely what to
  * change. All best-effort: callers must tolerate null results.
  */
case class FixSuggestion(summary: String, diagnosis: String, suggestion: String)

/** Diagnoses a failed tap or pipeline run and proposes a fix. Shared by the
  * pipeline-side failure path (JobRunner) and the tap-side cron retry ladder
  * (TapScheduler, after retries are exhausted). One LLM call per failure —
  * advisory only, nothing is applied automatically.
  */
object FixSuggestionUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    private val MaxFieldChars = 2000
    private val MaxSummaryChars = 160

    /** @param kind         "pipeline" or "tap" — only used to phrase the prompt
      * @param configJson   the failing entity's configuration as JSON (no secret values)
      * @param errorMessage the error text / stack trace
      * @param extraContext optional additional context (e.g. script output logs); may be null
      * @return a FixSuggestion, or null when AI is disabled or the call/parse fails
      */
    def suggest(kind: String, configJson: String, errorMessage: String, extraContext: String = null): FixSuggestion = {
        try {
            if (!DatrisEnvironment.current.aiEnabled || DatrisEnvironment.current.aiConfig == null)
                return null

            val prompt =
                s"""You are a data ${kind} error analyst. A ${kind} run failed with the error below.
                   |Respond with ONLY a JSON object, no markdown fences, in exactly this shape:
                   |{"summary": "one plain-English sentence, under 120 characters, naming the root cause and the fix direction",
                   | "diagnosis": "2-3 sentences explaining what went wrong and why",
                   | "suggestion": "2-3 sentences saying concretely what to change (which configuration field, instruction, or credential) and to what"}
                   |Do NOT repeat the raw error message. Do not include server file paths or hostnames.
                   |
                   |${kind.capitalize} configuration:
                   |${truncate(configJson)}
                   |
                   |Error:
                   |${truncate(errorMessage)}${extraSection(extraContext)}""".stripMargin

            val responseText = AIUtil.extractText(AIUtil.callAI(prompt))
            parse(responseText)
        } catch {
            case e: Exception =>
                logger.warn("Failed to get AI fix suggestion (best-effort, continuing without it)", e)
                null
        }
    }

    private def extraSection(extraContext: String): String =
        if (extraContext == null || extraContext.trim.isEmpty) ""
        else "\n\nRun output (may contain the real failure):\n" + truncate(extraContext)

    private def truncate(s: String): String = {
        if (s == null) ""
        else if (s.length > MaxFieldChars) s.substring(0, MaxFieldChars) + "..."
        else s
    }

    /** Parse the model's JSON reply. Falls back to treating the whole reply as
      * the diagnosis when it isn't valid JSON — a degraded suggestion beats none. */
    private def parse(responseText: String): FixSuggestion = {
        if (responseText == null || responseText.trim.isEmpty) return null
        val cleaned = responseText.trim
            .replaceAll("(?s)^```(?:json)?\\s*", "")
            .replaceAll("(?s)\\s*```$", "")
        try {
            val obj = new JsonParser().parse(cleaned).getAsJsonObject
            def field(name: String): String =
                if (obj.has(name) && !obj.get(name).isJsonNull) obj.get(name).getAsString else null
            val summary = field("summary")
            val diagnosis = field("diagnosis")
            val suggestion = field("suggestion")
            if (summary == null && diagnosis == null && suggestion == null) null
            else FixSuggestion(clip(if (summary != null) summary else diagnosis), diagnosis, suggestion)
        } catch {
            case _: Exception =>
                FixSuggestion(clip(cleaned), cleaned, null)
        }
    }

    private def clip(s: String): String = {
        if (s == null) null
        else {
            val oneLine = s.replaceAll("\\s+", " ").trim
            if (oneLine.length <= MaxSummaryChars) oneLine else oneLine.substring(0, MaxSummaryChars - 3) + "..."
        }
    }
}
