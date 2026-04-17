package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.util.{APIKeyValidator, HttpUtil}
import com.google.common.base.Throwables
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.util.concurrent.atomic.AtomicReference

/** Server-side proxy for the remote model catalog at docs.datris.ai/models.json.
 *  Browsers can't fetch docs.datris.ai directly (no CORS headers), so the UI
 *  fetches from this same-origin endpoint instead.
 *
 *  A successful fetch is cached for CACHE_TTL_MS so that rapid reloads don't
 *  hammer the docs host; on fetch failure the endpoint returns 502 and the UI
 *  falls back to its baked-in default list. */
@RestController
@RequestMapping(Array("/api/v1"))
class ModelCatalogAPIController {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ModelCatalogAPIController])

    private val CATALOG_URL = "https://docs.datris.ai/models.json"
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
        }
        catch {
            case e: Exception =>
                logger.warn("Model catalog fetch failed: " + e.getMessage)
                // Serve the stale cache if we have one — better than forcing UIs to fall back.
                val stale = cache.get()
                if (stale != null) new ResponseEntity[String](stale.body, HttpStatus.OK)
                else ResponseEntity.status(HttpStatus.BAD_GATEWAY).body[String]("{\"error\":\"catalog unavailable\"}")
        }
    }
}
