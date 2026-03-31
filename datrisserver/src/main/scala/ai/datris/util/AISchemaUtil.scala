package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException}
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

    def buildCsvConfig(pipeline: String, fileContent: String, delimiter: String, header: Boolean): String = {
        val aiConfig = DatrisEnvironment.current.aiConfig
        if (aiConfig == null || aiConfig.endpoint == null || aiConfig.endpoint.isEmpty)
            throw new DatrisException("AI configuration is not set. Configure 'ai.endpoint' and 'ai.model' in application.yaml")

        val myDelimiter = if (delimiter == null) "," else delimiter
        val truncatedContent = fileContent.split("\n").take(MAX_CONTENT_LINES).mkString("\n")
        val prompt = buildCsvPrompt(truncatedContent, myDelimiter)

        logger.info("Calling AI API for CSV schema generation, pipeline: " + pipeline + ", provider: " + aiConfig.provider)
        val responseText = AIUtil.callAI(prompt)
        val text = AIUtil.extractText(responseText)
        val fieldsJson = extractJsonArray(text)

        buildConfig(
            pipeline = pipeline,
            fieldsJson = fieldsJson,
            sourceAttributesJson = s""""csvAttributes": { "delimiter": "$myDelimiter", "header": $header, "encoding": "UTF-8" }""",
            usePostgres = true,
            useMongoDB = false
        )
    }

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

    def generateJsonSchema(sampleData: String): String = {
        val aiConfig = DatrisEnvironment.current.aiConfig
        if (aiConfig == null || aiConfig.endpoint == null || aiConfig.endpoint.isEmpty)
            throw new DatrisException("AI configuration is not set. Configure 'ai.endpoint' and 'ai.model' in application.yaml")

        val truncated = sampleData.take(10000)
        val prompt =
            s"""You are a JSON Schema expert. Generate a JSON Schema (Draft 4) for validating the following JSON data.
               |The schema MUST use "$$schema": "http://json-schema.org/draft-04/schema#".
               |Include type constraints, required fields, and format validations where appropriate.
               |Return ONLY the JSON Schema, no explanation, no markdown, no code fences.
               |
               |Sample data:
               |$truncated""".stripMargin

        logger.info("Calling AI for JSON Schema generation")
        val responseText = AIUtil.callAI(prompt)
        val text = AIUtil.extractText(responseText)
        extractJsonObject(text)
    }

    def generateXsdSchema(sampleData: String): String = {
        val aiConfig = DatrisEnvironment.current.aiConfig
        if (aiConfig == null || aiConfig.endpoint == null || aiConfig.endpoint.isEmpty)
            throw new DatrisException("AI configuration is not set. Configure 'ai.endpoint' and 'ai.model' in application.yaml")

        val truncated = sampleData.take(10000)
        val prompt =
            s"""You are an XML Schema expert. Generate a W3C XML Schema (XSD) for validating the following XML data.
               |Include element definitions, type constraints, and required attributes.
               |Return ONLY the XSD, no explanation, no markdown, no code fences.
               |
               |Sample data:
               |$truncated""".stripMargin

        logger.info("Calling AI for XSD generation")
        val responseText = AIUtil.callAI(prompt)
        val text = AIUtil.extractText(responseText)
        extractXml(text)
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
