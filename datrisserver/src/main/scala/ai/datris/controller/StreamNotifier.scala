package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model._
import ai.datris.util._
import ai.datris.model.{Data, INITIALIZED, JobContext}
import ai.datris.util.CSVReader
import org.slf4j.{Logger, LoggerFactory}

import java.io.ByteArrayInputStream
import java.util.regex.Pattern
import scala.collection.JavaConverters._

class StreamNotifier {
    private val logger: Logger = LoggerFactory.getLogger(classOf[FileNotifier])
    private val statusUtil = new StatusUtil().init(DatrisEnvironment.values.datasetStatusTableName, this.getClass.getSimpleName)

    def process(byteArray: Array[Byte], filename: String, dataset: String, publisherToken: String): JobContext = {
        logger.info("StreamNotifier processing dataset: " + dataset + ", filename: " + filename)
        statusUtil.setFilename("stream: " + dataset)

        try {
            val pipelineToken = GuidV5.nameUUIDFrom(System.currentTimeMillis().toString).toString
            statusUtil.setPipelineToken(pipelineToken)
            statusUtil.setPublisherToken(Option(publisherToken).getOrElse(pipelineToken))

            val config = DatasetConfigIO.read(DatrisEnvironment.values.datasetTableName, dataset)
            if (config == null)
                throw new DatrisException("Dataset: " + dataset + " is not configured in the NoSQL database")

            val metadata = DatasetMetadata(dataset, filename, null, Option(publisherToken).getOrElse(pipelineToken), bulkUpload = false)
            val gson = new Gson
            // Must persist metadata before any statusUtil calls so getDatasetName can resolve the pipeline token
            NoSQLDbUtil.setItemNameValue(DatrisEnvironment.values.archivedMetadataTableName, "pipeline_token", pipelineToken, "metadata", gson.toJson(metadata))

            statusUtil.info("begin", "Stream data received, dataset: " + dataset + ", filename: " + filename)
            statusUtil.info("processing", "Total data size: " + byteArray.length.toString)

            val dataObj = parseData(byteArray, config)

            statusUtil.info("end", "Process completed successfully")

            JobContext(pipelineToken, metadata, dataObj, config, null, INITIALIZED, null, statusUtil)
        } catch {
            case e: Exception =>
                statusUtil.error("end", "Process completed, error: " + Throwables.getStackTraceAsString(e))
                throw new DatrisException("StreamNotifier error: " + Throwables.getStackTraceAsString(e))
        }
    }

    private def parseData(byteArray: Array[Byte], config: DatasetConfig): Data = {
        val size = byteArray.length.toLong

        if (config.source.fileAttributes.csvAttributes != null) {
            val trimColumns = config.transformation != null && config.transformation.trimColumnWhitespace
            val schemaColumns = config.source.schemaProperties.fields.asScala.map(_.name).toList

            val sourceColumns = {
                if (config.source.fileAttributes.csvAttributes.header) {
                    val headerLine = new String(byteArray, "UTF-8").linesIterator.next()
                    headerLine.split(Pattern.quote(config.source.fileAttributes.csvAttributes.delimiter)).map(_.toLowerCase).toList
                } else
                    schemaColumns
            }

            val csvData = new CSVReader().readFromStream(
                new ByteArrayInputStream(byteArray),
                config.source.fileAttributes.csvAttributes.header,
                config.source.fileAttributes.csvAttributes.delimiter,
                sourceColumns,
                schemaColumns,
                trimColumns = trimColumns
            ).split("\n").toList

            val (header, rows) = {
                if (config.source.fileAttributes.csvAttributes.header)
                    (schemaColumns, csvData.tail)
                else
                    (null, csvData)
            }

            Data(size, header, config.source.schemaProperties.fields.asScala.toList, rows, null)
        }
        else if (config.source.fileAttributes.jsonAttributes != null || config.source.fileAttributes.xmlAttributes != null) {
            Data(size, null, null, null, new String(byteArray, "UTF-8"))
        }
        else if (config.source.fileAttributes.unstructuredAttributes != null) {
            Data(size, null, null, null, null, byteArray)
        }
        else
            throw new DatrisException("StreamNotifier: unsupported file type in dataset config for dataset: " + config.name)
    }

    def process(config: DatasetConfig, data: String): JobContext = {
        val dataset = config.name
        logger.info("Processing stream message for dataset: " + dataset)
        statusUtil.setFilename("stream: " + dataset)

        try {
            // Generate a UUID to track the dataset through the pipeline
            val pipelineToken = GuidV5.nameUUIDFrom(System.currentTimeMillis().toString).toString
            statusUtil.setPipelineToken(pipelineToken)
            statusUtil.setPublisherToken(pipelineToken)
            statusUtil.info("begin", "Stream received for dataset: " + dataset)
            statusUtil.info("processing", "Total data size: " + data.length.toString)

            // Save the metadata in NoSQL
            val metadata = DatasetMetadata(dataset, null, null, pipelineToken, bulkUpload = false)
            val gson = new Gson
            val jsonMetadata = gson.toJson(metadata)
            NoSQLDbUtil.setItemNameValue(DatrisEnvironment.values.archivedMetadataTableName, "pipeline_token", pipelineToken, "metadata", jsonMetadata)

            val dataObj = Data(data.length, null, null, null, data)

            statusUtil.info("end", "Process completed successfully")

            JobContext(pipelineToken, metadata, dataObj, config, null, INITIALIZED, null, statusUtil)
        } catch {
            case e: Exception =>
                statusUtil.error("end", "Process completed, error: " + Throwables.getStackTraceAsString(e))
                throw new DatrisException("FileNotifier error: " +Throwables.getStackTraceAsString(e))
        }
    }
}