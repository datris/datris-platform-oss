# Release Notes

## v1.28.1 — September 8, 2026

**Security and documentation maintenance release.**

- **Security update.** The embedded web server that serves the API is updated to close three recently disclosed critical advisories affecting authentication and authorization handling. The UI and tap-runner container images also pick up the latest operating-system package security fixes. No functional changes.
- **Hardening switches now take effect.** `DATRIS_ENV=production` (require TLS to external Postgres destinations), `DATRIS_ALLOW_PLAINTEXT_DB`, `DATRIS_ALLOW_PRIVATE_EGRESS` (let HTTP taps and REST endpoints reach private-network addresses) and `TAP_MAX_OUTPUT_MB` were described in `.env.example` but were not passed through to the server by the bundled Compose files, so setting them had no effect. They are now honored and documented in the Configuration Reference.
- **Documentation accuracy pass.** Every page on docs.datris.ai was checked against the current release and corrected: MCP tool list and count, CLI flags and authentication, Assistant model defaults and limits, tap runtime behaviour, ingestion and destination options, data-quality behaviour, AI configuration, the API reference and OpenAPI spec, and the examples. Historical changelog entries were tidied to user-facing outcomes.
- **Examples.** Two examples that depended on files no longer in the repository were removed.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required. CLI users on pip or Homebrew: upgrade to 1.28.1.
