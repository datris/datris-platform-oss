# Release Notes

## v1.20.1 — August 25, 2026

**MCP tool catalog now matches API-key permissions.**

- When API keys are enabled, each MCP session's tool list is filtered to what its key is actually allowed to do — agents no longer see (and plan around) tools that could only fail with a permission error at call time. Full-access keys and installs without API keys keep the complete catalog.
- The `rag-builder` key template gained the read permissions its workflow was missing: agents using it can now list taps and manage their own tap credentials end to end. Existing issued keys keep their original permissions — re-issue (or edit) rag-builder keys to pick up the additions.
- With API keys enabled, MCP sessions that present no key are now refused instead of silently connecting. Installs without API keys are unaffected.
- Permission denials now return a clear, structured error instead of a generic server error, so agents recognize a permission boundary rather than retrying or misreporting a server fault.
- Tightened API-key permission enforcement on the server.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No action needed for installs without API keys (the default). If API keys are enabled: ensure MCP clients send their key (header for remote connections, `DATRIS_API_KEY` environment variable for stdio), and re-issue rag-builder-template keys to pick up the new permissions.

---

## v1.20.0 — August 24, 2026

**Tap isolation on by default, opt-in Postgres TLS, Prometheus metrics + JSON logs, digest-pinned images + SBOMs.**

See the [full v1.20.0 notes](release-notes/v1.20.0.md) for details.

---

## v1.19.4 — August 21, 2026

**Dependency security cleanup across the server and UI.**

See the [full v1.19.4 notes](release-notes/v1.19.4.md) for details.

---

## v1.19.3 — August 21, 2026

**Server security updates and a friendlier vector-store default in the installer.**

See the [full v1.19.3 notes](release-notes/v1.19.3.md) for details.

---

## v1.19.2 — August 20, 2026

**Security updates across the UI and server, and a pipeline-delete fix.**

See the [full v1.19.2 notes](release-notes/v1.19.2.md) for details.
