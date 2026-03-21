package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import java.sql.{ResultSet, Types}
import scala.collection.mutable.ListBuffer

object SQLUtil {
    def getResultSet(resultSet: ResultSet): List[Map[String, String]] = {
        val rows = new ListBuffer[Map[String, String]]
        val metaData = resultSet.getMetaData
        val columnCount = metaData.getColumnCount

        while (resultSet.next()) {
            val columnMap = 1.until(columnCount + 1).map(index => {
                val name = metaData.getColumnName(index)
                val dataType = metaData.getColumnType(index)
                val value = dataType match {
                    case Types.BOOLEAN | Types.BIT =>
                        convertIfNull(resultSet.getBoolean(index))
                    case Types.TINYINT | Types.SMALLINT | Types.INTEGER =>
                        convertIfNull(resultSet.getInt(index))
                    case Types.BIGINT =>
                        convertIfNull(resultSet.getLong(index))
                    case Types.NUMERIC | Types.DECIMAL =>
                        val value = convertIfNull(resultSet.getBigDecimal(index))
                        if(value.isBlank)
                            value
                        else
                            BigDecimal(value).toInt.toString    // Remove scientific notation
                    case Types.REAL =>
                        convertIfNull(resultSet.getFloat(index))
                    case Types.FLOAT | Types.DOUBLE =>
                        convertIfNull(resultSet.getDouble(index))
                    case Types.TIME | Types.TIME_WITH_TIMEZONE =>
                        convertIfNull(resultSet.getTime(index))
                    case Types.TIMESTAMP | Types.TIMESTAMP_WITH_TIMEZONE =>
                        convertIfNull(resultSet.getTimestamp(index))
                    case Types.DATE =>
                        convertIfNull(resultSet.getDate(index))
                    case Types.CHAR | Types.VARCHAR | Types.LONGVARCHAR =>
                        convertIfNull(resultSet.getString(index))
                    case _ =>
                        convertIfNull(resultSet.getString(index))
                }
                (name, value)
            }).toMap
            rows.append(columnMap)
        }
        rows.toList
    }

    private def convertIfNull(value: Any): String = {
        if(value == null)
            ""
        else
            value.toString
    }
}
