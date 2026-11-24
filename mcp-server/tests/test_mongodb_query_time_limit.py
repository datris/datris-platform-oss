"""Story: MongoDB query: every query has a time limit, and an unlimited read
cannot exhaust the server's memory (plans/stories/mongodb-query-bounds.md).

Pins the MCP line of Acceptance: the query_mongodb tool states the time limit,
30 seconds for a bounded query and 300 seconds for `limit: -1` (Decision 1), so
an agent asking for every document knows the query can be cancelled. The tool
description proper must carry the 300-second limit; the 30-second one may sit
there or in the `limit` property's text."""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


_SECONDS = r"[\s-]*(s|sec|secs|second|seconds)\b"
_UNLIMITED = re.compile(r"\b300" + _SECONDS + r"|\b(5|five)[\s-]*minutes?\b", re.IGNORECASE)
_BOUNDED = re.compile(r"\b30" + _SECONDS + r"|\bthirty[\s-]*seconds?\b", re.IGNORECASE)


def test_the_query_mongodb_description_states_the_time_limit():
    tool = _tool("query_mongodb")
    description = tool.description
    limit_text = tool.input_schema["properties"]["limit"].get("description", "")
    assert _UNLIMITED.search(description), f"query_mongodb description does not state the 300 s time limit: {description}"
    assert _BOUNDED.search(description + " " + limit_text), (
        f"query_mongodb does not state the 30 s time limit for a bounded query: {description} / {limit_text}"
    )
    # -1 must no longer read as "no time limit"
    assert not re.search(r"disables? the (query )?timeout|no time limit", description + " " + limit_text, re.IGNORECASE)
