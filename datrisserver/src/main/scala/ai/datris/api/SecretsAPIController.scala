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
class SecretsAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SecretsAPIController])
    private val SENSITIVE_FIELDS = Set("password", "apikey", "secretkey", "token", "secret")
    private val LOCKED_AI_SLOTS_ON_TRIAL = Set("ai-primary", "codegen", "embedding")

    /** On trial tenants, block mutations to the three AI configuration slots.
      * Trials run on shared Datris-managed Anthropic/OpenAI keys; allowing a tenant
      * to point its endpoint or model at an attacker-controlled URL would exfiltrate
      * the shared key on the next AI call. UI hiding alone is cosmetic — this is
      * the actual security boundary. Returns Some(403 response) when the request
      * should be rejected, None when it should proceed. */
    private def rejectIfTrialAiSecret(name: String): Option[ResponseEntity[String]] = {
        if (DatrisEnvironment.current.isTrial && LOCKED_AI_SLOTS_ON_TRIAL.contains(name)) {
            Some(ResponseEntity.status(HttpStatus.FORBIDDEN).body[String](
                "{\"error\": \"AI configuration is locked on the trial. " +
                "Visit https://datris.ai/dashboard to upgrade to a dedicated instance.\"}"))
        } else None
    }

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
                        if (isSensitive(key) && value != null && value.nonEmpty) {
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
            rejectIfTrialAiSecret(name).getOrElse {
                val env = DatrisEnvironment.current.environment
                val secretPath = env + "/" + name

                // Existing secret at the same path — used to preserve sensitive fields
                // when the request sends them as the masked placeholder.
                val existing = SecretsUtil.getSecretMap(secretPath).map(_.asScala).getOrElse(scala.collection.mutable.Map.empty[String, String])

                val json = JsonParser.parseString(body).getAsJsonObject
                val incoming = new java.util.LinkedHashMap[String, Object]()
                json.entrySet().asScala.foreach { entry =>
                    val value = entry.getValue
                    if (value.isJsonPrimitive) {
                        val key = entry.getKey
                        val strValue = value.getAsString
                        if (isSensitive(key) && strValue == "••••••••") {
                            // Masked placeholder ⇒ preserve existing value at this path (if any).
                            existing.get(key).filter(_.nonEmpty).foreach(v => incoming.put(key, v))
                        } else {
                            incoming.put(key, strValue)
                        }
                    }
                }

                // Special-case the codegen secret: if the request omits or blanks out apiKey,
                // copy it from the AI primary secret at {env}/ai-primary. This lets the UI
                // omit the apiKey when the user wants codegen to reuse the main key without
                // re-entering it.
                if (name == "codegen") {
                    val providedApiKey = Option(incoming.get("apiKey")).map(_.asInstanceOf[String]).getOrElse("")
                    if (providedApiKey.isEmpty) {
                        val mainKey = SecretsUtil.getSecretMap(env + "/ai-primary")
                            .flatMap(m => Option(m.get("apiKey")))
                            .filter(_.nonEmpty)
                        mainKey.foreach(k => incoming.put("apiKey", k))
                    }
                }

                SecretsUtil.writeSecret(secretPath, incoming)

                // Hot-reload AI config when an AI secret changes — no restart required.
                if (Set("ai-primary", "codegen").contains(name)) {
                    DatrisEnvironment.reloadAiConfig()
                    logger.info("AI configuration reloaded from Vault after PUT /secrets/" + name)
                }

                new ResponseEntity[String]("{\"status\": \"ok\"}", HttpStatus.OK)
            }
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
            rejectIfTrialAiSecret(name).getOrElse {
                val env = DatrisEnvironment.current.environment
                val secretPath = env + "/" + name
                SecretsUtil.deleteSecret(secretPath)
                new ResponseEntity[String]("{\"status\": \"ok\"}", HttpStatus.OK)
            }
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
