package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{PipelineConfig, DatrisEnvironment, DatrisException}
import ai.datris.util.{PipelineConfigIO, NoSQLDbUtil}
import ai.datris.util._
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"), methods = Array(RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS))
class PipelineAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[PipelineAPIController])

    @GetMapping(path = Array("/pipeline"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getDataset(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                         @RequestParam pipeline: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /dataset called with pipeline: " + pipeline)
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.values.pipelineTableName, pipeline)
            if(config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")
            val gson = new Gson
            val json = gson.toJson(config)
            new ResponseEntity[String](json, HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/pipelines"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getDatasets(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /datasets called")
            APIKeyValidator.validate(apiKey)

            val pipelineNames = NoSQLDbUtil.getItemsKeysByKeyName(DatrisEnvironment.values.pipelineTableName, "name")
            val pipelineConfigs = pipelineNames.map(name => {
                PipelineConfigIO.read(DatrisEnvironment.values.pipelineTableName, name)
            }).asJava

            val gson = new Gson
            val json = gson.toJson(pipelineConfigs)
            new ResponseEntity[String](json, HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/pipeline"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def putDataset(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                         @RequestBody config: PipelineConfig): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /dataset with pipeline name: " + config.name)
            APIKeyValidator.validate(apiKey)

            PipelineValidatorUtil.validate(config)
            val modifiedConfig = PipelineValidatorUtil.modify(config)

            // Write to NoSQL pipeline table
            PipelineConfigIO.write(modifiedConfig)

            // If the source is a database, initialize the pipeline pull table
            if(modifiedConfig.source.databaseAttributes != null)
                PipelinePullTableUtil.initialize(modifiedConfig.name, modifiedConfig.source.databaseAttributes.cronExpression)

            new ResponseEntity[String](HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/pipeline"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteDataset(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                            @RequestParam pipeline: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /dataset with pipeline name: " + pipeline)
            APIKeyValidator.validate(apiKey)

            val config = PipelineConfigIO.read(DatrisEnvironment.values.pipelineTableName, pipeline)
            if(config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")

            if(config.source.databaseAttributes != null)
                PipelinePullTableUtil.deleteEntryIfExists(config.name)

            // Delete the json configuration
            NoSQLDbUtil.deleteItemJSON(DatrisEnvironment.values.pipelineTableName, "name", pipeline)

            new ResponseEntity[String](HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
