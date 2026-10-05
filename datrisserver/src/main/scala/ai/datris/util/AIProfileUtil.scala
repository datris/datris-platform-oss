package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, DatrisException}
import com.google.gson.{GsonBuilder, JsonArray, JsonNull, JsonObject, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Random

object AIProfileUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def profile(fileContent: String, filename: String, delimiter: String, header: Boolean, sampleSize: Int): String =
        profile(fileContent, filename, delimiter, header, sampleSize, prompt => AIUtil.extractText(AIUtil.callAI(prompt)))

    /** Seam for specs: `ai(prompt)` returns the model's extracted text. With
      * DATRIS_AI_SAMPLE_VALUES=false the model gets column statistics (CSV) or
      * a value-free skeleton (JSON/XML) instead of rows, and the result gains
      * top-level `"valuesWithheld": true`. */
    private[datris] def profile(fileContent: String, filename: String, delimiter: String, header: Boolean, sampleSize: Int, ai: String => String): String = {
        if (!DatrisEnvironment.current.aiEnabled)
            throw new DatrisException("AI profiling requires ai.enabled: true in application.yaml")

        if (!AiSampleValues.enabled) return profileWithheld(fileContent, filename, delimiter, header, ai)

        val isJson = filename.toLowerCase.endsWith(".json")
        val isXml = filename.toLowerCase.endsWith(".xml")

        val content = if (isJson || isXml) {
            // For JSON/XML, truncate if too large
            if (AIUtil.fitsInContext(fileContent)) fileContent
            else fileContent.substring(0, AIUtil.maxInputChars() - 2000)
        } else {
            // CSV — sample rows if needed
            val lines = fileContent.split("\n").toList
            val headerLine = if (header && lines.nonEmpty) lines.head else null
            val dataLines = if (header && lines.nonEmpty) lines.tail else lines

            if (dataLines.size <= sampleSize) {
                fileContent
            } else {
                val sampled = Random.shuffle(dataLines).take(sampleSize).sorted
                val sampledContent = if (headerLine != null) (headerLine +: sampled).mkString("\n") else sampled.mkString("\n")
                sampledContent
            }
        }

        logger.info("Profiling file: " + filename + ", content length: " + content.length + " chars")

        val formatDescription = if (isJson) "JSON" else if (isXml) "XML" else "CSV (delimiter: \"" + delimiter + "\")"

        val prompt =
            s"""You are a data profiling expert. Analyze the following $formatDescription file and return a JSON profile.
               |
               |Return ONLY a JSON object with no explanation, no markdown, and no code fences. Use this structure:
               |{
               |  "summary": {
               |    "rowCount": <number of data rows>,
               |    "columnCount": <number of columns>,
               |    "columns": [
               |      {
               |        "name": "<column name>",
               |        "inferredType": "<string|integer|float|boolean|date|timestamp>",
               |        "nullCount": <number of null/empty values>,
               |        "uniqueCount": <approximate unique values>,
               |        "sampleValues": ["<up to 3 sample values>"]
               |      }
               |    ]
               |  },
               |  "qualityIssues": [
               |    "<description of each issue found, e.g. missing values, outliers, inconsistent formats>"
               |  ],
               |  "recommendations": [
               |    "<suggested validation rules or transformations>"
               |  ],
               |  "suggestedDataQuality": {
               |    "aiRule": {
               |      "instruction": "<a single natural language instruction combining ALL validation checks — structural patterns (emails, phone numbers, zip codes, dates), value ranges, cross-column relationships, and business logic>",
               |      "onFailureIsError": false
               |    }
               |  }
               |}
               |
               |For suggestedDataQuality:
               |- The aiRule instruction should be a comprehensive plain-English rule covering all validations: format checks, value ranges, cross-column relationships, and business logic.
               |- Combine all checks into one instruction. Datris will generate a Python validation script from this instruction.
               |- If no validation rule is appropriate, omit the aiRule field.
               |
               |$content""".stripMargin

        extractObject(ai(prompt))
    }

    private def extractObject(answer: String): String = {
        val text = answer.trim

        // Extract JSON object from response
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < 0)
            throw new DatrisException("AI profiling response did not contain a JSON object. Response: " + text)

        text.substring(start, end + 1)
    }

    /** Profile from structure only: per-column statistics over every row
      * (CSV) or the skeleton of the whole document (JSON/XML). No row value is
      * in the prompt; `sampleValues` come back empty. */
    private def profileWithheld(fileContent: String, filename: String, delimiter: String, header: Boolean, ai: String => String): String = {
        val lower = filename.toLowerCase
        val ext = lower.lastIndexOf('.') match {
            case -1 => ""
            case i => lower.substring(i + 1)
        }
        val head = AiSampleValues.firstNonBlank(fileContent)
        // Known extensions decide; content is sniffed only for unknown ones, so
        // a JSON/NDJSON/XML file never takes the delimited path.
        val delimitedExt = Set("csv", "tsv", "psv", "txt").contains(ext)
        val knownExt = delimitedExt || Set("json", "ndjson", "jsonl", "xml").contains(ext)
        val isJson = Set("json", "ndjson", "jsonl").contains(ext) || (!knownExt && (head == '{' || head == '['))
        val isXml = !isJson && (ext == "xml" || (!knownExt && head == '<'))

        // Local statistics (CSV only): (row count, per-column stats), for the
        // statistics-only answer when the model's reply cannot be parsed.
        var localStats: Option[(Int, List[ColumnStat])] = None

        val (formatDescription, evidence) =
            if (isJson || isXml) {
                // Built from the full input; the skeleton is truncated, never the input.
                val skeleton = if (isJson) AiSampleValues.jsonSkeleton(fileContent) else AiSampleValues.xmlSkeleton(fileContent)
                val fitted = if (AIUtil.fitsInContext(skeleton)) skeleton else skeleton.substring(0, math.max(0, AIUtil.maxInputChars() - 2000))
                val kind = if (isJson) "JSON" else "XML"
                val legend =
                    if (isJson)
                        "every string is \"<string>\", every number 0, every boolean true; arrays keep one element per distinct shape; maps keyed by data show \"<key>\""
                    else "element and attribute names kept, text, attribute values and namespace URIs removed; repeated elements are marked"
                (kind, "File size: " + fileContent.length + " chars\nValue-free " + kind + " skeleton (" + legend + "):\n" + fitted)
            } else {
                val d = if (delimiter == null) "," else delimiter
                val splitOn = if (d == "\\t") "\t" else d
                val lines = fileContent.split("\n").iterator.map(_.stripSuffix("\r")).filter(_.nonEmpty).toList
                val firstCells = lines.headOption.map(l => CodeGenTransformationEvaluator.splitLine(l, splitOn)).getOrElse(Nil)
                // Line 1 is a header only when the caller says so and it reads as names;
                // a name that is not an identifier is never sent (column_N instead).
                val lineOneIsHeader = header && lines.nonEmpty && !AiSampleValues.headerLooksLikeData(firstCells)
                val names = AiSampleValues.safeColumnNames(firstCells, lineOneIsHeader)
                val dataLines = if (lineOneIsHeader) lines.tail else lines
                val stats = AiSampleValues.columnStats(names, dataLines.iterator, d)
                localStats = Some((dataLines.size, stats))
                (
                    "CSV (delimiter: \"" + delimiter + "\")",
                    "Rows: " + dataLines.size + "\nColumns: " + names.size + "\nPer-column statistics computed by the server over every row " +
                        "(nulls are empty values; lengths are of non-empty values; column_N names a column whose header is withheld or absent):\n" +
                        AiSampleValues.statsTable(stats)
                )
            }

        logger.info("Profiling file: " + filename + " with values withheld (" + AiSampleValues.EnvVar + "=false)")

        val prompt =
            s"""You are a data profiling expert. Profile the following $formatDescription file. Values withheld; infer from structure only.
               |The server's configuration withholds the file's values, so you are given its structure and statistics instead of its rows.
               |
               |Return ONLY a JSON object with no explanation, no markdown, and no code fences. Use this structure:
               |{
               |  "summary": {
               |    "rowCount": <number of data rows>,
               |    "columnCount": <number of columns>,
               |    "columns": [
               |      {
               |        "name": "<column name>",
               |        "inferredType": "<string|integer|float|boolean|date|timestamp>",
               |        "nullCount": <number of null/empty values>,
               |        "uniqueCount": <approximate unique values>,
               |        "sampleValues": []
               |      }
               |    ]
               |  },
               |  "qualityIssues": [
               |    "<description of each issue the statistics show, e.g. missing values, inconsistent lengths>"
               |  ],
               |  "recommendations": [
               |    "<suggested validation rules or transformations>"
               |  ],
               |  "suggestedDataQuality": {
               |    "aiRule": {
               |      "instruction": "<a single natural language instruction combining the validation checks the column names, types and statistics support>",
               |      "onFailureIsError": false
               |    }
               |  }
               |}
               |
               |Rules:
               |- sampleValues MUST be an empty array for every column: no values were provided.
               |- Use the statistics for rowCount, nullCount and uniqueCount when they are given.
               |- If no validation rule is appropriate, omit the aiRule field.
               |
               |$evidence""".stripMargin

        val answer = ai(prompt)
        val parsed =
            try Some(JsonParser.parseString(extractObject(answer)).getAsJsonObject)
            catch {
                case e: Exception =>
                    logger.warn("AI profile reply could not be parsed (" + e.getClass.getSimpleName + "); returning statistics only")
                    None
            }
        if (parsed.isEmpty) return statisticsOnly(localStats)
        val obj = parsed.get
        // The model saw no values; make sure none is reported as one.
        Option(obj.get("summary")).filter(_.isJsonObject).map(_.getAsJsonObject.get("columns")).filter(c => c != null && c.isJsonArray).foreach { cols =>
            cols.getAsJsonArray.forEach(c =>
                if (c.isJsonObject && c.getAsJsonObject.has("sampleValues")) c.getAsJsonObject.add("sampleValues", new JsonArray())
            )
        }
        obj.addProperty("valuesWithheld", true)
        obj.toString
    }

    /** Off-mode answer when the model's reply is not JSON (e.g. cut off on a
      * wide file): the locally computed statistics, no analysis. */
    private def statisticsOnly(localStats: Option[(Int, List[ColumnStat])]): String = {
        val out = new JsonObject()
        val summary = new JsonObject()
        val columns = new JsonArray()
        localStats.foreach {
            case (rows, stats) =>
                summary.addProperty("rowCount", rows)
                summary.addProperty("columnCount", stats.size)
                stats.foreach { st =>
                    val c = new JsonObject()
                    c.addProperty("name", st.name)
                    c.addProperty("inferredType", st.inferredType)
                    c.addProperty("nullCount", st.nulls)
                    c.addProperty("uniqueCount", st.distinct)
                    c.addProperty("minLength", st.minLength)
                    c.addProperty("maxLength", st.maxLength)
                    c.add("sampleValues", new JsonArray())
                    columns.add(c)
                }
        }
        summary.add("columns", columns)
        out.add("summary", summary)
        out.add("analysis", JsonNull.INSTANCE)
        out.addProperty("valuesWithheld", true)
        out.addProperty("note", "the model's reply could not be parsed; statistics only")
        new GsonBuilder().serializeNulls().create().toJson(out)
    }
}
