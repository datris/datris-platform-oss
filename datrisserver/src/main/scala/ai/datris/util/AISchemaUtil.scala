package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{AIRefusalException, DatrisEnvironment, DatrisException}
import com.google.gson.{GsonBuilder, JsonParser}
import org.slf4j.{Logger, LoggerFactory}

object AISchemaUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private val MAX_CONTENT_LINES = 100

    def buildCsvConfigAllStrings(pipeline: String, fileContent: String, delimiter: String, header: Boolean): String = {
        val myDelimiter = if (delimiter == null) "," else delimiter
        val firstLine = fileContent.split("\n").head
        val delimChar = if (myDelimiter == "\\t") "\t" else myDelimiter
        val fields = firstLine.split(java.util.regex.Pattern.quote(delimChar), -1)
            .map(_.trim.replaceAll("\"", "").replaceAll("'", ""))
        val fieldsJson = fields.map(f => s"""{"name":"$f","type":"string"}""").mkString("[", ",", "]")

        logger.info("Building all-string CSV config for pipeline: " + pipeline + ", fields: " + fields.length)

        buildConfig(
            pipeline = pipeline,
            fieldsJson = fieldsJson,
            sourceAttributesJson = s""""csvAttributes": { "delimiter": "$myDelimiter", "header": $header, "encoding": "UTF-8" }""",
            usePostgres = true,
            useMongoDB = false
        )
    }

    /** DATRIS_AI_SAMPLE_VALUES=false: all-string fields, split exactly as
      * [[buildCsvConfigAllStrings]] does, but named by
      * [[AiSampleValues.safeColumnNames]] so a value never becomes a field
      * name: `column_1..N` when `header` is false or line 1 reads as data,
      * and `column_N` for any header cell that is not an identifier. When the
      * columns are numbered because line 1 is data, `csvAttributes.header` is
      * written as false. */
    private def buildCsvConfigWithheld(pipeline: String, fileContent: String, delimiter: String, header: Boolean): String = {
        val myDelimiter = if (delimiter == null) "," else delimiter
        val cells = firstLineCells(fileContent, myDelimiter)
        val fields = AiSampleValues.safeColumnNames(cells, header)
        // Columns numbered because line 1 is data: the saved pipeline must not
        // skip that line as a header.
        val lineOneIsHeader = header && !AiSampleValues.headerLooksLikeData(cells)
        val fieldsJson = fields.map(f => s"""{"name":"$f","type":"string"}""").mkString("[", ",", "]")

        logger.info("Building all-string CSV config (values withheld) for pipeline: " + pipeline + ", fields: " + fields.length)

        buildConfig(
            pipeline = pipeline,
            fieldsJson = fieldsJson,
            sourceAttributesJson = s""""csvAttributes": { "delimiter": "$myDelimiter", "header": $lineOneIsHeader, "encoding": "UTF-8" }""",
            usePostgres = true,
            useMongoDB = false
        )
    }

    /** Line 1 split exactly as [[buildCsvConfigAllStrings]] does (BOM removed). */
    private def firstLineCells(fileContent: String, delimiter: String): List[String] = {
        // limit -1: a newline-only file still has a (blank) first line.
        val firstLine = fileContent.stripPrefix("\uFEFF").split("\n", -1).head
        val delimChar = if (delimiter == "\\t") "\t" else delimiter
        firstLine.split(java.util.regex.Pattern.quote(delimChar), -1).map(_.trim.replaceAll("\"", "").replaceAll("'", "")).toList
    }

    /** On-mode fallback when the model declines: the header was already sent
      * to the model, so a real header keeps its names exactly as written (as
      * [[buildCsvConfigAllStrings]] writes them), except that a blank cell
      * (empty, whitespace or invisible characters only), or a name already
      * used (ignoring case and padding), takes `column_N` for its
      * position, so the result never has a blank or duplicate field name (an
      * empty file gives `column_1`, as the values-withheld result does). When
      * `header` is false or line 1 reads as data, the columns are numbered and
      * `csvAttributes.header` is false, as in the values-withheld result.
      * Top-level `"aiDeclined": true`; never `valuesWithheld`. */
    private def buildCsvConfigDeclined(pipeline: String, fileContent: String, delimiter: String, header: Boolean): String = {
        val myDelimiter = if (delimiter == null) "," else delimiter
        val cells = firstLineCells(fileContent, myDelimiter)
        val lineOneIsHeader = header && !AiSampleValues.headerLooksLikeData(cells)
        val base =
            if (lineOneIsHeader) {
                logger.info("Building all-string CSV config (model declined) for pipeline: " + pipeline + ", fields: " + cells.size)
                buildConfig(
                    pipeline = pipeline,
                    fieldsJson = stringFieldsJson(declinedHeaderNames(cells)),
                    sourceAttributesJson = s""""csvAttributes": { "delimiter": "$myDelimiter", "header": true, "encoding": "UTF-8" }""",
                    usePostgres = true,
                    useMongoDB = false
                )
            } else buildCsvConfigWithheld(pipeline, fileContent, delimiter, header)
        val config = JsonParser.parseString(base).getAsJsonObject
        config.addProperty("aiDeclined", true)
        new GsonBuilder().setPrettyPrinting().create().toJson(config)
    }

    /** Header names for the decline fallback: each non-blank cell as written,
      * first occurrence wins; a blank or repeated cell becomes `column_N` (N =
      * its position), suffixed `_2`, `_3`, … only if that name is also taken. */
    private[util] def declinedHeaderNames(cells: List[String]): List[String] = {
        val kept = scala.collection.mutable.Set.empty[String]
        val firstUse = cells.map { c =>
            val key = nameKey(c)
            key.nonEmpty && kept.add(key)
        }
        val used = scala.collection.mutable.Set.empty[String] ++ kept
        cells.zip(firstUse).zipWithIndex.map {
            case ((c, true), _) => c
            case ((_, false), i) =>
                val baseName = "column_" + (i + 1)
                var name = baseName
                var k = 2
                while (used.contains(nameKey(name))) { name = baseName + "_" + k; k += 1 }
                used += nameKey(name)
                name
        }
    }

    /** What a header cell names, for blank and duplicate checks: invisible
      * characters (byte-order mark, zero-width space/joiners, word joiner)
      * removed, Unicode whitespace stripped from both ends, lower-cased. */
    private def nameKey(c: String): String =
        c.replaceAll("[\\uFEFF\\u200B-\\u200D\\u2060]", "").strip().toLowerCase(java.util.Locale.ROOT)

    /** `[{"name":…,"type":"string"},…]` with each name JSON-escaped. */
    private def stringFieldsJson(names: List[String]): String = {
        val arr = new com.google.gson.JsonArray()
        names.foreach { n =>
            val f = new com.google.gson.JsonObject()
            f.addProperty("name", n)
            f.addProperty("type", "string")
            arr.add(f)
        }
        arr.toString
    }

    def buildCsvConfig(pipeline: String, fileContent: String, delimiter: String, header: Boolean): String =
        buildCsvConfig(pipeline, fileContent, delimiter, header, prompt => callCodegen(prompt, "CSV schema generation, pipeline: " + pipeline))

    /** Seam for specs: `ai(prompt)` returns the model's extracted text. With
      * DATRIS_AI_SAMPLE_VALUES=false no model is called and every field is a
      * string named from the header ([[buildCsvConfigAllStrings]]). With the
      * switch on, a model decline ([[AIRefusalException]]) returns
      * [[buildCsvConfigDeclined]]. */
    private[datris] def buildCsvConfig(pipeline: String, fileContent: String, delimiter: String, header: Boolean, ai: String => String): String = {
        if (!AiSampleValues.enabled) {
            logger.info(
                "CSV schema generation for pipeline " + pipeline + ": values withheld (" + AiSampleValues.EnvVar + "=false), all-string fields, no model call"
            )
            return buildCsvConfigWithheld(pipeline, fileContent, delimiter, header)
        }

        val myDelimiter = if (delimiter == null) "," else delimiter
        val truncatedContent = fileContent.split("\n").take(MAX_CONTENT_LINES).mkString("\n")
        val prompt = buildCsvPrompt(truncatedContent, myDelimiter)

        val text =
            try ai(prompt)
            catch {
                case _: AIRefusalException =>
                    // A safety decline only; every other failure propagates. The
                    // provider's message is not logged or returned.
                    logger.warn("CSV schema generation for pipeline " + pipeline + ": the model declined; returning all-string fields named from the header")
                    return buildCsvConfigDeclined(pipeline, fileContent, delimiter, header)
            }
        val fieldsJson = extractJsonArray(text)

        buildConfig(
            pipeline = pipeline,
            fieldsJson = fieldsJson,
            sourceAttributesJson = s""""csvAttributes": { "delimiter": "$myDelimiter", "header": $header, "encoding": "UTF-8" }""",
            usePostgres = true,
            useMongoDB = false
        )
    }

    /** One codegen-slot call, the AI configuration checked at call time. */
    private def callCodegen(prompt: String, purpose: String): String = {
        val aiConfig = DatrisEnvironment.aiConfigForCodegen
        if (aiConfig == null || aiConfig.endpoint == null || aiConfig.endpoint.isEmpty)
            throw new DatrisException("AI configuration is not set. Configure 'ai.endpoint' and 'ai.model' in application.yaml")
        logger.info("Calling AI API for " + purpose + ", provider: " + aiConfig.provider)
        val responseText = AIUtil.callAI(prompt, aiConfig)
        AIUtil.extractText(responseText, aiConfig)
    }

    /** First line of a schema prompt whose sample was replaced by a skeleton. */
    private def withheldPreamble(kind: String, placeholders: String): String =
        "Note: values withheld; infer from structure only. The sample below is a value-free " + kind + " skeleton built by the server " +
            "(" + placeholders + "), so do not infer formats, enums, patterns or ranges from it.\n"

    def buildJsonConfig(pipeline: String): String = {
        buildConfig(
            pipeline = pipeline,
            fieldsJson = """[{"name":"_json","type":"string"}]""",
            sourceAttributesJson = """"jsonAttributes": { "everyRowContainsObject": false, "encoding": "UTF-8" }""",
            usePostgres = false,
            useMongoDB = true
        )
    }

    def buildXmlConfig(pipeline: String): String = {
        buildConfig(
            pipeline = pipeline,
            fieldsJson = """[{"name":"_xml","type":"string"}]""",
            sourceAttributesJson = """"xmlAttributes": { "everyRowContainsObject": false, "encoding": "UTF-8" }""",
            usePostgres = false,
            useMongoDB = true
        )
    }

    private def buildConfig(pipeline: String, fieldsJson: String, sourceAttributesJson: String, usePostgres: Boolean, useMongoDB: Boolean): String = {
        val destination = {
            if (usePostgres)
                s"""{ "database": { "dbName": "DATABASE_NAME", "schema": "SCHEMA_NAME", "table": "TABLE_NAME", "usePostgres": true } }"""
            else
                s"""{ "database": { "dbName": "DATABASE_NAME", "table": "TABLE_NAME", "useMongoDB": true } }"""
        }

        s"""{
           |  "name": "$pipeline",
           |  "source": {
           |    "schemaProperties": {
           |      "fields": $fieldsJson
           |    },
           |    "fileAttributes": {
           |      $sourceAttributesJson
           |    }
           |  },
           |  "destination": $destination
           |}""".stripMargin
    }

    private def buildCsvPrompt(content: String, delimiter: String): String = {
        s"""You are a data schema expert. Analyze the following delimited file content and return ONLY a JSON array of field definitions.
           |Use this exact format: [{"name": "column_name", "type": "data_type"}, ...]
           |Valid types are: boolean, int, bigint, float, double, string, date, timestamp.
           |The delimiter is: $delimiter
           |Return only the JSON array with no explanation, no markdown, and no code fences.
           |
           |File content:
           |$content""".stripMargin
    }

    def generateJsonSchema(sampleData: String): String =
        generateJsonSchema(sampleData, prompt => callCodegen(prompt, "JSON Schema generation"))

    /** Seam for specs. With DATRIS_AI_SAMPLE_VALUES=false the model gets the
      * value-free skeleton of the whole sample instead of the sample. */
    private[datris] def generateJsonSchema(sampleData: String, ai: String => String): String = {
        val withheld = !AiSampleValues.enabled
        // The skeleton is built from the full input, before truncation.
        val truncated = (if (withheld) AiSampleValues.jsonSkeleton(sampleData) else sampleData).take(10000)
        val prompt =
            (if (withheld)
                 withheldPreamble("JSON", "every string is \"<string>\", every number 0, every boolean true; arrays keep one element per distinct shape")
             else "") +
                s"""You are a JSON Schema expert. Generate a JSON Schema (Draft 4) for validating the following JSON data.
               |The schema MUST use "$$schema": "http://json-schema.org/draft-04/schema#".
               |Include type constraints, required fields, and format validations where appropriate.
               |Return ONLY the JSON Schema, no explanation, no markdown, no code fences.
               |
               |Sample data:
               |$truncated""".stripMargin

        if (withheld) logger.info("JSON Schema generation: values withheld (" + AiSampleValues.EnvVar + "=false), sending the skeleton")
        extractJsonObject(ai(prompt))
    }

    def generateXsdSchema(sampleData: String): String =
        generateXsdSchema(sampleData, prompt => callCodegen(prompt, "XSD generation"))

    /** Seam for specs. With DATRIS_AI_SAMPLE_VALUES=false the model gets the
      * value-free skeleton of the whole sample instead of the sample. */
    private[datris] def generateXsdSchema(sampleData: String, ai: String => String): String = {
        val withheld = !AiSampleValues.enabled
        val truncated = (if (withheld) AiSampleValues.xmlSkeleton(sampleData) else sampleData).take(10000)
        val prompt =
            (if (withheld) withheldPreamble("XML", "element and attribute names kept, text and attribute values removed; repeated elements are marked")
             else "") +
                s"""You are an XML Schema expert. Generate a W3C XML Schema (XSD) for validating the following XML data.
               |Include element definitions, type constraints, and required attributes.
               |Return ONLY the XSD, no explanation, no markdown, no code fences.
               |
               |Sample data:
               |$truncated""".stripMargin

        if (withheld) logger.info("XSD generation: values withheld (" + AiSampleValues.EnvVar + "=false), sending the skeleton")
        extractXml(ai(prompt))
    }

    private def extractJsonObject(text: String): String = {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < 0)
            throw new DatrisException("AI response did not contain a JSON object. Response: " + text)
        text.substring(start, end + 1)
    }

    private def extractXml(text: String): String = {
        val start = text.indexOf("<?xml")
        if (start >= 0) return text.substring(start).trim
        val xsStart = text.indexOf("<xs:")
        if (xsStart >= 0) return text.substring(xsStart).trim
        val xsdStart = text.indexOf("<xsd:")
        if (xsdStart >= 0) return text.substring(xsdStart).trim
        // Fallback: return as-is
        text.trim
    }

    private def extractJsonArray(text: String): String = {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end < 0)
            throw new DatrisException("AI response did not contain a JSON array. Response: " + text)
        text.substring(start, end + 1)
    }
}
