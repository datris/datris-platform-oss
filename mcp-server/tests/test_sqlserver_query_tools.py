"""Story: SQL Server read-back and on-demand typing
(plans/stories/mssql-query-and-typing.md).

Pins the MCP side: a pipeline-scoped `query_sqlserver` tool next to
`query_snowflake` that posts to /api/v1/query/sqlserver (Acceptance bullet 4:
omitting `sql` sends no `sql` so the server previews the pipeline's table;
a non-SELECT reaches the server, whose error names the SELECT/WITH allowlist
and is passed through), the instruction text naming it, and the
get_dest_types / apply_dest_types descriptions naming sqlserver."""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


class _Stub:
    def __init__(self, response):
        self.response = response
        self.calls = []

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs.get("json")))
        return self.response


# --------------------------------------------------------------- the tool ---

def test_query_sqlserver_is_registered_and_pipeline_scoped():
    tool = _tool("query_sqlserver")
    props = tool.input_schema["properties"]
    assert set(["pipeline", "sql", "limit"]).issubset(props), props
    assert tool.input_schema.get("required") == ["pipeline"]
    desc = tool.description
    assert "SQL Server" in desc
    assert "useSqlServer" in desc
    assert "SELECT" in desc and "WITH" in desc
    assert "INFORMATION_SCHEMA" in desc.upper()


def test_query_sqlserver_sits_after_query_snowflake():
    names = [t.name for t in server._base_tools()]
    assert names.index("query_sqlserver") > names.index("query_snowflake")


def test_query_sqlserver_without_sql_previews_the_pipeline_table(monkeypatch):
    rows = {"pipeline": "orders_mssql", "sql": "SELECT TOP (5) * FROM (SELECT * FROM dbo.orders) q",
            "results": [{"id": 1}, {"id": 2}], "count": 2}
    stub = _Stub(json.dumps(rows))
    monkeypatch.setattr(server, "_call", stub.call)
    out = server._dispatch("query_sqlserver", {"pipeline": "orders_mssql", "limit": 5})
    assert stub.calls == [("post", "/api/v1/query/sqlserver", {"pipeline": "orders_mssql", "limit": 5})]
    assert json.loads(out)["results"] == rows["results"]


def test_query_sqlserver_forwards_sql(monkeypatch):
    stub = _Stub(json.dumps({"results": [], "count": 0}))
    monkeypatch.setattr(server, "_call", stub.call)
    server._dispatch("query_sqlserver", {"pipeline": "orders_mssql", "sql": "SELECT id FROM dbo.orders ORDER BY id"})
    method, path, body = stub.calls[0]
    assert (method, path) == ("post", "/api/v1/query/sqlserver")
    assert body["sql"] == "SELECT id FROM dbo.orders ORDER BY id"
    assert body["pipeline"] == "orders_mssql"


def test_query_sqlserver_non_select_returns_the_allowlist_error(monkeypatch):
    err = {"error": "Only read-only statements are allowed: SELECT (WITH/CTE)"}
    stub = _Stub(json.dumps(err))
    monkeypatch.setattr(server, "_call", stub.call)
    out = server._dispatch("query_sqlserver", {"pipeline": "orders_mssql", "sql": "DELETE FROM dbo.orders"})
    parsed = json.loads(out)
    assert "error" in parsed
    assert "SELECT" in parsed["error"] and "WITH" in parsed["error"]


# ---------------------------------------------------------- instructions ---

def test_instructions_name_query_sqlserver():
    text = server._render_instructions()
    assert text.count("query_sqlserver") >= 3, "expected query_sqlserver in the read-existing-data, workflow and query lists"


# ------------------------------------------------------- dest typing tools ---

def _names_sqlserver(text):
    t = text.lower()
    return "sqlserver" in t or "sql server" in t


def test_get_dest_types_description_names_sqlserver():
    assert _names_sqlserver(_tool("get_dest_types").description)


def test_apply_dest_types_description_names_sqlserver():
    assert _names_sqlserver(_tool("apply_dest_types").description)
