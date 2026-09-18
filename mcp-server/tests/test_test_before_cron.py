"""Story: Test-before-cron gate (plans/stories/test-before-cron.md) — the
MCP-server half.

The platform now refuses `POST /api/v1/tap` with 409 when a cron is set on a
script that has not passed `test_tap`. On the MCP side that means two things:

1. `create_tap` (python lane and HTTP lane) must hand the 409 body back to the
   agent untouched — the remedy text is the whole point.
2. The prose that used to be the ONLY enforcement (VALIDATION RULE in the
   server instructions and in TAP_WORKFLOW_REFERENCE, plus the `create_tap` /
   `update_tap` descriptions) must say the platform enforces this with a 409
   and spell out the three-call remedy: save without cron -> test_tap ->
   update_tap with the cron. The rule headings stay (do not delete, align).

Modelled on test_scratch_tools.py: `server._base_tools()` plus a stubbed
`_call` seam."""
import json
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


REMEDY_409 = (
    "Tap 'prices' cannot be scheduled: its script has never passed a test run. "
    "Remedy: save the tap without `cronExpression`, call `test_tap`, then `update_tap` with the cron."
)
CONFLICT_BODY = json.dumps({"error": REMEDY_409})


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


class _Captured:
    """Stub the HTTP seam: script store succeeds, the tap save is refused with 409."""

    def __init__(self):
        self.calls = []

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        if method == "post" and path == "/api/v1/tap/script":
            return json.dumps({"scriptPath": "tap-scripts/prices_1.py", "storage": "minio"})
        if method == "post" and path == "/api/v1/tap":
            return CONFLICT_BODY
        return ""


@pytest.fixture
def captured(monkeypatch):
    c = _Captured()
    monkeypatch.setattr(server, "_call", c.call)
    return c


# --------------------------------------------- Acceptance bullet 7 (body) ---
# MCP create_tap with cron_expression on a fresh script returns the 409 body
# with the save -> test -> update remedy. (Pass-through contract: the server
# owns the message; the MCP layer must not rewrap or swallow it.)

def test_create_tap_python_lane_returns_409_body_verbatim(captured):
    out = server._dispatch("create_tap", {
        "name": "prices",
        "script": "print('hi')",
        "target_pipeline": "prices",
        "cron_expression": "0 0 3 * * ?",
    })
    assert json.loads(out) == json.loads(CONFLICT_BODY), out
    posted = [k for m, p, k in captured.calls if (m, p) == ("post", "/api/v1/tap")]
    assert len(posted) == 1 and posted[0]["json"]["cronExpression"] == "0 0 3 * * ?", captured.calls


def test_create_tap_http_lane_returns_409_body_verbatim(captured):
    out = server._dispatch("create_tap", {
        "name": "prices",
        "kind": "http",
        "endpoint_url": "https://feeds.example.org/v1/prices",
        "target_pipeline": "prices",
        "cron_expression": "0 0 3 * * ?",
    })
    assert json.loads(out) == json.loads(CONFLICT_BODY), out


def test_update_tap_returns_409_body_verbatim(monkeypatch):
    def call(method, path, timeout=300, **kwargs):
        if method == "get" and path.startswith("/api/v1/tap"):
            return json.dumps({"name": "prices", "description": "d", "targetPipeline": "prices", "enabled": True})
        if method == "post" and path == "/api/v1/tap":
            return CONFLICT_BODY
        return ""
    monkeypatch.setattr(server, "_call", call)
    out = server._dispatch("update_tap", {"name": "prices", "cron_expression": "0 0 3 * * ?"})
    assert json.loads(out) == json.loads(CONFLICT_BODY), out


# -------------------------------------------- Acceptance bullet 7 (prose) ---
# The four prose sites the story names now state the platform-enforced 409
# and the three-call remedy, in the same order the remedy runs.

def _remedy_present(text):
    """save without cron -> test_tap -> update_tap with the cron, in that order."""
    low = text.lower()
    i_save = low.find("without")
    i_test = low.find("test_tap", i_save if i_save >= 0 else 0)
    i_update = low.find("update_tap", i_test if i_test >= 0 else 0)
    return i_save >= 0 and i_test > i_save and i_update > i_test


def _section(text, heading):
    start = text.find(heading)
    assert start >= 0, f"{heading!r} missing — the rule must be aligned, not deleted"
    end = text.find("\n## ", start + len(heading))
    end = len(text) if end < 0 else end
    # Instructions template uses blank-line-separated blocks; bound by the next
    # ALL-CAPS "X RULE" heading when there is one.
    m = re.search(r"\n[A-Z][A-Z -]+ RULE\b", text[start + len(heading):])
    if m and start + len(heading) + m.start() < end:
        end = start + len(heading) + m.start()
    return text[start:end]


def test_instructions_validation_rule_states_the_409_and_remedy():
    block = _section(server._INSTRUCTIONS_TEMPLATE, "VALIDATION RULE")
    assert "409" in block, block
    assert _remedy_present(block), block


def test_tap_workflow_reference_validation_rule_states_the_409_and_remedy():
    block = _section(server.TAP_WORKFLOW_REFERENCE, "## VALIDATION RULE")
    assert "409" in block, block
    assert _remedy_present(block), block


def test_create_tap_description_states_the_409_and_remedy():
    desc = _tool("create_tap").description
    assert "409" in desc, desc
    assert _remedy_present(desc), desc


def test_update_tap_description_states_the_409_and_remedy():
    desc = _tool("update_tap").description
    assert "409" in desc, desc
    assert _remedy_present(desc), desc


def test_prose_still_carries_the_rule_headings():
    # Story: "do not delete, align".
    assert server._INSTRUCTIONS_TEMPLATE.count("VALIDATION RULE") >= 2
    assert "## VALIDATION RULE" in server.TAP_WORKFLOW_REFERENCE
