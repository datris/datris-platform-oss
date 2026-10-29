# Release Notes

## v1.54.0 — October 29, 2026

**Datris is now licensed under Apache 2.0.**

- **Apache License 2.0.** From this release the platform, UI, MCP server, CLI and tap runner are released under the Apache License 2.0 instead of AGPL-3.0. You may run, modify, embed or host Datris without copyleft obligations; keep the LICENSE and NOTICE files with any copy you redistribute and mark the files you changed. Earlier releases remain under the license they shipped with. Commercial support subscriptions continue unchanged; see [Licensing & Support](https://docs.datris.ai/production/licensing-support).
- **CLI watch hint after a tap run.** `datris tap run` now points you at `datris status <pipeline>` to follow the resulting run, instead of a command that did not exist.
- **Documentation corrections.** Tool counts, the security overview (API-key storage, bootstrap admin password, single sign-on), the OpenAPI spec and several destination and ingestion pages now match current behaviour.

**Upgrading:** `docker compose pull && docker compose up -d`; all four images are rebuilt with the new license metadata but behave exactly as before. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
