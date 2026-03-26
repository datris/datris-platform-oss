package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.DatrisException
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.{Files, Path}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process._

object CodeGenTransformationEvaluator {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val SCRIPT_TIMEOUT_SECONDS = 300
    private val MAX_SAMPLE_ROWS = 5

    private val SYSTEM_PROMPT =
        """You are a code generator. Output ONLY a valid Python 3 script with no explanation,
          |no markdown fences, and no commentary. The script must:
          |- Accept an input data file path as sys.argv[1] and an output file path as sys.argv[2]
          |- Read and parse the input file appropriately based on the format described
          |- Apply the transformation described to every record
          |- Write the transformed data to the output file in the SAME format as the input
          |- For CSV: write data rows only (NO header row in output), using the same delimiter
          |- For JSON/XML: write the complete transformed document
          |- Use ONLY Python standard library (no pip packages)
          |- The script must be completely self-contained
          |- Preserve all columns unless the transformation explicitly adds or removes them""".stripMargin

    /**
     * Transform CSV data using CodeGen.
     * Generates a Python script via LLM, executes it locally.
     *
     * @param instruction Plain-English transformation instruction
     * @param header      CSV column names
     * @param rows        CSV data rows
     * @param delimiter   CSV delimiter
     * @return Transformed rows (no header)
     */
    def transformCsv(instruction: String, header: List[String], rows: List[String], delimiter: String): List[String] = {
        val sampleRows = rows.take(MAX_SAMPLE_ROWS)
        val headerLine = header.mkString(delimiter)

        val userPrompt =
            s"""Format: CSV (delimiter: "${escapeDelimiter(delimiter)}")
               |Columns: $headerLine
               |Sample rows:
               |${sampleRows.mkString("\n")}
               |
               |Transformation: "$instruction"
               |
               |The input CSV file has a header row as the first line. Read with the csv module using the appropriate delimiter.
               |Write ONLY data rows to the output file (no header). Use the same delimiter.""".stripMargin

        val csvContent = (headerLine +: rows).mkString("\n")
        val result = transform(userPrompt, csvContent, "csv")

        // Split into rows, filter empties
        result.split("\n").toList.filter(_.nonEmpty)
    }

    /**
     * Transform raw JSON/XML data using CodeGen.
     *
     * @param instruction Plain-English transformation instruction
     * @param rawData     The raw JSON or XML content
     * @param isJson      True for JSON, false for XML
     * @return Transformed raw content
     */
    def transformRaw(instruction: String, rawData: String, isJson: Boolean): String = {
        val format = if (isJson) "JSON" else "XML"
        val sample = rawData.take(2000)

        val parseInstruction = if (isJson) {
            "Parse the file as JSON using the json module. Write the transformed JSON to the output file."
        } else {
            "Parse the file as XML using xml.etree.ElementTree. Write the transformed XML to the output file."
        }

        val userPrompt =
            s"""Format: $format
               |Sample data (first 2000 chars):
               |$sample
               |
               |Transformation: "$instruction"
               |
               |$parseInstruction""".stripMargin

        transform(userPrompt, rawData, format.toLowerCase)
    }

    private def transform(userPrompt: String, fileContent: String, fileExtension: String): String = {
        logger.info("CodeGen Transformation: generating Python transformation script")

        // Step 1: Generate the Python script via LLM
        val responseText = AIUtil.callAIWithSystem(SYSTEM_PROMPT, userPrompt)
        val scriptContent = AIUtil.extractText(responseText)
        val cleanScript = cleanGeneratedScript(scriptContent)

        logger.info("CodeGen Transformation: generated script (" + cleanScript.length + " chars)")

        // Step 2: Write data and script to temp files
        val inputFile: Path = Files.createTempFile("tx_input_", "." + fileExtension)
        val outputFile: Path = Files.createTempFile("tx_output_", "." + fileExtension)
        val scriptFile: Path = Files.createTempFile("tx_codegen_", ".py")

        try {
            Files.write(inputFile, fileContent.getBytes("UTF-8"))
            Files.write(scriptFile, cleanScript.getBytes("UTF-8"))

            // Step 3: Execute the script
            val result = executeWithTimeout(scriptFile.toString, inputFile.toString, outputFile.toString, SCRIPT_TIMEOUT_SECONDS)
            logger.info("CodeGen Transformation: script executed successfully")

            // Step 4: Read the output file
            new String(Files.readAllBytes(outputFile), "UTF-8").trim
        } catch {
            case e: DatrisException => throw e
            case e: Exception =>
                logger.error("CodeGen Transformation script failed", e)
                throw new DatrisException("CodeGen transformation script failed: " + e.getMessage)
        } finally {
            Files.deleteIfExists(inputFile)
            Files.deleteIfExists(outputFile)
            Files.deleteIfExists(scriptFile)
        }
    }

    private def executeWithTimeout(scriptPath: String, inputPath: String, outputPath: String, timeoutSec: Int): Unit = {
        val stderr = new StringBuilder
        val processLogger = ProcessLogger(
            _ => (), // ignore stdout
            line => stderr.append(line).append("\n")
        )

        val process = Process(Seq("python3", scriptPath, inputPath, outputPath))
        val future = Future {
            process.!(processLogger)
        }

        try {
            val exitCode = Await.result(future, timeoutSec.seconds)
            if (exitCode != 0) {
                val errOutput = stderr.toString.take(1000)
                logger.error("CodeGen transformation script exited with code " + exitCode + ": " + errOutput)
                throw new DatrisException("CodeGen transformation script failed (exit code " + exitCode + "): " + errOutput)
            }
        } catch {
            case _: java.util.concurrent.TimeoutException =>
                throw new DatrisException("CodeGen transformation script timed out after " + timeoutSec + " seconds")
            case e: DatrisException => throw e
            case e: Exception =>
                throw new DatrisException("CodeGen transformation script execution error: " + e.getMessage)
        }
    }

    private def cleanGeneratedScript(script: String): String = {
        var cleaned = script.trim
        if (cleaned.startsWith("```python"))
            cleaned = cleaned.stripPrefix("```python").trim
        else if (cleaned.startsWith("```"))
            cleaned = cleaned.stripPrefix("```").trim
        if (cleaned.endsWith("```"))
            cleaned = cleaned.stripSuffix("```").trim
        cleaned
    }

    private def escapeDelimiter(d: String): String = d match {
        case "\t" => "\\t"
        case other => other
    }
}
