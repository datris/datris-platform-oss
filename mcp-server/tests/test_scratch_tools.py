"""Story: Scratch results over MCP and the CLI, and the KEEP-OR-SCRATCH rule
(plans/stories/scratch-mcp-cli-prompts.md) — the MCP-server half.

Pins: the new `get_pipeline_result` tool (schema, description order, dispatch
to GET /api/v1/pipeline/result), `scratch` as a fourth `create_pipeline`
destination that still goes through /pipeline/generate and registers exactly
{"scratch": {}}, the existing postgres / objectstore / vector configs being
byte-for-byte what they were, and the wording sweep: KEEP-OR-SCRATCH in the
instructions, scratch NOT joining the five-destination offer list, and
create_tap no longer steering agents into a fetch-only tap with no target.

Modelled on test_iceberg_tools.py: `server._base_tools()` plus stubbed
`_call` / `_upload_content` seams."""
import json
import os
import re
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
    """Stub the HTTP seams so tools run end-to-end without a server."""

    def __init__(self):
        self.posted = None
        self.calls = []          # (method, path, kwargs) for every _call
        self.generate_calls = 0

    def upload_content(self, path, content_b64, filename, data=None):
        assert path == "/api/v1/pipeline/generate"
        self.generate_calls += 1
        return json.dumps({
            "name": data["pipeline"],
            "source": {"fileAttributes": {"csvAttributes": {"delimiter": ","}}},
        })

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        if method == "post" and path == "/api/v1/pipeline":
            self.posted = kwargs.get("json")
            return "Pipeline saved"
        if method == "get" and path == "/api/v1/pipeline":
            return json.dumps({"name": kwargs.get("params", {}).get("pipeline")})
        if method == "get" and path == "/api/v1/pipeline/result":
            return json.dumps({"records": [], "offset": 0, "limit": 100, "total": 0, "truncated": False})
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
        "filename": "orders.csv",
        "content_text": "id,amount\n1,10\n2,20\n",
    }
    args.update(extra)
    out = json.loads(server._dispatch("create_pipeline", args))
    assert "error" not in out, out
    assert captured.posted is not None, "pipeline config was never POSTed"
    return captured.posted["destination"]


# ------------------------------------------------------ Acceptance bullet 1 ---
# get_pipeline_result is in _base_tools() with exactly publisher_token,
# pipeline_token, offset, limit and no `required`; description order pinned.

def test_get_pipeline_result_tool_exists_with_exact_params():
    t = _tool("get_pipeline_result")
    assert set(_props(t).keys()) == {"publisher_token", "pipeline_token", "offset", "limit"}
    assert "required" not in t.inputSchema or t.inputSchema["required"] in ([], None)


def test_get_pipeline_result_offset_and_limit_are_integers():
    p = _props(_tool("get_pipeline_result"))
    assert p["offset"]["type"] == "integer"
    assert p["limit"]["type"] == "integer"


def test_get_pipeline_result_description_covers_scratch_preview_rerun_and_expiry():
    d = _tool("get_pipeline_result").description
    low = d.lower()
    assert "scratch" in low
    assert "resultPreview" in d
    assert "resultTruncated" in d
    assert "never re-run" in low or "never re-runs" in low, d
    assert "410" in d
    assert "expire" in low, d


def test_get_pipeline_result_description_names_the_real_page_shape():
    d = _tool("get_pipeline_result").description
    assert "returnedCount" in d and "rowCount" in d, d
    assert "total" not in d and "expiresAt" not in d.replace("resultExpiresAt", ""), d


def test_get_pipeline_result_description_mentions_preview_before_paging():
    d = _tool("get_pipeline_result").description
    preview_at = d.find("resultPreview")
    paging_at = min(i for i in (d.lower().find("page"), d.lower().find("paging"), d.lower().find("offset")) if i >= 0)
    assert 0 <= preview_at < paging_at, d


# ------------------------------------------------------ Acceptance bullet 2 ---
# dispatch with pipeline_token -> GET /api/v1/pipeline/result?pipelinetoken=;
# neither token -> error, no HTTP; offset/limit absent when not supplied.

def test_dispatch_pipeline_token_issues_get_result(captured):
    server._dispatch("get_pipeline_result", {"pipeline_token": "ptok-1"})
    assert len(captured.calls) == 1, captured.calls
    method, path, kwargs = captured.calls[0]
    assert (method, path) == ("get", "/api/v1/pipeline/result")
    params = kwargs.get("params", {})
    assert params.get("pipelinetoken") == "ptok-1"
    assert "publishertoken" not in params


def test_dispatch_publisher_token_issues_get_result(captured):
    server._dispatch("get_pipeline_result", {"publisher_token": "pub-1"})
    method, path, kwargs = captured.calls[0]
    assert (method, path) == ("get", "/api/v1/pipeline/result")
    assert kwargs["params"].get("publishertoken") == "pub-1"


def test_dispatch_neither_token_errors_without_http(captured):
    out = json.loads(server._dispatch("get_pipeline_result", {}))
    assert "error" in out, out
    assert "token" in out["error"].lower(), out   # not "Unknown tool"
    assert captured.calls == []


def test_dispatch_offset_limit_absent_when_not_supplied(captured):
    server._dispatch("get_pipeline_result", {"pipeline_token": "ptok-1"})
    params = captured.calls[0][2]["params"]
    assert "offset" not in params and "limit" not in params, params


