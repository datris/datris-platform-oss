"""
agent/scheduler.py

Background refresh scheduler.

Periodically calls MCP `run_tap` on each provisioned tap so platform-side
data fetches stay fresh. Data sourcing itself happens inside the tap's
Docker sandbox on the Datris platform — the agent just pulls the trigger.

    from agent.scheduler import start_scheduler, stop_scheduler, refresh_now
"""

import asyncio
import logging
import os

from agent.executor import _extract_row_count, _interpret_run_tap
from agent.mcp_client import call_tool as mcp_call, is_connected
from agent.pipeline_store import store
from agent.tap_definitions import PIPELINE_TO_TAP, TAPS

log = logging.getLogger("datris.scheduler")

_task: asyncio.Task | None = None
_interval_minutes: int = 15


def start_scheduler() -> None:
    """Start the background refresh loop."""
    global _task, _interval_minutes
    _interval_minutes = int(os.environ.get("REFRESH_INTERVAL_MINUTES", "15"))
    _task = asyncio.create_task(_loop())
    log.info("Scheduler started — refresh every %d minutes", _interval_minutes)


def stop_scheduler() -> None:
    """Cancel the background refresh loop."""
    global _task
    if _task:
        _task.cancel()
        _task = None
    log.info("Scheduler stopped")


async def _loop() -> None:
    """Run refresh cycles at the configured interval."""
    while True:
        await asyncio.sleep(_interval_minutes * 60)
        try:
            await _refresh_all()
        except asyncio.CancelledError:
            break
        except Exception as e:
            log.error("Refresh cycle failed: %s", e)


async def _refresh_all() -> None:
    """Trigger run_tap for every pipeline that's been exercised at least once."""
    if not is_connected():
        log.warning("MCP not connected — skipping refresh")
        return

    snap = await store.snapshot()
    pipelines = snap["pipelines"]

    for key, p in pipelines.items():
        if p["status"] == "idle":
            continue

        pipeline_name = p.get("id", key)
        tap_name = PIPELINE_TO_TAP.get(pipeline_name)
        if not tap_name:
            continue

        try:
            await _refresh_one(key, pipeline_name, tap_name)
        except Exception as e:
            log.error("Refresh failed for %s: %s", key, e)
            await store.update_pipeline(key, {"status": "error"})
            await store.add_activity("error", f"Refresh failed for {pipeline_name}: {e}")


async def _refresh_one(key: str, pipeline_name: str, tap_name: str) -> None:
    """Run a single tap and reflect status on the pipeline tile."""
    await store.update_pipeline(key, {"status": "ingesting"})
    await store.add_activity("ingest", f"Auto-refresh: running tap {tap_name}...")

    result = await mcp_call("run_tap", {"name": tap_name})

    status, msg = _interpret_run_tap(tap_name, result)
    updates: dict = {"status": status}
    rows = _extract_row_count(result)
    if rows is not None:
        updates["rows"] = rows
    await store.update_pipeline(key, updates)
    await store.add_activity(
        "error" if status == "error" else "success",
        f"Auto-refresh: {msg}",
    )


async def refresh_now(source: str | None = None) -> dict:
    """
    Trigger an immediate refresh.

    If source is None, refresh every tap registered in tap_definitions.
    If source is given, match it against a tap name or pipeline name.
    """
    if not is_connected():
        return {"error": "MCP not connected"}

    refreshed: list[str] = []

    for tap in TAPS:
        tap_name = tap["name"]
        pipeline = tap["pipeline"]
        if source and source not in (tap_name, pipeline):
            continue

        key = pipeline.lower().replace(" ", "_")
        try:
            await _refresh_one(key, pipeline, tap_name)
            refreshed.append(tap_name)
        except Exception as e:
            log.error("Refresh failed for %s: %s", tap_name, e)

    return {"refreshed": refreshed, "count": len(refreshed)}
