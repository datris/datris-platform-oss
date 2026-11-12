# Release Notes

## v1.58.1 — November 12, 2026

**Claude Fable 5.2 is selectable, and an empty chat message gets a clear answer.**

- **Claude Fable 5.2 support.** Anthropic's newest model appears in Configuration → AI Providers for both the Assistant and code generation, on the direct Anthropic provider and on Amazon Bedrock. Claude Opus 5.5 stays the recommended model and the default on new installs, and no saved selection changes. Installs that can reach datris.ai see the new option without upgrading; this release adds it to the built-in list for installs that cannot. On Amazon Bedrock, Fable 5.2 needs the same one-time data-retention opt-in as the earlier Fable models.
- **Correct model names on Amazon Bedrock.** With live model discovery, Claude Fable 5.2 is listed under its own name instead of appearing as Claude Fable 5.
- **Empty chat messages are refused clearly.** Sending a message with no text to the Assistant or to the Operations, Configuration, Search or Catalog chat now returns a short message asking you to type something, instead of a provider error. A message with no text that carries an attached file still works.

**Upgrading:** `docker compose pull && docker compose up -d`; the server, UI and MCP server images changed. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
