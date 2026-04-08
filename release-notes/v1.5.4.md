# Release Notes

## v1.5.4 — April 7, 2026

### Taps (Beta)

A new way to feed pipelines from external sources: AI-generated Python scripts that fetch data on a schedule and stream it into a pipeline.

- **Tap creation wizard** — 4-step wizard (collapsed from 5): Describe → Edit & Test → Schedule → Review. The describe step now opens with a **brainstorm chat** that auto-fills the instruction box on every turn and proactively suggests env-var names you'll need.
- **AI script generation** — `TapScriptGenerator` produces a runnable Python script from your instruction. JSON-parse retries with format-reminder reprompt and a raw-script fallback so a malformed LLM response no longer hard-fails generation.
- **AI diagnosis** — When a test run fails (or emits warnings/deprecations), the AI explains what went wrong with explicit (a)/(b)/(c) options. "Apply Diagnosis" rewrites the script in place.
- **Tap secrets** — Per-tap credentials stored in Vault, tagged `_type=tap`. Inline create/edit on step 2; one-click "Create tap secret" shortcut from the brainstorm suggestions.
- **Run history** — `TapRunLog` model persisted to MongoDB; `GET /tap/logs` endpoint and history modal on the taps list.
- **Scheduling** — CRON expressions in Quartz format, AI-generated from natural language; human-readable cron description on the review step.
- **Pipeline feed** — JSON-to-CSV conversion (union of keys) routes tap output through `StreamNotifier`, matching the pipeline's source format. CSV column names are normalized to `[a-z0-9_]+` via a spell-out table (`%` → `percent`, `#` → `num`, `&` → `and`, …) so output passes `PipelineValidatorUtil` and downstream SQL needs no quoting.
- **All-string schemas** — Tap-derived schemas default columns to `string` so type inference doesn't break ingestion on the first run.
- **Step 5 run** — Run the tap directly from the wizard's review step.
- **Test failures** — A run that produces 0 records is now treated as a failure. Configurable `tapScriptTimeoutSeconds`.
- **Pipelines tap column** — The Pipelines page shows which tap (if any) feeds each pipeline, clickable to jump to the tap edit page.
- **Pipeline wizard "Create from Tap"** — Auto-populates source type and schema; auto-derives the pipeline name from the tap.
- **MCP + CLI** — 4 new MCP tools (`create_tap`, `list_taps`, `run_tap`, `delete_tap`) and 4 matching CLI commands.
- **Pip caching** — Two new Docker volumes (`pip-cache`, `pip-packages`) so packages installed by tap scripts persist across container restarts. `--no-cache-dir` removed from the Dockerfile.
- **Brainstorm endpoint** — `POST /api/v1/tap/brainstorm` backed by `AIUtil.callAIWithMessages` for multi-turn conversations. The system prompt teaches the LLM about Datris metadata/query endpoints so it doesn't ask about discoverable schema details.
- **Beta label** — Taps page title is marked `(beta)`.

### Configurability

- **Configurable date format and timezone** — `dateFormat` and `dateTimezone` in `application.yaml`, threaded through `DatrisEnvironment` and `StartupRunner`. All display timestamps (pipeline status, tap run history, transformations) honor them. Auto-DST via the `z` pattern. Default timezone is now `America/New_York`.
- **Configurable Postgres database** — `postgres.database` replaces six hardcoded `"datris"` defaults across `QueryAPIController`, `MetadataAPIController`, and `PostgresQueryUtil`. Threaded through `DatrisEnvironment` with multi-tenant override.
- **Split tap database env vars** — `TapScriptRunner` now injects `DATRIS_POSTGRES_DATABASE` and `DATRIS_MONGODB_DATABASE` separately (Mongo follows the multi-tenant rule), replacing the old single `DATRIS_DATABASE`.
- **Configurable AI version** — `ai.version` replaces the hardcoded `anthropic-version: 2023-06-01` header. Generic `version` field on `AIConfig` so other providers can reuse it.
- **Default AI provider** — `ai.provider` defaults to `anthropic` in the configuration reference.

### Security (Phase 1)

- **Secret masking** — `TapScriptRunner` masks secret values in stderr logs and exception messages.
- **Global CORS** — Centralized in `WebMvcConfig` via `cors.allowedOrigins`. Removed `@CrossOrigin` from 11 controllers.
- **Non-root containers** — `datris`, `ui`, and `mcp-server` Dockerfiles run as non-root. `ui` switches to `nginx-unprivileged` on port 8080.
- **Pinned GitHub Actions** — All actions in `docker-publish` and `mcp-registry-publish` workflows pinned to commit SHAs.
- **Security docs** — New `SECURITY.md` with an honest description of the current security model and operator responsibilities. New `docs/plans/security-fixes.md` (gap analysis) and `security-fixes-implementation.md` (phased plan).

### AI

- **Unified retry helper** — Single retry path across `callAI`, `callAIWithSystem`, and `callAIWithMessages`. Retries on 429/503/529 with linear backoff.
- **CodeGen prompt fixes** — Cleaner system prompts, snake_case key guidance, env-var name updates.

### Server Fixes

- **PostgresLoader NULL default** — `truncateBeforeWrite` now runs **after** `createTableIfUndefined` so first-run pipelines no longer fail trying to truncate a not-yet-existing table.
- **Search legacy identifiers** — `search.component.ts` double-quotes schema/table/column identifiers in the generated `SELECT` so legacy non-snake_case names still parse.
- **Search refresh** — Postgres schemas/tables and Mongo databases are refreshed on every navigation to `/search` so newly created tables show up.
- **TapScheduler date parsing** — Now uses the configured `dateFormat` instead of `Instant.parse()`, which broke after the date format change.
- **MinIO init** — Correct hostname (`datris`), `bash` for `/dev/tcp` health check, idempotent event setup.

### UI

- **Pipeline wizard** — Back/Next/Save buttons moved to the top of each step.
- **Spinners** — Generate Script and Apply AI Diagnosis buttons now show a spinner.
- **Taps list** — View config modal, run history modal, inline name editing, pipeline dropdown, Last Run / Last Test Run columns. Refresh pauses during inline edits.

### Dependency Upgrades

ActiveMQ 5.18.6, Quartz 2.5.0, Spring Boot 3.2.12, Hadoop 3.3.6, POI 5.3.0, Gson 2.11.0, MongoDB 4.11.4, mysql-connector-j 8.4.0, commons-csv 1.12.0, slf4j 2.0.16, angus-mail 2.0.3. Removed unused `deephaven-csv`.

### Documentation

- New [Taps](/taps) user guide and [Taps API](/taps-api) reference (4-step wizard, brainstorm, env vars, secrets, scheduling)
- Full Taps section added to `openapi.yaml` (`TapConfig`, `TapRunLog`, `TapRunResult`, `TapBrainstorm` schemas + 9 endpoints)
- New `postgres`, `date`, `ai.version`, and CORS sections in [configuration-reference](/configuration-reference)
- Column naming rules and tap auto-normalization documented in [schemas](/schemas)
- Pip volumes documented in [installation](/installation)
- mcp-server README and `pyproject.toml` documentation URL now point to `docs.datris.ai/mcp-server`

### Version

- Server: 1.5.4
- MCP Server: 1.5.4
- CLI: 1.5.4

---

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
