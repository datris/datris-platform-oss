"""Story: a failed run's status shows the error message, not a Java stack trace
(plans/stories/run-status-message-not-stacktrace.md, Resolved details).

The server now puts the full stack trace of a failed run's terminal event in
an optional `detail` field. The MCP status tools must not hand that to the
model: `get_pipeline_status` and `get_job_status` strip `detail` from every
event, leave everything else (including `description` and the rollup) as is,
and say in their tool text where the full trace lives."""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

TRACE = "java.lang.IllegalStateException: schema mismatch\n\tat ai.datris.util.DataUtil$.evolveSchema(DataUtil.scala:175)"


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


def _response():
    return json.dumps({
        "rollup": {"allDone": True, "status": "error", "jobs": [{
            "pipelineToken": "tok-1", "status": "error",
            "lastError": {"processName": "JobRunner", "description": "Process completed, error: schema mismatch"},
        }]},
        "events": [
            {"state": "begin", "code": "info", "description": "Data received"},
            {"state": "end", "code": "error", "description": "Process completed, error: schema mismatch", "detail": TRACE},
        ],
    })


def _run(monkeypatch, name, args, body):
    calls = []

    def fake_call(method, path, timeout=300, **kwargs):
        calls.append((method, path, kwargs))
        return body

    monkeypatch.setattr(server, "_call", fake_call)
    out = server._dispatch(name, args)
    assert calls and calls[0][1] == "/api/v1/pipeline/status"
    return out


def test_get_pipeline_status_strips_event_detail(monkeypatch):
    out = _run(monkeypatch, "get_pipeline_status", {"publisher_token": "pub-1"}, _response())
    data = json.loads(out)
    assert all("detail" not in e for e in data["events"])
    assert "\tat ai.datris" not in out and "evolveSchema" not in out
    assert data["events"][1]["description"] == "Process completed, error: schema mismatch"
    assert data["rollup"]["jobs"][0]["lastError"]["description"] == "Process completed, error: schema mismatch"


def test_get_job_status_strips_event_detail(monkeypatch):
    out = _run(monkeypatch, "get_job_status", {"pipeline_token": "tok-1"}, _response())
    data = json.loads(out)
    assert all("detail" not in e for e in data["events"])
    assert "evolveSchema" not in out


def test_responses_without_detail_pass_through_unchanged(monkeypatch):
    body = json.dumps({"rollup": {"allDone": False}, "events": [{"state": "begin", "description": "x"}]})
    assert _run(monkeypatch, "get_pipeline_status", {"pipeline_token": "tok-1"}, body) == body
    summary = json.dumps([{"status": "success", "pipelineToken": "t"}])
    assert _run(monkeypatch, "get_job_status", {"pipeline_name": "orders"}, summary) == summary
    assert _run(monkeypatch, "get_pipeline_status", {"pipeline_token": "tok-1"}, "not json") == "not json"


def test_tool_text_says_where_the_trace_is():
    for name in ("get_pipeline_status", "get_job_status"):
        desc = _tool(name).description
        assert "stack trace is not included" in desc, name
        assert "server log" in desc and "Ops activity view" in desc, name
