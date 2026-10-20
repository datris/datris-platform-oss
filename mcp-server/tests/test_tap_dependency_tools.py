"""Story: Tap dependencies: declare, infer, and show the enable order of a catalog
(plans/stories/tap-dependency-order.md) — the MCP-server half, Acceptance
bullets 6 and 9.

Pins: `catalog_enable_order` exists in the tool catalog, is read-only (not in
`_MUTATING_TOOLS`) and dispatches GET /api/v1/catalog/<name>/enable-order;
`create_tap` / `update_tap` offer an optional `depends_on` list and
`update_tap` sends it as `dependsOn`. Also the drift guard: the three
tool-catalog surfaces list the tool and scripts/check-mcp-tool-catalog.py
exits 0.

Modelled on test_catalog_backup_tools.py."""
import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def _tool(name):
    for t in server._all_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _all_tools()")


def _props(tool):
    return tool.input_schema["properties"]


def _path_only(path):
    return urlparse(path).path


@pytest.fixture
def capture(monkeypatch):
    """Records every `_call`; GET /api/v1/tap returns a stored tap."""
    calls = []
    stored = {"name": "consumer", "description": "d", "targetPipeline": "p", "catalog": "sales",
              "enabled": False, "script": "print(1)"}

    def _call(method, path, timeout=300, **kwargs):
        calls.append((method, path, kwargs))
        if method == "get" and _path_only(path) == "/api/v1/tap":
            return json.dumps(stored)
        if method == "post" and _path_only(path) == "/api/v1/tap":
            return json.dumps(kwargs.get("json") or {})
        return json.dumps({"catalog": "sales", "levels": [], "cycles": [], "warnings": []})

    monkeypatch.setattr(server, "_call", _call)
    return calls


# ---------------------------------------------------- catalog_enable_order ---

def test_enable_order_tool_present_and_read_only():
    names = {t.name for t in server._all_tools()}
    assert "catalog_enable_order" in names
    assert "catalog_enable_order" not in server._MUTATING_TOOLS


def test_enable_order_schema_requires_catalog():
    t = _tool("catalog_enable_order")
    assert "catalog" in _props(t)
    assert "catalog" in t.input_schema.get("required", [])


def test_enable_order_dispatches_get(capture):
    body = server._dispatch("catalog_enable_order", {"catalog": "sales"})
    hits = [(m, p) for (m, p, _k) in capture if _path_only(p) == "/api/v1/catalog/sales/enable-order"]
    assert hits, capture
    assert all(m == "get" for m, _p in hits), hits
    assert [m for (m, _p, _k) in capture if m != "get"] == [], "a read-only tool must not write"
    assert "levels" in body


def test_enable_order_quotes_the_name(capture):
    server._dispatch("catalog_enable_order", {"catalog": "a/b"})
    paths = [_path_only(p) for (_m, p, _k) in capture]
    assert "/api/v1/catalog/a%2Fb/enable-order" in paths, paths


# ------------------------------------------------------------- depends_on ---

@pytest.mark.parametrize("tool", ["create_tap", "update_tap"])
def test_depends_on_is_an_optional_string_list(tool):
    t = _tool(tool)
    props = _props(t)
    assert "depends_on" in props, f"{tool} has no depends_on"
    assert props["depends_on"].get("type") == "array"
    assert props["depends_on"].get("items", {}).get("type") == "string"
    assert "depends_on" not in t.input_schema.get("required", [])


def test_update_tap_sends_depends_on_as_dependsOn(capture):
    server._dispatch("update_tap", {"name": "consumer", "depends_on": ["prices", "universe"]})
    posts = [k for (m, p, k) in capture if m == "post" and _path_only(p) == "/api/v1/tap"]
    assert len(posts) == 1, capture
    sent = posts[0].get("json") or {}
    assert sent.get("dependsOn") == ["prices", "universe"], sent
    assert "depends_on" not in sent


def test_update_tap_empty_depends_on_is_sent_as_explicit_none(capture):
    server._dispatch("update_tap", {"name": "consumer", "depends_on": []})
    posts = [k for (m, p, k) in capture if m == "post" and _path_only(p) == "/api/v1/tap"]
    assert len(posts) == 1, capture
    assert (posts[0].get("json") or {}).get("dependsOn") == []


def test_update_tap_without_depends_on_does_not_add_it(capture):
    server._dispatch("update_tap", {"name": "consumer", "enabled": True})
    posts = [k for (m, p, k) in capture if m == "post" and _path_only(p) == "/api/v1/tap"]
    assert len(posts) == 1, capture
    assert "dependsOn" not in (posts[0].get("json") or {})


# ------------------------------------------------------------- drift check ---

