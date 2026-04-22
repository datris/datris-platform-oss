package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.DatrisEnvironment
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
                            Option(s).filter(_.nonEmpty).flatMap(v => try Some(sdf.parse(v)) catch { case _: Exception => None })
                        parseOpt(tap.lastRunTime)
                            .orElse(parseOpt(tap.updatedAt))
                            .orElse(parseOpt(tap.createdAt))
                            .getOrElse(new Date(0L))
                    }
                    val nextRun = cron.getNextValidTimeAfter(anchor)
                    val shouldRun = nextRun != null && now.after(nextRun)

                    if (shouldRun) {
                        logger.info("TapScheduler: triggering scheduled tap: " + tap.name)
                        // Run on a background thread to avoid blocking the scheduler
                        val thread = new Thread(() => {
                            try {
                                TapRunner.run(tap, mode = "run")
                            } catch {
                                case e: Exception =>
                                    logger.error("TapScheduler: error running tap: " + tap.name, e)
                            }
                        })
                        thread.setDaemon(true)
                        thread.start()
                    }
                } catch {
                    case e: Exception =>
                        logger.error("TapScheduler: invalid cron expression for tap: " + tap.name + ", cron: " + tap.cronExpression, e)
                }
            }
        })
    }
}
