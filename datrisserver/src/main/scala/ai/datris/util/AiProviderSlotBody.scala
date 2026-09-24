package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.aiutil.AIProviders
import com.google.gson.JsonObject

/** The AI slot secret body (`PUT /api/v1/secrets/{slot}`) the Configuration
  * tab builds in `ui/src/app/configuration/configuration.component.ts`
  * (`endpointFor`, `webSearchEndpointFor`, `webSearchModelFor`, `save()`).
  *
  * `apiKey` is always the mask: the server's masked-value merge keeps a
  * stored slot key or writes nothing, and provider keys resolve from the
  * shared `ai-keys` store at request time. */
object AiProviderSlotBody {

    val Mask: String = "••••••••"
    val AnthropicVersion: String = "2023-06-01"
    val WebSearchSlot: String = "web-search"
    val EmbeddingSlot: String = "embedding"

    /** UI `endpointFor(provider, 'chat')`. */
    private[util] def chatEndpointFor(provider: String): String = provider.toLowerCase match {
        case "anthropic" => "https://api.anthropic.com/v1/messages"
        case "openai" => "https://api.openai.com/v1/chat/completions"
        case "grok" => "https://api.x.ai/v1/chat/completions"
        case "ollama" => "http://host.docker.internal:11434/v1/chat/completions"
        case _ => ""
    }

    /** UI `endpointFor(provider, 'embedding')`. */
    private[util] def embeddingEndpointFor(provider: String): String = provider.toLowerCase match {
        case "openai" => "https://api.openai.com/v1/embeddings"
        case "tei" => "http://tei:80/v1/embeddings"
        case "ollama" => "http://ollama:11434/v1/embeddings"
        case "ollama-local" => "http://host.docker.internal:11434/v1/embeddings"
        case _ => ""
    }

    /** UI `webSearchEndpointFor(provider)`. */
    private[util] def webSearchEndpointFor(provider: String): String = provider.toLowerCase match {
        case "anthropic" => "https://api.anthropic.com/v1/messages"
        case "openai" => "https://api.openai.com/v1/responses"
        case _ => ""
    }

    def build(
        slot: String,
        provider: String,
        model: Option[String],
        endpoint: Option[String],
        enabled: Option[Boolean],
        maxUses: Option[Int]
    ): Either[String, JsonObject] = {
        val p = provider.trim.toLowerCase
        val webSearch = slot == WebSearchSlot
        val explicitEndpoint = endpoint.map(_.trim).filter(_.nonEmpty)
        if (p == "azure" && explicitEndpoint.isEmpty)
            return Left("provider azure needs an endpoint (https://<resource>.openai.azure.com/...)")
        val givenModel = model.map(_.trim).filter(_.nonEmpty)
        val resolvedModel: String = givenModel match {
            case Some(m) => m
            case None if webSearch => AIProviders.defaultModelFor(p)
            case None => return Left("a model is required for the " + slot + " slot")
        }
        val resolvedEndpoint = explicitEndpoint.getOrElse {
            if (webSearch) webSearchEndpointFor(p)
            else if (slot == EmbeddingSlot) embeddingEndpointFor(p)
            else chatEndpointFor(p)
        }
        val o = new JsonObject()
        o.addProperty("provider", p)
        o.addProperty("model", resolvedModel)
        o.addProperty("endpoint", resolvedEndpoint)
        o.addProperty("apiKey", Mask)
        if (p == "anthropic") o.addProperty("version", AnthropicVersion)
        if (webSearch) {
            o.addProperty("enabled", if (enabled.getOrElse(true)) "true" else "false")
            o.addProperty("maxUses", maxUses.filter(_ > 0).getOrElse(3).toString)
        }
        Right(o)
    }
}
