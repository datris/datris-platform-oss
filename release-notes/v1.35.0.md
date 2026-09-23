# Release Notes

## v1.35.0 — September 21, 2026

**GPT-6 Astra support, and an Assistant that stays connected through long tool calls.**

- **GPT-6 Astra works everywhere on the OpenAI provider.** Pick or type `gpt-6-astra` for the Assistant or for code generation and it works for chat, tap script generation, AI data-quality rules, AI transformations, schema generation, brainstorming and natural-language queries. Previously every non-chat call to it failed. It appears in the model dropdowns; GPT-5.6 Sol remains the recommended OpenAI model.
- **OpenAI reasoning models show their reasoning.** Every Assistant turn on an OpenAI reasoning model now asks for a reasoning summary, so a thinking block appears where before there was none. Models or accounts that decline the summary fall back to the previous behaviour.
- **The Assistant no longer stops silently during long tool calls.** A tool call that ran for several minutes, such as testing a tap over a large source, could end the conversation mid-turn with no message. The connection now stays alive for as long as the work runs, and if it is ever cut, the chat says so instead of going quiet. The same applies to the Ops, Catalog and Search chats.
- **Tap scripts stream their sources instead of downloading them.** The AI helpers, the Assistant and the documentation now state that a tap has only a small amount of scratch space and must read a remote file directly rather than save it first, and that the size of a run should be estimated against the per-run disk budget before anything is built.

**Upgrading**

Run `datris doctor --pre-upgrade`, then `docker compose pull && docker compose up -d --force-recreate`. Update all images together, including the UI and the MCP server. No configuration changes are required.
