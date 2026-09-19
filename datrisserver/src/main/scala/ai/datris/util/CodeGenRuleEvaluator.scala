package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Data, DatrisEnvironment, DatrisException, StagedFormat, StagedPayload}
import com.google.gson.{GsonBuilder, JsonArray}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
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
     * The script reads the staged payload (header line first) from a file; the
     * rows never pass through the JVM heap.
     *
     * @param rule      Plain-English validation rule
     * @param data      The delimited payload (header + staged rows)
     * @param delimiter CSV delimiter
     * @return List of (rowIndex, failureReason) tuples
     */
    def evaluateCsv(rule: String, data: Data, delimiter: String): List[(Int, String)] = {
        val sampleRows = CloseableIterator.using(data.rowIterator())(_.take(MAX_SAMPLE_ROWS).toList)
        val headerLine = data.header.mkString(delimiter)

        val userPrompt =
            s"""Format: CSV (delimiter: "${escapeDelimiter(delimiter)}")
               |Columns: $headerLine
               |Sample rows:
               |${sampleRows.mkString("\n")}
               |
               |Rule: "$rule"
               |
               |The CSV file has a header row as the first line. Read with the csv module using the appropriate delimiter.""".stripMargin

        evaluate(userPrompt, data)
    }

    /**
     * Evaluate a plain-English rule against raw JSON/XML data using CodeGen.
     *
     * @param rule   Plain-English validation rule
     * @param data   The JSON or XML payload
     * @param isJson True for JSON, false for XML
     * @return List of (recordIndex, failureReason) tuples
     */
    def evaluateRaw(rule: String, data: Data, isJson: Boolean): List[(Int, String)] = {
        val format = if (isJson) "JSON" else "XML"
        val sample = sampleDocument(data, 2000)

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

        evaluate(userPrompt, data)
    }

    /** The first `chars` characters of the document exactly as the script will
      * see it (a JSON array re-wrapped in `[...]`, NDJSON line per record, XML
      * verbatim) — read from the staged file, never the whole payload. */
    private[util] def sampleDocument(data: Data, chars: Int): String = {
        val sb = new java.lang.StringBuilder(chars)
        val staged = data.staged
        if (staged == null || staged.isEmpty) return ""
        if (data.isNdJson) {
            val open = if (staged.arraySource) "[" else ""
            val sep = if (staged.arraySource) "," else "\n"
            sb.append(open)
            val it = data.recordIterator()
            try {
                var first = true
                while (it.hasNext && sb.length < chars) {
                    if (!first) sb.append(sep)
                    sb.append(it.next())
                    first = false
                }
                if (!it.hasNext && staged.arraySource) sb.append("]")
            } finally it.close()
        } else {
            val reader = Files.newBufferedReader(Paths.get(staged.path), StandardCharsets.UTF_8)
            try {
                val buf = new Array[Char](chars)
                var n = reader.read(buf)
                while (n > 0 && sb.length < chars) {
                    sb.append(buf, 0, n)
                    n = reader.read(buf)
                }
            } finally reader.close()
        }
        val text = sb.toString
        if (text.length > chars) text.substring(0, chars) else text
    }

    /** The file handed to a CodeGen script as `sys.argv[1]`, written into the
      * run's staging area (so `JobRunner`'s cleanup reclaims it) by streaming
      * the staged payload — the script contract is unchanged:
      *  - delimited: header line, then the staged rows (byte-equal to the
      *    `(headerLine +: rows).mkString("\n")` the script used to get);
      *  - JSON: one array `[...]` when the payload arrived as an array, else
      *    the NDJSON lines as they are;
      *  - XML / text: the document verbatim.
      * Shared with `CodeGenTransformationEvaluator`. */
    private[util] def stageInput(data: Data): Path = {
        val staged = if (data.staged == null) StagedPayload.empty else data.staged
        staged.format match {
            case StagedFormat.Delimited(delimiter) =>
                val (path, writer) = StagingArea.newWriter("codegen-input", "csv")
                try {
                    if (data.header != null) writer.write(data.header.mkString(delimiter))
                    if (!staged.isEmpty && staged.rowCount > 0) {
                        if (data.header != null) writer.write("\n")
                        copyText(data.openStream(), writer)
                    }
                } finally writer.close()
                path
            case StagedFormat.NdJson =>
                val (path, writer) = StagingArea.newWriter("codegen-input", "json")
                try {
                    if (staged.arraySource) writer.write("[")
                    val it = data.recordIterator()
                    try {
                        var first = true
                        while (it.hasNext) {
                            if (!first) writer.write(if (staged.arraySource) "," else "\n")
                            writer.write(it.next())
                            first = false
                        }
                    } finally it.close()
                    if (staged.arraySource) writer.write("]")
                } finally writer.close()
                path
            case other =>
                val (path, writer) = StagingArea.newWriter("codegen-input", other.extension)
                try copyText(data.openStream(), writer)
                finally writer.close()
                path
        }
    }

    private def copyText(in: java.io.InputStream, out: java.io.Writer): Unit = {
        val reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)
        try {
            val buf = new Array[Char](64 * 1024)
            var n = reader.read(buf)
            while (n >= 0) {
                if (n > 0) out.write(buf, 0, n)
                n = reader.read(buf)
            }
        } finally reader.close()
    }

    private def evaluate(userPrompt: String, data: Data): List[(Int, String)] = {
        logger.info("CodeGen DQ: generating Python validation script")

        // Step 1: Generate the Python script via LLM (uses codegen config when set)
        val codegenCfg = DatrisEnvironment.aiConfigForCodegen
        val responseText = AIUtil.callAIWithSystem(SYSTEM_PROMPT, userPrompt, codegenCfg)
        val scriptContent = AIUtil.extractText(responseText, codegenCfg)
        val cleanScript = cleanGeneratedScript(scriptContent)

        logger.info("CodeGen DQ: generated script (" + cleanScript.length + " chars)")
        logger.info("CodeGen DQ: script content:\n" + cleanScript)

        // Step 2: Stream the data into the staging area; the script goes to a temp file
        val dataFile: Path = stageInput(data)
        val scriptFile: Path = Files.createTempFile("dq_codegen_", ".py")

        try {
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
