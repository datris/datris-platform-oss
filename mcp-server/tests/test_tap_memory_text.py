"""Story: taps survive large sources (plans/stories/tap-large-sources.md),
the prose half (story Step 9).

A tap that materialises 3.5M rows is SIGKILLed; the runner reports exit
code -9 with no hint. Left alone, an agent reads that as "something in the
environment", starts probing the runner (printing os.environ, reading the
wrapper source, pip-listing) and never rewrites fetch() to yield. The agent-
facing text must carry three rules, in both channels an agent reads:

  1. yield-on-kill: exit -9 / "killed" means out of memory; rewrite fetch()
     to `yield` records (or return an iterator) instead of building a list;
  2. two-failures-then-report: after two consecutive failed tests, stop and
     report the error text to the user instead of iterating blind;
  3. no probing: never probe the runner environment or read the wrapper.

Pure file-content checks, modelled on test_payload_budget_text.py. Sections
are cut out of server.py by anchor so the `yield` keyword in server.py's own
Python code never satisfies a check meant for prose.
"""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
ASSISTANT_SCALA = os.path.join(REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "api", "AssistantAPIController.scala")
TAPS_MDX = os.path.join(REPO_ROOT, "docs", "taps.mdx")
UTIL_DIR = os.path.join(REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "util")
PROMPT_FILES = [os.path.join(UTIL_DIR, f) for f in ("TapScriptGenerator.scala", "TapScriptOptimizer.scala", "TapScriptReviewer.scala", "TapScriptFixer.scala")]

# Rule 1 — killed for memory → yield. Both halves must sit in the same
# sentence-ish window so a stray "yield" elsewhere cannot satisfy it.
_KILLED = re.compile(r"(exit code -9|\b-9\b|killed)", re.IGNORECASE)
_MEMORY = re.compile(r"memory", re.IGNORECASE)
_YIELD = re.compile(r"\byield\b", re.IGNORECASE)
# Rule 2 — two consecutive failed tests → stop and report to the user.
_TWO_FAILURES = re.compile(r"two consecutive", re.IGNORECASE)
_REPORT = re.compile(r"\b(report|tell|show)\b.*\buser\b|\buser\b.*\b(report|tell|show)\b", re.IGNORECASE)
# Rule 3 — never probe the runner / read the wrapper.
_NO_PROBE = re.compile(
    r"(never|do not|don't|must not)\b[^.\n]{0,80}\b(probe|inspect|explore|introspect|reverse-engineer|read)\b[^.\n]{0,60}\b(runner|wrapper|environment)",
    re.IGNORECASE,
)
# Wording that INVITES probing. Assembled from parts so this file never matches itself.
_PROBE_INVITES = re.compile(
    "|".join([
        r"pip (list|freeze)",
        r"sys\.modules",
        r"print\(os\.environ\)",
        r"print\(dict\(os\.environ\)\)",
        r"(explore|investigate|inspect) (the )?(runner|sandbox) (env|environment)",
        r"see what('s| is) (installed|available)",
        r"check (what|which) (packages|modules) (are|is) (installed|available)",
    ]),
    re.IGNORECASE,
)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _between(text, start_anchor, end_anchor, start_from=0):
    """The text from `start_anchor` up to the next `end_anchor` (or EOF when the
    section is the file's last one)."""
    i = text.index(start_anchor, start_from)
    j = text.find(end_anchor, i + len(start_anchor))
    return text[i:] if j < 0 else text[i:j]


def _windowed(text, *patterns, window=6):
    """True when every pattern matches inside some window of consecutive lines."""
    lines = text.splitlines()
    for k in range(len(lines)):
        chunk = "\n".join(lines[k:k + window])
        if all(p.search(chunk) for p in patterns):
            return True
    return False


# ------------------------------------------------------------ server.py ---

def _tap_workflow_reference():
    return _between(_read(SERVER_PY), 'TAP_WORKFLOW_REFERENCE = """', '\n"""')


def _tool_block(name):
    text = _read(SERVER_PY)
    return _between(text, f'name="{name}"', "\n        Tool(")


def _instructions_tap_workflow():
    text = _read(SERVER_PY)
    return _between(text, "Tap workflow (for step 3 Option B):", '\n"""')


def test_tap_workflow_reference_states_yield_on_kill():
    ref = _tap_workflow_reference()
    assert _windowed(ref, _KILLED, _MEMORY, _YIELD), (
        "tap-workflow-reference must say that exit -9 / killed means out of memory and that fetch() should yield records"
    )
    # The contract line ("fetch() ... returning one of") must admit an iterator/generator.
    contract = _between(ref, "The script MUST define `fetch()`", "\n\n")
    assert re.search(r"yield|generator|iterator", contract, re.IGNORECASE), (
        "the fetch() contract must say fetch() may yield records / return an iterator: " + contract
    )


