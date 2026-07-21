package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{ChunkingConfig, DatrisException}

object ChunkUtil {

    def chunk(text: String, config: ChunkingConfig): List[String] = {
        if (text == null || text.isEmpty) return List.empty

        val raw = config.strategy.toLowerCase match {
            case "none" => List(text)
            case "fixed" => fixedChunk(text, config.chunkSize, config.chunkOverlap)
            case "sentence" => sentenceChunk(text, config.chunkSize, config.chunkOverlap)
            case "paragraph" => paragraphChunk(text, config.chunkSize, config.chunkOverlap)
            case "recursive" => recursiveChunk(text, config.chunkSize, config.chunkOverlap)
            case other => throw new DatrisException("Unknown chunking strategy: " + other + ". Valid strategies: none, fixed, sentence, paragraph, recursive")
        }

        // Token-aware safety pass. When maxChunkTokens is set, anything the
        // strategy emits that exceeds the token cap gets split here so the
        // embedding-side TokenGuard becomes a true safety net rather than the
        // primary defender. Uses the heuristic counter — exact tokenizers can
        // be plugged in later via TokenCounterRegistry.
        if (config.maxChunkTokens > 0) {
            val counter = new HeuristicTokenCounter(config.tokensPerCharRatio)
            raw.flatMap(splitByTokens(_, counter, config.maxChunkTokens, config.chunkOverlap))
        } else raw
    }

    private def fixedChunk(text: String, chunkSize: Int, chunkOverlap: Int): List[String] = {
        if (text.length <= chunkSize) return List(text)

        val chunks = scala.collection.mutable.ListBuffer[String]()
        var start = 0
        while (start < text.length) {
            val end = Math.min(start + chunkSize, text.length)
            chunks += text.substring(start, end)
            start += chunkSize - chunkOverlap
        }
        chunks.toList
    }

    private def sentenceChunk(text: String, chunkSize: Int, chunkOverlap: Int): List[String] = {
        val sentences = text.split("(?<=[.!?])\\s+").toList
        mergeIntoChunks(sentences, chunkSize, chunkOverlap)
    }

    private def paragraphChunk(text: String, chunkSize: Int, chunkOverlap: Int): List[String] = {
        val paragraphs = text.split("\n\n+").toList.filter(_.nonEmpty)
        mergeIntoChunks(paragraphs, chunkSize, chunkOverlap)
    }

    private def recursiveChunk(text: String, chunkSize: Int, chunkOverlap: Int): List[String] = {
        if (text.length <= chunkSize) return List(text)

        // Try separators in order: double newline, newline, sentence end, space
        val separators = List("\n\n", "\n", "(?<=[.!?])\\s+", " ")

        for (sep <- separators) {
            val parts = text.split(sep).toList.filter(_.nonEmpty)
            if (parts.size > 1) {
                val merged = mergeIntoChunks(parts, chunkSize, chunkOverlap)
                if (merged.nonEmpty) return merged
            }
        }

        // Fallback to fixed-size if no separator works
        fixedChunk(text, chunkSize, chunkOverlap)
    }

    private def mergeIntoChunks(segments: List[String], chunkSize: Int, chunkOverlap: Int): List[String] = {
        if (segments.isEmpty) return List.empty

        val chunks = scala.collection.mutable.ListBuffer[String]()
        var current = new StringBuilder()
        var overlapBuffer = scala.collection.mutable.ListBuffer[String]()

        for (segment <- segments) {
            if (current.isEmpty) {
                current.append(segment)
            } else if (current.length + 1 + segment.length <= chunkSize) {
                current.append(" ").append(segment)
            } else {
                chunks += current.toString()
                // Start new chunk with overlap from recent segments
                current = new StringBuilder()
                for (ob <- overlapBuffer) {
                    if (current.length + 1 + ob.length <= chunkOverlap) {
                        if (current.nonEmpty) current.append(" ")
                        current.append(ob)
                    }
                }
                if (current.nonEmpty) current.append(" ")
                current.append(segment)
                overlapBuffer.clear()
            }
            overlapBuffer += segment
            // Keep overlap buffer within bounds
            while (overlapBuffer.map(_.length).sum > chunkOverlap && overlapBuffer.size > 1) {
                overlapBuffer.remove(0)
            }
        }

        if (current.nonEmpty) chunks += current.toString()
        chunks.toList
    }

    /**
     * Token-aware split: if `text` exceeds `maxTokens` by the heuristic count,
     * slice it into pieces sized to fit under the cap, with `chunkOverlap`
     * char overlap to preserve context across the boundary.
     */
    private def splitByTokens(text: String, counter: HeuristicTokenCounter, maxTokens: Int, overlapChars: Int): List[String] = {
        if (text == null || text.isEmpty) return List(text)
        if (counter.count(text) <= maxTokens) return List(text)

        val targetChars = Math.max(1, (maxTokens * counter.charsPerToken * 0.90).toInt)
        val effOverlap = Math.max(0, Math.min(overlapChars, targetChars - 1))
        val stride = Math.max(1, targetChars - effOverlap)

        val buf = scala.collection.mutable.ListBuffer[String]()
        var start = 0
        while (start < text.length) {
            val end = Math.min(start + targetChars, text.length)
            var piece = text.substring(start, end)
            // Heuristic-miss back-off: if a piece still over-counts, shrink it.
            while (counter.count(piece) > maxTokens && piece.length > 1) {
                piece = piece.substring(0, Math.max(1, (piece.length * 0.9).toInt))
            }
            buf += piece
            if (end >= text.length) start = text.length
            else start += stride
        }
        buf.toList
    }
}
