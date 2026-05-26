#!/usr/bin/env python3
"""
Datris MCP Server

MCP server for AI-driven data platform operations — ingest, validate, transform,
analyze, and query data from enterprise sources. 32 tools covering ETL orchestration,
AI-generated data quality rules (Python codegen via LLM), vector database search,
and secrets-managed connectivity. Supports PostgreSQL, MongoDB, Kafka, S3/MinIO,
HashiCorp Vault, and vector stores (Qdrant, Weaviate, Milvus, Chroma, pgvector).

Usage:
    pip install -r requirements.txt

    # stdio mode (for Claude Desktop / Claude Code)
    python server.py

    # SSE mode (for Docker / remote agents)
    python server.py --sse --port 3000

    # Streamable HTTP mode (for Smithery / remote clients)
    python server.py --streamable-http --port 3000
"""

import argparse
import asyncio
import contextvars
import json
import os
import threading
import time
import uuid
from collections import deque
from typing import Any

import requests
from dotenv import load_dotenv
from mcp.server import Server
from mcp.types import Resource, Tool, TextContent

load_dotenv()

DATRIS_API_URL = os.getenv("DATRIS_API_URL", "http://localhost:8080")
REQUIRE_API_KEY = os.getenv("REQUIRE_API_KEY", "").lower() in ("true", "1", "yes")
WEBSITE_URL = os.getenv("WEBSITE_URL", "https://datris.ai")

# Per-session API key for multi-tenant SSE/HTTP connections
_session_api_key: contextvars.ContextVar[str] = contextvars.ContextVar("_session_api_key", default="")
# Per-session id used for agent-monitor attribution
_session_id: contextvars.ContextVar[str] = contextvars.ContextVar("_session_id", default="")

# Agent-monitor: in-process activity buffer + session tracker.
# Ephemeral; cleared on restart. Safe to read via a plain lock.
_activity_buffer: deque = deque(maxlen=200)
_activity_sessions: dict[str, dict[str, Any]] = {}
_activity_lock = threading.Lock()
# Zombie-session reaper threshold. _activity_session_open/close already track
# the real SSE/HTTP connection lifecycle, so this is just a safety net for
# sessions whose close handler never fires (server crash, stdio bridge oddities).
# Keep it well above any realistic gap between tool calls — an agent thinking
# between user prompts should not disappear from the monitor.
SESSION_IDLE_SECS = 600


_PREVIEW_ARG_KEYS = [
    "pipeline", "tap", "name", "collection", "query", "question",
    "text", "limit", "top_k", "page", "file", "filename",
]
_PREVIEW_VALUE_MAX = 40
_PREVIEW_MAX = 140


def _build_args_preview(args: Any) -> str:
    if not isinstance(args, dict):
        return ""
    parts: list[str] = []
    for key in _PREVIEW_ARG_KEYS:
        if key not in args:
            continue
        val = args[key]
        if isinstance(val, (dict, list)):
            s = "…"
        else:
            s = str(val).replace("\n", " ").strip()
            if len(s) > _PREVIEW_VALUE_MAX:
                s = s[:_PREVIEW_VALUE_MAX - 1] + "…"
        parts.append(f"{key}={s}")
        if len(parts) == 3:
            break
    preview = ", ".join(parts)
    if len(preview) > _PREVIEW_MAX:
        preview = preview[:_PREVIEW_MAX - 1] + "…"
    return preview


def _pretty_json(value: Any, max_chars: int | None = None) -> str:
    """Pretty-print a Python object as indented JSON. When max_chars is set,
    truncate with an ellipsis; pass None to keep the full string."""
    try:
        s = json.dumps(value, indent=2, default=str)
    except Exception:
        s = str(value)
    if max_chars is not None and len(s) > max_chars:
        s = s[:max_chars] + "\n…"
    return s


def _pretty_json_text(text: str, max_chars: int | None = None) -> str:
    """Pretty-print a string that may or may not already be JSON.
    Falls back to the raw string if parsing fails. When max_chars is set,
    truncate with an ellipsis; pass None to keep the full string."""
    if not text:
        return ""
    try:
        parsed = json.loads(text)
        s = json.dumps(parsed, indent=2, default=str)
    except Exception:
        s = text
    if max_chars is not None and len(s) > max_chars:
        s = s[:max_chars] + "\n…"
    return s


def _record_count_from_result(result_text: str) -> int | None:
    try:
        parsed = json.loads(result_text)
    except Exception:
        return None
    if isinstance(parsed, list):
        return len(parsed)
    if isinstance(parsed, dict):
        for key in ("pipelines", "taps", "results", "items", "records", "data", "rows"):
            val = parsed.get(key)
            if isinstance(val, list):
                return len(val)
    return None


def _read_client_info() -> tuple[str, str]:
    """Read MCP clientInfo (name, version) from the current request context.
    Returns ("", "") when unavailable (e.g. stdio-only bootstrap, or SDK internals changed)."""
    try:
        ctx = server.request_context
        if ctx is None:
            return ("", "")
        session = getattr(ctx, "session", None)
        if session is None:
            return ("", "")
        # Try common attribute names across MCP SDK versions.
        params = getattr(session, "client_params", None) or getattr(session, "_client_params", None)
        if params is None:
            return ("", "")
        ci = getattr(params, "clientInfo", None)
        if ci is None:
            return ("", "")
        name = getattr(ci, "name", "") or ""
        version = getattr(ci, "version", "") or ""
        return (name, version)
    except Exception:
        return ("", "")


def _activity_record(session_id: str, tool: str, status: str, latency_ms: int, api_key: str,
                     args: Any, result_text: str, error_msg: str) -> None:
    now = time.time()
    api_key_hint = api_key[:6] if api_key else ""
    client_name, client_version = _read_client_info()
    args_preview = _build_args_preview(args)
    args_full = _pretty_json(args) if args else ""
    response_preview = ""
    response_size = 0
    if result_text:
        response_size = len(result_text)
        response_preview = _pretty_json_text(result_text)
    record_count = _record_count_from_result(result_text) if status == "ok" else None
    error = ""
    if error_msg:
        error = error_msg if len(error_msg) <= 300 else error_msg[:300] + "…"

    with _activity_lock:
        _activity_buffer.append({
            "ts": now,
            "session_id": session_id,
            "tool": tool,
            "status": status,
            "latency_ms": latency_ms,
            "api_key_hint": api_key_hint,
            "client_name": client_name,
            "client_version": client_version,
            "args_preview": args_preview,
            "args_full": args_full,
            "response_preview": response_preview,
            "response_size": response_size,
            "record_count": record_count,
            "error": error,
        })
        sess = _activity_sessions.get(session_id)
        if sess is None:
            _activity_sessions[session_id] = {
                "session_id": session_id,
                "first_seen": now,
                "last_seen": now,
                "call_count": 1,
                "api_key_hint": api_key_hint,
                "client_name": client_name,
                "client_version": client_version,
            }
        else:
            sess["last_seen"] = now
            sess["call_count"] += 1
            if api_key_hint:
                sess["api_key_hint"] = api_key_hint
            if client_name and not sess.get("client_name"):
                sess["client_name"] = client_name
                sess["client_version"] = client_version


def _activity_session_open(session_id: str, api_key: str) -> None:
    now = time.time()
    api_key_hint = api_key[:6] if api_key else ""
    with _activity_lock:
        _activity_sessions[session_id] = {
            "session_id": session_id,
            "first_seen": now,
            "last_seen": now,
            "call_count": 0,
            "api_key_hint": api_key_hint,
        }


def _activity_session_close(session_id: str) -> None:
    with _activity_lock:
        _activity_sessions.pop(session_id, None)


def _activity_clear() -> None:
    """Wipe the activity buffer. Live session rows are left untouched —
    those represent currently-connected MCP clients, not historical events."""
    with _activity_lock:
        _activity_buffer.clear()


def _activity_snapshot(since: float) -> dict[str, Any]:
    now = time.time()
    with _activity_lock:
        active_sessions = [
            s for s in _activity_sessions.values()
            if (now - s["last_seen"]) < SESSION_IDLE_SECS
        ]
        calls = [c for c in _activity_buffer if c["ts"] > since]
        return {
            "server_time": now,
            "sessions": sorted(active_sessions, key=lambda s: s["first_seen"]),
            "calls": calls,
        }

server = Server("datris", instructions="""\
Datris is the first AI Agent-Native Data Platform. It ingests, validates, transforms, and routes data to databases, message queues, and vector stores — all driven by pipeline configurations that AI agents can create and manage programmatically.

FIRST-RESPONSE RULE (read this before anything else):
When the user makes ANY data-related ask — "I'm looking for X", "can you get me Y", "do you have Z", "I need data about W", "help me ingest...", or anything similar — your FIRST tool call MUST be `list_pipelines` AND `list_taps`. Do this BEFORE generating any text reply. Do this BEFORE suggesting external sources (SEC EDGAR, yfinance, Alpha Vantage, etc.). Do this BEFORE asking clarifying scope questions. The user is connected to a Datris environment that likely already has the data they want — assume YES until your tool calls prove otherwise.

After those calls return, anchor your reply in what exists: "There's already a `<name>` pipeline doing X — does that cover your need, or do you want to extend it / add Y / pick a different source?" Only enumerate external API options after you've confirmed nothing in the platform already covers the ask. A generic options menu drawn from training data wastes the user's time when the answer is sitting in their own environment.

SCHEDULING RULE (read this before suggesting any recurring/timely workflow):
If the user mentions ANY recurrence cue — "nightly", "daily", "hourly", "every morning", "weekly", "at market open", "on a schedule", "recurring", "on a timely basis", "keep this up to date", "refresh this every X" — set a `cron_expression` on the relevant tap via `create_tap` (when first creating) or `update_tap` (when wiring an existing tap). The Datris platform runs the scheduler — once you set `cron_expression`, the tap fires automatically on that cadence, with the same publisherToken + get_tap_logs verification path as manual runs.
DO NOT respond with shell snippets, cron jobs, Airflow DAGs, or any other external scheduler that just invokes the CLI or the API on a timer. That defeats the platform: the user delegated both "what data" and "when it refreshes" to Datris. Handing back a "run this every night at 9pm" command pushes operational burden the user already chose to offload. The schedule lives on the tap.
After setting the schedule, tell the user what you set ("scheduled `canslim_screen` for 0 30 5 * * ? — runs daily at 5:30am") and offer to adjust the cadence or chain related taps.

VALIDATION RULE (read this before the first run of any new or updated tap):
Before calling `run_tap` AND before setting `cron_expression` on a tap whose script has not yet been validated, you MUST call `test_tap`. This applies to:
  - Any tap just created via `create_tap` (whether AI-generated from `instruction` or user-supplied via `script`)
  - Any tap whose script was just replaced (call `create_tap` again with the same name and a new `script` or `instruction` — create_tap upserts by name)
If `test_tap` fails, fix the script (call `create_tap` again with a corrected `instruction` or revised `script`) and re-test until it succeeds. ONLY THEN call `run_tap` or set `cron_expression`. Setting a cron on an untested script ships a guaranteed-bad nightly run; the user delegated the schedule to Datris, not the validation to luck.
Existing taps that have run successfully do NOT need a fresh `test_tap` for cadence-only changes (`cron_expression`, `target_pipeline`, `enabled` toggle) — only when the script itself just changed.

EVIDENCE RULE (read this before summarizing what you did):
NEVER narrate a create / update / delete / run operation as completed unless the corresponding tool call appears in THIS turn. The collapsed tool-call blocks in your message are the ONLY evidence that work actually happened — your prose must match them. Specifically:
  - If you intended to set up a pipeline + tap but only called `create_tap`, do NOT write "pipeline and tap are live." Write what's true: "tap created; pipeline still needs to be created" and then call `create_pipeline`.
  - If a tool call returned an error or unexpected response, do NOT paper it over with confident success language. Surface the actual response shape (error string, persistedReason, etc.) and decide the next step from that.
  - After multi-step setups (e.g., create pipeline → create tap → test → run), enumerate explicitly what completed and what didn't BEFORE summarizing. "Pipeline X: created ✓. Tap Y: created ✓, tested ✓ (7 records). Tap not yet run — say the word."
  - When in doubt — when you intended to do N steps and you can't enumerate the N tool calls in this turn — STOP and verify. Call `list_pipelines` / `list_taps` / `get_tap` to ground yourself in actual platform state before claiming anything is done.
The user trusts your narrative as a proxy for the platform state. Confabulating "done" when only some of it is done corrupts that trust and creates failure modes (a cron set on a tap whose pipeline doesn't exist; a "run now" call against a pipeline that was never created). Be honest about what happened in THIS turn, even if it's less than the user asked for — they can redirect, but only if your report is true.

A pipeline config has two required sections: source and destination. Keep configs simple: source + destination only.

NEVER rules:
  - NEVER respond to a data-related ask without first calling list_pipelines and list_taps (see FIRST-RESPONSE RULE above)
  - NEVER respond to a recurrence/timely ask with shell commands, external cron, or off-platform schedulers — set a `cron_expression` on the tap (see SCHEDULING RULE above)
  - NEVER call `run_tap` or set `cron_expression` on a tap whose current script hasn't been validated by a successful `test_tap` (see VALIDATION RULE above)
  - NEVER narrate a create/update/delete/run as completed without the corresponding tool call in THIS turn (see EVIDENCE RULE above). If you intended to do N things and only did some, say which and finish the rest.
  - NEVER use profile_data to determine how to generate a pipeline configuration
  - NEVER add dataQuality or transformation sections unless explicitly requested
  - If data quality is needed, use codegen_rule on create_pipeline (plain-English validation instruction)
  - If transformation is needed, use codegen_transform on create_pipeline (plain-English transformation instruction)

Required workflow:
  1. Check existing pipelines and taps: call list_pipelines and list_taps. If a pipeline exists, data may already be in the destination — use metadata tools to discover and query it directly. If a tap exists, use run_tap or test_tap directly. Only create new pipelines or taps if needed.
  2. Create a pipeline: call create_pipeline. For STRUCTURED destinations (postgres, mongodb), pass sample data (base64-encoded) + filename so the schema is auto-detected. For VECTOR destinations (pgvector, qdrant, weaviate, milvus, chroma), pass ONLY pipeline name + destination — there is no schema, and base64'ing the document here just to satisfy the call is wasted tokens (the document goes through upload_data instead).
     create_pipeline UPSERTS by name: if a pipeline with the same name already exists, the call REPLACES its config in place — the data already in the destination is NOT touched. To change a knob (keyFields, truncate, codegen_rule, etc.) on an existing pipeline, just call create_pipeline again with the same name and the new settings. You do NOT need to delete first.
     Common knobs: keyFields (list of column names that act as a natural key for dedupe/upsert on every run), truncate (wipe the destination before each run), codegen_rule (AI-powered data quality), codegen_transform (AI-powered transformation).
  3. Ingest data (choose one):
     Option A — Direct upload: call upload_data ONCE with the entire base64-encoded content and the pipeline name. Do NOT split the content into multiple uploads — vector destinations (pgvector, qdrant, weaviate, milvus, chroma) chunk server-side, and structured pipelines accept the whole file as one batch.
     Option B — Create a tap: use create_tap to provide an instruction (AI generates the script) or your own Python script that fetches data from an external source and pushes it into the pipeline automatically. See Tap workflow below.
  4. Monitor: call get_job_status with the pipelineToken returned from upload_data and poll until `rollup.allDone` is true. You MUST wait for that before querying.
  5. If `rollup.status` is `error` or `warning`: read `rollup.jobs[].lastError` for the failing process and description. Fix the issue (e.g., delete the pipeline, re-create with corrected parameters, re-upload).
  6. Query & search: use query_postgres, query_mongodb for structured data; search_qdrant, search_pgvector, etc. for vector search
  7. RAG: pass search results as context to ai_answer with the user's question

Tap workflow (for step 3 Option B):
  Taps are Python scripts that fetch data from external sources and push it into pipelines. Use taps when data needs to be pulled from APIs, websites, databases, or other external sources on demand or on a schedule.
  Two ways to create a tap:
    - With instruction: provide a plain-English instruction and the platform's AI generates the script. This is slower (1-2 minutes) because the platform must generate and store the script.
    - With your own script: write the Python fetch() function yourself and pass it as the script parameter. This is faster and gives you full control. The script must define a fetch() function that takes no arguments and returns a list of dictionaries.
  Writing the script yourself is often quicker and more reliable — you control the logic directly instead of waiting for AI generation and hoping it gets the implementation right on the first try.
  1. Create a tap: call create_tap with an instruction (AI generates the script) or with your own script
  2. Test (MANDATORY for new or updated scripts): call test_tap to validate the script without pushing data. See the VALIDATION RULE — skipping this step means a scheduled cron could ship a guaranteed-bad nightly run, or a manual `run_tap` could push broken data into the destination.
  3. If test fails: read the error, fix the script, and call create_tap again with a corrected script or updated instruction to regenerate. Repeat test until it succeeds.
  4. Run: call run_tap to execute and push data to the pipeline.
     After `run_tap` returns, READ the response's `persisted` field BEFORE doing anything else:
       - `persisted: true` → records were handed to the pipeline, but the load is async. You MUST call `get_pipeline_status(publisher_token=response.publisherToken)` and poll (re-call every few seconds) until `rollup.allDone` is true. Only THEN query the destination or report completion to the user. Reporting success before the poll finishes is a bug — the destination will appear empty. Read `rollup.status` for the outcome and `rollup.jobs[].lastError` for any failures.
       - `persisted: false` → records did NOT land in the destination. Read `persistedReason` and tell the user exactly why: `no_target_pipeline` (call update_tap to set one, then re-run), `test_mode` (you ran it in test mode — or mcp-server/datris are out of sync; flag it and stop), `run_error` (show the `error` string), `no_records` (source returned nothing).
     Note: `run_tap` does NOT return the records themselves (only `recordCount`). If you need to preview what the script produces, use `test_tap`.
  5. Schedule: if the user described any recurrence (nightly, daily, every morning, market open, etc.), set `cron_expression` — on `create_tap` if you're creating the tap now, or via `update_tap` if the tap already exists. The platform runs the scheduler; once set, the tap fires automatically and shows up in `get_tap_logs` exactly like manual runs. NEVER substitute external schedulers (shell cron, Airflow DAGs invoking the CLI, "run this every night at 9pm" shell snippets) — see the SCHEDULING RULE.
  6. Verify ingestion outcome — the same check works for any run, manual or scheduled. The `publisherToken` is your handle on whether the data actually landed in the destination. After a manual `run_tap` you already have it in the response; for a scheduled (cron) run, call `get_tap_logs` and pick the relevant entry — every log entry that submitted records includes its `publisherToken`. Either way, call `get_pipeline_status(publisher_token=...)` and poll until `rollup.allDone` is true; then `rollup.status` tells you success/warning/error and `rollup.jobs[].lastError` tells you which file failed and why. The tap log only tells you the script ran; the publisher token is how you trace it through to whether the destination actually has the data. Prefer `get_tap_logs` over holding the token in your own context across many turns — if the conversation is compressed or you reconnect, you can always re-derive the token from the log.
  7. Manage: call update_tap to enable/disable, change schedule, or retarget pipeline; call get_tap to view details and script

Long-form references — read on demand to verify your mental model:
  - `datris://pipeline-config-reference` — pipeline source/destination shapes, codegen rules, vector vs structured.
  - `datris://tap-workflow-reference` — full tap workflow: creation, params, SCHEDULING RULE with CRON cookbook, run flow, error handling (`persistedReason` table, size limits), document taps, outcome verification. Re-read this any time you're unsure about how taps work or what the platform expects.

Do NOT call check_service_health as part of the normal workflow — it is slow. Only use it for diagnostics if something fails.
Do NOT call update_secret unless you need to configure AI provider keys and they are not already set.
""")


