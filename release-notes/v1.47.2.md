# Release Notes

## v1.47.2 — October 12, 2026

**See which AI model is answering.**

- **The Assistant names its model.** The top of the Assistant conversation now shows the chat provider and model in use (for example "Anthropic · claude-fable-5-1 · extended thinking on"), and the line stays visible after the first message. Administrators get a link to change it on Configuration → AI Providers; the line updates as soon as the configuration is saved.
- **Generated tap scripts say who wrote them.** When the Assistant or an agent generates a tap script, the result reports which CodeGen provider and model produced it, so you always know which model's output you are reviewing. The tap generation status also carries the provider and model from the first poll.
- **Consistent provider names.** The Assistant, tap generation and the Configuration tab now use the same display names for AI providers.

**Upgrading**

- The server, UI and MCP server images changed: run `docker compose pull && docker compose up -d`.
- No configuration changes are required. The additions are new fields only; existing clients and agents are unaffected.
