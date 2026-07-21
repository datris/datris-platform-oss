package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonParser}
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.util.{AISchemaUtil, APIKeyValidator, ObjectStoreUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.multipart.MultipartFile

import java.io.ByteArrayInputStream

@RestController
@RequestMapping(Array("/api/v1"))
class ConfigAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ConfigAPIController])

    private val VALID_TYPES = Set("validation-schema", "javascript")
    private val VALID_SCHEMA_TYPES = Set("json-schema", "xsd")

    @PostMapping(path = Array("/config/upload"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def uploadConfigFile(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam("type") fileType: String,
        @RequestPart("file") file: MultipartFile
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /config/upload called, type: " + fileType + ", filename: " + file.getOriginalFilename)
            APIKeyValidator.validate(apiKey)

            if (!VALID_TYPES.contains(fileType))
                throw new DatrisException("Invalid config file type: " + fileType + ". Must be one of: " + VALID_TYPES.mkString(", "))

            val filename = file.getOriginalFilename
            if (filename == null || filename.isEmpty)
                throw new DatrisException("File must have a name")

            val bucket = DatrisEnvironment.current.environment + "-config"
            val key = fileType + "/" + filename

            val bytes = file.getBytes
            ObjectStoreUtil.writeBucketObjectFromStream(bucket, key, new ByteArrayInputStream(bytes), bytes.length.toLong)

            logger.info("Config file uploaded: s3://" + bucket + "/" + key)

            val gson = new Gson
            val response = new java.util.LinkedHashMap[String, Any]()
            response.put("filename", filename)
            response.put("path", "s3://" + bucket + "/" + key)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PostMapping(
        path = Array("/config/generate-schema"),
        consumes = Array(MediaType.APPLICATION_JSON_VALUE),
        produces = Array(MediaType.APPLICATION_JSON_VALUE)
    )
    def generateSchema(@RequestHeader(name = "x-api-key", required = false) apiKey: String, @RequestBody body: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /config/generate-schema called")
            APIKeyValidator.validate(apiKey)

            val json = JsonParser.parseString(body).getAsJsonObject
            val schemaType = json.get("type").getAsString
            val name = json.get("name").getAsString
            val sampleData = json.get("sampleData").getAsString

            if (!VALID_SCHEMA_TYPES.contains(schemaType))
                throw new DatrisException("Invalid schema type: " + schemaType + ". Must be one of: " + VALID_SCHEMA_TYPES.mkString(", "))
            if (name == null || name.trim.isEmpty)
                throw new DatrisException("Schema name is required")
            if (sampleData == null || sampleData.trim.isEmpty)
                throw new DatrisException("Sample data is required")

            val (schema, extension) = schemaType match {
                case "json-schema" => (AISchemaUtil.generateJsonSchema(sampleData), ".json")
                case "xsd" => (AISchemaUtil.generateXsdSchema(sampleData), ".xsd")
            }

            val filename = name.trim + extension
            val bucket = DatrisEnvironment.current.environment + "-config"
            val key = "validation-schema/" + filename

            val bytes = schema.getBytes("UTF-8")
            ObjectStoreUtil.writeBucketObjectFromStream(bucket, key, new java.io.ByteArrayInputStream(bytes), bytes.length.toLong)

            logger.info("Generated schema uploaded: s3://" + bucket + "/" + key)

            val gson = new Gson
            val response = new java.util.LinkedHashMap[String, Any]()
            response.put("filename", filename)
            response.put("path", "s3://" + bucket + "/" + key)
            response.put("schema", schema)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
