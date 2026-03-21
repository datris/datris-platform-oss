package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{DatasetConfig, DatrisEnvironment, DatrisException}
import ai.datris.util.{DatasetConfigIO, NoSQLDbUtil}
import ai.datris.util._
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"), methods = Array(RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS))
class DatasetAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[DatasetAPIController])

    @GetMapping(path = Array("/dataset"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getDataset(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                         @RequestParam dataset: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /dataset called with dataset: " + dataset)
            APIKeyValidator.validate(apiKey)

            val config = DatasetConfigIO.read(DatrisEnvironment.values.datasetTableName, dataset)
            if(config == null)
                throw new DatrisException("Dataset: " + dataset + " is not configured in the NoSQL database")
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

    @GetMapping(path = Array("/datasets"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getDatasets(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /datasets called")
            APIKeyValidator.validate(apiKey)

            val datasetNames = NoSQLDbUtil.getItemsKeysByKeyName(DatrisEnvironment.values.datasetTableName, "name")
            val datasetConfigs = datasetNames.map(name => {
                DatasetConfigIO.read(DatrisEnvironment.values.datasetTableName, name)
            }).asJava

            val gson = new Gson
            val json = gson.toJson(datasetConfigs)
            new ResponseEntity[String](json, HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(path = Array("/dataset"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def putDataset(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                         @RequestBody config: DatasetConfig): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /dataset with dataset name: " + config.name)
            APIKeyValidator.validate(apiKey)

            DatasetValidatorUtil.validate(config)
            val modifiedConfig = DatasetValidatorUtil.modify(config)

            // Write to NoSQL dataset table
            DatasetConfigIO.write(modifiedConfig)

            // If the source is a database, initialize the dataset pull table
            if(modifiedConfig.source.databaseAttributes != null)
                DataPullTableUtil.initialize(modifiedConfig.name, modifiedConfig.source.databaseAttributes.cronExpression)

            new ResponseEntity[String](HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/dataset"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteDataset(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                            @RequestParam dataset: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /dataset with dataset name: " + dataset)
            APIKeyValidator.validate(apiKey)

            val config = DatasetConfigIO.read(DatrisEnvironment.values.datasetTableName, dataset)
            if(config == null)
                throw new DatrisException("Dataset: " + dataset + " is not configured in the NoSQL database")

            if(config.source.databaseAttributes != null)
                DataPullTableUtil.deleteEntryIfExists(config.name)

            // Delete the json configuration
            NoSQLDbUtil.deleteItemJSON(DatrisEnvironment.values.datasetTableName, "name", dataset)

            new ResponseEntity[String](HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
