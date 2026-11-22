"""Story: Pipeline delete: a vector collection that could not be removed is
reported, never logged as deleted
(plans/stories/vector-store-delete-reports-failure.md), Acceptance, pytest.

Pins the `delete_pipeline` MCP tool description: a vector collection the store
could not remove is reported in `warnings`, the configuration is deleted
regardless, and the agent relays the warning. No handler change."""
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


def _tool(name):
    for t in server._all_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _all_tools()")


def _norm(text):
    return re.sub(r"\s+", " ", text or "").replace("`", "").lower()


def test_delete_pipeline_description_says_a_collection_that_could_not_be_removed_is_reported_in_warnings():
    d = _norm(_tool("delete_pipeline").description)
    assert "a vector collection the store could not remove is reported in warnings" in d, d
    assert "the configuration is deleted regardless" in d, d
    assert "relay the warning" in d, d


def test_the_existing_shared_collection_sentence_is_still_there():
    d = _norm(_tool("delete_pipeline").description)
    assert "a vector collection another pipeline also writes to is kept and reported in warnings" in d, d