def _effective_api_key() -> str:
    """Return the per-session API key. The agent that connected to this MCP
    session is responsible for providing its own credentials via the
    `x-api-key` header — there is no server-side fallback. Calls made before a
    session establishes a key (rare) return an empty string and the downstream
    Datris API decides whether to accept the request based on its own
    `useApiKeys` setting."""
    return _session_api_key.get()


def _headers():
    """Build request headers."""
    h = {"Content-Type": "application/json"}
    key = _effective_api_key()
    if key:
        h["x-api-key"] = key
    return h


def _call(method, path, **kwargs):
    """Make an HTTP request to the pipeline API."""
    url = f"{DATRIS_API_URL}{path}"
    try:
        resp = getattr(requests, method)(url, headers=_headers(), timeout=300, **kwargs)
        return resp.text
    except requests.RequestException as e:
        return json.dumps({"error": str(e)})


def _upload(path, file_path, data=None):
    """Upload a file via multipart POST to the pipeline API (local file path)."""
    with open(file_path, "rb") as f:
        files = {"file": (os.path.basename(file_path), f)}
        h = {}
        key = _effective_api_key()
        if key:
            h["x-api-key"] = key
        resp = requests.post(
            f"{DATRIS_API_URL}{path}",
            headers=h,
            files=files,
            data=data or {},
            timeout=300
        )
        return resp.text


def _upload_content(path, content_b64, filename, data=None):
    """Upload base64-encoded content via multipart POST to the pipeline API."""
    import base64
    import tempfile

    # Fix missing base64 padding
    padded = content_b64 + "=" * (-len(content_b64) % 4)
    file_bytes = base64.b64decode(padded)
    with tempfile.NamedTemporaryFile(delete=False, suffix=f"_{filename}") as tmp:
        tmp.write(file_bytes)
        tmp_path = tmp.name
    try:
        with open(tmp_path, "rb") as f:
            files = {"file": (filename, f)}
            h = {}
            key = _effective_api_key()
            if key:
                h["x-api-key"] = key
            resp = requests.post(
                f"{DATRIS_API_URL}{path}",
                headers=h,
                files=files,
                data=data or {},
                timeout=300
            )
            return resp.text
    finally:
        os.unlink(tmp_path)


# ---------------------------------------------------------------------------
# MCP Resources
# ---------------------------------------------------------------------------

PIPELINE_CONFIG_REFERENCE = """\
# Datris Pipeline Configuration Reference

## Required Workflow — Follow These Steps

1. Call `list_pipelines` to check if the pipeline already exists.
2. If it exists, data may already be in the destination. Use metadata tools (`list_postgres_tables`, `list_mongodb_collections`, `list_qdrant_collections`, etc.) to discover it and query/search directly. Only re-ingest if the data is stale or needs updating.
3. If the pipeline does not exist, call `create_pipeline`. Structured destinations (postgres, mongodb) need sample data (base64) for schema auto-detection. Vector destinations (pgvector/qdrant/weaviate/milvus/chroma) need only pipeline name + destination — no sample content; the document goes through `upload_data`.
5. Call `check_service_health` to verify the target destination service is available.
6. Call `create_pipeline` with the config.
7. Call `upload_data` ONCE with your full data (base64-encoded) and the pipeline name. Do not pre-chunk — vector destinations chunk server-side, structured pipelines accept the whole file as one batch.
8. Call `get_job_status` with the pipelineToken returned from `upload_data` to monitor processing. Poll until `rollup.allDone` is true, then read `rollup.status` (`success` | `warning` | `error`).
9. Query or search the data: `query_postgres`, `query_mongodb`, `search_qdrant`, `search_pgvector`, etc.
10. For RAG: pass search results as context to `ai_answer` with the user's question.

---

## Config Structure

Pass this JSON to `create_pipeline`. Only `name`, `source`, and `destination` are required. For reliable data sources, do NOT add dataQuality, transformation, or preprocessor sections — keep it simple. Only add those sections if the data source is unreliable or you have a specific processing requirement.

```json
{
  "name": "pipeline_name",
  "source": { ... },
  "preprocessor": { ... },
  "dataQuality": { ... },
  "transformation": { ... },
  "destination": { ... }
}
```

## Source

Set `source.fileAttributes` to one of these (create_pipeline does this automatically):

### fileAttributes — choose one

**csvAttributes** — use for CSV/TSV files:
```json
"csvAttributes": {
  "delimiter": ",",
  "header": true,
  "encoding": "UTF-8"
}
```

**jsonAttributes** — for JSON files:
```json
"jsonAttributes": {
  "everyRowContainsObject": false,
  "encoding": "UTF-8"
}
```

**xmlAttributes** — for XML files:
```json
"xmlAttributes": {
  "everyRowContainsObject": false,
  "encoding": "UTF-8"
}
```

**xlsAttributes** — for Excel files:
```json
"xlsAttributes": {
  "worksheet": 0,
  "tempCsvFileDelimiter": ","
}
```

**unstructuredAttributes** — use for PDFs, DOCX, TXT. Must use a vector DB destination:
```json
"unstructuredAttributes": {
  "fileExtension": "pdf",
  "preserveFilename": true
}
```

### schemaProperties — set field names and types (create_pipeline does this automatically, omit for unstructured)

```json
"schemaProperties": {
  "fields": [
    {"name": "column_name", "type": "string"},
    {"name": "price", "type": "double"},
    {"name": "quantity", "type": "int"}
  ]
}
```

Supported field types: `string`, `int`, `bigint`, `float`, `double`, `boolean`, `date`, `timestamp`

### streamAttributes (optional, for streaming sources like Kafka)

```json
"streamAttributes": {
  "type": "kafka"
}
```

### databaseAttributes (optional, for database pull sources)

```json
"databaseAttributes": {
  "type": "postgres",
  "postgresSecretsName": "oss/postgres",
  "database": "mydb",
  "schema": "public",
  "table": "source_table",
  "cronExpression": "0 0 * * *",
  "timestampFieldName": "updated_at",
  "includeFields": ["col1", "col2"],
  "sqlOverride": "SELECT * FROM source_table WHERE active = true"
}
```

## Preprocessor (optional)

Set this to call an external REST endpoint before processing. Use for custom validation or enrichment.

```json
"preprocessor": {
  "endpoint": "https://my-service.example.com/preprocess",
  "bearerToken": "token123",
  "apiKey": "key123",
  "timeoutMs": 300000,
  "async": false
}
```

## Data Quality (optional — only if explicitly requested)

Use `codegen_rule` on `create_pipeline` for AI-powered validation. Datris generates a Python script from the instruction and runs it locally.

For JSON/XML schema validation, use `validationSchema` (upload schema file first with `upload_config`).

For CSV header validation, use `validateFileHeader: true`.

## Transformation (optional — only if explicitly requested)

Use `codegen_transform` on `create_pipeline` for AI-powered transformation. Datris generates a Python script from the instruction and runs it locally.

Example config (for reference only — agents should use create_pipeline params, not construct configs):

```json
"transformation": {
  "aiTransformation": {
    "instruction": "convert all dates to YYYY-MM-DD format"
  }
}
```

## Destination (required — set one)

Call `check_service_health` first to verify the target service is available.

### database — PostgreSQL (use for structured CSV data)

```json
"destination": {
  "database": {
    "dbName": "datris",
    "schema": "public",
    "table": "my_table",
    "usePostgres": true,
    "keyFields": ["id"],
    "truncateBeforeWrite": false,
    "manageTableManually": false
  }
}
```

**keyFields semantics (Postgres):** When `keyFields` is set and `truncateBeforeWrite` is false, the loader switches to an upsert path — COPY into a session-local staging table, then `INSERT ... SELECT ... ON CONFLICT (keyFields) DO UPDATE SET <non_key_cols> = EXCLUDED.<non_key_cols>`. Same external contract as Mongo's keyFields upsert.

- **First load on a fresh table:** the table is created with a `PRIMARY KEY (keyFields)`. Subsequent loads upsert against that key.
- **Retrofitting onto an existing table:** if the table predates the `keyFields` config and lacks a matching unique constraint, the loader auto-adds a `UNIQUE INDEX` on the keyFields. If existing rows already violate the proposed uniqueness, the index creation fails and the loader surfaces a clear error with remediation hints (deduplicate manually, set `truncateBeforeWrite=true`, or pick different keyFields).
- **NULL handling on conflict:** when an incoming row collides on the natural key, ALL non-key columns are overwritten with the incoming values, **including NULLs**. This is true upsert semantics, not non-null merge. If your source emits partial rows and you don't want NULLs to clobber existing values, coalesce upstream before the pipeline.
- **Performance:** the upsert path is meaningfully slower than raw COPY (extra staging round-trip plus the INSERT). Use `keyFields` only when you genuinely need natural-key dedupe; for append-only ingestion, leave it unset and let raw COPY do its thing.
- **Without keyFields:** raw COPY straight into the target table. Duplicate-key violations fail the load. Use `truncateBeforeWrite=true` for full-refresh pipelines, or leave both unset for pure append.

### database — MongoDB (use for JSON data)

```json
"destination": {
  "database": {
    "dbName": "datris",
    "table": "my_collection",
    "useMongoDB": true
  }
}
```

### objectStore — S3/MinIO

```json
"destination": {
  "objectStore": {
    "prefixKey": "data/output/",
    "fileFormat": "parquet",
    "writeMode": "overwrite",
    "partitionBy": ["date", "region"]
  }
}
```

File formats: `parquet`, `csv`, `json`, `orc`
Write modes: `overwrite`, `append`, `ignore`, `error`

### kafka

```json
"destination": {
  "kafka": {
    "topic": "my-topic",
    "keyField": "id"
  }
}
```

### activeMQ

```json
"destination": {
  "activeMQ": {
    "queueName": "my-queue"
  }
}
```

### restEndpoint

```json
"destination": {
  "restEndpoint": {
    "endpoint": "https://my-service.example.com/ingest",
    "bearerToken": "token123",
    "timeoutMs": 300000
  }
}
```

### Vector databases — use for RAG / semantic search with unstructured files (PDF, DOCX, TXT)

All vector DB destinations require chunking and embedding config. Call `check_service_health` to see which vector DBs are available.

**qdrant:**
```json
"destination": {
  "qdrant": {
    "collectionName": "my_documents",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports", "year": "2026"},
    "embeddingSecretName": "oss/embedding",
    "qdrantSecretName": "oss/qdrant"
  }
}
```

**weaviate:**
```json
"destination": {
  "weaviate": {
    "className": "MyDocuments",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports"},
    "embeddingSecretName": "oss/embedding",
    "weaviateSecretName": "oss/weaviate"
  }
}
```

**milvus:**
```json
"destination": {
  "milvus": {
    "collectionName": "my_documents",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports"},
    "embeddingSecretName": "oss/embedding",
    "milvusSecretName": "oss/milvus"
  }
}
```

**chroma:**
```json
"destination": {
  "chroma": {
    "collectionName": "my_documents",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports"},
    "embeddingSecretName": "oss/embedding",
    "chromaSecretName": "oss/chroma"
  }
}
```

**pgvector:**
```json
"destination": {
  "pgvector": {
    "tableName": "my_documents",
    "schemaName": "public",
    "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
    "metadata": {"source": "annual_reports"},
    "embeddingSecretName": "oss/embedding",
    "postgresSecretName": "oss/pgvector"
  }
}
```

## Example Configs — for reference only (create_pipeline generates these automatically)

### CSV → PostgreSQL with AI data quality

```json
{
  "name": "stock_prices",
  "source": {
    "fileAttributes": {
      "csvAttributes": {"delimiter": ",", "header": true, "encoding": "UTF-8"}
    },
    "schemaProperties": {
      "fields": [
        {"name": "symbol", "type": "string"},
        {"name": "date", "type": "string"},
        {"name": "close", "type": "double"},
        {"name": "volume", "type": "int"}
      ]
    }
  },
  "dataQuality": {
    "aiRule": {
      "instruction": "All price columns must be positive. Volume must be a positive integer.",
      "onFailureIsError": false
    }
  },
  "destination": {
    "database": {
      "dbName": "datris",
      "schema": "public",
      "table": "stock_prices",
      "usePostgres": true
    }
  }
}
```

### PDF → pgvector for RAG

```json
{
  "name": "financial_docs",
  "source": {
    "fileAttributes": {
      "unstructuredAttributes": {"fileExtension": "pdf", "preserveFilename": true}
    }
  },
  "destination": {
    "pgvector": {
      "tableName": "financial_documents",
      "schemaName": "public",
      "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50},
      "metadata": {"company": "Acme Corp", "document_type": "10-K"},
      "embeddingSecretName": "oss/embedding",
      "postgresSecretName": "oss/pgvector"
    }
  }
}
```

### JSON → MongoDB

```json
{
  "name": "events",
  "source": {
    "fileAttributes": {
      "jsonAttributes": {"everyRowContainsObject": false, "encoding": "UTF-8"}
    },
    "schemaProperties": {
      "fields": [{"name": "_json", "type": "string"}]
    }
  },
  "destination": {
    "database": {
      "dbName": "datris",
      "table": "events",
      "useMongoDB": true
    }
  }
}
```
"""


