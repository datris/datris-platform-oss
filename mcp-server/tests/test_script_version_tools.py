"""Story: Version history for stored CodeGen scripts
(plans/stories/self-healing-5a-script-versions.md) — the MCP-server half,
Acceptance bullet 5.

Pins: four tools in `_base_tools()` — `list_script_versions(name, kind)`,
`get_script_version(name, kind, version)`, `diff_script_versions(name, kind,
version, against)`, `restore_script_version(name, kind, version)` — with
`kind` an enum of dataQuality / transformation; dispatch to
/api/v1/pipelines/<name>/codegen-scripts/<kind>/versions[/<version>[/diff?against=N | /restore]];
`restore_script_version` is mutating (gets `reason` + the approval note) and
echoes the environment; descriptions name no proper-noun source; the three
tool-catalog surfaces list all four and scripts/check-mcp-tool-catalog.py
exits 0.

Modelled on test_incident_evidence_tools.py (`_tool` helper, stubbed `_call`)."""
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlencode

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]

TOOLS = ["list_script_versions", "get_script_version", "diff_script_versions", "restore_script_version"]


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


class _Captured:
    def __init__(self):
        self.calls = []

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        return json.dumps({"version": 3, "origin": "restore-version"})


@pytest.fixture
def captured(monkeypatch):
    c = _Captured()
    monkeypatch.setattr(server, "_call", c.call)
    return c


def _full(path, kwargs):
    """The request path including any query string, whether the handler
    built it into the path or passed `params`."""
    params = kwargs.get("params")
    return path + ("?" + urlencode(params) if params else "")


# ------------------------------------------------------------- schemas ---

@pytest.mark.parametrize("name", TOOLS)
def test_tool_in_base_tools_with_kind_enum(name):
    t = _tool(name)
    props = t.inputSchema["properties"]
    assert props["name"]["type"] == "string"
    assert props["kind"]["type"] == "string"
    assert props["kind"]["enum"] == ["dataQuality", "transformation"]


@pytest.mark.parametrize(
    "name,required",
    [
        ("list_script_versions", {"name", "kind"}),
        ("get_script_version", {"name", "kind", "version"}),
        ("diff_script_versions", {"name", "kind", "version", "against"}),
        ("restore_script_version", {"name", "kind", "version"}),
    ],
)
def test_required_arguments(name, required):
    t = _tool(name)
    assert set(t.inputSchema.get("required", [])) == required
    props = t.inputSchema["properties"]
    for arg in required - {"name", "kind"}:
        assert props[arg]["type"] == "integer", (arg, props[arg])


# ------------------------------------------------------------ dispatch ---

BASE = "/api/v1/pipelines/orders/codegen-scripts/transformation/versions"


@pytest.mark.parametrize(
    "name,args,expected",
    [
        ("list_script_versions", {"name": "orders", "kind": "transformation"}, ("get", BASE)),
        ("get_script_version", {"name": "orders", "kind": "transformation", "version": 2}, ("get", BASE + "/2")),
        (
            "diff_script_versions",
            {"name": "orders", "kind": "transformation", "version": "2", "against": 1},
            ("get", BASE + "/2/diff?against=1"),
        ),
        ("restore_script_version", {"name": "orders", "kind": "transformation", "version": 1}, ("post", BASE + "/1/restore")),
    ],
)
def test_dispatch_paths(captured, name, args, expected):
    server._dispatch(name, args)
    assert len(captured.calls) == 1, captured.calls
    method, path, kwargs = captured.calls[0]
    assert (method, _full(path, kwargs)) == expected


def test_dispatch_uses_the_data_quality_kind(captured):
    server._dispatch("list_script_versions", {"name": "orders", "kind": "dataQuality"})
    assert [(m, p) for (m, p, _) in captured.calls] == [("get", "/api/v1/pipelines/orders/codegen-scripts/dataQuality/versions")]


def test_pipeline_name_is_quoted(captured):
    server._dispatch("list_script_versions", {"name": "my orders", "kind": "transformation"})
    _, path, _ = captured.calls[0]
    assert path.startswith("/api/v1/pipelines/my%20orders/codegen-scripts/transformation/versions"), path


# ------------------------------------------------------------ mutating ---

def test_restore_script_version_is_mutating_and_echoes_the_environment():
    assert "restore_script_version" in server._MUTATING_TOOLS
    assert "restore_script_version" in server._ECHO_ENVIRONMENT_TOOLS
    for read in ("list_script_versions", "get_script_version", "diff_script_versions"):
        assert read not in server._MUTATING_TOOLS, read
        assert read not in server._ECHO_ENVIRONMENT_TOOLS, read
    decorated = {t.name: t for t in server._all_tools()}["restore_script_version"]
    assert "reason" in decorated.inputSchema["properties"]
    assert "pending_approval" in decorated.description


# --------------------------------------------------------- descriptions ---

def test_descriptions_say_what_the_script_is_and_how_restore_behaves():
    listed = _tool("list_script_versions").description
    assert "AI data-quality rule or AI transformation" in listed, listed
    restore = _tool("restore_script_version").description
    assert "NEW latest version" in restore, restore
    assert "refused" in restore.lower(), restore
    assert "instruction or source schema" in restore, restore


_PROPER_NOUNS = re.compile(
    r"\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|"
    r"nasdaq|bloomberg|weather|crypto|bitcoin|stock|claude|openai|anthropic|polygon|yahoo|"
    r"alpha\s*vantage|google|slack|jira|hubspot|zendesk)\b",
    re.IGNORECASE,
)


@pytest.mark.parametrize("name", TOOLS)
def test_descriptions_name_no_proper_noun_source(name):
    t = _tool(name)
    texts = [t.description] + [p.get("description", "") for p in t.inputSchema.get("properties", {}).values()]
    hits = [h for txt in texts for h in _PROPER_NOUNS.findall(txt)]
    assert hits == [], hits


# ------------------------------------------------------- catalog drift ---

def _load_drift_script():
    spec = importlib.util.spec_from_file_location("check_mcp_tool_catalog", ROOT / "scripts" / "check-mcp-tool-catalog.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_tool_catalog_surfaces_list_the_four_tools_and_drift_check_passes():
    mod = _load_drift_script()
    for surface in (mod.server_tools(), mod.routes_tools(), mod.ui_tools()):
        for name in TOOLS:
            assert name in surface, name
    r = subprocess.run([sys.executable, str(ROOT / "scripts" / "check-mcp-tool-catalog.py")], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


def test_ui_service_dispatches_the_four_tools():
    svc = (ROOT / "ui" / "src" / "app" / "mcp.service.ts").read_text()
    for name in TOOLS:
        assert f"case '{name}'" in svc, name
