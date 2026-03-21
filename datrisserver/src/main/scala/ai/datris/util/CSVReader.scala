package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import org.apache.commons.csv.{CSVFormat, CSVParser}

import java.io.{InputStreamReader, InputStream}
import scala.collection.JavaConverters._

class CSVReader {
    def readFromStream(inputStream: InputStream, header: Boolean, delimiter: String, columnList: List[String], columnFilter: List[String], trimColumns: Boolean = false, removeHeader: Boolean = false): String = {
        val columnNumbers = columnFilter.flatMap(filteredColumn => {
            columnList.zipWithIndex.flatMap { case (column, index) =>
                if (filteredColumn.equalsIgnoreCase(column)) Some(index) else None
            }
        })

        val reader = new InputStreamReader(inputStream)
        val parser = new CSVParser(reader, CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(trimColumns).build())

        val rows = parser.getRecords.asScala.map(record => {
            columnNumbers.map(colNumber => record.get(colNumber)).mkString(delimiter)
        }).toList

        if (header && removeHeader)
            rows.tail.mkString("\n")
        else
            rows.mkString("\n")
    }

    def readFile(url: String, header: Boolean, delimiter: String, columnList: List[String], columnFilter: List[String], trimColumns: Boolean = false, removeHeader: Boolean = false): String = {
        // Determine the column #'s to read
        val columnNumbers = columnFilter.flatMap(filteredColumn => {
            columnList.zipWithIndex.flatMap { case (column, index) =>
                if (filteredColumn.equalsIgnoreCase(column))
                    Some(index)
                else
                    None
            }
        })

        // Read the file using Apache commons-csv
        val bufferedReader = ObjectStoreUtil.getBufferedReader(ObjectStoreUtil.getBucket(url), ObjectStoreUtil.getKey(url))
        val parser = new CSVParser(bufferedReader, CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(trimColumns).build())

        // Get only the columns in the column filter
        val rows = parser.getRecords.asScala.map(record => {
            columnNumbers.map(colNumber => {
                record.get(colNumber)
            }).mkString(delimiter)
        }).toList

        if(header && removeHeader)
            rows.tail.mkString("\n")
        else
            rows.mkString("\n")
    }
}
