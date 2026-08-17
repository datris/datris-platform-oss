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

    test("addTokenLimit: bedrock uses max_tokens (Anthropic wire shape)") {
        val obj = new com.google.gson.JsonObject()
        AIProviders.addTokenLimit(obj, "bedrock", "anthropic.claude-sonnet-5", 4096)
        assert(obj.has("max_tokens"))
        assert(!obj.has("max_completion_tokens"))
    }

    // ---- bedrock / anthropic wire ----

    test("usesAnthropicWire covers anthropic and bedrock only") {
        assert(AIProviders.usesAnthropicWire("anthropic"))
        assert(AIProviders.usesAnthropicWire("bedrock"))
        assert(AIProviders.usesAnthropicWire("Bedrock"))
        assert(!AIProviders.usesAnthropicWire("openai"))
        assert(!AIProviders.usesAnthropicWire("azure"))
        assert(!AIProviders.usesAnthropicWire("ollama"))
        assert(!AIProviders.usesAnthropicWire(null))
    }

    test("supportsExtendedThinking is true for bedrock Claude") {
        val cfg = ai.datris.model.AIConfig(
            provider = "bedrock",
            endpoint = "",
            model = "anthropic.claude-sonnet-5",
            apiKey = ""
        )
        assert(AIProviders.supportsExtendedThinking(cfg))
    }

    test("rejectsSamplingParams fires on bedrock-prefixed and inference-profile model ids") {
        val rejecting = List(
            "anthropic.claude-opus-5",
            "anthropic.claude-sonnet-5",
            "anthropic.claude-fable-5",
            "us.anthropic.claude-opus-5",
            "us.anthropic.claude-sonnet-5"
        )
        rejecting.foreach { m =>
            assert(AIProviders.rejectsSamplingParams(m), s"$m should reject sampling params")
        }
        assert(!AIProviders.rejectsSamplingParams("anthropic.claude-haiku-4-5"))
    }

    test("usesResponsesApi is false for bedrock") {
        val cfg = ai.datris.model.AIConfig(
            provider = "bedrock",
            endpoint = "",
            model = "anthropic.claude-sonnet-5",
            apiKey = ""
        )
        assert(!AIProviders.usesResponsesApi(cfg))
    }

    test("defaultEndpointFor has no static default for bedrock (derived from region at call time)") {
        assert(AIProviders.defaultEndpointFor("bedrock") == "")
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

    // ---- grok (xAI) ----

    test("addTokenLimit: grok always uses max_tokens") {
        val models = List("grok-4.6", "grok-4.1-fast", "grok-code-fast-1")
        models.foreach { m =>
            val obj = new com.google.gson.JsonObject()
            AIProviders.addTokenLimit(obj, "grok", m, 4096)
            assert(obj.has("max_tokens"), s"model $m should get max_tokens")
            assert(!obj.has("max_completion_tokens"), s"model $m should not get max_completion_tokens")
        }
    }

    test("usesAnthropicWire is false for grok") {
        assert(!AIProviders.usesAnthropicWire("grok"))
        assert(!AIProviders.usesAnthropicWire("Grok"))
    }

    test("supportsExtendedThinking is false for grok (reasoning stays server-side)") {
        val cfg = ai.datris.model.AIConfig(
            provider = "grok",
            endpoint = "https://api.x.ai/v1/chat/completions",
            model = "grok-4.6",
            apiKey = ""
        )
        assert(!AIProviders.supportsExtendedThinking(cfg))
    }

    test("usesResponsesApi is false for grok even with codex in the model name or /v1/responses in the endpoint") {
        val codexModel = ai.datris.model.AIConfig(
            provider = "grok",
            endpoint = "https://api.x.ai/v1/chat/completions",
            model = "grok-codex-hypothetical",
            apiKey = ""
        )
        assert(!AIProviders.usesResponsesApi(codexModel))
        val responsesEndpoint = ai.datris.model.AIConfig(
            provider = "grok",
            endpoint = "https://api.x.ai/v1/responses",
            model = "grok-4.6",
            apiKey = ""
        )
        assert(!AIProviders.usesResponsesApi(responsesEndpoint))
    }

    test("rejectsSamplingParams is false for grok models") {
        assert(!AIProviders.rejectsSamplingParams("grok-4.6"))
        assert(!AIProviders.rejectsSamplingParams("grok-code-fast-1"))
    }

    test("defaultEndpointFor grok is the xAI chat/completions URL") {
        assert(AIProviders.defaultEndpointFor("grok") == "https://api.x.ai/v1/chat/completions")
    }
}
