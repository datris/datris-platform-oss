package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.auth.TapRunTokens
import ai.datris.model.{Capability, DatrisEnvironment, DatrisException, ResolvedKey, User, UserContext}
import com.google.common.cache.CacheBuilder
import com.google.gson.JsonParser

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/** A presented key value that matches a known key whose metadata is flagged
  * revoked. Carries the label (never the value) so the audit log can name
  * which revoked key was used. */
class RevokedKeyException(val label: String) extends DatrisException(s"API key '$label' is revoked")

object APIKeyValidator {

    /** Companion secret to `oss/api-keys` (single-tenant). Holds per-key
      * metadata: capabilities, label timestamps, revoked flag. Values are
      * JSON strings; the secret as a whole is `{label -> jsonBlob}`. If
      * absent or a label has no entry, the key falls back to legacy full
      * access for backward compatibility. */
    private val apiKeyMetadataSecretName = "oss/api-key-metadata"

    /** Short-lived cache so capability resolution doesn't hit the secret
      * store on every request. 60s TTL strikes a balance between revocation
      * latency and read pressure; the cache is invalidated explicitly when
      * the Keys UI modifies a key. */
    private val resolvedKeyCache = CacheBuilder.newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .maximumSize(1000)
        .build[String, ResolvedKey]()

    def validate(apiKey: String): Unit = {
        if (DatrisEnvironment.values.multiTenant) {
            // In multi-tenant mode, validation is handled by TenantInterceptor
            return
        }
        if (DatrisEnvironment.values.useApiKeys) {
            // Session-cookie auth bypass: if SessionAuthenticator already
            // established UserContext on this request, the caller is an
            // authenticated browser user — no x-api-key required. API keys
            // remain mandatory for programmatic clients (CLI, MCP, external
            // agents) that don't carry a session cookie.
            if (UserContext.get().isDefined) return

            // A running tap calling back into the platform presents the
            // per-run token TapScriptRunner minted for it (see TapRunTokens).
            if (TapRunTokens.lookup(apiKey).isDefined) return

            if (apiKey == null)
                throw new DatrisException("x-api-key does not exist or is invalid")

            validateAgainst(apiKey, readKeysMap(), readMetadataMap())
        }
    }

    /** Pure core of [[validate]] for a presented key value: the value must be
      * a known key AND its metadata (if any) must not be revoked. Revocation
      * only flags metadata — the value stays in `oss/api-keys` for listing and
      * audit — so checking membership alone would let a revoked key through.
      * Keys with no metadata entry are legacy full-access and stay valid.
      * `metadata` is by-name: it is only read once the value is known. */
    private[util] def validateAgainst(apiKey: String, keys: Map[String, String], metadata: => Map[String, String]): Unit = {
        val label = labelForValue(keys, apiKey)
            .getOrElse(throw new DatrisException("Invalid x-api-key"))
        metadata.get(label).foreach { json =>
            val (revoked, _, _) = parseMetadata(label, json)
            if (revoked) throw new RevokedKeyException(label)
        }
    }

    private def labelForValue(keys: Map[String, String], apiKey: String): Option[String] =
        keys.find { case (_, v) => v == apiKey }.map(_._1)

    /** Error message when `oss/api-key-metadata` cannot be read. The
      * interceptor maps a rejection with this reason to 503. */
    val MetadataUnavailableMessage: String = "API key metadata unavailable"

    /** Error message when the key store (`oss/api-keys`) itself cannot be
      * read — a secret-store outage, not a bad key. Also mapped to 503. */
    val KeyStoreUnavailableMessage: String = "API key store unavailable"

    /** True when the rejection reason is a secret-store outage rather than a
      * bad key, i.e. the caller should see 503 instead of 401. */
    def isStoreOutage(reason: String): Boolean =
        reason == MetadataUnavailableMessage || reason == KeyStoreUnavailableMessage

    /** `oss/api-keys` read that fails closed: absent → "not found" (no key
      * can be valid), read failure → store outage. */
    private def readKeysMap(): Map[String, String] =
        SecretsUtil.tryGetSecretMap(DatrisEnvironment.values.apiKeysSecretName) match {
            case Success(Some(m)) => m.asScala.toMap
            case Success(None) =>
                throw new DatrisException(
                    "The Secrets Manager entry for value: " + DatrisEnvironment.values.apiKeysSecretName + " was not found"
                )
            case Failure(_) => throw new DatrisException(KeyStoreUnavailableMessage)
        }

