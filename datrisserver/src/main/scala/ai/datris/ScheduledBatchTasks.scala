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
            if(isAppInitialized) {
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
            if(isAppInitialized) {
                TapScheduler.checkSchedules()
            }
        } catch {
            case e: Exception =>
                logger.error("checkTapSchedules error: " + Throwables.getStackTraceAsString(e))
        }
    }

    @Scheduled(fixedRateString = "${schedule.checkFileNotifierQueue}")
    private def checkFileNotifierQueue(): Unit = {
        try {
            if(isAppInitialized) {
                val messages = QueueUtil.receiveMessages(DatrisEnvironment.current.fileNotifierQueue, maxMessages = 10, longPolling = true)

                val gson = new Gson
                messages.asScala.foreach(message => {
                    val eventMessage = gson.fromJson(message.body, classOf[ObjectStoreEventMessage])
                    QueueUtil.deleteMessage(DatrisEnvironment.current.fileNotifierQueue, message.receiptHandle)

                    if(eventMessage != null && eventMessage.Records != null) {
                        if(! hasMessageBeenProcessed(message.messageId, eventMessage))
                            eventMessage.Records.asScala.map(record => {
                                    val key = URLDecoder.decode(record.s3.`object`.key, StandardCharsets.UTF_8.name())
                                    (record.s3.bucket.name, key)
                                }).toMap
                                .foreach(record => {
                                    newFileReceived(record._1, record._2)
                                })
                        }
                    })
                }
        } catch {
            case e: Exception =>
                logger.error("checkFileNotifierQueue error: " + Throwables.getStackTraceAsString(e))
        }
    }

    private def hasMessageBeenProcessed(messageID: String, eventMessageS3: ObjectStoreEventMessage): Boolean = {
        // Check the NoSQL table to determine if this message has already been processed
        val message = NoSQLDbUtil.getItemJSON(DatrisEnvironment.current.fileNotifierMessageTableName, "id", messageID, "value")
        if(message.isEmpty) {
            // Create a future TTL
            val now = Calendar.getInstance
            now.add(Calendar.DATE, DatrisEnvironment.current.ttlFileNotifierQueueMessages) // Days in future for TTL to delete this new entry from the table
            val epoch = now.getTime.getTime
            logger.info("File notifier queue message TTL: " + epoch.toString)

            // Write out the Message ID with the future TTL
            NoSQLDbUtil.setItemNameValue(DatrisEnvironment.current.fileNotifierMessageTableName, "id", messageID, "ttl", epoch.toString)
            false
        }
        else
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
            if(isAppInitialized) {
                startJobs()
                checkExistingJobs()
            }
        }
        catch {
            case e: Exception =>
                logger.error("findJobsToStart error: " + Throwables.getStackTraceAsString(e))
        }
    }

    private def startJobs(): Unit = {
        GlobalJobContext.getAll.foreach(jobContext => {
            if(jobContext.state == INITIALIZED) {
                if(!isDatabaseJobForPipelineAlreadyRunning(jobContext))
                    startJob(jobContext)
            }
        })

        // Show running jobs
        GlobalJobContext.getAll.foreach(jobContext => {
            if(jobContext.state ==  PROCESSING)
                logger.info(jobContext.pipelineToken + ": pipeline: " + jobContext.config.name + ", " + jobContext.state.toString)
        })
    }

    private def isDatabaseJobForPipelineAlreadyRunning(jobContext: JobContext): Boolean = {
        if(jobContext.config.destination.database != null) {
            // Find the jobs with the same database table name
            val jobContextsWithDbTableName = GlobalJobContext.getAll.flatMap(jc => {
                if(jc.config.destination.database != null && jc.config.destination.database.table.compareTo(jobContext.config.destination.database.table) == 0)
                    Some(jc)
                else
                    None
            }).toList

            // Do any exist that are running?
            jobContextsWithDbTableName.exists(_.state == PROCESSING)
        }
        else
            false
    }

    private def startJob(jobContext: JobContext): Unit = {
        logger.info("Starting job for the pipeline: " + jobContext.config.name)

        // Start the db loading process
        val thread = new Thread(new JobRunner(jobContext))
        thread.start()
        GlobalJobContext.replaceJobContext(jobContext = jobContext.copy(state = PROCESSING, thread = thread))
    }

    private def checkExistingJobs(): Unit ={
        GlobalJobContext.getAll.foreach(jobContext => {
            if(jobContext.state == PROCESSING && jobContext.thread != null && !jobContext.thread.isAlive) {
                logger.info(jobContext.pipelineToken + ": pipeline: " + jobContext.config.name + ", COMPLETED")
                GlobalJobContext.replaceJobContext(jobContext = jobContext.copy(state = COMPLETED))
            }
            // Clean up cancelled jobs whose threads have stopped
            if(jobContext.state == CANCELLED && (jobContext.thread == null || !jobContext.thread.isAlive)) {
                logger.info(jobContext.pipelineToken + ": pipeline: " + jobContext.config.name + ", CANCELLED (thread stopped)")
            }
        })
    }

    private def isAppInitialized: Boolean = {
        DatrisEnvironment != null && DatrisEnvironment.current != null && DatrisEnvironment.current.initialized
    }
}

