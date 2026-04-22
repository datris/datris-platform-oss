"""
agent/tap_provisioning.py

One-shot, idempotent startup routine that ensures the 4 market-intelligence
taps exist on the Datris platform. Called from main.py's lifespan after the
MCP client connects.

Steps per tap:
  1. Push secrets (FRED_API_KEY today) into Datris via `create_tap_secret`
     — the agent reads them from its local .env and hands them off to the
     platform. Skip on "already exists" collision.
  2. Check `list_taps` — if the tap is already provisioned, done.
  3. Ensure the target pipeline exists (the 4 pipelines match the original
     example's seed so `query_postgres` still works downstream).
  4. `create_tap` with the BYO script, pipeline, optional secret, disabled.

Best-effort: if a single tap fails, logs and continues — the agent remains
useful for whatever did provision.
"""

import base64
import logging
import os

from agent.mcp_client import call_tool as mcp_call, is_connected
from agent.pipeline_store import store
from agent.tap_definitions import TAPS

log = logging.getLogger("datris.taps")


async def provision_taps() -> None:
    """Idempotently provision the 4 market-intelligence taps on the platform."""
    if not is_connected():
        log.warning("MCP not connected — skipping tap provisioning")
        return

    existing = await _existing_tap_names()

    for tap in TAPS:
        try:
            await _provision_one(tap, existing)
        except Exception as e:
            log.error("Tap provisioning failed for %s: %s", tap["name"], e)
            await store.add_activity("error", f"Tap setup failed: {tap['name']} ({e})")


async def _existing_tap_names() -> set[str]:
    result = await mcp_call("list_taps", {})
    if not isinstance(result, dict):
        return set()
    taps = result.get("taps") or result.get("result") or []
    if isinstance(result, list):
        taps = result
    names: set[str] = set()
    for t in taps:
        if isinstance(t, dict):
            name = t.get("name") or t.get("tap")
            if name:
                names.add(name)
    return names


async def _provision_one(tap: dict, existing: set[str]) -> None:
    name = tap["name"]
    pipeline = tap["pipeline"]

    # 1. Secret
    if tap["secret_fields"]:
        await _ensure_secret(tap)

    # 2. Skip if the tap is already on the platform
    if name in existing:
        await _register_pipeline_locally(pipeline)
        log.info("Tap %s already exists — skipping", name)
        return

    # 3. Pipeline — create it on the platform if missing (required before create_tap
    #    can bind to it; target_pipeline is a reference only, not auto-created).
    await _ensure_pipeline(tap)
    await _register_pipeline_locally(pipeline)

    # 4. Create tap
    args: dict = {
        "name": name,
        "script": tap["script"],
        "target_pipeline": pipeline,
        "tap_type": "structured",
    }
    if tap.get("description"):
        args["description"] = tap["description"]
    if tap["secret_fields"] and tap.get("secret_name"):
        args["secret_name"] = tap["secret_name"]
    if tap.get("packages"):
        args["packages"] = tap["packages"]

    result = await mcp_call("create_tap", args)
    if isinstance(result, dict) and result.get("error"):
        raise RuntimeError(str(result["error"]))

    await store.add_activity("create", f"Tap provisioned: {name} → {pipeline}")
    log.info("Created tap %s (pipeline=%s)", name, pipeline)


async def _ensure_pipeline(tap: dict) -> None:
    """Create the target pipeline on the platform if it doesn't already exist."""
    pipeline = tap["pipeline"]

    check = await mcp_call("get_pipeline", {"pipeline": pipeline})
    if isinstance(check, dict) and not check.get("error") and check.get("name"):
        log.info("Pipeline %s already exists — reusing", pipeline)
        return

    sample = tap.get("sample_csv")
    if not sample:
        raise RuntimeError(f"Tap {tap['name']} has no sample_csv; cannot create pipeline")

    content_b64 = base64.b64encode(sample.encode()).decode()
    result = await mcp_call("create_pipeline", {
        "content": content_b64,
        "filename": f"{pipeline}.csv",
        "pipeline": pipeline,
        "destination": "postgres",
    })
    if isinstance(result, dict) and result.get("error"):
        raise RuntimeError(f"create_pipeline failed for {pipeline}: {result['error']}")
    await store.add_activity("create", f"Pipeline provisioned: {pipeline}")
    log.info("Created pipeline %s", pipeline)


async def _ensure_secret(tap: dict) -> None:
    """Push any available env values into Datris as a tap secret. Skip on collision."""
    fields: dict[str, str] = {}
    missing: list[str] = []
    for key in tap["secret_fields"]:
        val = os.environ.get(key)
        if val:
            fields[key] = val
        else:
            missing.append(key)

    if not fields:
        if missing:
            log.warning(
                "Tap %s expects env vars %s but none are set — tap will run with empty creds",
                tap["name"], missing,
            )
        return

    result = await mcp_call("create_tap_secret", {
        "name": tap["secret_name"],
        "fields": fields,
    })

    if isinstance(result, dict) and result.get("error"):
        err = str(result["error"])
        # Treat "already exists" as a no-op — avoid silently overwriting a
        # secret the user may have tuned in the UI.
        if "exists" in err.lower() or "already" in err.lower():
            log.info("Tap secret %s already present — reusing", tap["secret_name"])
            return
        raise RuntimeError(err)


async def _register_pipeline_locally(pipeline: str) -> None:
    """Seed the local pipeline_store so the UI shows the tile before first run."""
    key = pipeline.lower().replace(" ", "_")
    snap = await store.snapshot()
    if key in snap["pipelines"]:
        return
    await store.update_pipeline(key, {
        "id": pipeline,
        "name": pipeline,
        "source": pipeline,
        "status": "created",
        "rows": 0,
        "last_run": None,
    })
