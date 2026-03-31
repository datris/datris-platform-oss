package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.model.JobContext

import java.text.SimpleDateFormat
import java.util.Date
import javax.script.ScriptEngineManager
import scala.collection.JavaConverters._
import scala.collection.mutable

class Transformation(jobContext: JobContext) {
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    def process(): JobContext = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Process started")

        val jobContextDD = {
            if(config.transformation.deduplicate)
                deduplicate(jobContext)
            else
                jobContext
        }

        val jobContextRF = {
            if(config.transformation.rowFunctions != null)
                runRowFunctions(jobContextDD)
            else
                jobContextDD
        }

        val jobContextAI = {
            if(config.transformation.aiTransformation != null)
                runAITransformation(jobContextRF)
            else
                jobContextRF
        }

        statusUtil.info("end", "Process completed successfully")
        jobContextAI
    }

    private def deduplicate(jobContext: JobContext): JobContext = {
        statusUtil.info("processing", "Running deduplication")

        val distinct = jobContext.data.rows.distinct
        val deduped = jobContext.data.rows.size - distinct.size
        if(deduped > 0) {
            statusUtil.info("processing", deduped.toString + " rows were duplicates and removed")
            val newData = jobContext.data.copy(rows = distinct)
            jobContext.copy(data = newData)
        }
        else
            jobContext
    }


    private def runRowFunctions(jobContextRF: JobContext): JobContext = {
        var currentContext = jobContextRF

        config.transformation.rowFunctions.asScala.foreach { rowFunction =>
            rowFunction.function.toLowerCase match {
                case "javascript" =>
                    currentContext = runJavaScriptFunction(currentContext, rowFunction)
                case "restendpoint" =>
                    currentContext = runRestEndpointFunction(currentContext, rowFunction)
                case other =>
                    statusUtil.info("processing", "Unknown row function type: " + other + ", skipping")
            }
        }

        currentContext
    }

    private def runJavaScriptFunction(ctx: JobContext, rowFunction: ai.datris.model.RowFunction): JobContext = {
        if (rowFunction.parameters == null || rowFunction.parameters.isEmpty)
            throw new DatrisException("Javascript row function does not contain any parameters")

        val filePath = rowFunction.parameters.get(0)
        val javascript = {
            val url = {
                if (filePath.startsWith("s3"))
                    filePath
                else
                    "s3://" + DatrisEnvironment.current.environment + "-config/javascript/" + filePath
            }
            statusUtil.info("processing", "Running row function: javascript, using script: " + url)
            ObjectStoreUtil.readBucketObject(ObjectStoreUtil.getBucket(url), ObjectStoreUtil.getKey(url)).getOrElse(
                throw new DatrisException("Javascript file not found using the first parameter of the row function: " + filePath))
        }

        var removed: Long = 0
        val transformed = ctx.data.rows.flatMap(row => {
            val columnMap = RowUtil.getRowAsMap(row, config, ctx.data.header)
            val changedValues = runScript(columnMap, javascript)
            if (changedValues != null) {
                val newRow = config.destination.schemaProperties.fields.asScala.map(field => {
                    val value = changedValues.get(field.name)
                    if (value == null) columnMap.getOrElse(field.name, "")
                    else value.toString
                }).toList.mkString(config.source.fileAttributes.csvAttributes.delimiter)
                Some(newRow)
            } else {
                removed = removed + 1
                None
            }
        })

        if (removed > 0)
            statusUtil.info("processing", removed.toString + " rows were removed during the javascript transformation")

        val headerWithSchema = config.destination.schemaProperties.fields.asScala.toList
        val newData = ctx.data.copy(headerWithSchema = headerWithSchema, rows = transformed)
        ctx.copy(data = newData)
    }

    private def runRestEndpointFunction(ctx: JobContext, rowFunction: ai.datris.model.RowFunction): JobContext = {
        if (rowFunction.parameters == null || rowFunction.parameters.isEmpty)
            throw new DatrisException("REST endpoint row function does not contain any parameters")

        val endpointUrl = rowFunction.parameters.get(0)
        val mode = if (rowFunction.parameters.size() > 1) rowFunction.parameters.get(1).toLowerCase else "row"
        val timeoutMs = if (rowFunction.parameters.size() > 2) try { rowFunction.parameters.get(2).toInt } catch { case _: NumberFormatException => 30000 } else 30000
        val bearerToken = if (rowFunction.parameters.size() > 3 && rowFunction.parameters.get(3).nonEmpty) rowFunction.parameters.get(3) else null
        val apiKey = if (rowFunction.parameters.size() > 4 && rowFunction.parameters.get(4).nonEmpty) rowFunction.parameters.get(4) else null
        val delimiter = config.source.fileAttributes.csvAttributes.delimiter
        val pipelineName = config.name
        val pipelineToken = jobContext.pipelineToken

        statusUtil.info("processing", "Running transformation row function: restEndpoint, mode: " + mode + ", URL: " + endpointUrl)

        mode match {
            case "batch" =>
                val rowMaps = ctx.data.rows.map(row => RowUtil.getRowAsMap(row, config, ctx.data.header).asJava)
                val transformedRows = callRestTransformBatch(endpointUrl, pipelineName, pipelineToken, rowMaps, timeoutMs, bearerToken, apiKey, delimiter)
                statusUtil.info("processing", "REST batch transformation returned " + transformedRows.size + " rows (from " + ctx.data.rows.size + ")")
                val newData = ctx.data.copy(rows = transformedRows)
                ctx.copy(data = newData)

            case _ => // "row" mode
                var removed: Long = 0
                val transformed = ctx.data.rows.flatMap(row => {
                    val columnMap = RowUtil.getRowAsMap(row, config, ctx.data.header)
                    val result = callRestTransformRow(endpointUrl, pipelineName, pipelineToken, columnMap, timeoutMs, bearerToken, apiKey, delimiter)
                    if (result != null) {
                        Some(result)
                    } else {
                        removed = removed + 1
                        None
                    }
                })

                if (removed > 0)
                    statusUtil.info("processing", removed.toString + " rows were removed during the REST endpoint transformation")

                val newData = ctx.data.copy(rows = transformed)
                ctx.copy(data = newData)
        }
    }

    private def callRestTransformRow(endpointUrl: String, pipelineName: String, pipelineToken: String,
                                     columnMap: mutable.ListMap[String, Any], timeoutMs: Int,
                                     bearerToken: String, apiKey: String, delimiter: String): String = {
        val gson = new Gson()
        val payload = mutable.ListMap[String, Any](
            "pipelineName" -> pipelineName,
            "pipelineToken" -> pipelineToken,
            "row" -> columnMap.asJava
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
            throw new DatrisException("REST transform endpoint returned null for pipeline: " + pipelineName)

        val responseMap = gson.fromJson(response.trim, classOf[java.util.Map[String, Any]])
        val status = Option(responseMap.get("status")).map(_.toString).getOrElse("failure")
        if (status != "success") {
            val message = Option(responseMap.get("message")).map(_.toString).getOrElse("Unknown error")
            throw new DatrisException("REST transform endpoint failed: " + message)
        }

        val rowData = responseMap.get("row")
        if (rowData == null) return null // null row = remove

        val rowMap = rowData.asInstanceOf[java.util.Map[String, Any]]
        config.destination.schemaProperties.fields.asScala.map(field => {
            val value = rowMap.get(field.name)
            if (value == null) columnMap.getOrElse(field.name, "")
            else valueToString(value)
        }).toList.mkString(delimiter)
    }

    private def callRestTransformBatch(endpointUrl: String, pipelineName: String, pipelineToken: String,
                                       rowMaps: List[java.util.Map[String, Any]], timeoutMs: Int,
                                       bearerToken: String, apiKey: String, delimiter: String): List[String] = {
        val gson = new Gson()
        val wrapper = mutable.ListMap[String, Any](
            "pipelineName" -> pipelineName,
            "pipelineToken" -> pipelineToken,
            "rows" -> rowMaps.asJava
        )
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
            throw new DatrisException("REST batch transform endpoint returned null for pipeline: " + pipelineName)

        val responseMap = gson.fromJson(response.trim, classOf[java.util.Map[String, Any]])
        val status = Option(responseMap.get("status")).map(_.toString).getOrElse("failure")
        if (status != "success") {
            val message = Option(responseMap.get("message")).map(_.toString).getOrElse("Unknown error")
            throw new DatrisException("REST batch transform endpoint failed: " + message)
        }

        val rows = responseMap.get("rows")
        if (rows == null) throw new DatrisException("REST batch transform endpoint did not return 'rows'")

        val rowsList = rows.asInstanceOf[java.util.List[Any]]
        rowsList.asScala.flatMap { entry =>
            if (entry == null) None // null entry = remove row
            else {
                val rowMap = entry.asInstanceOf[java.util.Map[String, Any]]
                val csvRow = config.destination.schemaProperties.fields.asScala.map(field => {
                    val value = rowMap.get(field.name)
                    if (value == null) "" else valueToString(value)
                }).toList.mkString(delimiter)
                Some(csvRow)
            }
        }.toList
    }

    private def runAITransformation(jobContext: JobContext): JobContext = {
        val aiTransformation = config.transformation.aiTransformation
        val instruction = aiTransformation.instruction
        val rows = if (jobContext.data.rows != null) jobContext.data.rows else List.empty[String]
        val rawData = jobContext.data.rawData

        statusUtil.info("processing", "AI Transformation instruction: " + instruction)

        if (rows.nonEmpty && jobContext.data.header != null) {
            val delimiter = config.source.fileAttributes.csvAttributes.delimiter
            statusUtil.info("processing", "CodeGen transformation on " + rows.size + " rows")
            val transformedRows = CodeGenTransformationEvaluator.transformCsv(instruction, jobContext.data.header, rows, delimiter)
            val newData = jobContext.data.copy(rows = transformedRows)
            jobContext.copy(data = newData)
        } else if (rawData != null) {
            val isJson = config.source.fileAttributes.jsonAttributes != null
            statusUtil.info("processing", "CodeGen transformation on " + (if (isJson) "JSON" else "XML") + " data")
            val transformedRaw = CodeGenTransformationEvaluator.transformRaw(instruction, rawData, isJson)
            val newData = jobContext.data.copy(rawData = transformedRaw)
            jobContext.copy(data = newData)
        } else {
            jobContext
        }
    }

    private def valueToString(value: Any): String = {
        value match {
            case d: java.lang.Double if d == d.longValue().toDouble => d.longValue().toString
            case _ => value.toString
        }
    }

    private def runScript(columnMap: mutable.ListMap[String, Any], script: String): java.util.HashMap[String, Any] = {
        val engine = new ScriptEngineManager().getEngineByName("JavaScript")
        val bindings = engine.createBindings()

        // Add all of the column key/values as parameters
        columnMap.foreach { case (key, value) => bindings.put(key, value) }

        // Add the _pipelinetimestamp as the last parameter
        val formatter= new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z")
        val pipelineTimestamp = formatter.format(new Date(System.currentTimeMillis()))
        bindings.put("_pipelinetimestamp", pipelineTimestamp)

        engine.eval(script, bindings).asInstanceOf[java.util.HashMap[String, Any]]
    }
}