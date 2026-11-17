"""Story: One vector container per pipeline
(plans/stories/vector-container-one-pipeline.md), Acceptance,
test_vector_container_rule_docs.py.

Pins the agent-facing wording: `create_pipeline` and the
`datris://pipeline-config-reference` vector section say a vector
destination's collection, class or table must not already be used by another
pipeline (the save is refused); `delete_pipeline` says a vector collection
another pipeline also writes to is kept and reported in `warnings`."""
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


def _norm(text):
    return re.sub(r"\s+", " ", text or "").replace("`", "").lower()


CREATE_RULE = "must not already be used by another pipeline"


def test_create_pipeline_and_delete_pipeline_state_the_one_pipeline_per_container_rule():
    create = _norm(_tool("create_pipeline").description)
    assert CREATE_RULE in create, "create_pipeline description lacks the one-pipeline-per-container rule"
    tail = create[create.index(CREATE_RULE):]
    assert "refused" in tail[:120], "create_pipeline must say the save is refused"
    assert "collection, class or table" in create, create

    reference = _norm(server.PIPELINE_CONFIG_REFERENCE)
    assert CREATE_RULE in reference, "pipeline-config-reference lacks the one-pipeline-per-container rule"
    assert "collection, class or table" in reference
    ref_tail = reference[reference.index(CREATE_RULE):]
    assert "refused" in ref_tail[:120]

    delete = _norm(_tool("delete_pipeline").description)
    assert "another pipeline also writes to is kept" in delete, \
        "delete_pipeline description lacks the kept-shared-collection sentence"
    tail = delete[delete.index("another pipeline also writes to is kept"):]
    assert "warnings" in tail[:80], "delete_pipeline must say the kept collection is reported in warnings"
