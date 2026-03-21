package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.{Gson, GsonBuilder}
import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object AIDataQualityUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val MAX_RETRIES = 2
    private val BATCH_TIMEOUT_MINUTES = 10

    def validateColumnValues(columnName: String, instruction: String, values: List[(Int, String)], batchSize: Int): List[(Int, String)] = {
        checkAiEnabled()
        logger.info("Running AI column rule for column: " + columnName + ", values: " + values.size + ", batchSize: " + batchSize)

        val batches = values.grouped(batchSize).toList
        val futures = batches.map { batch =>
            Future {
                val valuesJson = batch.map { case (idx, v) => s"""[$idx, "${escapeJson(v)}"]""" }.mkString(", ")
                val prompt =
                    s"""IMPORTANT: You are a data validation engine. Do NOT describe or summarize the data. Do NOT ask questions.
                       |Your ONLY job is to validate each value against this rule: "$instruction"
                       |
                       |Output ONLY a JSON array. No explanation, no markdown, no code fences.
                       |Include ONLY values that FAIL validation. If all values pass, return exactly: []
                       |Format: [{"index": N, "reason": "..."}, ...]
                       |
                       |Values (index, value): [$valuesJson]""".stripMargin

                callWithRetry(prompt)
            }
        }
        Await.result(Future.sequence(futures), BATCH_TIMEOUT_MINUTES.minutes).flatten
    }

    def validateRows(instruction: String, rowMaps: List[(Int, java.util.Map[String, Any])], batchSize: Int): List[(Int, String)] = {
        checkAiEnabled()
        logger.info("Running AI row rule, rows: " + rowMaps.size + ", batchSize: " + batchSize)

        val gson = new Gson()
        val batches = rowMaps.grouped(batchSize).toList
        val futures = batches.map { batch =>
            Future {
                val rowsJson = batch.map { case (idx, rowMap) =>
                    s"""{"index": $idx, "data": ${gson.toJson(rowMap)}}"""
                }.mkString(", ")
                val prompt =
                    s"""IMPORTANT: You are a data validation engine. Do NOT describe or summarize the data. Do NOT ask questions.
                       |Your ONLY job is to validate each row against this rule: "$instruction"
                       |
                       |Output ONLY a JSON array. No explanation, no markdown, no code fences.
                       |Include ONLY rows that FAIL validation. If all rows pass, return exactly: []
                       |Format: [{"index": N, "reason": "..."}, ...]
                       |
                       |Rows: [$rowsJson]""".stripMargin

                callWithRetry(prompt)
            }
        }
        Await.result(Future.sequence(futures), BATCH_TIMEOUT_MINUTES.minutes).flatten
    }

    def validateWithFileContent(instruction: String, header: List[String], rows: List[String], delimiter: String): List[(Int, String)] = {
        checkAiEnabled()

        val headerLine = header.mkString(delimiter)
        val numberedRows = rows.zipWithIndex.map { case (row, idx) => s"$idx$delimiter$row" }
        val csvContent = (s"row_index${delimiter}$headerLine" +: numberedRows).mkString("\n")

        logger.info("Running AI rule in full-file mode, rows: " + rows.size)

        val prompt =
            s"""IMPORTANT: You are a data validation engine. Do NOT describe or summarize the data. Do NOT ask questions.
               |Below is a CSV file with a row_index column prepended.
               |Your ONLY job is to validate every row against this rule: "$instruction"
               |
               |Output ONLY a JSON array. No explanation, no markdown, no code fences.
               |Include ONLY rows that FAIL validation. If all rows pass, return exactly: []
               |Format: [{"index": <row_index>, "reason": "brief failure description"}, ...]
               |Each reason must be a single short sentence stating the failure. Do NOT include reasoning, analysis, corrections, or second-guessing.
               |
               |$csvContent""".stripMargin

        callWithRetry(prompt)
    }

    def validateWithRawContent(instruction: String, rawData: String): List[(Int, String)] = {
        checkAiEnabled()
        logger.info("Running AI rule in full-file mode on raw data, length: " + rawData.length)

        val prompt =
            s"""IMPORTANT: You are a data validation engine. Do NOT describe or summarize the data. Do NOT ask questions.
               |Below is a data file. Your ONLY job is to validate every record against this rule: "$instruction"
               |
               |Output ONLY a JSON array. No explanation, no markdown, no code fences.
               |Include ONLY records that FAIL validation. If all records pass, return exactly: []
               |Format: [{"index": <record_number_starting_from_0>, "reason": "brief failure description"}, ...]
               |Each reason must be a single short sentence stating the failure. Do NOT include reasoning, analysis, corrections, or second-guessing.
               |
               |$rawData""".stripMargin

        callWithRetry(prompt)
    }

    private def callWithRetry(prompt: String): List[(Int, String)] = {
        var lastException: Exception = null
        for (attempt <- 0 to MAX_RETRIES) {
            try {
                val responseText = AIUtil.callAI(prompt)
                val text = AIUtil.extractText(responseText)
                return parseFailuresArray(text)
            } catch {
                case e: Exception if attempt < MAX_RETRIES =>
                    lastException = e
                    logger.warn("AI response parse failed (attempt " + (attempt + 1) + " of " + (MAX_RETRIES + 1) + "), retrying: " + e.getMessage)
            }
        }
        throw new DatrisException("AI validation failed after " + (MAX_RETRIES + 1) + " attempts: " + lastException.getMessage)
    }

    private def checkAiEnabled(): Unit = {
        if (!DatrisEnvironment.values.aiEnabled)
            throw new DatrisException("AI data quality rules require ai.enabled: true in application.yaml")
    }

    private def escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private def findMatchingBracket(text: String, start: Int): Int = {
        var depth = 0
        for (i <- start until text.length) {
            text.charAt(i) match {
                case '[' => depth += 1
                case ']' => depth -= 1; if (depth == 0) return i
                case _ =>
            }
        }
        -1
    }

    private def parseFailuresArray(text: String): List[(Int, String)] = {
        val start = text.indexOf('[')
        val end = if (start >= 0) findMatchingBracket(text, start) else -1
        if (start < 0 || end < 0)
            throw new DatrisException("AI validation response did not contain a JSON array. Response: " + text)

        val gson = new GsonBuilder().setLenient().create()
        val jsonArray = text.substring(start, end + 1)
        val resultList = gson.fromJson(jsonArray, classOf[java.util.List[java.util.Map[String, Any]]])
        if (resultList == null)
            throw new DatrisException("AI validation response could not be parsed as a JSON array")

        resultList.asScala.map { entry =>
            val index = entry.get("index") match {
                case d: java.lang.Double => d.toInt
                case i: java.lang.Integer => i.toInt
                case other => throw new DatrisException("AI validation response 'index' field is not a number: " + other)
            }
            val reason = Option(entry.get("reason")).map(_.toString).getOrElse("")
            (index, reason)
        }.toList
    }
}