TAP_WORKFLOW_REFERENCE = """\
# Datris Tap Workflow Reference

This is the canonical, fetch-on-demand reference for everything tap-related: creation, running, scheduling, per-run parameterization, error handling, and verification. Re-read this any time you need to verify your mental model of how taps work — the rules here are authoritative.

## What a tap is

A tap is a Python script registered with Datris that fetches data from an external source (REST API, web page, S3 bucket, database, etc.) and pushes records into a target pipeline. Taps are the right answer when:
  - The source is external (not a file the user has locally — that's `upload_data`'s job)
  - The data needs to refresh on a schedule, on demand, or both
  - The user wants the data flowing into a destination managed by Datris

If the user already has the file in hand, prefer `upload_data` against an existing pipeline — taps add operational machinery you don't need for a one-shot load.

---

## Required workflow

1. **Check existing.** Call `list_taps`. If a tap with the right purpose exists, prefer running it (or updating its config) over creating a new one.
2. **Create.** Call `create_tap` with either `instruction` (AI generates the Python `fetch()` function) or `script` (you provide it directly). Writing the script yourself is usually faster and more reliable than AI generation. Pass `target_pipeline` so the tap actually persists to a destination — without it, runs come back with `persistedReason: no_target_pipeline`.
3. **Test.** Call `test_tap` to validate the script without persisting. **MANDATORY for any newly-created or just-updated script** — see the VALIDATION RULE below. If the script errors, fix it by calling `create_tap` again with the same name and a corrected `instruction` or revised `script` (create_tap upserts and replaces the existing script), and re-test until it succeeds.
4. **Schedule (if recurring).** If the user mentioned any recurrence cue, set `cron_expression` — see the SCHEDULING RULE below.
5. **Run.** Call `run_tap` with `name` and optional `params`. Read the response — see the run-flow section below.
6. **Poll.** When `persisted: true`, call `get_pipeline_status(publisher_token=response.publisherToken)` and poll until `rollup.allDone` is true.
7. **Verify.** Read `rollup.status` (`success` / `warning` / `error`) and per-job `rollup.jobs[].lastError`. Report the outcome to the user with the concrete numbers.

---

## SCHEDULING RULE (read this every time)

If the user mentions ANY recurrence cue — "nightly", "daily", "hourly", "every morning", "weekly", "at market open", "on a schedule", "recurring", "on a timely basis", "keep this up to date", "refresh this every X" — set `cron_expression` on the relevant tap via `create_tap` (when first creating) or `update_tap` (when wiring an existing one). The Datris platform runs the scheduler — once you set `cron_expression`, the tap fires automatically and its runs show up in `get_tap_logs` exactly like manual runs.

**DO NOT** respond with shell snippets, host cron jobs, Airflow DAGs that just invoke the CLI on a timer, or "run this every night at 9pm" command examples for the user to wire up themselves. That defeats the platform: the user delegated both "what data" AND "when it refreshes" to Datris. Handing back a copy-pasteable cron line pushes operational burden the user already chose to offload. The schedule lives on the tap.

After setting `cron_expression`, tell the user the cadence you set in plain English ("scheduled `canslim_screen` for `0 30 5 ? * MON-FRI` — runs every weekday at 5:30am") and offer to adjust it or chain related taps.

### Quartz CRON expression cookbook

Datris uses Quartz CRON syntax: `seconds minutes hours day-of-month month day-of-week [year]`. Note the leading SECONDS field — many cron resources online only show 5 fields.

| Cadence | Expression |
|---|---|
| Every minute | `0 * * * * ?` |
| Every 5 minutes | `0 */5 * * * ?` |
| Every hour on the hour | `0 0 * * * ?` |
| Daily at midnight | `0 0 0 * * ?` |
| Daily at 5:30am | `0 30 5 * * ?` |
| Weekdays at 5:30am (US market pre-open) | `0 30 5 ? * MON-FRI` |
| Weekdays at 4:15pm (US market post-close) | `0 15 16 ? * MON-FRI` |
| Every Sunday at 2am | `0 0 2 ? * SUN` |
| First of each month at midnight | `0 0 0 1 * ?` |

Use `?` (not `*`) when you specify one of day-of-month / day-of-week and want the other left unspecified — Quartz requires exactly one of those two fields to be `?`.

---

## VALIDATION RULE (read this every time you create or replace a tap's script)

Before calling `run_tap` AND before setting `cron_expression` on a tap whose script has not yet been validated, you MUST call `test_tap` and see it succeed.

This applies to:
- A tap you just created via `create_tap` (AI-generated from `instruction` or supplied as `script`).
- A tap whose script you just replaced (by calling `create_tap` again with the same name — `create_tap` upserts by name and replaces the existing script).

If `test_tap` fails:
1. Read the `error` and the `logs` field — they carry the Python traceback or runtime issue.
2. Fix the script: call `create_tap` again with the same `name` and a corrected `instruction` or revised `script`.
3. Re-run `test_tap`. Iterate until it passes.

Only THEN are you allowed to call `run_tap` or set `cron_expression`. Setting a cron on a never-tested script ships a guaranteed-bad nightly run; the user delegated the schedule to Datris, not the validation to luck.

### When `test_tap` is NOT needed

For existing taps that have run successfully, cadence-only changes are safe without a fresh test:
- Toggling `enabled` on/off
- Changing `cron_expression` (different cadence, same script)
- Changing `target_pipeline` (different destination, same script)
- Updating `description` or other metadata

The script itself has already been proven by past successful runs, so re-validation isn't useful. The rule fires only when the script changed.

---

## EVIDENCE RULE (read this before summarizing a multi-step setup)

The collapsed tool-call blocks in your message are the ONLY evidence that work actually happened. Your prose must match what those blocks show, not what you intended to do.

**Failure mode this prevents:** confabulated success. The classic bad turn looks like this:
1. User: "create a tap to fetch X nightly and load to MongoDB"
2. You call `create_tap(name=X, target_pipeline=X, cron_expression=...)`
3. You call `test_tap(X)` — test pulls records cleanly (test mode doesn't need the pipeline to exist)
4. You narrate: "Pipeline + tap are live, scheduled weekdays 22:00 UTC"
5. **Reality:** the pipeline was never created. `create_pipeline` was never called. The next scheduled run will fail with `persistedReason: no_target_pipeline`.

The user trusted your narrative, you produced a confident story, and the platform is in a broken state that won't surface until the cron fires.

**Discipline:**
- Before writing "X is live" or "X is set up," enumerate the tool calls you made in this turn. If your enumeration is shorter than your intent, finish the intent before claiming success.
- For pipeline + tap workflows, the minimum is THREE tool calls in the same turn: `create_pipeline` → `create_tap` → `test_tap`. If you set `cron_expression`, that's part of `create_tap`. If the user wants a manual run too, add `run_tap` and the polling chain.
- When a tool call returns an error or unexpected response, surface the actual response field (`error`, `persistedReason`, `recordCount`, etc.) — don't smooth it into success language.
- When in doubt, call `list_pipelines` / `list_taps` / `get_pipeline` / `get_tap` to ground yourself in real platform state before claiming anything is done.

Honest narration with fewer claims is always better than a confident narrative that drifts past the tool calls.

---

## Per-run params

`run_tap(name, params={...})` lets you drive a single run with caller-supplied values that vary per call: date ranges, ticker lists, page cursors, batch sizes, geographic regions, anything that changes between runs.

### How it works

Each key/value you pass becomes an env var the script reads:

```python
import os
start = os.environ.get("DATRIS_TAP_PARAM_start_date", "2026-01-01")  # sensible default for cron
end   = os.environ.get("DATRIS_TAP_PARAM_end_date")
tickers_json = os.environ.get("DATRIS_TAP_PARAM_tickers", "[]")
tickers = json.loads(tickers_json)
```

- **Key constraints:** must match `[A-Za-z_][A-Za-z0-9_]*` (clean env var names). Anything else is rejected with an actionable error.
- **Value handling:** strings pass through; numbers/booleans get stringified; nested objects/arrays are JSON-encoded (script can `json.loads()` them).
- **Scheduled runs supply no params** — cron-triggered runs have an empty params bag. Scripts MUST apply sensible defaults when an env var is absent.

### Params vs secrets — when to use which

| Use `params` for | Use `secret_name` for | Use hardcoded script values for |
|---|---|---|
| Values that vary per call | Credentials (API keys, passwords, OAuth tokens, signing keys, certificates) | Static config the user has already shared in conversation |
| Date windows, page cursors, ticker lists, batch sizes | Things the user would refuse to paste into chat | Regions, bucket names, account IDs, project IDs, base URLs, table/schema names |
| Anything the user might want to override on an ad-hoc run | Things you'd never want to change just to trigger a one-off | Things that don't change between runs and aren't sensitive |

**The "is this a secret?" test:** would the user reasonably refuse to type this value into the chat? An access key, password, or signed token? Yes — that's a secret, ask via `request_tap_secret_from_user`. A region, container/bucket/database name, account/project/tenant ID, base URL, or endpoint URL? No — that's config, hardcode it in the script or pass as a `run_tap(params=...)` value.

**Anti-pattern 1:** rewriting a secret on every run to smuggle per-call params through. This clobbers concurrent runs, pollutes audit history, and wastes Vault writes. Use `params` instead. If an existing script doesn't yet read a param, update it by calling `create_tap` again with the same name and a revised `script` — create_tap upserts by name and replaces the existing script.

**Anti-pattern 2:** putting non-secret config (regions, endpoint URLs, bucket names, account IDs) into the secret form when calling `request_tap_secret_from_user`. The user just spent a turn telling you the region in chat; asking them to type it AGAIN into a secret form makes the platform feel broken. Hardcode it in the script — or read it from `DATRIS_TAP_PARAM_<key>` if it should vary per call. The secret form is for values the user wouldn't safely paste into chat; everything else goes in code or params.

---

## Creating a tap — instruction vs script

**With instruction:** pass a plain-English `instruction` to `create_tap`. Platform AI generates the Python `fetch()` function. Slower (1–2 minutes for codegen). Use when the source is well-known and the logic is straightforward.

**With script:** write `fetch()` yourself and pass as `script`. Faster, no codegen wait, full control. Use when:
- The source has quirks AI is likely to misunderstand (paginated APIs, rate limits, auth handshakes, undocumented edge cases)
- You want to thread `DATRIS_TAP_PARAM_*` env vars through specific points in the logic
- You've already iterated and know exactly what you want

The script MUST define `fetch()` taking no arguments and returning one of:
- A list of dicts (structured records)
- A list of `{uri, filename, content}` dicts where `content` is base64-encoded bytes (document tap — for vector destinations)
- A string (raw JSON, XML, or text)

### Pre-installed packages

`requests`, `beautifulsoup4`, `pandas`, `lxml`, `feedparser`, `boto3`, `google-cloud-storage`, `azure-storage-blob`, `openpyxl`, `pyyaml`, `python-dateutil`, `pytz`, plus the Python stdlib. If your script imports anything else, pass it in `packages` to `create_tap` (e.g. `["yfinance", "alpha-vantage"]`).

---

## Run flow

`run_tap` is async with respect to ingestion: the script executes synchronously, but record loading runs in the background. The response tells you whether the data is in flight, not whether it has landed.

### Response shape

```json
{
  "tap": "<name>",
  "mode": "run",
  "status": "success" | "failure" | "skipped",
  "persisted": true | false,
  "persistedReason": "<reason>",       // present when persisted=false
  "publisherToken": "<uuid>",          // present when persisted=true
  "pipelineTokens": ["<uuid>", ...],   // one per ingestion job submitted
  "recordCount": 12345,
  "error": "<string>",                 // present on failure
  "logs": "<wrapper + script stderr>"
}
```

### What to do based on the response

**`persisted: true`** — load is in flight. Call `get_pipeline_status(publisher_token=response.publisherToken)` and poll every few seconds until `rollup.allDone` is true. Then read `rollup.status`:
  - `success` — every job landed cleanly. Report counts.
  - `warning` — some jobs landed, some had non-fatal issues. Read `rollup.jobs[].lastError` for the affected ones.
  - `error` — at least one job failed. Read `rollup.jobs[].lastError` for `processName` and `description`.

Do not query the destination or report completion to the user before polling completes — the data isn't there yet.

**`persisted: false`** — destination was not written. Read `persistedReason`:

| `persistedReason` | What it means | What to do |
|---|---|---|
| `no_target_pipeline` | Tap has no pipeline wired | Call `update_tap` with `target_pipeline`, then re-run |
| `test_mode` | Ran in test mode (or mcp-server/datris version mismatch) | Flag it; do not report data as stored |
| `run_error` | Script execution or post-execution failure | Show the `error` string. See common causes below. |
| `no_records` | Source returned nothing | Tell the user the source had nothing; consider whether params (date window etc.) were too narrow |
| `debounced` | Tap was triggered server-side within the last 5s | DO NOT retry. Your previous call is still running. Use `get_tap_logs` to find the live run's `publisherToken`, then poll `get_pipeline_status` |
| `already_running` (response `status: skipped`) | Another run_tap for this tap is in flight | Same as `debounced` |

### Common `run_error` causes

- **Output exceeded size limit** — script produced more JSON than the configured tap output cap (default 100MB). The whole batch is buffered before pipeline loading; very large fetches risk OOM. Fix: reduce the source range via `params` (shorter date window, smaller page, per-symbol chunks). Multiple smaller runs all land in the same destination pipeline.
- **Script raised an exception** — read the `logs` field for the Python traceback. Common: 403/404 from the source API (auth, entitlements), timeout, JSON parse error on malformed response.
- **Subprocess timed out** — script ran longer than `tapScriptTimeoutSeconds` (default 300). Either the source is genuinely slow (chunk smaller via params) or the script has a bug (infinite loop, missing pagination break).

---

## Verifying outcomes

The `publisherToken` is your handle on whether the data actually landed in the destination. It works the same for manual and scheduled runs:

- **Manual run:** the token is in the `run_tap` response.
- **Scheduled (cron) run:** call `get_tap_logs(name)` and pick the relevant entry — every log entry that submitted records includes its `publisherToken`.

Then call `get_pipeline_status(publisher_token=...)` and poll until `rollup.allDone` is true.

The tap log only tells you the script ran. The publisher token is how you trace a run through to whether the destination has the data. Prefer `get_tap_logs` over holding the token in your own context across many turns — if the conversation is compressed or you reconnect, you can always re-derive the token from the log.

One `get_pipeline_status` call with the publisherToken sees every ingestion job this run submitted (structured taps = 1 job; document taps = N jobs, one per document).

---

## Document taps (special case)

For ingesting files (PDFs, DOCX, etc.) into vector destinations, return a list of `{uri, filename, content}` dicts where `content` is base64-encoded bytes. Set `tap_type="document"` on `create_tap`. The target_pipeline MUST have:
  - Source: `unstructuredAttributes`
  - Destination: a vector store (`qdrant`, `pgvector`, `weaviate`, `milvus`, `chroma`)

The platform maintains a per-tap ledger of processed documents (URI + content hash). On each run, documents already in the ledger are skipped — re-running a document tap is safe and only processes new or changed files. To force re-processing, call `get_tap_ledger` with `clear_uri` (one file) or `clear_all=true` (everything).

---

## Tool quick-reference

| Tool | Use for |
|---|---|
| `list_taps` | Discover what taps exist. Call before suggesting new ones. |
| `get_tap` | Read a tap's config + script. NOT for run status — use `get_pipeline_status`. |
| `create_tap` | Create or replace a tap (upserts by name). Pass `cron_expression` here when recurrence is known up-front. |
| `update_tap` | Change `enabled`, `cron_expression`, `target_pipeline`, or `description` without touching the script. |
| `create_tap` (upsert) | Replacing an existing tap's script: call `create_tap` again with the same `name` and the new `script` or `instruction`. It upserts by name. There is no separate script-only update tool. |
| `test_tap` | Validate the script without persisting. Always run before the first real `run_tap`. |
| `run_tap` | Execute now. Pass `params` for per-call values. |
| `get_tap_logs` | Run history for a tap (manual + scheduled). Use to recover `publisherToken` for any past run. |
| `get_tap_ledger` | Document-tap-only: see/clear the dedupe ledger. |
| `get_pipeline_status` | Authoritative source for whether ingestion landed. Pass the `publisherToken` from `run_tap` or `get_tap_logs`. |
| `delete_tap` | Remove a tap and its script. |
"""


