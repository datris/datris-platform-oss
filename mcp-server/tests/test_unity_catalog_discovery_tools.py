"""Story: Unity Catalog 2: discovery (browse + find_data federation)
(plans/stories/unity-catalog-2-discovery.md).

Pins the MCP side: a new `browse_unity_catalog` tool (secret required;
warehouse/catalog/schema/table forwarded as GET params to
/api/v1/unity-catalog/browse), a neutral description that sends the agent to
list_platform_secrets and warns about warehouse start-up time, `find_data`
gaining `include_unity_catalog` (sent as includeUnityCatalog=true only when
truthy, so older servers and default callers see byte-identical requests),
and a docs sweep for the new unity-catalog.mdx page."""
import json
import os
import re
import subprocess
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
UC_MDX = os.path.join(DOCS, "destinations", "unity-catalog.mdx")
DOCS_JSON = os.path.join(DOCS, "docs.json")
DATA_CATALOG_MDX = os.path.join(DOCS, "data-catalog.mdx")


# ---------------------------------------------------------------- helpers ---

def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


def _props(tool):
    return tool.inputSchema["properties"]


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


class _Captured:
    """Stub the HTTP seam so dispatch runs without a server and we can see
    exactly which request it would make."""

    def __init__(self):
        self.calls = []

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        return json.dumps({"ok": True})


@pytest.fixture
def captured(monkeypatch):
    c = _Captured()
    monkeypatch.setattr(server, "_call", c.call)
    return c


# ------------------------------------------------- browse_unity_catalog ---

def test_browse_unity_catalog_schema_requires_secret_and_offers_optional_filters():
    t = _tool("browse_unity_catalog")
    props = _props(t)
    for arg in ("secret", "warehouse", "catalog", "schema", "table"):
        assert arg in props, sorted(props)
        assert props[arg]["type"] == "string", (arg, props[arg])
    assert t.inputSchema.get("required", []) == ["secret"], t.inputSchema.get("required")


def test_browse_unity_catalog_requires_secret_and_forwards_warehouse_catalog_schema_table_as_get_params(captured):
    # Missing / blank secret: refused locally, no HTTP call.
    for args in ({}, {"secret": ""}, {"secret": "   ", "catalog": "main"}):
        out = json.loads(server._dispatch("browse_unity_catalog", args))
        assert "error" in out and "secret" in out["error"].lower(), out
    assert captured.calls == [], captured.calls

    server._dispatch("browse_unity_catalog", {
        "secret": "dbx-sp",
        "warehouse": "abc123",
        "catalog": "main",
        "schema": "sales",
        "table": "orders",
    })
    assert len(captured.calls) == 1, captured.calls
    method, path, kwargs = captured.calls[0]
    assert method == "get"
    assert path == "/api/v1/unity-catalog/browse"
    assert kwargs.get("params") == {
        "secret": "dbx-sp",
        "warehouse": "abc123",
        "catalog": "main",
        "schema": "sales",
        "table": "orders",
    }, kwargs


def test_browse_unity_catalog_omits_absent_optional_params(captured):
    server._dispatch("browse_unity_catalog", {"secret": "dbx-sp"})
    method, path, kwargs = captured.calls[-1]
    assert (method, path) == ("get", "/api/v1/unity-catalog/browse")
    assert kwargs.get("params") == {"secret": "dbx-sp"}, kwargs


# Same list as test_catalog_tools.py minus databricks: the tool is about
# Databricks Unity Catalog, so that name is allowed; nothing else is.
_PROPER_NOUNS = re.compile(
    r"\b(snowflake|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|"
    r"nasdaq|bloomberg|weather|crypto|bitcoin|stock|claude|openai|anthropic|aws|azure|gcp)\b",
    re.IGNORECASE,
)


def test_browse_description_mentions_list_platform_secrets_and_warehouse_startup_and_no_proper_nouns():
    t = _tool("browse_unity_catalog")
    texts = [t.description] + [p.get("description", "") for p in _props(t).values()]
    d = t.description
    low = d.lower()
    assert "list_platform_secrets" in d, d
    assert "warehouse" in low, d
    # Start-up latency: a stopped warehouse auto-starts on first call (seconds).
    assert "start" in low, d
    assert re.search(r"\d+\s*(?:[-–]|to)\s*\d+\s*s|second", low), d
    assert "datris_pipeline" in d, d
    for text in texts:
        assert _PROPER_NOUNS.findall(text) == [], (_PROPER_NOUNS.findall(text), text)


# ------------------------------------------------ find_data federation ---

def test_find_data_schema_has_optional_boolean_include_unity_catalog():
    t = _tool("find_data")
    props = _props(t)
    assert "include_unity_catalog" in props, sorted(props)
    assert props["include_unity_catalog"]["type"] == "boolean"
    assert "include_unity_catalog" not in t.inputSchema.get("required", [])


def test_find_data_include_unity_catalog_true_sends_include_unity_catalog_true(captured):
    server._dispatch("find_data", {"query": "orders", "include_unity_catalog": True})
    method, path, kwargs = captured.calls[-1]
    assert (method, path) == ("get", "/api/v1/catalog/find")
    assert kwargs["params"].get("includeUnityCatalog") == "true", kwargs


def test_find_data_without_include_unity_catalog_sends_no_such_param(captured):
    server._dispatch("find_data", {"query": "orders"})
    server._dispatch("find_data", {"query": "orders", "include_unity_catalog": False})
    for method, path, kwargs in captured.calls:
        assert (method, path) == ("get", "/api/v1/catalog/find")
        assert "includeUnityCatalog" not in kwargs["params"], kwargs
        assert kwargs["params"] == {"query": "orders"}, kwargs


# -------------------------------------------------------------- docs sweep ---

def test_unity_catalog_docs_page_exists():
    assert os.path.isfile(UC_MDX), UC_MDX
    text = _read(UC_MDX)
    assert "browse_unity_catalog" in text
    assert "includeUnityCatalog" in text or "include_unity_catalog" in text


def test_docs_json_lists_unity_catalog_page_after_databricks():
    text = _read(DOCS_JSON)
    assert '"destinations/unity-catalog"' in text
    assert text.index('"destinations/databricks"') < text.index('"destinations/unity-catalog"')


def test_data_catalog_page_mentions_unity_catalog():
    text = _read(DATA_CATALOG_MDX)
    assert "Unity Catalog" in text
    assert "/destinations/unity-catalog" in text


def test_no_markdown_files_under_docs():
    tracked = subprocess.run(
        ["git", "ls-files", "docs"], cwd=REPO_ROOT, capture_output=True, text=True, check=True
    ).stdout.split()
    md = [p for p in tracked if p.lower().endswith(".md")]
    for root, dirs, files in os.walk(DOCS):
        dirs[:] = [d for d in dirs if d not in ("node_modules", ".git", "config")]
        md += [os.path.relpath(os.path.join(root, f), REPO_ROOT) for f in files if f.lower().endswith(".md")]
    assert md == [], md
