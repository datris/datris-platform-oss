# Release Notes

## v1.6.21 — May 11, 2026

**Web search for AI tap workflows, plus simpler MCP authentication.**

- **AI tap workflows can consult the live web.** When enabled in Configuration → AI Providers, tap brainstorm, dataset discovery, tap diagnosis, and tap auto-fix look up current API documentation, free-tier limits, current package names, and recent deprecation notices before recommending sources or generating fixes. Pick your web-search provider independently of AI Primary — the platform uses each provider's native search tool and routes accordingly. First-pass tap script generation stays fast and uses the model's training data only.
- **AI Configuration changes survive Docker restarts.** Saving from the Configuration UI now mirrors the relevant keys back to your `.env` file so changes aren't lost when the local Vault container restarts. Provider switches also clear any stale credential preserved from the prior provider, so a wrong-provider key can't silently 401 the next call.
- **Cleaner MCP authentication.** The bundled MCP server is now a transparent forwarder — each connecting agent provides its own API key per session and the MCP server passes it through to the Datris REST API on every tool call. The Configuration → Connect Your Agent panel generates the new configuration snippet automatically; paste your key from the Configuration UI into your agent's MCP config. Existing trial and managed-service users see no change in behavior.
- **Tap brainstorm asks about sources first.** When you describe data without naming where to fetch it from, the AI now lists 3-5 candidate sources (with free vs paid and key-required info) before drilling into parameters — instead of asking for filtering details up front.
- **Tap script generation is more resilient.** The platform now validates that a generated script actually defines a `fetch()` function before storing it, and the JSON extractor handles model responses that contain narrative braces (common when web search is enabled) without falling back to the raw-text path.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d`. No data migration needed.
- The `datris` CLI: `brew upgrade datris`.
- If you run the MCP server standalone outside Docker, the connection-target environment variable was renamed for consistency — see the [MCP server docs](https://docs.datris.ai/mcp-server) for the new variable name. No change is needed for the bundled Docker stack.

---

See [archived release notes](release-notes/) for prior versions.
