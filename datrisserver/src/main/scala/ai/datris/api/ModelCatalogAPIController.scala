package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.{APIKeyValidator, HttpUtil}
import com.google.common.base.Throwables
import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.http.{HttpStatus, MediaType, ResponseEntity}
import org.springframework.web.bind.annotation._

import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._

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

    // ------------------------------------------------------------------
    // OpenRouter live catalog proxy.
    //
    // OpenRouter serves two DISJOINT catalogs (verified 2026-07-30): the main
    // /api/v1/models list contains only text-output chat models, and embedding
    // models live exclusively at /api/v1/embeddings/models. Neither needs auth.
    // The UI's slot-aware dropdowns rely on that split: kind=chat feeds the
    // ai-primary/codegen model fields, kind=embedding feeds the embedding field.
    //
    // tier=recommended (chat only) applies a capability filter for the high
    // requirements of the Assistant agent loop and CodeGen: tool calling,
    // structured outputs, deep reasoning support, and a large context window.
    // The filter is metadata-driven so it tracks new frontier models without
    // curation. tier=all returns the full catalog. Deliberately NOT used:
    // OpenRouter's ?category=programming (a popularity ranking, not a quality
    // bar) and pricing (a crude quality proxy).
    // ------------------------------------------------------------------

    private val OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/models"
    private val OPENROUTER_EMBEDDING_URL = "https://openrouter.ai/api/v1/embeddings/models"
    private val OPENROUTER_CACHE_TTL_MS = 60L * 60L * 1000L
    private val OPENROUTER_FETCH_TIMEOUT_MS = 5000
    private val OPENROUTER_MIN_CONTEXT = 200000
    private val OPENROUTER_DEFAULT_CHAT_MODEL = "anthropic/claude-opus-5"

    private val openrouterCache = new java.util.concurrent.ConcurrentHashMap[String, Cached]()

    @GetMapping(path = Array("/ai/model-catalog/openrouter"), produces = Array(MediaType.APPLICATION_JSON_VALUE))
    def getOpenrouterCatalog(
        @RequestHeader(name = "x-api-key", required = false) apiKey: String,
        @RequestParam(name = "kind", required = false, defaultValue = "chat") kind: String,
        @RequestParam(name = "tier", required = false, defaultValue = "recommended") tier: String
    ): ResponseEntity[String] = {
        try {
            APIKeyValidator.validate(apiKey)

            val isEmbedding = "embedding".equalsIgnoreCase(kind)
            val url = if (isEmbedding) OPENROUTER_EMBEDDING_URL else OPENROUTER_CHAT_URL
            val cacheKey = if (isEmbedding) "embedding" else "chat"

            val now = System.currentTimeMillis()
            val cached = openrouterCache.get(cacheKey)
            val raw =
                if (cached != null && now - cached.fetchedAt < OPENROUTER_CACHE_TTL_MS) cached.body
                else {
                    val body = HttpUtil.get(url, timeoutMillis = OPENROUTER_FETCH_TIMEOUT_MS)
                    openrouterCache.put(cacheKey, Cached(body, now))
                    body
                }

            // Embedding catalog is small — no tier concept there.
            val recommendedOnly = !isEmbedding && !"all".equalsIgnoreCase(tier)
            new ResponseEntity[String](mapOpenrouterCatalog(raw, recommendedOnly).toString, HttpStatus.OK)
        } catch {
            case e: Exception =>
                logger.warn("OpenRouter catalog fetch failed (kind=" + kind + "): " + e.getMessage)
                val stale = openrouterCache.get(if ("embedding".equalsIgnoreCase(kind)) "embedding" else "chat")
                if (stale != null) {
                    val recommendedOnly = !"embedding".equalsIgnoreCase(kind) && !"all".equalsIgnoreCase(tier)
                    try return new ResponseEntity[String](mapOpenrouterCatalog(stale.body, recommendedOnly).toString, HttpStatus.OK)
                    catch { case _: Exception => () }
                }
                // 502 → UI falls back to its baked-in curated list.
                ResponseEntity.status(HttpStatus.BAD_GATEWAY).body[String]("{\"error\":\"catalog unavailable\"}")
        }
    }

    /** Map OpenRouter's raw catalog to the UI's ModelOption shape
      * `[{value, label, recommended?}]`, optionally applying the frontier
      * capability filter, and pin the Datris default model to position 1. */
    private def mapOpenrouterCatalog(raw: String, recommendedOnly: Boolean): JsonArray = {
        val data = JsonParser.parseString(raw).getAsJsonObject.getAsJsonArray("data")
        val out = new JsonArray()
        if (data == null) return out

        var pinned: JsonObject = null
        val rest = new JsonArray()

        data.asScala.foreach { el =>
            val m = el.getAsJsonObject
            val id = if (m.has("id") && !m.get("id").isJsonNull) m.get("id").getAsString else ""
            val keep = id.nonEmpty &&
                !id.startsWith("~") && !id.endsWith(":free") && !id.endsWith(":batch") &&
                (!recommendedOnly || passesFrontierBar(m))
            if (keep) {
                val name = if (m.has("name") && !m.get("name").isJsonNull) m.get("name").getAsString else id
                val opt = new JsonObject()
                opt.addProperty("value", id)
                if (id == OPENROUTER_DEFAULT_CHAT_MODEL) {
                    opt.addProperty("label", name + " (recommended)")
                    opt.addProperty("recommended", true)
                    pinned = opt
                } else {
                    opt.addProperty("label", name)
                    rest.add(opt)
                }
            }
        }

        // Catalog order is newest-first (no quality score exists) — pin the
        // Datris default at the top so "first in the list" is always a safe pick.
        if (pinned != null) out.add(pinned)
        rest.asScala.foreach(out.add)
        out
    }

    /** Vendors always eligible for tier=recommended. Other vendors qualify only
      * via the open-weights signal (`hugging_face_id` present) — users want the
      * high-end Anthropic/OpenAI models and the latest open-source flagships,
      * not every proprietary catalog entry that clears the capability bar. */
    private val RecommendedVendors = Set("anthropic", "openai")

    /** Budget-tier name markers excluded from tier=recommended. These variants
      * pass the capability bar but are the vendors' smaller/cheaper lines —
      * wrong for the Assistant/CodeGen quality requirements. "-fast" variants
      * stay: those are speed-optimized serving of the same frontier model. */
    private val BudgetMarkers = Seq("mini", "nano", "lite", "flash", "small")

    /** Full bar for tier=recommended. Capability: tools + tool_choice (agent
      * loop), structured_outputs (reliable JSON), reasoning with high/xhigh/max
      * effort support, >= 200k context. Provenance: Anthropic/OpenAI, or
      * open-weights (hugging_face_id present). Tier: no budget-line variants.
      * Live result at time of writing: 367 chat models -> ~34, exactly the
      * frontier set (Claude Opus/Sonnet/Fable, GPT-5.x incl. Pro/Codex,
      * Kimi K3, GLM-5.2, DeepSeek V4 Pro, Nemotron Ultra, ...). */
    private def passesFrontierBar(m: JsonObject): Boolean = {
        val id = if (m.has("id") && !m.get("id").isJsonNull) m.get("id").getAsString.toLowerCase else ""
        if (BudgetMarkers.exists(id.contains)) return false
        val vendor = id.takeWhile(_ != '/')
        val openWeights = m.has("hugging_face_id") && !m.get("hugging_face_id").isJsonNull &&
            m.get("hugging_face_id").getAsString.nonEmpty
        if (!RecommendedVendors.contains(vendor) && !openWeights) return false
        passesCapabilityBar(m)
    }

    private def passesCapabilityBar(m: JsonObject): Boolean = {
        val params: Set[String] =
            if (m.has("supported_parameters") && m.get("supported_parameters").isJsonArray)
                m.getAsJsonArray("supported_parameters").asScala.map(_.getAsString).toSet
            else Set.empty
        if (!params.contains("tools") || !params.contains("tool_choice") || !params.contains("structured_outputs")) return false

        val contextOk = m.has("context_length") && !m.get("context_length").isJsonNull &&
            m.get("context_length").getAsLong >= OPENROUTER_MIN_CONTEXT
        if (!contextOk) return false

        if (!m.has("reasoning") || !m.get("reasoning").isJsonObject) return false
        val r = m.getAsJsonObject("reasoning")
        if (!r.has("supported_efforts") || !r.get("supported_efforts").isJsonArray) return false
        val efforts = r.getAsJsonArray("supported_efforts").asScala.map(_.getAsString).toSet
        efforts.contains("high") || efforts.contains("xhigh") || efforts.contains("max")
    }
}
