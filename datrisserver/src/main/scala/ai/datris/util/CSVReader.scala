package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.apache.commons.csv.{CSVFormat, CSVParser}

import java.io.{InputStream, InputStreamReader, StringWriter, Writer}
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
        val out = new StringWriter
        readToWriter(inputStream, header, delimiter, columnList, columnFilter, trimColumns, removeHeader, out)
        out.toString
    }

    /** Streaming form of [[readFromStream]]: the same parsing and the same
      * output bytes (rows joined by "\n", no trailing newline), written to
      * `out` one record at a time instead of accumulated. Returns the number
      * of rows written — the header line counts as a row unless
      * `header && removeHeader`. Takes ownership of `inputStream`: closing the
      * parser closes the reader chain (and with it the stream). `out` is not
      * closed. */
    def readToWriter(
        inputStream: InputStream,
        header: Boolean,
        delimiter: String,
        columnList: List[String],
        columnFilter: List[String],
        trimColumns: Boolean = false,
        removeHeader: Boolean = false,
        out: Writer
    ): Long = {
        val columnNumbers = columnFilter.flatMap(filteredColumn => {
            columnList.zipWithIndex.flatMap { case (column, index) =>
                if (filteredColumn.equalsIgnoreCase(column)) Some(index) else None
            }
        })

        Loan.withResource(
            new CSVParser(
                new InputStreamReader(inputStream),
                CSVFormat.RFC4180.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(trimColumns).build()
            )
        ) { parser =>
            var written = 0L
            var skipHeader = header && removeHeader
            val records = parser.iterator()
            while (records.hasNext) {
                val record = records.next()
                if (skipHeader)
                    skipHeader = false
                else {
                    val line = columnNumbers.map(colNumber => {
                        val value = record.get(colNumber)
                        // A lone "\r" must be quoted too: the staged file is read
                        // back record by record (StagedRows), and an unquoted CR
                        // would be taken as a line terminator.
                        if (value != null && (value.contains(delimiter) || value.contains("\"") || value.contains("\n") || value.contains("\r")))
                            "\"" + value.replace("\"", "\"\"") + "\""
                        else
                            value
                    }).mkString(delimiter)
                    if (written > 0) out.write("\n")
                    out.write(line)
                    written += 1
                }
            }
            written
        }
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
