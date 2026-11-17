package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonElement, JsonParseException, JsonParser}
import ai.datris.model.{DatrisEnvironment, ValidationException}
import ai.datris.util.{AISchemaUtil, APIKeyValidator, AiSampleValues, ObjectStoreUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.multipart.MultipartFile

import java.io.ByteArrayInputStream

/** Pure request checks for [[ConfigAPIController]]. Every refusal is a
  * [[ValidationException]], which `ApiErrors.classify` maps to
  * `400 {"error": <message>}`. No message echoes the request body or the JSON
  * parser's own text. */
object ConfigAPIController {
    private[api] val ValidTypes = Set("validation-schema", "javascript")
    private[api] val ValidSchemaTypes = Set("json-schema", "xsd")

    private val RequiredFields = Seq("type", "name", "sampleData")

    /** A checked generate-schema request; `name` is trimmed and already a safe key segment. */
    case class GenerateSchemaRequest(schemaType: String, name: String, sampleData: String)

    /** Reduce a user-supplied filename to a safe single object-key segment.
      * Without this, a name like `../tap-scripts/x.py` escapes the intended
      * prefix and can write into another prefix of the same config bucket —
      * including the tap-scripts prefix, whose files are later executed. Strip
      * any path, reject traversal, and restrict to a safe charset. */
    private[api] def sanitizeKeySegment(name: String): String = {
        if (name == null) throw new ValidationException("File name is required")
        val base = name.replace("\\", "/").split("/").filter(_.nonEmpty).lastOption.getOrElse("")
        if (base.isEmpty || base == "." || base == "..")
            throw new ValidationException("Invalid file name: " + name)
        if (!base.matches("[A-Za-z0-9._-]+"))
            throw new ValidationException("File name '" + base + "' contains invalid characters. Allowed: letters, digits, . _ -")
        base
    }

    private[api] def requireUploadType(fileType: String): Unit =
        if (!ValidTypes.contains(fileType))
            throw new ValidationException("Invalid config file type: " + fileType + ". Must be one of: " + ValidTypes.mkString(", "))

    /** Parse and check a POST /config/generate-schema body. Checks, in order:
      * valid JSON, a JSON object, all of type/name/sampleData present and not
      * null, each a JSON primitive, then the schema type, blank name, blank
      * sample data, and finally the name as an object-key segment. */
    private[api] def parseGenerateSchema(body: String): GenerateSchemaRequest = {
        val parsed: JsonElement =
            if (body == null) null
            else
                try JsonParser.parseString(body)
                catch { case _: JsonParseException => throw new ValidationException("Request body is not valid JSON") }
        if (parsed == null || !parsed.isJsonObject)
            throw new ValidationException("Request body must be a JSON object with string fields: " + RequiredFields.mkString(", "))
        val json = parsed.getAsJsonObject

        val missing = RequiredFields.filter(f => !json.has(f) || json.get(f).isJsonNull)
        if (missing.nonEmpty)
            throw new ValidationException("Missing required field(s): " + missing.mkString(", "))
        RequiredFields.find(f => !json.get(f).isJsonPrimitive).foreach { f =>
            throw new ValidationException("Field '" + f + "' must be a string")
        }

        val schemaType = json.get("type").getAsString
        val name = json.get("name").getAsString
        val sampleData = json.get("sampleData").getAsString

        if (!ValidSchemaTypes.contains(schemaType))
            throw new ValidationException("Invalid schema type: " + schemaType + ". Must be one of: " + ValidSchemaTypes.mkString(", "))
        if (name.trim.isEmpty)
            throw new ValidationException("Schema name is required")
        if (sampleData.trim.isEmpty)
            throw new ValidationException("Sample data is required")

        GenerateSchemaRequest(schemaType, sanitizeKeySegment(name.trim), sampleData)
    }
}

@RestController
@RequestMapping(Array("/api/v1"))
class ConfigAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ConfigAPIController])

    @PostMapping(path = Array("/config/upload"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def uploadConfigFile(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam("type") fileType: String,
        @RequestPart("file") file: MultipartFile
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /config/upload called, type: " + fileType + ", filename: " + file.getOriginalFilename)
            APIKeyValidator.validate(apiKey)

            ConfigAPIController.requireUploadType(fileType)

            val filename = ConfigAPIController.sanitizeKeySegment(file.getOriginalFilename)

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
                ApiErrors.internal(e)
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

            val req = ConfigAPIController.parseGenerateSchema(body)
            val sampleData = req.sampleData

            val (schema, extension) = req.schemaType match {
                case "json-schema" => (AISchemaUtil.generateJsonSchema(sampleData), ".json")
                case "xsd" => (AISchemaUtil.generateXsdSchema(sampleData), ".xsd")
            }

            val filename = req.name + extension
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
            // DATRIS_AI_SAMPLE_VALUES=false: generated from a value-free skeleton.
            if (!AiSampleValues.enabled) response.put("valuesWithheld", true)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }
}