    private def readMetadataMap(): Map[String, String] =
        metadataFrom(SecretsUtil.tryGetSecretMap(apiKeyMetadataSecretName))

    /** An ABSENT metadata secret (fresh install, legacy keys only) means no
      * key has metadata → legacy full access, as before. A read FAILURE must
      * fail closed: treating it as absent would turn every revoked or scoped
      * key into a full-access legacy key. */
    private[util] def metadataFrom(result: Try[Option[java.util.Map[String, String]]]): Map[String, String] =
        result match {
            case Success(Some(m)) => m.asScala.toMap
            case Success(None) => Map.empty[String, String]
            case Failure(_) => throw new DatrisException(MetadataUnavailableMessage)
        }

    /** Validates the API key and resolves the tenant environment name.
      * Returns Some(environmentName) when multiTenant is true, None otherwise. */
    def validateAndResolve(apiKey: String): Option[String] = {
        if (DatrisEnvironment.values.multiTenant) {
            if (apiKey == null || apiKey.isEmpty)
                return None // No API key — fall back to global environment

            // Tap-run token: route to the tenant the run was started for.
            TapRunTokens.lookup(apiKey).foreach(t => return t.tenantEnvironment)

            val mappings = ai.datris.util.SecretsUtil.getSecretMap("api-key-mappings")
                .getOrElse(return None) // Mappings not found — fall back to global
            val environment = mappings.asScala.get(apiKey)

            environment // Some(env) if found, None if not (falls back to global)
        } else {
            validate(apiKey)
            None
        }
    }

    /** Resolves an x-api-key into a `ResolvedKey` carrying its label,
      * tenant routing, and capability bundle. Looks up the api-key-metadata
      * secret; if a key has no metadata entry, it gets full-access legacy
      * capabilities so existing deployments keep working unchanged.
      *
      * When `useApiKeys=false` (the OSS default), the key value is irrelevant
      * — every request is anonymous full-access. The caller can pass null/
      * empty in that mode and still get a valid ResolvedKey back, so the
      * capability framework can run uniformly without special-casing the
      * anonymous path at the interceptor level.
      *
      * Throws DatrisException only when a key IS required (multi-tenant,
      * or single-tenant + useApiKeys=true) and the value is missing or
      * unknown. */
    def resolveKey(apiKey: String): ResolvedKey = {
        // Per-run tap token → read-only `tap:<name>` identity. Checked before
        // the cache: tokens are minted and revoked per run, so a 60s cache
        // entry could outlive the run.
        TapRunTokens.resolve(apiKey).foreach(rk => return rk)

        val cacheKey = if (apiKey == null) "" else apiKey
        val cached = resolvedKeyCache.getIfPresent(cacheKey)
        if (cached != null) return cached

        val resolved = doResolve(apiKey)
        resolvedKeyCache.put(cacheKey, resolved)
        resolved
    }

    private def doResolve(apiKey: String): ResolvedKey = {
        if (DatrisEnvironment.values.multiTenant) {
            if (apiKey == null || apiKey.isEmpty)
                throw new DatrisException("x-api-key does not exist or is invalid")
            val mappings = SecretsUtil.getSecretMap("api-key-mappings")
                .getOrElse(throw new DatrisException("api-key-mappings secret not found"))
            val env = mappings.asScala.get(apiKey)
                .getOrElse(throw new DatrisException("Invalid x-api-key"))
            // Multi-tenant labels are not tracked per-key in v1; the env name
            // doubles as the label and all multi-tenant keys are legacy.
            return ResolvedKey(Some(env), env, Seq(Capability.FullAccess), isLegacyFullAccess = true)
        }

        if (!DatrisEnvironment.values.useApiKeys) {
            // Auth disabled — anonymous full-access. Same shape as legacy.
            // We return this regardless of whether a key was presented; the
            // value is ignored when keys aren't being checked.
            return ResolvedKey(None, "anonymous", Seq(Capability.FullAccess), isLegacyFullAccess = true)
        }

        if (apiKey == null || apiKey.isEmpty)
            throw new DatrisException("x-api-key does not exist or is invalid")

        // Single-tenant with API keys enabled: find the label by value.
        resolveAgainst(apiKey, readKeysMap(), readMetadataMap())
    }

