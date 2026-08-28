package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Capability, DatrisEnvironment, DatrisException, ResolvedKey, User, UserContext}
import com.google.common.cache.CacheBuilder
import com.google.gson.JsonParser

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._

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

            if (apiKey == null)
                throw new DatrisException("x-api-key does not exist or is invalid")

            val apiKeysMap = ai.datris.util.SecretsUtil.getSecretMap(DatrisEnvironment.values.apiKeysSecretName)
                .getOrElse(throw new DatrisException("The Secrets Manager entry for value: " + DatrisEnvironment.values.apiKeysSecretName + " was not found"))
            val apiKeys = apiKeysMap.asScala.map { case (key, value) => value }.toList

            if (!apiKeys.contains(apiKey))
                throw new DatrisException("Invalid x-api-key: " + apiKey)
        }
    }

    /** Validates the API key and resolves the tenant environment name.
      * Returns Some(environmentName) when multiTenant is true, None otherwise. */
    def validateAndResolve(apiKey: String): Option[String] = {
        if (DatrisEnvironment.values.multiTenant) {
            if (apiKey == null || apiKey.isEmpty)
                return None // No API key — fall back to global environment

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
        val keysMap = SecretsUtil.getSecretMap(DatrisEnvironment.values.apiKeysSecretName)
            .getOrElse(throw new DatrisException(
                "The Secrets Manager entry for value: " + DatrisEnvironment.values.apiKeysSecretName + " was not found"
            ))
        val label = keysMap.asScala
            .find { case (_, v) => v == apiKey }
            .map(_._1)
            .getOrElse(throw new DatrisException("Invalid x-api-key"))

        // Look up per-key metadata. Absence = legacy full-access.
        val metadataMap = SecretsUtil.getSecretMap(apiKeyMetadataSecretName)
            .map(_.asScala.toMap)
            .getOrElse(Map.empty[String, String])

        metadataMap.get(label) match {
            case Some(json) =>
                val (revoked, capabilities, keyId) = parseMetadata(label, json)
                if (revoked) throw new DatrisException(s"API key '$label' is revoked")
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
                "query:natural",
                "job:read",
                "metadata:read",
                "config:read"
            ).map(Capability.parse)
        case _ =>
            Seq.empty
    }
}
