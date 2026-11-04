# Release Notes

## v1.55.0 — October 31, 2026

**The Assistant asks its questions on a card.**

- **Question cards in the Assistant.** When the Assistant needs answers before it can build something (which source, how often to run, where the data should land, which locations to cover), it now shows an inline question card instead of a numbered list. Each question gets its own tab: choices come with a one-line description and an **Other** box for your own answer, and open-ended questions show a text box. Picking a single choice moves to the next tab; **Submit** on the last tab posts the answers as your next message and the Assistant carries on. You can still ignore the card and type a reply, or click **Answer in chat instead**. The Ops, Configuration, Search and Catalog chats, the MCP server and the CLI are unchanged.
- **Security hardening of request authentication.** Request identity handling in the server was tightened so that a request is only ever authenticated by the credentials it presents. All users should upgrade.

**Upgrading:** `docker compose pull && docker compose up -d`; the server and UI images changed. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
