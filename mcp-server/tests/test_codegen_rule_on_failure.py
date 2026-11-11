"""Story: Assistant data-quality card: the user chooses warn-only vs reject on
failure (plans/stories/assistant-dq-card-on-failure.md), Acceptance bullet 1.

`create_pipeline` gains an optional `codegen_rule_on_failure` ("error" |
"warn", default "error") mapped onto `dataQuality.aiRule.onFailureIsError`.
Absent -> True, byte-identical to today. An invalid value is an error and
nothing is posted. Given without `codegen_rule` it is ignored (no
dataQuality block, no error).

Modelled on test_create_pipeline_schema_fields.py (`_Captured` stub, `_tool`)."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


NAMES = ["id", "qty", "price", "created"]
RULE = "1. id: is never empty\n2. qty: is a whole number"


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


class _Captured:
    def __init__(self):
        self.posted = None
        self.post_count = 0
        self.uploads = []

    def upload_content(self, path, content_b64, filename, data=None):
        assert path == "/api/v1/pipeline/generate"
        self.uploads.append({"filename": filename, "data": dict(data or {})})
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


def _args(**extra):
    args = {
        "pipeline": "of_mcp", "destination": "postgres", "table": "of_mcp",
        "filename": "of_mcp.csv", "content_text": "id,qty,price,created\n1,2,3.5,2026-01-01\n",
    }
    args.update(extra)
    return args


# ------------------------------------------------------------- schema ---

def test_create_pipeline_schema_has_codegen_rule_on_failure_enum_error_warn():
    schema = _tool("create_pipeline").input_schema
    props = schema["properties"]
    assert "codegen_rule_on_failure" in props, sorted(props)
    arg = props["codegen_rule_on_failure"]
    assert arg.get("type") == "string", arg
    assert arg.get("enum") == ["error", "warn"], arg
    assert "codegen_rule_on_failure" not in schema.get("required", [])


# ----------------------------------------------------- backward compat ---
# What create_pipeline posts today for codegen_rule with no severity
# (captured on 9e8da00a, before this story).

TODAY_POSTED_WITH_RULE = (
    '{"dataQuality": {"aiRule": {"instruction": "1. id: is never empty\\n2. qty: is a whole number", '
    '"onFailureIsError": true}}, "destination": {"database": {"dbName": "datris", "schema": "public", '
    '"table": "of_mcp", "usePostgres": true}}, "name": "of_mcp", "source": {"fileAttributes": '
    '{"csvAttributes": {"delimiter": ","}}, "schemaProperties": {"fields": [{"name": "id", "type": '
    '"string"}, {"name": "qty", "type": "string"}, {"name": "price", "type": "string"}, '
    '{"name": "created", "type": "string"}]}}}'
)


def test_codegen_rule_without_the_arg_rejects_and_is_byte_identical_to_today(captured):
    out = json.loads(server._dispatch("create_pipeline", _args(codegen_rule=RULE)))
    assert "error" not in out, out
    assert captured.posted["dataQuality"]["aiRule"]["onFailureIsError"] is True
    assert json.dumps(captured.posted, sort_keys=True) == TODAY_POSTED_WITH_RULE, captured.posted


# ----------------------------------------------------------- dispatch ---

def test_warn_maps_to_on_failure_is_error_false(captured):
    out = json.loads(server._dispatch("create_pipeline", _args(codegen_rule=RULE, codegen_rule_on_failure="warn")))
    assert "error" not in out, out
    assert captured.post_count == 1
    ai_rule = captured.posted["dataQuality"]["aiRule"]
    assert ai_rule["onFailureIsError"] is False, ai_rule
    assert ai_rule["instruction"] == RULE
    assert "codegen_rule_on_failure" not in json.dumps(captured.posted), "the arg must not leak into the config"


def test_error_maps_to_on_failure_is_error_true(captured):
    out = json.loads(server._dispatch("create_pipeline", _args(codegen_rule=RULE, codegen_rule_on_failure="error")))
    assert "error" not in out, out
    assert captured.posted["dataQuality"]["aiRule"]["onFailureIsError"] is True
    assert json.dumps(captured.posted, sort_keys=True) == TODAY_POSTED_WITH_RULE, captured.posted


@pytest.mark.parametrize("bad", ["abort", "WARN", "reject"])
def test_invalid_value_is_an_error_naming_the_arg_and_nothing_is_posted(captured, bad):
    raw = server._dispatch("create_pipeline", _args(codegen_rule=RULE, codegen_rule_on_failure=bad))
    assert server._is_error_payload(raw), raw
    err = json.loads(raw)["error"]
    assert "codegen_rule_on_failure" in err, err
    assert captured.post_count == 0, captured.posted


def test_arg_without_codegen_rule_is_ignored(captured):
    raw = server._dispatch("create_pipeline", _args(codegen_rule_on_failure="warn"))
    out = json.loads(raw)
    assert not server._is_error_payload(raw), raw
    assert "error" not in out, out
    assert captured.post_count == 1
    assert "dataQuality" not in captured.posted, captured.posted
