package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.build.sbt.BuildInfo
import ai.datris.util.APIKeyValidator
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"),  methods = Array(RequestMethod.GET, RequestMethod.OPTIONS))
class VersionAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[VersionAPIController])

    @GetMapping(path = Array("/version"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getVersion(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /api/v1/version called")
            APIKeyValidator.validate(apiKey)
            val map = Map("version" -> BuildInfo.version).asJava
            val gson = new Gson
            new ResponseEntity[String](gson.toJson(map), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}