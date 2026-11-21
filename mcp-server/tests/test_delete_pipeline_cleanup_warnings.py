"""Story: Pipeline delete: a table, collection or prefix that could not be
removed is reported, and no cleanup can hang the request
(plans/stories/pipeline-delete-reports-failed-cleanup.md), Acceptance, pytest.

Pins the `delete_pipeline` MCP tool description: the sibling's sentence about a
vector collection the store could not remove is widened to tables,
collections and files. No handler change (the body is returned as is)."""
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


def test_delete_pipeline_description_says_data_that_could_not_be_removed_is_reported_in_warnings():
    d = _norm(_tool("delete_pipeline").description)
    widened = re.search(
        r"a table, collection, files or (a )?vector collection the store could not remove is reported in warnings", d
    )
    assert widened, d
    # The rest of the sibling's sentence still holds for every kind.
    tail = d[widened.end():]
    assert "the configuration is deleted regardless" in tail, d
    assert "relay the warning" in tail, d

