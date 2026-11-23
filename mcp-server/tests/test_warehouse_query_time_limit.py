"""Story: SQL Server, Snowflake and Databricks queries: one statement, no
functions that act, and a time limit on every query
(plans/stories/query-sqlserver-snowflake-databricks-side-effects.md).

Pins the MCP half of Acceptance: the query_snowflake, query_sqlserver and
query_databricks tool descriptions state the time limit, the 300-second
ceiling that now applies to `limit: -1` as well (Decision 5), so an agent
asking for every row knows the query can be cancelled."""
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


# 300 seconds, however it is written: "300 s", "300 seconds", "300-second", "5 minutes", "five minutes".
_UNLIMITED_CEILING = re.compile(r"\b300[\s-]*(s|sec|secs|second|seconds)\b|\b(5|five)[\s-]*minutes?\b", re.IGNORECASE)


@pytest.mark.parametrize("name", ["query_snowflake", "query_sqlserver", "query_databricks"])
def test_the_three_query_tool_descriptions_state_the_time_limit(name):
    description = _tool(name).description
    assert _UNLIMITED_CEILING.search(description), f"{name} description does not state the 300 s time limit: {description}"
    # the limit applies to limit -1 too: the description must not leave -1 reading as "no time limit"
    assert not re.search(r"disables? the (query )?timeout", description, re.IGNORECASE), description
