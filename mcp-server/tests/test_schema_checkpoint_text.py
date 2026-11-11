"""Story: Assistant preview-and-confirm schema before create_pipeline
(plans/stories/assistant-preview-confirm-schema.md), Acceptance 5 — prose.

Both prompt layers (server.py's MCP instructions / references / tool
descriptions, and the Assistant's SchemaCheckpointRule) must order the work
tap first: create_tap -> test_tap -> propose_pipeline_schema -> confirm ->
create_pipeline(schema_fields) -> update_tap. The "invent a header + 3-5 rows"
sample survives only as the fallback for data without a tap.

Pure file-content checks, modelled on test_tap_test_limit_text.py."""
import os
import re

import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
ASSISTANT_SCALA = os.path.join(
    REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "api", "AssistantAPIController.scala"
)

# "3-5 rows", "3-5 representative rows", "3–5 sample rows", ...
_THREE_FIVE = re.compile(r"3\s*[-–]\s*5\s+(?:\w+\s+)?rows?\b", re.IGNORECASE)
_FALLBACK = re.compile(r"fallback|without a tap|no tap|file in hand|pasted", re.IGNORECASE)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _between(text, start_anchor, end_anchor, start_from=0):
    i = text.index(start_anchor, start_from)
    j = text.find(end_anchor, i + len(start_anchor))
    return text[i:] if j < 0 else text[i:j]


def _instructions():
    return _between(_read(SERVER_PY), '_INSTRUCTIONS_TEMPLATE = """', '\n"""')


def _pipeline_config_reference():
    return _between(_read(SERVER_PY), 'PIPELINE_CONFIG_REFERENCE = """', '\n"""')


def _tap_workflow_reference():
    return _between(_read(SERVER_PY), 'TAP_WORKFLOW_REFERENCE = """', '\n"""')


def _tool_block(name):
    return _between(_read(SERVER_PY), f'name="{name}"', "\n        Tool(")


def _schema_checkpoint_rule():
    src = _read(ASSISTANT_SCALA)
    assert "SchemaCheckpointRule" in src, "AssistantAPIController has no SchemaCheckpointRule"
    i = src.index("val SchemaCheckpointRule")
    j = src.find("private[datris]", i + 1)
    return src[i:] if j < 0 else src[i:j]


def _assert_tap_first(text, where):
    """test_tap, then propose_pipeline_schema, then a create_pipeline that
    carries schema_fields."""
    p = text.find("propose_pipeline_schema")
    assert p >= 0, f"{where} must name propose_pipeline_schema"
    t = text.rfind("test_tap", 0, p)
    assert t >= 0, f"{where}: test_tap must come before propose_pipeline_schema"
    c = text.find("create_pipeline", p)
    assert c >= 0, f"{where}: create_pipeline must come after propose_pipeline_schema"
    assert "schema_fields" in text[p:], f"{where}: schema_fields must be named after propose_pipeline_schema"
    # create_tap is the step before the test.
    assert text.rfind("create_tap", 0, t) >= 0, f"{where}: create_tap must come before test_tap"


def _three_five_hits(text):
    hits = []
    for line in text.splitlines():
        if '{"error"' in line:
            continue  # tool error strings are not prompt text
        for m in _THREE_FIVE.finditer(line):
            window = line[max(0, m.start() - 400): m.end() + 250]
            hits.append((m.group(0), bool(_FALLBACK.search(window)), window))
    return hits


# ----------------------------------------------------------- server.py ---

def test_instructions_order_the_tap_before_the_pipeline():
    _assert_tap_first(_instructions(), "MCP instructions")


def test_tap_workflow_reference_orders_the_tap_before_the_pipeline():
    _assert_tap_first(_tap_workflow_reference(), "datris://tap-workflow-reference")


def test_pipeline_config_reference_names_the_schema_proposal_path():
    ref = _pipeline_config_reference()
    assert "propose_pipeline_schema" in ref, "pipeline-config-reference step 3 must name propose_pipeline_schema"
    assert "schema_fields" in ref, "pipeline-config-reference step 3 must name schema_fields"


def test_create_pipeline_description_names_schema_fields_and_propose():
    block = _tool_block("create_pipeline")
    assert "schema_fields" in block
    assert "propose_pipeline_schema" in block


def test_propose_pipeline_schema_tool_block_says_present_a_table_and_ask():
    block = _tool_block("propose_pipeline_schema")
    assert "schema_fields" in block
    assert re.search(r"\btable\b", block, re.IGNORECASE), "tool block must say present the fields as a table"


@pytest.mark.parametrize("path", [SERVER_PY, ASSISTANT_SCALA], ids=["server.py", "AssistantAPIController.scala"])
def test_three_to_five_rows_appears_only_in_the_fallback_sentence(path):
    hits = _three_five_hits(_read(path))
    bad = [(h, w) for h, ok, w in hits if not ok]
    assert not bad, (
        f"'3-5 rows' must appear only where content_text is described as the fallback for data "
        f"without a tap; found outside it in {os.path.basename(path)}:\n"
        + "\n---\n".join(w for _, w in bad)
    )


def test_list_pipelines_empty_hint_mentions_the_tap_path():
    src = _read(SERVER_PY)
    hint = _between(src, '"No pipelines exist.', "})")
    assert "propose_pipeline_schema" in hint or "schema_fields" in hint, hint


# --------------------------------------------------- Assistant prompt ---

def test_assistant_schema_checkpoint_rule_orders_the_tap_before_the_pipeline():
    rule = _schema_checkpoint_rule()
    p = rule.find("propose_pipeline_schema")
    assert p >= 0, rule
    assert 0 <= rule.find("test_tap") < p, rule
    assert rule.find("create_pipeline", p) > p, rule
    assert "schema_fields" in rule, rule
    assert "update_tap" in rule, rule


def test_assistant_prompt_appends_the_schema_checkpoint_rule():
    src = _read(ASSISTANT_SCALA)
    assert re.search(r"append\(\s*AssistantAPIController\.SchemaCheckpointRule\s*\)", src), (
        "buildSystemPrompt must append SchemaCheckpointRule"
    )
