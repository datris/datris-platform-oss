package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Data, DatrisEnvironment, DatrisException, StagedFormat, StagedPayload}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.{Files, Path}

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
          |- For CSV: write a header row as the FIRST line naming the output columns in order, then the data rows, using the same delimiter
          |- For JSON/XML: write the complete transformed document
          |- Use ONLY Python standard library (no pip packages)
          |- The script must be completely self-contained
          |- Preserve all columns unless the transformation explicitly adds or removes them""".stripMargin

    /**
     * Transform CSV data using CodeGen.
     * Generates a Python script via LLM, executes it locally. The script reads
     * the staged payload from a file and writes a file; its output is adopted
     * into the staging area, so the rows never pass through the JVM heap.
     *
     * @param instruction Plain-English transformation instruction
     * @param data        The delimited payload (header + staged rows)
     * @param delimiter   CSV delimiter
     * @return Transformed header (when the script emitted one, else the input header) and the staged rows
     */
    def transformCsv(instruction: String, data: Data, delimiter: String, pipelineName: String = null): CsvStagedResult = {
        val header = data.header
        val sampleRows = CloseableIterator.using(data.rowIterator())(_.take(MAX_SAMPLE_ROWS).toList)
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
               |Write a header row FIRST (the output column names, in order), then the data rows. Use the same delimiter.""".stripMargin

        val output = transform(userPrompt, data, "csv", instruction, pipelineName)
        try stageCsvOutput(output, header, data.rowCount, delimiter)
        finally Files.deleteIfExists(output)
    }

    /** Two passes over the script's output file, neither holding it in heap.
      * Pass 1 reads the first record, counts the records and checks whether the
      * first record recurs; [[decideHeader]] then applies exactly the rule
      * [[splitHeader]] applies to an in-memory list. Pass 2 stages the records
      * (skipping the header when there is one). Records are read quote-aware,
      * so a value holding an embedded newline stays one record; empty records
      * are dropped as the line filter always did. */
    private[util] def stageCsvOutput(output: Path, inputHeader: List[String], inputRowCount: Long, delimiter: String): CsvStagedResult = {
        def records(): CloseableIterator[String] = {
            val it = StagedRows.delimited(output.toString, delimiter)
            CloseableIterator(it.filter(_.nonEmpty), () => it.close())
        }
        var first: String = null
        var count = 0L
        var firstRecurs = false
        CloseableIterator.using(records()) { it =>
            while (it.hasNext) {
                val r = it.next()
                if (first == null) first = r
                else if (!firstRecurs && r == first) firstRecurs = true
                count += 1
            }
        }
        if (first == null) return CsvStagedResult(inputHeader, PayloadStager.stageRowIterator("transform", Iterator.empty, delimiter), headerFromScript = false)
        val headerFromScript = decideHeader(first, inputHeader, inputRowCount, count, firstRecurs, delimiter)
        val staged = CloseableIterator.using(records()) { it =>
            PayloadStager.stageRowIterator("transform", if (headerFromScript) it.drop(1) else it, delimiter)
        }
        val header = if (headerFromScript) splitLine(first, delimiter).map(_.trim) else inputHeader
        CsvStagedResult(header, staged, headerFromScript)
    }

    /** Output of a CSV transformation as staged rows: the header the script
      * emitted (or the input header when it emitted none) and the staged data. */
    case class CsvStagedResult(header: List[String], staged: StagedPayload, headerFromScript: Boolean)

    /** Output of a CSV transformation: the header the script emitted (or the
      * input header when it emitted none) and the data rows. */
    case class CsvTransformResult(header: List[String], rows: List[String], headerFromScript: Boolean)

    /** Decide whether the first output line is a header. It is when it equals
      * the input header, or when its tokens all look like column names
      * (non-empty, distinct, none numeric) and either the line count is one
      * more than the input (rows preserved + header prepended) or the line
      * never recurs as a data row. Otherwise the input header is kept — a
      * script that ignored the instruction and wrote data only must never
      * lose its first row. */
    private[datris] def splitHeader(lines: List[String], inputHeader: List[String], inputRowCount: Int, delimiter: String): CsvTransformResult = {
        if (lines.isEmpty) return CsvTransformResult(inputHeader, Nil, headerFromScript = false)
        if (decideHeader(lines.head, inputHeader, inputRowCount.toLong, lines.size.toLong, lines.tail.contains(lines.head), delimiter))
            CsvTransformResult(splitLine(lines.head, delimiter).map(_.trim), lines.tail, headerFromScript = true)
        else
            CsvTransformResult(inputHeader, lines, headerFromScript = false)
    }

    private val numeric = "^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$".r

    /** The pure header rule, over facts a streaming pass can gather without
      * holding the output: `first` is the first (non-empty) output record,
      * `recordCount` the number of output records and `firstRecurs` whether
      * `first` appears again as a later record. */
    private[datris] def decideHeader(
        first: String,
        inputHeader: List[String],
        inputRowCount: Long,
        recordCount: Long,
        firstRecurs: Boolean,
        delimiter: String
    ): Boolean = {
        val tokens = splitLine(first, delimiter).map(_.trim)
        val looksLikeHeader =
            tokens.nonEmpty && tokens.forall(t => t.nonEmpty && numeric.findFirstIn(t).isEmpty) && tokens.distinct.size == tokens.size
        val countSaysHeader = recordCount == inputRowCount + 1
        val sameAsInput = tokens.map(_.toLowerCase) == inputHeader.map(_.toLowerCase)
        sameAsInput || (looksLikeHeader && (countSaysHeader || !firstRecurs))
    }

    /** Minimal RFC4180-style split for one line (quotes, doubled quotes). */
    private[datris] def splitLine(line: String, delimiter: String): List[String] = {
        val d = if (delimiter == null || delimiter.isEmpty) ',' else delimiter.charAt(0)
        val out = List.newBuilder[String]; val cur = new StringBuilder; var inQ = false; var i = 0
        while (i < line.length) {
            val c = line.charAt(i)
            if (inQ) {
                if (c == '"' && i + 1 < line.length && line.charAt(i + 1) == '"') { cur.append('"'); i += 1 }
                else if (c == '"') inQ = false
                else cur.append(c)
            } else if (c == '"') inQ = true
            else if (c == d) { out += cur.toString; cur.clear() }
            else cur.append(c)
            i += 1
        }
        out += cur.toString
        out.result()
    }

    /**
     * Transform raw JSON/XML data using CodeGen. The script's output file is
     * adopted into the staging area: JSON is re-staged record per line (an
     * array explodes into records, as at ingest), XML verbatim.
     *
     * @param instruction Plain-English transformation instruction
     * @param data        The JSON or XML payload
     * @param isJson      True for JSON, false for XML
     * @return The transformed payload, staged
     */
    def transformRaw(instruction: String, data: Data, isJson: Boolean, pipelineName: String = null): StagedPayload = {
        val format = if (isJson) "JSON" else "XML"
        val sample = CodeGenRuleEvaluator.sampleDocument(data, 2000)

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

        val output = transform(userPrompt, data, format.toLowerCase, instruction, pipelineName)
        if (isJson) {
            // Same rule as Data.withRawData → stageRawString: JSON that parses is
            // staged as NDJSON, anything else is kept verbatim.
            val staged =
                try Some(PayloadStager.stageJson("transform", Files.newInputStream(output)))
                catch { case _: Exception => None }
            staged match {
                case Some(p) =>
                    Files.deleteIfExists(output)
                    p
                case None => PayloadStager.adopt("transform", output, StagedFormat.Text)
            }
        } else
            PayloadStager.adopt("transform", output, StagedFormat.Xml)
    }

    /** Generate the script, run it with the staged input as `sys.argv[1]` and a
      * fresh output file as `sys.argv[2]`, and return that output file's path.
      * The caller adopts (or stages from) the output and removes it. */
    private def transform(userPrompt: String, data: Data, fileExtension: String, instruction: String = null, pipelineName: String = null): Path = {
        logger.info("CodeGen Transformation: generating Python transformation script")

        // Step 1: Generate the Python script via LLM (uses codegen config when set)
        val codegenCfg = DatrisEnvironment.aiConfigForCodegen
        val responseText = AIUtil.callAIWithSystem(SYSTEM_PROMPT, userPrompt, codegenCfg)
        val scriptContent = AIUtil.extractText(responseText, codegenCfg)
        val cleanScript = cleanGeneratedScript(scriptContent)

        logger.info("CodeGen Transformation: generated script (" + cleanScript.length + " chars)")
        // Keep the last generated script per pipeline as evidence for
        // column-lineage inference (never fails the transformation).
        CodeGenScriptIO.write(pipelineName, "transformation", instruction, cleanScript)
        logger.info("CodeGen Transformation: script content:\n" + cleanScript)

        // Step 2: Stream the input into the staging area; script + output are temp files
        val inputFile: Path = CodeGenRuleEvaluator.stageInput(data, fileExtension)
        val outputFile: Path = Files.createTempFile("tx_output_", "." + fileExtension)
        val scriptFile: Path = Files.createTempFile("tx_codegen_", ".py")

        try {
            Files.write(scriptFile, cleanScript.getBytes("UTF-8"))

            // Step 3: Execute the script
            executeWithTimeout(scriptFile.toString, inputFile.toString, outputFile.toString, SCRIPT_TIMEOUT_SECONDS)
            logger.info("CodeGen Transformation: script executed successfully")

            // Step 4: Hand the output file back for adoption
            outputFile
        } catch {
            case e: DatrisException =>
                Files.deleteIfExists(outputFile)
                throw e
            case e: Exception =>
                Files.deleteIfExists(outputFile)
                logger.error("CodeGen Transformation script failed", e)
                throw new DatrisException("CodeGen transformation script failed: " + e.getMessage)
        } finally {
            Files.deleteIfExists(inputFile)
            Files.deleteIfExists(scriptFile)
        }
    }

    private def executeWithTimeout(scriptPath: String, inputPath: String, outputPath: String, timeoutSec: Int): Unit = {
        // SECURITY: the script is LLM-generated and shaped by untrusted ingested
        // data, so it runs through SandboxedPython — a scrubbed environment with
        // no platform secrets in os.environ. Never use scala.sys.process here;
        // it would inherit the JVM's full secret environment.
        val result = SandboxedPython.run(Seq("python3", scriptPath, inputPath, outputPath), timeoutSec)
        if (result.exitCode != 0) {
            val errOutput = result.stderr.take(1000)
            logger.error("CodeGen transformation script exited with code " + result.exitCode + ": " + errOutput)
            throw new DatrisException("CodeGen transformation script failed (exit code " + result.exitCode + "): " + errOutput)
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
