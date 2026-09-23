"""Story: agents size a tap run from measured numbers, not a guess
(plans/stories/tap-sizing-guidance.md) — this story is prose only.

Before an agent builds anything for a large source it must show the user a
four-line estimate (rows, bytes/record, GB staged, minutes) built from two
measured rules, identically in every channel an agent reads:

  1. bytes  ≈ 26 bytes x column count per staged JSON record;
  2. time   ≈ rows / 15,000 per second when fetch() yields one record at a
     time, or rows / 90,000 per second when it yields batches.

Both rates must appear in every channel, and the older 60,000 rows/s figure
(measured on a local sbt run, not in the tap-runner image) must appear in
none of them. The estimate is compared with PIPELINE_MAX_PAYLOAD_MB and
TAP_RUN_TIMEOUT_SECONDS, and going over either stops the agent and offers
three choices — which makes chunking the one size-driven exception to "one
run per requested range", so the exception clause has to be in the same
channels as the rule.

Pure file-content checks, modelled on test_tap_disk_text.py (anchor-cut
sections, windowed regexes) and test_tap_memory_text.py. The denylist sweep
is assembled from string parts so this file never matches its own sweep.
"""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
ASSISTANT_SCALA = os.path.join(
    REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "api", "AssistantAPIController.scala"
)
UTIL_DIR = os.path.join(REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "util")
GENERATOR_SCALA = os.path.join(UTIL_DIR, "TapScriptGenerator.scala")
FIXER_SCALA = os.path.join(UTIL_DIR, "TapScriptFixer.scala")
TAPS_MDX = os.path.join(REPO_ROOT, "docs", "taps.mdx")
CONFIG_REF_MDX = os.path.join(REPO_ROOT, "docs", "configuration-reference.mdx")

PROMPT_FILES = [SERVER_PY, ASSISTANT_SCALA, GENERATOR_SCALA, FIXER_SCALA]

BUDGET_VAR = "PIPELINE_MAX_PAYLOAD_MB"
RUN_VAR = "TAP_RUN_TIMEOUT_SECONDS"

_BYTES_PER_COL = re.compile(r"26\s?(bytes|B)\s?(×|x|\*)\s?(column|col)", re.IGNORECASE)
_ROWS_PER_SEC = re.compile(r"15,?000\s?(rows)?\s?(per second|/\s?s|rows/s)", re.IGNORECASE)
_BATCH_ROWS_PER_SEC = re.compile(r"90,?000\s?(rows)?\s?(per second|/\s?s|rows/s)", re.IGNORECASE)
# The superseded throughput figure (local sbt run, not the runner image).
_STALE_RATE = re.compile(r"60,?000|\b60k\b", re.IGNORECASE)
_CHUNK_EXCEPTION = re.compile(r"(unless|only .{0,40}exception).{0,120}(estimate|budget)", re.IGNORECASE | re.DOTALL)
_TABLE = re.compile(r"rows,? bytes/record,? GB.{0,20}minutes", re.IGNORECASE)

# Guessed figures this story replaces (en dash or hyphen).
_GUESSED = [
    ("250-400 bytes", re.compile(r"250\s*[–-]\s*400")),
    ("300-400 bytes", re.compile(r"300\s*[–-]\s*400")),
    ("a 20-column numeric record", re.compile(r"20-column numeric record", re.IGNORECASE)),
]

# Example domains an LLM reaches for first, assembled from parts so this file
# never matches its own sweep. `round-trip` is plain English (server.py) and
# is excluded.
_DENY = re.compile(
    r"\b(" + "|".join(["ta" + "xi", "tick" + "er", "tick" + "ers", "weat" + "her", "cry" + "pto", "co" + "vid"]) + r")\b"
    r"|(?<!round-)\btri" + r"ps?\b",
    re.IGNORECASE,
)


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


# ------------------------------------------------------- section cutters ---

def _assistant_run_section():
    return _between(
        _read(ASSISTANT_SCALA),
        '## Run the tap when you\'re done',
        '## Monitor runs to completion',
    )


def _tap_workflow_reference():
    return _between(_read(SERVER_PY), 'TAP_WORKFLOW_REFERENCE = """', '\n"""')


def _tool_block(name):
    return _between(_read(SERVER_PY), f'name="{name}"', "\n        Tool(")


def _instructions_tap_workflow():
    return _between(_read(SERVER_PY), "Tap workflow (for step 3 Option B):", '\n"""')


def _assert_both_rates(section, where):
    assert _ROWS_PER_SEC.search(section), where + " must state the ~15,000 rows/s per-row rate"
    assert _BATCH_ROWS_PER_SEC.search(section), where + " must state the ~90,000 rows/s batch rate"
    assert not _STALE_RATE.search(section), where + " still carries the superseded 60,000 rows/s figure"


# ------------------------------------------------------ Assistant prompt ---

def test_assistant_prompt_carries_both_rules_and_the_table():
    section = _assistant_run_section()
    where = "the Assistant prompt's 'Run the tap when you're done' section"
    assert _BYTES_PER_COL.search(section), where + " must state ~26 bytes x column count per staged record"
    _assert_both_rates(section, where)
    assert BUDGET_VAR in section, where + " must compare the disk estimate with " + BUDGET_VAR
    assert RUN_VAR in section, where + " must compare the time estimate with " + RUN_VAR
    assert _TABLE.search(section), where + " must ask for the rows / bytes per record / GB staged / minutes table"
    assert _CHUNK_EXCEPTION.search(section), where + " must make chunking the exception when the estimate exceeds a budget"
    # Kept verbatim (test_payload_budget_text.py also asserts this).
    assert "Never split a source for size" in _read(ASSISTANT_SCALA)


