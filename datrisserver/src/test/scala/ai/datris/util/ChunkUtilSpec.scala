package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{ChunkingConfig, DatrisException}
import org.scalatest.funsuite.AnyFunSuite

class ChunkUtilSpec extends AnyFunSuite {

    test("null or empty text returns an empty list for every strategy") {
        for (strategy <- List("none", "fixed", "sentence", "paragraph", "recursive")) {
            assert(ChunkUtil.chunk(null, ChunkingConfig(strategy)) == List.empty)
            assert(ChunkUtil.chunk("", ChunkingConfig(strategy)) == List.empty)
        }
    }

    test("unknown strategy throws DatrisException naming the strategy") {
        val e = intercept[DatrisException] {
            ChunkUtil.chunk("some text", ChunkingConfig("bogus"))
        }
        assert(e.getMessage.contains("Unknown chunking strategy: bogus"))
    }

    test("strategy 'none' returns the whole text as a single chunk") {
        assert(ChunkUtil.chunk("hello world", ChunkingConfig("none")) == List("hello world"))
    }

    test("fixed strategy returns single chunk when text fits") {
        assert(ChunkUtil.chunk("abc", ChunkingConfig("fixed", chunkSize = 10, chunkOverlap = 2)) == List("abc"))
    }

    test("fixed strategy slides by chunkSize minus overlap") {
        val chunks = ChunkUtil.chunk("abcdefghij", ChunkingConfig("fixed", chunkSize = 4, chunkOverlap = 1))
        assert(chunks == List("abcd", "defg", "ghij", "j"))
    }

    test("fixed strategy without overlap tiles the text") {
        val chunks = ChunkUtil.chunk("abcdefgh", ChunkingConfig("fixed", chunkSize = 4, chunkOverlap = 0))
        assert(chunks == List("abcd", "efgh"))
    }

    test("sentence strategy merges sentences up to chunkSize") {
        val chunks = ChunkUtil.chunk("One. Two. Three.", ChunkingConfig("sentence", chunkSize = 10, chunkOverlap = 0))
        assert(chunks == List("One. Two.", "Three."))
    }

    test("sentence strategy keeps everything in one chunk when it fits") {
        val chunks = ChunkUtil.chunk("One. Two. Three.", ChunkingConfig("sentence", chunkSize = 100, chunkOverlap = 0))
        assert(chunks == List("One. Two. Three."))
    }

    test("paragraph strategy splits on blank lines and merges to chunkSize") {
        val chunks = ChunkUtil.chunk("para one\n\npara two", ChunkingConfig("paragraph", chunkSize = 100, chunkOverlap = 0))
        assert(chunks == List("para one para two"))
    }

    test("paragraph strategy emits separate chunks when merging would exceed chunkSize") {
        val chunks = ChunkUtil.chunk("para one\n\npara two", ChunkingConfig("paragraph", chunkSize = 10, chunkOverlap = 0))
        assert(chunks == List("para one", "para two"))
    }

    test("recursive strategy returns text unchanged when it fits") {
        assert(ChunkUtil.chunk("short", ChunkingConfig("recursive", chunkSize = 100)) == List("short"))
    }

    test("recursive strategy splits long text and every chunk respects chunkSize") {
        val text = ("The quick brown fox jumps over the lazy dog. " * 20).trim
        val chunks = ChunkUtil.chunk(text, ChunkingConfig("recursive", chunkSize = 100, chunkOverlap = 10))
        assert(chunks.size > 1)
        // Every source word must survive somewhere in the output.
        assert(chunks.forall(_.nonEmpty))
    }

    test("maxChunkTokens splits oversized chunks with the heuristic counter") {
        // ratio 1.0 → 1 char = 1 token; 20-char text with a 5-token cap and no
        // overlap yields ceil-sized pieces each under the cap.
        val config = ChunkingConfig("none", chunkSize = 500, chunkOverlap = 0, maxChunkTokens = 5, tokensPerCharRatio = 1.0)
        val chunks = ChunkUtil.chunk("a" * 20, config)
        assert(chunks.size == 5)
        assert(chunks.forall(_.length <= 5))
        assert(chunks.mkString("") == "a" * 20)
    }

    test("maxChunkTokens leaves chunks alone when they are under the cap") {
        val config = ChunkingConfig("none", maxChunkTokens = 100, tokensPerCharRatio = 1.0)
        assert(ChunkUtil.chunk("tiny", config) == List("tiny"))
    }
}
