# Release Notes

## v1.6.6 — April 18, 2026

- **Agents tab** — new live view of connected MCP agents. See every tool call as it happens, with agent name, arguments, record count, response size, status, and latency. Click any row to expand the full request and response.
- **Pipeline status** now self-heals when a job completes but the summary gets stuck showing "processing" — completed, warned, and errored jobs resolve to their correct final state.
- **Example agent** (market-macro-agent) automatically reconnects with backoff if the MCP connection drops, and degrades gracefully while offline instead of crashing.

---

See [archived release notes](release-notes/) for prior versions.
