"""Databricks `warehouse` optional when the secret carries one.

An agent that can see (via list_platform_secrets / get_platform_secret_fields)
that the Databricks secret has a `warehouse` field must not have to ask the
human for the warehouse ID: create_pipeline accepts it omitted, the guidance
says so, and the docs mark the destination field optional-if-on-secret."""
import json
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATABRICKS_MDX = os.path.join(REPO_ROOT, "docs", "destinations", "databricks.mdx")
UNITY_CATALOG_MDX = os.path.join(REPO_ROOT, "docs", "destinations", "unity-catalog.mdx")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


class _Stub:
    def __init__(self, secret_fields):
        self.secret_fields = secret_fields
        self.posted = None

    def upload_content(self, path, content_b64, filename, data=None):
        return json.dumps({"name": data["pipeline"], "source": {"fileAttributes": {"csvAttributes": {"delimiter": ","}}}})

    def call(self, method, path, timeout=300, **kwargs):
        if method == "get" and path.startswith("/api/v1/secrets/"):
            if self.secret_fields is None:
                return json.dumps({"error": "not found"})
            return json.dumps({"name": path.rsplit("/", 1)[1], "fields": self.secret_fields})
        if method == "post" and path == "/api/v1/pipeline":
            self.posted = kwargs.get("json")
            return "Pipeline saved"
        if method == "get" and path == "/api/v1/pipeline":
            return json.dumps({"name": kwargs.get("params", {}).get("pipeline")})
        return ""


def _create(monkeypatch, secret_fields, **extra):
    stub = _Stub(secret_fields)
    monkeypatch.setattr(server, "_upload_content", stub.upload_content)
    monkeypatch.setattr(server, "_call", stub.call)
    args = {
        "pipeline": "orders",
        "destination": "databricks",
        "database": "datris",
        "credentialsSecret": "databricks",
        "filename": "orders.csv",
        "content_text": "id,amount\n1,10\n2,20\n",
    }
    args.update(extra)
    return json.loads(server._dispatch("create_pipeline", args)), stub


# ------------------------------------------------------------ create_pipeline ---

@pytest.mark.parametrize("field", ["warehouse", "httpPath", "http_path", "DATABRICKS_WAREHOUSE"])
def test_warehouse_omitted_when_secret_has_one_posts_no_warehouse(monkeypatch, field):
    out, stub = _create(monkeypatch, {"host": "********", "token": "********", field: "********"})
    assert "error" not in out, out
    db = stub.posted["destination"]["database"]
    assert db["useDatabricks"] is True
    assert "warehouse" not in db, db


def test_warehouse_omitted_and_secret_has_none_is_rejected_naming_both_fixes(monkeypatch):
    out, stub = _create(monkeypatch, {"host": "********", "token": "********"})
    assert "error" in out, out
    msg = out["error"]
    assert "'warehouse'" in msg and "databricks" in msg
    assert "add a 'warehouse' field to the secret" in msg
    assert stub.posted is None


def test_warehouse_omitted_and_secret_unreadable_defers_to_server(monkeypatch):
    out, stub = _create(monkeypatch, None)
    assert "error" not in out, out
    assert "warehouse" not in stub.posted["destination"]["database"]


def test_explicit_warehouse_still_posted(monkeypatch):
    out, stub = _create(monkeypatch, {"host": "x", "token": "y", "warehouse": "z"}, warehouse="abc123def456")
    assert "error" not in out, out
    assert stub.posted["destination"]["database"]["warehouse"] == "abc123def456"


# ------------------------------------------------------------------ guidance ---

def test_create_pipeline_description_says_warehouse_optional_and_do_not_ask():
    desc = _tool("create_pipeline").description
    assert "unless the Databricks secret has a `warehouse` field" in desc
    assert "do NOT ask the human for it" in desc
    wh = _tool("create_pipeline").inputSchema["properties"]["warehouse"]["description"]
    assert "optional when the Databricks secret has a `warehouse` field" in wh


def test_pipeline_config_reference_says_warehouse_may_be_omitted():
    ref = server.PIPELINE_CONFIG_REFERENCE
    assert "`warehouse` may be omitted when the Databricks secret has a `warehouse` field" in ref
    assert "do NOT ask the human for the warehouse when the secret carries one" in ref
    assert "list_platform_secrets" in ref


# ---------------------------------------------------------------------- docs ---

def test_databricks_docs_field_reference_marks_warehouse_optional_if_on_secret():
    text = _read(DATABRICKS_MDX)
    row = next(line for line in text.splitlines() if line.startswith("| `warehouse`           |"))
    assert "if not on secret" in row, row
    assert "Optional when the platform secret has a `warehouse` field" in row, row


def test_databricks_docs_secret_table_lists_warehouse_field():
    text = _read(DATABRICKS_MDX)
    assert re.search(r"^\| `warehouse`\s+\| no\s+\|.*leaves `warehouse` out", text, re.MULTILINE), "secret table must list the optional warehouse field"


def test_unity_catalog_docs_pipeline_fallback_needs_explicit_warehouse():
    assert "and that sets `warehouse` itself" in _read(UNITY_CATALOG_MDX)
