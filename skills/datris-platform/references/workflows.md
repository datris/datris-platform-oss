# Canonical workflows

Each sequence ends with verification by token. Say that data landed only after the rollup says so.

## Ingest a file

1. `list_pipelines`. If one fits, skip to 3.
2. `create_pipeline` with the file as the sample, a destination, and the project's `catalog`. Keep the config to source plus destination unless the user asked for rules or transformations.
3. `upload_data` with the content.
4. `get_job_status` with the returned token. Poll with `wait_seconds` until `rollup.allDone`. Read `rollup.status`; on `error` or `warning`, `rollup.jobs[].lastError` names the failing step.

## Onboard an external source with a tap

1. `list_taps`. If a tap covers the source, test or run it.
2. Credentials: `list_tap_secrets` and `get_tap_secret_fields` first. If nothing fits, `create_tap_secret`. The fields become env vars in the script.
3. `create_tap` with an `instruction`, or your own script exposing `fetch()`. Bind `secret_name`. Set `target_pipeline` (create it first if needed). For PDFs, Word, HTML into a vector pipeline, `tap_type="document"`.
4. `test_tap`. On failure read the error, fix, `create_tap` again with the same name, repeat until it passes.
5. `run_tap`. Read `persisted`. False: report `persistedReason` and stop. True: keep `publisherToken`.
6. `get_pipeline_status(publisher_token=...)` until `rollup.allDone`; then `rollup.status`.
7. Recurrence: `update_tap` with `cron_expression`. Later, pick a run in `get_tap_logs` and verify it with step 6.

Incremental sources: have the script commit a bookmark so each run fetches only what is new; inspect with `get_tap_state`, rewind or clear with `set_tap_state`. The `datris://tap-workflow-reference` resource covers the state contract and the CRON cookbook.

## Build and query a document index (RAG)

1. `create_pipeline` with an `unstructuredAttributes` source and a vector destination, in the project's catalog.
2. `create_tap` with `tap_type="document"` describing where the documents are. Secret first if the source needs one.
3. `test_tap`, then `run_tap`; read `persisted` as above.
4. `get_pipeline_status(publisher_token=...)`. Document taps fan out to one job per file; poll until all are done and check each `lastError`.
5. `get_tap_ledger` to see what was discovered and processed. Unchanged files are skipped on later runs; `clear_uri` or `clear_all` forces reprocessing.
6. `search_<store>` to retrieve chunks; `ai_answer` with those chunks and their provenance handles as `sources`.

## Find and query existing data

1. `find_data` with a plain-language description. The result names the pipeline, its freshness and the query tool to use.
2. Or browse: `list_postgres_databases`, `list_postgres_schemas`, `list_postgres_tables`, `list_postgres_columns` (equivalents for MongoDB and each vector store).
3. Query with the matching `query_*` or `search_*` tool, or `query_natural`.
4. When provenance matters, rows carry `_datris_run_id`; `get_provenance` resolves it.

## Answer now without landing anything (Live Read)

1. `create_pipeline` with destination `scratch` (reuse one if it exists).
2. `upload_data` or `run_tap` into it.
3. `get_pipeline_status`; the rollup carries `resultPreview`. If `resultTruncated`, page with `get_pipeline_result`.
4. If the rows are worth keeping, switch the destination; do not re-engineer the tap.

## Monitor scheduled taps

1. `list_taps`, then `get_tap_logs` for the tap.
2. For each run of interest, `get_pipeline_status(publisher_token=...)`; the tap log proves the script ran, the rollup proves rows landed.
3. On error, read `rollup.jobs[].lastError`. Fix the script via `create_tap`, retarget via `update_tap`, or check `list_incidents` to see whether the platform is already working the failure.

## Before changing or deleting anything

1. `get_lineage` on the node, downstream, to see what depends on it.
2. `get_agent_policy` to learn whether the action runs, queues or is refused.
3. Make the call with a `reason`. If it returns `pending_approval`, say so and stop unless the user asked you to wait on `get_approval`.
