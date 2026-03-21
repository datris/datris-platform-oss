package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{DatasetConfig, DatrisEnvironment, DatrisException}

object DatasetConfigIO {
    def readAll(tableName: String): List[DatasetConfig] = {
        val datasetNames = NoSQLDbUtil.getItemsKeysByKeyName(tableName, "name")
        datasetNames.map(name => {
            read(DatrisEnvironment.values.datasetTableName, name)
        })
    }

    def read(tableName: String, datasetName: String): DatasetConfig = {
        val json = NoSQLDbUtil.getItemJSON(tableName, "name", datasetName, "value").orNull
        if(json != null) {
            val gson = new Gson
            val config = gson.fromJson(json, classOf[DatasetConfig])

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

    def write(datasetConfig: DatasetConfig): Unit = {
        val gson = new Gson
        val json = gson.toJson(datasetConfig)
        NoSQLDbUtil.putItemJSON(DatrisEnvironment.values.datasetTableName, "name", datasetConfig.name, "value", json)
    }

    def getSourceFileExtension(config: DatasetConfig): String = {
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
            throw new DatrisException("The dataset configuration fileAttributes are not configured properly")
    }
}
