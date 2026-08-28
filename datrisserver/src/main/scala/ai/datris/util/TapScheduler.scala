package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, TapConfig, TapRunLog}
import com.google.gson.Gson
import org.quartz.CronExpression
import org.slf4j.{Logger, LoggerFactory}

import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}

object TapScheduler {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def checkSchedules(): Unit = {
        val taps = TapConfigIO.readAll(DatrisEnvironment.current.tapTableName)
        val now = new Date()

        taps.foreach(tap => {
            if (tap.cronExpression != null && tap.enabled && tap.lastRunStatus != "running") {
                try {
                    val cron = new CronExpression(tap.cronExpression)
                    cron.setTimeZone(TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
                    val sdf = new SimpleDateFormat(DatrisEnvironment.current.dateFormat)
                    sdf.setTimeZone(TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
                    // Anchor for "next valid cron time". Prefer lastRunTime; fall back to
                    // updatedAt then createdAt so a tap with a cron but no prior run still
                    // fires on its next scheduled slot after creation. Epoch as a final
                    // fallback means "fire on the next valid cron time from now".
                    val anchor: Date = {
                        def parseOpt(s: String): Option[Date] =
                            Option(s).filter(_.nonEmpty).flatMap(v =>
                                try Some(sdf.parse(v))
                                catch {
                                    case e: Exception =>
                                        logger.debug("TapScheduler: unparseable anchor date '" + v + "' for tap: " + tap.name + ", trying next fallback", e)
                                        None
                                }
                            )
                        parseOpt(tap.lastRunTime)
                            .orElse(parseOpt(tap.updatedAt))
                            .orElse(parseOpt(tap.createdAt))
                            .getOrElse(new Date(0L))
                    }
                    val nextRun = cron.getNextValidTimeAfter(anchor)
                    val shouldRun = nextRun != null && now.after(nextRun)

                    if (shouldRun) {
                        logger.info("TapScheduler: triggering scheduled tap: " + tap.name)
                        fireCronRun(tap)
                    } else if (shouldRetry(tap, now, sdf)) {
                        logger.info(
                            "TapScheduler: retrying failed tap: " + tap.name +
                                " (attempt " + (tap.retryCount + 1) + "/" + DatrisEnvironment.current.cronRetryCap + ")"
                        )
                        fireCronRun(tap.copy(retryCount = tap.retryCount + 1))
                    }
                } catch {
                    case e: Exception =>
                        logger.error("TapScheduler: invalid cron expression for tap: " + tap.name + ", cron: " + tap.cronExpression, e)
                }
            }
        })
    }

    /** Run a tap on a background thread with trigger = "cron"; after the run
      * settles, generate a fix suggestion if the failure is final (no retries
      * left, or not safe to retry). */
    private def fireCronRun(tap: TapConfig): Unit = {
        val thread = new Thread(() => {
            val md = new com.google.gson.JsonObject()
            md.addProperty("trigger", "cron")
            md.addProperty("cron", tap.cronExpression)
            if (tap.retryCount > 0) md.addProperty("retryAttempt", tap.retryCount)
            try {
                TapRunner.run(tap, mode = "run", trigger = "cron")
                ai.datris.audit.AuditLog.system("tap", "run", "tap", tap.name, md)
            } catch {
                case e: Exception =>
                    logger.error("TapScheduler: error running tap: " + tap.name, e)
                    ai.datris.audit.AuditLog.system(
                        "tap",
                        "run",
                        "tap",
                        tap.name,
                        md,
                        outcome = "failure",
                        errorMessage = Option(e.getMessage).map(_.take(500)).orNull
                    )
            }
            try {
                maybeSuggestFix(tap.name)
            } catch {
                case e: Exception =>
                    logger.warn("TapScheduler: fix suggestion failed for tap: " + tap.name + " (best-effort, continuing)", e)
            }
        })
        thread.setDaemon(true)
        thread.start()
    }

    /** A failed cron run is retried while: retries are enabled, the run
      * provably fed nothing downstream (retry-safe), attempts remain, and the
      * backoff for the current attempt has elapsed. Manual runs never retry —
      * a manual run stamps lastRunTrigger and stops the ladder. */
    private def shouldRetry(tap: TapConfig, now: Date, sdf: SimpleDateFormat): Boolean = {
        val env = DatrisEnvironment.current
        if (!env.cronRetryEnabled) return false
        if (tap.lastRunStatus != "failure" || tap.lastRunTrigger != "cron") return false
        if (!tap.lastRunRetrySafe || tap.retryCount >= env.cronRetryCap) return false

        val lastRun =
            try sdf.parse(tap.lastRunTime)
            catch { case _: Exception => return false }
        now.getTime - lastRun.getTime >= backoffMs(tap.retryCount)
    }

    private def backoffMs(retryCount: Int): Long = {
        val minutes = DatrisEnvironment.current.cronRetryBackoffMinutes
            .split(",").toList.flatMap(s =>
                try Some(s.trim.toInt)
                catch { case _: Exception => None }
            )
        val effective = if (minutes.isEmpty) List(5, 15) else minutes
        effective(Math.min(retryCount, effective.length - 1)).toLong * 60000L
    }

    /** After a cron run ends in a *final* failure — not retry-safe, or retries
      * exhausted/disabled — diagnose it once and stamp the suggestion onto the
      * run's log row so the Run History UI can surface it. Each failed run
      * writes a fresh log row, so a row with no aiSummary is exactly a failure
      * that hasn't been diagnosed yet; no extra bookkeeping needed. */
    private def maybeSuggestFix(tapName: String): Unit = {
        val env = DatrisEnvironment.current
        val tap = TapConfigIO.read(env.tapTableName, tapName)
        if (tap == null || tap.lastRunStatus != "failure" || tap.lastRunTrigger != "cron") return

        val retriesPending = env.cronRetryEnabled && tap.lastRunRetrySafe && tap.retryCount < env.cronRetryCap
        if (retriesPending) return

        val key = tap.name + "|" + tap.lastRunTime
        val gson = new Gson
        val logJson = NoSQLDbUtil.getItemJSON(env.tapLogTableName, "key", key, "value").orNull
        val runLog = if (logJson != null) gson.fromJson(logJson, classOf[TapRunLog]) else null
        if (runLog == null || runLog.aiSummary != null) return

        // Tap config carries no secret values (only the secret *name*), so it is
        // safe prompt context as-is.
        val fix = FixSuggestionUtil.suggest("tap", gson.toJson(tap), tap.lastRunError, runLog.logs)
        if (fix == null) return

        val enriched = runLog.copy(aiSummary = fix.summary, aiDiagnosis = fix.diagnosis, aiSuggestion = fix.suggestion)
        NoSQLDbUtil.putItemJSON(env.tapLogTableName, "key", key, "value", gson.toJson(enriched), "created_at", System.currentTimeMillis(): java.lang.Long)
        logger.info("TapScheduler: fix suggestion recorded for tap: " + tap.name + " — " + fix.summary)
    }
}
