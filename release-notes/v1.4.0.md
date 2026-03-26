# Release Notes

## v1.4.0 — March 26, 2026

### CodeGen Data Quality

Replaced the previous AI data quality approach (sends all data rows to the LLM) with **CodeGen** — the LLM generates a self-contained Python validation script from a plain-English instruction, then the script runs locally against all data. Cost drops from $25-40/file to ~$0.003/rule.

- **`aiRule`** now uses CodeGen: one LLM call generates a Python script, `python3` executes it locally via `ProcessBuilder`
- Works for CSV, JSON, and XML files — the LLM generates the appropriate parser
- **Removed**: `columnRules` (regex), JavaScript row rules, REST endpoint row rules, `AIDataQualityUtil`
- **Kept**: `validationSchema` (JSON Schema/XSD), `validateFileHeader`

### CodeGen AI Transformation

AI transformations now use the same CodeGen approach — the LLM generates a Python transformation script that runs locally.

- **`aiTransformation`** generates a Python script from a plain-English instruction
- Works for CSV, JSON, and XML
- **Removed**: JavaScript row functions and REST endpoint transformations from the UI and CLI (still work in server for backward compatibility)
- **Removed**: `AITransformationUtil`, sampling (`sample`/`sampleSize`) — no longer needed since scripts run locally
- **Removed**: `AIDataQualityUtil`

### AI Header Validation

CSV header validation (`validateFileHeader`) now uses an LLM call instead of rigid exact-match code.

- **Fuzzy matching**: allows case differences, underscores vs spaces, abbreviations (`qty` matches `quantity`)
- **Order-independent**: columns can be in any order — the pipeline uses the header to map columns by name
- **Missing columns fail**, extra columns are OK

### Out-of-Order CSV Column Support

`RowUtil.getRowAsMap` now uses the file header to map columns by name instead of assuming schema order. CSV files can have columns in any order as long as all schema columns are present.

### Destination Schema

Pipelines now support a separate destination schema for CSV files. Users can rename columns, change types, or remove columns — the destination table is created from the destination schema and data is projected to match.

- **UI**: New step 7 (Destination Schema) in the pipeline wizard — pre-populated from source schema
- **Server**: `PostgresLoader` projects source rows to destination schema columns before writing. `COPY` command explicitly lists destination column names.

### Pipeline Creation Wizard (UI)

Redesigned 9-step wizard:

1. Pipeline Name & Sample File
2. Source Configuration
3. Source Schema
4. Preprocessor
5. Data Quality
6. Transformation
7. Destination Schema (CSV only)
8. Destination
9. Review

**Changes:**
- Removed "Enable data quality checks" outer toggle — checkboxes show directly
- Removed column rules UI, regex assistant, JavaScript file upload
- Removed row rules UI (REST endpoint)
- Removed "Enable transformations" outer toggle
- AI Transformation textarea shown first, with "Trim whitespace" and "Remove duplicates" checkboxes that append text to the instruction
- Preprocessor moved to its own step (was embedded in Source Configuration)
- Destination Schema step added for CSV — customize column names, types, remove columns
- Blank field names blocked in both source and destination schema steps
- Step 2 validates CSV delimiter is not blank
- Better error messages on pipeline creation failure

### Datris CLI

New `--ai-validate` and `--ai-transform` flags on the `ingest` command. Pipeline name is now optional — auto-derived from filename.

```bash
datris ingest sales-data.csv --dest postgres \
  --ai-validate "all prices must be positive" \
  --ai-transform "convert dates to YYYY/MM/DD"
```

- **Removed**: `--validate-column` and `--regex` flags (replaced by `--ai-validate`)
- **Added**: `--ai-validate` — plain-English data quality instruction
- **Added**: `--ai-transform` — plain-English transformation instruction
- **Changed**: `--pipeline` is now optional — auto-derives from filename (`sales-data.csv` → `sales_data`)

### MCP Server

- Added `codegen_transform` parameter to `create_pipeline` tool — plain-English transformation instruction
- Updated NEVER rules: agents can use `aiRule` when user explicitly requests validation
- Removed row rules NEVER rule (row rules no longer exist)

### PostgreSQL Date Fix

`PostgresQueryUtil` now converts `java.sql.Date` and `java.sql.Timestamp` to ISO string format before serialization. Dates display as `2017-01-04` instead of `Jan 4, 2017`.

### Docker

Added `python3` to the pipeline server Docker image (`Dockerfile`) — required for CodeGen script execution.

### Case Class Defaults

Added default values to all Scala case classes in `PipelineConfig.scala` so Jackson can deserialize partial JSON without 400 errors. Added `@JsonCreator`/`@JsonProperty` annotations to `AIRule` and `AITransformation`.

### Version

- Server: 1.4.0
- MCP Server + CLI: 1.4.0

### Documentation

- New: [Datris CLI](docs/cli.md) — full CLI command reference
- Updated: [AI Data Quality (CodeGen)](docs/data-quality/column-rules.md)
- Updated: [AI Transformation (CodeGen)](docs/transformation/ai-transformation.md)
- Updated: [Header Validation](docs/data-quality/header-validation.md) — now AI-powered
- Updated: [Pipeline Configuration](docs/pipeline-configuration.md)
- Updated: [AI Data Profiling](docs/ai-data-profiling.md)
- Updated: [Index](docs/index.md) — added CLI, CodeGen descriptions
- Deprecated: [Row Rules](docs/data-quality/row-rules.md), [JavaScript Row Functions](docs/transformation/row-functions.md), [REST Endpoint Transformation](docs/transformation/rest-endpoint.md)
