package ai.datris

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{ObjectStoreEventMessage, DatrisEnvironment, TenantContext}
import ai.datris.util.{NoSQLDbUtil, QueueUtil}
import ai.datris.controller.{FileNotifier, JobRunner}
import ai.datris.model._
import ai.datris.util.{DataPuller, TapScheduler}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

import java.util.Calendar
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

@Component
class ScheduledBatchTasks {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ScheduledBatchTasks])

    @Scheduled(fixedRateString = "${schedule.checkDatabaseSourceQueries}")
    private def checkForDatabaseSourceQueries(): Unit = {
        try {
            if (isAppInitialized) {
                new DataPuller().run()
            }
        } catch {
            case e: Exception =>
                logger.error("checkForDatabaseSourceQueries error: " + Throwables.getStackTraceAsString(e))
        }
    }

    @Scheduled(fixedRateString = "${schedule.checkTapSchedules}")
    private def checkTapSchedules(): Unit = {
        try {
            if (isAppInitialized) {
                TapScheduler.checkSchedules()
            }
        } catch {
            case e: Exception =>
                logger.error("checkTapSchedules error: " + Throwables.getStackTraceAsString(e))
        }
    }

    @Scheduled(fixedRateString = "${schedule.incidentSweep:300000}")
    private def incidentSweep(): Unit = {
        try {
            if (isAppInitialized) {
                ai.datris.incident.IncidentSweep.run()
            }
        } catch {
            case e: Exception =>
                logger.error("incidentSweep error: " + Throwables.getStackTraceAsString(e))
        }
    }

    @Scheduled(fixedRateString = "${schedule.scratchSweep:3600000}")
    private def scratchSweep(): Unit = {
        try {
            if (isAppInitialized) {
                ai.datris.util.ScratchSweeper.sweep(ai.datris.util.ScratchLoader.settingsFromEnvironment())
            }
        } catch {
            case e: Exception =>
                logger.error("scratchSweep error: " + Throwables.getStackTraceAsString(e))
        }
    }

    @Scheduled(fixedRateString = "${schedule.doctorTick:60000}")
    private def doctorTick(): Unit = {
        try {
            if (isAppInitialized) {
                ai.datris.util.DoctorMonitor.runIfDue()
            }
        } catch {
            case e: Exception =>
                logger.error("doctorTick error: " + Throwables.getStackTraceAsString(e))
        }
    }

    @Scheduled(fixedRateString = "${schedule.checkFileNotifierQueue}")
    private def checkFileNotifierQueue(): Unit = {
        try {
            if (isAppInitialized) {
                val messages = QueueUtil.receiveMessages(DatrisEnvironment.current.fileNotifierQueue, maxMessages = 10, longPolling = true)

                // Retry semantics (unchanged): receiveMessages acknowledges every message at receive time,
                // deleteMessage is a no-op, and hasMessageBeenProcessed writes the dedupe row before dispatch.
                // A file that fails is therefore not redelivered. The failure stays visible: FileNotifier.process
                // writes an error to the pipeline status before rethrowing, and dispatch logs the bucket/key.
                // Each message and each record is isolated so one bad file does not block the rest of the batch.
                messages.asScala.foreach(message => {
                    try {
                        QueueUtil.deleteMessage(DatrisEnvironment.current.fileNotifierQueue, message.receiptHandle)
                        val records = ScheduledBatchTasks.recordsOf(message)
                        if (records.nonEmpty && !hasMessageBeenProcessed(message.messageId))
                            ScheduledBatchTasks.dispatch(records, newFileReceived)
                    } catch {
                        case e: Exception =>
                            logger.error(
                                "checkFileNotifierQueue: failed messageId=" + message.messageId + ": " + Throwables.getStackTraceAsString(e)
                            )
                    }
                })
            }
        } catch {
            case e: Exception =>
                logger.error("checkFileNotifierQueue error: " + Throwables.getStackTraceAsString(e))
        }
    }

    private def hasMessageBeenProcessed(messageID: String): Boolean = {
        // Check the NoSQL table to determine if this message has already been processed
        val message = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.fileNotifierMessageTableName, "id", messageID, "value")
        if (message.isEmpty) {
            // Create a future TTL
            val now = Calendar.getInstance
            now.add(Calendar.DATE, DatrisEnvironment.current.ttlFileNotifierQueueMessages) // Days in future for TTL to delete this new entry from the table
            val epoch = now.getTime.getTime
            logger.info("File notifier queue message TTL: " + epoch.toString)

            // Write out the Message ID with the future TTL
            NoSQLDbUtil.setItemNameValue(DatrisEnvironment.current.fileNotifierMessageTableName, "id", messageID, "ttl", epoch.toString)
            false
        } else
            true
    }

    private def newFileReceived(bucket: String, key: String): Unit = {
        resolveEnvironmentFromBucket(bucket).foreach(env => TenantContext.set(env))
        try {
            val jobContext = new FileNotifier().process(bucket, key)
            GlobalJobContext.addJobContext(jobContext)
        } finally {
            TenantContext.clear()
        }
    }

    private def resolveEnvironmentFromBucket(bucket: String): Option[DatrisEnvironment] = {
        if (DatrisEnvironment.values.multiTenant) {
            val envName = bucket.replaceAll("-(raw|raw-plus|config|temp)$", "")
            if (envName != bucket && envName != DatrisEnvironment.values.environment) {
                Some(DatrisEnvironment.forEnvironment(envName))
            } else None
        } else None
    }

    @Scheduled(fixedRateString = "${schedule.findJobsToStart}")
    private def findJobsToStart(): Unit = {
        try {
            if (isAppInitialized) {
                startJobs()
                checkExistingJobs()
            }
        } catch {
            case e: Exception =>
                logger.error("findJobsToStart error: " + Throwables.getStackTraceAsString(e))
        }
    }

    private def startJobs(): Unit = {
        GlobalJobContext.getAll.foreach(jobContext => {
            if (jobContext.state == INITIALIZED) {
                if (!isDatabaseJobForPipelineAlreadyRunning(jobContext))
                    startJob(jobContext)
            }
        })

        // Show running jobs
        GlobalJobContext.getAll.foreach(jobContext => {
            if (jobContext.state == PROCESSING)
                logger.info(jobContext.pipelineToken + ": pipeline: " + jobContext.config.name + ", " + jobContext.state.toString)
        })
    }

    private def isDatabaseJobForPipelineAlreadyRunning(jobContext: JobContext): Boolean = {
        if (jobContext.config.destination.database != null) {
            // Find the jobs with the same database table name
            val jobContextsWithDbTableName = GlobalJobContext.getAll.flatMap(jc => {
                if (jc.config.destination.database != null && jc.config.destination.database.table.compareTo(jobContext.config.destination.database.table) == 0)
                    Some(jc)
                else
                    None
            }).toList

            // Do any exist that are running?
            jobContextsWithDbTableName.exists(_.state == PROCESSING)
        } else
            false
    }

    private def startJob(jobContext: JobContext): Unit = {
        logger.info("Starting job for the pipeline: " + jobContext.config.name)

        // Start the db loading process
        val thread = new Thread(new JobRunner(jobContext))
        thread.start()
        GlobalJobContext.replaceJobContext(jobContext = jobContext.copy(state = PROCESSING, thread = thread))
    }

    private def checkExistingJobs(): Unit = {
        GlobalJobContext.getAll.foreach(jobContext => {
            if (jobContext.state == PROCESSING && jobContext.thread != null && !jobContext.thread.isAlive) {
                logger.info(jobContext.pipelineToken + ": pipeline: " + jobContext.config.name + ", COMPLETED")
                GlobalJobContext.replaceJobContext(jobContext = jobContext.copy(state = COMPLETED))
            }
            // Clean up cancelled jobs whose threads have stopped
            if (jobContext.state == CANCELLED && (jobContext.thread == null || !jobContext.thread.isAlive)) {
                logger.info(jobContext.pipelineToken + ": pipeline: " + jobContext.config.name + ", CANCELLED (thread stopped)")
            }
        })
    }

    private def isAppInitialized: Boolean = {
        DatrisEnvironment != null && DatrisEnvironment.current != null && DatrisEnvironment.current.initialized
    }
}

