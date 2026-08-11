package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import ai.datris.util.{APIKeyValidator, HttpUtil}
import ai.datris.util.aiutil.BedrockSupport
import com.google.common.base.Throwables
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.util.concurrent.atomic.AtomicReference

/** Server-side proxy for the remote model catalog at datris.ai/models.json.
 *  The UI fetches through this same-origin endpoint so the api-key interceptor
 *  attaches the tenant key and we don't depend on cross-origin CORS headers.
 *
 *  A successful fetch is cached for CACHE_TTL_MS so that rapid reloads don't
 *  hammer the website; on fetch failure the endpoint returns the stale cache
 *  (if any) or 502 so the UI falls back to its baked-in default list. */
@RestController
@RequestMapping(Array("/api/v1"))
class ModelCatalogAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ModelCatalogAPIController])

    private val CATALOG_URL = "https://datris.ai/models.json"
    private val CACHE_TTL_MS = 5L * 60L * 1000L
    private val FETCH_TIMEOUT_MS = 3000

    private case class Cached(body: String, fetchedAt: Long)
    private val cache = new AtomicReference[Cached](null)

    @GetMapping(path = Array("/ai/model-catalog"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getModelCatalog(@RequestHeader(name = "x-api-key", required = false) apiKey: String): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)

            val now = System.currentTimeMillis()
            val cached = cache.get()
            if (cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
                return new ResponseEntity[String](cached.body, HttpStatus.OK)
            }

            val body = HttpUtil.get(CATALOG_URL, timeoutMillis = FETCH_TIMEOUT_MS)
            cache.set(Cached(body, now))
            new ResponseEntity[String](body, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.warn("Model catalog fetch failed: " + e.getMessage)
                // Serve the stale cache if we have one — better than forcing UIs to fall back.
                val stale = cache.get()
                if (stale != null) new ResponseEntity[String](stale.body, HttpStatus.OK)
                else ResponseEntity.status(HttpStatus.BAD_GATEWAY).body[String]("{\"error\":\"catalog unavailable\"}")
        }
    }

    // Per-provider live model discovery. Unlike the static catalog above, this
    // reflects what the configured account can actually invoke. For bedrock,
    // that means the AWS discovery APIs (ListFoundationModels +
    // ListInferenceProfiles) merged into invokable ids — a model without
    // ON_DEMAND support is surfaced as its cross-region inference-profile id.
    // The UI overlays catalog `recommended` labels on this list and falls back
    // to the static catalog when discovery is unavailable (no credentials yet,
    // or an IAM policy without the two List permissions).
    private val discoveryCache = new AtomicReference[Cached](null)

    @GetMapping(path = Array("/ai/models"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getProviderModels(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(name = "provider") provider: String
    ): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)
            provider.toLowerCase match {
                case "bedrock" =>
                    val now = System.currentTimeMillis()
                    val cached = discoveryCache.get()
                    if (cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
                        return new ResponseEntity[String](cached.body, HttpStatus.OK)
                    }
                    val env = DatrisEnvironment.current
                    val body = BedrockSupport.listModels(env.environment, env.multiTenant)
                    discoveryCache.set(Cached(body, now))
                    new ResponseEntity[String](body, HttpStatus.OK)
                case other =>
                    ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body[String]("{\"error\":\"model discovery is not supported for provider '" + other + "'\"}")
            }
        } catch {
            case e: Exception =>
                // Expected failure modes: no AWS credentials saved yet, or an IAM
                // policy missing bedrock:ListFoundationModels /
                // bedrock:ListInferenceProfiles. The UI treats non-200 as "use the
                // static catalog" — log at info, not error.
                logger.info("Bedrock model discovery unavailable: " + e.getMessage)
                ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body[String]("{\"error\":\"model discovery unavailable\"}")
        }
    }
}
