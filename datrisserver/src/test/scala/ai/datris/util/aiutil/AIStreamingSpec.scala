package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

class AIStreamingSpec extends AnyFunSuite {

    // The Responses request builder (openaiNonStreamingCallOnce) performs the
    // HTTP call inline, so it can't be exercised without a server. What is
    // testable is the retry matcher that decides whether a `reasoning` rejection
    // gets a second, reasoning-free attempt — now that we ask for
    // `reasoning.summary` as well as `reasoning.effort`, a model (or an
    // unverified org) that refuses only the summary must also be caught.

    test("isOpenAiReasoningError catches effort rejections") {
        assert(AIStreaming.isOpenAiReasoningError("Unknown parameter: 'reasoning'."))
        assert(AIStreaming.isOpenAiReasoningError("reasoning.effort is not supported on this model"))
        assert(AIStreaming.isOpenAiReasoningError("This model does not support reasoning"))
        assert(AIStreaming.isOpenAiReasoningError("Invalid value for 'reasoning.effort'"))
    }

    test("isOpenAiReasoningError catches summary rejections so the turn retries instead of failing") {
        assert(AIStreaming.isOpenAiReasoningError("Unknown parameter: 'reasoning.summary'."))
        assert(AIStreaming.isOpenAiReasoningError("Your organization must be verified to generate reasoning summaries."))
        assert(AIStreaming.isOpenAiReasoningError("summary is not supported with this model"))
    }

    test("isOpenAiReasoningError ignores null and unrelated errors") {
        assert(!AIStreaming.isOpenAiReasoningError(null))
        assert(!AIStreaming.isOpenAiReasoningError(""))
        assert(!AIStreaming.isOpenAiReasoningError("Rate limit reached for requests"))
        assert(!AIStreaming.isOpenAiReasoningError("Incorrect API key provided"))
        // Mentions reasoning but is not a parameter rejection — must not trigger
        // the reasoning-free retry.
        assert(!AIStreaming.isOpenAiReasoningError("The model produced a reasoning trace that was truncated"))
    }
}
