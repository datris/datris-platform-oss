package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.IterationRecord
import com.google.gson.{Gson, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

/** Builds the "PRIOR ATTEMPTS" block that gets prepended to fix/optimize/review
  * system prompts. Without this, each LLM call sees only the latest broken
  * state and can cycle through the same failed strategies repeatedly. */
object IterationHistoryPromptBuilder {

    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Cap how many prior attempts we surface to the model. Beyond this the
      * prompt grows unboundedly and the model gets paralyzed by old failures. */
    val MAX_HISTORY_DEPTH: Int = 3

    /** Parse a JSON array of iteration records. Returns Nil on any parse
      * failure — never crash the caller because of malformed history. */
    def parseFromJson(json: String): List[IterationRecord] = {
        if (json == null || json.trim.isEmpty || json.trim == "[]" || json.trim == "null") return Nil
        try {
            val gson = new Gson
            val arr = JsonParser.parseString(json).getAsJsonArray
            arr.iterator.asScala.toList.map(el => gson.fromJson(el, classOf[IterationRecord])).filter(_ != null)
        } catch {
            case e: Exception =>
                logger.warn("Failed to parse iteration history JSON, ignoring: " + e.getMessage)
                Nil
        }
    }

    /** Build the prompt block. Returns "" when history is empty so callers can
      * concatenate unconditionally without conditionally inserting separators. */
    def build(history: List[IterationRecord]): String = {
        if (history == null || history.isEmpty) return ""
        val capped = history.takeRight(MAX_HISTORY_DEPTH)
        val sections = capped.reverse.map(formatRecord)
        val header = "PRIOR ATTEMPTS in this session (most recent first):\n\n"
        val footer =
            "\n\nCRITICAL guidance from prior attempts:\n" +
                "- Do NOT repeat fixes that already failed. If a strategy was tried and produced the same class of error, choose a different approach.\n" +
                "- Preserve constraints introduced by prior iterations (e.g. rate-limit awareness, retry logic, burst protection, pagination handling). Their presence means they were either user-requested or load-bearing — removing them re-introduces the original failure.\n" +
                "- If the prior diagnosis was wrong, identify why and pursue a different root cause.\n\n"
        header + sections.mkString("\n\n") + footer
    }

    private def formatRecord(rec: IterationRecord): String = {
        val outcomeStr = Option(rec.outcome).getOrElse("unknown").toUpperCase
        val triggerStr = Option(rec.trigger).getOrElse("unknown")
        val sb = new StringBuilder
        sb.append(s"ATTEMPT ${rec.attempt} ($triggerStr, $outcomeStr):\n")
        sb.append(s"  Outcome: $outcomeStr (${rec.recordCount} records, ${rec.durationMs}ms)\n")
        if (rec.appliedChange != null && rec.appliedChange.trim.nonEmpty)
            sb.append(s"  What was tried: ${rec.appliedChange.trim}\n")
        if (rec.error != null && rec.error.trim.nonEmpty)
            sb.append(s"  Error encountered: ${truncate(rec.error, 800)}\n")
        if (rec.diagnosis != null && rec.diagnosis.trim.nonEmpty)
            sb.append(s"  Prior diagnosis: ${truncate(rec.diagnosis, 800)}\n")
        if (rec.scriptDigest != null && rec.scriptDigest.trim.nonEmpty)
            sb.append(s"  Script tried (truncated):\n${indent(truncate(rec.scriptDigest, 1500), "    ")}\n")
        sb.toString.stripLineEnd
    }

    private def truncate(s: String, max: Int): String = {
        if (s == null) return ""
        if (s.length <= max) s.trim else s.substring(0, max).trim + "…"
    }

    private def indent(s: String, prefix: String): String =
        s.split("\n").map(prefix + _).mkString("\n")
}
