"""Story: Pipeline delete: an explicit `deleteData=false` keeps the destination
data (plans/stories/pipeline-delete-keep-data.md), Acceptance, pytest.

Pins the `delete_pipeline` MCP tool: `keep_data=true` sends `deleteData=false`
(and no `deleteConfig`, so the server's default config delete applies);
`keep_config=true` still sends the reset pair; no flags sends only the
pipeline; `keep_data` together with `keep_config` is an error returned without
calling the server; the description says kept data is listed in `kept` and a
vector destination is refused.

Modelled on test_catalog_tools.py: `server._dispatch` with a stubbed `_call`."""
import json
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tool(name):
    for t in server._all_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _all_tools()")


def _norm(text):
    return re.sub(r"\s+", " ", text or "").replace("`", "").lower()


class _Captured:
    def __init__(self, body=""):
        self.calls = []
        self.body = body

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        return self.body


@pytest.fixture
def captured(monkeypatch):
    c = _Captured(body=json.dumps({"warnings": [], "kept": ["postgres table datris.public.orders"]}))
    monkeypatch.setattr(server, "_call", c.call)
    return c


def _only_call(captured):
    assert len(captured.calls) == 1, captured.calls
    method, path, kwargs = captured.calls[0]
    assert method == "delete"
    assert path.split("?")[0] == "/api/v1/pipeline"
    return kwargs.get("params") or {}


def test_keep_data_is_an_optional_boolean_defaulting_to_false():
    schema = _tool("delete_pipeline").input_schema
    props = schema["properties"]
    assert "keep_data" in props, props
    assert props["keep_data"]["type"] == "boolean"
    assert props["keep_data"].get("default") is False
    assert "keep_data" not in schema.get("required", [])


def test_keep_data_sends_deleteData_false_and_no_deleteConfig(captured):
    out = server._dispatch("delete_pipeline", {"pipeline": "orders", "keep_data": True})
    params = _only_call(captured)
    assert params == {"pipeline": "orders", "deleteData": "false"}, params
    assert "deleteConfig" not in params
    # The server body (with `kept`) reaches the agent.
    assert json.loads(out)["kept"] == ["postgres table datris.public.orders"]


def test_keep_config_still_sends_the_reset_pair(captured):
    server._dispatch("delete_pipeline", {"pipeline": "orders", "keep_config": True})
    params = _only_call(captured)
    assert params == {"pipeline": "orders", "deleteConfig": "false", "deleteData": "true"}, params


def test_no_flags_sends_only_pipeline(captured):
    server._dispatch("delete_pipeline", {"pipeline": "orders"})
    assert _only_call(captured) == {"pipeline": "orders"}


def test_false_flags_send_only_pipeline(captured):
    server._dispatch("delete_pipeline", {"pipeline": "orders", "keep_data": False, "keep_config": False})
    assert _only_call(captured) == {"pipeline": "orders"}


def test_keep_data_with_keep_config_is_an_error_and_nothing_is_called(captured):
    out = server._dispatch("delete_pipeline", {"pipeline": "orders", "keep_data": True, "keep_config": True})
    assert captured.calls == [], "nothing may be called when keep_data and keep_config are both set"
    assert server._is_error_payload(out), out
    err = _norm(json.loads(out)["error"])
    assert "keep_data" in err and "keep_config" in err, err


_PROPER_NOUNS = re.compile(
    r"\b(snowflake|databricks|bigquery|redshift|salesforce|stripe|shopify|github|twitter|reddit|"
    r"nasdaq|bloomberg|weather|crypto|bitcoin|stock|claude|openai|anthropic)\b",
    re.IGNORECASE,
)


def test_delete_pipeline_description_says_kept_data_is_listed_and_a_vector_destination_is_refused():
    raw = _tool("delete_pipeline").description
    d = _norm(raw)
    assert "does not support deleting just the config" not in d, "the old ghost-state sentence must go"
    assert "ghost state" not in d
    assert "keep_data=true" in d, d
    tail = d[d.index("keep_data=true"):]
    assert "removes the config" in tail, d
    assert "table, collection or files" in tail, d
    assert "in kept" in tail, "the description must say kept data is listed in `kept`: " + d
    assert "refused for a vector destination" in tail, d
    assert "no longer managed by any pipeline" in tail, d
    # The pinned kept-shared sentences stay word for word.
    assert "A vector collection another pipeline also writes to is kept and reported in `warnings`" in raw
    assert "Likewise, a table or collection another pipeline also writes to is kept and reported in `warnings`." in raw
    assert _PROPER_NOUNS.findall(raw) == [], _PROPER_NOUNS.findall(raw)
