package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.{Gson, JsonArray, JsonObject, JsonParser}
import ai.datris.config.RequiresRole
import ai.datris.model.{Capability, DatrisEnvironment, DatrisException, UserContext}
import ai.datris.util.{APIKeyValidator, SecretsUtil}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.security.SecureRandom
import scala.collection.JavaConverters._

/** Admin-only endpoints for managing API keys: list, issue, revoke, rotate.
  * Keys are the unit of identity for programmatic callers (UI, CLI, MCP
  * agents). Each key carries:
  *
  *  - a label — operator-chosen name (`ui`, `claude-desktop`, `support-rag`)
  *  - a value — the random secret the caller sends as `x-api-key`
  *  - a capability list — what operations the key can perform
  *
  * Storage is split across two Vault secrets:
  *  - `{env}/api-keys` — `{label: value}` map, the authoritative source for
  *    `APIKeyValidator.resolveKey()`. Values stay here; this endpoint never
  *    returns them in list responses (only on issue/rotate, one-shot).
  *  - `{env}/api-key-metadata` — `{label: jsonBlob}` map carrying the
  *    capability list, audit fields (createdAt/By, revokedAt/By), and the
  *    revoked flag. A label without an entry here gets legacy full access
  *    (`*:*`) — that's the backward-compat backstop for keys seeded before
  *    this management surface existed.
  *
  * Gated by `@RequiresRole(Array("admin"))` so only logged-in admins reach
  * these endpoints. Programmatic callers do not manage their own keys. */
