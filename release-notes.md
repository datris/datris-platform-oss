# Release Notes

## v1.6.0 — April 11, 2026

Dedicated instance support and hosted platform improvements.

### Configuration UI: hosted-aware

The Configuration tab adapts when running on a hosted instance:

- **AI Primary & CodeGen:** hides the Ollama option (not applicable on hosted — bundled Ollama handles embeddings only)
- **Embedding on hosted + Anthropic:** locked to "Ollama bge-m3 (bundled)" — no configuration needed
- **Embedding on hosted + OpenAI:** dropdown with OpenAI and bundled Ollama options
- **Advanced toggle:** hidden on hosted instances

### UI: improved multi-user session handling

The platform UI now handles user switching more reliably on shared instances, ensuring each user sees their own environment without manual intervention.

### Upgrading from v1.5.9

No configuration changes required. Pull the new images and restart:

```sh
docker compose pull datris ui mcp-server
docker compose up -d datris ui mcp-server
```

Self-hosted users: the `hosted` flag defaults to `false` — no action needed.

### Version

- Server: 1.6.0
- MCP Server: 1.6.0
- CLI: 1.6.0

---

## v1.5.9 — April 11, 2026

See [v1.5.9 release notes](release-notes/v1.5.9.md).

## v1.5.8 — April 10, 2026

See [v1.5.8 release notes](release-notes/v1.5.8.md).

## v1.5.7 — April 9, 2026

See [v1.5.7 release notes](release-notes/v1.5.7.md).

## v1.5.6 — April 8, 2026

See [v1.5.6 release notes](release-notes/v1.5.6.md).

## v1.5.5 — April 8, 2026

See [v1.5.5 release notes](release-notes/v1.5.5.md).

## v1.5.4 — April 7, 2026

See [v1.5.4 release notes](release-notes/v1.5.4.md).

## v1.5.3 — April 3, 2026

See [v1.5.3 release notes](release-notes/v1.5.3.md).

## v1.5.2 — April 3, 2026

See [v1.5.2 release notes](release-notes/v1.5.2.md).

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
