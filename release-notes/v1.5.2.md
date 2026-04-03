# Release Notes

## v1.5.2 — April 3, 2026

### Remote MCP Endpoint — Managed Service Support

- **Per-session API key forwarding** — The MCP server now accepts `x-api-key` from connecting clients (via header or `api_key` query param) and forwards it per-request to the Datris API server. Enables multi-tenant SSE and streamable-HTTP connections where each client operates in their own tenant environment.
- **`REQUIRE_API_KEY` env var** — When set to `true`, the SSE and streamable-HTTP endpoints reject connections without a valid API key (returns 401).
- **`signup_trial` tool** — AI agents can sign up for a free 14-day trial directly via MCP. Returns an API key and MCP endpoint URL. No authentication required (bootstrap tool).
- **`upgrade_to_dedicated` tool** — Initiates upgrade from shared trial to a dedicated Datris instance. Returns a Stripe checkout URL for payment.
- **`check_upgrade_status` tool** — Monitors dedicated instance provisioning. Returns status, new MCP endpoint URL, and API key when ready.
- **Remote server registry entry** — `server.json` now includes a remote SSE entry for `https://mcp.trial.datris.ai/sse`, enabling discovery via MCP registries.
- **Website API key auth** — `/api/provision/status` and `/api/billing/checkout` now accept `x-api-key` header as an alternative to cookie JWT, enabling MCP tools to call them.
- **Agent trial endpoint** — New `/api/provision/agent-trial` combines signup + trial provisioning in a single call, with rate limiting (one trial per email domain per 30 days).

### Version

- Server: 1.5.2
- MCP Server: 1.5.2
- CLI: 1.5.2

---

## v1.5.1 — April 3, 2026

See [v1.5.1 release notes](release-notes/v1.5.1.md).

## v1.5.0 — April 2, 2026

See [v1.5.0 release notes](release-notes/v1.5.0.md).

## v1.4.4 — March 30, 2026

See [v1.4.4 release notes](release-notes/v1.4.4.md).

## v1.4.3 — March 29, 2026

See [v1.4.3 release notes](release-notes/v1.4.3.md).

## v1.4.2 — March 27, 2026

See [v1.4.2 release notes](release-notes/v1.4.2.md).

## v1.4.1 — March 26, 2026

See [v1.4.1 release notes](release-notes/v1.4.1.md).

## v1.4.0 — March 26, 2026

See [v1.4.0 release notes](release-notes/v1.4.0.md).
