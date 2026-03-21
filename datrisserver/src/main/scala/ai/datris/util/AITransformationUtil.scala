package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

object AITransformationUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val MAX_RETRIES = 2

    def transformWithFileContent(instruction: String, header: List[String], rows: List[String], delimiter: String): List[String] = {
        checkAiEnabled()

        val headerLine = header.mkString(delimiter)
        val csvContent = (headerLine +: rows).mkString("\n")

        logger.info("Running AI transformation in full-file mode, rows: " + rows.size)

        val prompt =
            s"""IMPORTANT: You are a data transformation engine. Do NOT describe or summarize the data. Do NOT ask questions.
               |Below is a CSV file. Apply this transformation to every row: "$instruction"
               |
               |Return ONLY the transformed CSV data rows (no header row, no row numbers, no explanation, no markdown, no code fences).
               |Use the same delimiter: "$delimiter"
               |Keep the same number of columns in the same order.
               |If a transformation adds new columns, append them at the end.
               |Return one row per line, nothing else.
               |
               |$csvContent""".stripMargin

        callWithRetry(prompt, header, delimiter)
    }

    def transformRawContent(instruction: String, rawData: String): String = {
        checkAiEnabled()
        logger.info("Running AI transformation on raw data, length: " + rawData.length)

        val prompt =
            s"""IMPORTANT: You are a data transformation engine. Do NOT describe or summarize the data. Do NOT ask questions.
               |Below is a data file. Apply this transformation to every record: "$instruction"
               |
               |Return ONLY the transformed data in the same format as the input. No explanation, no markdown, no code fences.
               |
               |$rawData""".stripMargin

        val responseText = AIUtil.callAI(prompt)
        AIUtil.extractText(responseText).trim
    }

    private def callWithRetry(prompt: String, header: List[String], delimiter: String): List[String] = {
        var lastException: Exception = null
        for (attempt <- 0 to MAX_RETRIES) {
            try {
                val responseText = AIUtil.callAI(prompt)
                val text = AIUtil.extractText(responseText).trim
                val rows = text.split("\n").toList.filter(_.nonEmpty)

                // Validate that rows have the expected number of columns
                val expectedCols = header.size
                val validRows = rows.filter { row =>
                    val cols = row.split(delimiter, -1).length
                    cols >= expectedCols
                }

                if (validRows.isEmpty && rows.nonEmpty)
                    throw new DatrisException("AI transformation returned rows with wrong column count. Expected " + expectedCols + " columns.")

                return validRows
            } catch {
                case e: Exception if attempt < MAX_RETRIES =>
                    lastException = e
                    logger.warn("AI transformation failed (attempt " + (attempt + 1) + " of " + (MAX_RETRIES + 1) + "), retrying: " + e.getMessage)
            }
        }
        throw new DatrisException("AI transformation failed after " + (MAX_RETRIES + 1) + " attempts: " + lastException.getMessage)
    }

    private def checkAiEnabled(): Unit = {
        if (!DatrisEnvironment.values.aiEnabled)
            throw new DatrisException("AI transformations require ai.enabled: true in application.yaml")
    }
}
