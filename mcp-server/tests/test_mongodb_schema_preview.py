"""Story: Assistant preview-and-confirm schema (E2E finding).

A MongoDB pipeline created with typed `schema_fields` failed to load with
"Schema must contain a '_json' field for semi-structured data ingestion into
MongoDB": a tap's JSON records land in MongoDB as documents, so there are no
column types to confirm. propose_pipeline_schema now treats mongodb like the
vector destinations (preview only, no propose-schema call, a `next` hint), and
create_pipeline rejects schema_fields for mongodb before anything is posted."""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))

import server  # noqa: E402
from test_propose_pipeline_schema import RECORDS, _Server, _install  # noqa: E402
from test_create_pipeline_schema_fields import CONFIRMED, _Captured  # noqa: E402

import pytest  # noqa: E402


@pytest.fixture
def captured(monkeypatch):
    c = _Captured()
    monkeypatch.setattr(server, "_upload_content", c.upload_content)
    monkeypatch.setattr(server, "_call", c.call)
    return c


def test_mongodb_proposal_is_preview_only_without_calling_propose_schema(monkeypatch):
    srv = _install(monkeypatch, _Server())
    out = json.loads(server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "mongodb"}))
    assert out.get("status") == "schema_proposal", out
    assert out.get("typeGrid") is False, out
    assert out.get("preview") == RECORDS[:5], out
    assert out.get("sampleRowCount") == len(RECORDS), out
    assert "fields" not in out or not out["fields"], out
    assert srv.paths() == ["/api/v1/tap/run"], srv.posts


def test_mongodb_proposal_next_says_documents_are_stored_as_is(monkeypatch):
    _install(monkeypatch, _Server())
    out = json.loads(server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "MongoDB"}))
    nxt = out.get("next") or ""
    assert "as-is" in nxt and "no column types" in nxt, nxt
    assert "schema_fields" in nxt and "content_text" in nxt, nxt


def test_create_pipeline_mongodb_with_schema_fields_is_an_error_and_nothing_is_posted(captured):
    raw = server._dispatch("create_pipeline", {"pipeline": "m1", "destination": "mongodb", "schema_fields": CONFIRMED})
    assert server._is_error_payload(raw), raw
    err = json.loads(raw)["error"]
    assert "mongodb" in err.lower(), err
    for dest in ("postgres", "snowflake", "databricks", "sqlserver", "objectstore"):
        assert dest in err, f"the error must list the destinations that accept schema_fields: {err}"
    assert captured.post_count == 0, captured.posted
    assert captured.uploads == [], captured.uploads


def test_create_pipeline_mongodb_with_a_json_sample_still_works(captured):
    raw = server._dispatch("create_pipeline", {
        "pipeline": "m1", "destination": "mongodb",
        "content_text": json.dumps(RECORDS[:3]), "filename": "m1.json",
    })
    assert not server._is_error_payload(raw), raw
    assert captured.post_count == 1


def test_create_pipeline_mongodb_with_empty_schema_fields_is_not_rejected_by_the_mongodb_rule(captured):
    raw = server._dispatch("create_pipeline", {
        "pipeline": "m1", "destination": "mongodb", "schema_fields": [],
        "content_text": json.dumps(RECORDS[:3]), "filename": "m1.json",
    })
    assert "does not apply to mongodb" not in raw, raw


def test_tool_descriptions_say_mongodb_gets_a_preview_only():
    tools = {t.name: t for t in server._base_tools()}
    propose = tools["propose_pipeline_schema"].description
    assert "mongodb" in propose.lower() and "typeGrid: false" in propose, propose
    sf = tools["create_pipeline"].input_schema["properties"]["schema_fields"]["description"]
    assert "rejected for mongodb" in sf, sf
    assert "not mongodb" in tools["create_pipeline"].description, tools["create_pipeline"].description


# Story: Schema proposal reuses the tap's test rows and stops cleanly on failure
# (plans/stories/schema-proposal-reuse-test-rows.md), Acceptance 4.

def test_mongodb_preview_sends_reuse_last_test_and_reports_rows_from(monkeypatch):
    srv = _install(monkeypatch, _Server())
    out = json.loads(server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "mongodb"}))
    body = srv.posts[0][1]
    assert srv.posts[0][0] == "/api/v1/tap/run", srv.posts
    assert body.get("reuseLastTest") is True, body
    assert out.get("rowsFrom") == "new_test", out


def test_mongodb_preview_from_a_reused_test_reports_last_test(monkeypatch):
    result = dict(_Server().test_result)
    result.update({"reused": True, "testedAt": "2026-11-12T10:15:00Z"})
    srv = _install(monkeypatch, _Server(test_result=result))
    out = json.loads(server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "mongodb"}))
    assert out.get("rowsFrom") == "last_test", out
    assert out.get("testedAt") == "2026-11-12T10:15:00Z", out
    assert srv.paths() == ["/api/v1/tap/run"], srv.posts
