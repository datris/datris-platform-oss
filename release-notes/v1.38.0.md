# Release Notes

## v1.38.0 — September 25, 2026

**Change your platform's settings by asking: every change waits for your Confirm, and secrets never touch the chat.**

- **A Configuration assistant.** The Configuration tab has an Ask panel at its right edge that reads and changes what the sub-tabs cover: AI providers, secrets, data sources, the code repository, users, API keys and agent policy. It proposes one change at a time on a card, and nothing is written until you click Confirm; a confirmation works once and expires after ten minutes. Credentials are entered in a masked form on the card, never in the conversation. A new user's temporary password or a new API key is shown once on the card, with a copy button. The panel is for admins, needs a signed-in session when user auth is on, and is not shown on trial installs.
- **Ask the audit log.** Ask the assistant who changed what and when in plain language, and it answers from the audit entries themselves.
- **Doctor from chat.** Ask the assistant to run the health checks, optionally with a test request to each AI provider, and it summarizes what needs attention.
- **Deep links to Code Repository.** A link that opens Configuration on the Code Repository sub-tab now lands there instead of on the default sub-tab.
- **Changes made through the assistant are marked in the audit log.** With user auth on, changes made through the assistant are attributed to the signed-in admin and marked `via: config-chat` in the audit log.

- **More chats at once.** Several assistant, Ops, Catalog, Search or Configuration chats can stream at the same time without one of them failing on a connection-pool timeout. The connection limits are now configurable through two optional settings, documented in the configuration reference.

**Upgrading**

Run `datris doctor --pre-upgrade`, then `docker compose pull && docker compose up -d --force-recreate`. Both the server and the UI changed, so update all images together. No configuration changes are needed. With API keys and user auth on, the Configuration assistant is available in the UI only; API-key clients such as the CLI and MCP agents cannot use it.
