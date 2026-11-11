"""Story: Assistant preview-and-confirm schema before create_pipeline
(plans/stories/assistant-preview-confirm-schema.md), review follow-up.

/pipeline/generate reads the synthesised header by splitting on the raw
delimiter and stripping quotes (AISchemaUtil.buildCsvConfigAllStrings), so a
schema_fields name holding the delimiter, a quote or a line break could never
match a generated column. It is refused up front, before any HTTP call."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


@pytest.fixture
def no_http(monkeypatch):
    calls = []

    def upload(*a, **k):
        calls.append(("upload", a))
        raise AssertionError("no upload expected")

    def call(*a, **k):
        calls.append(("call", a))
        raise AssertionError("no call expected")

    monkeypatch.setattr(server, "_upload_content", upload)
    monkeypatch.setattr(server, "_call", call)
    return calls


@pytest.mark.parametrize("bad", ["a,b", 'a"b', "a'b", "a\nb"])
def test_unsafe_name_is_refused_before_any_call(no_http, bad):
    raw = server._dispatch("create_pipeline", {
        "pipeline": "p", "destination": "postgres",
        "schema_fields": [{"name": "id", "type": "int"}, {"name": bad, "type": "string"}],
    })
    assert server._is_error_payload(raw), raw
    assert "may not contain" in json.loads(raw)["error"]
    assert no_http == []


def test_name_with_the_delimiter_depends_on_the_delimiter():
    ok, err = server._validate_schema_fields([{"name": "a,b", "type": "int"}], "|")
    assert err is None and ok == [{"name": "a,b", "type": "int"}]
    _, err = server._validate_schema_fields([{"name": "a|b", "type": "int"}], "|")
    assert err and "may not contain" in err


def test_tab_delimiter_header_is_joined_with_a_real_tab():
    assert server._csv_header_line(["a", "b"], "\\t") == "a\tb\n"
    assert server._csv_header_line(["a", "b"]) == "a,b\n"
