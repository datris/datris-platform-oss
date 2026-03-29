# Release Notes

## v1.4.3 — March 29, 2026

### Unified `datris analyze` Command

New `datris analyze` command replaces both `ask-sql` and `ask` with a single unified interface. Auto-picks the right approach based on `--dest`:

- **PostgreSQL** — AI generates SQL, executes it, returns an AI narrative answer
- **MongoDB** — fetches documents, AI answers based on the data
- **Vector stores** — semantic search for relevant chunks, AI generates answer (RAG)

```bash
datris analyze "What are the top 5 stocks by volume?" --table trades
datris analyze "What is the return policy?" --table support_docs --dest pgvector
datris analyze "How many events in March?" --table events --dest mongodb
```

### `--ai-analyze` on Ingest

Chain ingest and analysis in one command. After ingestion completes, the CLI automatically queries the data and returns an AI answer:

```bash
datris ingest trades.csv --dest postgres --ai-analyze "What are the top 5 stocks by volume?"
datris ingest report.pdf --dest pgvector --ai-analyze "What was the company's revenue?"
```

### `--json` on All CLI Commands

Every CLI command now supports `--json` for raw JSON output — useful for scripting and programmatic use:

```bash
datris pipelines --json
datris query "SELECT * FROM trades" --json
datris analyze "top 5 stocks" --table trades --json
datris health --json
datris status my_pipeline --json
```

### Removed Commands

- `datris ask` — replaced by `datris analyze --dest pgvector`
- `datris ask-sql` — replaced by `datris analyze --dest postgres`

### Handle Missing CSV Columns Gracefully

When a CSV file is missing columns defined in the pipeline schema, the pipeline now fills them with empty values instead of failing. If a missing column is a key field (primary key), the pipeline fails immediately with a clear error.

- Extra columns in the CSV are silently ignored (existing behavior)
- Missing non-key columns are filled as empty and logged as a warning
- Missing key fields fail with: `"CSV is missing required key field(s): id. CSV columns: name, email. Expected columns: id, name, email"`

### Mintlify `/mcp` Path Fix

Renamed `mcp.mdx` to `mcp-server.mdx` — Mintlify reserves the `/mcp` URL path for its own built-in MCP endpoint, which was returning JSON instead of the documentation page.

### New API Reference Documentation

- **Metadata API** — 10 endpoints for PostgreSQL, MongoDB, and vector store metadata discovery
- **Configuration API** — upload validation schemas, AI-generated JSON Schema/XSD
- **Secrets API** — list, get (always masked), update, delete Vault secrets

### Security: Removed Secret Reveal

Removed the `reveal=true` query parameter from `GET /api/v1/secrets/{name}`. Sensitive fields (passwords, API keys) are now always masked. Removed the Reveal/Hide toggle from the UI.

### MCP Reference Tab

Added a third top-level tab in the docs navigation: **Guides → MCP Reference → API Reference**. MCP server and CLI documentation now live in their own tab.

### Source Code Page

New documentation page linking to the GitHub repo with directory structure, key files, and build instructions.

### Version

- Server: 1.4.3
- MCP Server + CLI: 1.4.3

---

## v1.4.2 — March 27, 2026

See [v1.4.2 release notes](release-notes/v1.4.2.md).

## v1.4.1 — March 26, 2026

See [v1.4.1 release notes](release-notes/v1.4.1.md).

## v1.4.0 — March 26, 2026

See [v1.4.0 release notes](release-notes/v1.4.0.md).
