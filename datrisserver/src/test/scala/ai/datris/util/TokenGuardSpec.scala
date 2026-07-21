package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

class HeuristicTokenCounterSpec extends AnyFunSuite {

    test("counts characters divided by the ratio, rounded up") {
        val counter = new HeuristicTokenCounter(2.0)
        assert(counter.count("abcd") == 2)
        assert(counter.count("abcde") == 3)
    }

    test("null and empty count as zero") {
        val counter = new HeuristicTokenCounter(2.0)
        assert(counter.count(null) == 0)
        assert(counter.count("") == 0)
    }

    test("non-positive ratio falls back to 2.0") {
        assert(new HeuristicTokenCounter(0.0).charsPerToken == 2.0)
        assert(new HeuristicTokenCounter(-1.0).charsPerToken == 2.0)
    }

    test("does not support exact split and encode throws") {
        val counter = new HeuristicTokenCounter(2.0)
        assert(!counter.supportsExactSplit)
        intercept[UnsupportedOperationException] { counter.encode("x") }
    }
}

class TokenGuardSpec extends AnyFunSuite {

    // ratio 1.0 → 1 char = 1 token, so caps read directly as char counts.
    private val counter = new HeuristicTokenCounter(1.0)

    test("Mode.parse: empty and null default to Split; names parse case-insensitively") {
        assert(TokenGuard.Mode.parse("") == TokenGuard.Mode.Split)
        assert(TokenGuard.Mode.parse(null) == TokenGuard.Mode.Split)
        assert(TokenGuard.Mode.parse("split") == TokenGuard.Mode.Split)
        assert(TokenGuard.Mode.parse("TRUNCATE") == TokenGuard.Mode.Truncate)
        assert(TokenGuard.Mode.parse(" fail ") == TokenGuard.Mode.Fail)
    }

    test("Mode.parse: unknown mode throws") {
        val e = intercept[DatrisException] { TokenGuard.Mode.parse("bogus") }
        assert(e.getMessage.contains("bogus"))
    }

    test("cap must be positive") {
        intercept[DatrisException] {
            TokenGuard.fitChunks(List("x"), counter, 0, TokenGuard.Mode.Split, "m")
        }
    }

    test("chunks under the cap pass through unchanged, order preserved") {
        val texts = List("aa", "bbb", "c")
        assert(TokenGuard.fitChunks(texts, counter, 10, TokenGuard.Mode.Split, "m") == texts)
    }

    test("null and empty entries are preserved") {
        val texts = List(null, "", "ok")
        assert(TokenGuard.fitChunks(texts, counter, 10, TokenGuard.Mode.Split, "m") == texts)
    }

    test("empty input returns as-is") {
        assert(TokenGuard.fitChunks(Nil, counter, 10, TokenGuard.Mode.Split, "m") == Nil)
    }

    test("Fail mode throws on the first oversized chunk, naming the index") {
        val e = intercept[DatrisException] {
            TokenGuard.fitChunks(List("ok", "x" * 20), counter, 10, TokenGuard.Mode.Fail, "my-model")
        }
        assert(e.getMessage.contains("chunk 1"))
        assert(e.getMessage.contains("my-model"))
    }

    test("Truncate mode preserves cardinality and brings every chunk under the cap") {
        val out = TokenGuard.fitChunks(List("x" * 25, "ok"), counter, 10, TokenGuard.Mode.Truncate, "m")
        assert(out.size == 2)
        assert(out.forall(t => counter.count(t) <= 10))
        assert(out(1) == "ok")
    }

    test("Split mode may grow the list; every piece is under the cap and no text is lost") {
        val text = "abcdefghijklmnopqrstuvwxy" // 25 chars
        val out = TokenGuard.fitChunks(List(text), counter, 10, TokenGuard.Mode.Split, "m")
        assert(out.size > 1)
        assert(out.forall(t => counter.count(t) <= 10))
        // char-boundary split with zero effective overlap at this size: pieces tile the text
        assert(out.mkString("") == text)
    }
}