    /** Pure core of the single-tenant, keys-enabled branch of [[resolveKey]]:
      * unknown value → "Invalid x-api-key"; revoked metadata → "is revoked";
      * no metadata → legacy full access. */
    private[util] def resolveAgainst(apiKey: String, keys: Map[String, String], metadata: => Map[String, String]): ResolvedKey = {
        val label = labelForValue(keys, apiKey)
            .getOrElse(throw new DatrisException("Invalid x-api-key"))

        // Look up per-key metadata. Absence = legacy full-access.
        metadata.get(label) match {
            case Some(json) =>
                val (revoked, capabilities, keyId) = parseMetadata(label, json)
                if (revoked) throw new RevokedKeyException(label)
                ResolvedKey(None, label, capabilities, isLegacyFullAccess = false, keyId = keyId)
            case None =>
                ResolvedKey(None, label, Seq(Capability.FullAccess), isLegacyFullAccess = true)
        }
    }

    private def parseMetadata(label: String, json: String): (Boolean, Seq[Capability], Option[String]) = {
        try {
            val obj = JsonParser.parseString(json).getAsJsonObject
            val revoked =
                if (obj.has("revoked") && !obj.get("revoked").isJsonNull) obj.get("revoked").getAsBoolean
                else false
            val keyId =
                if (obj.has("keyId") && !obj.get("keyId").isJsonNull) Option(obj.get("keyId").getAsString).filter(_.nonEmpty)
                else None
            val caps: Seq[Capability] =
                if (obj.has("capabilities") && obj.get("capabilities").isJsonArray) {
                    val arr = obj.getAsJsonArray("capabilities")
                    val builder = Seq.newBuilder[Capability]
                    val iter = arr.iterator()
                    while (iter.hasNext) {
                        builder += Capability.parse(iter.next().getAsString)
                    }
                    builder.result()
                } else Seq.empty
            (revoked, caps, keyId)
        } catch {
            case e: DatrisException => throw e
            case e: Exception =>
                throw new DatrisException(s"Failed to parse metadata for API key '$label': ${e.getMessage}")
        }
    }

    /** Clear the resolution cache. Call after a key's metadata changes or
      * it is revoked so the next request picks up the new state immediately
      * rather than waiting for the TTL. */
    def invalidateCache(): Unit = resolvedKeyCache.invalidateAll()

    /** Build a ResolvedKey from an authenticated session user. Used by
      * TenantInterceptor when a request has a valid session cookie but no
      * x-api-key — so the capability framework sees a first-class identity
      * for browser flows too, not just programmatic clients.
      *
      * Capability bundles are derived from the user's role. This is the
      * Phase 2 "Option 3" mapping — admin gets full access, editor gets
      * write capabilities on data resources, viewer gets read-only. */
    def resolveFromSession(user: User): ResolvedKey = {
        val capabilities = roleToCapabilities(user.role)
        ResolvedKey(
            tenantEnvironment = None,
            label = "session:" + user.username,
            capabilities = capabilities,
            isLegacyFullAccess = user.role == User.RoleAdmin
        )
    }

    /** Maps a user role to the capability bundle the role grants. Kept
      * conservative — admins are functionally legacy `*:*`, editors can
      * create and modify but not edit secrets or platform config, viewers
      * can read everything but write nothing. */
    private def roleToCapabilities(role: String): Seq[Capability] = role match {
        case User.RoleAdmin =>
            Seq(Capability.FullAccess)
        case User.RoleEditor =>
            Seq(
                "pipeline:read",
                "pipeline:create",
                "pipeline:update",
                "pipeline:delete",
                "pipeline:run",
                "tap:read",
                "tap:create",
                "tap:update",
                "tap:delete",
                "tap:run",
                "document:upload",
                "search:vector",
                "query:postgres",
                "query:mongodb",
                "query:objectstore",
                "query:snowflake",
                "query:databricks",
                "query:natural",
                "job:read",
                "job:kill",
                "metadata:read",
                "config:read",
                "mcp:tool"
            ).map(Capability.parse)
        case User.RoleViewer =>
            Seq(
                "pipeline:read",
                "tap:read",
                "search:vector",
                "query:postgres",
                "query:mongodb",
                "query:objectstore",
                "query:snowflake",
                "query:databricks",
                "query:natural",
                "job:read",
                "metadata:read",
                "config:read"
            ).map(Capability.parse)
        case _ =>
            Seq.empty
    }
}
