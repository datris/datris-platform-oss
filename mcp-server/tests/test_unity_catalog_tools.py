"""Story: Unity Catalog 1: config + metadata push (Databricks)
(plans/stories/unity-catalog-1-metadata-push.md).

Pins the MCP side of the opt-in: create_pipeline advertises an optional
boolean `unity_catalog` arg whose description names Databricks and the
`APPLY TAG` grant, `unity_catalog=true` posts `{"unityCatalog": {"enabled":
true}}` in the registered config, and absent/false posts no `unityCatalog`
key at all (older-server and existing-pipeline back-compat)."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


# ---------------------------------------------------------------- helpers ---

def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


def _props(tool):
    return tool.inputSchema["properties"]


class _Captured:
    """Stub the two HTTP seams create_pipeline goes through so the tool runs
    end-to-end without a server and we can inspect the config it registers."""

    def __init__(self):
        self.posted = None

    def upload_content(self, path, content_b64, filename, data=None):
        assert path == "/api/v1/pipeline/generate"
        return json.dumps({
            "name": data["pipeline"],
            "source": {"fileAttributes": {"csvAttributes": {"delimiter": ","}}},
        })

    def call(self, method, path, timeout=300, **kwargs):
        if method == "post" and path == "/api/v1/pipeline":
            self.posted = kwargs.get("json")
            return "Pipeline saved"
        if method == "get" and path == "/api/v1/pipeline":
            return json.dumps({"name": kwargs.get("params", {}).get("pipeline")})
        return ""


@pytest.fixture
def captured(monkeypatch):
    c = _Captured()
    monkeypatch.setattr(server, "_upload_content", c.upload_content)
    monkeypatch.setattr(server, "_call", c.call)
    return c


def _create_databricks(captured, **extra):
    args = {
        "pipeline": "orders",
        "destination": "databricks",
        "database": "datris",
        "warehouse": "abc123def456",
        "credentialsSecret": "databricks-sp",
        "filename": "orders.csv",
        "content_text": "id,amount\n1,10\n2,20\n",
    }
    args.update(extra)
    out = json.loads(server._dispatch("create_pipeline", args))
    assert "error" not in out, out
    assert captured.posted is not None, "pipeline config was never POSTed"
    assert captured.posted["destination"]["database"]["useDatabricks"] is True, captured.posted
    return captured.posted


# ------------------------------------------------------------------ schema ---

def test_create_pipeline_schema_has_boolean_unity_catalog_arg_naming_databricks_and_apply_tag():
    props = _props(_tool("create_pipeline"))
    assert "unity_catalog" in props, sorted(props)
    arg = props["unity_catalog"]
    assert arg["type"] == "boolean", arg
    desc = arg["description"]
    assert "Databricks" in desc, desc
    assert "APPLY TAG" in desc, desc
    assert "OMIT BY DEFAULT" in desc, desc


def test_unity_catalog_is_optional():
    required = _tool("create_pipeline").inputSchema.get("required", [])
    assert "unity_catalog" not in required, required


# ---------------------------------------------------------------- dispatch ---

def test_unity_catalog_true_posts_unity_catalog_enabled_true(captured):
    posted = _create_databricks(captured, unity_catalog=True)
    assert posted.get("unityCatalog") == {"enabled": True}, posted


def test_unity_catalog_absent_posts_no_unity_catalog_key(captured):
    posted = _create_databricks(captured)
    assert "unityCatalog" not in posted, posted


def test_unity_catalog_false_posts_no_unity_catalog_key(captured):
    posted = _create_databricks(captured, unity_catalog=False)
    assert "unityCatalog" not in posted, posted
