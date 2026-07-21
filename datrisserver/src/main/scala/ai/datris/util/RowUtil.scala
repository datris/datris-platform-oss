package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineConfig, DatrisException}

import scala.collection.JavaConverters._
import scala.collection.mutable

object RowUtil {
    def getRowAsMap(row: String, config: PipelineConfig, header: List[String]): mutable.ListMap[String, Any] = {
        // Build a header name -> column position map (case-insensitive)
        val headerIndex: Map[String, Int] = header.zipWithIndex.map { case (name, idx) => name.toLowerCase -> idx }.toMap
        val columns = row.split(config.source.fileAttributes.csvAttributes.delimiter).toList

        val columnMap = mutable.ListMap[String, Any]()
        config.source.schemaProperties.fields.asScala.foreach(column => {
            // Find the column position from the header
            val columnNumber = headerIndex.getOrElse(
                column.name.toLowerCase,
                throw new DatrisException("Column '" + column.name + "' from schema not found in file header. Header columns: " + header.mkString(", "))
            )

            if (columnNumber >= columns.size)
                throw new DatrisException(
                    "Column '" + column.name + "' refers to position " + columnNumber + " but row only has " + columns.size + " column(s)"
                )

            val columnValue = columns(columnNumber)

            // Add to the map with type conversion
            if (columnValue == null || columnValue.isEmpty)
                columnMap.put(column.name, columnValue)
            else {
                if (column.`type`.startsWith("boolean"))
                    columnMap.put(column.name, columnValue.toBoolean)
                else if (column.`type`.startsWith("int"))
                    columnMap.put(column.name, columnValue.toInt)
                else if (column.`type`.startsWith("tinyint"))
                    columnMap.put(column.name, columnValue.toShort)
                else if (column.`type`.startsWith("smallint"))
                    columnMap.put(column.name, columnValue.toShort)
                else if (column.`type`.startsWith("bigint"))
                    columnMap.put(column.name, columnValue.toLong)
                else if (column.`type`.startsWith("float"))
                    columnMap.put(column.name, columnValue.toFloat)
                else if (column.`type`.startsWith("double"))
                    columnMap.put(column.name, columnValue.toDouble)
                else if (column.`type`.startsWith("decimal"))
                    columnMap.put(column.name, columnValue.toDouble)
                else if (column.`type`.startsWith("string"))
                    columnMap.put(column.name, columnValue)
                else if (column.`type`.startsWith("varchar"))
                    columnMap.put(column.name, columnValue)
                else if (column.`type`.startsWith("char"))
                    columnMap.put(column.name, columnValue)
                else if (column.`type`.startsWith("date"))
                    columnMap.put(column.name, columnValue)
                else if (column.`type`.startsWith("timestamp"))
                    columnMap.put(column.name, columnValue)
                else
                    throw new DatrisException("Internal error applying destination schema, dataType: " + column.`type` + ", is not supported")
            }
        })
        columnMap
    }
}
