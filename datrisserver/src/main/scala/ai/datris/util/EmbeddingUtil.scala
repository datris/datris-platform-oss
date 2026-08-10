package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.{Gson, JsonArray, JsonObject}
import ai.datris.model.DatrisException
import org.apache.http.HttpHeaders
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._

object EmbeddingUtil {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // Inputs per /v1/embeddings POST. 32 is safe across providers we support:
    // - OpenAI accepts up to 2048
    // - HuggingFace TEI defaults to 32 (--max-client-batch-size)
    // - Ollama accepts large batches but doesn't benefit from them
    // Override per-secret with `batchSize` in the embedding Vault entry when
    // a provider supports more (e.g. OpenAI users can crank to 2048 for throughput).
    private val DEFAULT_BATCH_SIZE = 32

    // Default heuristic ratio: 2.0 chars/token deliberately over-counts tokens
    // so the guard splits/truncates a touch too eagerly rather than letting a
    // borderline chunk slip through. Users with predictable Latin prose can
    // raise this via the embedding secret's `tokensPerCharRatio` field.
    private val DEFAULT_TOKENS_PER_CHAR_RATIO = 2.0

    case class EmbeddingConfig(
        endpoint: String,
        model: String,
        apiKey: String,
        batchSize: Int,
        // Token-guard knobs. All optional; sensible defaults derive from the
        // model name via EmbeddingDefaults.
        maxTokens: Option[Int] = None,
        tokenizer: Option[String] = None,
        tokensPerCharRatio: Double = DEFAULT_TOKENS_PER_CHAR_RATIO,
        oversize: String = "split",
        // When true, on a 4xx error from the embedding API, retry the failed
        // batch one chunk at a time so a single poison chunk doesn't lose the
        // whole batch. Costs N HTTP calls in the failure path; off by default.
        retryIndividualOnFailure: Boolean = false
    )

    /** Pair of (fitted text, embedding) — vector-store loaders persist both. */
    case class EmbeddedChunk(text: String, embedding: Array[Float])

    def getConfig(secretName: String): EmbeddingConfig = {
        val secret = SecretsUtil.getSecretMap(secretName)
            .getOrElse(throw new DatrisException("Embedding secret not found: " + secretName))
        val endpoint = secret.get("endpoint")
        if (endpoint == null) throw new DatrisException("'endpoint' not found in embedding secret: " + secretName)
        val model = secret.get("model")
        if (model == null) throw new DatrisException("'model' not found in embedding secret: " + secretName)
        // Resolve the key through the same per-provider store / env-var fallback as the
        // chat slots, so an OpenAI embedding endpoint keeps working after the user
        // switches the embedding provider away and back (the slot's inline apiKey can
        // be cleared on a provider switch; the shared {env}/ai-keys store is durable).
        // Bundled providers (Ollama, TEI) need no key and pass through as "".
        val rawKey = Option(secret.get("apiKey")).getOrElse("")
        val provider = Option(secret.get("provider")).map(_.trim.toLowerCase).getOrElse("")
        val apiKey =
            if (provider == "openai" || provider == "anthropic" || provider == "azure")
                AIUtil.resolveApiKey(rawKey, provider, ai.datris.model.DatrisEnvironment.values.multiTenant, secretName.takeWhile(_ != '/'))
            else rawKey
        val batchSize = Option(secret.get("batchSize"))
            .filter(_.nonEmpty)
            .flatMap(s => scala.util.Try(s.toInt).toOption)
            .filter(_ > 0)
            .getOrElse(DEFAULT_BATCH_SIZE)
        val maxTokens = Option(secret.get("maxTokens"))
            .filter(_.nonEmpty)
            .flatMap(s => scala.util.Try(s.toInt).toOption)
            .filter(_ > 0)
        val tokenizer = Option(secret.get("tokenizer")).filter(_.nonEmpty)
        val tokensPerCharRatio = Option(secret.get("tokensPerCharRatio"))
            .filter(_.nonEmpty)
            .flatMap(s => scala.util.Try(s.toDouble).toOption)
            .filter(_ > 0.0)
            .getOrElse(DEFAULT_TOKENS_PER_CHAR_RATIO)
        val oversize = Option(secret.get("oversize")).filter(_.nonEmpty).getOrElse("split")
        val retryIndividual = Option(secret.get("retryIndividualOnFailure"))
            .map(_.trim.toLowerCase)
            .exists(s => s == "true" || s == "1" || s == "yes")
        EmbeddingConfig(endpoint, model, apiKey, batchSize, maxTokens, tokenizer, tokensPerCharRatio, oversize, retryIndividual)
    }

    /**
     * Embed `texts` and return `(fittedText, vector)` pairs. The fitted text is
     * what was actually sent to the provider — in `split` mode there may be
     * more output entries than input entries (a single oversized input can
     * fan out into N sub-chunks). Callers MUST iterate the returned list and
     * not assume positional alignment with the input.
     */
    def generateEmbeddings(texts: List[String], config: EmbeddingConfig): List[EmbeddedChunk] = {
        logger.info("Generating embeddings for " + texts.size + " texts using model: " +
            config.model + " (batchSize=" + config.batchSize + ")")

        val fitted = fitChunks(texts, config)

        fitted.grouped(config.batchSize).flatMap { batch =>
            val vectors = callBatchWithFallback(batch, config)
            batch.zip(vectors).map { case (t, v) => EmbeddedChunk(t, v) }
        }.toList
    }

