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
    private val statusUtil = new StatusUtil().init(DatrisEnvironment.current.pipelineStatusTableName, this.getClass.getSimpleName)

    def process(byteArray: Array[Byte], filename: String, pipeline: String, publisherToken: String): JobContext = {
        logger.info("StreamNotifier processing pipeline: " + pipeline + ", filename: " + filename)
        statusUtil.setFilename("stream: " + pipeline)

        try {
            val pipelineToken = GuidV5.nameUUIDFrom(System.currentTimeMillis().toString).toString
            statusUtil.setPipelineToken(pipelineToken)
            statusUtil.setPublisherToken(Option(publisherToken).getOrElse(pipelineToken))

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
            if (config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")

            val metadata = PipelineMetadata(pipeline, filename, null, Option(publisherToken).getOrElse(pipelineToken), bulkUpload = false)
            val gson = new Gson
            // Must persist metadata before any statusUtil calls so getPipelineName can resolve the pipeline token
            NoSQLDbUtil.setItemNameValue(DatrisEnvironment.current.archivedMetadataTableName, "pipeline_token", pipelineToken, "metadata", gson.toJson(metadata))

            statusUtil.info("begin", "Stream data received, pipeline: " + pipeline + ", filename: " + filename)
            statusUtil.info("processing", "Total data size: " + byteArray.length.toString)

            val dataObj = parseData(byteArray, config)

            statusUtil.info("end", "Process completed successfully")

            JobContext(pipelineToken, metadata, dataObj, config, null, INITIALIZED, null, statusUtil, DatrisEnvironment.current)
        } catch {
            case e: Exception =>
                statusUtil.error("end", "Process completed, error: " + Throwables.getStackTraceAsString(e))
                throw new DatrisException("StreamNotifier error: " + Throwables.getStackTraceAsString(e))
        }
    }

    private def parseData(byteArray: Array[Byte], config: PipelineConfig): Data = {
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

            // Detect missing schema columns in the CSV header
            val missingColumns = schemaColumns.filterNot(col =>
                sourceColumns.exists(_.equalsIgnoreCase(col)))

            if (missingColumns.nonEmpty) {
                // Fail if any missing column is a key field
                val keyFields = Option(config.destination)
                    .flatMap(d => Option(d.database))
                    .flatMap(db => Option(db.keyFields))
                    .map(_.asScala.map(_.toLowerCase).toSet)
                    .getOrElse(Set.empty)

                val missingKeyFields = missingColumns.filter(col => keyFields.contains(col.toLowerCase))
                if (missingKeyFields.nonEmpty) {
                    throw new DatrisException(
                        "CSV is missing required key field(s): " + missingKeyFields.mkString(", ") +
                        ". CSV columns: " + sourceColumns.mkString(", ") +
                        ". Expected columns: " + schemaColumns.mkString(", "))
                }

                statusUtil.info("processing", "CSV is missing columns (will be filled as empty): " + missingColumns.mkString(", "))
            }

            // Only request columns that exist in the CSV header
            val presentColumns = schemaColumns.filter(col =>
                sourceColumns.exists(_.equalsIgnoreCase(col)))

            val csvData = new CSVReader().readFromStream(
                new ByteArrayInputStream(byteArray),
                config.source.fileAttributes.csvAttributes.header,
                config.source.fileAttributes.csvAttributes.delimiter,
                sourceColumns,
                presentColumns,
                trimColumns = trimColumns
            ).split("\n").toList

            val delimiter = config.source.fileAttributes.csvAttributes.delimiter
            val (header, rows) = {
                val dataRows = if (config.source.fileAttributes.csvAttributes.header)
                    if (csvData.nonEmpty) csvData.tail else List.empty[String]
                else
                    csvData

                if (missingColumns.isEmpty) {
                    (schemaColumns, dataRows)
                } else {
                    // Insert empty values for missing columns
                    val presentSet = presentColumns.map(_.toLowerCase).toSet
                    val presentIndices = schemaColumns.map(col =>
                        if (presentSet.contains(col.toLowerCase))
                            presentColumns.indexWhere(_.equalsIgnoreCase(col))
                        else -1
                    )
                    val rebuiltRows = dataRows.map { row =>
                        val cols = row.split(delimiter, -1).toList
                        presentIndices.map(idx =>
                            if (idx >= 0 && idx < cols.size) cols(idx) else ""
                        ).mkString(delimiter)
                    }
                    (schemaColumns, rebuiltRows)
                }
            }

            if (rows.isEmpty)
                throw new DatrisException("No data rows found in uploaded file for pipeline: " + config.name + ". The file may be empty or contain only a header row.")

            Data(size, header, config.source.schemaProperties.fields.asScala.toList, rows, null)
        }
        else if (config.source.fileAttributes.jsonAttributes != null || config.source.fileAttributes.xmlAttributes != null) {
            Data(size, null, null, null, new String(byteArray, "UTF-8"))
        }
        else if (config.source.fileAttributes.unstructuredAttributes != null) {
            Data(size, null, null, null, null, byteArray)
        }
        else
            throw new DatrisException("StreamNotifier: unsupported file type in pipeline config for pipeline: " + config.name)
    }

    def process(config: PipelineConfig, data: String): JobContext = {
        val pipeline = config.name
        logger.info("Processing stream message for pipeline: " + pipeline)
        statusUtil.setFilename("stream: " + pipeline)

        try {
            // Generate a UUID to track the pipeline through the pipeline
            val pipelineToken = GuidV5.nameUUIDFrom(System.currentTimeMillis().toString).toString
            statusUtil.setPipelineToken(pipelineToken)
            statusUtil.setPublisherToken(pipelineToken)
            statusUtil.info("begin", "Stream received for pipeline: " + pipeline)
            statusUtil.info("processing", "Total data size: " + data.length.toString)

            // Save the metadata in NoSQL
            val metadata = PipelineMetadata(pipeline, null, null, pipelineToken, bulkUpload = false)
            val gson = new Gson
            val jsonMetadata = gson.toJson(metadata)
            NoSQLDbUtil.setItemNameValue(DatrisEnvironment.current.archivedMetadataTableName, "pipeline_token", pipelineToken, "metadata", jsonMetadata)

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