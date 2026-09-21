"""Story: MCP and REST tap tests are capped at 20 records like the UI's Test
button (plans/stories/tap-test-limit.md) — the prose half.

Once `/tap/run` with `mode=test` previews only the first 20 records, an agent
that reads a test's `recordCount` as the source's size will lie to the user
("the feed has 20 rows"). Two facts therefore have to reach every channel an
agent reads:

  1. a test previews the first 20 records by default (and `limit` / `testLimit`
     raises it, 0 disables the cap);
  2. a test's `recordCount` is the preview size, NOT what a real run produces.

Pure file-content checks, modelled on test_tap_memory_text.py (`_between`,
`_tool_block`, `_windowed`). `\\b20\\b` deliberately excludes the existing
"a 20-column numeric record" payload-budget sentence, so only new wording
can satisfy these.
"""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
ASSISTANT_SCALA = os.path.join(
    REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "api", "AssistantAPIController.scala"
)
TAPS_MDX = os.path.join(REPO_ROOT, "docs", "taps.mdx")

# "20" as a record cap — never the "20-column numeric record" size example.
_TWENTY = re.compile(r"\b20\b(?!\s*-?\s*column)")
_TEST = re.compile(r"\btest\w*\b", re.IGNORECASE)
_RECORD = re.compile(r"\brecords?\b", re.IGNORECASE)
# "a test's count is a preview, not the run's count".
_NOT_THE_RUN_COUNT = re.compile(r"preview|not (the|what)[^.\n]{0,60}\brun\b", re.IGNORECASE)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _between(text, start_anchor, end_anchor, start_from=0):
    i = text.index(start_anchor, start_from)
    j = text.find(end_anchor, i + len(start_anchor))
    return text[i:] if j < 0 else text[i:j]


def _windowed(text, *patterns, window=6):
    lines = text.splitlines()
    for k in range(len(lines)):
        chunk = "\n".join(lines[k:k + window])
        if all(p.search(chunk) for p in patterns):
            return True
    return False


def _tap_workflow_reference():
    return _between(_read(SERVER_PY), 'TAP_WORKFLOW_REFERENCE = """', '\n"""')


def _tool_block(name):
    return _between(_read(SERVER_PY), f'name="{name}"', "\n        Tool(")


def _instructions_tap_workflow():
    return _between(_read(SERVER_PY), "Tap workflow (for step 3 Option B):", '\n"""')


def _assistant_run_section():
    return _between(_read(ASSISTANT_SCALA), "## Run the tap when you're done", "## Monitor runs to completion")


# ----------------------------------------------------------- server.py ---

def test_test_tap_tool_block_states_the_20_record_cap():
    block = _tool_block("test_tap")
    assert _windowed(block, _TWENTY, _TEST, _RECORD, window=4), (
        "the test_tap tool block must say a test previews the first 20 records by default"
    )
    assert _NOT_THE_RUN_COUNT.search(block), (
        "the test_tap tool block must say a test's recordCount is the preview size, not what run_tap produces"
    )


def test_tap_workflow_reference_states_the_20_record_cap():
    ref = _tap_workflow_reference()
    assert _windowed(ref, _TWENTY, _TEST, _RECORD), (
        "tap-workflow-reference must say a test previews the first 20 records by default"
    )
    assert _NOT_THE_RUN_COUNT.search(ref), (
        "tap-workflow-reference must say a test's recordCount is a preview, not the run's count"
    )


def test_instructions_tap_workflow_paragraph_states_the_20_record_cap():
    para = _instructions_tap_workflow()
    assert _windowed(para, _TWENTY, _TEST, _RECORD), (
        "the instructions' tap-workflow paragraph must say a test previews the first 20 records"
    )
    assert _NOT_THE_RUN_COUNT.search(para), (
        "the instructions' tap-workflow paragraph must say the test count is not the run's count"
    )


# --------------------------------------------------- Assistant prompt ---

def test_assistant_run_section_states_the_20_record_cap():
    section = _assistant_run_section()
    assert _windowed(section, _TWENTY, _TEST, _RECORD, window=4), (
        "the Assistant prompt's 'Run the tap when you're done' section must state the 20-record test cap"
    )
    assert _NOT_THE_RUN_COUNT.search(section), (
        "the Assistant prompt must say a test's recordCount is the preview size, not the run's"
    )


# ------------------------------------------------------------- docs ---

def test_taps_doc_test_limit_paragraph_names_the_agent_channels():
    text = _read(TAPS_MDX)
    para = [l for l in text.splitlines() if "DATRIS_TAP_TEST_LIMIT" in l]
    assert para, "docs/taps.mdx lost its DATRIS_TAP_TEST_LIMIT paragraph"
    joined = "\n".join(para)
    assert re.search(r"test_tap|/tap/run", joined), (
        "the DATRIS_TAP_TEST_LIMIT paragraph still describes the UI Test button only — it must name "
        "`test_tap` / `POST /api/v1/tap/run` with mode=test as capped too: " + joined
    )
