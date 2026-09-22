"""Story: taps may yield batches (plans/stories/tap-batch-yield.md), the prose
half (story Step 8).

An agent writing a tap over a large columnar or tabular source must be told,
in every channel it reads, to `yield` each BATCH (a pyarrow RecordBatch, a
pandas DataFrame, or a list of dicts) instead of each row, and to read only
the columns the pipeline needs (`columns=[...]`). Left alone, the model
keeps emitting the per-row loop the story measured at ~66,000 rows/s.

Pure file-content checks, modelled on test_tap_memory_text.py: sections are
cut out of server.py by anchor so the word "batch" in server.py's own Python
code can never satisfy a check meant for prose. The batch passages are also
guarded against domain bias — no URL, hostname or file-extension-specific
product name (see the platform's prompt rules).

Every assertion here fails today: no channel mentions batches.
"""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
ASSISTANT_SCALA = os.path.join(REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "api", "AssistantAPIController.scala")
TAPS_MDX = os.path.join(REPO_ROOT, "docs", "taps.mdx")
UTIL_DIR = os.path.join(REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "util")
PROMPT_FILES = [
    os.path.join(UTIL_DIR, f)
    for f in ("TapScriptGenerator.scala", "TapScriptOptimizer.scala", "TapScriptReviewer.scala", "TapScriptFixer.scala")
]

_BATCH = re.compile(r"\bbatch(es)?\b", re.IGNORECASE)
_YIELD = re.compile(r"\byield\b", re.IGNORECASE)
_COLUMNS_ARG = re.compile(r"columns\s*=")

# Domain bias / leakage guards applied to the batch passage only.
_URL = re.compile(r"https?://|\bwww\.", re.IGNORECASE)
_HOSTNAME = re.compile(r"\b[a-z0-9][a-z0-9-]*\.(com|ai|io|org|net|dev|co)\b", re.IGNORECASE)
_FILE_EXT_PRODUCT = re.compile(r"\b(parquet|orc|avro|feather|hdf5|xlsx|sas7bdat|dta)\b", re.IGNORECASE)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _between(text, start_anchor, end_anchor, start_from=0):
    i = text.index(start_anchor, start_from)
    j = text.find(end_anchor, i + len(start_anchor))
    return text[i:] if j < 0 else text[i:j]


def _window(text, *patterns, window=6):
    """The first window of consecutive lines in which every pattern matches, or
    None."""
    lines = text.splitlines()
    for k in range(len(lines)):
        chunk = "\n".join(lines[k:k + window])
        if all(p.search(chunk) for p in patterns):
            return chunk
    return None


def _assert_batch_guidance(text, where, window=6):
    chunk = _window(text, _BATCH, _YIELD, window=window)
    assert chunk, where + " must tell the agent to yield a BATCH (DataFrame / RecordBatch / list of dicts) for a large source"
    assert _COLUMNS_ARG.search(text), where + " must also tell the agent to read only the needed columns (columns=[...])"
    return chunk


def _assert_no_domain_bias(chunk, where):
    for pattern, label in ((_URL, "a URL"), (_HOSTNAME, "a hostname"), (_FILE_EXT_PRODUCT, "a file-extension-specific product name")):
        m = pattern.search(chunk)
        assert not m, "the batch passage in %s leaks %s: %r" % (where, label, m.group(0))


# ------------------------------------------------------------ server.py ---


def _tap_workflow_reference():
    return _between(_read(SERVER_PY), 'TAP_WORKFLOW_REFERENCE = """', '\n"""')


def _tool_block(name):
    return _between(_read(SERVER_PY), f'name="{name}"', "\n        Tool(")


def _instructions_tap_workflow():
    return _between(_read(SERVER_PY), "Tap workflow (for step 3 Option B):", '\n"""')


def test_tap_workflow_reference_tells_the_agent_to_yield_batches():
    ref = _tap_workflow_reference()
    chunk = _assert_batch_guidance(ref, "TAP_WORKFLOW_REFERENCE")
    _assert_no_domain_bias(chunk, "TAP_WORKFLOW_REFERENCE")


def test_server_instructions_tap_workflow_paragraph_mentions_batches():
    para = _instructions_tap_workflow()
    chunk = _assert_batch_guidance(para, "the server.py instructions' tap-workflow paragraph")
    _assert_no_domain_bias(chunk, "the server.py instructions")


def test_create_tap_and_test_tap_descriptions_carry_the_batch_rule():
    for tool in ("create_tap", "test_tap"):
        block = _tool_block(tool)
        chunk = _assert_batch_guidance(block, tool + " description")
        _assert_no_domain_bias(chunk, tool + " description")


# --------------------------------------------------- Assistant prompt ---


def test_assistant_prompt_tells_the_agent_to_yield_batches():
    text = _read(ASSISTANT_SCALA)
    chunk = _assert_batch_guidance(text, "the Assistant prompt (AssistantAPIController.scala)")
    _assert_no_domain_bias(chunk, "the Assistant prompt")


# ---------------------------------------------- generator/fixer prompts ---


def test_script_generation_prompts_restate_the_batch_contract():
    for path in PROMPT_FILES:
        name = os.path.basename(path)
        chunk = _assert_batch_guidance(_read(path), name)
        _assert_no_domain_bias(chunk, name)


# ------------------------------------------------------------------ docs ---


def test_taps_doc_documents_batch_yield():
    text = _read(TAPS_MDX)
    section = _between(text, "## Script Requirements", "\n## ")
    _assert_batch_guidance(section, "docs/taps.mdx Script Requirements")

    table = _between(text, "## Data Types", "\n## ")
    rows = [l for l in table.splitlines() if l.startswith("|")]
    batch_rows = [r for r in rows if _BATCH.search(r)]
    assert batch_rows, "the Data Types table must have a row for a generator yielding batches"
    for r in batch_rows:
        assert re.search(r"row", r, re.IGNORECASE), (
            "the batch row must say each batch is written as its ROWS (count is rows): " + r
        )


def test_per_row_yield_stays_correct_for_small_sources_in_every_channel():
    # The batch rule must not read as "always batch": per-row yield stays
    # correct for small or API-paged sources.
    _KEEP = re.compile(r"(one row at a time|per-row|row at a time|yielding one row)", re.IGNORECASE)
    for path in PROMPT_FILES + [SERVER_PY, ASSISTANT_SCALA, TAPS_MDX]:
        text = _read(path)
        chunk = _window(text, _BATCH, _KEEP, window=8)
        assert chunk, os.path.basename(path) + " must keep saying that yielding one row at a time is still correct for small or API-paged sources"