    /** Same as generateEmbeddings but discards text — convenience for single-vector callers (search). */
    def generateVectors(texts: List[String], config: EmbeddingConfig): List[Array[Float]] =
        generateEmbeddings(texts, config).map(_.embedding)

    def embeddingDimension(config: EmbeddingConfig): Int = {
        val testEmbedding = callEmbeddingAPI(List("test"), config)
        testEmbedding.head.length
    }

    private def fitChunks(texts: List[String], config: EmbeddingConfig): List[String] = {
        val cap = config.maxTokens
            .map(EmbeddingDefaults.applyMargin)
            .getOrElse(EmbeddingDefaults.effectiveMaxTokens(config.model))
        val counter = TokenCounterRegistry.forModel(config.model, config.tokenizer, config.tokensPerCharRatio)
        val mode = TokenGuard.Mode.parse(config.oversize)
        TokenGuard.fitChunks(texts, counter, cap, mode, config.model)
    }

    /**
     * Call the embedding API for a batch. On 4xx (provider rejected the input),
     * surface a more useful error than the raw provider body — and, when
     * `retryIndividualOnFailure` is on, retry each chunk one-at-a-time so a
     * single poison chunk doesn't lose 31 good ones.
     */
    private def callBatchWithFallback(batch: List[String], config: EmbeddingConfig): List[Array[Float]] = {
        try {
            callEmbeddingAPI(batch, config)
        } catch {
            case e: DatrisException if isClientError(e.getMessage) =>
                logger.warn("Embedding batch of " + batch.size + " failed: " + e.getMessage)
                surfaceLikelyCulprit(batch, config)
                if (config.retryIndividualOnFailure) {
                    logger.warn("retryIndividualOnFailure=true — retrying " + batch.size + " chunks individually")
                    retryIndividually(batch, config)
                } else {
                    throw e
                }
        }
    }

    private def isClientError(msg: String): Boolean =
        msg != null && (msg.contains("status: 4") || msg.contains("status: 413"))

    /**
     * Log the top-N largest chunks in the failing batch (by heuristic count) so
     * a user reading the error can tell which input is suspect. Best-effort —
     * we never throw from here; the original exception is what gets raised.
     */
    private def surfaceLikelyCulprit(batch: List[String], config: EmbeddingConfig): Unit = {
        try {
            val counter = TokenCounterRegistry.forModel(config.model, config.tokenizer, config.tokensPerCharRatio)
            val cap = config.maxTokens
                .map(EmbeddingDefaults.applyMargin)
                .getOrElse(EmbeddingDefaults.effectiveMaxTokens(config.model))
            val top = batch.zipWithIndex
                .map { case (t, i) => (i, counter.count(t), t.length) }
                .sortBy(-_._2)
                .take(3)
            val summary = top.map { case (i, tokens, chars) =>
                "#" + i + " (" + tokens + " " + counter.label + " tokens, " + chars + " chars)"
            }.mkString(", ")
            logger.warn("Embedding batch culprit candidates (cap=" + cap + " tokens, counter=" +
                counter.label + "): " + summary)
        } catch {
            case _: Throwable => // best-effort; swallow
        }
    }

    private def retryIndividually(batch: List[String], config: EmbeddingConfig): List[Array[Float]] = {
        batch.zipWithIndex.flatMap { case (text, idx) =>
            try {
                callEmbeddingAPI(List(text), config)
            } catch {
                case e: DatrisException =>
                    throw new DatrisException(
                        "Embedding failed on chunk #" + idx + " of failing batch (length=" +
                            text.length + " chars). Underlying: " + e.getMessage
                    )
            }
        }
    }

    private def callEmbeddingAPI(texts: List[String], config: EmbeddingConfig): List[Array[Float]] = {
        val gson = new Gson()

        val inputArray = new JsonArray()
        texts.foreach(t => inputArray.add(t))

        val requestObj = new JsonObject()
        requestObj.addProperty("model", config.model)
        requestObj.add("input", inputArray)

        val jsonBody = requestObj.toString
        val client = HttpClients.createDefault()

        try {
            val httpPost = new HttpPost(config.endpoint)
            if (config.apiKey.nonEmpty)
                httpPost.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey)
            httpPost.addHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8))

            val response = client.execute(httpPost)
            val statusCode = response.getStatusLine.getStatusCode
            if (statusCode != 200) {
                val body = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
                throw new DatrisException("Embedding API returned error status: " + statusCode + ", body: " + body)
            }

            val responseBody = EntityUtils.toString(response.getEntity, StandardCharsets.UTF_8)
            parseEmbeddingResponse(responseBody)
        } finally {
            client.close()
        }
    }

    private def parseEmbeddingResponse(responseBody: String): List[Array[Float]] = {
        val gson = new Gson()
        val responseMap = gson.fromJson(responseBody, classOf[java.util.Map[String, Any]])
        val data = responseMap.get("data").asInstanceOf[java.util.List[java.util.Map[String, Any]]]
        if (data == null || data.isEmpty)
            throw new DatrisException("Embedding API response contained no data")

        data.asScala.map { item =>
            val embedding = item.get("embedding").asInstanceOf[java.util.List[Any]]
            val values = embedding.asScala.map { v =>
                val f: Float = v match {
                    case d: java.lang.Double => d.floatValue()
                    case f: java.lang.Float => f.floatValue()
                    case other => throw new DatrisException("Unexpected embedding value type: " + other.getClass)
                }
                f
            }
            values.toArray
        }.toList
    }
}