@server.list_resources()
async def list_resources():
    return [
        Resource(
            uri="datris://pipeline-config-reference",
            name="Pipeline Configuration Reference",
            description="Complete reference for building Datris pipeline configurations. Covers all source types (CSV, JSON, XML, PDF), AI-powered data quality (CodeGen), AI transformations (CodeGen), and all destination types (PostgreSQL, MongoDB, Kafka, vector databases). Read this before using create_pipeline.",
            mimeType="text/plain",
        ),
        Resource(
            uri="datris://tap-workflow-reference",
            name="Tap Workflow Reference",
            description="Canonical reference for everything tap-related: creation (instruction vs script), per-run params, scheduling (with CRON cookbook), run flow + polling, error handling (persistedReason table, size-limit guidance), document taps, and outcome verification via publisherToken + get_tap_logs. Re-read this any time you need to verify your understanding of how taps work — including the SCHEDULING RULE for recurring data needs.",
            mimeType="text/plain",
        ),
    ]


@server.read_resource()
async def read_resource(uri):
    if str(uri) == "datris://pipeline-config-reference":
        return PIPELINE_CONFIG_REFERENCE
    if str(uri) == "datris://tap-workflow-reference":
        return TAP_WORKFLOW_REFERENCE
    raise ValueError(f"Unknown resource: {uri}")


# ---------------------------------------------------------------------------
# MCP Tools
# ---------------------------------------------------------------------------

