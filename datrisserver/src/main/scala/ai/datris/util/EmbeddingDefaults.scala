package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

/**
 * Per-model max input-token limits for common embedding providers. Used by
 * TokenGuard to size oversized-chunk handling without requiring users to know
 * each provider's caps. Match is by case-insensitive model-name prefix; the
 * fallback for unknown models is intentionally conservative.
 *
 * Override per-secret with `maxTokens` in the embedding Vault entry.
 */
object EmbeddingDefaults {

    // Conservative fallback for unknown models. Most modern embedding models
    // accept >= 8192, so 6000 errs on the safe side without being uselessly low.
    val FallbackMaxTokens: Int = 6000

    // Multiplier applied to whatever cap matches. Absorbs tokenizer mismatch
    // (heuristic vs. real, jtokkit vs. provider-side tokenizer) and avoids
    // edge-case failures at the exact boundary.
    val SafetyMargin: Double = 0.90

    // (prefix, maxTokens). First match wins; iteration order is insertion order.
    private val table: Seq[(String, Int)] = Seq(
        // OpenAI
        "text-embedding-3-small"            -> 8192,
        "text-embedding-3-large"            -> 8192,
        "text-embedding-ada-002"            -> 8191,
        // Cohere
        "embed-english-v3.0"                -> 512,
        "embed-multilingual-v3.0"           -> 512,
        "embed-english-light-v3.0"          -> 512,
        "embed-multilingual-light-v3.0"     -> 512,
        // Voyage
        "voyage-3-lite"                     -> 32000,
        "voyage-3"                          -> 32000,
        "voyage-large-2"                    -> 16000,
        "voyage-code-2"                     -> 16000,
        // Google Vertex
        "text-embedding-004"                -> 2048,
        "text-embedding-005"                -> 2048,
        "gecko"                             -> 2048,
        // BAAI
        "bge-m3"                            -> 8192,
        "bge-large-en-v1.5"                 -> 512,
        "bge-base-en-v1.5"                  -> 512,
        // Nomic
        "nomic-embed-text-v1.5"             -> 8192,
        "nomic-embed-text-v2"               -> 8192,
        // Intfloat E5
        "e5-large-v2"                       -> 512,
        "multilingual-e5-large"             -> 512,
        // Sentence Transformers
        "all-minilm-l6-v2"                  -> 256,
        "all-mpnet-base-v2"                 -> 256,
        // Mistral
        "mistral-embed"                     -> 8192
    )

    /** Resolve the raw (pre-safety-margin) cap for a model. */
    def rawMaxTokens(model: String): Int = {
        val m = Option(model).getOrElse("").toLowerCase
        table.collectFirst { case (prefix, cap) if m.startsWith(prefix) => cap }
            .getOrElse(FallbackMaxTokens)
    }

    /** Effective cap a guard should enforce, after applying the safety margin. */
    def effectiveMaxTokens(model: String): Int = applyMargin(rawMaxTokens(model))

    /** Apply the safety margin to an explicit (user-supplied) cap. */
    def applyMargin(rawCap: Int): Int = Math.max(1, Math.floor(rawCap * SafetyMargin).toInt)
}
