# Release Notes

## v1.3.0 — March 26, 2026

### MCP Tool Changes

**Atomic `create_pipeline`** — The `generate_schema` tool has been removed. `create_pipeline` now accepts sample data content (base64-encoded), auto-detects the schema, and creates the pipeline in one atomic call. Agents never see or modify raw pipeline config JSON. Parameters: `content`, `filename`, `pipeline`, `destination` (postgres/mongodb/qdrant/weaviate/milvus/chroma/pgvector), `table`, `database`.

**Content-based uploads** — All upload tools (`upload_data`, `profile_data`, `upload_config`) now use base64-encoded `content` + `filename` instead of `file_path`. Base64 padding is auto-fixed for agents that send improperly padded strings.

**`upload_file` renamed to `upload_data`** — Returns structured JSON: `{ pipelineToken, message }`.

**`update_secret` tool added** — Agents can configure AI provider API keys (anthropic, openai, ollama, embedding). Scoped to AI secrets only.

**All-string schema for MCP** — `create_pipeline` generates all columns as `string` type (no AI call, just parses CSV header). Eliminates type casting errors during ingestion.

**Pipeline registration verification** — `create_pipeline` now verifies the pipeline was actually registered by reading it back, and returns clear errors if registration fails silently.

### Agent Workflow Improvements

**Simplified workflow** — `check_service_health` and `update_secret` removed from required workflow (slow, only for diagnostics). Workflow: `list_pipelines` → `create_pipeline` → `upload_data` → `get_job_status` → query/search.

**NEVER rules** — Server instructions prohibit: AI rules for DQ, JavaScript/REST endpoint row rules, AI transformations, JavaScript/REST endpoint transformation functions, data profiling for pipeline config generation. Default pipeline = source + destination only.

**Job status detection fixed** — `get_job_status` now correctly detects status from both detail view (`state`: begin/processing/end/error) and summary view (`status`: processing/success/error). Returns "STILL RUNNING" or "JOB FAILED" messages.

**Empty pipeline list message** — When no pipelines exist, `list_pipelines` returns instruction to create one. Agents cannot skip to querying without a pipeline.

### Pipeline Delete — Destination Cleanup

`DELETE /api/v1/pipeline` now cleans up destination data by default (`deleteData=true`):
- PostgreSQL: `DROP TABLE IF EXISTS ... CASCADE`
- MongoDB: `collection.drop()`
- pgvector: `DROP TABLE IF EXISTS ... CASCADE`
- Qdrant/Weaviate/Milvus/Chroma: HTTP delete collection/class

### Ingestion Tab — Clear All

New "Clear All" button on the Ingestion tab to delete all pipeline status history.
- `DELETE /api/v1/pipeline/status` — clears both detail and summary collections
- Confirmation prompt before clearing

### SQL Query Improvements

- **CTE support** — `WITH ... AS` queries (Common Table Expressions) now accepted alongside `SELECT`
- **Trailing semicolons** — Automatically stripped instead of rejected
- **CSV quoting fix** — `CSVReader` now re-quotes fields containing delimiters when reconstructing rows, preventing PostgreSQL COPY errors with values like `"Activision Blizzard, Inc."`

### Health Check Improvements

- All timeouts reduced from 5s to 2s (PostgreSQL, MongoDB, Kafka, all vector stores)
- Qdrant and Milvus health checks use HTTP instead of gRPC (no channel leaks)
- `ClosedResourceError` in SSE handler caught gracefully

### Terminology Cleanup

- All "dataset" references renamed to "pipeline" across the entire codebase
  - API controller method names and log messages
  - Error messages in PipelineValidatorUtil
  - Internal method names (StatusUtil, ScheduledBatchTasks, PipelineMetadataUtil)
  - Vector store metadata field: `source_dataset` → `source_pipeline`
  - Config property: `sendDatasetNotifications` → `sendPipelineNotifications`
- `helpers/` directory renamed to `examples/`
- `docs/helpers.md` renamed to `docs/examples.md`
- Market Macro Agent renamed from "Claude" to "MacroAgent"

### Server Fixes

- **PostgreSQL NULL handling** — `COPY` command uses `NULL '.'` for FRED-style missing data placeholders
- **CSV empty rows** — `StreamNotifier` handles empty CSV files gracefully
- **ASGI SSE fix** — Raw ASGI handler instead of Starlette endpoints
- **SQLXML conversion** — XML column values converted to strings for JSON serialization

### PyPI + MCP Registry

- Published `datris-mcp-server` to PyPI and MCP Registry as `io.github.datris/datris`
- `pyproject.toml`, `server.json` added for packaging
- `server.py` refactored with `main()` entry point

### UI Changes

- MCP tab is first tab and default landing page
- Logo click navigates to home
- Pipelines tab auto-refreshes every 5 seconds
- "Clear All" button on Ingestion tab
- MCP tool catalog updated for new tool signatures

### Documentation

- `docs/examples.md` — Overview with categorized table linking to individual example docs
- `docs/examples/` — Individual docs for each example (market-macro-agent, chat-vector-store, etc.)
- `docs/mcp.md` — Updated tool descriptions
- `docs/installation.md` — Added "Upgrading" section
- `examples/test-mcp-server/app.py` — Rewritten to test all current MCP tools
- Pipeline Config Reference rewritten for agent-directive language
