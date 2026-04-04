package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, DatrisException, GlobalJobContext, TapConfig}
import ai.datris.controller.{JobRunner, StreamNotifier}
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant

object TapRunner {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /**
     * Execute a tap: run the script, feed results to the target pipeline.
     *
     * @param tapConfig the tap to run
     * @param pushToPipeline if true, push records to pipeline and update status in DB; if false, just execute and return (test mode)
     * @return TapScriptResult with fetched records
     */
    def run(tapConfig: TapConfig, pushToPipeline: Boolean = true): TapScriptResult = {
        val now = Instant.now().toString

        // Only update status in DB for real runs, not tests
        if (pushToPipeline) {
            val runningConfig = tapConfig.copy(lastRunStatus = "running", lastRunTime = now, lastRunError = null)
            TapConfigIO.write(runningConfig)
        }

        try {
            val result = TapScriptRunner.run(tapConfig)

            if (result.error != null) {
                if (pushToPipeline) {
                    val failedConfig = tapConfig.copy(
                        lastRunStatus = "failure",
                        lastRunTime = now,
                        lastRunRecordCount = 0,
                        lastRunError = result.error
                    )
                    TapConfigIO.write(failedConfig)
                }
                return result
            }

            // Push to pipeline if requested, records exist, and a target pipeline is configured
            if (pushToPipeline && result.records != null && result.recordCount > 0 &&
                tapConfig.targetPipeline != null && tapConfig.targetPipeline.nonEmpty) {
                feedPipeline(tapConfig, result)
            }

            if (pushToPipeline) {
                val successConfig = tapConfig.copy(
                    lastRunStatus = "success",
                    lastRunTime = now,
                    lastRunRecordCount = result.recordCount,
                    lastRunError = null,
                    lastRunDataType = result.dataType,
                    lastRunColumns = result.columns
                )
                TapConfigIO.write(successConfig)
            }

            result
        } catch {
            case e: Exception =>
                logger.error("TapRunner failed for tap: " + tapConfig.name, e)
                if (pushToPipeline) {
                    val failedConfig = tapConfig.copy(
                        lastRunStatus = "failure",
                        lastRunTime = now,
                        lastRunRecordCount = 0,
                        lastRunError = e.getMessage
                    )
                    TapConfigIO.write(failedConfig)
                }
                TapScriptResult(null, 0, e.getMessage)
        }
    }

    private def feedPipeline(tapConfig: TapConfig, result: TapScriptResult): Unit = {
        logger.info("TapRunner: feeding " + result.recordCount + " records to pipeline: " + tapConfig.targetPipeline)

        // Check what format the pipeline expects
        val pipelineConfig = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, tapConfig.targetPipeline)
        val pipelineExpectsCsv = pipelineConfig != null &&
            pipelineConfig.source != null &&
            pipelineConfig.source.fileAttributes != null &&
            pipelineConfig.source.fileAttributes.csvAttributes != null

        val (bytes, filename) = if (pipelineExpectsCsv) {
            val delimiter = if (pipelineConfig.source.fileAttributes.csvAttributes.delimiter != null)
                pipelineConfig.source.fileAttributes.csvAttributes.delimiter else ","
            try {
                val csv = jsonToCsv(result.records, delimiter)
                (csv.getBytes("UTF-8"), "tap-" + tapConfig.name + ".csv")
            } catch {
                case e: Exception =>
                    logger.error("TapRunner: jsonToCsv failed: " + e.getMessage)
                    (result.records.getBytes("UTF-8"), "tap-" + tapConfig.name + ".json")
            }
        } else {
            (result.records.getBytes("UTF-8"), "tap-" + tapConfig.name + ".json")
        }

        val jobContext = new StreamNotifier().process(bytes, filename, tapConfig.targetPipeline, null)
        GlobalJobContext.addJobContext(jobContext)
        logger.info("TapRunner: submitted job for pipeline: " + tapConfig.targetPipeline + ", token: " + jobContext.pipelineToken)
    }

    private def jsonToCsv(json: String, delimiter: String = ","): String = {
        import scala.collection.JavaConverters._
        val jsonArray = com.google.gson.JsonParser.parseString(json).getAsJsonArray
        if (jsonArray.size() == 0) return ""

        val firstObj = jsonArray.get(0).getAsJsonObject
        val columns = firstObj.keySet().asScala.toList
        val header = columns.mkString(delimiter)

        val rows = (0 until jsonArray.size()).map(i => {
            val obj = jsonArray.get(i).getAsJsonObject
            columns.map(col => {
                val elem = obj.get(col)
                if (elem == null || elem.isJsonNull) ""
                else {
                    val s = if (elem.isJsonPrimitive) {
                        val prim = elem.getAsJsonPrimitive
                        if (prim.isString) prim.getAsString
                        else prim.getAsString // returns raw number string: "1782800", "254.2"
                    } else elem.toString
                    if (s.contains(delimiter) || s.contains("\"") || s.contains("\n") || s.contains("\r"))
                        "\"" + s.replace("\"", "\"\"") + "\""
                    else s
                }
            }).mkString(delimiter)
        })

        (header +: rows).mkString("\n")
    }
}
