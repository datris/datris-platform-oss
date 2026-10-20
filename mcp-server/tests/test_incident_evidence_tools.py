"""Story: Incident evidence model: profiler, baseline, blast radius, read surfaces
(plans/stories/self-healing-1-evidence-model.md) — the MCP-server half,
Acceptance bullets 7 and 8.

Pins: `get_incident_evidence` is in `_base_tools()` with `incident_id`
required and dispatches GET /api/v1/incidents/<id>/evidence; a missing id
returns {"error": ...} without an HTTP call; `get_incident` names the
evidence; `list_incidents` mentions `verdict`; no incident tool description
names a proper-noun source; the three tool-catalog surfaces list the tool and
scripts/check-mcp-tool-catalog.py exits 0.

Modelled on test_scratch_tools.py (`_tool` helper, stubbed `_call`)."""
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


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
        return json.dumps({"verdict": "pending"})


@pytest.fixture
def captured(monkeypatch):
    c = _Captured()
    monkeypatch.setattr(server, "_call", c.call)
    return c


# ------------------------------------------------------ Acceptance bullet 7 ---

def test_get_incident_evidence_in_base_tools_with_incident_id_required():
    t = _tool("get_incident_evidence")
    props = t.input_schema["properties"]
    assert "incident_id" in props
    assert props["incident_id"]["type"] == "string"
    assert t.input_schema.get("required") == ["incident_id"]


def test_get_incident_evidence_dispatches_get_evidence(captured):
    out = server._dispatch("get_incident_evidence", {"incident_id": "inc_x"})
    assert len(captured.calls) == 1, captured.calls
    method, path, _ = captured.calls[0]
    assert (method, path) == ("get", "/api/v1/incidents/inc_x/evidence")
    assert json.loads(out) == {"verdict": "pending"}


@pytest.mark.parametrize("args", [{}, {"incident_id": ""}, {"incident_id": "   "}])
def test_get_incident_evidence_missing_id_errors_without_http(captured, args):
    out = json.loads(server._dispatch("get_incident_evidence", args))
    assert "error" in out, out
    assert "incident_id" in out["error"], out  # not "Unknown tool"
    assert captured.calls == []


@pytest.mark.parametrize(
    "tool,suffix",
    [("get_incident_evidence", "/evidence"), ("get_incident", "")],
)
def test_incident_id_is_path_encoded(captured, tool, suffix):
    server._dispatch(tool, {"incident_id": "inc_x?x=1/../../policy"})
    assert [(m, p) for (m, p, _) in captured.calls] == [
        ("get", "/api/v1/incidents/inc_x%3Fx%3D1%2F..%2F..%2Fpolicy" + suffix)
    ]


def test_get_incident_still_dispatches_the_incident_route(captured):
    server._dispatch("get_incident", {"incident_id": "inc_x"})
    assert [(m, p) for (m, p, _) in captured.calls] == [("get", "/api/v1/incidents/inc_x")]


def test_get_incident_description_names_evidence():
    d = _tool("get_incident").description.lower()
    assert "evidence" in d, d
    for word in ("blast radius", "verdict", "threshold"):
        assert word in d, (word, d)


def test_get_incident_evidence_description_names_its_parts():
    d = _tool("get_incident_evidence").description.lower()
    for word in ("evidence", "blast radius", "threshold", "verdict"):
        assert word in d, (word, d)


def test_list_incidents_description_mentions_verdict():
    assert "verdict" in _tool("list_incidents").description


_PROPER_NOUNS = re.compile(
    r"\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|"
    r"nasdaq|bloomberg|weather|crypto|bitcoin|stock|claude|openai|anthropic|polygon|yahoo|"
    r"alpha\s*vantage|google|slack|jira|hubspot|zendesk)\b",
    re.IGNORECASE,
)


@pytest.mark.parametrize("name", ["get_incident_evidence", "get_incident", "list_incidents"])
def test_incident_tool_descriptions_name_no_proper_noun_source(name):
    t = _tool(name)
    texts = [t.description] + [p.get("description", "") for p in t.input_schema.get("properties", {}).values()]
    hits = [h for txt in texts for h in _PROPER_NOUNS.findall(txt)]
    assert hits == [], hits


# ------------------------------------------------------ Acceptance bullet 8 ---

def _load_drift_script():
    spec = importlib.util.spec_from_file_location("check_mcp_tool_catalog", ROOT / "scripts" / "check-mcp-tool-catalog.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_tool_catalog_surfaces_list_get_incident_evidence_and_drift_check_passes():
    mod = _load_drift_script()
    for surface in (mod.server_tools(), mod.routes_tools(), mod.ui_tools()):
        assert "get_incident_evidence" in surface
    r = subprocess.run([sys.executable, str(ROOT / "scripts" / "check-mcp-tool-catalog.py")], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


def test_ui_service_dispatches_get_incident_evidence():
    svc = (ROOT / "ui" / "src" / "app" / "mcp.service.ts").read_text()
    assert "case 'get_incident_evidence'" in svc


# ---- Story self-healing-6 (plans/stories/self-healing-6-env-defaults-learn-notes.md):
# the incident webhook now carries `evidenceSummary`; get_incident says so.

def test_get_incident_description_says_the_evidence_summary_is_what_the_webhook_sends():
    d = _tool("get_incident").description.lower()
    assert "evidence summary" in d, d
    assert "webhook" in d, d
