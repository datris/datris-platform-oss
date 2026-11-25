"""Story: Snowflake query: statements chained with the flow operator are
refused (plans/stories/snowflake-chained-statements-refused.md).

Pins the MCP half of Acceptance: the query_snowflake tool description says a
query is one statement, and that semicolons and the ->> operator are both
refused, so an agent does not read the semicolon rule as the whole rule."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


def test_the_query_snowflake_description_says_a_query_is_one_statement():
    desc = _tool("query_snowflake").description
    sentences = [s for s in desc.replace("\n", " ").split(". ") if "one statement" in s.lower()]
    assert sentences, f"query_snowflake description does not say a query is one statement: {desc}"
    sentence = sentences[0]
    lowered = sentence.lower()
    assert "semicolon" in lowered, sentence
    assert "->>" in sentence, sentence
    assert "refused" in lowered or "rejected" in lowered, sentence
