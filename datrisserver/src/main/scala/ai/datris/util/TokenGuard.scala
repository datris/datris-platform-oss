package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.DatrisException
import org.slf4j.{Logger, LoggerFactory}

/**
 * Provider-agnostic token-count safety net for embeddings.
 *
 * Sits between chunk producers and the embeddings API so a single oversized
 * chunk can't fail an entire batch. Provider-agnostic by design: the
 * `TokenCounter` interface lets the platform swap in exact tokenizers
 * (jtokkit, HF) without touching the guard or the embedding path.
 */
trait TokenCounter {
    /** Estimate or exact token count for `text`. Implementations must be O(n) and pure. */
    def count(text: String): Int

    /**
     * Encode `text` into token IDs. Default implementation throws — only exact
     * tokenizers (Phase 4) need to implement this for token-boundary splitting.
     * The heuristic counter falls back to character-boundary splits.
     */
    def encode(text: String): Array[Int] =
        throw new UnsupportedOperationException(label + " does not support encode/decode")

    /** Decode token IDs back to text. See encode. */
    def decode(tokens: Array[Int]): String =
        throw new UnsupportedOperationException(label + " does not support encode/decode")

    /** True when encode/decode are usable. Drives token-boundary vs char-boundary split. */
    def supportsExactSplit: Boolean = false

    /** Human-readable label, surfaced in logs so users can tell heuristic from exact counts. */
    def label: String
}

/**
 * Default counter. Works for every provider because it depends on no external
 * tokenizer — counts characters and divides by a configurable ratio.
 *
 * Defaults to 2.0 chars/token, which over-estimates token count on English
 * prose. That's the right direction for a safety net: better to over-split a
 * chunk that would have fit than to send one that wouldn't.
 */
final class HeuristicTokenCounter(ratio: Double) extends TokenCounter {
    /** Effective chars-per-token; exposed so split-mode can size character-boundary pieces. */
    val charsPerToken: Double = if (ratio <= 0.0) 2.0 else ratio
    override def count(text: String): Int =
        if (text == null || text.isEmpty) 0
        else Math.ceil(text.length / charsPerToken).toInt
    override val label: String = "heuristic"
}

object TokenCounterRegistry {
    /**
     * Pick a counter for `model`. Dispatch order:
     *   1. Explicit `tokenizerHint` ("openai" | "heuristic") wins if set.
     *   2. Model-name prefix match picks an exact tokenizer when one exists
     *      (currently: OpenAI families via jtokkit).
     *   3. Heuristic fallback otherwise — works for every provider.
     *
     * HuggingFace tokenizers (BGE, Nomic, E5, etc.) deliberately ship as a
     * future opt-in: their native binaries are ~30 MB. Users who want exact
     * HF counts today set `tokensPerCharRatio` and accept the heuristic.
     */
    def forModel(model: String, tokenizerHint: Option[String], ratio: Double): TokenCounter = {
        tokenizerHint.map(_.toLowerCase) match {
            case Some("openai")    => new OpenAITokenCounter(model)
            case Some("heuristic") => new HeuristicTokenCounter(ratio)
            case Some(other)       =>
                throw new DatrisException("Unknown tokenizer hint: '" + other + "'. Valid: openai, heuristic")
            case None if OpenAITokenCounter.matches(model) => new OpenAITokenCounter(model)
            case None              => new HeuristicTokenCounter(ratio)
        }
    }
}

object TokenGuard {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    // Anything beyond this multiple of the cap is treated as runaway input. In
    // truncate mode we hard-cut to this ceiling before further work; in split
    // mode we limit how many sub-chunks one input can produce.
    private val OuterCeilingMultiplier = 100

    sealed trait Mode
    object Mode {
        case object Truncate extends Mode
        case object Fail extends Mode
        case object Split extends Mode

        def parse(s: String): Mode = Option(s).map(_.trim.toLowerCase).getOrElse("") match {
            case "" | "split"   => Split
            case "truncate"     => Truncate
            case "fail"         => Fail
            case other          => throw new DatrisException(
                "Unknown TokenGuard oversize mode: '" + other + "'. Valid: truncate, fail, split"
            )
        }
    }

    /**
     * Bring a list of texts under the per-call token cap. Returns texts in the
     * same order; `Truncate` and `Fail` preserve cardinality, `Split` may
     * return more entries than the input. Callers must pair the returned
     * texts back to embeddings themselves — never assume same-index mapping.
     */
    def fitChunks(
        texts: List[String],
        counter: TokenCounter,
        capTokens: Int,
        mode: Mode,
        modelForLogging: String
    ): List[String] = {
        if (texts == null || texts.isEmpty) return texts
        if (capTokens <= 0)
            throw new DatrisException("TokenGuard cap must be > 0, got: " + capTokens)

        var rewrites = 0

        val outerCeilingChars = capTokens.toLong * OuterCeilingMultiplier * 8L

        val out = texts.zipWithIndex.flatMap { case (text, idx) =>
            if (text == null || text.isEmpty) List(text)
            else {
                val tokens = counter.count(text)
                if (tokens <= capTokens) List(text)
                else {
                    rewrites += 1
                    mode match {
                        case Mode.Fail =>
                            throw new DatrisException(
                                "TokenGuard [model=" + modelForLogging + ", counter=" + counter.label +
                                    "]: chunk " + idx + " exceeded limit (" + tokens +
                                    " tokens > " + capTokens + " cap) and oversize=fail"
                            )
                        case Mode.Truncate =>
                            val truncated = truncateToCap(text, counter, capTokens, outerCeilingChars)
                            logger.info(
                                "TokenGuard [model=" + modelForLogging + ", counter=" + counter.label +
                                    "]: chunk " + idx + " exceeded limit (" + tokenLabel(counter) + tokens +
                                    " tokens > " + capTokens + " cap), truncated to " +
                                    tokenLabel(counter) + counter.count(truncated) + " tokens"
                            )
                            List(truncated)
                        case Mode.Split =>
                            val parts = splitToCap(text, counter, capTokens, outerCeilingChars)
                            logger.info(
                                "TokenGuard [model=" + modelForLogging + ", counter=" + counter.label +
                                    "]: chunk " + idx + " exceeded limit (" + tokenLabel(counter) + tokens +
                                    " tokens > " + capTokens + " cap), split into " + parts.size + " sub-chunks"
                            )
                            parts
                    }
                }
            }
        }

        if (rewrites > 0) {
            logger.warn(
                "TokenGuard: " + rewrites + " of " + texts.size +
                    " chunks exceeded the limit (model=" + modelForLogging +
                    ", counter=" + counter.label + ", cap=" + capTokens +
                    " tokens). Consider lowering chunkSize or setting maxChunkTokens " +
                    "on ChunkingConfig to prevent oversized chunks at the source."
            )
        }

        out
    }

