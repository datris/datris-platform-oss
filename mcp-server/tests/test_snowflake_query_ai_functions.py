"""Story: Snowflake query: a call to a model or search service is refused, as
on Databricks (plans/stories/snowflake-ai-functions-refused.md).

Pins the MCP half of Acceptance: the query_snowflake tool description says, in
a sentence about what is refused, that calls to a model and to a search
service are refused, so an agent does not send such a query and read the 400
as a fault."""
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


def _sentences(desc):
    return [s for s in re.split(r"(?<=[.;])\s+", desc.replace("\n", " ")) if s.strip()]


def test_the_query_snowflake_description_says_model_and_search_calls_are_refused():
    desc = _tool("query_snowflake").description
    refused = [s for s in _sentences(desc) if re.search(r"\b(refused|rejected)\b", s, re.IGNORECASE)]
    assert refused, f"query_snowflake description has no sentence saying what is refused: {desc}"
    assert any(
        re.search(r"\bmodels?\b", s, re.IGNORECASE) and re.search(r"\bsearch", s, re.IGNORECASE) for s in refused
    ), f"no refused sentence of the query_snowflake description mentions model and search calls: {refused}"
