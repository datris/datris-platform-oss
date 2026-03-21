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
import ai.datris.util.{DataUtil, DatasetMetadataUtil}
import org.slf4j.{Logger, LoggerFactory}

class FileNotifier {
    private val logger: Logger = LoggerFactory.getLogger(classOf[FileNotifier])
    private val statusUtil = new StatusUtil().init(DatrisEnvironment.values.datasetStatusTableName, this.getClass.getSimpleName)

    def process(bucket: String, key: String): JobContext = {
        logger.info("Processing queue message, bucket: " + bucket + ", key: " + key)
        statusUtil.setFilename(bucket + "/" + key)

        try {
            // Generate a UUID to track the dataset through the pipeline
            val pipelineToken = GuidV5.nameUUIDFrom(System.currentTimeMillis().toString).toString
            statusUtil.setPipelineToken(pipelineToken)

            val metadata = new DatasetMetadataUtil(statusUtil).read(bucket, key)
            statusUtil.setFilename(metadata)
            statusUtil.setPublisherToken(metadata.publisherToken)

            // Save the metadata in NoSQL
            val gson = new Gson
            val jsonMetadata = gson.toJson(metadata)
            NoSQLDbUtil.setItemNameValue(DatrisEnvironment.values.archivedMetadataTableName, "pipeline_token", pipelineToken, "metadata", jsonMetadata)

            statusUtil.info("begin", "Data received, bucket: " + bucket + ", key: " + key)

            val config = DatasetConfigIO.read(DatrisEnvironment.values.datasetTableName, metadata.dataset)
            if(config == null)
                throw new DatrisException("Dataset: " + metadata.dataset + " is not configured in the NoSQL database")

            // Read the data into memory
            val data = DataUtil.read(bucket, key, config, metadata, statusUtil)
            statusUtil.info("processing", "Total file size: " + data.size.toString)

            statusUtil.info("end", "Process completed successfully")

            JobContext(pipelineToken, metadata, data, config, null, INITIALIZED, null, statusUtil)
        } catch {
            case e: Exception =>
                statusUtil.error("end", "Process completed, error: " + Throwables.getStackTraceAsString(e))
                throw new DatrisException("FileNotifier error: " +Throwables.getStackTraceAsString(e))
        }
    }
}