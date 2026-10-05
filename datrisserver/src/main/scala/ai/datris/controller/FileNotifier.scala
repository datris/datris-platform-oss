package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model._
import ai.datris.util._
import ai.datris.model.{INITIALIZED, JobContext}
import ai.datris.util.{DataUtil, PipelineMetadataUtil}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID

class FileNotifier private[controller] (
    statusUtil: StatusUtil,
    archiveMetadata: (String, PipelineMetadata) => Unit,
    readConfig: String => PipelineConfig
) {
    // Defaults are resolved per instance, so production still picks up the
    // tenant's DatrisEnvironment.current set by newFileReceived.
    def this() = this(FileNotifier.defaultStatusUtil, FileNotifier.archiveMetadata, FileNotifier.readConfig)

    private val logger: Logger = LoggerFactory.getLogger(classOf[FileNotifier])

    def process(bucket: String, key: String): JobContext = {
        logger.info("Processing queue message, bucket: " + bucket + ", key: " + key)
        statusUtil.setFilename(bucket + "/" + key)
        // Generate a UUID to track the pipeline through the pipeline
        val pipelineToken = UUID.randomUUID().toString
        // StatusUtil resolves the pipeline name of a key without ".pipeline." in it
        // from the archived-metadata row, so no status event may be written before
        // that row exists; otherwise the status write throws and masks the real error.
        var metadataArchived = false

        try {
            statusUtil.setPipelineToken(pipelineToken)

            val metadata = new PipelineMetadataUtil(statusUtil).read(bucket, key)
            statusUtil.setFilename(metadata)
            statusUtil.setPublisherToken(metadata.publisherToken)

            // Save the metadata in NoSQL
            archiveMetadata(pipelineToken, metadata)
            metadataArchived = true

            statusUtil.info("begin", "Data received, bucket: " + bucket + ", key: " + key)

            val config = readConfig(metadata.pipeline)
            if (config == null)
                throw new DatrisException("Pipeline: " + metadata.pipeline + " is not configured in the NoSQL database")

            // Read the data (includes schema evolution). Staged under the run
            // token so JobRunner.run()'s finally removes the files with the run.
            val (data, resolvedConfig) = StagingArea.withToken(pipelineToken)(DataUtil.read(bucket, key, config, metadata, statusUtil))
            statusUtil.info("processing", "Total file size: " + data.size.toString)

            statusUtil.info("end", "Process completed successfully")

            JobContext(pipelineToken, metadata, data, resolvedConfig, null, INITIALIZED, null, statusUtil, DatrisEnvironment.current)
        } catch {
            case e: Exception =>
                // No JobContext exists yet, so no JobRunner finally will reclaim
                // whatever DataUtil.read staged before the failure.
                StagingArea.delete(pipelineToken)
                if (metadataArchived) {
                    // Plain words on the status; the full trace in the event's
                    // detail and in the log under the run token (the queue
                    // dispatcher logs bucket/key only, the Kafka path nothing).
                    logger.error(pipelineToken + ": ingest failed before the job started", e)
                    try statusUtil.error("end", "Process completed, error: " + ErrorText.messageChain(e), Throwables.getStackTraceAsString(e))
                    catch {
                        case inner: Exception =>
                            logger.warn("FileNotifier: could not write status event: " + inner.getMessage)
                    }
                } else {
                    // dispatch logs the full stack with bucket/key; keep this to one line.
                    logger.error(
                        "FileNotifier: cannot attribute bucket=" + bucket + " key=" + key +
                            " to a pipeline, no status event written: " + e.getMessage
                    )
                }
                throw e
        }
    }
}

object FileNotifier {
    private[controller] def defaultStatusUtil: StatusUtil =
        new StatusUtil().init(DatrisEnvironment.current.pipelineStatusTableName, classOf[FileNotifier].getSimpleName)

    private[controller] def archiveMetadata(pipelineToken: String, metadata: PipelineMetadata): Unit = {
        val jsonMetadata = new Gson().toJson(metadata)
        NoSQLDbUtil.setItemNameValue(DatrisEnvironment.current.archivedMetadataTableName, "pipeline_token", pipelineToken, "metadata", jsonMetadata)
    }

    private[controller] def readConfig(pipeline: String): PipelineConfig =
        PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
}
