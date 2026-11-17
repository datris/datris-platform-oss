"""Story: Hidden dataset writes
(plans/stories/restricted-hidden-dataset-writes.md), Acceptance,
test_shared_dataset_rule_docs.py.

Pins the agent-facing wording: `create_pipeline`'s `authoritative` parameter
says the flag does not allow writing to a destination used by a pipeline the
caller cannot see (that save is refused); `delete_pipeline` and the
`datris://pipeline-config-reference` say a table or collection another
pipeline also writes to is kept and reported in `warnings`. The vector-rule
sentences pinned by test_vector_container_rule_docs.py are not re-checked."""
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


HIDDEN_RULE = "it does not allow writing to a destination used by a pipeline you cannot see"
KEPT_RULE = "a table or collection another pipeline also writes to is kept and reported in warnings"


def test_create_pipeline_and_delete_pipeline_state_the_hidden_writer_and_kept_table_rules():
    auth = _norm(_tool("create_pipeline").input_schema["properties"]["authoritative"]["description"])
    assert HIDDEN_RULE in auth, "create_pipeline authoritative lacks the hidden-writer sentence: " + auth
    tail = auth[auth.index(HIDDEN_RULE) + len(HIDDEN_RULE):]
    assert "that save is refused" in tail[:40], "authoritative must say that save is refused"
    # the existing guidance stays
    assert auth.startswith("omit by default."), auth

    delete = _norm(_tool("delete_pipeline").description)
    assert KEPT_RULE in delete, "delete_pipeline lacks the kept-table sentence: " + delete

    reference = _norm(server.PIPELINE_CONFIG_REFERENCE)
    assert KEPT_RULE in reference, "pipeline-config-reference lacks the kept-table sentence"

    # no domain or proper-noun examples in the new sentences
    for text, rule in ((auth, HIDDEN_RULE), (delete, KEPT_RULE), (reference, KEPT_RULE)):
        start = text.index(rule)
        window = text[start:start + len(rule) + 60]
        for bad in ("marketing", "sales", "payroll", "orders", "customers"):
            assert bad not in window, (bad, window)
