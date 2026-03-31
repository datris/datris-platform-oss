package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{PipelineConfig, DatrisEnvironment, DatrisException}
import ai.datris.model._

import scala.collection.JavaConverters._

class DataQuality(jobContext: JobContext) {
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Process started")

        // Validate the header of the file(s) for delimited files (if it has a header)
        if(config.dataQuality.validateFileHeader) {
            if(config.source.fileAttributes.csvAttributes != null && config.source.fileAttributes.csvAttributes.header) {
                statusUtil.info("processing", "Validating the incoming file header(s)")

                validateHeader(jobContext.data.header, jobContext.config)
            }
        }

        // Validation schema?
        if(config.dataQuality.validationSchema != null) {
            val schemaFileUrl = {
                if(config.dataQuality.validationSchema.startsWith("s3://"))
                    config.dataQuality.validationSchema
                else
                    "s3://" + DatrisEnvironment.current.environment + "-config/validation-schema/" + config.dataQuality.validationSchema
            }

            statusUtil.info("processing", "Validating the incoming data for pipeline: " + config.name + ", against the validation schema: " + schemaFileUrl)
            if(config.source.fileAttributes.jsonAttributes != null)
                SchemaValidationUtil.validateJson(jobContext.data.rawData, schemaFileUrl)
            else if(config.source.fileAttributes.xmlAttributes != null)
                SchemaValidationUtil.validateXml(jobContext.data.rawData, schemaFileUrl)
        }

        // AI rule (CodeGen)?
        if(config.dataQuality.aiRule != null)
            runAIRule(jobContext.data)

        statusUtil.info("end", "Process completed successfully")
    }

    private def validateHeader(header: List[String], config: PipelineConfig): Unit = {
        val schemaFields = config.source.schemaProperties.fields.asScala.map(_.name).toList
        val headerStr = header.mkString(", ")
        val schemaStr = schemaFields.mkString(", ")

        val systemPrompt =
            """You are a data validation engine. You compare a CSV file header against an expected schema.
              |Output ONLY a JSON object with two fields:
              |  "valid": true or false
              |  "reason": a brief explanation (empty string if valid)
              |
              |Rules:
              |- Column names do NOT need to match exactly — allow case differences, underscores vs spaces, abbreviations, and minor naming variations (e.g. "First Name" matches "first_name", "qty" matches "quantity")
              |- Column ORDER does not matter — columns can appear in any order
              |- The header must contain ALL schema columns (missing columns = invalid)
              |- Extra columns in the header beyond the schema are OK (valid)
              |- If invalid, list which schema columns are missing or unmatched
              |
              |No markdown, no code fences, no explanation outside the JSON.""".stripMargin

        val userPrompt =
            s"""File header columns: [$headerStr]
               |Expected schema columns: [$schemaStr]""".stripMargin

        statusUtil.info("processing", "Validating file header against schema using AI")

        val responseText = AIUtil.callAIWithSystem(systemPrompt, userPrompt)
        val text = AIUtil.extractText(responseText).trim

        // Parse the JSON response
        val gson = new com.google.gson.Gson()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < 0)
            throw new DatrisException("Header validation AI response did not contain JSON: " + text.take(500))

        val json = text.substring(start, end + 1)
        val result = gson.fromJson(json, classOf[java.util.Map[String, Any]])
        val valid = result.get("valid") match {
            case b: java.lang.Boolean => b.booleanValue()
            case _ => false
        }
        val reason = Option(result.get("reason")).map(_.toString).getOrElse("")

        if (!valid) {
            throw new DatrisException("File header validation failed for pipeline: " + config.name + ". " + reason)
        }
    }

    private def runAIRule(data: Data): Unit = {
        val aiRule = config.dataQuality.aiRule
        val instruction = aiRule.instruction
        val rows = if (data.rows != null) data.rows else List.empty[String]
        val rawData = data.rawData

        statusUtil.info("processing", "AI Data Quality instruction: " + instruction)

        statusUtil.info("processing", "Running CodeGen data quality rule")

        val failures = if (rows.nonEmpty && data.header != null) {
            val delimiter = config.source.fileAttributes.csvAttributes.delimiter
            statusUtil.info("processing", "CodeGen rule validating " + rows.size + " rows")
            CodeGenRuleEvaluator.evaluateCsv(instruction, data.header, rows, delimiter)
        } else if (rawData != null) {
            val isJson = config.source.fileAttributes.jsonAttributes != null
            statusUtil.info("processing", "CodeGen rule on " + (if (isJson) "JSON" else "XML") + " data")
            CodeGenRuleEvaluator.evaluateRaw(instruction, rawData, isJson)
        } else {
            List.empty
        }

        val results = failures.map { case (rowIdx, reason) =>
            (aiRule.onFailureIsError, "Data quality CodeGen failure, row: " + rowIdx.toString + ", reason: " + reason)
        }
        dumpResults(results)
    }

    private def dumpResults(results: List[(Boolean, String)]): Unit = {
        val errors = results.collect { case (true, message) => message }
        val warnings = results.collect { case (false, message) => message }

        val errorCount = errors.size
        val warningCount = warnings.size

        if (errorCount > 0) {
            val errorDetails = errors.take(100).mkString("\n")
            val suffix = if (errorCount > 100) "\n... and " + (errorCount - 100) + " more error(s)" else ""
            throw new DatrisException("Aborting processing this pipeline, " + errorCount.toString + " error(s) were found while performing data quality rules:\n" + errorDetails + suffix)
        }
        if (warningCount > 0)
            statusUtil.warn("processing", warnings.mkString("\n"))
    }
}
