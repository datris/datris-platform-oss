"""
agent/pipeline_store.py

In-memory pipeline registry and activity feed.
Shared state between the agent loop and the SSE broadcast endpoint.

Thread-safety: asyncio.Lock guards all mutations.  Read access (for SSE
snapshots) is always done under the same lock so clients never see a torn
state during an ingest update.
"""

import asyncio
import time
from copy import deepcopy
from datetime import datetime
from typing import Any

class PipelineStore:
    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._pipelines: dict[str, dict] = {}
        self._activity: list[dict] = [
            {"id": 1, "type": "info", "msg": "Agent initialized — connected to Datris MCP",
             "time": datetime.utcnow().isoformat()}
        ]
        self._total_rows: int = 0
        self._api_calls: int = 0
        # Subscribers waiting for state-change events (SSE)
        self._subscribers: list[asyncio.Queue] = []

    # ── Subscription (SSE) ────────────────────────────────────────────────────

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue()
        self._subscribers.append(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._subscribers.discard(q) if hasattr(self._subscribers, "discard") else None
        try:
            self._subscribers.remove(q)
        except ValueError:
            pass

    async def _broadcast(self, event_type: str, payload: dict) -> None:
        msg = {"event": event_type, "data": payload}
        for q in list(self._subscribers):
            await q.put(msg)

    # ── Snapshot (for initial SSE load) ──────────────────────────────────────

    async def snapshot(self) -> dict:
        async with self._lock:
            return {
                "pipelines": deepcopy(self._pipelines),
                "activity":  list(self._activity[-60:]),
                "total_rows": self._total_rows,
                "api_calls":  self._api_calls,
            }

    # ── Mutations ─────────────────────────────────────────────────────────────

    async def add_activity(self, type_: str, msg: str) -> None:
        async with self._lock:
            entry = {
                "id":   time.time_ns(),
                "type": type_,
                "msg":  msg,
                "time": datetime.utcnow().isoformat(),
            }
            self._activity.append(entry)
            if len(self._activity) > 100:
                self._activity = self._activity[-80:]
        await self._broadcast("activity", entry)

    async def update_pipeline(self, key: str, updates: dict) -> None:
        async with self._lock:
            if key not in self._pipelines:
                # Auto-register unknown pipeline keys (created dynamically)
                self._pipelines[key] = {
                    "id": key, "name": key, "source": key,
                    "status": "idle", "rows": 0, "last_run": None,
                }
            old_rows = self._pipelines[key].get("rows", 0)
            self._pipelines[key].update(updates)
            if "rows" in updates:
                delta = max(0, updates["rows"] - old_rows)
                self._total_rows += delta
            payload = {"key": key, "pipeline": deepcopy(self._pipelines[key]),
                       "total_rows": self._total_rows}
        await self._broadcast("pipeline", payload)

    async def increment_api_calls(self) -> None:
        async with self._lock:
            self._api_calls += 1
            calls = self._api_calls
        await self._broadcast("api_calls", {"api_calls": calls})

    async def get_pipeline_list(self) -> list[dict]:
        async with self._lock:
            return [
                {
                    "id":     p["id"],
                    "name":   p["name"],
                    "source": p["source"],
                    "status": p["status"],
                    "rows":   p["rows"],
                    "last_run": p.get("last_run"),
                    "staleness_minutes": (
                        int((time.time() - p["_last_run_ts"]) / 60)
                        if p.get("_last_run_ts") else None
                    ),
                }
                for p in self._pipelines.values()
            ]

    def _find_key_by_id_or_source(self, pipeline_id: str) -> str | None:
        for k, p in self._pipelines.items():
            if p["id"] == pipeline_id or p["source"] == pipeline_id:
                return k
        return None

    async def find_key(self, pipeline_id: str) -> str:
        async with self._lock:
            k = self._find_key_by_id_or_source(pipeline_id)
            return k or list(self._pipelines.keys())[0]


# Singleton — imported by both main.py and agent/executor.py
store = PipelineStore()
