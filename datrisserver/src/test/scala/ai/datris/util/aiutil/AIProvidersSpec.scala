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

    test("rejectsSamplingParams fires on OpenRouter namespaced ids") {
        assert(AIProviders.rejectsSamplingParams("anthropic/claude-opus-5"))
        assert(AIProviders.rejectsSamplingParams("anthropic/claude-sonnet-5"))
        assert(!AIProviders.rejectsSamplingParams("openai/gpt-5.5"))
    }

    // ---- addTokenLimit ----

    test("addTokenLimit: openrouter always uses max_tokens, even for GPT-5-family ids") {
        val obj = new com.google.gson.JsonObject()
        AIProviders.addTokenLimit(obj, "openrouter", "openai/gpt-5.5", 4096)
        assert(obj.has("max_tokens"))
        assert(!obj.has("max_completion_tokens"))
    }

    test("addTokenLimit: direct openai GPT-5 still uses max_completion_tokens") {
        val obj = new com.google.gson.JsonObject()
        AIProviders.addTokenLimit(obj, "openai", "gpt-5.5", 4096)
        assert(obj.has("max_completion_tokens"))
        assert(!obj.has("max_tokens"))
    }

    // ---- usesResponsesApi ----

    test("usesResponsesApi is false for openrouter even with codex model ids") {
        val cfg = ai.datris.model.AIConfig(
            provider = "openrouter",
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            model = "openai/gpt-5-codex",
            apiKey = ""
        )
        assert(!AIProviders.usesResponsesApi(cfg))
    }

    // ---- defaultEndpointFor ----

    test("defaultEndpointFor knows openrouter") {
        assert(AIProviders.defaultEndpointFor("openrouter") == "https://openrouter.ai/api/v1/chat/completions")
    }
}
