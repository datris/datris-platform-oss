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

    // ---- addTokenLimit ----

    test("addTokenLimit: azure always uses max_completion_tokens, regardless of deployment name") {
        val deployments = List("gpt-5-2", "prod-chat", "o4-mini", "my-deployment")
        deployments.foreach { d =>
            val obj = new com.google.gson.JsonObject()
            AIProviders.addTokenLimit(obj, "azure", d, 4096)
            assert(obj.has("max_completion_tokens"), s"deployment $d should get max_completion_tokens")
            assert(!obj.has("max_tokens"), s"deployment $d should not get max_tokens")
        }
    }

    test("addTokenLimit: direct openai keeps the model-prefix heuristic") {
        val gpt5 = new com.google.gson.JsonObject()
        AIProviders.addTokenLimit(gpt5, "openai", "gpt-5.5", 4096)
        assert(gpt5.has("max_completion_tokens"))
        val legacy = new com.google.gson.JsonObject()
        AIProviders.addTokenLimit(legacy, "openai", "gpt-4o", 4096)
        assert(legacy.has("max_tokens"))
    }

    test("addTokenLimit: anthropic uses max_tokens") {
        val obj = new com.google.gson.JsonObject()
        AIProviders.addTokenLimit(obj, "anthropic", "claude-opus-5", 4096)
        assert(obj.has("max_tokens"))
    }

    // ---- usesResponsesApi ----

    test("usesResponsesApi is false for azure even with codex in the deployment name") {
        val cfg = ai.datris.model.AIConfig(
            provider = "azure",
            endpoint = "https://myres.openai.azure.com/openai/v1/chat/completions",
            model = "gpt-5-codex",
            apiKey = ""
        )
        assert(!AIProviders.usesResponsesApi(cfg))
    }

    test("usesResponsesApi is false for azure even when the endpoint mentions /v1/responses") {
        val cfg = ai.datris.model.AIConfig(
            provider = "azure",
            endpoint = "https://myres.openai.azure.com/openai/v1/responses",
            model = "gpt-5-2",
            apiKey = ""
        )
        assert(!AIProviders.usesResponsesApi(cfg))
    }

    // ---- key store / defaults ----

    test("providerKeyFromStore field mapping ignores unknown providers") {
        // azure resolves via the azureApiKey field; a missing store returns ""
        // (no Vault in unit tests, so all we can assert is the no-throw contract)
        assert(AIProviders.providerKeyFromStore("oss", "does-not-exist") == "")
    }

    test("defaultEndpointFor has no default for azure (endpoint embeds the resource name)") {
        assert(AIProviders.defaultEndpointFor("azure") == "")
    }

    test("defaultModelFor has no default for azure (model is the deployment name)") {
        assert(AIProviders.defaultModelFor("azure") == "")
    }
}