def _load_drift_script():
    spec = importlib.util.spec_from_file_location("check_mcp_tool_catalog", ROOT / "scripts" / "check-mcp-tool-catalog.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_tool_catalog_surfaces_list_enable_order_and_drift_check_passes():
    mod = _load_drift_script()
    for surface in (mod.server_tools(), mod.routes_tools(), mod.ui_tools()):
        assert "catalog_enable_order" in surface
    r = subprocess.run([sys.executable, str(ROOT / "scripts" / "check-mcp-tool-catalog.py")], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


# ================================================================ Story 2 ===
# Story: Enable a catalog's taps in dependency order: server-orchestrated job,
# progress, cancel (plans/stories/tap-dependency-order-enable.md), Acceptance
# bullet 6 (+ the drift half of bullet 8).
#
# Pins: `enable_catalog_ordered(catalog)` is mutating (in `_MUTATING_TOOLS`,
# offers `reason`, carries the approval note), posts to
# /api/v1/catalog/<name>/enable-ordered with NO JSON body (the empty body is
# what PolicyGate queues and replays), and returns the 202 body verbatim. Its
# description sends the agent to `catalog_enable_order` (with `wait_seconds`)
# for progress and tells it not to call `run_tap` on those taps meanwhile.
# Progress stays on the read tool (GET .../enable-order, which gains a `job`
# block); cancel is REST + UI only, so no MCP tool issues DELETE
# .../enable-ordered.

_JOB_202 = {"jobId": "job-1", "catalog": "sales",
            "plan": {"levels": [{"level": 1, "taps": [{"name": "prices"}]},
                                {"level": 2, "taps": [{"name": "signals"}]}], "cycles": []}}


@pytest.fixture
def capture_job(monkeypatch):
    """Records every `_call`; POST .../enable-ordered answers the 202 body."""
    calls = []

    def _call(method, path, timeout=300, **kwargs):
        calls.append((method, path, kwargs))
        if method == "post" and _path_only(path).endswith("/enable-ordered"):
            return json.dumps(_JOB_202)
        if method == "get" and _path_only(path).endswith("/enable-order"):
            return json.dumps({"catalog": "sales", "levels": [], "cycles": [], "warnings": [],
                               "job": {"active": True, "jobId": "job-1", "taps": []}})
        return json.dumps({})

    monkeypatch.setattr(server, "_call", _call)
    return calls


def test_enable_catalog_ordered_present_mutating_with_reason():
    names = {t.name for t in server._all_tools()}
    assert "enable_catalog_ordered" in names
    assert "enable_catalog_ordered" in server._MUTATING_TOOLS
    t = _tool("enable_catalog_ordered")
    assert "reason" in _props(t)
    assert "pending_approval" in (t.description or ""), "mutating tools carry the approval note"


def test_enable_catalog_ordered_schema_requires_only_catalog():
    t = _tool("enable_catalog_ordered")
    assert "catalog" in _props(t)
    assert t.input_schema.get("required", []) == ["catalog"]


def test_enable_catalog_ordered_description_points_to_progress_and_forbids_run_tap():
    d = _tool("enable_catalog_ordered").description or ""
    assert "catalog_enable_order" in d
    assert "wait_seconds" in d
    assert "run_tap" in d


def test_enable_catalog_ordered_posts_with_no_json_body(capture_job):
    out = server._dispatch("enable_catalog_ordered", {"catalog": "sales"})
    posts = [(p, k) for (m, p, k) in capture_job if m == "post"]
    assert [_path_only(p) for p, _k in posts] == ["/api/v1/catalog/sales/enable-ordered"], capture_job
    kwargs = posts[0][1]
    assert kwargs.get("json") is None, f"no JSON body expected, got {kwargs.get('json')!r}"
    assert not kwargs.get("data"), f"no body expected, got {kwargs.get('data')!r}"
    assert [m for (m, _p, _k) in capture_job if m not in ("post", "get")] == [], "never cancels or deletes"
    assert json.loads(out) == _JOB_202, "the 202 body is returned verbatim"


def test_enable_catalog_ordered_quotes_the_name(capture_job):
    server._dispatch("enable_catalog_ordered", {"catalog": "a/b"})
    paths = [_path_only(p) for (m, p, _k) in capture_job if m == "post"]
    assert paths == ["/api/v1/catalog/a%2Fb/enable-ordered"], paths


def test_progress_is_read_through_catalog_enable_order_and_there_is_no_cancel_tool(capture_job):
    # Progress: the existing read tool, a GET (never a POST that would start a job).
    server._dispatch("catalog_enable_order", {"catalog": "sales"})
    assert [(m, _path_only(p)) for (m, p, _k) in capture_job] == [("get", "/api/v1/catalog/sales/enable-order")]
    names = {t.name for t in server._all_tools()}
    assert "enable_catalog_ordered" in names
    # Out of scope: an MCP cancel tool.
    assert not [n for n in names if "enable" in n and ("cancel" in n or "stop" in n)], names


def test_tool_catalog_surfaces_list_enable_catalog_ordered_and_drift_check_passes():
    mod = _load_drift_script()
    for surface in (mod.server_tools(), mod.routes_tools(), mod.ui_tools()):
        assert "enable_catalog_ordered" in surface
    r = subprocess.run([sys.executable, str(ROOT / "scripts" / "check-mcp-tool-catalog.py")], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr
