# Release Notes

## v1.6.3 — April 15, 2026

Database lockdown, tap script hardening, and Create Tap UX improvements.

### Database name is now server-controlled

The UI no longer lets you edit the Postgres or MongoDB database name — not in pipeline destinations (step 8), not in the Search tab. The platform uses a single canonical database per backend, returned by `/api/v1/version` as `postgresDatabase` and `mongodbDatabase`. The UI reads these as authoritative and submits them unchanged. No more accidentally routing pipeline data to a misspelled database.

### MongoDB: internal vs user databases split

MongoDB config now has two fields:

- `mongodb.database` — **user-facing**, default `datris`. Pipelines write here; the UI Search tab shows this.
- `mongodb.internalDatabase` — **platform state**, default `oss`. Pipeline/tap configs, run status, job queues. Never surfaced in the UI.

`NoSQLDbUtil` (the internal platform singleton) binds to `internalDatabase`; user-facing ops (queries, metadata listing, tap env var, pipeline destinations) use the user database. Existing OSS installations keep their platform state in `oss` with zero migration; new user pipelines land in `datris`.

### Unlimited reads for tap scripts

`/api/v1/query/mongodb` and `/api/v1/query/postgres` used to cap results at 1000 rows and silently truncate to 20 (Mongo) or 100 (Postgres) when no `limit` was passed — which meant tap scripts that queried S&P 500 tickers from a Datris collection were getting back only 20. Two changes:

1. **`limit: -1` is the "unlimited" sentinel** — no cap, returns every matching row. The tap codegen prompt now requires generated scripts to pass this on `/query/*` bodies for cron/manual runs.
2. **Preview defaults unchanged** (20 Mongo / 100 Postgres) for UI/MCP callers who omit `limit` — their behavior is identical.

### Tap test sampling

New checkbox in Create Tap step 2: **"Limit test sample to 20 records"** (default on). When enabled, the runner injects `DATRIS_TAP_TEST_LIMIT=20` into the script process. The codegen prompt now emits a pattern that respects this env var — capping both `/query/*` limits and per-item iteration loops. Cron and manual runs never set the env var, so production runs always read everything.

### Codegen and diagnosis prompt hardening

- **Codegen** now declares platform response shapes contractual and forbids defensive shape-probing: no `isinstance(list vs dict)` branching, no candidate-key iteration, no alternate-name probing, no multi-candidate field guessing, no silent fallbacks. Generated scripts raise with stderr context instead of limping along with wrong data.
- **Diagnosis** now requires evidence-based analysis: quote the actual traceback, respect in-script guards (don't blame env vars that would have been caught by an existing check), prefer data-level explanations when code ran fine, and recognize the 20/100 default-limit pattern.

### Create Tap UX

- **Step 1 brainstorm**: *Send* button renamed to *Ask* with the AI sparkle icon.
- **Step 2**: auto-apply the AI diagnosis and retest, capped at 2 attempts. After that, the user sees the diagnosis and decides manually.
- **Step 2**: *Stop Test* button cancels an in-flight test (and halts the auto-fix chain).
- **Step 2**: Copy-to-clipboard button on both the generated script preview and the editable textarea.
- **Step 2**: Test results render as scrollable JSON instead of a columnar table (MongoDB docs are nested; the table was flattening them). Preview expanded from 10 to 100 records.
- **Generate Pipeline modal**: editable destination collection/table name with existence check against Mongo and Postgres — no more silent collision with an existing collection.
- **Step 5 (Run the Tap)**: now shows the linked pipeline's full destination (backend + database.schema.table or db/collection + truncate mode) fetched from the pipeline config.

### Search tab

MongoDB query results now render as formatted, scrollable JSON instead of a columnar table. Preserves nested document structure.

### Data Catalog

- **Delete the Uncataloged group**: a single delete action removes every uncataloged tap and pipeline. Confirm prompt reads "Delete ALL uncataloged items?" so the scope is obvious.
- **Per-item delete**: trash icon on each tap and pipeline inside every catalog — no need to navigate to the Taps or Pipelines tab to delete one item.

### Yaml defaults

`mongodb.database` default changed from `oss` → `datris` in both `datrisserver/src/main/resources/application.yaml` and `docker/config/application.yaml` (and the matching Spring Data `uri` values). Existing OSS installations with data in the `oss` MongoDB database should either set `mongodb.database: oss` explicitly in the yaml to keep old behavior, or migrate the data (`mongodump` + `mongorestore` into `datris`). Multi-tenant / hosted deployments derive the Mongo database name from the tenant environment and are unaffected.

---

See [archived release notes](release-notes/) for prior versions.
