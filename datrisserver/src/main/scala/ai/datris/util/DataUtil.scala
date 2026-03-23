package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{PipelineConfig, PipelineMetadata, DatrisException}
import ai.datris.model.Data

import java.util.regex.Pattern
import scala.collection.JavaConverters._

object DataUtil {
    def read(bucket: String, key: String, config: PipelineConfig, metadata: PipelineMetadata, statusUtil: StatusUtil): Data = {
        val files = new PipelineMetadataUtil(statusUtil).getFiles(metadata)
        val size = getSize(bucket, key, metadata)

        if(config.source.fileAttributes.csvAttributes != null) {
            val trimColumns = {
                if(config.transformation != null && config.transformation.trimColumnWhitespace)
                    true
                else
                    false
            }

            val schemaColumns = config.source.schemaProperties.fields.asScala.map(_.name).toList

            // Read the actual header from the first file if header=true
            val sourceColumns = {
                if (config.source.fileAttributes.csvAttributes.header) {
                    val firstFileUrl = files.head
                    val reader = ObjectStoreUtil.getBufferedReader(
                        ObjectStoreUtil.getBucket(firstFileUrl),
                        ObjectStoreUtil.getKey(firstFileUrl))
                    try {
                        val headerLine = reader.readLine()
                        headerLine.split(Pattern.quote(config.source.fileAttributes.csvAttributes.delimiter)).map(_.toLowerCase).toList
                    } finally {
                        reader.close()
                    }
                }
                else
                    schemaColumns
            }

            var header: List[String] = null
            val data = files.zipWithIndex.flatMap { case (fileUrl, index) =>
                val rows = {
                    if (index == 0) {
                        val data = new CSVReader().readFile(fileUrl,
                                config.source.fileAttributes.csvAttributes.header,
                                config.source.fileAttributes.csvAttributes.delimiter,
                                sourceColumns,    // actual CSV column order
                                schemaColumns,    // desired schema order
                                trimColumns = trimColumns)
                            .split("\n")
                            .toList
                        if(config.source.fileAttributes.csvAttributes.header) {
                            header = schemaColumns  // use schema order, not file order
                            data.tail
                        }
                        else
                            data
                    }
                    else {
                        new CSVReader().readFile(fileUrl,
                                config.source.fileAttributes.csvAttributes.header,
                                config.source.fileAttributes.csvAttributes.delimiter,
                                sourceColumns,
                                schemaColumns,
                                trimColumns = trimColumns,
                                removeHeader = true)
                            .split("\n")
                            .toList
                    }
                }
                rows
            }
            val headerWithSchema = config.source.schemaProperties.fields.asScala.toList
            Data(size, header, headerWithSchema, data, null)
        }
        else if(config.source.fileAttributes.jsonAttributes != null || config.source.fileAttributes.xmlAttributes != null) {
            val fileUrl = files.head
            val rawData = ObjectStoreUtil.readBucketObject(ObjectStoreUtil.getBucket(fileUrl), ObjectStoreUtil.getKey(fileUrl))
                .getOrElse(throw new DatrisException("Error reading source file: " + fileUrl))
            Data(size, null, null, null, rawData)
        }
        else if(config.source.fileAttributes.unstructuredAttributes != null)
            Data(size, null, null, null, null)
        else
            null
    }

    private def getSize(bucket: String, key: String, metadata: PipelineMetadata): Long = {
        // Get the file size
        val objectMetadata = ObjectStoreUtil.getObjectMetadata(bucket, key)
        val objectSize = {
            // Bulk file ingestion?
            if(metadata.dataFilePath != null) {
                val summaries = ObjectStoreUtil.listSummaries(ObjectStoreUtil.getBucket(metadata.dataFilePath),
                    ObjectStoreUtil.getKey(metadata.dataFilePath))
                summaries.map(_.size).sum
            }
            else
                objectMetadata.contentLength
        }

        objectSize
    }
}
