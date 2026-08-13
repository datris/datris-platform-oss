package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.azure.core.credential.{AccessToken, TokenCredential, TokenRequestContext}
import com.azure.identity.{ClientSecretCredentialBuilder, DefaultAzureCredentialBuilder}
import ai.datris.model.DatrisException
import ai.datris.util.SecretsUtil

import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.JavaConverters._

/** Azure OpenAI auth resolution: API key or Entra ID (keyless). The Azure
  * analogue of BedrockSupport — where Bedrock swaps the API-key header for
  * SigV4 signing, Azure swaps it for an Entra ID Bearer token. The wire path
  * (endpoints, bodies, streaming, deployment names) is untouched; this object
  * only decides which auth headers a request carries.
  *
  * Auth precedence, derived — never stored as a mode field:
  *
  *   1. API key (already resolved through resolveApiKey's tiers: ai-keys
  *      `azureApiKey`, slot inline key, AZURE_OPENAI_API_KEY env). A stored
  *      key always wins — the backward-compat guarantee.
  *   2. Service-principal trio in the shared key store `{env}/ai-keys` —
  *      fields `azureTenantId` / `azureClientId` / `azureClientSecret`,
  *      exchanged for tokens via the Entra client_credentials grant. The
  *      per-tenant credential in multi-tenant mode; centrally rotatable.
  *   3. The Azure default credential chain (managed identity on Azure
  *      compute, AZURE_* env vars, workload identity, az CLI) — single-tenant
  *      only: a multi-tenant tenant must never ride the platform's identity.
  *      Mirrors BedrockSupport's default-chain guard.
  *
  * Tokens are scoped to Cognitive Services and live ~1 hour; they're cached
  * with a 5-minute expiry margin so mid-request expiry can't happen and no
  * 401-refresh-retry logic is needed. executeWithRetry re-invokes its request
  * factory per attempt, so retries pick up a fresh token automatically — the
  * same property Bedrock's per-attempt re-signing relies on.
  *
  * The identity (SP or managed identity) needs the "Cognitive Services OpenAI
  * User" RBAC role on the Azure OpenAI resource.
  */
object AzureEntraSupport {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Entra token audience for Azure OpenAI (public cloud). Sovereign clouds
      * (Government / China) use a different host — out of scope until needed. */
    private val SCOPE = "https://cognitiveservices.azure.com/.default"

    /** Refresh a cached token once it has less than this long to live. */
    private val EXPIRY_MARGIN_SECONDS = 300L

    /** Resolved auth for one Azure OpenAI request. */
    sealed trait AzureAuth
    case class ApiKey(key: String) extends AzureAuth
    case class EntraToken(bearer: String) extends AzureAuth

    /** Which credential source the store/env state selects — the pure
      * precedence decision, split out for unit testing. */
    private[aiutil] sealed trait AuthMode
    private[aiutil] case class KeyMode(key: String) extends AuthMode
    private[aiutil] case class ServicePrincipalMode(tenantId: String, clientId: String, clientSecret: String) extends AuthMode
    private[aiutil] case object DefaultChainMode extends AuthMode

    // Credential objects are cached so MSAL's internal token cache and the
    // default chain's probing (IMDS, env, CLI) apply across calls. SP
    // credentials are keyed per identity so multi-tenant stores coexist.
    private lazy val defaultCredential: TokenCredential = new DefaultAzureCredentialBuilder().build()
    private val spCredentials = new ConcurrentHashMap[String, TokenCredential]()

    // cacheKey -> last fetched token; refreshed when inside the expiry margin.
    private val tokenCache = new ConcurrentHashMap[String, AccessToken]()

    /** Resolve the auth for an Azure OpenAI request. `apiKey` is the slot's
      * already-resolved key (AIProviders.resolveApiKey) — non-empty means API-key
      * mode and nothing else is consulted. Keyless resolution reads the SP trio
      * from `{env}/ai-keys`, falling back to the Azure default credential chain
      * in single-tenant mode. Throws a DatrisException naming what's missing and
      * how to fix it — an Azure call cannot proceed with no credential at all. */
    def resolveAuth(apiKey: String, env: String, multiTenant: Boolean): AzureAuth = {
        if (apiKey != null && apiKey.nonEmpty) return ApiKey(apiKey)
        val store: Map[String, String] =
            try SecretsUtil.getSecretMap(env + "/ai-keys").map(_.asScala.toMap).getOrElse(Map.empty)
            catch {
                case e: Exception =>
                    logger.debug("Could not read " + env + "/ai-keys for Azure Entra credentials — falling through", e)
                    Map.empty
            }
        resolveMode("", store, multiTenant) match {
            case KeyMode(key) => ApiKey(key)
            case ServicePrincipalMode(tenantId, clientId, clientSecret) =>
                val credKey = "sp:" + tenantId + ":" + clientId + ":" + clientSecret.hashCode
                val credential = spCredentials.computeIfAbsent(
                    credKey,
                    _ =>
                        new ClientSecretCredentialBuilder()
                            .tenantId(tenantId)
                            .clientId(clientId)
                            .clientSecret(clientSecret)
                            .build()
                )
                EntraToken(cachedToken(credKey, OffsetDateTime.now(), () => fetchToken(credential, "service principal")))
            case DefaultChainMode =>
                EntraToken(cachedToken("default", OffsetDateTime.now(), () => fetchToken(defaultCredential, "default credential chain")))
        }
    }

