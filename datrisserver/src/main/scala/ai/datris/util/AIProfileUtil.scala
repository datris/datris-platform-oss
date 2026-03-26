package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Random

object AIProfileUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    def profile(fileContent: String, filename: String, delimiter: String, header: Boolean, sampleSize: Int): String = {
        if (!DatrisEnvironment.values.aiEnabled)
            throw new DatrisException("AI profiling requires ai.enabled: true in application.yaml")

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

        val responseText = AIUtil.callAI(prompt)
        val text = AIUtil.extractText(responseText).trim

        // Extract JSON object from response
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < 0)
            throw new DatrisException("AI profiling response did not contain a JSON object. Response: " + text)

        text.substring(start, end + 1)
    }
}
