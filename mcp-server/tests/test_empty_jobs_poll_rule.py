"""Story: Four small read leaks for callers limited to a catalog
(plans/stories/limited-caller-read-leaks.md), review fix 6.

A run or publisher token whose runs are all outside the caller's access
answers with an empty job list and `allDone` false, the same answer as a
token that does not exist. Every passage that tells an agent to poll a token
until it is done also says that an answer with no jobs means the token has no
runs available to it, so it stops polling and reports that: in the MCP tool
descriptions, the server instructions and resources, and the published
skill."""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RULE = "an answer with no jobs (`rollup.jobs` empty) means the token has no runs available to you, so stop polling and report that"

# A passage that tells an agent to poll a run or publisher token until it is done.
POLL = re.compile(r"(until `?rollup\.allDone`?|then poll `get_pipeline_status`|until the rollup is done|poll until all are done)", re.I)
# The rule must follow within this many characters of the instruction.
NEAR = 400


def _read(*parts):
    with open(os.path.join(ROOT, *parts), encoding="utf-8") as f:
        return f.read()


def _passages(text):
    return [m.start() for m in POLL.finditer(text)]


def _assert_rule_near_each(text, where):
    found = _passages(text)
    assert found, where + ": no polling passage found"
    low = text.lower()
    for at in found:
        window = low[at:at + NEAR]
        assert RULE in window, where + ": polling passage without the no-jobs rule: " + text[max(0, at - 120):at + 200]


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


def test_status_tool_descriptions_carry_the_rule():
    for name in ("get_pipeline_status", "get_job_status", "run_tap"):
        _assert_rule_near_each(_tool(name).description, name)


def test_every_polling_passage_in_the_server_source_carries_the_rule():
    src = _read("mcp-server", "server.py")
    # the approval and catalog-enable polls are not token polls
    _assert_rule_near_each(src, "server.py")


def test_skill_polling_guidance_carries_the_rule():
    for parts in (
        ("skills", "datris-platform", "SKILL.md"),
        ("skills", "datris-platform", "references", "workflows.md"),
        ("skills", "datris-platform", "references", "mcp-tools.md"),
        ("skills", "datris-platform", "references", "rest-api.md"),
    ):
        text = _read(*parts)
        if "references" in parts and parts[-1] == "mcp-tools.md":
            assert RULE in text.lower(), "/".join(parts)
        else:
            _assert_rule_near_each(text, "/".join(parts))


def test_the_rule_names_no_value_beyond_the_field():
    assert re.findall(r"`[^`]+`", RULE) == ["`rollup.jobs`"]
