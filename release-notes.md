# Release Notes

## v1.7.0 — May 12, 2026

**A new in-product Assistant — chat your way from "I need data" to a working pipeline.**

- **The Assistant tab.** A new top-level tab opens an in-product agent that finds an external data source, builds the tap, creates the pipeline, runs it, and shows you the result — all in one chat. You watch the model's reasoning, every tool it calls, and the live status, then click straight into the tap or pipeline it created. No clicks through the wizard for the common path.
- **Real-time visibility while the agent works.** Streaming reasoning, inline tool cards with friendly labels (*"Searching the web for …"*, *"Creating tap …"*), live success/error status, and a Stop button that aborts the loop at the next checkpoint. Conversations survive navigating to other tabs and back.
- **Credentials never enter the chat.** When the agent needs an API key or other credentials, it opens an inline credentials form right in the chat. Values go straight to the vault — they don't appear in the conversation log, your screenshots, or the model's context. The form also lets you reuse an existing tap secret instead of creating a new one.
- **Pipelines can be edited in place.** Calling pipeline-create again with the same name now upserts the configuration without dropping the destination data. Two new knobs in the same call: a natural-key list for dedupe/upsert on every run, and a flag to wipe the destination before each run for full-snapshot workflows. Both work on PostgreSQL and MongoDB destinations.
- **External agents get the same new tools.** Claude Desktop, Cursor, and any other MCP client connected to your Datris server now see two new tools for discovering existing tap secrets (names and field shapes, never values) plus the new dedupe and reset knobs on pipeline create. Same canonical workflow guide as the in-product Assistant.
- **Pipeline delete now describes what it actually does.** The tool description used to claim it kept your destination data; it never has. The MCP tool description now correctly says it removes both the configuration *and* the destination data, with an explicit opt-in flag for the "keep the schema, clear the data" reset case.
- **MCP transport upgraded — both protocols at once.** The bundled MCP server now serves the streamable-HTTP transport alongside the existing SSE transport on the same port, so the in-product Assistant and external agents like Claude Desktop / Cursor connect to one running process. No configuration change needed.
- **Discovery tab is hidden in this release.** The Assistant supersedes the Discovery tab for the common path. The Discovery doc page stays available for reference.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d`. No data migration needed.
- The `datris` CLI: `brew upgrade datris`.
- The Assistant uses your existing codegen AI configuration (Configuration → AI Providers). On Anthropic tenants you'll see full chain-of-thought reasoning streamed inline; on OpenAI you'll see reasoning summaries instead.

---

See [archived release notes](release-notes/) for prior versions.
