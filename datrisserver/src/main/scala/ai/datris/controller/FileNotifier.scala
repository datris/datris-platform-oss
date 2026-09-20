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

class FileNotifier {
    private val logger: Logger = LoggerFactory.getLogger(classOf[FileNotifier])
    private val statusUtil = new StatusUtil().init(DatrisEnvironment.current.pipelineStatusTableName, this.getClass.getSimpleName)

    def process(bucket: String, key: String): JobContext = {
        logger.info("Processing queue message, bucket: " + bucket + ", key: " + key)
        statusUtil.setFilename(bucket + "/" + key)
        // Generate a UUID to track the pipeline through the pipeline
        val pipelineToken = UUID.randomUUID().toString

        try {
            statusUtil.setPipelineToken(pipelineToken)

            val metadata = new PipelineMetadataUtil(statusUtil).read(bucket, key)
            statusUtil.setFilename(metadata)
            statusUtil.setPublisherToken(metadata.publisherToken)

            // Save the metadata in NoSQL
            val gson = new Gson
            val jsonMetadata = gson.toJson(metadata)
            NoSQLDbUtil.setItemNameValue(DatrisEnvironment.current.archivedMetadataTableName, "pipeline_token", pipelineToken, "metadata", jsonMetadata)

            statusUtil.info("begin", "Data received, bucket: " + bucket + ", key: " + key)

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, metadata.pipeline)
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
                statusUtil.error("end", "Process completed, error: " + Throwables.getStackTraceAsString(e))
                throw new DatrisException("FileNotifier error: " + Throwables.getStackTraceAsString(e))
        }
    }
}
