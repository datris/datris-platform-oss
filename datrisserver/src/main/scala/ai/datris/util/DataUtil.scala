package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineConfig, PipelineMetadata, DatrisException, SchemaField}
import ai.datris.model.Data
import org.slf4j.{Logger, LoggerFactory}

import java.util.regex.Pattern
import scala.collection.JavaConverters._

object DataUtil {
    private val logger: Logger = LoggerFactory.getLogger(DataUtil.getClass)

    /**
     * Schema evolution: detect new/missing columns and update config.
     * Returns (updatedConfig, updatedSchemaColumns, presentColumns, missingColumns).
     */
    def evolveSchema(
        sourceColumns: List[String],
        config: PipelineConfig,
        statusUtil: StatusUtil
    ): (PipelineConfig, List[String], List[String], List[String]) = {
        var updatedConfig = config
        var schemaColumns = config.source.schemaProperties.fields.asScala.map(_.name).toList

        // Detect new columns in the CSV that are not in the schema (additive schema evolution)
        val newColumns = sourceColumns.filterNot(col =>
            schemaColumns.exists(_.equalsIgnoreCase(col))
        )

        if (newColumns.nonEmpty) {
            logger.info("Schema evolved: added fields [" + newColumns.mkString(", ") + "] to pipeline " + config.name)
            statusUtil.info("processing", "Schema evolution: new columns detected [" + newColumns.mkString(", ") + "], adding to pipeline schema")

            val newFields = newColumns.map(col => SchemaField(col, "string"))
            val updatedFields = new java.util.ArrayList[SchemaField](config.source.schemaProperties.fields)
            newFields.foreach(f => updatedFields.add(f))

            val newVersion = config.source.schemaProperties.schemaVersion + 1
            val updatedSourceSchema = config.source.schemaProperties.copy(fields = updatedFields, schemaVersion = newVersion)
            val updatedSource = config.source.copy(schemaProperties = updatedSourceSchema)

            // Update destination schema if it exists
            val updatedDest = if (config.destination != null && config.destination.schemaProperties != null) {
                val destFields = new java.util.ArrayList[SchemaField](config.destination.schemaProperties.fields)
                newFields.foreach(f => destFields.add(f))
                val updatedDestSchema = config.destination.schemaProperties.copy(fields = destFields, schemaVersion = newVersion)
                config.destination.copy(schemaProperties = updatedDestSchema)
            } else config.destination

            updatedConfig = config.copy(source = updatedSource, destination = updatedDest)
            PipelineConfigIO.write(updatedConfig)

            schemaColumns = updatedSourceSchema.fields.asScala.map(_.name).toList
        }

        // Detect missing schema columns in the CSV header
        val missingColumns = schemaColumns.filterNot(col =>
            sourceColumns.exists(_.equalsIgnoreCase(col))
        )

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
                        ". Expected columns: " + schemaColumns.mkString(", ")
                )
            }

            statusUtil.info("processing", "CSV is missing columns (will be NULL in destination): " + missingColumns.mkString(", "))
        }

        // Only columns that exist in the CSV
        val presentColumns = schemaColumns.filter(col =>
            sourceColumns.exists(_.equalsIgnoreCase(col))
        )

        (updatedConfig, schemaColumns, presentColumns, missingColumns)
    }

    def read(bucket: String, key: String, config: PipelineConfig, metadata: PipelineMetadata, statusUtil: StatusUtil): (Data, PipelineConfig) = {
        val files = new PipelineMetadataUtil(statusUtil).getFiles(metadata)
        val size = getSize(bucket, key, metadata)

        if (config.source.fileAttributes.csvAttributes != null) {
            val trimColumns = {
                if (config.transformation != null && config.transformation.trimColumnWhitespace)
                    true
                else
                    false
            }

            // Read the actual header from the first file if header=true
            val sourceColumns = {
                if (config.source.fileAttributes.csvAttributes.header) {
                    val firstFileUrl = files.head
                    val reader = ObjectStoreUtil.getBufferedReader(
                        ObjectStoreUtil.getBucket(firstFileUrl),
                        ObjectStoreUtil.getKey(firstFileUrl)
                    )
                    try {
                        val headerLine = reader.readLine()
                        headerLine.split(Pattern.quote(config.source.fileAttributes.csvAttributes.delimiter)).map(_.toLowerCase).toList
                    } finally {
                        reader.close()
                    }
                } else
                    config.source.schemaProperties.fields.asScala.map(_.name).toList
            }

            // Schema evolution: detect new/missing columns, update config
            val (resolvedConfig, schemaColumns, presentColumns, missingColumns) = evolveSchema(sourceColumns, config, statusUtil)

            var header: List[String] = null
            val data = files.zipWithIndex.flatMap { case (fileUrl, index) =>
                val rows = {
                    if (index == 0) {
                        val data = new CSVReader().readFile(
                            fileUrl,
                            resolvedConfig.source.fileAttributes.csvAttributes.header,
                            resolvedConfig.source.fileAttributes.csvAttributes.delimiter,
                            sourceColumns, // actual CSV column order
                            presentColumns, // only columns present in CSV
                            trimColumns = trimColumns
                        )
                            .split("\n")
                            .toList
                        if (resolvedConfig.source.fileAttributes.csvAttributes.header) {
                            header = if (missingColumns.isEmpty) schemaColumns else presentColumns
                            data.tail
                        } else
                            data
                    } else {
                        new CSVReader().readFile(
                            fileUrl,
                            resolvedConfig.source.fileAttributes.csvAttributes.header,
                            resolvedConfig.source.fileAttributes.csvAttributes.delimiter,
                            sourceColumns,
                            presentColumns,
                            trimColumns = trimColumns,
                            removeHeader = true
                        )
                            .split("\n")
                            .toList
                    }
                }
                rows
            }
            if (data.isEmpty)
                throw new DatrisException(
                    "No data rows found in uploaded file for pipeline: " + config.name + ". The file may be empty or contain only a header row."
                )

            val headerWithSchema = resolvedConfig.source.schemaProperties.fields.asScala.toList
            (Data(size, header, headerWithSchema, data, null), resolvedConfig)
        } else if (config.source.fileAttributes.jsonAttributes != null || config.source.fileAttributes.xmlAttributes != null) {
            val fileUrl = files.head
            val rawData = ObjectStoreUtil.readBucketObject(ObjectStoreUtil.getBucket(fileUrl), ObjectStoreUtil.getKey(fileUrl))
                .getOrElse(throw new DatrisException("Error reading source file: " + fileUrl))
            (Data(size, null, null, null, rawData), config)
        } else if (config.source.fileAttributes.unstructuredAttributes != null) {
            val fileUrl = files.head
            val inputStream = ObjectStoreUtil.getInputStream(ObjectStoreUtil.getBucket(fileUrl), ObjectStoreUtil.getKey(fileUrl))
            try {
                val rawBytes = inputStream.readAllBytes()
                (Data(size, null, null, null, null, rawBytes), config)
            } finally {
                inputStream.close()
            }
        } else
            throw new DatrisException("Unsupported file type in pipeline config for pipeline: " + config.name)
    }

    private def getSize(bucket: String, key: String, metadata: PipelineMetadata): Long = {
        // Get the file size
        val objectMetadata = ObjectStoreUtil.getObjectMetadata(bucket, key)
        val objectSize = {
            // Bulk file ingestion?
            if (metadata.dataFilePath != null) {
                val summaries = ObjectStoreUtil.listSummaries(ObjectStoreUtil.getBucket(metadata.dataFilePath), ObjectStoreUtil.getKey(metadata.dataFilePath))
                summaries.map(_.size).sum
            } else
                objectMetadata.contentLength
        }

        objectSize
    }
}
