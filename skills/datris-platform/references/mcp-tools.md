# Datris MCP tools

Grouped by purpose. Tool names are exact. Every tool that changes state accepts an optional `reason` string that lands in the audit log and on the approval card; always pass one.

## Resources (read with the MCP resource API)

| URI | Use it for |
|---|---|
| `datris://pipeline-config-reference` | Every source type, data quality rule, transformation and destination, with JSON examples. Read before writing a pipeline config by hand. |
| `datris://tap-workflow-reference` | Tap creation, per-run params, incremental state, the CRON cookbook, test-before-run, params versus secrets, run outcomes. Re-read whenever a tap behaves unexpectedly. |

## Instance and health

| Tool | Notes |
|---|---|
| `get_version` | Server version and the budgets in force (`pipelineMaxPayloadMB`, `tapScriptTimeoutSeconds`, `tapRunTimeoutSeconds`). Call before sizing a large job. |
| `check_service_health` | Which backends are up, down or unconfigured. Slow; diagnostics only. |
| `run_doctor` | Vault token expiry, AI secrets, embedding model, disk, version skew. Each finding comes with its fix. Diagnostics only. |
| `get_agent_policy` | Per action: auto, approve or deny. Call before a delete or a destination type change. |
| `list_pending_approvals`, `get_approval` | The approvals this agent queued, and polling one by id. |

## Pipelines

| Tool | Notes |
|---|---|
| `list_pipelines`, `get_pipeline` | Check these before creating anything. Results reflect the caller's catalog access; a 404 for a name you were given means it is not available to this user, so do not retry other spellings. |
| `create_pipeline` | From a base64 sample; schema auto-detected. Destination categories: structured (postgres, mongodb, snowflake, databricks, sqlserver), object store (Parquet, ORC or Iceberg in MinIO or S3), vector (pgvector, qdrant, weaviate, milvus, chroma), plus `scratch` for Live Read. External destinations reference a platform `credentialsSecret` by name. Set `catalog`; for a user limited to specific catalogs, a create without it lands in their only editable catalog or is refused with the list of allowed catalogs. Optional `protect` (field name to `{"method": ...}` with hmac, mask, redact or drop; `preserve` for mask) protects fields before any AI stage; confirm the fields with the user first. Optional `protect_preset` (`hipaa-safe-harbor`, delimited sources) keeps the preset enforced at every save and for new columns, with `protect_exempt` naming fields left unprotected on purpose. |
| `upload_data` | Base64 content to a pipeline. New CSV columns are added to the schema automatically. Returns a pipeline token. |
| `get_job_status` | With `pipeline_token`: a `{rollup, events}` body; poll `rollup.allDone`, then `rollup.status` and per-job `lastError`; an answer with no jobs (`rollup.jobs` empty) means the token has no runs available to you, so stop polling and report that. With `pipeline_name`: a paged summary of recent jobs. |
| `get_pipeline_status` | Same rollup shape, keyed by `publisherToken` (covers every job a tap run submitted) or a single `pipelineToken`. |
| `get_pipeline_result` | Rows from a Live Read (`scratch`) pipeline only. The rollup already carries `resultPreview`; call this only when `resultTruncated` is true. Results expire; a 410 means run again. |
| `kill_job` | By pipeline token. Only when the user asks. |
| `delete_pipeline` | Deletes the pipeline and its destination data. `keep_config=true` deletes only the data (reset); `keep_data=true` deletes only the config and lists the table, collection or files left in place in `kept` (refused for a vector destination; the kept data is no longer managed by any pipeline). A table, collection, files or vector collection the store could not remove is reported in `warnings` and the config is deleted regardless: relay it so it can be removed in the store (after a reset, run the reset again once the store is available). Usually policy-gated. Check lineage first. |
| `profile_data` | Summary stats and suggested rules for a sample. Report the output to the user; never paste it into a config. |
| `get_dest_types`, `apply_dest_types` | Propose and apply real column types for an all-text destination (Postgres, Snowflake, Databricks, SQL Server; SQL Server `_json` / `_xml` document columns are never retyped). Apply needs the user's explicit approval and lists every column; one uncastable value fails the whole apply with nothing changed. |
| `suggest_field_protection` | Proposes per-field protection (hmac, mask with preserve, redact, drop, or none, each with a reason) from field names and types only; no values are sent and nothing is saved. Pass `pipeline` or `fields`. Show the suggestions and ask which to accept before passing them as `protect` to `create_pipeline`. Optional `preset` (`hipaa-safe-harbor`, delimited sources) uses the rule-based preset instead: class, method and reason per field, unclassified names and review notes, all to be shown to the user. |
| `set_catalog`, `rename_catalog`, `delete_catalog`, `export_catalog`, `restore_catalog` | Grouping labels. Rename never merges into an existing catalog and carries users' catalog grants to the new name; delete only moves items to Uncataloged. A user limited to specific catalogs moves items only between catalogs they can edit and never to Uncataloged. `export_catalog` returns a catalog as a zip (base64) with its manifest: definitions, scripts, tap state, validation schemas; never secret values or data. `restore_catalog` restores it under the same or a new name only when the user asked and named the archive; it never merges (clashes are refused), `mode=overwrite` needs `confirm`, `restore_state=false` makes a clean clone. Restored taps are disabled; report `missingSecrets` and `failed` to the user. |
| `catalog_enable_order` | Read-only. The catalog's taps in levels (level 1 reads no other tap's tables) with `hasPassingTest`, `lastRealRunLanded`, `blockedBy` and `ready` per tap; cycles under `cycles`. Call it for "what order do I enable these in", typically after `restore_catalog`: a tap whose sources have not landed data fails its test, so run (not enable) the sources first. Dependencies are inferred from the script's queries or declared with `depends_on` on `create_tap` / `update_tap` (`[]` = none); never enforced. |
| `enable_catalog_ordered` | Starts one job that tests (if needed), enables and runs each tap of a catalog in its enable order, waiting for each tap's data to land before the taps that read it; sources that fail make their dependents `skipped`. Only when the user asks to enable the catalog. Returns `jobId` and `plan`; poll `catalog_enable_order` (its `job` block) with `wait_seconds` until `job.active` is false, then report each tap's phase and reason. Do not `run_tap` / `test_tap` / `update_tap` those taps meanwhile. One approval under an agent policy (`tap:run:enable-ordered`). No MCP cancel: the user cancels in Datris. |
| `list_pipeline_versions`, `get_pipeline_version`, `diff_pipeline_versions`, `restore_pipeline_version` | Definition history. Restore is append-only: it writes the chosen snapshot as a new version. An empty version list means never edited, not no version. |
| `list_script_versions`, `get_script_version`, `diff_script_versions`, `restore_script_version` | History of the AI data-quality rule or AI transformation script a pipeline runs (`kind`: `dataQuality` or `transformation`), separate from the definition history. Restore only when the user asks for that version: it writes the text back as a new latest version with no model call, and is refused (422) when the pipeline's instruction or source schema no longer matches the version, or (409) when a repository file was hand-edited. Needs `pipeline:update`; `pipeline:update:codegen-scripts` under an agent policy. |

