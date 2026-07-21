package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.DatrisEnvironment
import ai.datris.util.{NoSQLDbUtil, PipelineStatusUtil, APIKeyValidator}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

@RestController
@RequestMapping(Array("/api/v1"))
class PipelineStatusAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineStatusAPIController])

    @GetMapping(path = Array("/pipeline/status"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipelineStatus(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(required = false) pipelinetoken: String,
        @RequestParam(required = false) publishertoken: String,
        @RequestParam(required = false) pipelinename: String,
        @RequestParam(required = false) page: String,
        @RequestParam(required = false) withrollup: String
    ): ResponseEntity[String] = {
        try {
            logger.info(
                "API endpoint GET /pipeline/status called with pipelinetoken: " + pipelinetoken + ", publishertoken: " + publishertoken + ", pipelinename: " + pipelinename + ", page: " + page + ", withrollup: " + withrollup
            )
            APIKeyValidator.validate(apiKey)

            val rollup = withrollup != null && withrollup.equalsIgnoreCase("true")

            // publishertoken wins when both are sent — it's the broader query and a
            // single pipelineToken's rows are a strict subset of its publisherToken's rows.
            // withrollup=true is only meaningful for token queries (publisher/pipeline);
            // the paginated summary path returns its own rollup-equivalent shape.
            val data: AnyRef = {
                if (publishertoken != null) {
                    if (rollup) PipelineStatusUtil.getPipelineStatusByPublisherWithRollup(publishertoken)
                    else PipelineStatusUtil.getPipelineStatusByPublisher(publishertoken)
                } else if (pipelinetoken == null) {
                    val pageNbr = {
                        if (page == null)
                            1
                        else
                            page.toInt
                    }
                    PipelineStatusUtil.getPipelineStatusSummary(pipelinename, pageNbr)
                } else {
                    if (rollup) PipelineStatusUtil.getPipelineStatusWithRollup(pipelinetoken)
                    else PipelineStatusUtil.getPipelineStatus(pipelinetoken)
                }
            }
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(data), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/pipeline/status"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def clearAllStatus(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /pipeline/status called — clearing all ingestion history")
            APIKeyValidator.validate(apiKey)

            val tableName = DatrisEnvironment.current.pipelineStatusTableName
            val detailCount = NoSQLDbUtil.deleteAll(tableName)
            val summaryCount = NoSQLDbUtil.deleteAll(tableName + "-summary")

            logger.info("Cleared " + detailCount + " detail entries and " + summaryCount + " summary entries")

            val gson = new Gson
            val response = new java.util.LinkedHashMap[String, Any]()
            response.put("deleted", detailCount + summaryCount)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
