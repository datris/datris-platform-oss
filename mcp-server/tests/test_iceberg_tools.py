"""Story: Iceberg in MCP tools, wizard and prompt wording
(plans/stories/iceberg-mcp-ui-prompts.md).

Pins: the create_pipeline tool schema advertises fileFormat=iceberg and
writeMode=merge, keyFields flows into the objectStore config block only when
given, the parquet/append defaults survive for clients that never send the
new args, and the prompt/tool wording no longer steers readers to loose
columnar files only (and names no query engines or vendors in the objectstore
strings). The file-content sweeps mirror the story's grep acceptance bullets
so they run under pytest instead of needing a shell.

The wording tests build their patterns from fragments so this file never
matches the sweep it enforces."""
import json
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")

# The story's loose-files-only wording pattern, case-insensitive — assembled from parts.
_LOOSE_FILES_ONLY = re.compile("parquet" + r"(/| or )" + "orc", re.IGNORECASE)


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


def _create(captured, **extra):
    args = {
        "pipeline": "orders",
        "destination": "objectstore",
        "prefix": "orders/daily",
        "filename": "orders.csv",
        "content_text": "id,amount\n1,10\n2,20\n",
    }
    args.update(extra)
    out = json.loads(server._dispatch("create_pipeline", args))
    assert "error" not in out, out
    assert captured.posted is not None, "pipeline config was never POSTed"
    return captured.posted["destination"]["objectStore"]


# ------------------------------------------------------ Acceptance bullet 2 ---
# create_pipeline with destination=objectstore, fileFormat=iceberg,
# writeMode=merge, keyFields=["id"] is accepted by the schema and produces
# that objectStore config.

def test_schema_fileformat_enum_offers_iceberg():
    assert "iceberg" in _props(_tool("create_pipeline"))["fileFormat"]["enum"]
    # existing choices survive
    assert {"parquet", "orc"} <= set(_props(_tool("create_pipeline"))["fileFormat"]["enum"])


def test_schema_writemode_enum_offers_merge():
    enum = _props(_tool("create_pipeline"))["writeMode"]["enum"]
    assert "merge" in enum
    assert {"append", "overwrite", "ignore", "errorifexists"} <= set(enum)


def test_schema_keyfields_is_documented_for_objectstore_iceberg_merge():
    kf = _props(_tool("create_pipeline"))["keyFields"]
    assert kf["type"] == "array"
    desc = kf["description"].lower()
    assert "iceberg" in desc and "merge" in desc, desc


def test_iceberg_merge_keyfields_lands_in_objectstore_config(captured):
    obj = _create(captured, fileFormat="iceberg", writeMode="merge", keyFields=["id"])
    assert obj["fileFormat"] == "iceberg"
    assert obj["writeMode"] == "merge"
    assert obj["keyFields"] == ["id"]
    assert obj["prefixKey"] == "orders/daily"


# ------------------------------------------------------ Acceptance bullet 3 ---
# create_pipeline with no fileFormat still produces parquet / append.

def test_no_fileformat_still_defaults_to_parquet_append(captured):
    obj = _create(captured)
    assert obj["fileFormat"] == "parquet"
    assert obj["writeMode"] == "append"
    assert "keyFields" not in obj


def test_keyfields_passed_through_only_when_given(captured):
    obj = _create(captured, fileFormat="iceberg", writeMode="append")
    assert obj["fileFormat"] == "iceberg"
    assert "keyFields" not in obj


# ------------------------------------------------------ Acceptance bullet 4 ---
# The story's wording sweep over mcp-server, ui/src and datrisserver/src
# returns nothing.

def _sweep_hits(rel_dirs):
    hits = []
    for rel in rel_dirs:
        for root, dirs, files in os.walk(os.path.join(REPO_ROOT, rel)):
            dirs[:] = [d for d in dirs if d not in ("node_modules", "__pycache__", "dist", "target", ".git")]
            for f in files:
                p = os.path.join(root, f)
                if os.path.abspath(p) == os.path.abspath(__file__):
                    continue
                try:
                    with open(p, encoding="utf-8", errors="ignore") as fh:
                        for n, line in enumerate(fh, 1):
                            if _LOOSE_FILES_ONLY.search(line):
                                hits.append(f"{os.path.relpath(p, REPO_ROOT)}:{n}: {line.strip()[:120]}")
                except OSError:
                    pass
    return hits


def test_server_py_no_longer_says_parquet_slash_orc():
    with open(SERVER_PY, encoding="utf-8") as fh:
        text = fh.read()
    hits = [m.group(0) for m in _LOOSE_FILES_ONLY.finditer(text)]
    assert hits == [], hits


def test_sweep_mcp_server_ui_src_datrisserver_src_is_clean():
    hits = _sweep_hits(["mcp-server", os.path.join("ui", "src"), os.path.join("datrisserver", "src")])
    assert hits == [], "\n".join(hits)


_ENGINE_OR_VENDOR = re.compile(
    r"\b(trino|duckdb|athena|presto|glue|hive|dremio|starburst|snowflake|databricks|bigquery|redshift|clickhouse)\b",
    re.IGNORECASE,
)


def test_objectstore_tool_wording_names_no_engine_or_vendor():
    """Scoped to the objectstore strings only: the query_objectstore tool and
    the objectstore-only args on create_pipeline. The `destination` arg and
    the snowflake/databricks args legitimately name those products and are
    deliberately not scanned."""
    cp = _props(_tool("create_pipeline"))
    qo = _tool("query_objectstore")
    strings = {
        "query_objectstore.description": qo.description,
        **{f"query_objectstore.{k}": v.get("description", "") for k, v in qo.inputSchema["properties"].items()},
        **{f"create_pipeline.{k}": cp[k]["description"]
           for k in ("bucket", "prefix", "fileFormat", "partitionBy", "writeMode", "deleteBeforeWrite", "provider", "endpoint")},
    }
    offenders = {k: _ENGINE_OR_VENDOR.findall(v) for k, v in strings.items() if _ENGINE_OR_VENDOR.search(v)}
    assert offenders == {}, offenders


# ------------------------------------------------------ Acceptance bullet 5 ---
# the repo-wide grep for the dead model name returns nothing; the
# file is gone.

_DEAD_MODEL = "dataset" + "_config"


def test_dead_hosted_era_dataset_model_is_deleted():
    assert not os.path.exists(os.path.join(REPO_ROOT, "ui", "src", "model", _DEAD_MODEL + ".py"))


def test_nothing_references_the_dead_dataset_model():
    hits = []
    for root, dirs, files in os.walk(REPO_ROOT):
        dirs[:] = [d for d in dirs if d not in ("node_modules", "__pycache__", "dist", "target", ".git", "plans")]
        for f in files:
            p = os.path.join(root, f)
            if os.path.abspath(p) == os.path.abspath(__file__):
                continue
            try:
                with open(p, encoding="utf-8", errors="ignore") as fh:
                    for n, line in enumerate(fh, 1):
                        if _DEAD_MODEL in line:
                            hits.append(f"{os.path.relpath(p, REPO_ROOT)}:{n}")
            except OSError:
                pass
    assert hits == [], hits
