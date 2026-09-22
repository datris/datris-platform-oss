package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.quartz.CronExpression

/** Tap cron validation (plans/stories/tap-cron-validation.md).
  *
  * A tap may not be saved with a cron expression the scheduler cannot parse.
  * The parser here is the one `TapScheduler` uses (`org.quartz.CronExpression`),
  * so "valid at save" is exactly "valid at schedule".
  *
  * A null / blank cron means "unscheduled" and never reaches the parser — blank
  * normalisation (`TapCronGate.normalizeCron`) runs first in the save path, and
  * this object treats blank as fine either way.
  *
  * No auto-conversion: Unix and Quartz disagree on day-of-week numbering and
  * Quartz requires exactly one of day-of-month / day-of-week to be `?`, so a
  * silent rewrite would schedule something the caller did not write. A 5-field
  * Unix cron gets the suggested 6-field form in the error instead, and the
  * caller re-submits it.
  */
object TapCronValidation {

    /** None when the cron is null / blank (unscheduled) or parses; otherwise the
      * refusal message. */
    def check(name: String, cron: String): Option[String] = {
        if (cron == null || cron.trim.isEmpty) return None
        if (CronExpression.isValidExpression(cron)) return None
        val base =
            "Tap '" + name + "' has an invalid cronExpression '" + cron + "'. " +
                "Datris schedules use Quartz cron: 6 fields (seconds minutes hours day-of-month month day-of-week) " +
                "plus optional year, e.g. '0 0 * * * ?' = hourly."
        Some(unixHint(cron) match {
            case Some(hint) => base + " This looks like a 5-field Unix cron; the 6-field equivalent is '" + hint + "'."
            case None => base
        })
    }

    /** Same message as `check`, thrown. Callers that map exceptions themselves
      * (or want the save path to abort) use this; the REST controllers prefer
      * `check` so they can answer 400 rather than the catch-all 500. */
    def validate(name: String, cron: String): Unit =
        check(name, cron).foreach(msg => throw new DatrisException(msg))

    /** The refusal as a REST body: `{"error": "<message>"}`, built with Gson so
      * the submitted cron can carry quotes, backslashes or control characters
      * without producing a body that clients fail to parse (a malformed body
      * makes the MCP server's `json.loads` fallback report success on a 400). */
    def errorBody(message: String): String = {
        val body = new com.google.gson.JsonObject
        body.addProperty("error", message)
        body.toString
    }

    /** A 5-token (Unix) cron rewritten as its 6-field Quartz equivalent: a
      * leading `0` seconds field, `?` in whichever day field the Unix form left
      * as `*` (Quartz forbids `*` in both), and a numeric day-of-week
      * translated to day NAMES. Returned only when the rewrite itself parses,
      * so garbage never yields a hint. */
    def unixHint(cron: String): Option[String] = {
        if (cron == null) return None
        val fields = cron.trim.split("\\s+")
        if (fields.length != 5) return None
        val Array(minute, hour, dom, month, dow) = fields
        val (fixedDom, fixedDow) =
            if (dow == "*") (dom, "?")
            else if (dom == "*") ("?", translateDow(dow))
            else (dom, translateDow(dow))
        val candidate = Seq("0", minute, hour, fixedDom, month, fixedDow).mkString(" ")
        if (CronExpression.isValidExpression(candidate)) Some(candidate) else None
    }

    /** Unix day-of-week digits by value: Unix counts 0 (or 7) = Sunday … 6 =
      * Saturday, Quartz counts 1 = Sunday … 7 = Saturday. */
    private val UnixDayNames = Array("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    /** A numeric Unix day-of-week rewritten as Quartz day NAMES, keeping `-`,
      * `,` and `/` structure (`1-5` → `MON-FRI`, `0` and `7` → `SUN`, `1,3,5` →
      * `MON,WED,FRI`). Copying the digits through would be valid Quartz with
      * DIFFERENT semantics — the two calendars are off by one — so a hint the
      * caller resubmits verbatim would fire on the wrong days. A `/` step value
      * is not a day: only the base before the `/` is translated. Fields with no
      * digits (`MON-FRI`, `*`, `?`) are already Quartz and pass through. */
    private def translateDow(field: String): String = {
        if (!field.exists(_.isDigit)) return field
        val slash = field.indexOf('/')
        val base = if (slash >= 0) field.substring(0, slash) else field
        val step = if (slash >= 0) field.substring(slash) else ""
        val translated = "\\d+".r.replaceAllIn(
            base,
            m => {
                val n = m.matched.toInt
                if (n >= 0 && n < UnixDayNames.length) UnixDayNames(n) else m.matched
            }
        )
        translated + step
    }
}