# ------------------------------------------------- tap-workflow reference ---

def test_tap_workflow_reference_carries_both_rules_and_the_timeout_cause():
    ref = _tap_workflow_reference()
    where = "server.py TAP_WORKFLOW_REFERENCE"
    assert _BYTES_PER_COL.search(ref), where + " must state ~26 bytes x column count per staged record"
    _assert_both_rates(ref, where)
    assert BUDGET_VAR in ref and RUN_VAR in ref, where + " must name both budget variables"
    assert _TABLE.search(ref), where + " must ask for the rows / bytes per record / GB staged / minutes table"
    assert _CHUNK_EXCEPTION.search(ref), where + " must make chunking the exception when the estimate exceeds a budget"
    causes = _between(_read(SERVER_PY), "### Common `run_error` causes", "\n---")
    assert RUN_VAR in causes, "the `run_error` cause list must name " + RUN_VAR + " next to the disk-budget cause"


# ------------------------------------------------------ tool descriptions ---

def test_create_tap_and_run_tap_descriptions_carry_both_rules():
    for tool in ("create_tap", "run_tap"):
        block = _tool_block(tool)
        where = tool + " description"
        assert _BYTES_PER_COL.search(block), where + " must state ~26 bytes x column count per staged record"
        _assert_both_rates(block, where)
        assert BUDGET_VAR in block, where + " must name " + BUDGET_VAR
        assert RUN_VAR in block, where + " must name " + RUN_VAR
        assert _CHUNK_EXCEPTION.search(block), where + " must carry the chunk-as-exception clause"
    # Kept verbatim (test_payload_budget_text.py also asserts this).
    assert "Do NOT split a source" in _tool_block("run_tap")


# ------------------------------------------------------ server instructions ---

def test_server_instructions_point_at_sizing():
    para = _instructions_tap_workflow()
    where = "the server.py instructions' tap-workflow paragraph"
    assert _windowed(
        para, _BYTES_PER_COL, _ROWS_PER_SEC, _BATCH_ROWS_PER_SEC,
        re.compile(BUDGET_VAR), re.compile(RUN_VAR), window=3,
    ), where + " must carry both sizing rules and both budget variables in one 3-line window"
    assert not _STALE_RATE.search(para), where + " still carries the superseded 60,000 rows/s figure"


# -------------------------------------------------- script-generator prompt ---

def test_generator_prompt_asks_for_column_projection():
    memory = _between(_read(GENERATOR_SCALA), "Memory — IMPORTANT for large sources:", "Disk — stream the source")
    assert re.search(r"only the columns|project", memory, re.IGNORECASE), (
        "TapScriptGenerator's Memory block must tell the script to read only the columns the destination needs"
    )
    assert re.search(r"columns\s*=|SELECT", memory), (
        "the column-projection bullet must show the mechanism (columns=[...] / a SELECT list / an API field filter)"
    )
    # The script-writing prompt gets the projection bullet only — no estimate table.
    gen = _read(GENERATOR_SCALA)
    assert not _TABLE.search(gen), "TapScriptGenerator must not carry the estimate table (it writes scripts, not estimates)"


# ------------------------------------------------------------------- docs ---

def test_docs_carry_the_rule():
    taps = _read(TAPS_MDX)
    guard = _between(taps, "### Output Size Guard", "\n## ")
    assert _BYTES_PER_COL.search(guard), "docs/taps.mdx Output Size Guard must state ~26 bytes x column count per record"
    reqs = _between(taps, "## Script Requirements", "\n## ")
    assert _BYTES_PER_COL.search(reqs), "docs/taps.mdx Script Requirements must state ~26 bytes x column count per record"
    _assert_both_rates(reqs, "docs/taps.mdx Script Requirements")
    assert _TABLE.search(reqs), "docs/taps.mdx Script Requirements must show the rows / bytes per record / GB staged / minutes table"

    config = _read(CONFIG_REF_MDX)
    for key in ("pipelineMaxPayloadMB", BUDGET_VAR):
        rows = [l for l in config.splitlines() if l.startswith(f"| `{key}`")]
        assert rows, f"no `{key}` row in docs/configuration-reference.mdx"
        assert _BYTES_PER_COL.search(rows[0]), (
            f"the `{key}` row must carry the bytes-per-column rule so an operator can size the variable: " + rows[0]
        )


# ------------------------------------------------------------- old guesses ---

def test_no_guessed_figure_remains():
    for path in PROMPT_FILES + [TAPS_MDX, CONFIG_REF_MDX]:
        text = _read(path)
        name = os.path.relpath(path, REPO_ROOT)
        for label, rx in _GUESSED:
            assert not rx.search(text), f"{name} still carries the guessed figure {label}"


# ---------------------------------------------------------------- denylist ---

def test_prompt_files_carry_no_domain_words():
    for path in PROMPT_FILES:
        text = _read(path)
        name = os.path.relpath(path, REPO_ROOT)
        for i, line in enumerate(text.splitlines(), 1):
            m = _DENY.search(line)
            assert not m, f"{name}:{i} names an example domain ({m.group(0)!r}) — prompts stay domain-free: {line.strip()[:120]}"
