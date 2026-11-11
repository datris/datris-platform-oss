"""Story: Assistant checkpoint-2 cards: propose and confirm data-quality checks
and transformations (plans/stories/assistant-rule-proposal-cards.md),
Step 9, Acceptance bullet 6 — prose for external MCP clients.

External MCP clients get no card: in the Tap workflow (MCP instructions),
datris://tap-workflow-reference and datris://pipeline-config-reference the
agent must propose the checks / transformations as a numbered list, one per
line, ask the user to confirm, remove or edit them before create_pipeline, and
pass only the confirmed list. The codegen_rule / codegen_transform property
descriptions lose the free-form quoted "Example: '...'" instruction the model
copies verbatim.

Pure file-content checks, modelled on test_schema_checkpoint_text.py."""
import os
import re

import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")

_LIST = re.compile(r"numbered list|one per line", re.IGNORECASE)
_CONFIRM = re.compile(r"\bconfirm", re.IGNORECASE)
_CONFIRMED_LIST = re.compile(r"confirmed (?:list|checks|transformations|items)", re.IGNORECASE)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _between(text, start_anchor, end_anchor, start_from=0):
    i = text.index(start_anchor, start_from)
    j = text.find(end_anchor, i + len(start_anchor))
    return text[i:] if j < 0 else text[i:j]


def _tap_workflow():
    """The 'Tap workflow' section of the MCP instructions, up to the next
    top-level paragraph."""
    instructions = _between(_read(SERVER_PY), '_INSTRUCTIONS_TEMPLATE = """', '\n"""')
    return _between(instructions, "Tap workflow (for step 3 Option B):", "\nLong-form references")


def _tap_workflow_reference():
    return _between(_read(SERVER_PY), 'TAP_WORKFLOW_REFERENCE = """', '\n"""')


def _pipeline_config_reference():
    return _between(_read(SERVER_PY), 'PIPELINE_CONFIG_REFERENCE = """', '\n"""')


def _property_description(prop):
    src = _read(SERVER_PY)
    block = _between(src, f'"{prop}": {{', "\n                    },")
    m = re.search(r'"description":\s*"((?:[^"\\]|\\.)*)"', block)
    assert m, f"no description on create_pipeline property {prop}"
    return m.group(1)


def _assert_confirm_list_before_create(text, where):
    m = _LIST.search(text)
    assert m, f"{where} must say to propose the items as a numbered list, one per line"
    window = text[max(0, m.start() - 400): m.end() + 600]
    assert _CONFIRM.search(window), f"{where}: the numbered list must be confirmed by the user:\n{window}"
    c = text.find("create_pipeline", m.end())
    assert c >= 0 and c - m.end() < 600, (
        f"{where}: the list must be confirmed before create_pipeline (create_pipeline named right after it):\n{window}"
    )
    assert _CONFIRMED_LIST.search(text[m.start():]), f"{where} must say to pass only the confirmed list"
    for token in ("codegen_rule", "codegen_transform"):
        assert token in text, f"{where} must name {token}"


def test_tap_workflow_confirms_the_list_before_create_pipeline():
    _assert_confirm_list_before_create(_tap_workflow(), "MCP instructions Tap workflow")


def test_tap_workflow_reference_confirms_the_list_before_create_pipeline():
    _assert_confirm_list_before_create(_tap_workflow_reference(), "datris://tap-workflow-reference")


def test_pipeline_config_reference_confirms_the_list_before_create_pipeline():
    ref = _pipeline_config_reference()
    dq = _between(ref, "## Data Quality", "\n## Transformation")
    tr = _between(ref, "## Transformation", "\n## ")
    assert _LIST.search(dq), f"pipeline-config-reference Data Quality section must say numbered list / one per line:\n{dq}"
    assert _LIST.search(tr), f"pipeline-config-reference Transformation section must say numbered list / one per line:\n{tr}"
    for section, where in ((dq, "Data Quality"), (tr, "Transformation")):
        assert _CONFIRM.search(section), f"pipeline-config-reference {where}: user must confirm the list"
        assert "create_pipeline" in section[_LIST.search(section).end():], (
            f"pipeline-config-reference {where}: confirm before create_pipeline"
        )
        assert _CONFIRMED_LIST.search(section), f"pipeline-config-reference {where}: pass only the confirmed list"


