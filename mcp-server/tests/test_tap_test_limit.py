"""Story: MCP and REST tap tests are capped at 20 records like the UI's Test
button (plans/stories/tap-test-limit.md) — the MCP-server half.

`test_tap` has always dispatched `POST /api/v1/tap/run {"name", "mode":
"test"}`, and the server left `testLimit` at its default 0, so every
agent-driven test streamed the whole source. After this story:

  * `test_tap`'s inputSchema gains an integer `limit` (default 20); `name`
    stays the only required field, so existing callers are untouched;
  * dispatching `test_tap` sends `testLimit` in the body — 20 by default,
    the caller's value when given, 0 for the deliberate unlimited opt-out;
  * `run_tap` is untouched: `{"name", "mode": "run"}`, no `testLimit`.

Modelled on test_test_before_cron.py: `server._base_tools()` plus a stubbed
`_call` seam (`_Captured`)."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


class _Captured:
    """Stub the HTTP seam and record every call."""

    def __init__(self):
        self.calls = []

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        return json.dumps({"recordCount": 20, "persistedReason": "test_mode"})


@pytest.fixture
def captured(monkeypatch):
    c = _Captured()
    monkeypatch.setattr(server, "_call", c.call)
    return c


def _posted_run_body(captured):
    posted = [k for m, p, k in captured.calls if (m, p) == ("post", "/api/v1/tap/run")]
    assert len(posted) == 1, captured.calls
    return posted[0]["json"]


# ------------------------------------------------------------- schema ---

def test_test_tap_schema_has_integer_limit_defaulting_to_20():
    schema = _tool("test_tap").inputSchema
    props = schema["properties"]
    assert "limit" in props, "test_tap must accept an optional `limit` for the preview size: " + repr(sorted(props))
    assert props["limit"]["type"] == "integer", props["limit"]
    assert props["limit"].get("default") == 20, props["limit"]


def test_test_tap_schema_still_requires_only_name():
    schema = _tool("test_tap").inputSchema
    assert schema["required"] == ["name"], (
        "`name` must stay the only required field — `limit` is optional: " + repr(schema["required"])
    )


# ----------------------------------------------------------- dispatch ---

def test_test_tap_without_limit_sends_test_limit_20(captured):
    server._dispatch("test_tap", {"name": "prices"})
    assert _posted_run_body(captured) == {"name": "prices", "mode": "test", "testLimit": 20}


def test_test_tap_with_limit_5_sends_5(captured):
    server._dispatch("test_tap", {"name": "prices", "limit": 5})
    assert _posted_run_body(captured) == {"name": "prices", "mode": "test", "testLimit": 5}


def test_test_tap_with_limit_0_sends_0_unlimited(captured):
    server._dispatch("test_tap", {"name": "prices", "limit": 0})
    assert _posted_run_body(captured) == {"name": "prices", "mode": "test", "testLimit": 0}


def test_run_tap_still_sends_no_test_limit(captured):
    server._dispatch("run_tap", {"name": "prices"})
    body = _posted_run_body(captured)
    assert body == {"name": "prices", "mode": "run"}, "mode=run must not carry a testLimit: " + repr(body)
