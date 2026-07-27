package ai.datris.util.aiutil

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

class AIProvidersSpec extends AnyFunSuite {

    // ---- rejectsSamplingParams ----

    test("adaptive-only Anthropic models reject sampling params") {
        val rejecting = List(
            "claude-fable-5",
            "claude-mythos-5",
            "claude-opus-4-7",
            "claude-opus-4-8",
            "claude-opus-5",
            "claude-sonnet-5"
        )
        rejecting.foreach { m =>
            assert(AIProviders.rejectsSamplingParams(m), s"$m should reject sampling params")
        }
    }

    test("older Anthropic models still accept sampling params") {
        val accepting = List(
            "claude-opus-4-6",
            "claude-opus-4-5-20251101",
            "claude-sonnet-4-6",
            "claude-sonnet-4-5-20250929",
            "claude-haiku-4-5"
        )
        accepting.foreach { m =>
            assert(!AIProviders.rejectsSamplingParams(m), s"$m should accept sampling params")
        }
    }

    test("rejectsSamplingParams: null and empty are safe") {
        assert(!AIProviders.rejectsSamplingParams(null))
        assert(!AIProviders.rejectsSamplingParams(""))
    }

    test("rejectsSamplingParams is case-insensitive") {
        assert(AIProviders.rejectsSamplingParams("Claude-Opus-5"))
    }
}
