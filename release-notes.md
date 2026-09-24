# Release Notes

## vNEXT — unreleased

**Change your platform's settings by asking: every change waits for your Confirm, and secrets never touch the chat.**

- **A Configuration assistant.** The Configuration tab has an Ask panel at its right edge that reads and changes what the sub-tabs cover: AI providers, secrets, data sources, the code repository, users, API keys and agent policy. It proposes one change at a time on a card, and nothing is written until you click Confirm; a confirmation works once and expires after ten minutes. Credentials are entered in a masked form on the card, never in the conversation. A new user's temporary password or a new API key is shown once on the card, with a copy button. The panel is for admins, needs a signed-in session when user auth is on, and is not shown on trial installs.
- **Ask the audit log.** Ask the assistant who changed what and when in plain language, and it answers from the audit entries themselves.
- **Doctor from chat.** Ask the assistant to run the health checks, optionally with a test request to each AI provider, and it summarizes what needs attention.
- **Deep links to Code Repository.** A link that opens Configuration on the Code Repository sub-tab now lands there instead of on the default sub-tab.
- **Changes made through the assistant are marked in the audit log.** With user auth on, changes made through the assistant are attributed to the signed-in admin and marked `via: config-chat` in the audit log.

**Upgrading**

Run `datris doctor --pre-upgrade`, then `docker compose pull && docker compose up -d --force-recreate`. Both the server and the UI changed, so update all images together. No configuration changes are needed. With API keys and user auth on, the Configuration assistant is available in the UI only; API-key clients such as the CLI and MCP agents cannot use it.

## v1.37.0 — September 24, 2026

**Agents get live, validated reads from any source, and only land what's worth keeping.**

- **The scratch destination is now called Live Read.** Same behaviour, same configuration; only the name changed in the wizard, the Pipelines list, the run detail page, the docs, the Assistant and the CLI. The first mention on each surface still says which JSON key it maps to, so nothing you have written needs to change.
- **The Assistant offers Live Read.** When it asks where data should go, it now names Live Read alongside the structured destinations, with a one-line description, and still never picks it unless you do. It leaves Live Read out when you have asked for a schedule.
- **The docs page moved.** Live Read has its own page under Destinations; the old address redirects.
- **The CLI signs in to the MCP server.** On installs with API keys turned on, every CLI command now sends the key you already export for `datris doctor`. A missing key gives a one-line message instead of a connection error, and a rejected key gives a one-line message and a non-zero exit instead of raw output and success. The query-string form still works.
- **A clean exit on every command.** The CLI no longer prints a stray traceback after a successful command, and commands that make several calls no longer reuse a stale connection.
- **MCP clients see the real version.** The MCP server now reports its own version instead of the library it is built on.
- **Claude Opus 5.5 and Grok 4.7.** Both are in the model catalog. Opus 5.5 is the recommended and first-boot default for code generation on Anthropic and Bedrock; Grok 4.7 is the recommended and first-boot default for Grok. The chat default is unchanged.

**Upgrading**

Run `datris doctor --pre-upgrade`, then `docker compose pull && docker compose up -d --force-recreate`. Update all images together, including the UI and the MCP server. Existing installs keep the models they already have; the new defaults apply only on first boot. If you use API keys and run the CLI, export `DATRIS_API_KEY` once and drop any `?api_key=` you had put on the MCP server address.