object ScheduledBatchTasks {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ScheduledBatchTasks])

    /** Parse an object-store event message into (bucket, URL-decoded key) pairs, de-duplicated. Null-safe. */
    private[datris] def recordsOf(message: QueueMessage): Seq[(String, String)] = {
        val eventMessage = new Gson().fromJson(message.body, classOf[ObjectStoreEventMessage])
        if (eventMessage == null || eventMessage.Records == null) Seq.empty
        else {
            val pairs = eventMessage.Records.asScala.toSeq.map(record => {
                val key = URLDecoder.decode(record.s3.`object`.key, StandardCharsets.UTF_8.name())
                (record.s3.bucket.name, key)
            })
            // Collapse identical (bucket, key) pairs, keeping first-seen order. This replaces a .toMap that
            // built Map[bucket, key] and so kept only one key per bucket, dropping files in multi-record messages.
            pairs.distinct
        }
    }

    /** Call process for every record, isolating failures. Returns the number of records that failed. Never throws. */
    private[datris] def dispatch(records: Seq[(String, String)], process: (String, String) => Unit): Int = {
        var failures = 0
        records.foreach { case (bucket, key) =>
            try process(bucket, key)
            catch {
                case e: Exception =>
                    failures += 1
                    logger.error(
                        "checkFileNotifierQueue: failed bucket=" + bucket + " key=" + key + ": " + Throwables.getStackTraceAsString(e)
                    )
            }
        }
        failures
    }
}
