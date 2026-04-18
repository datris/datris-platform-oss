"""
agent/executor.py

Routes each Anthropic tool_use block to the real Datris MCP server.

For data-fetching tools (ingest_data), it first pulls live market data
via data_fetcher, then uploads through MCP upload_data.

All other tools are forwarded directly to the MCP server.
"""

import logging

from agent.data_fetcher import fetch_source
from agent.mcp_client import call_tool as mcp_call, is_connected
from agent.pipeline_store import store

log = logging.getLogger("datris.executor")

# Server-side cache for fetched data — keyed by data_id
_data_cache: dict[str, dict] = {}
_data_counter = 0


def _resolve_content(input_: dict) -> dict:
    """Resolve any data_id references in the input — checks both data_id field and content field."""
    resolved = dict(input_)

    # Check explicit data_id field
    data_id = resolved.get("data_id")

    # Also check if content field is a data_id reference (Claude often puts it there directly)
    if not data_id:
        content = resolved.get("content", "")
        if isinstance(content, str) and content.startswith("data_") and content in _data_cache:
            data_id = content

    if data_id and data_id in _data_cache:
        cached = _data_cache[data_id]
        resolved["content"] = cached["content"]
        if "filename" not in resolved:
            resolved["filename"] = cached["filename"]
        if "source" in cached:
            resolved["_resolved_source"] = cached["source"]
        resolved.pop("data_id", None)
        print(f"[executor] Resolved {data_id} → {len(cached['content'])} chars of base64 (source={cached.get('source')})")

    return resolved


# ── ingest_data (special handling: fetch + cache) ─────────────────────────────

async def _ingest_data(input_: dict) -> dict:
    """
    Fetch live data from the named source, cache it server-side, and return
    a data_id reference. The base64 content is NOT returned to Claude — it
    stays server-side to keep the conversation history small.

    Claude uses data_id with create_pipeline, upload_data, and generate_schema.
    The executor resolves data_id → real content before forwarding to MCP.
    """
    global _data_counter

    source = input_.get("source", "")
    source_key = source or input_.get("pipeline_id", input_.get("pipeline", ""))

    await store.add_activity("ingest", f"Fetching live data from {source_key.upper()}...")

    try:
        base64_content, filename = await fetch_source(source_key)
    except ValueError:
        await store.add_activity("error", f"Unknown data source: {source_key}")
        return {"error": f"Unknown data source: {source_key}"}

    if not base64_content:
        await store.add_activity("error", f"No data returned from {source_key}")
        return {"error": f"No data returned from {source_key}"}

    # Cache server-side, return only a reference
    _data_counter += 1
    data_id = f"data_{source_key}_{_data_counter}"
    _data_cache[data_id] = {"content": base64_content, "filename": filename, "source": source_key}

    await store.add_activity("success", f"Fetched {filename} from {source_key.upper()}")

    return {
        "data_id": data_id,
        "filename": filename,
        "source": source_key,
        "message": (
            f"Data fetched and cached as '{data_id}'. "
            f"Pass data_id='{data_id}' to create_pipeline, generate_schema, and upload_data — "
            f"the content will be automatically resolved server-side."
        ),
    }


# ── Generic MCP pass-through ──────────────────────────────────────────────────

async def _mcp_passthrough(name: str, input_: dict) -> dict:
    """Forward a tool call directly to the MCP server, resolving data_id references."""
    if not is_connected():
        return {"error": "Datris MCP server is unavailable. Retrying in the background — try again shortly."}

    resolved = _resolve_content(input_)

    # Validate data source matches pipeline before uploading
    if name == "upload_data":
        resolved_source = resolved.get("_resolved_source")
        pipeline = input_.get("pipeline", "")
        if resolved_source and pipeline:
            key = pipeline.lower().replace(" ", "_")
            snap = await store.snapshot()
            expected = snap["pipelines"].get(key, {}).get("data_source")
            if expected and resolved_source != expected:
                msg = f"Data source mismatch: pipeline '{pipeline}' expects '{expected}' data but received '{resolved_source}' data. Use the correct data_id."
                print(f"[executor] BLOCKED: {msg}")
                return {"error": msg}

    # Save resolved source before stripping internal metadata
    resolved_source = resolved.pop("_resolved_source", None)

    result = await mcp_call(name, resolved)

    # Update pipeline store for state-changing operations
    if name == "create_pipeline":
        # Extract pipeline name from MCP result (preferred) or input
        pname = (
            result.get("pipeline")
            or input_.get("pipeline")
            or input_.get("config", {}).get("name")
            or "unknown"
        )
        key = pname.lower().replace(" ", "_")
        await store.update_pipeline(key, {
            "id": pname,
            "name": pname,
            "source": result.get("destination", "postgres"),
            "data_source": resolved_source,
            "status": "created",
        })
        await store.add_activity("create", f"Pipeline created: {pname}")

    elif name == "list_pipelines":
        await store.add_activity("info", "Listed pipelines from Datris")

    elif name == "get_job_status":
        token = input_.get("pipeline_token", input_.get("pipelineToken", ""))
        status = result.get("status", "unknown")
        await store.add_activity("info", f"Job {token[:12]}… status: {status}")

    return result


# ── Dispatcher ────────────────────────────────────────────────────────────────

# Tools that need special handling (data fetch + upload)
_SPECIAL_HANDLERS = {
    "ingest_data": _ingest_data,
}


async def execute_tool(name: str, input_: dict) -> dict:
    """
    Execute a tool call. Special tools (ingest_data) get custom handling;
    everything else is forwarded directly to the MCP server.
    """
    handler = _SPECIAL_HANDLERS.get(name)
    if handler:
        return await handler(input_)

    await store.add_activity("tool", f"MCP call: {name}")
    return await _mcp_passthrough(name, input_)
