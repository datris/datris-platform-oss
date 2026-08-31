package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.{APIKeyValidator, SecretsUtil}
import com.google.gson.{Gson, JsonArray, JsonObject}
import org.slf4j.LoggerFactory

import java.security.SecureRandom
import scala.collection.JavaConverters._

/** The recovery agent's own identity: an ordinary labeled API key issued by
  * the platform at startup when the feature is on. Every action the runner
  * takes is attributable to this label, scoped by its capability bundle,
  * policy-gated like any other agent, and revocable — revoke the key and
  * the runner is dead, independent of any flag.
  *
  * Deliberately minimal bundle (open decision #2 in the plan, resolved to
  * the recommendation): reads, tap run/update, job read, approvals it
  * queued. No deletes, no secrets, no pipeline definition changes — schema
  * changes are always proposed to a human via the approval queue. */
object RecoveryKey {

    val Label = "recovery-agent"

    val Capabilities: Seq[String] = Seq(
        "tap:read",
        "tap:run",
        "tap:update",
        // The MCP `update_tap` tool writes through the upsert route
        // (POST /api/v1/tap), which the capability table classifies as
        // tap:create — without it, script repairs are refused at the gate.
        "tap:create",
        "pipeline:read",
        "job:read",
        "metadata:read",
        "query:postgres",
        "query:mongodb",
        "query:snowflake",
        "query:databricks",
        "query:objectstore",
        "policy:read",
        "approval:read:owner=self"
    )

    private val logger = LoggerFactory.getLogger(getClass)
    private val random = new SecureRandom()

    private def apiKeysSecretName: String = DatrisEnvironment.current.environment + "/api-keys"
    private def metadataSecretName: String = DatrisEnvironment.current.environment + "/api-key-metadata"

    /** Issue the key if it does not exist. No-op when API keys are off (the
      * runner then calls anonymously, like every other client). Never
      * rotates or repairs an existing key — an operator who revoked it has
      * turned the runner off on purpose. */
    def ensure(): Unit = {
        val env = DatrisEnvironment.values
        if (env == null || !env.useApiKeys) return
        try {
            val existing = SecretsUtil.getSecretMap(apiKeysSecretName).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
            if (existing.contains(Label)) return

            val value = randomHex(32)
            val keyId = "k_" + randomHex(6)
            val sdf = new java.text.SimpleDateFormat(DatrisEnvironment.current.dateFormat)
            sdf.setTimeZone(java.util.TimeZone.getTimeZone(DatrisEnvironment.current.dateTimezone))
            val now = sdf.format(new java.util.Date())

            val keys = new java.util.LinkedHashMap[String, Object]()
            existing.foreach { case (k, v) => keys.put(k, v) }
            keys.put(Label, value)
            SecretsUtil.writeSecret(apiKeysSecretName, keys)

            val meta = new JsonObject()
            val caps = new JsonArray()
            Capabilities.foreach(caps.add)
            meta.add("capabilities", caps)
            meta.addProperty("createdAt", now)
            meta.addProperty("createdBy", "system:recovery-agent")
            meta.addProperty("revoked", false)
            meta.addProperty("keyId", keyId)
            val existingMeta = SecretsUtil.getSecretMap(metadataSecretName).map(_.asScala.toMap).getOrElse(Map.empty[String, String])
            val metaMap = new java.util.LinkedHashMap[String, Object]()
            existingMeta.foreach { case (k, v) => metaMap.put(k, v) }
            metaMap.put(Label, new Gson().toJson(meta))
            SecretsUtil.writeSecret(metadataSecretName, metaMap)

            APIKeyValidator.invalidateCache()
            ai.datris.audit.AuditLog.system("key", "issue", "key", Label)
            logger.info("Issued the recovery-agent API key (keyId=" + keyId + ")")
        } catch {
            case e: Exception => logger.error("Could not ensure the recovery-agent key: " + e.getMessage)
        }
    }

    /** The key's secret value, for the runner's MCP calls. None when keys are
      * off (call anonymously) or the key is missing/revoked (runner refuses
      * to act and says why). */
    def value(): Option[String] = {
        val env = DatrisEnvironment.values
        if (env == null || !env.useApiKeys) return None
        SecretsUtil.getSecretMap(apiKeysSecretName).flatMap(m => Option(m.get(Label))).filter(_.nonEmpty)
    }

    def isRevoked: Boolean =
        SecretsUtil.getSecretMap(metadataSecretName).flatMap(m => Option(m.get(Label))).exists { json =>
            try {
                val o = com.google.gson.JsonParser.parseString(json).getAsJsonObject
                o.has("revoked") && !o.get("revoked").isJsonNull && o.get("revoked").getAsBoolean
            } catch { case _: Exception => false }
        }

    private def randomHex(bytes: Int): String = {
        val b = new Array[Byte](bytes)
        random.nextBytes(b)
        b.map(x => f"${x & 0xff}%02x").mkString
    }
}