## Taps (acquiring data from outside)

| Tool | Notes |
|---|---|
| `list_taps`, `get_tap` | `get_tap` includes the Python script and the committed state. Results reflect the caller's catalog access (404 = not available to this user). |
| `create_tap` | From an `instruction` (the platform generates the script) or your own `script` with a `fetch()` function (faster, more predictable). Optional `target_pipeline`, `cron_expression`, `secret_name`, `tap_type` (`structured` or `document`). Upserts by name, so this is also how you replace a script. `catalog` follows the same catalog-access defaulting as `create_pipeline`. |
| `test_tap` | Runs without pushing. Default 20-row preview; `limit: 0` streams the whole source. Call before the first `run_tap` of any new or edited script and before setting a cron. |
| `run_tap` | Executes and pushes. Optional `params` object becomes `DATRIS_TAP_PARAM_<key>` env vars for that run. Read `persisted` and `persistedReason` before anything else; capture `publisherToken` for verification. Records are not returned. |
| `update_tap` | Config only: enabled, schedule, pipeline, description. Script changes go through `create_tap`. |
| `get_tap_logs` | Last 50 runs with status, duration, errors and `publisherToken`. Pivot to `get_pipeline_status` to confirm a scheduled run landed. |
| `get_tap_state`, `set_tap_state` | Incremental bookmark. `reset=true` forces a full first-run fetch next time. |
| `get_tap_ledger` | Document taps: which files were seen and processed. `clear_uri` or `clear_all` forces reprocessing. |
| `delete_tap` | Removes the tap, its script, ledger and staged objects. Policy-gated as a rule. |
| `list_tap_versions`, `get_tap_version`, `diff_tap_versions`, `restore_tap_version` | History of config plus script. Restore does not run the tap. Restore is checked like a save of that version (refused with the reason, nothing written) and never changes the tap's catalog or enabled state. |
| `wait_seconds` | Sleep 1 to 120 seconds between polls. Start at 5, back off toward 60, reset when a poll shows progress. |

## Secrets

