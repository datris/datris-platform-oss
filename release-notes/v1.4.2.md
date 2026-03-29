# Release Notes

## v1.4.2 — March 27, 2026

### Mintlify Documentation

Migrated all documentation from `.md` to `.mdx` (Mintlify format). The docs site is now powered by Mintlify with a two-tab layout (Guides + API Reference), internal navigation without new tabs, and a searchable sidebar.

- All internal links converted to Mintlify slug format (no file extensions)
- Removed deprecated docs: row rules, column rules, JavaScript row functions, REST endpoint transformations, deduplication, column trimming
- Renamed `column-rules.md` → `ai-rules.mdx`
- Added `docs.json` configuration for Mintlify

### Documentation Accuracy Pass

Reviewed and corrected every documentation page against the codebase:

- **AI Configuration** — removed outdated context window references, file size limits, and batch mode. AI now generates scripts from samples, not full files. Removed "Running Ollama on a cloud instance" section. Updated to reflect AI provider is required
- **Pipeline Configuration** — added all 5 vector store destination configs (Qdrant, Weaviate, pgvector, Milvus, Chroma) with chunking config. Added UI wizard reference
- **PostgreSQL Destination** — fixed type mapping table (pass-through types), corrected transaction default, added schema auto-creation, documented default COPY options (`FORMAT csv, NULL '.'`), added destination schema fallback
- **MongoDB Destination** — fixed `useTransaction` default to `true`
- **REST Endpoint Destination** — added missing `apiKey` field
- **Object Store Destination** — removed unused `writeToTemporaryLocation` field
- **Kafka Ingestion** — rewrote to match actual code: topic names constructed from pipeline configs (not Kafka metadata discovery), format determined by pipeline config (not auto-detected), fixed examples
- **File Upload** — corrected to accept any file type, fixed staging path to `{env}-raw/temp/{pipeline}/`, corrected error responses (no 404), fixed max file size to 1GB
- **Data Types** — fixed type coercion section (was inaccurate about null handling, error behavior, and timestamp parsing)
- **Schemas** — split types into common (auto-generated) and advanced (manual only), added MCP server all-string schema explanation
- **Monitoring** — added `CANCELLED` job state, added Datris UI and CLI sections
- **Notifications** — added all 11 destination types to notification list
- **MCP Server** — added missing `query_natural` tool, updated setup to use `uvx`, updated Claude Desktop/Code configs, added CLI examples
- **CLI** — simplified install to Homebrew only, added `datris help` command
- **All destinations** — added Completion Notification section to PostgreSQL, MongoDB, Kafka, ActiveMQ, REST Endpoint
- Removed "CodeGen" terminology from data quality and transformation docs
- Changed "runs locally" to "runs in the container" throughout
- Removed cost references (~$0.003/rule)

### Homebrew Tap

Created `datris/homebrew-tap` for installing the CLI via Homebrew:

```bash
brew tap datris/tap
brew install datris
```

### CLI sdist Fix

Fixed `pyproject.toml` — `cli.py` was missing from the sdist build target, causing `datris` CLI command to fail when installed via pip/pipx/brew.

### README Overhaul

Slimmed down `README.md` from 188 lines to ~90 lines:
- Added badges (PyPI, MCP Registry, Docker Hub, License)
- Added MCP client config example and CLI examples
- Condensed AI features into a scannable table
- Removed 50-line documentation tree (now points to Mintlify docs site)
- Fixed license from Apache 2.0 to AGPL-3.0

### Deprecated Examples Removed

- Deleted `examples/data-quality-rest/` and `examples/transformation-rest/` (replaced by CodeGen)
- Removed from `docs/examples.mdx`

### Examples Updated

- **chat-vector-store** — fixed `PG_DATABASE` default from `idata` to `datris`, renamed `source_dataset` to `source_pipeline`
- **topic-subscriber** — renamed all `idata`/`dataset` references to `datris`/`pipeline`
- **preprocessor** — renamed `datasetName` to `pipelineName`
- **kafka-csv-loader** — updated default topic from `idata.*` to `datris.*`
- **test-mcp-server** — added `query_natural` test, fixed dependencies in docs

### Server

- `useTransaction` default changed from `false` to `true` in `PipelineConfig.scala` — PostgreSQL and MongoDB writes now wrap in transactions by default

### Version

- Server: 1.4.2
- MCP Server + CLI: 1.4.2
- Homebrew: `brew tap datris/tap && brew install datris`
- PyPI: `pip install datris-mcp-server==1.4.2`

---

## v1.4.1 — March 26, 2026

See [v1.4.1 release notes](release-notes/v1.4.1.md).

## v1.4.0 — March 26, 2026

See [v1.4.0 release notes](release-notes/v1.4.0.md).