@server.list_tools()
async def list_tools():
    return [
        # --- Pipeline Management ---
        Tool(
            name="list_pipelines",
            description="List all registered pipeline configurations. Each pipeline defines a complete data processing flow: source format and schema, AI-powered data quality and transformations, and destination (database, message queue, or vector store). CALL THIS FIRST on any data-related user request — before suggesting external sources, before asking scope questions. The user almost always cares more about what's already in their Datris environment than about a generic options menu.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="get_pipeline",
            description="Get a specific pipeline configuration by name. Returns the full JSON config including source, dataQuality, transformation, preprocessor, and destination sections.",
            inputSchema={
                "type": "object",
                "properties": {
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name"
                    }
                },
                "required": ["pipeline"]
            }
        ),
        Tool(
            name="create_pipeline",
            description="Create a pipeline. For STRUCTURED destinations (postgres, mongodb): send a small sample file and the schema is auto-detected — you do not specify field names or types. For VECTOR destinations (pgvector, qdrant, weaviate, milvus, chroma): there is no schema; pass ONLY pipeline + destination (and optionally filename to set the file-extension hint). Do NOT base64 the document just to satisfy this call — that's wasted tokens; send the document later via upload_data.",
            inputSchema={
                "type": "object",
                "properties": {
                    "content": {
                        "type": "string",
                        "description": "Base64-encoded sample data file content. REQUIRED for structured destinations (postgres, mongodb). OMIT for vector destinations — the file goes through upload_data after this call returns."
                    },
                    "filename": {
                        "type": "string",
                        "description": "Filename (e.g., data.csv, report.json, orders.xml). REQUIRED for structured destinations. Optional for vector destinations — only used as a fileExtension hint (defaults to txt)."
                    },
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name"
                    },
                    "destination": {
                        "type": "string",
                        "enum": ["postgres", "mongodb", "qdrant", "weaviate", "milvus", "chroma", "pgvector"],
                        "description": "Destination type (default: postgres for CSV, mongodb for JSON/XML)"
                    },
                    "table": {
                        "type": "string",
                        "description": "Destination table or collection name (default: pipeline name)"
                    },
                    "database": {
                        "type": "string",
                        "description": "Destination database name (default: datris)"
                    },
                    "delimiter": {
                        "type": "string",
                        "description": "CSV delimiter (default: comma)"
                    },
                    "header": {
                        "type": "boolean",
                        "description": "Whether CSV has a header row (default: true)"
                    },
                    "keyFields": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Optional natural-key columns used to dedupe / upsert rows on every run. Only applies to postgres and mongodb destinations. Example: ['user_id', 'event_date'] — rows with the same (user_id, event_date) will replace the existing row instead of appending. On Postgres this triggers a staging + INSERT…ON CONFLICT path; on Mongo it uses upsertJSON. NOTE: on conflict, ALL non-key columns from the incoming row overwrite the existing row, including NULLs (true upsert semantics, not non-null merge). If your source emits partial rows, coalesce upstream. Omit to append on every run (default behavior)."
                    },
                    "truncate": {
                        "type": "boolean",
                        "description": "Optional. When true, the destination table/collection is truncated before each run, so only the latest run's data is kept. Only applies to postgres and mongodb destinations. Default false (append). Mutually useful with — but distinct from — keyFields: truncate wipes everything each run, keyFields upserts per natural key."
                    },
                    "codegen_rule": {
                        "type": "string",
                        "description": "Optional data quality validation rule as a plain-English instruction. Only add when the user explicitly requests validation. Datris will generate a Python validation script from this instruction and run it locally against all data. Example: 'Validate that all dates are YYYY-MM-DD format and all email addresses are valid'"
                    },
                    "codegen_transform": {
                        "type": "string",
                        "description": "Optional transformation instruction as a plain-English description. Only add when the user explicitly requests transformation. Datris will generate a Python script from this instruction and run it locally to transform all data. Example: 'Convert all date columns to YYYY/MM/DD format and uppercase all name fields'"
                    },
                    "catalog": {
                        "type": "string",
                        "description": "OMIT BY DEFAULT. Catalogs are a user-chosen organizational convention — do NOT set a catalog unless the user has explicitly asked to group this pipeline under a named catalog. Assigning one for them puts the pipeline into a taxonomy they didn't ask for. When unset, the platform shows the pipeline as Uncataloged, which is the correct default."
                    }
                },
                "required": ["pipeline"]
            }
        ),
        Tool(
            name="set_catalog",
            description=(
                "Set or clear the catalog grouping label on an existing pipeline or tap. "
                "ONLY call this when the user has explicitly asked to organize work under a named catalog. "
                "Do NOT call it proactively — catalogs are a user-chosen organizational convention; "
                "assigning one for them puts the pipeline/tap into a taxonomy they didn't ask for. "
                "Pass exactly one of `pipeline` or `tap` to identify the target. Pass `catalog` to set the label, or omit it (or pass an empty string) to clear it. "
                "Survives subsequent re-ingests — `datris ingest` no longer rewrites an existing pipeline's config."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name to update. Mutually exclusive with `tap`."
                    },
                    "tap": {
                        "type": "string",
                        "description": "Tap name to update. Mutually exclusive with `pipeline`."
                    },
                    "catalog": {
                        "type": "string",
                        "description": "Catalog label. Omit or pass an empty string to clear the label."
                    },
                },
                "required": []
            }
        ),
        Tool(
            name="delete_pipeline",
            description=(
                "Delete a pipeline. This is DESTRUCTIVE: by default it removes BOTH the "
                "pipeline configuration AND all data already written to the destination "
                "(MongoDB collection rows, Postgres table rows, vector-store entries). "
                "It also wipes document-tap ledgers and staged files for any tap that "
                "targets this pipeline, so a recreate gets a clean re-ingest. "
                "The platform deliberately does NOT support deleting just the config and "
                "orphaning the data — that creates ghost state. "
                "If you want to keep the config but wipe the destination data (\"reset\"), "
                "pass keep_config=true; the config survives, the data does not. "
                "ALWAYS confirm with the user before calling this tool."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name to delete"
                    },
                    "keep_config": {
                        "type": "boolean",
                        "description": "If true, delete only the destination data but keep the pipeline config (useful for a clean reset). Default false (full delete of both config and data).",
                        "default": False
                    }
                },
                "required": ["pipeline"]
            }
        ),
        Tool(
            name="upload_data",
            description="Upload data to a registered pipeline for processing. Send the ENTIRE file content as a single base64-encoded string in ONE call — do not pre-split or chunk the content client-side. Vector destinations (pgvector, qdrant, weaviate, milvus, chroma) apply recursive chunking server-side using the pipeline's configured chunkSize/chunkOverlap; for those, one upload_data call yields many embedded chunks automatically. The pipeline's rules are applied: schema validation, data quality checks, transformations, then routing to the configured destination. Returns a pipelineToken for tracking job status via get_job_status.",
            inputSchema={
                "type": "object",
                "properties": {
                    "content": {
                        "type": "string",
                        "description": "Base64-encoded file content"
                    },
                    "filename": {
                        "type": "string",
                        "description": "Filename (e.g., data.csv, report.json, orders.xml)"
                    },
                    "pipeline": {
                        "type": "string",
                        "description": "Pipeline name to process the data with"
                    }
                },
                "required": ["content", "filename", "pipeline"]
            }
        ),
        Tool(
            name="get_job_status",
            description=(
                "Get job status for an upload_data submission. Pass `pipeline_token` (returned from upload_data) for the recommended path. "
                "Pass `pipeline_name` instead for a paginated summary of recent jobs for that pipeline. "
                "When queried by `pipeline_token`, the response is `{rollup: {allDone, status, jobs: [...]}, events: [...]}` — "
                "poll every few seconds until `rollup.allDone` is true, then read `rollup.status` (`success` | `warning` | `error`) for the outcome. "
                "Per-job detail is in `rollup.jobs[]` with `pipelineToken`, `pipeline`, `filename`, `status`, `startedAt`, `lastEventAt`, `elapsed`, and `lastError` (populated on failure with `processName` and `description`). "
                "`events[]` is the raw begin/info/end audit trail; the rollup is the source of truth for completion. "
                "When queried by `pipeline_name`, the response is a paginated array of summary rows; the most recent job is index 0 and its `status` field is `success` | `processing` | `error`. "
                "Do NOT proceed to query/search until the job is in a terminal state."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "pipeline_token": {
                        "type": "string",
                        "description": "Pipeline token returned from upload_data"
                    },
                    "pipeline_name": {
                        "type": "string",
                        "description": "Pipeline name to get a paginated summary of recent jobs"
                    },
                    "page": {
                        "type": "integer",
                        "description": "Page number for paginated results (default: 1)"
                    }
                }
            }
        ),
        Tool(
            name="kill_job",
            description="Kill a running pipeline job by its pipeline token. The job thread will be interrupted and the job marked as cancelled.",
            inputSchema={
                "type": "object",
                "properties": {
                    "pipeline_token": {
                        "type": "string",
                        "description": "Pipeline token of the running job to kill"
                    }
                },
                "required": ["pipeline_token"]
            }
        ),
        Tool(
            name="profile_data",
            description="Send data and use AI to generate a comprehensive data profile: summary statistics per column, data quality issues detected, and suggested validation rules. Use the suggested aiRule when building a pipeline's dataQuality section.",
            inputSchema={
                "type": "object",
                "properties": {
                    "content": {
                        "type": "string",
                        "description": "Base64-encoded file content"
                    },
                    "filename": {
                        "type": "string",
                        "description": "Filename (e.g., sample.csv)"
                    },
                    "delimiter": {
                        "type": "string",
                        "description": "CSV delimiter (default: comma)"
                    },
                    "header": {
                        "type": "boolean",
                        "description": "Whether CSV has a header row (default: true)"
                    },
                    "sample_size": {
                        "type": "integer",
                        "description": "Number of rows to sample for profiling (default: 200)"
                    }
                },
                "required": ["content", "filename"]
            }
        ),
        Tool(
            name="get_version",
            description="Get the Datris server version.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="check_service_health",
            description="Check which backend services are up, down, or not configured. Returns the health status of PostgreSQL, MongoDB, MinIO, ActiveMQ, Kafka, and any configured vector databases (Qdrant, Weaviate, Milvus, Chroma, pgvector). Call this before attempting search or query operations to know which services are available.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        # --- Vector Database Search Tools ---
        Tool(
            name="search_qdrant",
            description="Semantic search across a Qdrant vector database collection. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. For RAG: pass the returned text to ai_answer with the user's question.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "collection": {"type": "string", "description": "Qdrant collection name (default: financial_documents)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="search_weaviate",
            description="Semantic search across a Weaviate vector database class. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. For RAG: pass the returned text to ai_answer with the user's question.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "class_name": {"type": "string", "description": "Weaviate class name (default: FinancialDocuments)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="search_milvus",
            description="Semantic search across a Milvus vector database collection. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. For RAG: pass the returned text to ai_answer with the user's question.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "collection": {"type": "string", "description": "Milvus collection name (default: financial_documents)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="search_pgvector",
            description="Semantic search across a PostgreSQL pgvector table using cosine distance. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. Use list_postgres_tables with vector_only=true to discover available pgvector tables. For RAG: pass the returned text to ai_answer.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "table": {"type": "string", "description": "Table name (default: financial_documents)"},
                    "schema": {"type": "string", "description": "PostgreSQL schema (default: public)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        Tool(
            name="search_chroma",
            description="Semantic search across a Chroma vector database collection. Takes a natural language query, generates an embedding, and returns the most similar document chunks with similarity scores. For RAG: pass the returned text to ai_answer with the user's question.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "collection": {"type": "string", "description": "Chroma collection name (default: financial_documents)"},
                    "top_k": {"type": "integer", "description": "Number of results to return (default: 5)"},
                },
                "required": ["query"]
            }
        ),
        # --- Database Query Tools ---
        Tool(
            name="query_postgres",
            description="Execute a read-only SQL SELECT query against PostgreSQL. Use the metadata discovery tools (list_postgres_databases, list_postgres_schemas, list_postgres_tables, list_postgres_columns) first to explore available data before constructing queries. Only SELECT is allowed; LIMIT is auto-appended if missing.",
            inputSchema={
                "type": "object",
                "properties": {
                    "sql": {"type": "string", "description": "SQL SELECT query to execute"},
                    "limit": {"type": "integer", "description": "Maximum rows to return (default: 100). Pass -1 for unlimited — no cap, returns every matching row."},
                },
                "required": ["sql"]
            }
        ),
        Tool(
            name="query_mongodb",
            description="Query a MongoDB collection with optional filter and projection. Use list_mongodb_databases and list_mongodb_collections first to discover available data. Returns matching documents as JSON.",
            inputSchema={
                "type": "object",
                "properties": {
                    "collection": {"type": "string", "description": "MongoDB collection name"},
                    "filter": {"type": "object", "description": "MongoDB query filter (default: {})"},
                    "projection": {"type": "object", "description": "Fields to include/exclude (default: all fields)"},
                    "limit": {"type": "integer", "description": "Maximum documents to return (default: 20). Pass -1 for unlimited — no cap, returns every matching document."},
                },
                "required": ["collection"]
            }
        ),
        Tool(
            name="query_natural",
            description="Ask a question in natural language about data in a PostgreSQL table. The AI generates a SQL query from the question and table schema, executes it, and returns the results. Use this instead of writing SQL manually.",
            inputSchema={
                "type": "object",
                "properties": {
                    "question": {"type": "string", "description": "Natural language question about the data"},
                    "table": {"type": "string", "description": "PostgreSQL table name to query"},
                    "schema": {"type": "string", "description": "PostgreSQL schema (default: public)"},
                    "database": {"type": "string", "description": "Database name (default: datris)"},
                    "limit": {"type": "integer", "description": "Maximum rows to return (default: 100). Pass -1 for unlimited — no cap, returns every matching row."},
                },
                "required": ["question", "table"]
            }
        ),
        # --- Metadata Discovery Tools ---
        Tool(
            name="list_postgres_databases",
            description="List all PostgreSQL databases available in the Datris platform. Use this as the first step when exploring what data has been ingested into PostgreSQL destinations.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_postgres_schemas",
            description="List all schemas in a PostgreSQL database. Schemas organize tables within a database (e.g., 'public', 'analytics'). Use after list_postgres_databases to drill into a specific database.",
            inputSchema={
                "type": "object",
                "properties": {
                    "database": {"type": "string", "description": "Database name (default: datris)"},
                },
            }
        ),
        Tool(
            name="list_postgres_tables",
            description="List all tables in a PostgreSQL schema. Set vector_only=true to show only pgvector embedding tables, or false (default) to show regular data tables. Use after list_postgres_schemas.",
            inputSchema={
                "type": "object",
                "properties": {
                    "database": {"type": "string", "description": "Database name (default: datris)"},
                    "schema": {"type": "string", "description": "Schema name (default: public)"},
                    "vector_only": {"type": "boolean", "description": "If true, only return tables with an embedding column (pgvector tables). Default: false"},
                },
            }
        ),
        Tool(
            name="list_postgres_columns",
            description="List all columns and their data types for a specific PostgreSQL table. Use this to understand table structure before writing a query_postgres SQL query.",
            inputSchema={
                "type": "object",
                "properties": {
                    "database": {"type": "string", "description": "Database name (default: datris)"},
                    "schema": {"type": "string", "description": "Schema name (default: public)"},
                    "table": {"type": "string", "description": "Table name"},
                },
                "required": ["table"]
            }
        ),
        Tool(
            name="list_mongodb_databases",
            description="List all MongoDB databases available in the Datris platform. Use this as the first step when exploring what data has been ingested into MongoDB destinations.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_mongodb_collections",
            description="List MongoDB collections. If database is specified, lists collections in that database. If omitted, lists all collections across all databases in 'db.collection' format.",
            inputSchema={
                "type": "object",
                "properties": {
                    "database": {"type": "string", "description": "Database name (optional; omit to list from all databases)"},
                },
            }
        ),
        # --- Vector Store Metadata ---
        Tool(
            name="list_qdrant_collections",
            description="List all collections in the Qdrant vector database. Use this to discover available collections before running search_qdrant.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_weaviate_classes",
            description="List all classes in the Weaviate vector database. Use this to discover available classes before running search_weaviate.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_milvus_collections",
            description="List all collections in the Milvus vector database. Use this to discover available collections before running search_milvus.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_chroma_collections",
            description="List all collections in the Chroma vector database. Use this to discover available collections before running search_chroma.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="list_pgvector_collections",
            description="List all pgvector tables (tables with an embedding column) in PostgreSQL. Use this to discover available collections before running search_pgvector.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        # --- AI Tools ---
        Tool(
            name="ai_answer",
            description="Ask the Datris AI to answer a question based on provided context. Ideal for RAG workflows: first retrieve relevant chunks using a search tool (search_qdrant, search_pgvector, etc.), then pass the retrieved text as context along with the user's question to get a synthesized answer.",
            inputSchema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The question to answer"},
                    "context": {"type": "string", "description": "Context text to base the answer on (e.g., retrieved document chunks)"},
                },
                "required": ["query", "context"]
            }
        ),
        # --- Configuration Tools ---
        Tool(
            name="upload_config",
            description="Upload a configuration file to the Datris platform. Supports 'validation-schema' (JSON Schema files used in pipeline dataQuality schema validation). Send the file content as base64.",
            inputSchema={
                "type": "object",
                "properties": {
                    "content": {"type": "string", "description": "Base64-encoded file content"},
                    "filename": {"type": "string", "description": "Filename (e.g., schema.json, transform.js)"},
                    "type": {"type": "string", "enum": ["validation-schema"], "description": "Config file type: 'validation-schema' for JSON Schema"},
                },
                "required": ["content", "filename", "type"]
            }
        ),
        # --- Secrets ---
        Tool(
            name="update_secret",
            description="Update an AI provider secret in the Datris platform. Use this to configure your AI API keys so Datris can use AI features (data profiling, schema generation, AI transformations, RAG). Only AI-related secrets can be updated: anthropic, openai, ollama, embedding.",
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "enum": ["anthropic", "openai", "ollama", "embedding"],
                        "description": "Secret name: anthropic, openai, ollama, or embedding"
                    },
                    "fields": {
                        "type": "object",
                        "description": "Key-value fields to set. Typical fields: endpoint (API URL), model (model name), apiKey (API key)"
                    },
                },
                "required": ["name", "fields"]
            }
        ),
        Tool(
            name="list_tap_secrets",
            description=(
                "List the names of tap secrets that already exist (secrets tagged _type=tap "
                "— both agent-created and human-owned ones surfaced via the UI's Tap Secrets "
                "section). ALWAYS call this before create_tap_secret: if a suitable secret "
                "already exists, prefer reusing it by passing its name as secret_name to "
                "create_tap. Only ask the user to provide credentials when no existing "
                "secret covers the need."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
                "required": []
            }
        ),
        Tool(
            name="get_tap_secret_fields",
            description=(
                "Return the FIELD NAMES (keys only — never values) of an existing tap "
                "secret. Use this after list_tap_secrets to verify a candidate secret has "
                "the keys your tap script will need (e.g. API_KEY, USER_AGENT). Secret "
                "values are intentionally NOT returned and are never visible to the agent."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Tap secret name (from list_tap_secrets)."
                    }
                },
                "required": ["name"]
            }
        ),
        Tool(
            name="create_tap_secret",
            description=(
                "Create or update a secret for a tap to use. The secret's fields are "
                "injected as environment variables into the tap's Python script at runtime. "
                "Use this before create_tap when the tap needs credentials (API key, DB "
                "password, etc.). FIRST call list_tap_secrets to check whether a suitable "
                "secret already exists — if so, reuse it instead of creating a duplicate. "
                "By default, fails if a secret with this name already exists — "
                "pass overwrite=true to replace it (ask the user first). Agents can only "
                "overwrite secrets that were also created by an agent (tagged _type=tap); "
                "secrets owned by a human user must be updated via the UI."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Secret name. Must not use reserved AI-slot names (anthropic, openai, ollama, embedding, ai-primary, codegen). Convention: lowercase, hyphenated, e.g. 'stripe-api-key'."
                    },
                    "fields": {
                        "type": "object",
                        "description": "Key-value fields. Each key becomes an env var name in the tap script; e.g., {\"apiKey\": \"sk_...\"} is read as os.environ['apiKey']."
                    },
                    "overwrite": {
                        "type": "boolean",
                        "description": "If true, replace an existing secret with the same name. Default false (fails on collision). Only tap-typed secrets can be overwritten by an agent.",
                        "default": False
                    },
                },
                "required": ["name", "fields"]
            }
        ),
        Tool(
            name="delete_tap_secret",
            description=(
                "Delete a tap secret. Only secrets created by an agent (tagged _type=tap) "
                "can be deleted via this tool; secrets owned by a human user must be "
                "removed from the Secrets tab. Use this to clean up after a tap is no "
                "longer needed."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the tap secret to delete."
                    },
                },
                "required": ["name"]
            }
        ),

        # --- Taps ---
        Tool(
            name="create_tap",
            description=(
                "Create a tap — a Python script that fetches data from an external source and pushes it into a pipeline. Provide a plain-English instruction to have AI generate the script, or supply your own script directly. "
                "If the user wants the tap to feed a pipeline, pass `target_pipeline` now. Without it, `run_tap` will fetch but not persist (response will show `persisted: false, persistedReason: \"no_target_pipeline\"`) — you'd then need to call update_tap to wire a pipeline. Only skip target_pipeline if the user explicitly wants a fetch-only tap. "
                "If the user mentioned ANY recurrence (nightly, daily, hourly, every morning, market open, etc.), pass `cron_expression` NOW — the platform's scheduler will run the tap on that cadence automatically. This is the canonical way to make a tap recurring; do NOT respond with shell commands or external schedulers for the user to run themselves. See the SCHEDULING RULE in the server instructions. "
                "AFTER creating, call `test_tap` to validate the script BEFORE any `run_tap` or before relying on a scheduled cron run — see the VALIDATION RULE. Setting a cron on a never-tested script is a guaranteed-bad nightly run waiting to happen."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Unique tap name (e.g., 'weather-data')"
                    },
                    "instruction": {
                        "type": "string",
                        "description": "Plain-English instruction for AI script generation (e.g., 'Fetch current weather for NYC from Open-Meteo API'). If provided, AI generates the Python script."
                    },
                    "script": {
                        "type": "string",
                        "description": "Raw Python source code with a fetch() function. Use this to provide your own script instead of AI generation."
                    },
                    "target_pipeline": {
                        "type": "string",
                        "description": "Name of the pipeline to push fetched data into"
                    },
                    "cron_expression": {
                        "type": "string",
                        "description": "Quartz CRON expression for recurring runs (e.g., '0 0 * * * ?' for hourly, '0 30 5 ? * MON-FRI' for weekdays 5:30am). SET THIS whenever the user describes a recurrence — nightly, daily, hourly, market open, etc. The platform's scheduler fires the tap on this cadence automatically; do not propose external schedulers or shell cron for the user to run themselves."
                    },
                    "secret_name": {
                        "type": "string",
                        "description": "Vault secret name containing API keys/credentials the script needs"
                    },
                    "tap_type": {
                        "type": "string",
                        "enum": ["structured", "document"],
                        "description": "Tap type. 'structured' (default) returns rows of records. 'document' returns a list of {uri, filename, content (base64)} dicts; the platform stages each document and uses a ledger to skip files it has already processed. A document tap's target_pipeline MUST have an unstructuredAttributes source and a vector-store destination (qdrant, pgvector, weaviate, milvus, or chroma) — the server rejects save attempts that violate this."
                    },
                    "packages": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Extra pip packages the script imports that aren't pre-installed. Required when `script` imports non-stdlib modules beyond the pre-installed set (requests, beautifulsoup4, pandas, lxml, feedparser, boto3, google-cloud-storage, azure-storage-blob, openpyxl, pyyaml, python-dateutil, pytz). Example: ['yfinance', 'alpha_vantage']. When `instruction` is used instead of `script`, the AI populates this automatically — if you pass it anyway, your value wins."
                    },
                },
                "required": ["name"]
            }
        ),
        Tool(
            name="list_taps",
            description="List all taps with their status, target pipeline, schedule, and last run info. CALL THIS FIRST (alongside list_pipelines) on any data-related user request — the user's existing taps often reveal what's already being pulled and where, which short-circuits the entire 'should I suggest an external API?' conversation.",
            inputSchema={
                "type": "object",
                "properties": {},
            }
        ),
        Tool(
            name="run_tap",
            description=(
                "Manually trigger a tap. Executes the script; when a target pipeline is configured, hands records to the pipeline async. "
                "The response carries `recordCount`, `publisherToken`, `pipelineTokens`, `persisted`, `persistedReason`, and the script's `logs` — but NOT the records themselves. "
                "If you need to preview what the script produces, call `test_tap` instead. "
                "BEFORE the first run of a newly-created or newly-updated tap, you MUST have called `test_tap` and seen it succeed. See the VALIDATION RULE in the server instructions. Skipping the test on a fresh script pushes potentially-broken data into the destination — and `run_tap` doesn't return records, so you won't see the breakage from the response. "
                "\n\n"
                "PER-RUN PARAMS — pass a `params` object to drive this run with caller-supplied values (date range, ticker list, page cursor, etc.). "
                "Each key/value becomes an env var the script reads via `os.environ.get('DATRIS_TAP_PARAM_<key>')`. "
                "Keys must match `[A-Za-z_][A-Za-z0-9_]*` so they map cleanly onto env var names. Values are stringified; nested objects/arrays are JSON-encoded (script can `json.loads()` them back). "
                "Use this for anything that varies per-call — date windows, ticker lists, page cursors, batch sizes. "
                "Do NOT rewrite the tap secret to pass per-run params: secrets are for credentials (API keys, DB passwords); rewriting them on every call clobbers concurrent runs, pollutes audit history, and wastes Vault writes. "
                "If the tap script doesn't yet read a particular param, update the script by calling `create_tap` again with the same `name` and a revised `script` (create_tap upserts and replaces the existing script). That's the right shape for parameterized runs.\n"
                "\n"
                "REQUIRED next steps based on the response:\n"
                "  • `persisted: true` → load is still running. Call `get_pipeline_status(publisher_token=response.publisherToken)` and poll until `rollup.allDone` is true. Then read `rollup.status` (`success`/`warning`/`error`) and `rollup.jobs[].lastError`. Do not report completion or query the destination before that.\n"
                "  • `persisted: false` → the destination was NOT written. Read `persistedReason`:\n"
                "      - `no_target_pipeline`: tap has no pipeline wired. Tell the user; offer to call update_tap.\n"
                "      - `test_mode`: ran in test mode (or mcp-server/datris version mismatch). Flag it; do not report data as stored.\n"
                "      - `run_error`: show the `error` string. If the error says output exceeded the size limit, reduce the source range via `params` (shorter date window, smaller page, per-symbol chunks) and call run_tap again — multiple smaller runs all land in the same destination pipeline.\n"
                "      - `no_records`: source returned nothing.\n"
                "      - `debounced`: this tap was triggered server-side within the last 5 seconds. Do NOT retry — your previous call is still running. Use `get_tap_logs` to find the live run's `publisherToken`, then poll `get_pipeline_status`.\n"
                "      - `already_running` (response `status: skipped`): another run_tap for this tap is already in flight in this agent session. Same handling as `debounced`: wait, then look up the live run in `get_tap_logs`.\n"
                "The response's `publisherToken` covers every ingestion job this run submitted (structured taps = 1, document taps = N). One `get_pipeline_status` call with the publisherToken sees them all."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the tap to run"
                    },
                    "params": {
                        "type": "object",
                        "description": (
                            "Optional per-run parameters injected into the script as DATRIS_TAP_PARAM_<key> env vars. "
                            "Use for values that vary per call (date ranges, ticker lists, page cursors, batch sizes) — "
                            "NOT for credentials (those belong in the tap secret). "
                            "Keys must match [A-Za-z_][A-Za-z0-9_]*. Values are stringified; nested objects/arrays are JSON-encoded. "
                            "Scheduled cron runs supply no params, so scripts must apply sensible defaults when the env var is absent. "
                            "Example: {\"start_date\": \"2026-05-01\", \"end_date\": \"2026-05-31\", \"tickers\": [\"AAPL\", \"MSFT\"]}"
                        ),
                        "additionalProperties": True
                    }
                },
                "required": ["name"]
            }
        ),
        Tool(
            name="get_pipeline_status",
            description=(
                "Read pipeline ingestion status. Use this after `run_tap` to watch a tap-submitted load progress. "
                "Pass `publisher_token` to see every job the tap run submitted (the recommended option — works for both structured and document taps). "
                "Pass `pipeline_token` for a single ingestion job. Exactly one of the two must be supplied. "
                "Response shape: `{rollup: {allDone, status, jobs: [...]}, events: [...]}`. "
                "Poll every few seconds until `rollup.allDone` is true, then read `rollup.status` (`success` | `warning` | `error`) for the outcome. "
                "Per-job detail is in `rollup.jobs[]` — each entry has `pipelineToken`, `pipeline`, `filename`, `status`, `startedAt`, `lastEventAt`, `elapsed`, and `lastError` (populated on failure with `processName` and `description`). "
                "`events[]` is the raw begin/info/end audit trail if you need it; the rollup is the source of truth for completion."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "publisher_token": {
                        "type": "string",
                        "description": "UUID returned from run_tap response. Returns status rows for ALL jobs this tap run submitted."
                    },
                    "pipeline_token": {
                        "type": "string",
                        "description": "UUID for a single ingestion job. Returns status rows for that one job."
                    },
                },
                "required": []
            }
        ),
        Tool(
            name="delete_tap",
            description="Delete a tap and its stored script.",
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the tap to delete"
                    },
                },
                "required": ["name"]
            }
        ),
        Tool(
            name="get_tap",
            description=(
                "Get a tap's static definition: configuration, schedule, target pipeline, and the generated Python script content. "
                "This is config-only — it returns the SAME data on every call and tells you NOTHING about run state. "
                "Do NOT call this to check whether a run is finished or to poll for completion — repeatedly calling get_tap after run_tap is a bug, the response will never change to reflect ingestion progress. "
                "For run status: call `get_pipeline_status(publisher_token=...)` (token comes from the run_tap response or from a get_tap_logs entry). "
                "For run history: call `get_tap_logs`."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the tap to retrieve"
                    },
                },
                "required": ["name"]
            }
        ),
        Tool(
            name="get_tap_logs",
            description=(
                "Get the run history for a tap. Returns the last 50 run log entries sorted by most recent first, including status, record count, duration, errors, logs, and `publisherToken` for each run that submitted records to a pipeline. "
                "Works for both manual runs (triggered by `run_tap`) and scheduled runs (triggered by the platform's cron scheduler) — they share the same log. "
                "Use this to verify whether a scheduled run fired, whether any recent run's script succeeded, and to recover the `publisherToken` for any run if you didn't keep the original `run_tap` response in context. "
                "To verify the actual destination ingestion outcome — not just that the script ran — pick the relevant entry and call `get_pipeline_status(publisher_token=entry.publisherToken)`. The tap log only records what the script did; the publisher token is how you trace a run through to whether the data actually landed in the destination."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the tap to get logs for"
                    },
                },
                "required": ["name"]
            }
        ),
        Tool(
            name="get_tap_ledger",
            description="For a document tap: return the ledger of discovered documents (URI, filename, status, hashes, first/last seen timestamps). The ledger is what tells the platform which documents have already been processed so re-runs skip unchanged files. Pass 'clear_uri' to delete one entry (forces that document to be re-processed on the next run) or 'clear_all=true' to wipe the entire ledger (forces a full re-scan).",
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the document tap"
                    },
                    "clear_uri": {
                        "type": "string",
                        "description": "Optional. If set, deletes the ledger entry for this URI so the document is re-processed on the next run."
                    },
                    "clear_all": {
                        "type": "boolean",
                        "description": "Optional. If true, deletes the entire ledger for this tap, forcing every document to be re-processed on the next run."
                    }
                },
                "required": ["name"]
            }
        ),
        Tool(
            name="test_tap",
            description="Test-run a tap without pushing data to the pipeline. Executes the tap's script and returns results, record count, and any errors. Use this to validate a script before running it for real.",
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the tap to test"
                    },
                },
                "required": ["name"]
            }
        ),
        Tool(
            name="update_tap",
            description=(
                "Update an existing tap's CONFIG without regenerating the script. Change the enabled state, CRON schedule, target pipeline, or description. "
                "USE THIS to set or adjust a tap's schedule (`cron_expression`) whenever the user describes a recurrence (nightly, daily, every morning, market open, etc.). "
                "The platform's scheduler runs the tap on the cadence you set — no external cron, Airflow DAG, or shell loop is needed (or wanted). See the SCHEDULING RULE in the server instructions. "
                "VALIDATION RULE: if you're enabling a `cron_expression` on a tap whose script has NEVER been validated, call `test_tap` FIRST and confirm it succeeds. The cadence-change path is safe for taps that have already run successfully; it is not safe to set a cron on a never-tested script. "
                "To change the SCRIPT itself, call `create_tap` again with the same `name` and the new `script` or `instruction` — create_tap upserts by name and replaces the existing script. There is no separate script-only update tool."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "Name of the tap to update"
                    },
                    "enabled": {
                        "type": "boolean",
                        "description": "Enable or disable the tap"
                    },
                    "cron_expression": {
                        "type": "string",
                        "description": "Quartz CRON expression for recurring runs (e.g., '0 0 * * * ?' for hourly, '0 30 5 ? * MON-FRI' for weekdays 5:30am). SET THIS whenever the user describes a recurrence — nightly, daily, hourly, market open, etc. The platform's scheduler fires the tap on this cadence automatically; do not propose external schedulers or shell cron for the user to run themselves."
                    },
                    "target_pipeline": {
                        "type": "string",
                        "description": "New target pipeline name"
                    },
                    "description": {
                        "type": "string",
                        "description": "New plain-English description"
                    },
                },
                "required": ["name"]
            }
        ),
        Tool(
            name="wait_seconds",
            description=(
                "Sleep for a fixed number of seconds, then return. Use this to pace polling against long-running "
                "platform work (most often `get_pipeline_status` after `run_tap`, or `get_job_status` after `upload_data`) "
                "so you do not burn tool calls hammering an endpoint that is still in progress. "
                "ALWAYS poll once BEFORE the first wait — many runs finish in 1–5 seconds. "
                "Then use exponential backoff between polls: 5s, 10s, 20s, 30s, 60s, 60s, ... (cap at 60s normally, 120s only if the run is genuinely glacial). "
                "Reset to a short wait (5–15s) on the next cycle whenever a poll shows new jobs flipped to a terminal state — that means you're close to done. "
                "If 80%+ of jobs are terminal, wait ~15s; if only 1–2 jobs remain, wait ~10s. "
                "Hard upper bound is 120 seconds per call — for longer waits, call this tool in a loop interleaved with a status check."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "seconds": {
                        "type": "integer",
                        "description": "How long to sleep, in seconds. Range: 1–120. Values outside this range are clamped."
                    }
                },
                "required": ["seconds"]
            }
        ),
    ]


