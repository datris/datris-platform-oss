package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException}
import com.google.gson.{GsonBuilder, JsonArray}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

object CodeGenRuleEvaluator {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val SCRIPT_TIMEOUT_SECONDS = 300
    private val MAX_SAMPLE_ROWS = 5

    private val SYSTEM_PROMPT =
        """You are a code generator. Output ONLY a valid Python 3 script with no explanation,
          |no markdown fences, and no commentary. The script must:
          |- Accept a data file path as sys.argv[1]
          |- Read and parse the file appropriately based on the format described
          |- Validate every record against the rule provided
          |- Print a JSON array to stdout: [{"index": <record_number>, "reason": "..."}]
          |- If all records pass, print: []
          |- Use 0-based record indexing (first data record = 0)
          |- Handle edge cases: empty values, whitespace, encoding
          |- Use ONLY Python standard library (no pip packages)
          |- The script must be completely self-contained""".stripMargin

    /**
     * Evaluate a plain-English rule against CSV data using CodeGen.
     * Generates a Python script via LLM, executes it locally against the data.
     *
     * @param rule      Plain-English validation rule
     * @param header    CSV column names
     * @param rows      CSV data rows
     * @param delimiter CSV delimiter
     * @return List of (rowIndex, failureReason) tuples
     */
    def evaluateCsv(rule: String, header: List[String], rows: List[String], delimiter: String): List[(Int, String)] = {
        val sampleRows = rows.take(MAX_SAMPLE_ROWS)
        val headerLine = header.mkString(delimiter)

        val userPrompt =
            s"""Format: CSV (delimiter: "${escapeDelimiter(delimiter)}")
               |Columns: $headerLine
               |Sample rows:
               |${sampleRows.mkString("\n")}
               |
               |Rule: "$rule"
               |
               |The CSV file has a header row as the first line. Read with the csv module using the appropriate delimiter.""".stripMargin

        // Write CSV data to temp file
        val csvContent = (headerLine +: rows).mkString("\n")
        evaluate(userPrompt, csvContent, "csv")
    }

    /**
     * Evaluate a plain-English rule against raw JSON/XML data using CodeGen.
     *
     * @param rule    Plain-English validation rule
     * @param rawData The raw JSON or XML content
     * @param isJson  True for JSON, false for XML
     * @return List of (recordIndex, failureReason) tuples
     */
    def evaluateRaw(rule: String, rawData: String, isJson: Boolean): List[(Int, String)] = {
        val format = if (isJson) "JSON" else "XML"
        val sample = rawData.take(2000)

        val parseInstruction = if (isJson) {
            "Parse the file as a JSON array of objects using the json module."
        } else {
            "Parse the file as XML. Each child element of the root is one record. Use xml.etree.ElementTree."
        }

        val userPrompt =
            s"""Format: $format
               |Sample data (first 2000 chars):
               |$sample
               |
               |Rule: "$rule"
               |
               |$parseInstruction""".stripMargin

        evaluate(userPrompt, rawData, format.toLowerCase)
    }

    private def evaluate(userPrompt: String, fileContent: String, fileExtension: String): List[(Int, String)] = {
        logger.info("CodeGen DQ: generating Python validation script")

        // Step 1: Generate the Python script via LLM (uses codegen config when set)
        val codegenCfg = DatrisEnvironment.aiConfigForCodegen
        val responseText = AIUtil.callAIWithSystem(SYSTEM_PROMPT, userPrompt, codegenCfg)
        val scriptContent = AIUtil.extractText(responseText, codegenCfg)
        val cleanScript = cleanGeneratedScript(scriptContent)

        logger.info("CodeGen DQ: generated script (" + cleanScript.length + " chars)")
        logger.info("CodeGen DQ: script content:\n" + cleanScript)

        // Step 2: Write data and script to temp files
        val dataFile: Path = Files.createTempFile("dq_data_", "." + fileExtension)
        val scriptFile: Path = Files.createTempFile("dq_codegen_", ".py")

        try {
            Files.write(dataFile, fileContent.getBytes("UTF-8"))
            Files.write(scriptFile, cleanScript.getBytes("UTF-8"))

            // Step 3: Execute the script
            val result = executeWithTimeout(scriptFile.toString, dataFile.toString, SCRIPT_TIMEOUT_SECONDS)
            logger.info("CodeGen DQ: script executed, output length: " + result.length + " chars")

            // Step 4: Parse the JSON result
            parseFailures(result)
        } catch {
            case e: DatrisException => throw e
            case e: Exception =>
                logger.error("CodeGen DQ script failed", e)
                throw new DatrisException("CodeGen data quality script failed: " + e.getMessage)
        } finally {
            Files.deleteIfExists(dataFile)
            Files.deleteIfExists(scriptFile)
        }
    }

    private def executeWithTimeout(scriptPath: String, dataPath: String, timeoutSec: Int): String = {
        // SECURITY: the script is LLM-generated and shaped by untrusted ingested
        // data, so it runs through SandboxedPython — a scrubbed environment with
        // no platform secrets in os.environ. Never use scala.sys.process here;
        // it would inherit the JVM's full secret environment.
        val result = SandboxedPython.run(Seq("python3", scriptPath, dataPath), timeoutSec)
        if (result.exitCode != 0) {
            val errOutput = result.stderr.take(1000)
            logger.error("CodeGen script exited with code " + result.exitCode + ": " + errOutput)
            throw new DatrisException("CodeGen validation script failed (exit code " + result.exitCode + "): " + errOutput)
        }
        result.stdout
    }

    private def cleanGeneratedScript(script: String): String = {
        var cleaned = script.trim
        // Remove markdown code fences if present
        if (cleaned.startsWith("```python"))
            cleaned = cleaned.stripPrefix("```python").trim
        else if (cleaned.startsWith("```"))
            cleaned = cleaned.stripPrefix("```").trim
        if (cleaned.endsWith("```"))
            cleaned = cleaned.stripSuffix("```").trim
        cleaned
    }

    private def parseFailures(output: String): List[(Int, String)] = {
        if (output.isEmpty) return List.empty

        val start = output.indexOf('[')
        val end = findMatchingBracket(output, start)
        if (start < 0 || end < 0) {
            val lower = output.toLowerCase
            if (lower.contains("no failures") || lower.contains("all pass") || lower.contains("[]"))
                return List.empty
            throw new DatrisException("CodeGen script output did not contain a JSON array. Output: " + output.take(500))
        }

        val jsonArray = output.substring(start, end + 1).trim
        if (jsonArray == "[]") return List.empty

        val gson = new GsonBuilder().setLenient().create()
        val resultList = gson.fromJson(jsonArray, classOf[java.util.List[java.util.Map[String, Any]]])
        if (resultList == null || resultList.isEmpty)
            return List.empty

        resultList.asScala.flatMap { entry =>
            val index = entry.get("index") match {
                case d: java.lang.Double => d.toInt
                case i: java.lang.Integer => i.toInt
                case _ => -1
            }
            val reason = Option(entry.get("reason")).map(_.toString).getOrElse("")
            if (index < 0 || reason.isEmpty)
                None
            else
                Some((index, reason))
        }.toList
    }

    private def findMatchingBracket(text: String, start: Int): Int = {
        if (start < 0) return -1
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

    private def escapeDelimiter(d: String): String = d match {
        case "\t" => "\\t"
        case other => other
    }
}
