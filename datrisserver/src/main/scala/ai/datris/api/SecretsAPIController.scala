package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonParser}
import ai.datris.audit.AuditLog
import ai.datris.auth.{CapabilityCheck, CapabilityDeniedException, ResolvedKeyAccess}
import ai.datris.config.RequiresRole
import ai.datris.model.DatrisEnvironment
import ai.datris.util.{APIKeyValidator, SecretsRetrieverUtil, SecretsUtil}
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import scala.collection.JavaConverters._

@RestController
@RequestMapping(Array("/api/v1"))
@RequiresRole(Array("admin"))
class SecretsAPIController {
    import SecretsAPIController.{isSensitive, mergeIncoming, FieldProtectionSecret}

    private val logger: Logger = LoggerFactory.getLogger(classOf[SecretsAPIController])

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
                    "Visit https://datris.ai/dashboard to upgrade to a dedicated instance.\"}"
            ))
        } else None
    }

    @GetMapping(path = Array("/secrets"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listSecrets(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(required = false, name = "type") secretType: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /secrets called" + (if (secretType != null) ", type=" + secretType else ""))
            APIKeyValidator.validate(apiKey)

            val env = DatrisEnvironment.current.environment
            val allSecrets = SecretsUtil.listSecrets(env)

            val secrets = if (secretType != null && secretType.nonEmpty) {
                // type=platform → all secrets NOT tagged _type=tap (mirrors the
                // UI's Platform tab). Any other value → exact-match on _type.
                if ("platform".equals(secretType)) {
                    SecretsRetrieverUtil.platformSecrets().map(_._1)
                } else {
                    allSecrets.filter(name => {
                        val secretMap = SecretsUtil.getSecretMap(env + "/" + name)
                        secretMap.exists(m => secretType.equals(m.get("_type")))
                    })
                }
            } else allSecrets

            // Read-scope filtering. Short-circuit when the key holds unscoped
            // `secret:read` (or is legacy full-access) — the common case, no
            // extra lookups. Only a scoped key (e.g. secret:read:_type=tap)
            // triggers per-secret _type resolution so it sees only what its
            // scope permits, rather than every secret name.
            val visible =
                if (CapabilityCheck.grants(request, "secret", "read", Map.empty[String, String])) secrets
                else secrets.filter { name =>
                    val t = SecretsUtil.getSecretMap(env + "/" + name).flatMap(m => Option(m.get("_type"))).getOrElse("")
                    val ctx = if (t.nonEmpty) Map("_type" -> t) else Map.empty[String, String]
                    CapabilityCheck.grants(request, "secret", "read", ctx)
                }

            val gson = new Gson
            new ResponseEntity[String](gson.toJson(visible.asJava), HttpStatus.OK)
        } catch {
            case e: CapabilityDeniedException =>
                // Same clean-JSON shape as a CapabilityInterceptor enforce-mode
                // deny — a stack-trace 500 reads to agents as a server fault,
                // not a permission boundary.
                logger.info("capability scope denial: " + e.getMessage)
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body[String]("{\"error\":\"capability denied\",\"errorKind\":\"capability_denied\",\"message\":\"" +
                        e.getMessage.replace("\"", "\\\"") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @GetMapping(path = Array("/secrets/{name}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getSecret(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /secrets/" + name + " called")
            APIKeyValidator.validate(apiKey)

            val env = DatrisEnvironment.current.environment
            val secretPath = env + "/" + name
            val secretMap = SecretsUtil.getSecretMap(secretPath)

            secretMap match {
                case Some(data) =>
                    // In-action read-scope check. The interceptor gate is
                    // scope-agnostic, so a key issued as `secret:read:_type=tap`
                    // could otherwise read ANY secret. Enforce the actual
                    // target's _type here — untyped/platform secrets fail a
                    // tap-scoped key (empty _type context can't satisfy _type=tap).
                    val existingType = data.asScala.get("_type").getOrElse("")
                    val scopeContext = if (existingType.nonEmpty) Map("_type" -> existingType) else Map.empty[String, String]
                    CapabilityCheck.assertScope(request, "secret", "read", scopeContext)

                    val result = new java.util.LinkedHashMap[String, Any]()
                    result.put("name", name)

                    result.put("fields", SecretsAPIController.maskedFields(name, data.asScala.toSeq))

                    val gson = new Gson
                    new ResponseEntity[String](gson.toJson(result), HttpStatus.OK)
                case None =>
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body[String]("{\"error\": \"Secret not found: " + name + "\"}")
            }
        } catch {
            case e: CapabilityDeniedException =>
                // Same clean-JSON shape as a CapabilityInterceptor enforce-mode
                // deny — a stack-trace 500 reads to agents as a server fault,
                // not a permission boundary.
                logger.info("capability scope denial: " + e.getMessage)
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body[String]("{\"error\":\"capability denied\",\"errorKind\":\"capability_denied\",\"message\":\"" +
                        e.getMessage.replace("\"", "\\\"") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @PutMapping(path = Array("/secrets/{name}"), consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def putSecret(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable name: String,
        @RequestBody body: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
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

                // In-action capability scope check for `secret:write:_type=tap`
                // keys. The interceptor's scope-agnostic gate already confirmed
                // the key holds `secret:write` for some scope; we now verify
                // the actual target satisfies the key's scope predicates.
                // For an EXISTING secret its stored _type is authoritative — a
                // scoped key cannot re-tag a platform secret by claiming
                // `_type: tap` in the payload. A BRAND-NEW secret is scoped by
                // the _type it declares (a tap-scoped key creating a secret
                // tagged tap is exactly the intended workflow; declaring no
                // _type leaves the context empty and the scoped key denied).
                // Server-side parallel to the Python `_type=tap` filter on the
                // MCP path — both retained for defense in depth.
                val existingType = existing.get("_type").getOrElse("")
                val incomingType =
                    if (json.has("_type") && json.get("_type").isJsonPrimitive) json.get("_type").getAsString else ""
                val effectiveType = if (existing.nonEmpty) existingType else incomingType
                val scopeContext = if (effectiveType.nonEmpty) Map("_type" -> effectiveType)
                else Map.empty[String, String]
                CapabilityCheck.assertScope(request, "secret", "write", scopeContext)

                // Merge the request body against the stored secret (mask / empty
                // preserves a stored sensitive value; ai-keys merges omitted keys
                // back). See the companion object's mergeIncoming.
                val primitiveEntries = json.entrySet().asScala.toSeq.collect {
                    case entry if entry.getValue.isJsonPrimitive => entry.getKey -> entry.getValue.getAsString
                }
                val incoming = mergeIncoming(name, existing.toMap, primitiveEntries)

                // Field protection 7: the key secret's hmac `key` cannot be changed
                // or removed here (409); enc.v<n> / encCurrent edits need
                // protect:admin (403), must be well formed (400) and are audited.
                // A round-trip of masks/blanks passes with plain secret:write.
                val guard: Either[ResponseEntity[String], Option[(String, Set[String])]] =
                    if (name == FieldProtectionSecret) fieldProtectionGuard(request, existing.toMap, incoming)
                    else Right(None)

                guard match {
                    case Left(refused) => refused
                    case Right(keyAudit) =>
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
                        //
                        // BUT only clear a *preserved* (masked/absent) key — never a fresh key
                        // the user typed for the NEW provider in this same request. The
                        // masked-preservation step above can't tell the two apart, so we look
                        // at the raw request: a real, non-masked apiKey means the user is
                        // switching provider AND supplying the new provider's key at once.
                        if (Set("ai-primary", "codegen", "embedding", "web-search").contains(name)) {
                            val incomingProvider = Option(incoming.get("provider")).map(_.asInstanceOf[String].toLowerCase).getOrElse("")
                            val existingProvider = existing.get("provider").map(_.toLowerCase).getOrElse("")
                            val rawRequestApiKey =
                                if (json.has("apiKey") && json.get("apiKey").isJsonPrimitive) json.get("apiKey").getAsString
                                else ""
                            val freshApiKeyProvided = rawRequestApiKey.nonEmpty && rawRequestApiKey != "••••••••"
                            if (
                                existingProvider.nonEmpty && incomingProvider.nonEmpty &&
                                existingProvider != incomingProvider && !freshApiKeyProvided
                            ) {
                                logger.info(
                                    "PUT /secrets/" + name + ": provider changed from '" + existingProvider + "' to '" + incomingProvider + "' — clearing preserved apiKey (will resolve from env var if available)"
                                )
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
                                    incoming.put("createdByKeyLabel", label)
                                )
                        }

                        SecretsUtil.writeSecret(secretPath, incoming)

                        // Field protection 7: an admin edit of the key material is audited
                        // with the field names only, never the values.
                        keyAudit.foreach { case (action, fields) =>
                            try AuditLog.record(request, "key", action, FieldProtectionSecret, fields.toSeq.sorted.mkString(","))
                            catch { case e: Exception => logger.warn("audit record failed: " + e.getMessage) }
                        }

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
                        if (Set("ai-primary", "codegen", "web-search", "ai-keys").contains(name)) {
                            DatrisEnvironment.reloadAiConfig()
                            logger.info("AI configuration reloaded from Vault after PUT /secrets/" + name)
                        }

                        // No .env write-back: Vault now persists on a disk-backed volume
                        // (see docker/vault.hcl + vault-bootstrap.sh), so UI saves stick
                        // across restarts directly. `.env` is first-boot seed only.

                        new ResponseEntity[String]("{\"status\": \"ok\"}", HttpStatus.OK)
                }
            }
        } catch {
            case e: CapabilityDeniedException =>
                // Same clean-JSON shape as a CapabilityInterceptor enforce-mode
                // deny — a stack-trace 500 reads to agents as a server fault,
                // not a permission boundary.
                logger.info("capability scope denial: " + e.getMessage)
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body[String]("{\"error\":\"capability denied\",\"errorKind\":\"capability_denied\",\"message\":\"" +
                        e.getMessage.replace("\"", "\\\"") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    @DeleteMapping(path = Array("/secrets/{name}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def deleteSecret(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @PathVariable name: String,
        request: HttpServletRequest
    ): ResponseEntity[String] = {
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

                // Deleting the field-protection secret would mint a new hmac key
                // on the next protected run and orphan every ciphertext: refused
                // for everyone, the same 409 as a `key` change.
                if (name == FieldProtectionSecret) {
                    logger.warn("DELETE /secrets/" + name + " refused: the field-protection key secret cannot be deleted through the API")
                    ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body[String]("{\"error\": " + new Gson().toJson(FieldProtectionSecretGuard.KeyChangeMessage) + "}")
                } else {
                    SecretsUtil.deleteSecret(secretPath)
                    new ResponseEntity[String]("{\"status\": \"ok\"}", HttpStatus.OK)
                }
            }
        } catch {
            case e: CapabilityDeniedException =>
                // Same clean-JSON shape as a CapabilityInterceptor enforce-mode
                // deny — a stack-trace 500 reads to agents as a server fault,
                // not a permission boundary.
                logger.info("capability scope denial: " + e.getMessage)
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body[String]("{\"error\":\"capability denied\",\"errorKind\":\"capability_denied\",\"message\":\"" +
                        e.getMessage.replace("\"", "\\\"") + "\"}")
            case e: Exception =>
                logger.error("Error: " + Throwables.getStackTraceAsString(e))
                ApiErrors.internal(e)
        }
    }

    /** Field protection 7 (plans/stories/field-protection-7-secret-write-guard.md):
      * map FieldProtectionSecretGuard's Decision for a PUT of the
      * field-protection secret to a refusal (Left) or to the key audit to
      * record after the write (Right). `incoming` is the merged map; key
      * fields still holding a mask or blank (encCurrent is not a sensitive
      * field, so mergeIncoming writes it verbatim) are restored from the
      * stored secret first, so "unchanged" really is unchanged on disk. */
    private def fieldProtectionGuard(
        request: HttpServletRequest,
        existing: Map[String, String],
        incoming: java.util.LinkedHashMap[String, Object]
    ): Either[ResponseEntity[String], Option[(String, Set[String])]] = {
        import FieldProtectionSecretGuard._
        incoming.asScala.toSeq.foreach { case (k, v) =>
            if (isKeyField(k) && isUnchanged(String.valueOf(v))) {
                existing.get(k).filter(_.nonEmpty) match {
                    case Some(stored) => incoming.put(k, stored)
                    case None => incoming.remove(k)
                }
            }
        }
        val merged = incoming.asScala.toMap.map { case (k, v) => k -> String.valueOf(v) }
        val d = diff(existing, merged)
        decide(d, merged, FieldProtectionAPIController.holdsCapability(request, "admin")) match {
            case Reject409(message) =>
                logger.warn("PUT /secrets/" + FieldProtectionSecret + " refused: hmac key change or removal")
                Left(ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body[String]("{\"error\": " + new Gson().toJson(message) + "}"))
            case Deny403 =>
                // The reveal controller's 403 shape and single security/denied entry.
                val e =
                    try {
                        FieldProtectionAPIController.requireCapability(request, "admin")
                        new CapabilityDeniedException("capability denied: protect:admin is required to change field-protection key material")
                    } catch { case denied: CapabilityDeniedException => denied }
                Left(FieldProtectionAPIController.denied(request, e, "admin"))
            case Invalid400(message) =>
                logger.warn("PUT /secrets/" + FieldProtectionSecret + " refused: " + message)
                Left(ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                    .body[String]("{\"error\": " + new Gson().toJson(message) + "}"))
            case Allow(Some(action), fields) => Right(Some(action -> fields))
            case Allow(None, _) => Right(None)
        }
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

object SecretsAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SecretsAPIController])

    private val MASK = "••••••••"

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
        "password",
        "passwd",
        "pwd",
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
        "createdbykeylabel" // matches "key" but stores a label, not a credential value
    )

    /** The field-protection key secret (FieldProtectionKey): `key` (hmac) and
      * every `enc.v<n>` are raw key material, and anyone holding an `enc.v<n>`
      * could decrypt an `encrypt` column offline without `protect:reveal` and
      * without a reveal audit entry. Every field is masked except the
      * `encCurrent` version number. */
    private[api] val FieldProtectionSecret = "field-protection"
    private val FieldProtectionPlain = Set("encCurrent")

    /** `isSensitive(fieldName)` plus per-secret rules: every field of the
      * field-protection secret except `encCurrent`. */
    private[api] def isSensitive(secretName: String, fieldName: String): Boolean =
        (secretName == FieldProtectionSecret && !FieldProtectionPlain.contains(fieldName)) || isSensitive(fieldName)

    /** The `fields` object GET /secrets/{name} returns: sensitive values
      * become the mask, everything else passes through redactJdbcUrl. */
    private[api] def maskedFields(name: String, data: Seq[(String, String)]): java.util.LinkedHashMap[String, String] = {
        val fields = new java.util.LinkedHashMap[String, String]()
        data.foreach { case (key, value) =>
            if (isSensitive(name, key) && value != null && value.nonEmpty) {
                fields.put(key, MASK)
            } else {
                // Value-level redaction for credential-bearing URLs/DSNs
                // whose field NAME doesn't trip a sensitive marker
                // (connectionString, jdbcUrl, uri, ...). redactJdbcUrl
                // strips embedded user:pass@ / password= and leaves
                // plain values untouched.
                fields.put(key, ai.datris.util.LogRedactUtil.redactJdbcUrl(value))
            }
        }
        fields
    }

    private[api] def isSensitive(fieldName: String): Boolean = {
        val normalized = fieldName.toLowerCase.replaceAll("[_-]", "")
        if (ALWAYS_PLAIN.contains(normalized)) false
        else SENSITIVE_MARKERS.exists(marker => normalized.contains(marker))
    }

    /** Merge a PUT body against the secret currently stored at the path.
      *
      * `existing` is the stored secret (empty when none); `incoming` is the
      * request body's primitive entries, in order. Returns the map handed to
      * SecretsUtil.writeSecret BEFORE the codegen apiKey fallback,
      * provider-change clearing and owner-tagging steps in putSecret.
      *
      * For a sensitive key, an incoming value that is the mask OR empty/blank
      * preserves a stored non-empty value — the Secrets tab (and API callers)
      * send fields they did not touch back as the mask, and an empty box must
      * never wipe a credential. A mask with nothing stored writes nothing for
      * that key. Non-sensitive keys are written verbatim, including "".
      * Omission means removal for every secret except `ai-keys`.
      *
      * `ai-keys` carve-out: every field in the shared per-provider key store is
      * a credential, so the mask preserves the stored value for ANY field (no
      * marker check). An empty/blank value is written verbatim there — it is the
      * explicit clear, and the only removal path for a merge-only secret (the
      * Configuration tab's Azure auth-mode switch depends on it).
      */
    private[api] def mergeIncoming(
        name: String,
        existing: Map[String, String],
        incoming: Seq[(String, String)]
    ): java.util.LinkedHashMap[String, Object] = {
        val result = new java.util.LinkedHashMap[String, Object]()
        val isAiKeys = name == "ai-keys"
        incoming.foreach { case (key, strValue) =>
            val preserves =
                if (isAiKeys) strValue == MASK
                else isSensitive(name, key) && (strValue == MASK || strValue.trim.isEmpty)
            if (preserves) {
                existing.get(key).filter(_.nonEmpty) match {
                    case Some(stored) =>
                        result.put(key, stored)
                        logger.info("PUT /secrets/" + name + ": preserved stored value for field '" + key + "'")
                    case None =>
                        // Nothing to preserve: the mask writes nothing; an empty
                        // string is written as sent.
                        if (strValue != MASK) result.put(key, strValue)
                }
            } else {
                result.put(key, strValue)
            }
        }

        // The shared per-provider key store is MERGE-only: each field is an
        // independent provider's key, so a partial update (e.g. an API or
        // MCP caller adding one provider's key) must never drop the other
        // providers' keys. Every other secret keeps replace semantics —
        // their fields form one coherent config where omission means removal.
        if (name == "ai-keys") {
            existing.foreach { case (k, v) =>
                if (!result.containsKey(k) && v.nonEmpty) result.put(k, v)
            }
        }
        result
    }
}
