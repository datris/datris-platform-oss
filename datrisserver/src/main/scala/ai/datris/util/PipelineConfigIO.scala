package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{PipelineConfig, DatrisEnvironment, DatrisException}

object PipelineConfigIO {
    def readAll(tableName: String): List[PipelineConfig] = {
        val pipelineNames = NoSQLDbUtil.getItemsKeysByKeyName(tableName, "name")
        pipelineNames.map(name => {
            read(DatrisEnvironment.values.pipelineTableName, name)
        })
    }

    def read(tableName: String, pipelineName: String): PipelineConfig = {
        val json = NoSQLDbUtil.getItemJSON(tableName, "name", pipelineName, "value").orNull
        if(json != null) {
            val gson = new Gson
            val config = gson.fromJson(json, classOf[PipelineConfig])

            // If there are no destination schema properties, use the source schema as the destination schema
            if(config.destination.schemaProperties == null) {
                val destination = config.destination.copy(schemaProperties = config.source.schemaProperties)
                config.copy(destination = destination)
            }
            else
                config
        }
        else
            null
    }

    def write(datasetConfig: PipelineConfig): Unit = {
        val gson = new Gson
        val json = gson.toJson(datasetConfig)
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.values.pipelineTableName, "name", datasetConfig.name, "value", json)
    }

    def getSourceFileExtension(config: PipelineConfig): String = {
        val fileAttributes = config.source.fileAttributes
        if(fileAttributes.csvAttributes != null)
            "csv"
        else if(fileAttributes.jsonAttributes != null)
            "json"
        else if(fileAttributes.xmlAttributes != null)
            "xml"
        else if(fileAttributes.unstructuredAttributes != null)
            fileAttributes.unstructuredAttributes.fileExtension
        else
            throw new DatrisException("The pipeline configuration fileAttributes are not configured properly")
    }
}
