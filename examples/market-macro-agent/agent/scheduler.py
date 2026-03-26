"""
agent/scheduler.py

Background refresh scheduler.

Periodically re-fetches data from all active sources and re-uploads
to existing Datris pipelines via MCP upload_data.

    from agent.scheduler import start_scheduler, stop_scheduler, refresh_now
"""

import asyncio
import logging
import os

from agent.data_fetcher import fetch_source
from agent.mcp_client import call_tool as mcp_call, is_connected
from agent.pipeline_store import store

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
    """Re-fetch and re-upload data for every pipeline that has been used."""
    if not is_connected():
        log.warning("MCP not connected — skipping refresh")
        return

    snap = await store.snapshot()
    pipelines = snap["pipelines"]

    for key, p in pipelines.items():
        # Only refresh pipelines that have been used (status != idle)
        if p["status"] == "idle":
            continue

        source = p.get("source", key)
        pipeline_name = p.get("id", key)

        try:
            await _refresh_one(key, pipeline_name, source)
        except Exception as e:
            log.error("Refresh failed for %s: %s", key, e)
            await store.update_pipeline(key, {"status": "error"})
            await store.add_activity("error", f"Refresh failed for {pipeline_name}: {e}")


async def _refresh_one(key: str, pipeline_name: str, source: str) -> None:
    """Re-fetch and re-upload data for a single pipeline."""
    await store.update_pipeline(key, {"status": "ingesting"})
    await store.add_activity("ingest", f"Auto-refresh: fetching {source.upper()}...")

    try:
        base64_content, filename = await fetch_source(source)
    except ValueError:
        log.warning("Unknown source '%s' for pipeline %s — skipping", source, pipeline_name)
        await store.update_pipeline(key, {"status": "ready"})
        return

    if not base64_content:
        await store.update_pipeline(key, {"status": "ready"})
        await store.add_activity("info", f"No data from {source} — skipping")
        return

    result = await mcp_call("upload_data", {
        "content": base64_content,
        "filename": filename,
        "pipeline": pipeline_name,
    })

    token = result.get("pipeline_token") or result.get("pipelineToken", "")
    await store.update_pipeline(key, {"status": "ready", "_pipeline_token": token})
    await store.add_activity("success", f"Auto-refresh complete: {pipeline_name}")


async def refresh_now(source: str | None = None) -> dict:
    """
    Trigger an immediate refresh.

    If source is None, refresh all active pipelines.
    If source is given, refresh only matching pipelines.
    """
    if not is_connected():
        return {"error": "MCP not connected"}

    snap = await store.snapshot()
    refreshed = []

    for key, p in snap["pipelines"].items():
        if p["status"] == "idle":
            continue
        if source and p.get("source", key) != source and key != source:
            continue

        pipeline_name = p.get("id", key)
        src = p.get("source", key)
        try:
            await _refresh_one(key, pipeline_name, src)
            refreshed.append(pipeline_name)
        except Exception as e:
            log.error("Refresh failed for %s: %s", key, e)

    return {"refreshed": refreshed, "count": len(refreshed)}
