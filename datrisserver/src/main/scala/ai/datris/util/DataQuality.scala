package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{ColumnRule, PipelineConfig, DatrisEnvironment, DatrisException}
import ai.datris.model._
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import javax.script.ScriptEngineManager
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

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
                    "s3://" + DatrisEnvironment.values.environment + "-config/validation-schema/" + config.dataQuality.validationSchema
            }

            statusUtil.info("processing", "Validating the incoming data for pipeline: " + config.name + ", against the validation schema: " + schemaFileUrl)
            if(config.source.fileAttributes.jsonAttributes != null)
                SchemaValidationUtil.validateJson(jobContext.data.rawData, schemaFileUrl)
            else if(config.source.fileAttributes.xmlAttributes != null)
                SchemaValidationUtil.validateXml(jobContext.data.rawData, schemaFileUrl)
        }

        // AI rule?
        if(config.dataQuality.aiRule != null)
            runAIRule(jobContext.data)

        // Row rules?
        if(config.dataQuality.rowRules != null)
            runRowRules(jobContext.data)

        // Column rules?
        if(config.dataQuality.columnRules != null)
            runColumnRules(jobContext.data)

        statusUtil.info("end", "Process completed successfully")
    }

    private def validateHeader(header: List[String], config: PipelineConfig): Unit = {
        // The header must be in the exact order of the source schema if the source schema exists
        (header, config.source.schemaProperties.fields.asScala).zipped.foreach { (column, schemaField) =>
            //logger.info("Comparing header column: " + column + ", to field: " + field.name)
            if(schemaField.name.compareToIgnoreCase(column) != 0)
                throw new DatrisException("The incoming header on the data file does not match the destination schema for pipeline: " + config.name + ", failed comparing column: " + column + " with source schema field: " + schemaField.name)
        }
    }

    private def runRowRules(data: Data): Unit = {
        val rows = if (data.rows != null) data.rows else List.empty[String]
        val rawData = data.rawData  // Will be null if not JSON or XML

        // Gather all of the "javaScript" rules
        val scriptRules = config.dataQuality.rowRules.asScala.flatMap(rowRule => {
            if (rowRule.function.compareToIgnoreCase("javascript") == 0)
                Some(rowRule)
            else
                None
        }).toList

        if (scriptRules != null && scriptRules.nonEmpty) {
            val results = scriptRules.flatMap(rule => {
                if (rule.parameters == null || rule.parameters.isEmpty)
                    throw new DatrisException("Javascript row rule '" + rule.function + "' does not contain any parameters")

                statusUtil.info("processing", "Running data quality row rule: javascript, using script: " + rule.parameters.get(0))

                val filePath = rule.parameters.get(0)
                val javascript = {
                    val url = {
                        if (filePath.startsWith("s3"))
                            filePath
                        else
                            "s3://" + DatrisEnvironment.values.environment + "-config/javascript/" + filePath
                    }
                    ObjectStoreUtil.readBucketObject(ObjectStoreUtil.getBucket(url), ObjectStoreUtil.getKey(url)).getOrElse(
                        throw new DatrisException("Javascript file not found using the first parameter of the row rule: " + filePath))
                }

                rows.zipWithIndex.flatMap { case (row, rowNumber) =>
                    val columnMap = RowUtil.getRowAsMap(row, config)
                    val description = runScript(columnMap, javascript)
                    if (description != null)
                        Some(rule.onFailureIsError, "Data quality failure, description: " + description)
                    else
                        None
                }
            })
            dumpResults(results)
        }

        // Gather all of the "restEndpoint" rules
        val restRules = config.dataQuality.rowRules.asScala.flatMap(rowRule => {
            if (rowRule.function.compareToIgnoreCase("restEndpoint") == 0)
                Some(rowRule)
            else
                None
        }).toList

        val pipelineName = config.name
        val pipelineToken = jobContext.pipelineToken
        if (restRules != null && restRules.nonEmpty) {
            val results = restRules.flatMap(rule => {
                if (rule.parameters == null || rule.parameters.isEmpty)
                    throw new DatrisException("REST endpoint row rule does not contain any parameters (expected URL as first parameter)")

                val endpointUrl = rule.parameters.get(0)
                val mode = if (rule.parameters.size() > 1) rule.parameters.get(1).toLowerCase else "row"
                val timeoutMs = if (rule.parameters.size() > 2) try { rule.parameters.get(2).toInt } catch { case _: NumberFormatException => 30000 } else 30000
                val bearerToken = if (rule.parameters.size() > 3 && rule.parameters.get(3).nonEmpty) rule.parameters.get(3) else null
                val apiKey = if (rule.parameters.size() > 4 && rule.parameters.get(4).nonEmpty) rule.parameters.get(4) else null

                statusUtil.info("processing", "Running data quality row rule: restEndpoint, mode: " + mode + ", using URL: " + endpointUrl)

                mode match {
                    case "batch" =>
                        val rowMaps = rows.map(row => RowUtil.getRowAsMap(row, config).asJava)
                        callRestEndpointBatch(endpointUrl, pipelineName, pipelineToken, rowMaps, rawData, timeoutMs, bearerToken, apiKey).map { case (rowNumber, description) =>
                            (rule.onFailureIsError, "Data quality failure, row: " + rowNumber.toString + ", description: " + description)
                        }

                    case "row" =>
                        if (rows.isEmpty)
                            throw new DatrisException("REST endpoint row rule mode 'row' requires row data but none was provided for pipeline: " + pipelineName)
                        rows.zipWithIndex.flatMap { case (row, rowNumber) =>
                            val columnMap = RowUtil.getRowAsMap(row, config)
                            val description = callRestEndpointRow(endpointUrl, pipelineName, pipelineToken, columnMap, timeoutMs, bearerToken, apiKey)
                            if (description != null)
                                Some(rule.onFailureIsError, "Data quality failure, description: " + description)
                            else
                                None
                        }

                    case _ =>
                        throw new DatrisException("REST endpoint row rule mode: '" + mode + "' is not valid, expected 'row' or 'batch'")
                }
            })
            dumpResults(results)
        }

    }

    private def runAIRule(data: Data): Unit = {
        val aiRule = config.dataQuality.aiRule
        val instruction = aiRule.instruction
        val rows = if (data.rows != null) data.rows else List.empty[String]
        val rawData = data.rawData

        statusUtil.info("processing", "Running AI data quality rule")

        val failures = if (rows.nonEmpty && data.header != null) {
            // CSV data
            val delimiter = config.source.fileAttributes.csvAttributes.delimiter

            if (aiRule.sample) {
                // Sampling mode: randomly select sampleSize rows
                val sampleSize = Math.min(aiRule.sampleSize, rows.size)
                val sampledRows = Random.shuffle(rows.zipWithIndex).take(sampleSize).sortBy(_._2)
                val sampledRowStrings = sampledRows.map(_._1)
                val sampledIndices = sampledRows.map(_._2)
                statusUtil.info("processing", "AI rule using sample mode (" + sampleSize + " of " + rows.size + " rows)")
                val results = AIDataQualityUtil.validateWithFileContent(instruction, data.header, sampledRowStrings, delimiter)
                results.map { case (sampledIdx, reason) =>
                    if (sampledIdx >= 0 && sampledIdx < sampledIndices.size)
                        (sampledIndices(sampledIdx), reason)
                    else
                        (sampledIdx, reason)
                }
            } else {
                // Full-file mode: send all rows
                statusUtil.info("processing", "AI rule using full-file mode (" + rows.size + " rows)")
                AIDataQualityUtil.validateWithFileContent(instruction, data.header, rows, delimiter)
            }
        } else if (rawData != null) {
            // JSON/XML data
            if (aiRule.sample) {
                val gson = new Gson()
                val listType = new TypeToken[java.util.List[java.util.Map[String, Any]]](){}.getType
                val jsonList: java.util.List[java.util.Map[String, Any]] = gson.fromJson(rawData, listType)
                val allRecords = jsonList.asScala.toList
                val sampleSize = Math.min(aiRule.sampleSize, allRecords.size)
                val sampled = Random.shuffle(allRecords).take(sampleSize)
                statusUtil.info("processing", "AI rule using sample mode on raw data (" + sampleSize + " of " + allRecords.size + " records)")
                val sampledJson = "[" + sampled.map(r => gson.toJson(r)).mkString(",") + "]"
                AIDataQualityUtil.validateWithRawContent(instruction, sampledJson)
            } else {
                statusUtil.info("processing", "AI rule using full-file mode on raw data")
                AIDataQualityUtil.validateWithRawContent(instruction, rawData)
            }
        } else {
            List.empty
        }

        val results = failures.map { case (rowIdx, reason) =>
            (aiRule.onFailureIsError, "Data quality AI failure, row: " + rowIdx.toString + ", reason: " + reason)
        }
        dumpResults(results)
    }

    private def runColumnRules(data: Data): Unit = {
        statusUtil.info("processing", "Performing data quality column rules")
        val rows = if (data.rows != null) data.rows else List.empty[String]

        val results = rows.zipWithIndex.flatMap { case (row, rowNumber) =>
            config.dataQuality.columnRules.asScala.flatMap(rule => {
                val (schemaField, columnNumber) = config.source.schemaProperties.fields.asScala.zipWithIndex.find { case (field, fieldNumber) =>
                    field.name.compareToIgnoreCase(rule.columnName) == 0
                }.getOrElse(throw new DatrisException("Column rule field: " + rule.columnName + " was not found in the source 'schemaProperties' for this pipeline"))

                val columns = row.split(config.source.fileAttributes.csvAttributes.delimiter).toList
                if(columnNumber >= columns.size)
                    throw new DatrisException("Column rule field: " + rule.columnName + " refers to column index " + columnNumber + " but row only has " + columns.size + " column(s)")
                val columnValue = columns(columnNumber)

                rule.function match {
                    case "regex" =>
                        if (!regex(rule, columnValue))
                            Some((rule.onFailureIsError, "Data quality regular expression failure on row: " + (rowNumber + 2).toString + ", column: " + rule.columnName.toLowerCase + ", rule: " + rule.function + "=" + rule.parameter))
                        else
                            None

                    case _ => throw new DatrisException("Data quality rule: " + rule.function + " for column: " + rule.columnName.toLowerCase + " is not defined in the Data Quality Engine")
                }
            }).toList
        }

        dumpResults(results)
    }

    private def regex(rule: ColumnRule, value: String): Boolean = {
        try {
            value.matches(rule.parameter)
        } catch {
            case e: java.util.regex.PatternSyntaxException =>
                throw new DatrisException("Invalid regular expression in column rule for column: " + rule.columnName + ", pattern: " + rule.parameter + ", error: " + e.getMessage)
        }
    }

    private def callRestEndpointRow(endpointUrl: String, pipelineName: String, pipelineToken: String, columnDataMap: mutable.ListMap[String, Any], timeoutMs: Int, bearerToken: String = null, apiKey: String = null): String = {
        val gson = new Gson()
        val payload = mutable.ListMap[String, Any](
            "pipelineName" -> pipelineName,
            "pipelineToken" -> pipelineToken,
            "row" -> columnDataMap.asJava
        )
        val jsonPayload = gson.toJson(payload.asJava)

        val response = HttpUtil.post(
            url = endpointUrl,
            contentType = "application/json",
            dataToPost = jsonPayload,
            bearerToken = bearerToken,
            apiKey = apiKey,
            timeoutMillis = timeoutMs
        )

        if (response == null || response.trim.isEmpty || response.trim == "null")
            throw new DatrisException(s"REST endpoint returned null for pipeline: $pipelineName")

        val responseMap = gson.fromJson(response.trim, classOf[java.util.Map[String, Any]])
        val status = Option(responseMap.get("status")).map(_.toString).getOrElse("failure")
        if (status == "success")
            return null

        val message = Option(responseMap.get("message")).map(_.toString).getOrElse("Unknown error")
        message
    }

    private def callRestEndpointBatch(endpointUrl: String, pipelineName: String, pipelineToken: String, rowMaps: List[java.util.Map[String, Any]], rawData: String, timeoutMs: Int, bearerToken: String = null, apiKey: String = null): List[(Int, String)] = {
        val gson = new Gson()
        val wrapper = mutable.ListMap[String, Any](
            "pipelineName" -> pipelineName,
            "pipelineToken" -> pipelineToken,
            "rows" -> rowMaps.asJava
        )
        if (rawData != null)
            wrapper.put("rawData", rawData)
        val jsonPayload = gson.toJson(wrapper.asJava)

        val response = HttpUtil.post(
            url = endpointUrl,
            contentType = "application/json",
            dataToPost = jsonPayload,
            bearerToken = bearerToken,
            apiKey = apiKey,
            timeoutMillis = timeoutMs
        )

        if (response == null || response.trim.isEmpty || response.trim == "null")
            throw new DatrisException(s"REST batch endpoint returned null for pipeline: $pipelineName")

        val responseMap = gson.fromJson(response.trim, classOf[java.util.Map[String, Any]])
        val status = Option(responseMap.get("status")).map(_.toString).getOrElse("failure")
        if (status != "success") {
            val message = Option(responseMap.get("message")).map(_.toString).getOrElse("Unknown error")
            throw new DatrisException(s"REST batch endpoint failed for pipeline $pipelineName: $message")
        }

        val failures = Option(responseMap.get("failures"))
        if (failures.isEmpty) List.empty
        else {
            val failureList = failures.get.asInstanceOf[java.util.List[java.util.Map[String, Any]]]
            failureList.asScala.map(failure => {
                val row = failure.get("row").asInstanceOf[Double].toInt
                val description = failure.get("description").asInstanceOf[String]
                (row, description)
            }).toList
        }
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

    private def runScript(columnDataMap: mutable.ListMap[String, Any], script: String): String = {
        val engine = new ScriptEngineManager().getEngineByName("JavaScript")
        val bindings = engine.createBindings()
        columnDataMap.foreach { case (key, value) => bindings.put(key, value) }

        val result = engine.eval(script, bindings)
        if(result == null) null
        else result match {
            case s: String => s
            case other => throw new DatrisException("Javascript row rule returned a non-string value: " + other.getClass.getName + ". The script must return a String or null.")
        }
    }
}