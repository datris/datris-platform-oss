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

    def buildCsvConfig(dataset: String, fileContent: String, delimiter: String, header: Boolean): String = {
        val aiConfig = DatrisEnvironment.values.aiConfig
        if (aiConfig == null || aiConfig.endpoint == null || aiConfig.endpoint.isEmpty)
            throw new DatrisException("AI configuration is not set. Configure 'ai.endpoint' and 'ai.model' in application.yaml")

        val myDelimiter = if (delimiter == null) "," else delimiter
        val truncatedContent = fileContent.split("\n").take(MAX_CONTENT_LINES).mkString("\n")
        val prompt = buildCsvPrompt(truncatedContent, myDelimiter)

        logger.info("Calling AI API for CSV schema generation, dataset: " + dataset + ", provider: " + aiConfig.provider)
        val responseText = AIUtil.callAI(prompt)
        val text = AIUtil.extractText(responseText)
        val fieldsJson = extractJsonArray(text)

        buildConfig(
            dataset = dataset,
            fieldsJson = fieldsJson,
            sourceAttributesJson = s""""csvAttributes": { "delimiter": "$myDelimiter", "header": $header, "encoding": "UTF-8" }""",
            usePostgres = true,
            useMongoDB = false
        )
    }

    def buildJsonConfig(dataset: String): String = {
        buildConfig(
            dataset = dataset,
            fieldsJson = """[{"name":"_json","type":"string"}]""",
            sourceAttributesJson = """"jsonAttributes": { "everyRowContainsObject": false, "encoding": "UTF-8" }""",
            usePostgres = false,
            useMongoDB = true
        )
    }

    def buildXmlConfig(dataset: String): String = {
        buildConfig(
            dataset = dataset,
            fieldsJson = """[{"name":"_xml","type":"string"}]""",
            sourceAttributesJson = """"xmlAttributes": { "everyRowContainsObject": false, "encoding": "UTF-8" }""",
            usePostgres = false,
            useMongoDB = true
        )
    }

    private def buildConfig(dataset: String, fieldsJson: String, sourceAttributesJson: String, usePostgres: Boolean, useMongoDB: Boolean): String = {
        val destination = {
            if (usePostgres)
                s"""{ "database": { "dbName": "DATABASE_NAME", "schema": "SCHEMA_NAME", "table": "TABLE_NAME", "usePostgres": true } }"""
            else
                s"""{ "database": { "dbName": "DATABASE_NAME", "table": "TABLE_NAME", "useMongoDB": true } }"""
        }

        s"""{
           |  "name": "$dataset",
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

    private def extractJsonArray(text: String): String = {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end < 0)
            throw new DatrisException("AI response did not contain a JSON array. Response: " + text)
        text.substring(start, end + 1)
    }
}
