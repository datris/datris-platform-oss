package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.GlobalJobContext
import ai.datris.util.{APIKeyValidator, PostgresQueryUtil, MongoDBQueryUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"), methods = Array(RequestMethod.POST, RequestMethod.OPTIONS))
class QueryAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[QueryAPIController])

    @PostMapping(path = Array("/query/postgres"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def queryPostgres(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                      @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /query/postgres called")
            APIKeyValidator.validate(apiKey)

            val sql = Option(body.get("sql")).map(_.toString)
                .getOrElse(throw new ai.datris.model.DatrisException("'sql' parameter is required"))
            val database = Option(body.get("database")).map(_.toString).getOrElse("idata")
            val limit = Option(body.get("limit")).map {
                case d: java.lang.Double => d.intValue()
                case i: java.lang.Integer => i.intValue()
                case other => other.toString.toInt
            }.getOrElse(100)

            val results = PostgresQueryUtil.query(sql, database, limit)

            val gson = new Gson
            val response = new java.util.LinkedHashMap[String, Any]()
            response.put("results", results)
            response.put("count", results.size())
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/query/mongodb"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def queryMongoDB(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                     @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /query/mongodb called")
            APIKeyValidator.validate(apiKey)

            val collection = Option(body.get("collection")).map(_.toString)
                .getOrElse(throw new ai.datris.model.DatrisException("'collection' parameter is required"))
            val filter = Option(body.get("filter"))
                .map(_.asInstanceOf[java.util.Map[String, Any]])
                .getOrElse(new java.util.HashMap[String, Any]())
            val projection = Option(body.get("projection"))
                .map(_.asInstanceOf[java.util.Map[String, Any]])
                .orNull
            val limit = Option(body.get("limit")).map {
                case d: java.lang.Double => d.intValue()
                case i: java.lang.Integer => i.intValue()
                case other => other.toString.toInt
            }.getOrElse(20)

            val results = MongoDBQueryUtil.query(collection, filter, projection, limit)

            // Parse each JSON string back into an object for proper nesting
            val gson = new Gson
            val parsedResults = results.asScala.map(jsonStr =>
                gson.fromJson(jsonStr, classOf[java.util.Map[String, Any]])
            ).asJava

            val response = new java.util.LinkedHashMap[String, Any]()
            response.put("results", parsedResults)
            response.put("count", parsedResults.size())
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/job/kill"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def killJob(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                @RequestBody body: java.util.Map[String, Any]): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /job/kill called")
            APIKeyValidator.validate(apiKey)

            val pipelineToken = Option(body.get("pipelineToken")).map(_.toString)
                .getOrElse(throw new ai.datris.model.DatrisException("'pipelineToken' parameter is required"))

            GlobalJobContext.killJob(pipelineToken)

            val gson = new Gson
            val response = new java.util.LinkedHashMap[String, Any]()
            response.put("status", "cancelled")
            response.put("pipelineToken", pipelineToken)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