def test_create_tap_and_test_tap_descriptions_carry_the_yield_rule():
    create = _tool_block("create_tap")
    assert _YIELD.search(create), "create_tap description must tell the agent to yield records for large sources"
    assert _MEMORY.search(create), "create_tap description must name memory as the reason to yield"
    test = _tool_block("test_tap")
    assert _windowed(test, _KILLED, _MEMORY, _YIELD, window=4), (
        "test_tap description must explain that exit -9 / killed is out of memory and the fix is to yield"
    )


def test_server_instructions_carry_the_two_failures_then_report_rule():
    text = _read(SERVER_PY)
    assert _windowed(text, _TWO_FAILURES, _REPORT, window=3), (
        "server.py must tell the agent: after two consecutive failed tests, stop and report the error text to the user"
    )


def test_server_instructions_forbid_probing_and_never_invite_it():
    text = _read(SERVER_PY)
    assert _NO_PROBE.search(text), "server.py must tell the agent never to probe the runner environment / read the wrapper"
    m = _PROBE_INVITES.search(text)
    assert not m, "server.py invites environment probing: %r" % m.group(0)


def test_instructions_tap_workflow_paragraph_mentions_streaming_fetch():
    para = _instructions_tap_workflow()
    assert re.search(r"yield|generator|iterator", para, re.IGNORECASE), (
        "the instructions' tap-workflow paragraph still says fetch() returns a list of dicts only"
    )


# --------------------------------------------------- Assistant prompt ---

def _assistant_run_section():
    text = _read(ASSISTANT_SCALA)
    return _between(text, "## Run the tap when you're done", "## Monitor runs to completion")


def test_assistant_prompt_states_yield_on_kill_after_the_never_split_bullet():
    section = _assistant_run_section()
    assert "Never split a source for size" in section, "the Phase 5 bullet must stay"
    after = section[section.index("Never split a source for size"):]
    assert _windowed(after, _KILLED, _MEMORY, _YIELD, window=4), (
        "the Assistant prompt must say a -9 / killed run was out of memory and fetch() must yield, right after the never-split bullet"
    )


def test_assistant_prompt_states_two_failures_then_report():
    text = _read(ASSISTANT_SCALA)
    assert _windowed(text, _TWO_FAILURES, _REPORT, window=3), (
        "the Assistant prompt must say: after two consecutive failed tests, stop and report the error text to the user"
    )


def test_assistant_prompt_forbids_probing_and_never_invites_it():
    text = _read(ASSISTANT_SCALA)
    assert _NO_PROBE.search(text), "the Assistant prompt must tell the agent never to probe the runner environment / read the wrapper"
    m = _PROBE_INVITES.search(text)
    assert not m, "AssistantAPIController.scala invites environment probing: %r" % m.group(0)


# ---------------------------------------------- generator/fixer prompts ---

def test_script_generation_prompts_restate_the_yield_contract():
    # Step 9: generator, optimizer, reviewer and fixer all restate "for large
    # sources, yield records" — otherwise the AI keeps emitting list builders.
    for path in PROMPT_FILES:
        text = _read(path)
        assert _YIELD.search(text), os.path.basename(path) + " never tells the model that fetch() may yield records"


# ------------------------------------------------------------------ docs ---

def test_taps_doc_script_requirements_allow_yield():
    text = _read(TAPS_MDX)
    section = _between(text, "## Script Requirements", "\n## ")
    assert re.search(r"yield|generator|iterator", section, re.IGNORECASE), (
        "docs/taps.mdx Script Requirements still says fetch() must return a list only"
    )


def test_taps_doc_data_types_table_no_longer_claims_flat_dicts_are_csv():
    text = _read(TAPS_MDX)
    table = _between(text, "## Data Types", "\n## ")
    rows = [l for l in table.splitlines() if l.startswith("|")]
    flat = [r for r in rows if re.search(r"flat dicts", r, re.IGNORECASE)]
    assert flat, "the Data Types table lost its list-of-dicts row"
    for r in flat:
        assert not re.search(r"\*\*CSV\*\*", r), "a list of dicts is typed json by the wrapper, the table says CSV: " + r
    assert any(re.search(r"yield|generator|iterator", r, re.IGNORECASE) for r in rows), (
        "the Data Types table must have a row for a generator / iterator of records"
    )


def test_taps_doc_test_limit_note_mentions_the_iterator_cap():
    text = _read(TAPS_MDX)
    paras = [p for p in text.split("\n\n") if "DATRIS_TAP_TEST_LIMIT" in p]
    assert paras, "docs/taps.mdx no longer documents DATRIS_TAP_TEST_LIMIT"
    assert any(re.search(r"yield|generator|iterator", p, re.IGNORECASE) for p in paras), (
        "the DATRIS_TAP_TEST_LIMIT note must say the wrapper caps a generator at N (and never truncates a list)"
    )