@server.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    started = time.time()
    session_id = _session_id.get() or "stdio"
    api_key = _session_api_key.get()
    status = "ok"
    result_text = ""
    error_msg = ""
    try:
        # _dispatch (and the _call/_upload helpers it invokes) uses synchronous
        # `requests` with a 300s timeout. Running it directly in this async
        # handler would block the asyncio event loop for the duration of every
        # tool call — starving /activity, /sse, /mcp, and any concurrent
        # tool calls from other sessions. asyncio.to_thread moves it to a
        # worker thread so the event loop stays responsive. Contextvars
        # (_session_api_key, _session_id) are copied automatically — see
        # asyncio.to_thread docs.
        result_text = await asyncio.to_thread(_dispatch, name, arguments)
        return [TextContent(type="text", text=result_text)]
    except Exception as e:
        status = "error"
        error_msg = str(e)
        result_text = json.dumps({"error": error_msg})
        return [TextContent(type="text", text=result_text)]
    finally:
        latency_ms = int((time.time() - started) * 1000)
        _activity_record(session_id, name, status, latency_ms, api_key,
                         arguments, result_text, error_msg)


def _dispatch(name: str, args: dict) -> str:
    # --- Pipeline Management ---
    if name == "list_pipelines":
        result = _call("get", "/api/v1/pipelines")
        try:
            pipelines = json.loads(result)
            if not pipelines or (isinstance(pipelines, list) and len(pipelines) == 0):
                return json.dumps({"pipelines": [], "message": "No pipelines exist. You MUST create a pipeline before you can ingest or query data. Call create_pipeline — for structured destinations pass sample data (base64) + filename; for vector destinations (pgvector, qdrant, weaviate, milvus, chroma) pass only pipeline name + destination."})
        except (json.JSONDecodeError, TypeError):
            pass
        return result

    elif name == "get_pipeline":
        return _call("get", "/api/v1/pipeline", params={"pipeline": args["pipeline"]})

    elif name == "create_pipeline":
        pipeline_name = args["pipeline"]
        table_name = args.get("table", pipeline_name)
        db_name = args.get("database", "datris")
        dest_type = args.get("destination", "")
        filename = args.get("filename", "")

        # Auto-detect destination if not specified
        if not dest_type:
            fn_lower = filename.lower()
            if fn_lower.endswith(".json"):
                dest_type = "mongodb"
            elif fn_lower.endswith(".xml"):
                dest_type = "postgres"
            elif fn_lower.endswith(".pdf") or fn_lower.endswith(".docx") or fn_lower.endswith(".txt"):
                dest_type = "pgvector"
            else:
                dest_type = "postgres"

        is_vector = dest_type in ("pgvector", "qdrant", "weaviate", "milvus", "chroma")

        if is_vector:
            # Vector destinations have no schema to detect — synthesize the config
            # directly and skip the /generate round-trip. Saves ~90K tokens of
            # base64'd sample content for typical PDF/DOCX uploads.
            file_ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else "txt"
            config = {
                "name": pipeline_name,
                "source": {
                    "fileAttributes": {
                        "unstructuredAttributes": {"fileExtension": file_ext}
                    }
                }
            }
        else:
            # Structured destinations: send sample to /generate for schema detection
            if not args.get("content") or not filename:
                return json.dumps({"error": "content and filename are required for non-vector destinations (used for schema auto-detection)"})
            gen_data = {"pipeline": pipeline_name, "allStrings": "true"}
            if args.get("delimiter"):
                gen_data["delimiter"] = args["delimiter"]
            header = args.get("header", True)
            gen_data["header"] = str(header).lower()
            gen_result = _upload_content("/api/v1/pipeline/generate", args["content"], filename, gen_data)
            try:
                config = json.loads(gen_result)
            except json.JSONDecodeError:
                return json.dumps({"error": "Failed to generate schema: " + gen_result})

        # Build destination
        dest = {}
        key_fields = args.get("keyFields") or None
        truncate = bool(args.get("truncate", False))
        if dest_type == "postgres":
            db_cfg = {"dbName": db_name, "schema": "public", "table": table_name, "usePostgres": True}
            if key_fields: db_cfg["keyFields"] = key_fields
            if truncate:   db_cfg["truncateBeforeWrite"] = True
            dest["database"] = db_cfg
        elif dest_type == "mongodb":
            db_cfg = {"dbName": db_name, "table": table_name, "useMongoDB": True}
            if key_fields: db_cfg["keyFields"] = key_fields
            if truncate:   db_cfg["truncateBeforeWrite"] = True
            dest["database"] = db_cfg
        elif dest_type == "qdrant":
            dest["qdrant"] = {"collectionName": table_name, "embeddingSecretName": "oss/embedding", "qdrantSecretName": "oss/qdrant", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        elif dest_type == "weaviate":
            dest["weaviate"] = {"className": table_name, "embeddingSecretName": "oss/embedding", "weaviateSecretName": "oss/weaviate", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        elif dest_type == "milvus":
            dest["milvus"] = {"collectionName": table_name, "embeddingSecretName": "oss/embedding", "milvusSecretName": "oss/milvus", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        elif dest_type == "chroma":
            dest["chroma"] = {"collectionName": table_name, "embeddingSecretName": "oss/embedding", "chromaSecretName": "oss/chroma", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        elif dest_type == "pgvector":
            dest["pgvector"] = {"tableName": table_name, "schemaName": "public", "embeddingSecretName": "oss/embedding", "postgresSecretName": "oss/pgvector", "chunking": {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}}
        else:
            dest["database"] = {"dbName": db_name, "schema": "public", "table": table_name, "usePostgres": True}

        config["destination"] = dest

        # Step 2b: Add optional CodeGen data quality rule
        if args.get("codegen_rule"):
            config["dataQuality"] = {"aiRule": {"instruction": args["codegen_rule"], "onFailureIsError": True}}

        # Step 2c: Add optional CodeGen transformation
        if args.get("codegen_transform"):
            config["transformation"] = {"aiTransformation": {"instruction": args["codegen_transform"]}}

        # Step 2d: Add optional catalog grouping label
        if args.get("catalog"):
            config["catalog"] = args["catalog"]

        # Step 3: Register the pipeline
        create_result = _call("post", "/api/v1/pipeline", json=config)

        # Check if registration failed
        if create_result and ("Exception" in create_result or "error" in create_result.lower()):
            return json.dumps({"error": "Failed to register pipeline: " + create_result[:500]})

        # Verify the pipeline was actually created by reading it back
        verify = _call("get", "/api/v1/pipeline", params={"pipeline": pipeline_name})
        if verify and "not configured" in verify.lower():
            return json.dumps({"error": "Pipeline registration failed silently. Generated config may be invalid.", "config": str(config)[:500]})

        actual_name = config.get("name", pipeline_name)
        response = {"status": "Pipeline created", "pipeline": actual_name, "destination": dest_type, "table": table_name}
        if dest_type in ("pgvector", "qdrant", "weaviate", "milvus", "chroma"):
            response["nextStep"] = (
                "Vector destination — call upload_data ONCE with the entire document content. "
                "The server chunks it server-side (chunkSize 500, overlap 50) and embeds each chunk."
            )
        return json.dumps(response)

    elif name == "delete_pipeline":
        params = {"pipeline": args["pipeline"]}
        if args.get("keep_config", False):
            # Reset mode: wipe destination data but keep the pipeline config.
            params["deleteConfig"] = "false"
            params["deleteData"] = "true"
        return _call("delete", "/api/v1/pipeline", params=params)

    elif name == "upload_data":
        data = {"pipeline": args["pipeline"]}
        result = _upload_content("/api/v1/pipeline/upload", args["content"], args["filename"], data)
        token = result.strip() if result else ""
        if token and not token.startswith("{"):
            return json.dumps({"pipelineToken": token, "message": "Upload successful. Pass this pipelineToken to get_job_status and poll until rollup.allDone is true."})
        return result

    elif name == "get_job_status":
        # Token queries opt into the rollup shape ({rollup, events}) so callers
        # have a single boolean (rollup.allDone) and aggregate status to poll on
        # instead of having to replay begin/info/end/error rules over raw events.
        # Name queries hit the paginated summary path, which already carries a
        # status field per row.
        params = {}
        if args.get("pipeline_token"):
            params["pipelinetoken"] = args["pipeline_token"]
            params["withrollup"] = "true"
        if args.get("pipeline_name"):
            params["pipelinename"] = args["pipeline_name"]
        if args.get("page"):
            params["page"] = args["page"]
        return _call("get", "/api/v1/pipeline/status", params=params)

    elif name == "kill_job":
        payload = {"pipelineToken": args["pipeline_token"]}
        return _call("post", "/api/v1/job/kill", json=payload)

    elif name == "profile_data":
        data = {}
        if args.get("delimiter"):
            data["delimiter"] = args["delimiter"]
        if args.get("header") is not None:
            data["header"] = str(args["header"]).lower()
        if args.get("sample_size"):
            data["sampleSize"] = str(args["sample_size"])
        return _upload_content("/api/v1/pipeline/profile", args["content"], args["filename"], data)

    elif name == "get_version":
        return _call("get", "/api/v1/version")

    elif name == "check_service_health":
        return _call("get", "/api/v1/health/services")

    # --- Vector Database Search (via REST API) ---
    elif name == "search_qdrant":
        payload = {"query": args["query"]}
        if args.get("collection"):
            payload["collection"] = args["collection"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/qdrant", json=payload)

    elif name == "search_weaviate":
        payload = {"query": args["query"]}
        if args.get("class_name"):
            payload["className"] = args["class_name"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/weaviate", json=payload)

    elif name == "search_milvus":
        payload = {"query": args["query"]}
        if args.get("collection"):
            payload["collection"] = args["collection"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/milvus", json=payload)

    elif name == "search_pgvector":
        payload = {"query": args["query"]}
        if args.get("table"):
            payload["table"] = args["table"]
        if args.get("schema"):
            payload["schema"] = args["schema"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/pgvector", json=payload)

    elif name == "search_chroma":
        payload = {"query": args["query"]}
        if args.get("collection"):
            payload["collection"] = args["collection"]
        if args.get("top_k"):
            payload["topK"] = args["top_k"]
        return _call("post", "/api/v1/search/chroma", json=payload)

    # --- Database Queries (via REST API) ---
    elif name == "query_postgres":
        payload = {"sql": args["sql"]}
        if args.get("limit"):
            payload["limit"] = args["limit"]
        return _call("post", "/api/v1/query/postgres", json=payload)

    elif name == "query_mongodb":
        payload = {"collection": args["collection"]}
        if args.get("filter"):
            payload["filter"] = args["filter"]
        if args.get("projection"):
            payload["projection"] = args["projection"]
        if args.get("limit"):
            payload["limit"] = args["limit"]
        return _call("post", "/api/v1/query/mongodb", json=payload)

    elif name == "query_natural":
        payload = {"question": args["question"], "table": args["table"]}
        if args.get("schema"):
            payload["schema"] = args["schema"]
        if args.get("database"):
            payload["database"] = args["database"]
        if args.get("limit"):
            payload["limit"] = args["limit"]
        return _call("post", "/api/v1/query/natural", json=payload)

    # --- Metadata Discovery (via REST API) ---
    elif name == "list_postgres_databases":
        return _call("get", "/api/v1/metadata/postgres/databases")

    elif name == "list_postgres_schemas":
        params = {}
        if args.get("database"):
            params["database"] = args["database"]
        return _call("get", "/api/v1/metadata/postgres/schemas", params=params)

    elif name == "list_postgres_tables":
        params = {}
        if args.get("database"):
            params["database"] = args["database"]
        if args.get("schema"):
            params["schema"] = args["schema"]
        if args.get("vector_only") is not None:
            params["vectorOnly"] = str(args["vector_only"]).lower()
        return _call("get", "/api/v1/metadata/postgres/tables", params=params)

    elif name == "list_postgres_columns":
        params = {"table": args["table"]}
        if args.get("database"):
            params["database"] = args["database"]
        if args.get("schema"):
            params["schema"] = args["schema"]
        return _call("get", "/api/v1/metadata/postgres/columns", params=params)

    elif name == "list_mongodb_databases":
        return _call("get", "/api/v1/metadata/mongodb/databases")

    elif name == "list_mongodb_collections":
        params = {}
        if args.get("database"):
            params["database"] = args["database"]
        return _call("get", "/api/v1/metadata/mongodb/collections", params=params)

    # --- Vector Store Metadata ---
    elif name == "list_qdrant_collections":
        return _call("get", "/api/v1/metadata/qdrant/collections")

    elif name == "list_weaviate_classes":
        return _call("get", "/api/v1/metadata/weaviate/classes")

    elif name == "list_milvus_collections":
        return _call("get", "/api/v1/metadata/milvus/collections")

    elif name == "list_chroma_collections":
        return _call("get", "/api/v1/metadata/chroma/collections")

    elif name == "list_pgvector_collections":
        return _call("get", "/api/v1/metadata/postgres/tables", params={"vectorOnly": "true"})

    # --- AI ---
    elif name == "ai_answer":
        payload = {"query": args["query"], "context": args["context"]}
        return _call("post", "/api/v1/ai/answer", json=payload)

    # --- Config ---
    elif name == "upload_config":
        data = {"type": args["type"]}
        return _upload_content("/api/v1/config/upload", args["content"], args["filename"], data)

    # --- Secrets ---
    elif name == "update_secret":
        allowed = {"anthropic", "openai", "ollama", "embedding"}
        secret_name = args["name"]
        if secret_name not in allowed:
            return json.dumps({"error": f"Only these secrets can be updated: {', '.join(sorted(allowed))}"})
        return _call("put", f"/api/v1/secrets/{secret_name}", json=args["fields"])

    elif name == "list_tap_secrets":
        # GET /api/v1/secrets?type=tap returns the names of all secrets tagged _type=tap.
        # No values are returned — name discovery only.
        return _call("get", "/api/v1/secrets", params={"type": "tap"})

    elif name == "get_tap_secret_fields":
        secret_name = args["name"]
        try:
            data = json.loads(_call("get", f"/api/v1/secrets/{secret_name}"))
        except (json.JSONDecodeError, TypeError):
            return json.dumps({"error": f"Could not look up secret '{secret_name}'."})
        if not isinstance(data, dict) or "error" in data:
            return json.dumps({"error": f"Secret '{secret_name}' not found."})
        fields = data.get("fields") or {}
        # Strip the _type marker — it's a platform tag, not a user-facing field. Return
        # NAMES ONLY (no values) so the agent never sees secret material.
        field_names = [k for k in fields.keys() if k != "_type"]
        return json.dumps({"name": secret_name, "fieldNames": field_names, "_type": fields.get("_type")})

    elif name == "create_tap_secret":
        reserved = {"anthropic", "openai", "ollama", "embedding", "ai-primary", "codegen"}
        secret_name = args["name"]
        if secret_name in reserved:
            return json.dumps({"error": f"'{secret_name}' is a reserved AI-provider slot. Use update_secret for those, or pick a different name for your tap secret."})
        overwrite = args.get("overwrite", False)
        existing_fields = None
        try:
            existing_data = json.loads(_call("get", f"/api/v1/secrets/{secret_name}"))
            if isinstance(existing_data, dict) and "error" not in existing_data:
                existing_fields = existing_data.get("fields") or {}
        except (json.JSONDecodeError, TypeError):
            existing_fields = None
        if existing_fields is not None:
            if not overwrite:
                return json.dumps({"error": f"Secret '{secret_name}' already exists. Pass overwrite=true to replace it (confirm with the user first)."})
            if existing_fields.get("_type") != "tap":
                return json.dumps({"error": f"Secret '{secret_name}' is not a tap secret and was not created by an agent. Agents can only modify tap secrets — have the user update this one in the Secrets tab."})
        fields = dict(args["fields"])
        fields.setdefault("_type", "tap")
        return _call("put", f"/api/v1/secrets/{secret_name}", json=fields)

    elif name == "delete_tap_secret":
        secret_name = args["name"]
        try:
            existing_data = json.loads(_call("get", f"/api/v1/secrets/{secret_name}"))
        except (json.JSONDecodeError, TypeError):
            return json.dumps({"error": f"Could not look up secret '{secret_name}'."})
        if not isinstance(existing_data, dict) or "error" in existing_data:
            return json.dumps({"error": f"Secret '{secret_name}' not found."})
        existing_fields = existing_data.get("fields") or {}
        if existing_fields.get("_type") != "tap":
            return json.dumps({"error": f"Secret '{secret_name}' is not a tap secret. Agents can only delete tap secrets."})
        return _call("delete", f"/api/v1/secrets/{secret_name}")

    # --- Taps ---
    elif name == "create_tap":
        tap_name = args["name"]
        instruction = args.get("instruction")
        script = args.get("script")
        target_pipeline = args.get("target_pipeline")
        cron_expression = args.get("cron_expression")
        secret_name = args.get("secret_name")
        tap_type = args.get("tap_type", "structured")
        caller_packages = args.get("packages")

        script_path = None
        packages = caller_packages

        # Mode 1: User-provided script — store directly
        if script:
            store_result = _call("post", "/api/v1/tap/script", json={"tapName": tap_name, "script": script})
            try:
                store_data = json.loads(store_result)
                if "error" in store_data:
                    return store_result
                script_path = store_data.get("scriptPath")
            except (json.JSONDecodeError, TypeError):
                return json.dumps({"error": f"Script storage failed: {store_result[:200]}"})

        # Mode 2: AI-generated script from instruction
        elif instruction:
            gen_payload = {"description": instruction, "tapName": tap_name, "tapType": tap_type}
            if secret_name:
                gen_payload["secretName"] = secret_name
            gen_result = _call("post", "/api/v1/tap/generate", json=gen_payload)
            try:
                gen_data = json.loads(gen_result)
                if "error" in gen_data:
                    return gen_result
                script_path = gen_data.get("scriptPath")
                if caller_packages is None:
                    packages = gen_data.get("packages")
            except (json.JSONDecodeError, TypeError):
                return json.dumps({"error": f"Script generation failed: {gen_result[:200]}"})

        # Mode 3: No script — config only (script added later)

        # Save the tap config
        tap_config = {
            "name": tap_name,
            "enabled": True,
            "tapType": tap_type,
        }
        if instruction:
            tap_config["description"] = instruction
        if script_path:
            tap_config["scriptPath"] = script_path
        if packages:
            tap_config["packages"] = packages
        if target_pipeline:
            tap_config["targetPipeline"] = target_pipeline
        if cron_expression:
            tap_config["cronExpression"] = cron_expression
        if secret_name:
            tap_config["secretName"] = secret_name

        save_result = _call("post", "/api/v1/tap", json=tap_config)
        try:
            saved = json.loads(save_result)
            if "error" in saved:
                return save_result
            return json.dumps({"message": f"Tap '{tap_name}' created successfully", "tap": saved})
        except (json.JSONDecodeError, TypeError):
            return json.dumps({"message": f"Tap '{tap_name}' created"})

    elif name == "list_taps":
        result = _call("get", "/api/v1/taps")
        try:
            taps = json.loads(result)
            if isinstance(taps, list):
                summary = []
                for t in taps:
                    summary.append({
                        "name": t.get("name"),
                        "description": t.get("description"),
                        "tapType": t.get("tapType") or "structured",
                        "targetPipeline": t.get("targetPipeline"),
                        "cronExpression": t.get("cronExpression"),
                        "enabled": t.get("enabled"),
                        "lastRunStatus": t.get("lastRunStatus"),
                        "lastRunTime": t.get("lastRunTime"),
                        "lastRunRecordCount": t.get("lastRunRecordCount"),
                        "lastTestRunStatus": t.get("lastTestRunStatus"),
                        "lastTestRunTime": t.get("lastTestRunTime"),
                        "lastTestRunRecordCount": t.get("lastTestRunRecordCount"),
                    })
                return json.dumps(summary, indent=2)
        except (json.JSONDecodeError, TypeError):
            pass
        return result

    elif name == "run_tap":
        payload = {"name": args["name"], "mode": "run"}
        if "params" in args and args["params"]:
            payload["params"] = args["params"]
        return _call("post", "/api/v1/tap/run", json=payload)

    elif name == "get_pipeline_status":
        publisher = args.get("publisher_token")
        pipeline = args.get("pipeline_token")
        if not publisher and not pipeline:
            return json.dumps({"error": "Pass publisher_token (preferred) or pipeline_token."})
        params = {"withrollup": "true"}
        if publisher:
            params["publishertoken"] = publisher
        elif pipeline:
            params["pipelinetoken"] = pipeline
        return _call("get", "/api/v1/pipeline/status", params=params)

    elif name == "delete_tap":
        return _call("delete", f"/api/v1/tap?name={args['name']}")

    elif name == "get_tap":
        return _call("get", f"/api/v1/tap?name={args['name']}")

    elif name == "get_tap_logs":
        return _call("get", f"/api/v1/tap/logs?name={args['name']}")

    elif name == "get_tap_ledger":
        tap_name = args["name"]
        clear_uri = args.get("clear_uri")
        clear_all = args.get("clear_all")
        if clear_uri:
            from urllib.parse import quote
            return _call("delete", f"/api/v1/tap/ledger?name={quote(tap_name)}&uri={quote(clear_uri)}")
        if clear_all:
            return _call("delete", f"/api/v1/tap/ledger?name={tap_name}")
        return _call("get", f"/api/v1/tap/ledger?name={tap_name}")

    elif name == "test_tap":
        return _call("post", "/api/v1/tap/run", json={"name": args["name"], "mode": "test"})

    elif name == "set_catalog":
        # Read-modify-write: there is no PATCH endpoint for either entity. The
        # tap POST path strips a `script` field that GET adds for convenience;
        # the pipeline POST path round-trips its own config cleanly.
        pipeline_name = args.get("pipeline")
        tap_name = args.get("tap")
        if bool(pipeline_name) == bool(tap_name):
            return json.dumps({"error": "Pass exactly one of `pipeline` or `tap`."})
        new_catalog = args.get("catalog") or ""

        if pipeline_name:
            existing = _call("get", "/api/v1/pipeline", params={"pipeline": pipeline_name})
            try:
                config = json.loads(existing)
            except (json.JSONDecodeError, TypeError):
                return json.dumps({"error": f"Pipeline '{pipeline_name}' not found"})
            if not isinstance(config, dict) or config.get("name") != pipeline_name:
                return json.dumps({"error": f"Pipeline '{pipeline_name}' not found"})
            config["catalog"] = new_catalog if new_catalog else None
            save_result = _call("post", "/api/v1/pipeline", json=config)
            if save_result and ("Exception" in save_result or "error" in save_result.lower()):
                return json.dumps({"error": "Failed to update pipeline: " + save_result[:500]})
            return json.dumps({
                "message": f"Pipeline '{pipeline_name}' catalog " + ("cleared" if not new_catalog else f"set to '{new_catalog}'"),
                "pipeline": pipeline_name,
                "catalog": new_catalog or None,
            })

        # tap path
        existing = _call("get", f"/api/v1/tap?name={tap_name}")
        try:
            tap_config = json.loads(existing)
            if "error" in tap_config:
                return existing
        except (json.JSONDecodeError, TypeError):
            return json.dumps({"error": f"Tap '{tap_name}' not found"})
        tap_config["catalog"] = new_catalog if new_catalog else None
        # GET /tap injects these for UI use; POST /tap doesn't expect them.
        tap_config.pop("script", None)
        tap_config.pop("scriptMissing", None)
        save_result = _call("post", "/api/v1/tap", json=tap_config)
        try:
            saved = json.loads(save_result)
            if "error" in saved:
                return save_result
        except (json.JSONDecodeError, TypeError):
            pass
        return json.dumps({
            "message": f"Tap '{tap_name}' catalog " + ("cleared" if not new_catalog else f"set to '{new_catalog}'"),
            "tap": tap_name,
            "catalog": new_catalog or None,
        })

    elif name == "update_tap":
        # Fetch existing config
        tap_result = _call("get", f"/api/v1/tap?name={args['name']}")
        try:
            tap_config = json.loads(tap_result)
            if "error" in tap_config:
                return tap_result
        except (json.JSONDecodeError, TypeError):
            return json.dumps({"error": f"Tap '{args['name']}' not found"})

        # Merge provided fields
        if "enabled" in args:
            tap_config["enabled"] = args["enabled"]
        if "cron_expression" in args:
            tap_config["cronExpression"] = args["cron_expression"]
        if "target_pipeline" in args:
            tap_config["targetPipeline"] = args["target_pipeline"]
        if "description" in args:
            tap_config["description"] = args["description"]

        # Remove the script content field (GET /tap adds it but POST /tap doesn't expect it)
        tap_config.pop("script", None)

        # Save via upsert
        save_result = _call("post", "/api/v1/tap", json=tap_config)
        try:
            saved = json.loads(save_result)
            if "error" in saved:
                return save_result
            return json.dumps({"message": f"Tap '{args['name']}' updated", "tap": saved})
        except (json.JSONDecodeError, TypeError):
            return json.dumps({"message": f"Tap '{args['name']}' updated"})

    elif name == "wait_seconds":
        # Clamp to a safe range; the LLM occasionally asks for absurd values.
        # Upper bound matches the description so the agent learns the limit.
        # _dispatch is sync (every other tool branch calls blocking `requests`),
        # so plain time.sleep is correct here.
        try:
            requested = int(args.get("seconds", 0))
        except (TypeError, ValueError):
            return json.dumps({"error": "seconds must be an integer"})
        seconds = max(1, min(120, requested))
        time.sleep(seconds)
        return json.dumps({"slept": seconds, "requested": requested})

    else:
        return json.dumps({"error": f"Unknown tool: {name}"})


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

async def run_stdio():
    from mcp.server.stdio import stdio_server
    async with stdio_server() as (read_stream, write_stream):
        await server.run(read_stream, write_stream, server.create_initialization_options())


def _extract_api_key(scope) -> str:
    """Extract x-api-key from ASGI scope headers or query string."""
    # Check headers first
    for header_name, header_value in scope.get("headers", []):
        if header_name == b"x-api-key":
            return header_value.decode("utf-8")
    # Fall back to query string (?api_key=...)
    qs = scope.get("query_string", b"").decode("utf-8")
    for part in qs.split("&"):
        if part.startswith("api_key="):
            return part[8:]
    return ""


async def _handle_activity(scope, send) -> None:
    method = scope.get("method", "GET").upper()
    if method == "DELETE":
        _activity_clear()
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})
        return
    qs = scope.get("query_string", b"").decode("utf-8")
    since = 0.0
    for part in qs.split("&"):
        if part.startswith("since="):
            try:
                since = float(part[6:])
            except ValueError:
                since = 0.0
    body = json.dumps(_activity_snapshot(since)).encode("utf-8")
    await send({"type": "http.response.start", "status": 200,
                "headers": [[b"content-type", b"application/json"]]})
    await send({"type": "http.response.body", "body": body})


async def run_sse(port: int):
    # Dual transport: serves SSE (/sse + /messages) for Claude Desktop / Cursor
    # AND Streamable HTTP (/mcp) for the in-product Datris Assistant. Both
    # surface the same tool catalog because they wrap the same `server`
    # instance. The streamable HTTP transport runs in stateless mode so each
    # POST is independent — no session ID needed.
    from mcp.server.sse import SseServerTransport
    from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
    from starlette.applications import Starlette
    import contextlib
    import uvicorn

    sse = SseServerTransport("/messages")
    streamable_mgr = StreamableHTTPSessionManager(app=server, json_response=False, stateless=True)

    @contextlib.asynccontextmanager
    async def lifespan(_app):
        # StreamableHTTPSessionManager needs its run() context active for the
        # lifetime of the process. SSE has no equivalent lifecycle hook.
        async with streamable_mgr.run():
            yield

    starlette_app = Starlette(lifespan=lifespan)

    async def app(scope, receive, send):
        if scope["type"] == "lifespan":
            await starlette_app(scope, receive, send)
            return
        if scope["type"] == "http":
            path = scope.get("path", "")
            if path == "/sse":
                api_key = _extract_api_key(scope)
                if REQUIRE_API_KEY and not api_key:
                    await send({"type": "http.response.start", "status": 401,
                                "headers": [[b"content-type", b"application/json"]]})
                    await send({"type": "http.response.body",
                                "body": b'{"error":"x-api-key header required. Sign up at datris.ai to get an API key."}'})
                    return
                _session_api_key.set(api_key)
                sess_id = uuid.uuid4().hex
                _session_id.set(sess_id)
                _activity_session_open(sess_id, api_key)
                try:
                    async with sse.connect_sse(scope, receive, send) as streams:
                        await server.run(streams[0], streams[1], server.create_initialization_options())
                finally:
                    _activity_session_close(sess_id)
                return
            elif path == "/messages":
                await sse.handle_post_message(scope, receive, send)
                return
            elif path == "/mcp":
                api_key = _extract_api_key(scope)
                if REQUIRE_API_KEY and not api_key:
                    await send({"type": "http.response.start", "status": 401,
                                "headers": [[b"content-type", b"application/json"]]})
                    await send({"type": "http.response.body",
                                "body": b'{"error":"x-api-key header required."}'})
                    return
                _session_api_key.set(api_key)
                sess_id = uuid.uuid4().hex
                _session_id.set(sess_id)
                _activity_session_open(sess_id, api_key)
                try:
                    await streamable_mgr.handle_request(scope, receive, send)
                finally:
                    _activity_session_close(sess_id)
                return
            elif path == "/activity":
                await _handle_activity(scope, send)
                return
        # 404 for anything else
        await send({"type": "http.response.start", "status": 404, "headers": []})
        await send({"type": "http.response.body", "body": b"Not Found"})

    config = uvicorn.Config(app, host="0.0.0.0", port=port, lifespan="on")
    srv = uvicorn.Server(config)
    await srv.serve()


async def run_streamable_http(port: int):
    from mcp.server.streamable_http_manager import StreamableHTTPSessionManager
    import contextlib
    import uvicorn

    session_manager = StreamableHTTPSessionManager(app=server, json_response=False, stateless=True)

    @contextlib.asynccontextmanager
    async def lifespan(app):
        async with session_manager.run():
            yield

    async def app(scope, receive, send):
        if scope["type"] == "lifespan":
            from starlette.applications import Starlette
            starlette_app = Starlette(lifespan=lifespan)
            await starlette_app(scope, receive, send)
            return
        if scope["type"] == "http":
            path = scope.get("path", "")
            if path == "/mcp":
                api_key = _extract_api_key(scope)
                if REQUIRE_API_KEY and not api_key:
                    await send({"type": "http.response.start", "status": 401,
                                "headers": [[b"content-type", b"application/json"]]})
                    await send({"type": "http.response.body",
                                "body": b'{"error":"x-api-key header required. Sign up at datris.ai to get an API key."}'})
                    return
                _session_api_key.set(api_key)
                sess_id = uuid.uuid4().hex
                _session_id.set(sess_id)
                _activity_session_open(sess_id, api_key)
                try:
                    await session_manager.handle_request(scope, receive, send)
                finally:
                    _activity_session_close(sess_id)
                return
            elif path == "/activity":
                await _handle_activity(scope, send)
                return
        await send({"type": "http.response.start", "status": 404, "headers": []})
        await send({"type": "http.response.body", "body": b"Not Found"})

    config = uvicorn.Config(app, host="0.0.0.0", port=port, lifespan="on")
    srv = uvicorn.Server(config)
    await srv.serve()


def main():
    import asyncio

    parser = argparse.ArgumentParser(description="Datris MCP Server")
    parser.add_argument("--sse", action="store_true", help="Run in SSE mode (default: stdio)")
    parser.add_argument("--streamable-http", action="store_true", help="Run in Streamable HTTP mode")
    parser.add_argument("--port", type=int, default=3000, help="Server port (default: 3000)")
    args = parser.parse_args()

    if args.streamable_http:
        asyncio.run(run_streamable_http(args.port))
    elif args.sse:
        asyncio.run(run_sse(args.port))
    else:
        asyncio.run(run_stdio())


if __name__ == "__main__":
    main()
