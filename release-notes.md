# Release Notes

## v1.24.1 — August 31, 2026

**Security update for the UI and MCP server images.**

- The `datris-ui` and `datris-mcp-server` container images ship with updated OpenSSL packages, picking up upstream fixes for several recently disclosed issues, including one rated high severity. No functional changes.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required.