    private def tokenLabel(counter: TokenCounter): String =
        if (counter.supportsExactSplit) "" else "est. "

    /**
     * Heuristic-aware truncation: shrinks `text` until the counter estimates it
     * fits under the cap. Single-pass with a small back-off loop to handle
     * heuristic underestimates near the boundary. With an exact tokenizer,
     * uses encode/decode for a lossless trim.
     */
    private def truncateToCap(text: String, counter: TokenCounter, capTokens: Int, outerCeilingChars: Long): String = {
        if (counter.supportsExactSplit) {
            val tokens = counter.encode(text)
            if (tokens.length <= capTokens) text
            else counter.decode(tokens.take(capTokens))
        } else {
            heuristicTruncate(text, counter, capTokens, outerCeilingChars)
        }
    }

    private def heuristicTruncate(text: String, counter: TokenCounter, capTokens: Int, outerCeilingChars: Long): String = {
        var current =
            if (text.length.toLong > outerCeilingChars) text.substring(0, outerCeilingChars.toInt)
            else text
        var attempts = 0
        while (counter.count(current) > capTokens && current.length > 1 && attempts < 8) {
            val estimated = Math.max(1, counter.count(current))
            val ratio = (capTokens.toDouble / estimated.toDouble) * 0.95
            val newLen = Math.max(1, (current.length * ratio).toInt)
            current = current.substring(0, Math.min(newLen, current.length))
            attempts += 1
        }
        if (counter.count(current) > capTokens) {
            val safeLen = Math.max(1, (capTokens * 0.5).toInt)
            current = current.substring(0, Math.min(safeLen, current.length))
        }
        current
    }

    /**
     * Split a single oversized text into sub-chunks each under the cap. With an
     * exact tokenizer, slices on token boundaries (lossless). Without one,
     * slices on character boundaries sized by the heuristic, with a small
     * overlap to absorb boundary loss.
     */
    private def splitToCap(text: String, counter: TokenCounter, capTokens: Int, outerCeilingChars: Long): List[String] = {
        if (text == null || text.isEmpty) return List(text)

        if (counter.supportsExactSplit) {
            splitExact(text, counter, capTokens)
        } else {
            splitHeuristic(text, counter, capTokens, outerCeilingChars)
        }
    }

    private def splitExact(text: String, counter: TokenCounter, capTokens: Int): List[String] = {
        val tokens = counter.encode(text)
        if (tokens.length <= capTokens) return List(text)

        // 10% overlap, cap at capTokens/2 to avoid pathological behavior at tiny caps.
        val overlap = Math.min(capTokens / 2, Math.max(1, capTokens / 10))
        val stride = Math.max(1, capTokens - overlap)
        val maxParts = OuterCeilingMultiplier

        val buf = scala.collection.mutable.ListBuffer[String]()
        var start = 0
        while (start < tokens.length && buf.size < maxParts) {
            val end = Math.min(start + capTokens, tokens.length)
            buf += counter.decode(java.util.Arrays.copyOfRange(tokens, start, end))
            if (end >= tokens.length) start = tokens.length
            else start += stride
        }
        if (buf.size >= maxParts && start < tokens.length) {
            logger.warn("TokenGuard: split hit OuterCeilingMultiplier (" + maxParts +
                " sub-chunks), discarding remainder beyond ~" + (maxParts.toLong * capTokens) + " tokens")
        }
        buf.toList
    }

    private def splitHeuristic(text: String, counter: TokenCounter, capTokens: Int, outerCeilingChars: Long): List[String] = {
        val ratio = counter match {
            case h: HeuristicTokenCounter => h.charsPerToken
            case _                        => 2.0
        }
        // 90% safety so we don't ride the boundary; the back-off loop below catches misses.
        val targetChars = Math.max(1, (capTokens * ratio * 0.90).toInt)
        val overlapChars = Math.max(0, (targetChars * 0.10).toInt)
        val stride = Math.max(1, targetChars - overlapChars)

        val end = Math.min(text.length.toLong, outerCeilingChars).toInt
        val maxParts = OuterCeilingMultiplier

        val buf = scala.collection.mutable.ListBuffer[String]()
        var start = 0
        while (start < end && buf.size < maxParts) {
            val pieceEnd = Math.min(start + targetChars, end)
            var piece = text.substring(start, pieceEnd)
            if (counter.count(piece) > capTokens) {
                piece = heuristicTruncate(piece, counter, capTokens, outerCeilingChars)
            }
            buf += piece
            if (pieceEnd >= end) start = end
            else start += stride
        }
        if (buf.isEmpty) List(text) else buf.toList
    }
}