@RestController
@RequestMapping(Array("/api/v1/keys"))
@RequiresRole(Array("admin"))
class KeysAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[KeysAPIController])

    // `def`, not `val` — DatrisEnvironment.current isn't initialized yet when
    // Spring constructs this bean; eager evaluation here NPEs at startup.
    // Resolving per-request is also the right shape for multi-tenant, where
    // the environment can vary per request via TenantContext.
    private def apiKeysSecretName: String = DatrisEnvironment.current.environment + "/api-keys"
    private def metadataSecretName: String = DatrisEnvironment.current.environment + "/api-key-metadata"

    private val secureRandom = new SecureRandom()

    // ------------------------------------------------------------------
    // GET /api/v1/keys — list all keys (without values)
    // ------------------------------------------------------------------

    @GetMapping(produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listKeys(): ResponseEntity[String] = {
        try {
            logger.info("API endpoint GET /keys called")

            val apiKeys = SecretsUtil.getSecretMap(apiKeysSecretName).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
            val metadata = SecretsUtil.getSecretMap(metadataSecretName).map(_.asScala.toMap).getOrElse(Map.empty[String, String])

            val rows = new JsonArray()
            apiKeys.keys.toSeq.sorted.foreach { label =>
                rows.add(buildListRow(label, metadata.get(label)))
            }

            val response = new JsonObject()
            response.add("keys", rows)
            new ResponseEntity[String](new Gson().toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error listing keys: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    // ------------------------------------------------------------------
    // POST /api/v1/keys — issue a new key
    // Body: {label: string, capabilities: string[]}
    // Returns: {label, value, capabilities, createdAt, createdBy}
    //          value is shown ONCE and disappears.
    // ------------------------------------------------------------------

    @PostMapping(consumes = Array(MediaType.APPLICATION_JSON_VALUE), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def issueKey(@RequestBody body: String): ResponseEntity[String] = {
        try {
            val req = JsonParser.parseString(body).getAsJsonObject
            val label = readString(req, "label")
            val capStrings = readStringArray(req, "capabilities")

            if (label == null || label.isEmpty)
                return badRequest("label is required")
            if (!isValidLabel(label))
                return badRequest("label must be 1-64 chars of [a-z0-9_-] (lowercase, no whitespace)")

            // Parse + validate capabilities up front; reject before any write.
            val capabilities: Seq[Capability] =
                try Capability.parseList(capStrings)
                catch { case e: DatrisException => return badRequest(e.getMessage) }

            logger.info("API endpoint POST /keys called for label=" + label + ", capabilities=" + capStrings.size)

            // Ensure label is unique.
            val existingKeys = SecretsUtil.getSecretMap(apiKeysSecretName).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
            if (existingKeys.contains(label))
                return ResponseEntity.status(HttpStatus.CONFLICT).body[String](errorJson("key with label '" + label + "' already exists"))

            val value = generateKeyValue()
            val now = nowTimestamp()
            val createdBy = currentAdminUsername()
            // Stable per-issue id. Survives rotate (same label, new secret);
            // a revoke + re-issue under the same label mints a new one, so
            // the audit log can tell the two keys apart.
            val keyId = generateKeyId()

            // 1) Add to oss/api-keys (preserve other labels).
            writeApiKeysWithLabel(existingKeys, label, value)

            // 2) Write metadata blob.
            val metaJson = buildMetadataJson(capabilities.map(_.raw), now, createdBy, revoked = false, None, None, Some(keyId))
            writeMetadataWithLabel(label, metaJson)

            APIKeyValidator.invalidateCache()

            val response = new JsonObject()
            response.addProperty("label", label)
            response.addProperty("value", value)
            response.addProperty("keyId", keyId)
            response.add("capabilities", stringArray(capabilities.map(_.raw)))
            response.addProperty("createdAt", now)
            response.addProperty("createdBy", createdBy)

            new ResponseEntity[String](new Gson().toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error issuing key: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    // ------------------------------------------------------------------
    // DELETE /api/v1/keys/{label} — revoke
    // Marks the key revoked; value stays in oss/api-keys so failed auth
    // attempts can be logged as "revoked key" instead of "unknown key".
    // ------------------------------------------------------------------

    @DeleteMapping(path = Array("/{label}"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def revokeKey(@PathVariable label: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint DELETE /keys/" + label + " called")

            val existingKeys = SecretsUtil.getSecretMap(apiKeysSecretName).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
            if (!existingKeys.contains(label))
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("key with label '" + label + "' not found"))

            val now = nowTimestamp()
            val revokedBy = currentAdminUsername()
            val existingMeta = readMetadata(label)
            val newMeta = buildMetadataJson(
                capabilities = existingMeta.flatMap(parseCapabilities).getOrElse(Seq.empty),
                createdAt = existingMeta.flatMap(m => Option(m.get("createdAt")).filterNot(_.isJsonNull).map(_.getAsString)).getOrElse(now),
                createdBy = existingMeta.flatMap(m => Option(m.get("createdBy")).filterNot(_.isJsonNull).map(_.getAsString)).getOrElse(""),
                revoked = true,
                revokedAt = Some(now),
                revokedBy = Some(revokedBy),
                keyId = existingMeta.flatMap(m => Option(m.get("keyId")).filterNot(_.isJsonNull).map(_.getAsString))
            )
            writeMetadataWithLabel(label, newMeta)
            APIKeyValidator.invalidateCache()

            new ResponseEntity[String]("{\"status\":\"revoked\",\"label\":\"" + escape(label) + "\"}", HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error revoking key: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    // ------------------------------------------------------------------
    // POST /api/v1/keys/{label}/rotate — generate a new value
    // Capabilities preserved; the previous value is no longer valid after
    // this returns. Caller must update wherever they had the old value.
    // ------------------------------------------------------------------

    @PostMapping(path = Array("/{label}/rotate"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def rotateKey(@PathVariable label: String): ResponseEntity[String] = {
        try {
            logger.info("API endpoint POST /keys/" + label + "/rotate called")

            val existingKeys = SecretsUtil.getSecretMap(apiKeysSecretName).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
            if (!existingKeys.contains(label))
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body[String](errorJson("key with label '" + label + "' not found"))

            val newValue = generateKeyValue()
            writeApiKeysWithLabel(existingKeys, label, newValue)
            mirrorIntoWellKnownSecret(label, newValue)
            APIKeyValidator.invalidateCache()

            val response = new JsonObject()
            response.addProperty("label", label)
            response.addProperty("value", newValue)
            response.addProperty("rotatedAt", nowTimestamp())
            new ResponseEntity[String](new Gson().toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error rotating key: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    // ------------------------------------------------------------------
    // GET /api/v1/keys/capabilities/catalog
    // The data the Keys-UI capability editor uses for autocomplete:
    // every resource, every action per resource, the allowed scope keys.
    // ------------------------------------------------------------------

    @GetMapping(path = Array("/capabilities/catalog"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def capabilitiesCatalog(): ResponseEntity[String] = {
        try {
            val resources = new JsonArray()
            KeysAPIController.CapabilitiesCatalog.foreach { case (resource, actions, scopeKeys) =>
                val r = new JsonObject()
                r.addProperty("resource", resource)
                r.add("actions", stringArray(actions))
                r.add("scopeKeys", stringArray(scopeKeys))
                resources.add(r)
            }
            val response = new JsonObject()
            response.add("resources", resources)
            new ResponseEntity[String](new Gson().toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error returning capabilities catalog: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    // ------------------------------------------------------------------
    // GET /api/v1/keys/templates
    // Common starting points the Keys-UI wizard offers as templates.
    // Operators pick a template and tweak from there.
    // ------------------------------------------------------------------

    @GetMapping(path = Array("/templates"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def listTemplates(): ResponseEntity[String] = {
        try {
            val templates = new JsonArray()
            KeysAPIController.Templates.foreach { case (name, description, caps) =>
                val t = new JsonObject()
                t.addProperty("name", name)
                t.addProperty("description", description)
                t.add("capabilities", stringArray(caps))
                templates.add(t)
            }
            val response = new JsonObject()
            response.add("templates", templates)
            new ResponseEntity[String](new Gson().toJson(response), HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.error("Error returning templates: " + Throwables.getStackTraceAsString(e))
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body[String](errorJson(e.getMessage))
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private def buildListRow(label: String, metaJsonOpt: Option[String]): JsonObject = {
        val row = new JsonObject()
        row.addProperty("label", label)
        metaJsonOpt match {
            case Some(json) =>
                try {
                    val obj = JsonParser.parseString(json).getAsJsonObject
                    row.add("capabilities", if (obj.has("capabilities")) obj.getAsJsonArray("capabilities") else new JsonArray())
                    row.addProperty("revoked", obj.has("revoked") && !obj.get("revoked").isJsonNull && obj.get("revoked").getAsBoolean)
                    if (obj.has("createdAt") && !obj.get("createdAt").isJsonNull) row.addProperty("createdAt", obj.get("createdAt").getAsString)
                    if (obj.has("createdBy") && !obj.get("createdBy").isJsonNull) row.addProperty("createdBy", obj.get("createdBy").getAsString)
                    if (obj.has("revokedAt") && !obj.get("revokedAt").isJsonNull) row.addProperty("revokedAt", obj.get("revokedAt").getAsString)
                    if (obj.has("revokedBy") && !obj.get("revokedBy").isJsonNull) row.addProperty("revokedBy", obj.get("revokedBy").getAsString)
                    if (obj.has("keyId") && !obj.get("keyId").isJsonNull) row.addProperty("keyId", obj.get("keyId").getAsString)
                    row.addProperty("isLegacyFullAccess", false)
                } catch {
                    case e: Exception =>
                        logger.warn("Malformed API key metadata for label '" + label + "'; listing as legacy full-access key", e)
                        row.add("capabilities", new JsonArray())
                        row.addProperty("isLegacyFullAccess", true)
                        row.addProperty("revoked", false)
                }
            case None =>
                // No metadata entry — legacy key, full access via the backstop.
                row.add("capabilities", new JsonArray())
                row.addProperty("isLegacyFullAccess", true)
                row.addProperty("revoked", false)
        }
        row
    }

    private def readMetadata(label: String): Option[JsonObject] = {
        val map = SecretsUtil.getSecretMap(metadataSecretName).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
        map.get(label).flatMap { json =>
            try Some(JsonParser.parseString(json).getAsJsonObject)
            catch {
                case e: Exception =>
                    logger.warn("Malformed API key metadata JSON for label '" + label + "'", e)
                    None
            }
        }
    }

    private def parseCapabilities(meta: JsonObject): Option[Seq[String]] = {
        if (!meta.has("capabilities")) return None
        val arr = meta.getAsJsonArray("capabilities")
        val builder = Seq.newBuilder[String]
        val it = arr.iterator()
        while (it.hasNext) builder += it.next().getAsString
        Some(builder.result())
    }

    private def buildMetadataJson(
        capabilities: Seq[String],
        createdAt: String,
        createdBy: String,
        revoked: Boolean,
        revokedAt: Option[String],
        revokedBy: Option[String],
        keyId: Option[String]
    ): String = {
        val obj = new JsonObject()
        obj.add("capabilities", stringArray(capabilities))
        obj.addProperty("createdAt", createdAt)
        obj.addProperty("createdBy", createdBy)
        obj.addProperty("revoked", java.lang.Boolean.valueOf(revoked))
        revokedAt.foreach(v => obj.addProperty("revokedAt", v))
        revokedBy.foreach(v => obj.addProperty("revokedBy", v))
        keyId.foreach(v => obj.addProperty("keyId", v))
        new Gson().toJson(obj)
    }

    /** Write a single (label, value) pair into oss/api-keys, preserving every
      * other label already there. Vault writes are full-map replacements, so
      * we have to read-modify-write. */
    private def writeApiKeysWithLabel(existing: Map[String, String], label: String, value: String): Unit = {
        val updated = new java.util.LinkedHashMap[String, Object]()
        existing.foreach { case (k, v) => if (k != label) updated.put(k, v) }
        updated.put(label, value)
        SecretsUtil.writeSecret(apiKeysSecretName, updated)
    }

    /** Same pattern for the metadata secret. */
    private def writeMetadataWithLabel(label: String, jsonString: String): Unit = {
        val existing = SecretsUtil.getSecretMap(metadataSecretName).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
        val updated = new java.util.LinkedHashMap[String, Object]()
        existing.foreach { case (k, v) => if (k != label) updated.put(k, v) }
        updated.put(label, jsonString)
        SecretsUtil.writeSecret(metadataSecretName, updated)
    }

    /** Some labels have a parallel "well-known" secret used by other parts of
      * the platform — e.g. `ui` is mirrored to `oss/ui-api-key` so the UI's
      * `/api/v1/auth/ui-key` endpoint can hand the value to the browser.
      * Without this mirror, a rotate from the Keys tab updates the validation
      * source but leaves the well-known record stale, and the next browser
      * request 401s.
      *
      * This is the rotate-side counterpart to the auto-mirror in
      * `SecretsAPIController.putSecret` which fires when the operator edits
      * the well-known secret directly. Both paths now keep the two records
      * in sync. */
    private def mirrorIntoWellKnownSecret(label: String, newValue: String): Unit = {
        val wellKnownPath: Option[String] = label match {
            case "ui" => Some(DatrisEnvironment.current.environment + "/ui-api-key")
            // Add future well-known labels here: cli, external-mcp, etc.
            case _ => None
        }
        wellKnownPath.foreach { path =>
            val existing = SecretsUtil.getSecretMap(path).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
            val updated = new java.util.LinkedHashMap[String, Object]()
            existing.foreach { case (k, v) => if (k != "apiKey") updated.put(k, v) }
            updated.put("apiKey", newValue)
            SecretsUtil.writeSecret(path, updated)
            logger.info("Rotate '" + label + "': mirrored new value into " + path)
        }
    }

    /** 32 random bytes hex-encoded — 64 character lowercase string. Strong
      * enough that brute-force isn't a concern at any realistic rate limit. */
    private def generateKeyValue(): String = {
        val bytes = new Array[Byte](32)
        secureRandom.nextBytes(bytes)
        bytes.map(b => f"${b & 0xff}%02x").mkString
    }

    /** `k_` + 12 hex chars — enough to be unique per install, short enough to
      * read in an audit-log detail panel. Not a secret. */
    private def generateKeyId(): String = {
        val bytes = new Array[Byte](6)
        secureRandom.nextBytes(bytes)
        "k_" + bytes.map(b => f"${b & 0xff}%02x").mkString
    }

    private def nowTimestamp(): String = {
        val sdf = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
        sdf.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
        sdf.format(new java.util.Date())
    }

    private def currentAdminUsername(): String = {
        UserContext.get().map(_.username).getOrElse("system")
    }

    private def readString(obj: JsonObject, key: String): String =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).getAsString else null

    private def readStringArray(obj: JsonObject, key: String): Seq[String] = {
        if (!obj.has(key) || !obj.get(key).isJsonArray) return Seq.empty
        val arr = obj.getAsJsonArray(key)
        val builder = Seq.newBuilder[String]
        val it = arr.iterator()
        while (it.hasNext) builder += it.next().getAsString
        builder.result()
    }

    private def stringArray(xs: Seq[String]): JsonArray = {
        val arr = new JsonArray()
        xs.foreach(arr.add)
        arr
    }

    /** Label rules: lowercase alphanumerics, dash, underscore, 1-64 chars.
      * Matches the convention of `ui`, `claude-desktop`, `support-rag-builder`. */
    private def isValidLabel(s: String): Boolean = s != null && s.matches("[a-z0-9_-]{1,64}")

    private def badRequest(msg: String): ResponseEntity[String] =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body[String](errorJson(msg))

    private def errorJson(msg: String): String =
        "{\"error\":\"" + escape(msg) + "\"}"

    private def escape(s: String): String =
        if (s == null) "" else s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
}

object KeysAPIController {

    /** Capability autocomplete catalog the Keys UI uses. Each entry is
      * (resource, [actions], [scope keys]). The scope-key list is the
      * intersection of what makes sense for that resource and what
      * `Capability.parse` allows. Resources whose scope list is empty
      * accept no scope qualifiers. */
    private val CapabilitiesCatalog: Seq[(String, Seq[String], Seq[String])] = Seq(
        ("pipeline", Seq("read", "create", "update", "delete", "run"), Seq("catalog", "owner")),
        ("tap", Seq("read", "create", "update", "delete", "run"), Seq("catalog", "owner")),
        ("secret", Seq("read", "write"), Seq("_type", "owner")),
        ("document", Seq("upload"), Seq("collection", "destination_kind")),
        ("search", Seq("vector"), Seq("collection")),
        ("query", Seq("postgres", "mongodb", "objectstore", "snowflake", "databricks", "natural"), Seq("database")),
        ("job", Seq("read", "kill"), Seq("owner")),
        ("metadata", Seq("read"), Seq.empty),
        ("config", Seq("read", "write"), Seq.empty),
        ("mcp", Seq("tool"), Seq.empty),
        ("audit", Seq("read"), Seq.empty)
    )

    /** Capability templates the Keys-UI wizard offers as starting points.
      * Each is (name, description, capability list). Operators pick a
      * template and edit from there. */
    private val Templates: Seq[(String, String, Seq[String])] = Seq(
        (
            "read-only",
            "Pure observer: every read-only action across pipelines, taps, jobs, metadata, configuration, plus data queries and vector search. Cannot create, edit, delete, run, or kill anything.",
            Seq(
                "pipeline:read",
                "tap:read",
                "job:read",
                "metadata:read",
                "config:read",
                "query:postgres",
                "query:mongodb",
                "search:vector"
            )
        ),
        (
            "rag-builder",
            "Build and search a vector knowledge base. Creates pipelines and taps in one catalog, uploads documents, and runs vector search.",
            Seq(
                "pipeline:create",
                "pipeline:read",
                "pipeline:run:owner=self",
                "tap:create",
                "tap:read",
                "tap:run:owner=self",
                "document:upload",
                "search:vector",
                // Read stays scoped to tap-typed secrets: the canonical
                // workflow (list secrets → create tap secret → create tap)
                // does GET pre-reads, and SecretsAPIController filters reads
                // per-secret by scope, so platform/AI keys stay invisible.
                "secret:read:_type=tap",
                "secret:write:_type=tap",
                "job:read"
            )
        ),
        (
            "reporting",
            "Read-only access for analyst agents: query data, search vectors, read schema and run status. No writes.",
            Seq(
                "pipeline:read",
                "tap:read",
                "query:postgres",
                "query:mongodb",
                "search:vector",
                "metadata:read",
                "job:read"
            )
        ),
        (
            "ops",
            "Operations agent: run any tap or pipeline, monitor jobs, kill stuck runs. Does not edit configurations.",
            Seq(
                "pipeline:read",
                "pipeline:run",
                "tap:read",
                "tap:run",
                "job:read",
                "job:kill"
            )
        ),
        (
            "full-access",
            "Legacy / power-user shape: every action on every resource. Equivalent to a pre-scoped master key. Avoid for agents.",
            Seq("*:*")
        )
    )
}
