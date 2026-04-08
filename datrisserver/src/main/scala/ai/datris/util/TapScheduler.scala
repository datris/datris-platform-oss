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
                    val shouldRun = {
                        if (tap.lastRunTime == null) {
                            // Never run before — wait for the first scheduled time
                            false
                        } else {
                            val sdf = new SimpleDateFormat(DatrisEnvironment.current.dateFormat)
                            sdf.setTimeZone(TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
                            val lastRun = sdf.parse(tap.lastRunTime)
                            val nextRun = cron.getNextValidTimeAfter(lastRun)
                            now.after(nextRun)
                        }
                    }

                    if (shouldRun) {
                        logger.info("TapScheduler: triggering scheduled tap: " + tap.name)
                        // Run on a background thread to avoid blocking the scheduler
                        val thread = new Thread(() => {
                            try {
                                TapRunner.run(tap, pushToPipeline = true)
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
