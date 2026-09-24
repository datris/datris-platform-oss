package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.aiutil.AIProviders
import com.google.gson.{JsonObject, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Configuration chat, tool implementations (plans/stories/config-chat-tools-and-prompt.md),
  * Step 3 `set_ai_provider_slot` and Step 6 `AiProviderSlotBodySpec`.
  *
  * `AiProviderSlotBody` is the Scala twin of the slot PUT body the
  * Configuration tab builds (ui/src/app/configuration/configuration.component.ts,
  * `endpointFor`, `webSearchEndpointFor`, `webSearchModelFor`, `save()`).
  *
  * Signature pinned by this spec (the story leaves it open):
  * {{{
  * object AiProviderSlotBody {
  *     def build(slot: String, provider: String, model: Option[String], endpoint: Option[String],
  *               enabled: Option[Boolean], maxUses: Option[Int]): Either[String, JsonObject]
  * }
  * }}}
  * `Left(message)` is the error the tool returns before any REST call.
  */
class AiProviderSlotBodySpec extends AnyFunSuite {

    private val Mask = "••••••••"

    private def obj(json: String): JsonObject = JsonParser.parseString(json).getAsJsonObject

    private def body(
        slot: String,
        provider: String,
        model: Option[String] = None,
        endpoint: Option[String] = None,
        enabled: Option[Boolean] = None,
        maxUses: Option[Int] = None
    ): JsonObject =
        AiProviderSlotBody.build(slot, provider, model, endpoint, enabled, maxUses) match {
            case Right(b) => b
            case Left(e) => fail(s"$slot/$provider unexpectedly failed: $e")
        }

    private def endpointOf(b: JsonObject): String = b.get("endpoint").getAsString

    // UI endpointFor(provider, 'chat') — configuration.component.ts
    private val UiChatEndpoints: Map[String, String] = Map(
        "anthropic" -> "https://api.anthropic.com/v1/messages",
        "openai" -> "https://api.openai.com/v1/chat/completions",
        "grok" -> "https://api.x.ai/v1/chat/completions",
        "ollama" -> "http://host.docker.internal:11434/v1/chat/completions",
        "bedrock" -> ""
    )

    // UI endpointFor(provider, 'embedding')
    private val UiEmbeddingEndpoints: Map[String, String] = Map(
        "openai" -> "https://api.openai.com/v1/embeddings",
        "ollama" -> "http://ollama:11434/v1/embeddings"
    )

    // UI webSearchEndpointFor(provider)
    private val UiWebSearchEndpoints: Map[String, String] = Map(
        "anthropic" -> "https://api.anthropic.com/v1/messages",
        "openai" -> "https://api.openai.com/v1/responses"
    )

    test("chat slots: endpoint falls back to the UI's chat table") {
        for (slot <- Seq("ai-primary", "codegen"); (provider, ep) <- UiChatEndpoints)
            assert(endpointOf(body(slot, provider, model = Some("m"))) == ep, s"$slot/$provider")
    }

    test("embedding slot: endpoint falls back to the UI's embedding table") {
        UiEmbeddingEndpoints.foreach { case (provider, ep) =>
            assert(endpointOf(body("embedding", provider, model = Some("m"))) == ep, provider)
        }
    }

    test("web-search slot: endpoint falls back to the UI's web-search table") {
        UiWebSearchEndpoints.foreach { case (provider, ep) =>
            assert(endpointOf(body("web-search", provider)) == ep, provider)
        }
    }

    test("an explicit endpoint is used as given") {
        assert(endpointOf(body("ai-primary", "openai", model = Some("m"), endpoint = Some("https://proxy.example/v1/chat"))) == "https://proxy.example/v1/chat")
        assert(endpointOf(body("ai-primary", "azure", model = Some("gpt-5.5"), endpoint = Some("https://r.openai.azure.com/openai/v1/responses"))) ==
            "https://r.openai.azure.com/openai/v1/responses")
    }

    test("codegen/anthropic: provider, model, endpoint, masked apiKey and version, nothing else") {
        assert(body("codegen", "anthropic", model = Some("claude-opus-5-5")) ==
            obj(
                s"""{"provider":"anthropic","model":"claude-opus-5-5","endpoint":"https://api.anthropic.com/v1/messages","apiKey":"$Mask","version":"2023-06-01"}"""
            ))
    }

    test("version is set only for anthropic") {
        assert(body("ai-primary", "anthropic", model = Some("m")).get("version").getAsString == "2023-06-01")
        assert(body("web-search", "anthropic").get("version").getAsString == "2023-06-01")
        assert(!body("ai-primary", "openai", model = Some("m")).has("version"))
        assert(!body("web-search", "openai").has("version"))
    }

    test("apiKey is the mask, never a real value") {
        Seq(("ai-primary", "openai"), ("codegen", "anthropic"), ("embedding", "openai"), ("web-search", "openai")).foreach { case (slot, p) =>
            assert(body(slot, p, model = Some("m")).get("apiKey").getAsString == Mask, s"$slot/$p")
        }
    }

    test("web-search defaults: enabled \"true\", maxUses \"3\", the provider's default model") {
        val b = body("web-search", "openai")
        assert(b == obj(
            s"""{"provider":"openai","model":"gpt-5.5","endpoint":"https://api.openai.com/v1/responses","apiKey":"$Mask","enabled":"true","maxUses":"3"}"""
        ))
        assert(b.get("enabled").getAsJsonPrimitive.isString)
        assert(b.get("maxUses").getAsJsonPrimitive.isString)
        assert(body("web-search", "anthropic").get("model").getAsString == "claude-sonnet-4-6")
    }

    test("web-search default model is AIProviders.defaultModelFor (widened to private[datris])") {
        Seq("anthropic", "openai").foreach { p =>
            assert(body("web-search", p).get("model").getAsString == AIProviders.defaultModelFor(p))
        }
        assert(AIProviders.defaultEndpointFor("anthropic") == UiWebSearchEndpoints("anthropic"))
    }

    test("web-search: enabled and max_uses given are sent as strings") {
        val b = body("web-search", "anthropic", model = Some("claude-fable-5-1"), enabled = Some(false), maxUses = Some(5))
        assert(b.get("enabled").getAsString == "false" && b.get("enabled").getAsJsonPrimitive.isString)
        assert(b.get("maxUses").getAsString == "5" && b.get("maxUses").getAsJsonPrimitive.isString)
        assert(b.get("model").getAsString == "claude-fable-5-1")
    }

    test("non-web-search slots carry no enabled or maxUses") {
        val b = body("ai-primary", "openai", model = Some("m"), enabled = Some(true), maxUses = Some(4))
        assert(!b.has("enabled") && !b.has("maxUses"))
    }

    test("azure without an endpoint is an error") {
        Seq("ai-primary", "codegen", "embedding", "web-search").foreach { slot =>
            assert(AiProviderSlotBody.build(slot, "azure", Some("gpt-5.5"), None, None, None).isLeft, slot)
            assert(AiProviderSlotBody.build(slot, "azure", Some("gpt-5.5"), Some("  "), None, None).isLeft, s"$slot blank endpoint")
        }
    }

    test("a non-web-search slot with no model is an error; web-search is not") {
        Seq("ai-primary", "codegen", "embedding").foreach { slot =>
            assert(AiProviderSlotBody.build(slot, "openai", None, None, None, None).isLeft, slot)
            assert(AiProviderSlotBody.build(slot, "openai", Some(""), None, None, None).isLeft, s"$slot empty model")
        }
        assert(AiProviderSlotBody.build("web-search", "openai", None, None, None, None).isRight)
    }
}
