# Release Notes

## v1.5.3 — April 3, 2026

### Schema Evolution

- **Additive schema evolution** — When a CSV upload contains new columns not in the pipeline schema, they are automatically added (as `string` type), the `schemaVersion` is incremented, and `ALTER TABLE` adds the columns to the PostgreSQL destination.
- **Dropped column support** — When a CSV is missing non-key columns from the schema, those columns are excluded from the PostgreSQL `COPY` command so the database defaults them to `NULL`. Previously, empty strings were inserted which failed on typed columns (e.g., integer).
- **Key field validation** — If a dropped column is a configured key field, ingestion fails with a clear error message listing the missing key fields.
- **Shared evolution logic** — Schema evolution (additive + dropped columns) is now handled by `DataUtil.evolveSchema()`, used by both `StreamNotifier` (stream uploads) and `FileNotifier` (file-based uploads). Previously only `StreamNotifier` had this logic.
- **FileNotifier parity** — `FileNotifier` now supports empty row detection, unstructured file byte loading, and proper error messages for unsupported file types (matching `StreamNotifier`).
- **Query NULL serialization fix** — PostgreSQL and natural language query endpoints now use `serializeNulls()` so columns with `NULL` values appear in query results. Previously, Gson's default behavior omitted null keys, causing columns to disappear from the UI.

### Documentation

- Added [Schema Evolution](/schemas#schema-evolution) section to the schemas docs
- Updated ingestion API and MCP tool docs to reference schema evolution behavior

### Version

- Server: 1.5.3
- MCP Server: 1.5.3
- CLI: 1.5.3

---

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

## v1.4.1 — March 26, 2026

See [v1.4.1 release notes](release-notes/v1.4.1.md).

## v1.4.0 — March 26, 2026

See [v1.4.0 release notes](release-notes/v1.4.0.md).