@pytest.mark.parametrize("prop", ["codegen_rule", "codegen_transform"])
def test_codegen_descriptions_carry_no_quoted_example_instruction(prop):
    desc = _property_description(prop)
    assert "Example: 'Validate" not in desc, f"{prop} description still carries the 'Validate ...' example: {desc}"
    assert "Example: '" not in desc, f"{prop} description still carries a quoted free-form example instruction: {desc}"


# ---------------------------------------------------------------------------
# Story: Assistant data-quality card: the user chooses warn-only vs reject on
# failure (plans/stories/assistant-dq-card-on-failure.md), Acceptance bullet 2.
# In the sentence that asks the user to confirm the list, external MCP clients
# are also told to ask warn-only vs reject and pass `codegen_rule_on_failure`.

_WARN = re.compile(r"\bwarn", re.IGNORECASE)


def _assert_on_failure_after_confirm(text, where):
    m = _LIST.search(text)
    assert m, f"{where} must have the confirm-the-list sentence"
    window = text[m.start(): m.end() + 600]
    assert "codegen_rule_on_failure" in window, (
        f"{where} must name codegen_rule_on_failure within 600 chars after the confirm-the-list sentence:\n{window}"
    )
    assert _WARN.search(window), f"{where} must say warn (warn only vs reject) next to the confirmation:\n{window}"


def test_tap_workflow_asks_warn_or_reject_with_the_confirmation():
    _assert_on_failure_after_confirm(_tap_workflow(), "MCP instructions Tap workflow")


def test_tap_workflow_reference_asks_warn_or_reject_with_the_confirmation():
    _assert_on_failure_after_confirm(_tap_workflow_reference(), "datris://tap-workflow-reference")


def test_pipeline_config_reference_data_quality_asks_warn_or_reject_with_the_confirmation():
    dq = _between(_pipeline_config_reference(), "## Data Quality", "\n## Transformation")
    _assert_on_failure_after_confirm(dq, "pipeline-config-reference Data Quality section")


def test_codegen_rule_description_names_codegen_rule_on_failure():
    desc = _property_description("codegen_rule")
    assert "codegen_rule_on_failure" in desc, f"codegen_rule description must name its companion arg: {desc}"


# ---------------------------------------------------------------------------
# Story: Assistant: vector destinations skip checkpoint 2
# (plans/stories/assistant-vector-skip-checkpoint-2.md), Acceptance bullet 3.

_SKIP_CP2 = re.compile(r"(?i)skip checkpoint 2|no checkpoint 2|skips? (both|the follow-up)")


def _assert_vector_skips_checkpoint_2(text, where):
    for m in _SKIP_CP2.finditer(text):
        if "vector" in text[max(0, m.start() - 400): m.start()].lower():
            return
    raise AssertionError(
        f"{where}: 'vector' must appear within 400 chars before a skip-checkpoint-2 phrase "
        f"(skip checkpoint 2 / no checkpoint 2 / skip both / skip the follow-up)"
    )


def test_tap_workflow_vector_skips_checkpoint_2():
    _assert_vector_skips_checkpoint_2(_tap_workflow(), "MCP instructions Tap workflow")


def test_tap_workflow_reference_vector_skips_checkpoint_2():
    _assert_vector_skips_checkpoint_2(_tap_workflow_reference(), "datris://tap-workflow-reference")


def test_pipeline_config_reference_vector_skips_checkpoint_2():
    _assert_vector_skips_checkpoint_2(_pipeline_config_reference(), "datris://pipeline-config-reference")


@pytest.mark.parametrize("heading,end", [("## Data Quality", "\n## Transformation"), ("## Transformation", "\n## ")])
def test_pipeline_config_reference_dq_and_transform_not_for_vector(heading, end):
    section = _between(_pipeline_config_reference(), heading, end)
    assert "Not for vector destinations" in section, f"{heading} section must say 'Not for vector destinations':\n{section}"


def test_propose_pipeline_schema_description_names_checks_transformations_or_protection():
    src = _read(SERVER_PY)
    block = _between(src, 'name="propose_pipeline_schema"', "inputSchema=")
    assert "checks, transformations or protection" in block, block
