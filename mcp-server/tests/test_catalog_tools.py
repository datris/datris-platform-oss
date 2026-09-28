"""Story: Catalog rename and delete: server endpoints and MCP tools
(plans/stories/catalog-ops-server-mcp.md) — the MCP-server half.

Pins: `rename_catalog` and `delete_catalog` exist in the tool catalog with the
optional `reason` argument (they are mutating tools), `rename_catalog`
dispatches PUT /api/v1/catalog/<name> with {"newName": ...}, and
`delete_catalog` dispatches DELETE /api/v1/catalog/<name> with mode=detach and
never cascade — cascade is UI-only. The server body is returned verbatim so a
207 partial failure reaches the agent.

Modelled on test_scratch_tools.py: `server._all_tools()` plus a stubbed `_call`."""
import json
import os
import re
import sys
from urllib.parse import parse_qs, urlparse

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tool(name):
    for t in server._all_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _all_tools()")


def _props(tool):
    return tool.inputSchema["properties"]


class _Captured:
    def __init__(self, body=""):
        self.calls = []
        self.body = body

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        return self.body


@pytest.fixture
def captured(monkeypatch):
    c = _Captured(body=json.dumps({"ok": True}))
    monkeypatch.setattr(server, "_call", c.call)
    return c


def _mode_of(path, kwargs):
    """mode may travel as a query param or in the path's query string."""
    params = kwargs.get("params") or {}
    modes = []
    if "mode" in params:
        modes.append(str(params["mode"]))
    q = parse_qs(urlparse(path).query)
    modes.extend(q.get("mode", []))
    return modes


def _path_only(path):
    return urlparse(path).path


# ------------------------------------------------ tools present, mutating ---

def test_both_tools_present_in_base_tools():
    names = {t.name for t in server._base_tools()}
    assert "rename_catalog" in names
    assert "delete_catalog" in names


def test_both_tools_are_mutating_and_offer_reason():
    assert "rename_catalog" in server._MUTATING_TOOLS
    assert "delete_catalog" in server._MUTATING_TOOLS
    for name in ("rename_catalog", "delete_catalog"):
        assert "reason" in _props(_tool(name)), name


def test_rename_catalog_schema():
    t = _tool("rename_catalog")
    props = _props(t)
    assert "catalog" in props and "new_name" in props
    assert set(t.inputSchema.get("required", [])) >= {"catalog", "new_name"}


def test_delete_catalog_schema_has_no_mode_argument():
    t = _tool("delete_catalog")
    props = _props(t)
    assert "catalog" in props
    assert "mode" not in props, "cascade is UI-only; the MCP tool must not expose a mode"
    assert "confirm" not in props
    assert "catalog" in t.inputSchema.get("required", [])


_PROPER_NOUNS = re.compile(
    r"\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|"
    r"nasdaq|bloomberg|weather|crypto|bitcoin|stock|claude|openai|anthropic)\b",
    re.IGNORECASE,
)


@pytest.mark.parametrize("name", ["rename_catalog", "delete_catalog"])
def test_descriptions_user_authorised_mention_keys_no_proper_nouns(name):
    d = _tool(name).description
    low = d.lower()
    assert "explicitly asked" in low or "user has asked" in low or "only when the user" in low \
        or "user-authori" in low or "user authori" in low, d
    assert _PROPER_NOUNS.findall(d) == [], _PROPER_NOUNS.findall(d)
    if name == "rename_catalog":
        assert "key" in low, "rename_catalog must mention affected API keys"
        assert "uncataloged" in low


# ---------------------------------------------------------- rename dispatch ---

def test_rename_catalog_dispatches_put_with_new_name(captured):
    server._dispatch("rename_catalog", {"catalog": "sales_old", "new_name": "sales_new"})
    assert len(captured.calls) == 1, captured.calls
    method, path, kwargs = captured.calls[0]
    assert method == "put"
    assert _path_only(path) == "/api/v1/catalog/sales_old"
    assert kwargs.get("json") == {"newName": "sales_new"}


def test_rename_catalog_returns_server_body_verbatim_including_partial_failure(monkeypatch):
    body = json.dumps({
        "renamed": ["a"], "failed": [{"name": "b", "error": "boom"}],
        "affectedKeys": ["reader"], "placeholder": "created",
    })
    c = _Captured(body=body)
    monkeypatch.setattr(server, "_call", c.call)
    out = server._dispatch("rename_catalog", {"catalog": "old", "new_name": "new"})
    assert out == body


# ---------------------------------------------------------- delete dispatch ---

def test_delete_catalog_dispatches_delete_with_detach(captured):
    server._dispatch("delete_catalog", {"catalog": "sales_old"})
    assert len(captured.calls) == 1, captured.calls
    method, path, kwargs = captured.calls[0]
    assert method == "delete"
    assert _path_only(path) == "/api/v1/catalog/sales_old"
    assert _mode_of(path, kwargs) == ["detach"]
    assert "cascade" not in json.dumps([path, kwargs.get("params"), kwargs.get("json")], default=str)


def test_delete_catalog_never_cascades_even_if_asked(captured):
    server._dispatch("delete_catalog", {"catalog": "sales_old", "mode": "cascade", "confirm": "sales_old"})
    for method, path, kwargs in captured.calls:
        assert "cascade" not in json.dumps([path, kwargs.get("params"), kwargs.get("json")], default=str)
        assert "confirm" not in (kwargs.get("params") or {})
    assert captured.calls, "delete_catalog should still detach"
    method, path, kwargs = captured.calls[0]
    assert method == "delete"
    assert _mode_of(path, kwargs) == ["detach"]


def test_delete_catalog_returns_server_body_verbatim(monkeypatch):
    body = json.dumps({"detached": ["a"], "failed": [{"name": "b", "error": "denied"}]})
    c = _Captured(body=body)
    monkeypatch.setattr(server, "_call", c.call)
    assert server._dispatch("delete_catalog", {"catalog": "old"}) == body
