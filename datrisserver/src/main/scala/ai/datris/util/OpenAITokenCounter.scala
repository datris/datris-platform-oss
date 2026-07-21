package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.{Encoding, EncodingType, IntArrayList}

/**
 * Exact token counts for OpenAI tokenizer families via jtokkit. Replaces the
 * heuristic when the embedding secret's `model` matches an OpenAI prefix —
 * cl100k_base for text-embedding-3-* and ada-002, o200k_base for gpt-4o, etc.
 *
 * encode/decode let `TokenGuard.Mode.Split` do lossless token-boundary slicing
 * rather than character-boundary fallback.
 */
final class OpenAITokenCounter(modelName: String) extends TokenCounter {

    private val encoding: Encoding = OpenAITokenCounter.encodingFor(modelName)

    override def count(text: String): Int =
        if (text == null || text.isEmpty) 0
        else encoding.countTokens(text)

    override def encode(text: String): Array[Int] = {
        if (text == null || text.isEmpty) Array.emptyIntArray
        else encoding.encode(text).toArray
    }

    override def decode(tokens: Array[Int]): String = {
        if (tokens == null || tokens.isEmpty) ""
        else {
            val list = new IntArrayList(tokens.length)
            var i = 0
            while (i < tokens.length) { list.add(tokens(i)); i += 1 }
            encoding.decode(list)
        }
    }

    override def supportsExactSplit: Boolean = true

    override val label: String = "openai:" + OpenAITokenCounter.encodingName(modelName)
}

object OpenAITokenCounter {
    // Single shared registry — jtokkit recommends one for the process.
    private lazy val registry = Encodings.newDefaultEncodingRegistry()

    /** True when the model name matches a known OpenAI tokenizer family. */
    def matches(model: String): Boolean = {
        val m = Option(model).getOrElse("").toLowerCase
        m.startsWith("text-embedding-3") ||
        m.startsWith("text-embedding-ada") ||
        m.startsWith("gpt-") ||
        m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")
    }

    def encodingFor(model: String): Encoding = {
        val t = encodingType(model)
        registry.getEncoding(t)
    }

    def encodingName(model: String): String = encodingType(model).getName

    private def encodingType(model: String): EncodingType = {
        val m = Option(model).getOrElse("").toLowerCase
        if (m.startsWith("gpt-4o") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4"))
            EncodingType.O200K_BASE
        else if (m.startsWith("text-embedding-3") || m.startsWith("text-embedding-ada") || m.startsWith("gpt-"))
            EncodingType.CL100K_BASE
        else
            EncodingType.CL100K_BASE
    }
}
