package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonParser}
import ai.datris.auth.{CapabilityCheck, ResolvedKeyAccess}
import ai.datris.config.RequiresRole
import ai.datris.model.DatrisEnvironment
import ai.datris.util.{APIKeyValidator, SecretsUtil}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@RequiresRole(Array("admin"))
class SecretsAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SecretsAPIController])
    // Substring markers in normalized field names (lowercased, underscores/hyphens
    // stripped) that flag a field as carrying a credential value. Substring rather
    // than exact-match because real-world field names commonly carry source/scope
    // prefixes or suffixes — an exact-match list misses those variations and
    // leaks the value.
    //
    // Mask aggressively: false positives (a field that's masked but the user
    // wanted visible) are recoverable via the Edit flow; false negatives (a
    // credential leaking in plain text on the Configuration screen) are not.
    private val SENSITIVE_MARKERS = Seq(
        "password", "passwd", "pwd",
        "secret",
        "token",
        "key",
        "credential",
        "signature",
        "bearer",
        "private"
    )

    // Fields whose normalized name pattern-matches a SENSITIVE_MARKER but are
    // platform-injected bookkeeping/metadata, not the credential itself. Keep
    // this list tight — add only for fields the platform itself writes (not
    // user-supplied field names).
    private val ALWAYS_PLAIN = Set(
        "createdbykeylabel"  // matches "key" but stores a label, not a credential value
    )


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
                // type=platform → all secrets NOT tagged _type=tap (mirrors the
                // UI's Platform tab). Any other value → exact-match on _type.
                if ("platform".equals(secretType)) {
                    allSecrets.filter(name => {
                        val secretMap = SecretsUtil.getSecretMap(env + "/" + name)
                        // Include when the secret has no _type (most platform
                        // secrets predate the tag) or when _type != "tap".
                        secretMap.exists(m => {
                            val t = m.get("_type")
                            t == null || !"tap".equals(t)
                        })
                    })
                } else {
                    allSecrets.filter(name => {
                        val secretMap = SecretsUtil.getSecretMap(env + "/" + name)
                        secretMap.exists(m => secretType.equals(m.get("_type")))
                    })
                }
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
                  @RequestBody body: String,
                  request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint PUT /secrets/" + name + " called")
            APIKeyValidator.validate(apiKey)
            rejectIfTrialAiSecret(name).getOrElse {
                val env = DatrisEnvironment.current.environment
                val secretPath = env + "/" + name

                // Existing secret at the same path — used to preserve sensitive fields
                // when the request sends them as the masked placeholder.
                val existing = SecretsUtil.getSecretMap(secretPath).map(_.asScala).getOrElse(scala.collection.mutable.Map.empty[String, String])

                // In-action capability scope check for `secret:write:_type=tap`
                // keys. The interceptor's scope-agnostic gate already confirmed
                // the key holds `secret:write` for some scope; we now verify
                // the actual target (existing secret's _type, or "tap" for a
                // brand-new tap secret) satisfies the key's scope predicates.
                // Server-side parallel to the Python `_type=tap` filter on the
                // MCP path — both retained for defense in depth.
                val existingType = existing.get("_type").getOrElse("")
                val scopeContext = if (existingType.nonEmpty) Map("_type" -> existingType)
                                   else Map.empty[String, String]
                CapabilityCheck.assertScope(request, "secret", "write", scopeContext)

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

                // Provider-change apiKey clearing — applies to every AI section. When
                // the user switches a section's provider (e.g. Anthropic → OpenAI), the
                // masked-preservation step above blindly keeps the OLD provider's
                // apiKey, which would fail with 401 at runtime. Drop the preserved key
                // so the loader either picks it up from the env-var fallback (single
                // tenant) or fails closed (multi-tenant — tenant must re-enter).
                if (Set("ai-primary", "codegen", "embedding", "web-search").contains(name)) {
                    val incomingProvider = Option(incoming.get("provider")).map(_.asInstanceOf[String].toLowerCase).getOrElse("")
                    val existingProvider = existing.get("provider").map(_.toLowerCase).getOrElse("")
                    if (existingProvider.nonEmpty && incomingProvider.nonEmpty && existingProvider != incomingProvider) {
                        logger.info("PUT /secrets/" + name + ": provider changed from '" + existingProvider + "' to '" + incomingProvider + "' — clearing preserved apiKey (will resolve from env var if available)")
                        incoming.remove("apiKey")
                    }
                }

                // Owner-tag the secret with the issuing key's label. Preserve on
                // update so ownership reflects who created the secret, not who
                // last edited it. Skip if the existing secret already has a
                // value to avoid clobbering.
                val existingOwner = existing.get("createdByKeyLabel").filter(_.nonEmpty)
                existingOwner match {
                    case Some(prior) =>
                        incoming.put("createdByKeyLabel", prior)
                    case None =>
                        ResolvedKeyAccess.keyLabel(request).foreach(label =>
                            incoming.put("createdByKeyLabel", label))
                }

                SecretsUtil.writeSecret(secretPath, incoming)

                // Mirror the UI identity's key value into oss/api-keys under
                // the reserved `ui` label so it actually validates at the auth
                // layer. Without this, saving a new value here would break the
                // UI and the Assistant on the next request (key not recognized).
                if (name == "ui-api-key") {
                    val incomingValue = Option(incoming.get("apiKey")).map(_.asInstanceOf[String]).filter(_.nonEmpty)
                    incomingValue.foreach { v =>
                        try mirrorUiKeyIntoApiKeys(env, v)
                        catch {
                            case e: Exception =>
                                logger.warn("Failed to mirror ui-api-key into oss/api-keys: " + e.getMessage)
                        }
                    }
                    APIKeyValidator.invalidateCache()
                }

                // Hot-reload AI config when an AI secret changes — no restart required.
                // web-search rides the same reload because reloadAiConfig() refreshes the
                // webSearchConfig field too.
                if (Set("ai-primary", "codegen", "web-search").contains(name)) {
                    DatrisEnvironment.reloadAiConfig()
                    logger.info("AI configuration reloaded from Vault after PUT /secrets/" + name)
                }

                // No .env write-back: Vault now persists on a disk-backed volume
                // (see docker/vault.hcl + vault-bootstrap.sh), so UI saves stick
                // across restarts directly. `.env` is first-boot seed only.

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
                     @PathVariable name: String,
                     request: HttpServletRequest): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /secrets/" + name + " called")
            APIKeyValidator.validate(apiKey)
            rejectIfTrialAiSecret(name).getOrElse {
                val env = DatrisEnvironment.current.environment
                val secretPath = env + "/" + name

                // Scope check before deletion — a key with
                // `secret:write:_type=tap` may only delete tap secrets.
                // Look up the existing secret's _type to feed the check.
                val existing = SecretsUtil.getSecretMap(secretPath).map(_.asScala).getOrElse(scala.collection.mutable.Map.empty[String, String])
                val existingType = existing.get("_type").getOrElse("")
                val scopeContext = if (existingType.nonEmpty) Map("_type" -> existingType)
                                   else Map.empty[String, String]
                CapabilityCheck.assertScope(request, "secret", "write", scopeContext)

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
        val normalized = fieldName.toLowerCase.replaceAll("[_-]", "")
        if (ALWAYS_PLAIN.contains(normalized)) false
        else SENSITIVE_MARKERS.exists(marker => normalized.contains(marker))
    }

    /** Copy a new UI-identity key value into the `ui` slot of oss/api-keys so
      * the auth layer recognizes it. Other labels in the map are preserved
      * (we read, update one slot, write back). Called when the operator saves
      * a new value in the ui-api-key secret; without this mirror, the new
      * value would be unknown to APIKeyValidator and the next UI request
      * would 401. */
    private def mirrorUiKeyIntoApiKeys(env: String, newValue: String): Unit = {
        val apiKeysPath = env + "/api-keys"
        val existing = SecretsUtil.getSecretMap(apiKeysPath).map(_.asScala).getOrElse(scala.collection.mutable.Map.empty[String, String])
        val updated = new java.util.LinkedHashMap[String, Object]()
        existing.foreach { case (k, v) => if (k != "ui") updated.put(k, v) }
        updated.put("ui", newValue)
        SecretsUtil.writeSecret(apiKeysPath, updated)
        logger.info("PUT /secrets/ui-api-key: mirrored value into " + apiKeysPath + " under label 'ui'")
    }
}
