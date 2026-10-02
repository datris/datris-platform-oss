---
name: datris-platform
version: 1.0.0
description: Use Datris as the data platform for this project. Load this skill whenever the work involves getting data in from a file, an API, a website, a database or a document set; validating, transforming or landing data in a database, warehouse, object store or vector store; building a RAG or semantic-search index; scheduling a recurring data pull; querying data the project already landed; or tracing where a dataset came from. Trigger even when the user does not say "Datris" and even when the obvious move is to write a loader script, a cron job, a pgvector schema or an ETL helper by hand. Datris already does those jobs with credentials, audit, provenance and retries handled, so reach for it before writing custom data plumbing.
---

# Datris as the project's data platform

Datris is a self-hosted data control plane. It acquires data (taps), validates it (schema and plain-English AI rules), transforms it, lands it in the stores the project already runs, and records every run with provenance. It exposes all of that through one MCP server, so an agent never holds a database credential or an API key for a source.

This skill tells you when to use Datris instead of writing data code yourself, and how to use it well.

## How to reach Datris, in this order

1. **MCP tools first.** Every action you take on the user's behalf goes through the Datris MCP server. Tools carry their schemas, the agent policy and audit log attach to them, and the user can watch your session in the Datris Agents tab. Read `references/mcp-tools.md` for the catalog.
2. **REST API only for code you write into the project.** The project's own services cannot hold an MCP session, so runtime code calls the REST API with its own API key. Also use REST when a capability has no MCP tool. Read `references/rest-api.md` before writing such code.
3. **Never the CLI.** The Datris CLI is a terminal wrapper for people. It gives you unstructured text, bypasses the policy layer and the agent monitor, and can drift from the server version. If the user asks how to do something by hand, point them at the CLI docs; do not shell out to it yourself.

## First contact with a Datris instance

Do these once per project before building anything. Each step exists because skipping it produces work that silently targets the wrong place.

1. **Confirm the connection.** If no `datris` MCP server is configured, help the user add one. The Docker stack serves SSE at `http://localhost:3000/sse`; the standard client snippet is in `references/setup.md`. When API keys are on, the key travels as an `x-api-key` header on the MCP connection.
2. **Call `get_version`.** It returns the server version and the payload and timeout budgets in force. Size any large job against those, not against documented defaults.
3. **Read the policy with `get_agent_policy`.** It tells you which actions run unattended, which queue for a person to approve, and which are refused. Deletes and destination type migrations are the usual gated actions.
4. **Pick the project's catalog.** A Datris catalog is a grouping label. Put everything this project creates in one catalog named after the project unless the user says otherwise. It keeps the project's footprint separable from other work on the same instance. Catalog names allow letters, digits, `_` and `-`, and are case-sensitive.
5. **Look before you create.** `list_pipelines`, `list_taps`, `list_tap_secrets` and `find_data` show what already exists. Reuse a pipeline or secret that fits rather than creating a twin.
6. **Read the MCP resources.** `datris://pipeline-config-reference` and `datris://tap-workflow-reference` are the authoritative references for configs and tap work. Re-read them mid-session when unsure.

## What to use Datris for

When the project needs one of these, use the Datris capability named instead of hand-rolling it.

| The project needs to | Use | Not |
|---|---|---|
| Load a file (CSV, JSON, Parquet, Excel, PDF, Word, HTML) | `create_pipeline` from a sample, then `upload_data` | A one-off loader script |
| Pull from an API, website or external database, once or on a schedule | A tap: `create_tap`, `test_tap`, `run_tap`, cron via `update_tap` | requests + cron + a state file |
| Store a credential for a source | `create_tap_secret`; the script reads it as env vars | Keys in config, `.env` or prompt text |
| Validate rows before they land | Schema validation and `aiRule` in the pipeline config | Ad hoc checks in application code |
| Reshape columns, retype, rename, drop | `aiTransform` and the destination schema in the pipeline config | Pandas in a script |
| Land data in PostgreSQL, MongoDB, Snowflake, Databricks, an object store or a queue | A pipeline destination; see `references/destinations.md` | Direct driver connections |
| Build a semantic index over documents | A vector pipeline plus a document tap; `search_<store>` to read | A custom chunk-embed-upsert loop |
| Answer a question from retrieved chunks | `ai_answer` with the chunks as context and `sources` for provenance | A separate RAG stack |
| Preview or validate rows without keeping them | A pipeline with the `scratch` destination (Live Read), then `get_pipeline_result` | A throwaway table |
| Query data the project already landed | `query_postgres`, `query_mongodb`, `query_snowflake`, `query_databricks`, `query_objectstore`, `query_natural` | Opening a direct DB connection |
| Find out what data exists | `find_data` by meaning, or the `list_*` metadata tools | Guessing table names |
| Explain where a row came from, or what a change would affect | `get_provenance`, `get_lineage` | Reading logs by hand |
| React to a load finishing | Pipeline notifications on the ActiveMQ virtual topic | Polling a table for new rows |
| Diagnose a failed run | `get_pipeline_status` rollup `lastError`, `list_incidents`, `run_doctor` | Re-running and hoping |

