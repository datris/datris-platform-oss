package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.apache.commons.csv.{CSVFormat, CSVParser}

import java.io.{InputStreamReader, InputStream}
import scala.collection.JavaConverters._

class CSVReader {
    def readFromStream(
        inputStream: InputStream,
        header: Boolean,
        delimiter: String,
        columnList: List[String],
        columnFilter: List[String],
        trimColumns: Boolean = false,
        removeHeader: Boolean = false
    ): String = {
        val columnNumbers = columnFilter.flatMap(filteredColumn => {
            columnList.zipWithIndex.flatMap { case (column, index) =>
                if (filteredColumn.equalsIgnoreCase(column)) Some(index) else None
            }
        })

        // Takes ownership of inputStream: parsing consumes it, and closing the
        // parser closes the reader chain (and with it the stream).
        val rows = Loan.withResource(
            new CSVParser(
                new InputStreamReader(inputStream),
                CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(trimColumns).build()
            )
        ) { parser =>
            parser.getRecords.asScala.map(record => {
                columnNumbers.map(colNumber => {
                    val value = record.get(colNumber)
                    if (value != null && (value.contains(delimiter) || value.contains("\"") || value.contains("\n")))
                        "\"" + value.replace("\"", "\"\"") + "\""
                    else
                        value
                }).mkString(delimiter)
            }).toList
        }

        if (header && removeHeader)
            rows.tail.mkString("\n")
        else
            rows.mkString("\n")
    }

    def readFile(
        url: String,
        header: Boolean,
        delimiter: String,
        columnList: List[String],
        columnFilter: List[String],
        trimColumns: Boolean = false,
        removeHeader: Boolean = false
    ): String = {
        // Determine the column #'s to read
        val columnNumbers = columnFilter.flatMap(filteredColumn => {
            columnList.zipWithIndex.flatMap { case (column, index) =>
                if (filteredColumn.equalsIgnoreCase(column))
                    Some(index)
                else
                    None
            }
        })

        // Read the file using Apache commons-csv. The parser owns the reader
        // chain down to the object-store stream; closing it releases the
        // connection (previously leaked — the reader was never closed).
        val bufferedReader = ObjectStoreUtil.getBufferedReader(ObjectStoreUtil.getBucket(url), ObjectStoreUtil.getKey(url))
        val rows = Loan.withResource(
            new CSVParser(bufferedReader, CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(trimColumns).build())
        ) { parser =>
            // Get only the columns in the column filter
            parser.getRecords.asScala.map(record => {
                columnNumbers.map(colNumber => {
                    val value = record.get(colNumber)
                    if (value != null && (value.contains(delimiter) || value.contains("\"") || value.contains("\n")))
                        "\"" + value.replace("\"", "\"\"") + "\""
                    else
                        value
                }).mkString(delimiter)
            }).toList
        }

        if (header && removeHeader)
            rows.tail.mkString("\n")
        else
            rows.mkString("\n")
    }
}
