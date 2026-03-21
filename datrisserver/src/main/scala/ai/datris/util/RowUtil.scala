package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatasetConfig, DatrisException}

import scala.collection.JavaConverters._
import scala.collection.mutable

object RowUtil {
    def getRowAsMap(row: String, config: DatasetConfig): mutable.ListMap[String, Any] = {
        val columnsWithIndex = config.source.schemaProperties.fields.asScala.zipWithIndex.toList

        // Map the row data by field type
        val columnMap = mutable.ListMap[String, Any]()
        config.source.schemaProperties.fields.asScala.map(column => {

            // Find the column value
            val (schemaField, columnNumber) = columnsWithIndex.find { case (columnWithIndex, columnNumber) =>
                columnWithIndex.name.compareToIgnoreCase(column.name) == 0
            }.getOrElse(throw new DatrisException("Internal error, could not find the field name: " + column.name))
            val columns = row.split(config.source.fileAttributes.csvAttributes.delimiter).toList
            val columnValue = columns(columnNumber)

            // Add to the map
            if(columnValue == null || columnValue.isEmpty)
                columnMap.put(column.name, columnValue)
            else {
                if(column.`type`.startsWith("boolean"))
                    columnMap.put(column.name, columnValue.toBoolean)
                else if(column.`type`.startsWith("int"))
                    columnMap.put(column.name, columnValue.toInt)
                else if(column.`type`.startsWith("tinyint"))
                    columnMap.put(column.name, columnValue.toShort)
                else if(column.`type`.startsWith("smallint"))
                    columnMap.put(column.name, columnValue.toShort)
                else if(column.`type`.startsWith("bigint"))
                    columnMap.put(column.name, columnValue.toLong)
                else if(column.`type`.startsWith("float"))
                    columnMap.put(column.name, columnValue.toFloat)
                else if(column.`type`.startsWith("double"))
                    columnMap.put(column.name, columnValue.toDouble)
                else if(column.`type`.startsWith("decimal"))
                    columnMap.put(column.name, columnValue.toDouble)
                else if(column.`type`.startsWith("string"))
                    columnMap.put(column.name, columnValue)
                else if(column.`type`.startsWith("varchar"))
                    columnMap.put(column.name, columnValue)
                else if(column.`type`.startsWith("char"))
                    columnMap.put(column.name, columnValue)
                else if(column.`type`.startsWith("date"))
                    columnMap.put(column.name, columnValue)
                else if(column.`type`.startsWith("timestamp"))
                    columnMap.put(column.name, columnValue)
                else
                    throw new DatrisException("Internal error applying destination schema, dataType: " + column.`type` + ", is not supported")
            }
        })
        columnMap
    }
}