"""
agent/executor.py

Routes each Anthropic tool_use block to the Datris MCP server.

All data sourcing lives on the platform as taps; the agent never handles
data content. Every tool call is a direct MCP pass-through, with a little
local bookkeeping to keep the UI's pipeline tiles in sync with tap activity.
"""

import logging

from agent.mcp_client import call_tool as mcp_call, is_connected
from agent.pipeline_store import store
from agent.tap_definitions import PIPELINE_TO_TAP

log = logging.getLogger("datris.executor")


# Tap name → local pipeline-store key. Used when we see a tap-related
# MCP call and want to reflect status on the UI tile.
_TAP_TO_PIPELINE: dict[str, str] = {v: k for k, v in PIPELINE_TO_TAP.items()}

# Tap names with a run_tap currently in flight in this agent process. Used to
# short-circuit duplicate run_tap calls — e.g. when the model emits the same
# tool_use twice in one turn, or when the scheduler and a user-initiated refresh
# fire simultaneously. Each run_tap takes seconds to minutes; pushing the same
# data twice would just create duplicate rows in the destination.
_inflight_run_taps: set[str] = set()


async def execute_tool(name: str, input_: dict) -> dict:
    """Forward a tool call to the MCP server, with UI bookkeeping side-effects."""
    if not is_connected():
        return {"error": "Datris MCP server is unavailable. Retrying in the background — try again shortly."}

    await store.add_activity("tool", f"MCP call: {name}")

    # In-flight dedup for run_tap. Check before kicking off MCP so duplicate
    # calls return immediately instead of spawning parallel script runs.
    if name == "run_tap":
        tap_name = input_.get("name", "")
        if tap_name and tap_name in _inflight_run_taps:
            log.info("run_tap dedup: '%s' already in flight, skipping", tap_name)
            await store.add_activity(
                "info",
                f"Skipped duplicate run_tap for '{tap_name}' — a run is already in progress.",
            )
            return {
                "tap": tap_name,
                "status": "skipped",
                "mode": "run",
                "persisted": False,
                "persistedReason": "already_running",
                "error": (
                    f"A run_tap for '{tap_name}' is already in progress in this agent "
                    "session. Wait for it to finish, then check `get_pipeline_status` or "
                    "`get_tap_logs` for the result."
                ),
                "recordCount": 0,
            }
        if tap_name:
            _inflight_run_taps.add(tap_name)

    # Mark a pipeline "ingesting" as soon as the run is kicked off, so the
    # UI tile flips amber while the tap executes.
    if name == "run_tap":
        tap_name = input_.get("name", "")
        pkey = _pipeline_key_for_tap(tap_name)
        if pkey:
            await store.update_pipeline(pkey, {"status": "ingesting"})

    try:
        result = await mcp_call(name, input_)
    finally:
        if name == "run_tap":
            tap_name = input_.get("name", "")
            if tap_name:
                _inflight_run_taps.discard(tap_name)

    if name == "run_tap":
        tap_name = input_.get("name", "")
        pkey = _pipeline_key_for_tap(tap_name)
        if pkey:
            status, msg = _interpret_run_tap(tap_name, result)
            updates: dict = {"status": status}
            rows = _extract_row_count(result)
            if rows is not None:
                updates["rows"] = rows
            await store.update_pipeline(pkey, updates)
            await store.add_activity(
                "error" if status == "error" else "success", msg
            )

    elif name == "create_pipeline":
        pname = (
            result.get("pipeline")
            if isinstance(result, dict) else None
        ) or input_.get("pipeline") or input_.get("config", {}).get("name") or "unknown"
        key = pname.lower().replace(" ", "_")
        await store.update_pipeline(key, {
            "id": pname,
            "name": pname,
            "source": pname,
            "status": "created",
        })
        await store.add_activity("create", f"Pipeline created: {pname}")

    elif name == "list_pipelines":
        await store.add_activity("info", "Listed pipelines from Datris")

    elif name == "get_job_status":
        token = input_.get("pipeline_token", input_.get("pipelineToken", ""))
        status = result.get("status", "unknown") if isinstance(result, dict) else "unknown"
        await store.add_activity("info", f"Job {token[:12]}… status: {status}")

    return result


def _pipeline_key_for_tap(tap_name: str) -> str | None:
    pipeline = _TAP_TO_PIPELINE.get(tap_name)
    if not pipeline:
        return None
    return pipeline.lower().replace(" ", "_")


def _extract_row_count(result) -> int | None:
    """Best-effort row-count extraction from a run_tap response."""
    if not isinstance(result, dict):
        return None
    for key in ("record_count", "rows", "recordCount", "count"):
        val = result.get(key)
        if isinstance(val, int):
            return val
    return None


def _interpret_run_tap(tap_name: str, result) -> tuple[str, str]:
    """
    Map a run_tap response to (pipeline_status, activity_message).

    Response shape (from mcp-server/server.py run_tap description):
      - persisted=true                → records submitted; ingestion async
      - persisted=false, run_error    → script failed
      - persisted=false, no_records   → script returned nothing
      - persisted=false, no_target_pipeline → tap not wired
      - top-level error field         → MCP-level failure
    """
    if not isinstance(result, dict):
        return "error", f"Tap {tap_name}: unexpected response"

    if result.get("error"):
        return "error", f"Tap {tap_name} failed: {result['error']}"

    if result.get("persisted") is False:
        reason = result.get("persistedReason", "unknown")
        if reason == "run_error":
            err = result.get("error") or result.get("message") or "script error"
            return "error", f"Tap {tap_name} script failed: {err}"
        if reason == "no_records":
            return "ready", f"Tap {tap_name} returned no records"
        if reason == "no_target_pipeline":
            return "error", f"Tap {tap_name} has no target pipeline configured"
        return "error", f"Tap {tap_name} did not persist ({reason})"

    # persisted=true (or absent on older servers) — records submitted.
    token = result.get("publisherToken")
    if token:
        return "ingesting", f"Tap {tap_name} submitted (publisherToken {token[:8]}…)"
    return "ready", f"Tap {tap_name} completed"