def test_dispatch_offset_limit_forwarded_when_supplied(captured):
    server._dispatch("get_pipeline_result", {"pipeline_token": "ptok-1", "offset": 200, "limit": 50})
    params = captured.calls[0][2]["params"]
    assert str(params.get("offset")) == "200"
    assert str(params.get("limit")) == "50"


# ------------------------------------------------------ Acceptance bullet 3 ---
# enum contains scratch; destination=scratch goes through /pipeline/generate
# and posts destination == {"scratch": {}}; other destinations unchanged.

def test_create_pipeline_destination_enum_offers_scratch():
    enum = _props(_tool("create_pipeline"))["destination"]["enum"]
    assert "scratch" in enum
    assert {"postgres", "mongodb", "snowflake", "databricks", "objectstore",
            "qdrant", "weaviate", "milvus", "chroma", "pgvector"} <= set(enum)


def test_create_pipeline_description_names_scratch_category():
    d = _tool("create_pipeline").description
    assert "scratch" in d.lower()
    assert "get_pipeline_result" in d


def test_scratch_goes_through_generate_and_posts_exact_scratch_block(captured):
    dest = _create(captured, destination="scratch")
    assert captured.generate_calls == 1, "scratch must keep the /pipeline/generate schema round-trip"
    assert dest == {"scratch": {}}, dest


def test_scratch_ignores_table_database_and_keyfields(captured):
    dest = _create(captured, destination="scratch", table="t", database="d", keyFields=["id"])
    assert dest == {"scratch": {}}, dest


def test_postgres_config_unchanged(captured):
    dest = _create(captured, destination="postgres", table="orders_t", database="shop")
    assert set(dest.keys()) == {"database"}
    assert dest["database"] == {"dbName": "shop", "schema": "public", "table": "orders_t", "usePostgres": True}


def test_objectstore_config_unchanged(captured):
    dest = _create(captured, destination="objectstore", prefix="orders/daily")
    assert set(dest.keys()) == {"objectStore"}
    assert dest["objectStore"] == {
        "prefixKey": "orders/daily",
        "fileFormat": "parquet",
        "writeMode": "append",
        "deleteBeforeWrite": False,
        "provider": "minio",
    }


def test_vector_config_unchanged_and_skips_generate(captured):
    args = {"pipeline": "docs", "destination": "qdrant"}
    out = json.loads(server._dispatch("create_pipeline", args))
    assert "error" not in out, out
    assert captured.generate_calls == 0
    dest = captured.posted["destination"]
    assert set(dest.keys()) == {"qdrant"}
    assert dest["qdrant"]["collectionName"] == "docs"
    assert dest["qdrant"]["chunking"] == {"strategy": "recursive", "chunkSize": 500, "chunkOverlap": 50}


# ------------------------------------------------------ Acceptance bullet 4 ---
# wording sweep.

def test_instructions_template_contains_keep_or_scratch_rule():
    tpl = server._INSTRUCTIONS_TEMPLATE
    assert "KEEP-OR-SCRATCH" in tpl
    rule = tpl[tpl.find("KEEP-OR-SCRATCH"):]
    assert "scratch" in rule
    assert "get_pipeline_result" in rule
    assert "resultPreview" in rule
    assert "update_pipeline" in rule


def test_rendered_instructions_contain_keep_or_scratch_rule():
    assert "KEEP-OR-SCRATCH" in server._render_destination_templates(
        server._INSTRUCTIONS_TEMPLATE, list(server.ALL_STRUCTURED_DESTINATIONS))


def test_all_structured_destinations_still_the_five_names():
    assert tuple(server.ALL_STRUCTURED_DESTINATIONS) == (
        "postgres", "mongodb", "objectstore", "snowflake", "databricks")


def _offering_rule_text():
    tpl = server._INSTRUCTIONS_TEMPLATE
    start = tpl.find("DESTINATION OFFERING RULE")
    assert start >= 0
    # the rule runs to the next blank line
    end = tpl.find("\n\n", start)
    return tpl[start:end if end > 0 else len(tpl)]


def test_destination_offering_rule_does_not_mention_scratch():
    assert "scratch" not in _offering_rule_text().lower()


def test_create_tap_no_longer_says_only_skip_target_pipeline():
    d = _tool("create_tap").description
    assert "Only skip target_pipeline" not in d
    assert "scratch" in d.lower(), "create_tap should point fetch-only taps at a scratch pipeline"


def test_upload_data_and_run_tap_mention_scratch():
    assert "scratch" in _tool("upload_data").description.lower()
    rt = _tool("run_tap").description
    assert "scratch" in rt.lower()
    assert "resultPreview" in rt and "get_pipeline_result" in rt


_VENDOR_OR_DOMAIN = re.compile(
    r"\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|"
    r"nasdaq|bloomberg|weather|crypto|bitcoin|stock)\b",
    re.IGNORECASE,
)
_CRON = re.compile(r"(\d+|\*)\s+(\d+|\*)\s+(\d+|\*)\s+(\d+|\*)\s+(\d+|\*)")


def test_keep_or_scratch_rule_has_no_domain_bias_or_cron():
    tpl = server._INSTRUCTIONS_TEMPLATE
    start = tpl.find("KEEP-OR-SCRATCH")
    assert start >= 0, "KEEP-OR-SCRATCH rule missing from _INSTRUCTIONS_TEMPLATE"
    end = tpl.find("\n\n", start)
    rule = tpl[start:end if end > 0 else len(tpl)]
    assert _VENDOR_OR_DOMAIN.findall(rule) == [], _VENDOR_OR_DOMAIN.findall(rule)
    assert _CRON.search(rule) is None, rule
