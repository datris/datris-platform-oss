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
            if (config.transformation.deduplicate)
                deduplicate(jobContext)
            else
                jobContext
        }

        val jobContextRF = {
            if (config.transformation.rowFunctions != null)
                runRowFunctions(jobContextDD)
            else
                jobContextDD
        }

        val jobContextAI = {
            if (config.transformation.aiTransformation != null)
                runAITransformation(jobContextRF)
            else
                jobContextRF
        }

        statusUtil.info("end", "Process completed successfully")
        jobContextAI
    }

    private def deduplicate(jobContext: JobContext): JobContext = {
        statusUtil.info("processing", "Running deduplication")

        // Deprecated, in-heap: gated by name above PIPELINE_MATERIALIZE_MAX_MB.
        jobContext.data.materializeFor("Deduplication (transformation.deduplicate)")
        val distinct = jobContext.data.rows.distinct
        val deduped = jobContext.data.rows.size - distinct.size
        if (deduped > 0) {
            statusUtil.info("processing", deduped.toString + " rows were duplicates and removed")
            val newData = jobContext.data.withRows(distinct)
            jobContext.copy(data = newData)
        } else
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

        // Deprecated, in-heap: gated by name above PIPELINE_MATERIALIZE_MAX_MB,
        // before the script is even fetched.
        ctx.data.materializeFor("JavaScript row function")
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
                throw new DatrisException("Javascript file not found using the first parameter of the row function: " + filePath)
            )
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
        val newData = ctx.data.withRows(transformed).copy(headerWithSchema = headerWithSchema)
        ctx.copy(data = newData)
    }

    private def runRestEndpointFunction(ctx: JobContext, rowFunction: ai.datris.model.RowFunction): JobContext = {
        if (rowFunction.parameters == null || rowFunction.parameters.isEmpty)
            throw new DatrisException("REST endpoint row function does not contain any parameters")

        val endpointUrl = rowFunction.parameters.get(0)
        val mode = if (rowFunction.parameters.size() > 1) rowFunction.parameters.get(1).toLowerCase else "row"
        val timeoutMs = if (rowFunction.parameters.size() > 2)
            try { rowFunction.parameters.get(2).toInt }
            catch { case _: NumberFormatException => 30000 }
        else 30000
        val bearerToken = if (rowFunction.parameters.size() > 3 && rowFunction.parameters.get(3).nonEmpty) rowFunction.parameters.get(3) else null
        val apiKey = if (rowFunction.parameters.size() > 4 && rowFunction.parameters.get(4).nonEmpty) rowFunction.parameters.get(4) else null
        // parameters[5]: batch mode only — rows per call; 0 (default) is one call carrying every row.
        val batchSize = if (rowFunction.parameters.size() > 5)
            try { math.max(0, rowFunction.parameters.get(5).trim.toInt) }
            catch { case _: NumberFormatException => 0 }
        else 0
        val delimiter = config.source.fileAttributes.csvAttributes.delimiter
        val pipelineName = config.name
        val pipelineToken = jobContext.pipelineToken

        statusUtil.info("processing", "Running transformation row function: restEndpoint, mode: " + mode + ", URL: " + endpointUrl)

        mode match {
            case "batch" if batchSize > 0 =>
                // Chunks of `batchSize` rows read from the staged file; each call
                // carries the same {pipelineName, pipelineToken, rows} envelope and
                // the responses are stitched into one new staged file in call order.
                val rows = ctx.data.rowIterator()
                val newStaged =
                    try {
                        val transformed = rows.grouped(batchSize).flatMap { batch =>
                            val rowMaps = batch.toList.map(row => RowUtil.getRowAsMap(row, config, ctx.data.header).asJava)
                            callRestTransformBatch(endpointUrl, pipelineName, pipelineToken, rowMaps, timeoutMs, bearerToken, apiKey, delimiter)
                        }
                        PayloadStager.stageRowIterator("rest-batch", transformed, delimiter)
                    } finally rows.close()
                statusUtil.info(
                    "processing",
                    "REST batch transformation returned " + newStaged.rowCount + " rows (from " + ctx.data.rowCount + ", " + batchSize + " per call)"
                )
                ctx.copy(data = ctx.data.withStaged(newStaged))

            case "batch" =>
                // No batch size: today's exact single call with every row, in heap
                // and gated by name above PIPELINE_MATERIALIZE_MAX_MB.
                ctx.data.materializeFor("REST endpoint row function in batch mode without a batch size (parameters[5])")
                val rowMaps = ctx.data.rows.map(row => RowUtil.getRowAsMap(row, config, ctx.data.header).asJava)
                val transformedRows = callRestTransformBatch(endpointUrl, pipelineName, pipelineToken, rowMaps, timeoutMs, bearerToken, apiKey, delimiter)
                statusUtil.info("processing", "REST batch transformation returned " + transformedRows.size + " rows (from " + ctx.data.rowCount + ")")
                val newData = ctx.data.withRows(transformedRows)
                ctx.copy(data = newData)

            case _ => // "row" mode: one call per row, streamed straight into a new staged file
                var removed: Long = 0
                val rows = ctx.data.rowIterator()
                val newStaged =
                    try {
                        val transformed = rows.flatMap(row => {
                            val columnMap = RowUtil.getRowAsMap(row, config, ctx.data.header)
                            val result = callRestTransformRow(endpointUrl, pipelineName, pipelineToken, columnMap, timeoutMs, bearerToken, apiKey, delimiter)
                            if (result != null) {
                                Some(result)
                            } else {
                                removed = removed + 1
                                None
                            }
                        })
                        PayloadStager.stageRowIterator("rest-row", transformed, delimiter)
                    } finally rows.close()

                if (removed > 0)
                    statusUtil.info("processing", removed.toString + " rows were removed during the REST endpoint transformation")

                ctx.copy(data = ctx.data.withStaged(newStaged))
        }
    }

    private def callRestTransformRow(
        endpointUrl: String,
        pipelineName: String,
        pipelineToken: String,
        columnMap: mutable.ListMap[String, Any],
        timeoutMs: Int,
        bearerToken: String,
        apiKey: String,
        delimiter: String
    ): String = {
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

    private def callRestTransformBatch(
        endpointUrl: String,
        pipelineName: String,
        pipelineToken: String,
        rowMaps: List[java.util.Map[String, Any]],
        timeoutMs: Int,
        bearerToken: String,
        apiKey: String,
        delimiter: String
    ): List[String] = {
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
        val data = jobContext.data

        statusUtil.info("processing", "AI Transformation instruction: " + instruction)

        // Dispatch on the staged format; the CodeGen script reads and writes
        // files, so the payload never passes through heap.
        if (data.isDelimited && data.rowCount > 0 && data.header != null) {
            val delimiter = config.source.fileAttributes.csvAttributes.delimiter
            statusUtil.info("processing", "CodeGen transformation on " + data.rowCount + " rows")
            val result = CodeGenTransformationEvaluator.transformCsv(instruction, data, delimiter, config.name)
            // The transformation may add, drop or reorder columns. Carry the
            // emitted header forward so downstream loaders project by name
            // against the new shape instead of positionally against the old
            // one (which silently landed values in the wrong columns).
            val newHeader = result.header
            val oldHeader = jobContext.data.header
            if (newHeader.map(_.toLowerCase) != oldHeader.map(_.toLowerCase)) {
                val added = newHeader.filterNot(h => oldHeader.exists(_.equalsIgnoreCase(h)))
                val removed = oldHeader.filterNot(h => newHeader.exists(_.equalsIgnoreCase(h)))
                statusUtil.info(
                    "processing",
                    "CodeGen transformation changed columns" +
                        (if (added.nonEmpty) " — added: " + added.mkString(", ") else "") +
                        (if (removed.nonEmpty) " — removed: " + removed.mkString(", ") else "") +
                        (if (added.isEmpty && removed.isEmpty) " — reordered" else "")
                )
            } else if (!result.headerFromScript)
                statusUtil.info("processing", "CodeGen transformation output carried no header; keeping the source columns")
            val newData = data.withStaged(result.staged).copy(
                header = newHeader,
                headerWithSchema = rebuildHeaderSchema(newHeader, data.headerWithSchema)
            )
            jobContext.copy(data = newData)
        } else if (data.isDocument) {
            val isJson = config.source.fileAttributes.jsonAttributes != null
            statusUtil.info("processing", "CodeGen transformation on " + (if (isJson) "JSON" else "XML") + " data")
            val transformed = CodeGenTransformationEvaluator.transformRaw(instruction, data, isJson, config.name)
            jobContext.copy(data = data.withStaged(transformed))
        } else {
            jobContext
        }
    }

    /** Schema for the transformed header: known columns keep their type, new
      * columns take the declared destination type when there is one, else string. */
    private def rebuildHeaderSchema(header: List[String], existing: List[ai.datris.model.SchemaField]): List[ai.datris.model.SchemaField] = {
        val known = Option(existing).getOrElse(Nil).map(f => f.name.toLowerCase -> f).toMap
        val declared =
            if (config.destination != null && config.destination.schemaProperties != null && config.destination.schemaProperties.fields != null)
                config.destination.schemaProperties.fields.asScala.map(f => f.name.toLowerCase -> f).toMap
            else Map.empty[String, ai.datris.model.SchemaField]
        header.map(h => known.get(h.toLowerCase).orElse(declared.get(h.toLowerCase)).getOrElse(ai.datris.model.SchemaField(h, "string")))
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
        val formatter = new SimpleDateFormat(DatrisEnvironment.current.dateFormat)
        formatter.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
        val pipelineTimestamp = formatter.format(new Date(System.currentTimeMillis()))
        bindings.put("_pipelinetimestamp", pipelineTimestamp)

        engine.eval(script, bindings).asInstanceOf[java.util.HashMap[String, Any]]
    }
}