| Tool | Notes |
|---|---|
| `list_tap_secrets`, `get_tap_secret_fields` | Find an existing tap secret before asking the user for credentials again. Field names only, never values. |
| `create_tap_secret`, `delete_tap_secret` | Tap-owned secrets (tagged `_type=tap`). Create fails on collision unless `overwrite=true`. AI-provider names are reserved. |
| `list_platform_secrets`, `get_platform_secret_fields` | Human-owned destination and infrastructure credentials. Read-only to agents. Use them to verify a `credentialsSecret` has the keys a destination needs. |
| `update_secret` | AI provider keys only (anthropic, openai, azure, grok, ollama, embedding). |
| `upload_config` | Upload a JSON Schema config file for schema validation. |

## Reading data

| Tool | Notes |
|---|---|
| `query_postgres` | Read-only SELECT. |
| `query_mongodb` | Filter and projection on a collection. |
| `query_objectstore` | Rows from a pipeline's Parquet, ORC or Iceberg destination by pipeline name. Iceberg responses carry the snapshot read. |
| `query_snowflake`, `query_databricks` | Read-only SQL by pipeline name; credentials stay on the server. Omit `sql` to preview the destination table. Databricks auto-starts a stopped warehouse, so the first query is slow. Snowflake: one statement per query; semicolons and the `->>` operator are refused (string literals included). Calls to functions that act (Snowflake `SYSTEM$...` except `SYSTEM$TYPEOF` and the clustering ones, `NEXTVAL`, `GETNEXTVAL`, `IDENTIFIER(...)`; Databricks `http_request`, `remote_query`, `ai_*`, `vector_search`, `secret`, `read_files`, `read_statestore`, `read_state_metadata`, `cloud_files_state`, `list_secrets`, `table_changes_by_path`, `IDENTIFIER(...)` and others) are refused. Databricks: a read from a file path in place of a table (a format word or any qualifier before a backticked location) is refused; name the table. Time limit 30 s, or 300 s with `limit: -1`. |
| `query_sqlserver` | Read-only T-SQL (`SELECT` or `WITH`) by pipeline name; credentials stay on the server. Omit `sql` to preview the destination table. Rows are capped with `TOP` (or `OFFSET ... FETCH` after an `ORDER BY`), so never add `LIMIT`. Metadata through `INFORMATION_SCHEMA` views. A second statement is refused where it can be recognised (a second `SELECT` outside `UNION`/`EXCEPT`/`INTERSECT`, a number joined to a word, `SET`/`BEGIN`/`WHILE`/`IF`/`PRINT` and similar words, `NEXT VALUE FOR`); always rolled back. Brace escapes such as `{d '...'}` and `{fn ...}` are refused: write the date as a string and use the T-SQL function. The text `{limit ...}` is refused anywhere, even inside a string or quoted name; use `limit`. Time limit 30 s, or 300 s with `limit: -1`. Needs `query:sqlserver`. |
| `query_natural` | Plain-language question; the platform writes and runs the SQL. |
| `search_pgvector`, `search_qdrant`, `search_weaviate`, `search_milvus`, `search_chroma` | Semantic search returning chunks, scores and metadata. |
| `ai_answer` | Answer from supplied context. Pass `sources` provenance handles so the answer carries them. |

## Discovery, provenance, lineage

| Tool | Notes |
|---|---|
| `find_data` | Rank pipelines by a natural-language query; returns location, freshness, provenance handles, lineage and a `howToQuery` hint. Discovery only; you still make the query call. `include_unity_catalog` extends it to Databricks Unity Catalog tables. |
| `list_postgres_databases`, `list_postgres_schemas`, `list_postgres_tables`, `list_postgres_columns` | Structural browse. Tables support a vector-only filter. |
| `list_mongodb_databases`, `list_mongodb_collections` | Same for MongoDB. |
| `list_qdrant_collections`, `list_weaviate_classes`, `list_milvus_collections`, `list_chroma_collections`, `list_pgvector_collections` | Vector store inventories. |
| `get_provenance` | Resolve a `_datris_run_id` stamped on a row back to pipeline run, tap run, script commit, config version and declared source. |
| `get_lineage` | Walk upstream, downstream or both from a node to a bounded depth, with freshness, recent runs and optional column-level lineage. Use for impact analysis before changing or deleting. |
| `browse_unity_catalog` | Catalogs, schemas, tables and columns a Databricks platform secret can see, one level per call. |

## Incidents

| Tool | Notes |
|---|---|
| `list_incidents`, `get_incident`, `get_incident_evidence` | Failures the platform's recovery agent is already working, with narrative, pending approvals, and platform-computed evidence (before/after profiles, diff, blast radius, thresholds, verdict). The incident webhook sends a one-line evidence summary (counts only, no values). When the user asks about such a failure, explain the record rather than re-diagnosing. Read-only. A learn note from a resolved fix becomes a suggested tap prompt fragment (`GET /api/v1/tap-prompts?status=suggested`); only a person can accept it. |
