package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model.{DatrisEnvironment, DatrisException}
import ai.datris.util.{APIKeyValidator, ObjectStoreUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._
import org.springframework.web.multipart.MultipartFile

import java.io.ByteArrayInputStream

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"), methods = Array(RequestMethod.POST, RequestMethod.OPTIONS))
class ConfigAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ConfigAPIController])

    private val VALID_TYPES = Set("validation-schema", "javascript")

    @PostMapping(path = Array("/config/upload"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def uploadConfigFile(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                         @RequestParam("type") fileType: String,
                         @RequestPart("file") file: MultipartFile): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /config/upload called, type: " + fileType + ", filename: " + file.getOriginalFilename)
            APIKeyValidator.validate(apiKey)

            if (!VALID_TYPES.contains(fileType))
                throw new DatrisException("Invalid config file type: " + fileType + ". Must be one of: " + VALID_TYPES.mkString(", "))

            val filename = file.getOriginalFilename
            if (filename == null || filename.isEmpty)
                throw new DatrisException("File must have a name")

            val bucket = DatrisEnvironment.values.environment + "-config"
            val key = fileType + "/" + filename

            val bytes = file.getBytes
            ObjectStoreUtil.writeBucketObjectFromStream(bucket, key, new ByteArrayInputStream(bytes), bytes.length.toLong)

            logger.info("Config file uploaded: s3://" + bucket + "/" + key)

            val gson = new Gson
            val response = new java.util.LinkedHashMap[String, Any]()
            response.put("filename", filename)
            response.put("path", "s3://" + bucket + "/" + key)
            new ResponseEntity[String](gson.toJson(response), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }
}