    /** Pure precedence decision over (resolved key, ai-keys store fields,
      * tenancy). Package-private for unit testing. */
    private[aiutil] def resolveMode(apiKey: String, store: Map[String, String], multiTenant: Boolean): AuthMode = {
        if (apiKey != null && apiKey.nonEmpty) return KeyMode(apiKey)
        def field(name: String): String = store.get(name).map(_.trim).filter(_.nonEmpty).getOrElse("")
        val tenantId = field("azureTenantId")
        val clientId = field("azureClientId")
        val clientSecret = field("azureClientSecret")
        val present = Seq(tenantId, clientId, clientSecret).count(_.nonEmpty)
        if (present == 3) ServicePrincipalMode(tenantId, clientId, clientSecret)
        else if (present > 0) {
            val missing = Seq(
                "Tenant ID" -> tenantId,
                "Client ID" -> clientId,
                "Client Secret" -> clientSecret
            ).collect { case (label, v) if v.isEmpty => label }
            throw new DatrisException(
                "Azure Entra service-principal credentials are incomplete: missing " + missing.mkString(", ") +
                    ". Enter all three (Tenant ID, Client ID, Client Secret) in the Configuration tab, or clear " +
                    "all three to use a managed identity / the Azure default credential chain."
            )
        } else if (!multiTenant) DefaultChainMode
        else
            throw new DatrisException(
                "No Azure OpenAI credentials found for this tenant. Enter an API key or an Entra service principal " +
                    "(Tenant ID, Client ID, Client Secret) in the Configuration tab."
            )
    }

    /** Serve a token from the cache, refreshing when it has less than the
      * expiry margin left. `now` is injectable for unit tests. */
    private[aiutil] def cachedToken(cacheKey: String, now: OffsetDateTime, fetch: () => AccessToken): String = {
        val cached = tokenCache.get(cacheKey)
        if (cached != null && cached.getExpiresAt != null && now.plusSeconds(EXPIRY_MARGIN_SECONDS).isBefore(cached.getExpiresAt))
            return cached.getToken
        val fresh = fetch()
        tokenCache.put(cacheKey, fresh)
        fresh.getToken
    }

    private def fetchToken(credential: TokenCredential, label: String): AccessToken = {
        try {
            val token = credential.getTokenSync(new TokenRequestContext().addScopes(SCOPE))
            logger.info("Acquired Azure Entra token via " + label + ", expires " + token.getExpiresAt)
            token
        } catch {
            case e: Exception =>
                throw new DatrisException(
                    "Could not acquire an Azure Entra token via the " + label + ". Enter an Entra service principal " +
                        "(Tenant ID, Client ID, Client Secret) in the Configuration tab, set " +
                        "AZURE_TENANT_ID/AZURE_CLIENT_ID/AZURE_CLIENT_SECRET, or run the server on Azure compute with a " +
                        "managed identity that has the \"Cognitive Services OpenAI User\" role on the resource. " +
                        "(" + e.getMessage + ")"
                )
        }
    }

    /** The auth headers an Azure OpenAI request carries. API-key mode keeps the
      * dual-header behavior shipped in v1.15.0 (legacy deployment-scoped URLs
      * accept only `api-key`; the v1 API accepts either). Entra mode sends ONLY
      * the Bearer header — an `api-key` header is exactly what a resource with
      * disableLocalAuth rejects, and Entra tokens are accepted on both endpoint
      * shapes. */
    def authHeaders(auth: AzureAuth): Seq[(String, String)] = auth match {
        case ApiKey(key) => Seq("api-key" -> key, "Authorization" -> ("Bearer " + key))
        case EntraToken(bearer) => Seq("Authorization" -> ("Bearer " + bearer))
    }

    /** Test seam: clear cached tokens (never needed in production — entries
      * self-refresh via the expiry margin). */
    private[aiutil] def clearTokenCache(): Unit = tokenCache.clear()
}
