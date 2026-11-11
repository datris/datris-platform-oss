"""Story: Assistant preview-and-confirm schema before create_pipeline
(plans/stories/assistant-preview-confirm-schema.md), Acceptance 4.

`create_pipeline` gains `schema_fields` (array of {name, type}). When present
and content/content_text are absent, the tool synthesises a header-only CSV
from the names (filename defaults to `<pipeline>.csv`), calls
/pipeline/generate as today, then sets each generated field's type from
`schema_fields` (case-insensitive). Unknown name or unsupported type -> error,
nothing posted. Absent `schema_fields` -> byte-identical behaviour to today.

Modelled on test_field_protection_tools.py (`_upload_content` + `_call` stubs)."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


NAMES = ["id", "qty", "price", "created"]
SUPPORTED = ["string", "boolean", "int", "bigint", "float", "double", "date", "timestamp"]


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


class _Captured:
    """/pipeline/generate returns all-string fields id/qty/price/created (what
    allStrings=true produces from that header); the save is captured."""

    def __init__(self):
        self.posted = None
        self.post_count = 0
        self.uploads = []

    def upload_content(self, path, content_b64, filename, data=None):
        assert path == "/api/v1/pipeline/generate"
        self.uploads.append({
            "content": server._decode_content(content_b64).decode("utf-8"),
            "filename": filename,
            "data": dict(data or {}),
        })
        return json.dumps({
            "name": data["pipeline"],
            "source": {
                "fileAttributes": {"csvAttributes": {"delimiter": ","}},
                "schemaProperties": {"fields": [{"name": n, "type": "string"} for n in NAMES]},
            },
        })

    def call(self, method, path, timeout=300, **kwargs):
        if method == "post" and path == "/api/v1/pipeline":
            self.post_count += 1
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


def _schema_args(schema_fields, **extra):
    args = {"pipeline": "sf_mcp", "destination": "postgres", "table": "sf_mcp", "schema_fields": schema_fields}
    args.update(extra)
    return args


CONFIRMED = [
    {"name": "id", "type": "bigint"},
    {"name": "qty", "type": "int"},
    {"name": "price", "type": "double"},
    {"name": "created", "type": "date"},
]


# ------------------------------------------------------------- schema ---

def test_create_pipeline_schema_has_optional_schema_fields_array():
    schema = _tool("create_pipeline").input_schema
    props = schema["properties"]
    assert "schema_fields" in props, sorted(props)
    arg = props["schema_fields"]
    assert arg["type"] == "array", arg
    item = arg["items"]
    assert item["type"] == "object", item
    assert {"name", "type"} <= set(item["properties"]), item
    assert "schema_fields" not in schema.get("required", [])


# ----------------------------------------------------------- dispatch ---

def test_types_are_applied_to_the_generated_fields(captured):
    out = json.loads(server._dispatch("create_pipeline", _schema_args(CONFIRMED)))
    assert "error" not in out, out
    assert captured.posted is not None, "pipeline config was never POSTed"
    fields = captured.posted["source"]["schemaProperties"]["fields"]
    assert [(f["name"], f["type"]) for f in fields] == [
        ("id", "bigint"), ("qty", "int"), ("price", "double"), ("created", "date"),
    ], fields


def test_without_content_a_header_only_csv_is_synthesised(captured):
    server._dispatch("create_pipeline", _schema_args(CONFIRMED))
    assert len(captured.uploads) == 1, captured.uploads
    up = captured.uploads[0]
    assert up["content"].splitlines()[0] == "id,qty,price,created", up
    assert up["filename"] == "sf_mcp.csv", up
    # /pipeline/generate is called as today.
    assert up["data"].get("allStrings") == "true", up


def test_name_match_is_case_insensitive_and_keeps_the_generated_spelling(captured):
    confirmed = [{"name": "ID", "type": "bigint"}, {"name": "Qty", "type": "int"},
                 {"name": "PRICE", "type": "double"}, {"name": "created", "type": "timestamp"}]
    out = json.loads(server._dispatch("create_pipeline", _schema_args(confirmed)))
    assert "error" not in out, out
    fields = captured.posted["source"]["schemaProperties"]["fields"]
    assert [(f["name"], f["type"]) for f in fields] == [
        ("id", "bigint"), ("qty", "int"), ("price", "double"), ("created", "timestamp"),
    ], fields


def test_unknown_name_is_an_error_listing_the_schema_fields_and_nothing_is_posted(captured):
    raw = server._dispatch("create_pipeline", _schema_args(CONFIRMED + [{"name": "missing_col", "type": "int"}]))
    assert server._is_error_payload(raw), raw
    err = json.loads(raw)["error"]
    assert "missing_col" in err, err
    for n in NAMES:
        assert n in err, f"error must list the schema fields: {err}"
    assert captured.post_count == 0, captured.posted


@pytest.mark.parametrize("bad", ["varchar", "integer", "text", "decimal", ""])
def test_unsupported_type_is_an_error_and_nothing_is_posted(captured, bad):
    confirmed = [dict(f) for f in CONFIRMED]
    confirmed[1]["type"] = bad
    raw = server._dispatch("create_pipeline", _schema_args(confirmed))
    assert server._is_error_payload(raw), raw
    err = json.loads(raw)["error"]
    # The error is about the type: it names the bad value or the supported list.
    assert (bad and bad in err) or "timestamp" in err, err
    assert captured.post_count == 0, captured.posted


@pytest.mark.parametrize("ok", SUPPORTED)
def test_every_supported_type_is_accepted(captured, ok):
    confirmed = [dict(f) for f in CONFIRMED]
    confirmed[1]["type"] = ok
    out = json.loads(server._dispatch("create_pipeline", _schema_args(confirmed)))
    assert "error" not in out, out
    fields = {f["name"]: f for f in captured.posted["source"]["schemaProperties"]["fields"]}
    assert fields["qty"]["type"] == ok


def test_schema_fields_compose_with_protect_and_codegen(captured):
    out = json.loads(server._dispatch("create_pipeline", _schema_args(
        CONFIRMED,
        protect={"id": {"method": "hmac"}},
        codegen_rule="each row has a non-empty id",
        codegen_transform="trim whitespace",
    )))
    assert "error" not in out, out
    fields = {f["name"]: f for f in captured.posted["source"]["schemaProperties"]["fields"]}
    assert fields["id"]["type"] == "bigint", fields["id"]
    assert fields["id"].get("protect") == {"method": "hmac"}, fields["id"]
    assert fields["qty"]["type"] == "int"
    assert captured.posted["dataQuality"]["aiRule"]["instruction"] == "each row has a non-empty id"
    assert captured.posted["transformation"]["aiTransformation"]["instruction"] == "trim whitespace"


# ----------------------------------------------------- backward compat ---
# Snapshot of what create_pipeline sends today for a content_text call
# (captured on main at v1.57.1 before this story). Absent schema_fields must
# keep producing exactly this.

TODAY_UPLOAD = {
    "content": "id,qty,price,created\n1,2,3.5,2026-01-01\n",
    "filename": "sf_mcp.csv",
    "data": {"pipeline": "sf_mcp", "allStrings": "true", "header": "true"},
}
TODAY_POSTED = (
    '{"destination": {"database": {"dbName": "datris", "schema": "public", "table": "sf_mcp", '
    '"usePostgres": true}}, "name": "sf_mcp", "source": {"fileAttributes": {"csvAttributes": '
    '{"delimiter": ","}}, "schemaProperties": {"fields": [{"name": "id", "type": "string"}, '
    '{"name": "qty", "type": "string"}, {"name": "price", "type": "string"}, '
    '{"name": "created", "type": "string"}]}}}'
)


def test_absent_schema_fields_posts_byte_identical_config_to_today(captured):
    out = json.loads(server._dispatch("create_pipeline", {
        "pipeline": "sf_mcp", "destination": "postgres", "table": "sf_mcp",
        "filename": "sf_mcp.csv", "content_text": "id,qty,price,created\n1,2,3.5,2026-01-01\n",
    }))
    assert "error" not in out, out
    assert captured.uploads == [TODAY_UPLOAD], captured.uploads
    assert json.dumps(captured.posted, sort_keys=True) == TODAY_POSTED, captured.posted
