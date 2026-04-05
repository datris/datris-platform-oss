package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonParser}
import ai.datris.model.DatrisEnvironment
import ai.datris.util.{APIKeyValidator, SecretsUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@CrossOrigin(origins = Array("*"), methods = Array(RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS))
class SecretsAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SecretsAPIController])
    private val SENSITIVE_FIELDS = Set("password", "apikey", "secretkey", "token", "secret")

    @GetMapping(path = Array("/secrets"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listSecrets(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                    @RequestParam(required = false, name = "type") secretType: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /secrets called" + (if (secretType != null) ", type=" + secretType else ""))
            APIKeyValidator.validate(apiKey)

            val env = DatrisEnvironment.current.environment
            val allSecrets = SecretsUtil.listSecrets(env)

            val secrets = if (secretType != null && secretType.nonEmpty) {
                // Filter by _type field
                allSecrets.filter(name => {
                    val secretMap = SecretsUtil.getSecretMap(env + "/" + name)
                    secretMap.exists(m => secretType.equals(m.get("_type")))
                })
            } else allSecrets

            val gson = new Gson
            new ResponseEntity[String](gson.toJson(secrets.asJava), HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @GetMapping(path = Array("/secrets/{name}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getSecret(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                  @PathVariable name: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /secrets/" + name + " called")
            APIKeyValidator.validate(apiKey)

            val env = DatrisEnvironment.current.environment
            val secretPath = env + "/" + name
            val secretMap = SecretsUtil.getSecretMap(secretPath)

            secretMap match {
                case Some(data) =>
                    val result = new java.util.LinkedHashMap[String, Any]()
                    result.put("name", name)

                    val fields = new java.util.LinkedHashMap[String, String]()
                    data.asScala.foreach { case (key, value) =>
                        if (isSensitive(key)) {
                            fields.put(key, "••••••••")
                        } else {
                            fields.put(key, value)
                        }
                    }
                    result.put("fields", fields)

                    val gson = new Gson
                    new ResponseEntity[String](gson.toJson(result), HttpStatus.OK)
                case None =>
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body[String]("{\"error\": \"Secret not found: " + name + "\"}")
            }
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @PutMapping(path = Array("/secrets/{name}"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def putSecret(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                  @PathVariable name: String,
                  @RequestBody body: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint PUT /secrets/" + name + " called")
            APIKeyValidator.validate(apiKey)

            val env = DatrisEnvironment.current.environment
            val secretPath = env + "/" + name

            val json = JsonParser.parseString(body).getAsJsonObject
            val data = new java.util.LinkedHashMap[String, Object]()
            json.entrySet().asScala.foreach { entry =>
                val value = entry.getValue
                if (value.isJsonPrimitive) {
                    data.put(entry.getKey, value.getAsString)
                }
            }

            SecretsUtil.writeSecret(secretPath, data)
            new ResponseEntity[String]("{\"status\": \"ok\"}", HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    @DeleteMapping(path = Array("/secrets/{name}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteSecret(@RequestHeader(name = "x-api-key", required = false) apiKey: String,
                     @PathVariable name: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /secrets/" + name + " called")
            APIKeyValidator.validate(apiKey)

            val env = DatrisEnvironment.current.environment
            val secretPath = env + "/" + name
            SecretsUtil.deleteSecret(secretPath)
            new ResponseEntity[String]("{\"status\": \"ok\"}", HttpStatus.OK)
        }
        catch {
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](Throwables.getStackTraceAsString(e))
        }
    }

    private def isSensitive(fieldName: String): Boolean = {
        SENSITIVE_FIELDS.contains(fieldName.toLowerCase.replaceAll("[_-]", ""))
    }
}