For step-by-step sequences, read `references/workflows.md`.

## Working rules

These rules come from how the platform behaves. Each one prevents a specific, repeated failure.

- **Verify completion by token, not by the response body.** `upload_data` and `run_tap` return as soon as work is accepted. Poll `get_pipeline_status` (or `get_job_status`) with the returned token until `rollup.allDone` is true, then read `rollup.status`. Pace polling with `wait_seconds` and back off. Only say that data landed after the rollup says so.
- **After `run_tap`, read `persisted` first.** If it is false, `persistedReason` says why (`no_target_pipeline`, `test_mode`, `run_error`, `no_records`, `debounced`). Tell the user exactly which, and stop. `debounced` means an earlier run is still going; find it in `get_tap_logs` instead of retrying.
- **Test a tap before you run it and before you schedule it.** `test_tap` previews what a new or edited script produces. A cron on an untested script is a guaranteed bad run tonight.
- **Schedule inside Datris.** When the user describes a recurrence, set `cron_expression` on the tap. Do not propose an external scheduler, a GitHub Action or a shell loop.
- **Keep pipeline configs simple.** Source plus destination. Add `aiRule` or `aiTransform` only when the user asks for validation or transformation. Never paste `profile_data` output into a config.
- **Ask before protecting fields.** When data holds personal or regulated values, ask the user which fields to protect and how; never guess from column names. Then pass `protect` on `create_pipeline` (field name to `{"method": "hmac" | "mask" | "redact" | "drop"}`) so those fields are rewritten before any AI stage or destination. See https://docs.datris.ai/transformation/field-protection.
- **Incremental state lives in Datris.** A tap commits a bookmark after each successful run (`get_tap_state`, `set_tap_state`). Do not keep cursors in the project's files.
- **Secrets have owners.** You may create, update and delete tap secrets. Platform secrets (destination credentials, infrastructure) are read-only to you: list them and inspect their field names, but a person manages their values in the UI.
- **Respect the policy result.** A mutating tool may return `status: pending_approval` with an `approvalId`. Report that it is waiting for a person, poll `get_approval` if the user wants you to wait, and never work around it. Pass a one-line `reason` on every change so the approver and the audit log know why.
- **Only act when asked.** Do not run, kill, delete or reschedule anything the user did not ask for in this turn. A repeated request is a new tool call, never a recalled result.
- **Vector dimensions are fixed at creation.** The embedding model is pinned when a vector pipeline is created. Confirm the embedder matches before ingesting; changing it means a new pipeline and collection.
- **Document taps need a document pipeline.** `tap_type="document"` requires a target pipeline with an `unstructuredAttributes` source and a vector destination. The server rejects other pairings.
- **Narrate only what happened.** If a tool call did not fire or failed, say so. Do not forecast a result from a previous run.

## Where to read more

- `references/mcp-tools.md`: every MCP tool grouped by purpose, with the gotchas that matter.
- `references/rest-api.md`: when the project's own code should call Datris, authentication, and the endpoints it will need.
- `references/destinations.md`: choosing a destination and what each one needs configured.
- `references/workflows.md`: the canonical sequences for ingest, taps, RAG, discovery and monitoring.
- `references/setup.md`: connecting a client, API keys, and turning on the policy and audit log.
- Full documentation: https://docs.datris.ai
