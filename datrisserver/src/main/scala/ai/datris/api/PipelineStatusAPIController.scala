package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.util.PipelineStatusUtil
import ai.datris.util.APIKeyValidator
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"),  methods = Array(RequestMethod.GET, RequestMethod.OPTIONS))
class
PipelineStatusAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineStatusAPIController])

    @GetMapping(path = Array("/pipeline/status"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getPipelineStatus(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                         @RequestParam(required = false) pipelinetoken: String,
                         @RequestParam(required=false) pipelinename: String,
                         @RequestParam(required = false) page: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /dataset/status called with pipelinetoken: " + pipelinetoken + ", pipelinename: " + pipelinename + ", page: " + page)
            APIKeyValidator.validate(apiKey)

            val data = {
                if(pipelinetoken == null) {
                    val pageNbr = {
                        if(page == null)
                            1
                        else
                            page.toInt
                    }
                    PipelineStatusUtil.getPipelineStatusSummary(pipelinename, pageNbr)
                }
                else
                    PipelineStatusUtil.getPipelineStatus(pipelinetoken)
            }
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(data), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}