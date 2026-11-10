"""Story: Assistant preview-and-confirm schema before create_pipeline
(plans/stories/assistant-preview-confirm-schema.md), Acceptance 3.

New MCP tool `propose_pipeline_schema(tap, destination, limit=20)`:
  1. POST /api/v1/tap/run {name, mode: "test", testLimit: _test_limit(limit)};
     a test whose `status` is not `success` comes back verbatim;
  2. vector destinations stop there: {"status": "schema_proposal",
     "typeGrid": false, "preview": <first 5>, "sampleRowCount": n};
  3. otherwise POST the records (and columns) to /api/v1/pipeline/propose-schema
     and return {"status": "schema_proposal", "typeGrid": true, "fields", ...};
  4. a 404 from propose-schema (older server) becomes an error naming
     content_text as the fallback.

`requests.post` is stubbed (not `_call`), so the tests hold whether the
handler goes through `_call` or a status-aware helper."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


VECTOR = ["pgvector", "qdrant", "weaviate", "milvus", "chroma"]

RECORDS = [{"id": str(i), "label": f"v{i}", "amount": f"{i}.5"} for i in range(8)]
COLUMNS = ["id", "label", "amount"]

PROPOSAL = {
    "pipeline": None,
    "eligible": True,
    "sampleRowCount": 8,
    "fields": [
        {"name": "id", "type": "string", "samples": ["0", "1"], "stringReason": None,
         "note": "8 non-null values in 8 rows; would be int"},
        {"name": "label", "type": "string", "samples": ["v0", "v1"], "stringReason": None, "note": None},
        {"name": "amount", "type": "double", "samples": ["0.5", "1.5"], "stringReason": None, "note": None},
    ],
}

SPRING_404 = json.dumps({
    "timestamp": "2026-11-09T00:00:00.000+00:00", "status": 404, "error": "Not Found",
    "path": "/api/v1/pipeline/propose-schema",
})


class _Resp:
    def __init__(self, status_code, body):
        self.status_code = status_code
        self.text = body
        self.ok = 200 <= status_code < 300
        self.content = body.encode("utf-8")

    def json(self):
        return json.loads(self.text)


class _Server:
    def __init__(self, test_result=None, propose_status=200, propose_body=None):
        self.test_result = test_result or {
            "tap": "src", "status": "success", "mode": "test", "persisted": False,
            "persistedReason": "test_mode", "records": RECORDS, "recordCount": len(RECORDS),
            "error": None, "logs": "", "dataType": "json", "columns": COLUMNS,
        }
        self.propose_status = propose_status
        self.propose_body = propose_body if propose_body is not None else json.dumps(PROPOSAL)
        self.posts = []

    def post(self, url, **kwargs):
        path = url[len(server.DATRIS_API_URL):] if url.startswith(server.DATRIS_API_URL) else url
        self.posts.append((path, kwargs.get("json")))
        if path == "/api/v1/tap/run":
            return _Resp(200, json.dumps(self.test_result))
        if path == "/api/v1/pipeline/propose-schema":
            return _Resp(self.propose_status, self.propose_body)
        return _Resp(404, "")

    def paths(self):
        return [p for p, _ in self.posts]


def _install(monkeypatch, srv):
    monkeypatch.setattr(server.requests, "post", srv.post)
    return srv


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


# ------------------------------------------------------------- schema ---

def test_tool_is_registered_with_tap_destination_and_limit():
    schema = _tool("propose_pipeline_schema").input_schema
    props = schema["properties"]
    assert "tap" in props and "destination" in props, sorted(props)
    assert "limit" in props, sorted(props)
    assert props["limit"]["type"] == "integer", props["limit"]
    assert props["limit"].get("default") == 20, props["limit"]
    assert set(schema.get("required", [])) == {"tap", "destination"}, schema.get("required")


# ----------------------------------------------------------- dispatch ---

def test_first_call_is_a_capped_tap_test(monkeypatch):
    srv = _install(monkeypatch, _Server())
    server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "postgres"})
    assert srv.posts, "no HTTP call was made"
    path, body = srv.posts[0]
    assert path == "/api/v1/tap/run", srv.posts
    assert body == {"name": "src", "mode": "test", "testLimit": 20}, body


def test_limit_is_passed_as_test_limit(monkeypatch):
    srv = _install(monkeypatch, _Server())
    server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "postgres", "limit": 100})
    assert srv.posts[0][1] == {"name": "src", "mode": "test", "testLimit": 100}, srv.posts[0]


def test_failed_test_is_passed_through_verbatim_and_nothing_is_proposed(monkeypatch):
    failure = {
        "tap": "src", "status": "failure", "mode": "test", "persisted": False,
        "persistedReason": "test_mode", "recordCount": 0,
        "error": "Traceback: ValueError: upstream returned 503", "logs": "line 1\nline 2",
        "columns": None,
    }
    srv = _install(monkeypatch, _Server(test_result=failure))
    out = server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "postgres"})
    assert "Traceback: ValueError: upstream returned 503" in out, out
    assert "schema_proposal" not in out, out
    assert "/api/v1/pipeline/propose-schema" not in srv.paths(), srv.posts


@pytest.mark.parametrize("dest", VECTOR)
def test_vector_destination_returns_preview_only_without_calling_propose_schema(monkeypatch, dest):
    srv = _install(monkeypatch, _Server())
    out = json.loads(server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": dest}))
    assert out.get("status") == "schema_proposal", out
    assert out.get("typeGrid") is False, out
    assert out.get("preview") == RECORDS[:5], out
    assert out.get("sampleRowCount") == len(RECORDS), out
    assert "fields" not in out or not out["fields"], out
    assert srv.paths() == ["/api/v1/tap/run"], srv.posts


def test_structured_destination_makes_two_calls_in_order_and_returns_the_proposal(monkeypatch):
    srv = _install(monkeypatch, _Server())
    out = json.loads(server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "postgres"}))
    assert srv.paths() == ["/api/v1/tap/run", "/api/v1/pipeline/propose-schema"], srv.posts
    sent = srv.posts[1][1]
    assert isinstance(sent, dict), sent
    assert sent.get("records") == RECORDS, sent
    if "columns" in sent:
        assert sent["columns"] == COLUMNS, sent

    assert out.get("status") == "schema_proposal", out
    assert out.get("typeGrid") is True, out
    assert out.get("tap") == "src", out
    assert out.get("destination") == "postgres", out
    assert out.get("sampleRowCount") == 8, out
    assert "recordsTruncated" in out, out
    assert [f["name"] for f in out["fields"]] == ["id", "label", "amount"], out
    assert [f["type"] for f in out["fields"]] == ["string", "string", "double"], out
    by = {f["name"]: f for f in out["fields"]}
    assert by["id"]["note"] == "8 non-null values in 8 rows; would be int", by["id"]
    for f in out["fields"]:
        assert {"name", "type", "samples", "stringReason", "note"} <= set(f), f
    assert out.get("preview") == RECORDS[:5], out
    assert "schema_fields" in out.get("next", ""), out
    assert "create_pipeline" in out.get("next", ""), out


def test_older_server_404_returns_the_compat_error(monkeypatch):
    _install(monkeypatch, _Server(propose_status=404, propose_body=SPRING_404))
    raw = server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "postgres"})
    out = json.loads(raw)
    assert "error" in out, out
    assert "server too old" in out["error"], out
    assert "content_text" in out["error"], out
    assert "schema_proposal" not in raw, raw


# ---------------------------------------------------------------------------
# Story: Assistant: vector destinations skip checkpoint 2
# (plans/stories/assistant-vector-skip-checkpoint-2.md), Acceptance bullet 2.
# The vector result gains a `next` hint: create_pipeline with name and
# destination only, no checks / transformations / protection question.

_MONGODB_NEXT_BEFORE = (
    "MongoDB stores each record as a document as-is, so there are no column types to confirm: show "
    "the preview rows only, then call create_pipeline with destination mongodb and content_text set "
    "to a few of these rows as JSON (filename ending in .json). Do not pass schema_fields."
)


@pytest.mark.parametrize("dest", VECTOR)
def test_vector_destination_next_goes_straight_to_create_pipeline(monkeypatch, dest):
    _install(monkeypatch, _Server())
    out = json.loads(server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": dest}))
    nxt = out.get("next")
    assert isinstance(nxt, str) and nxt, f"vector result must carry a `next` hint: {out}"
    assert "create_pipeline" in nxt, nxt
    assert "update_tap" in nxt, nxt
    assert "transformations" in nxt, nxt
    stripped = nxt.replace("no schema_fields", "")
    assert "schema_fields" not in stripped, f"`next` may name schema_fields only as 'no schema_fields': {nxt}"
    assert set(out) == {"status", "typeGrid", "tap", "destination", "preview", "sampleRowCount", "next"}, sorted(out)


def test_mongodb_next_is_unchanged(monkeypatch):
    _install(monkeypatch, _Server())
    out = json.loads(server._dispatch("propose_pipeline_schema", {"tap": "src", "destination": "mongodb"}))
    assert out.get("next") == _MONGODB_NEXT_BEFORE, out
    assert out.get("typeGrid") is False, out
