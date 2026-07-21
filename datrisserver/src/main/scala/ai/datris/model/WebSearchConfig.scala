package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Web search is its own independent service — same model as the Embedding
  * provider. Pick a provider (Anthropic or OpenAI) regardless of which provider
  * is doing the main AI work; the runtime either attaches the tool natively to
  * the main call (when providers match — fastest path) or makes a separate
  * out-of-band search call and injects the results as context. */
case class WebSearchConfig(
    enabled: Boolean,
    provider: String, // "anthropic" | "openai"
    endpoint: String, // e.g. https://api.anthropic.com/v1/messages or https://api.openai.com/v1/responses
    model: String, // model that runs the search call
    apiKey: String, // own key, copied from the matching ai-primary key on save (mirrors Embedding pattern)
    version: String = "", // optional Anthropic API version
    maxUses: Int = 3
)
