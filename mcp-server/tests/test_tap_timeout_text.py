"""Story: taps get a run timeout separate from the test ceiling, and the
timeout error names its knob (plans/stories/tap-run-timeout.md) — the prose
half.

Two ceilings only help if an operator can find them: `TAP_SCRIPT_TIMEOUT_SECONDS`
(tests, 300 s) and `TAP_RUN_TIMEOUT_SECONDS` (real and cron runs, 3600 s) have
to be forwarded by compose, listed in `.env.example`, documented, and named in
the agent-facing prose — otherwise an agent still reads "tapScriptTimeoutSeconds
is the ceiling, chunk the source" and chunks a run that now has an hour.

Pure file-content checks, modelled on test_payload_budget_text.py.
"""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
ASSISTANT_SCALA = os.path.join(
    REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "api", "AssistantAPIController.scala"
)
ENV_EXAMPLE = os.path.join(REPO_ROOT, ".env.example")
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
COMPOSE_STANDALONE = os.path.join(REPO_ROOT, "docker-compose.standalone.yml")
CONFIG_REF_MDX = os.path.join(REPO_ROOT, "docs", "configuration-reference.mdx")
TAPS_MDX = os.path.join(REPO_ROOT, "docs", "taps.mdx")
HTTP_CONTRACT_MDX = os.path.join(REPO_ROOT, "docs", "tap-http-contract.mdx")
TAP_CREATE_TS = os.path.join(REPO_ROOT, "ui", "src", "app", "tap-create", "tap-create.component.ts")

TEST_VAR = "TAP_SCRIPT_TIMEOUT_SECONDS"
RUN_VAR = "TAP_RUN_TIMEOUT_SECONDS"

ALL_FILES = [ENV_EXAMPLE, COMPOSE, COMPOSE_STANDALONE, CONFIG_REF_MDX, TAPS_MDX, HTTP_CONTRACT_MDX, SERVER_PY, ASSISTANT_SCALA]

# The old single-ceiling claim, assembled from parts so this file never matches
# its own sweep.
_OLD_RUN_CEILING = re.compile(r"ran longer than[^.\n]{0,40}" + "tapScriptTimeoutSeconds")

# No domain bias in the new text (feedback_prompt_no_domain_bias): a timeout
# example must never name a real source, vendor or place.
_BANNED = re.compile(
    r"\b(salesforce|hubspot|stripe|shopify|zendesk|jira|github api|weather|nasdaq|yahoo|bloomberg|"
    r"new york|london|california)\b",
    re.IGNORECASE,
)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _lines_with(path, needle):
    return [l for l in _read(path).splitlines() if needle in l]


# ------------------------------------------------------------ every file ---

def test_every_channel_names_both_ceilings():
    for path in ALL_FILES:
        text = _read(path)
        name = os.path.relpath(path, REPO_ROOT)
        assert TEST_VAR in text, f"{name} never names {TEST_VAR}"
        assert RUN_VAR in text, f"{name} never names {RUN_VAR}"


def test_new_timeout_text_names_no_source_vendor_or_place():
    for path in ALL_FILES:
        for line in _lines_with(path, RUN_VAR) + _lines_with(path, TEST_VAR):
            assert not _BANNED.search(line), f"{os.path.relpath(path, REPO_ROOT)} names a specific source/vendor: {line}"


# ------------------------------------------------------------ MCP server ---

def test_mcp_server_no_longer_calls_the_test_ceiling_the_run_ceiling():
    text = _read(SERVER_PY)
    assert not _OLD_RUN_CEILING.search(text), (
        "server.py still tells agents a run is bounded by tapScriptTimeoutSeconds"
    )
    assert "tapScriptTimeoutSeconds" not in text, (
        "agent prose must name the container variables (TAP_SCRIPT_TIMEOUT_SECONDS / "
        "TAP_RUN_TIMEOUT_SECONDS), not the internal property name"
    )


def test_run_tap_description_points_at_the_run_ceiling():
    text = _read(SERVER_PY)
    i = text.index('name="run_tap"')
    block = text[i:text.find("\n        Tool(", i)]
    assert RUN_VAR in block, "the run_tap tool description must name TAP_RUN_TIMEOUT_SECONDS where it names PIPELINE_MAX_PAYLOAD_MB"


def test_test_tap_description_points_at_the_test_ceiling():
    text = _read(SERVER_PY)
    i = text.index('name="test_tap"')
    block = text[i:text.find("\n        Tool(", i)]
    assert TEST_VAR in block, "the test_tap tool description must say a test is bounded by TAP_SCRIPT_TIMEOUT_SECONDS"


def test_assistant_prompt_carries_the_time_budget():
    text = _read(ASSISTANT_SCALA)
    assert RUN_VAR in text
    # The sizing paragraph must offer raising the knob as an alternative to
    # chunking, not chunking alone.
    assert re.search(r"raise\s+" + RUN_VAR, text), "the Assistant prompt must offer raising TAP_RUN_TIMEOUT_SECONDS"


# ---------------------------------------------------------------- compose ---

def test_compose_forwards_both_vars_with_their_defaults():
    for path in (COMPOSE, COMPOSE_STANDALONE):
        text = _read(path)
        name = os.path.basename(path)
        assert re.search(r"\$\{" + TEST_VAR + r":-300\}", text), f"{name} must forward {TEST_VAR} defaulting to 300"
        # Forwarded without a default: an unset run ceiling must reach the
        # server as "unset" so it resolves to max(3600, tapScriptTimeoutSeconds)
        # rather than being pinned to 3600 inside the container.
        assert re.search(r"\$\{" + RUN_VAR + r":-\}", text), f"{name} must forward {RUN_VAR} without a default"


def test_env_example_documents_both_vars_and_the_test_vs_run_distinction():
    text = _read(ENV_EXAMPLE)
    i = min(text.index(TEST_VAR), text.index(RUN_VAR))
    block = text[max(0, i - 600):i + 600]
    assert "300" in block and "3600" in block, ".env.example must state both defaults"
    assert re.search(r"\btest\b", block, re.IGNORECASE) and re.search(r"\brun\b", block, re.IGNORECASE), (
        ".env.example must distinguish a test from a real run"
    )


# ------------------------------------------------------------------ docs ---

def test_configuration_reference_has_rows_for_both_vars_and_properties():
    text = _read(CONFIG_REF_MDX)
    for var in (TEST_VAR, RUN_VAR):
        assert [l for l in text.splitlines() if l.startswith(f"| `{var}`")], f"no `{var}` row in the container env table"
    prop_rows = [l for l in text.splitlines() if l.startswith("| `tapRunTimeoutSeconds`")]
    assert prop_rows, "no `tapRunTimeoutSeconds` row in the property table"
    assert "3600" in prop_rows[0], "the tapRunTimeoutSeconds row must state its default"
    script_rows = [l for l in text.splitlines() if l.startswith("| `tapScriptTimeoutSeconds`")]
    assert script_rows and re.search(r"\btest\b", script_rows[0], re.IGNORECASE), (
        "the tapScriptTimeoutSeconds row must be reworded as the test ceiling: " + str(script_rows)
    )


def test_taps_doc_configuration_table_lists_both_vars_from_env():
    text = _read(TAPS_MDX)
    for var, prop in ((TEST_VAR, "tapScriptTimeoutSeconds"), (RUN_VAR, "tapRunTimeoutSeconds")):
        rows = [l for l in text.splitlines() if l.startswith("|") and (var in l or f"`{prop}`" in l)]
        assert rows, f"docs/taps.mdx configuration table has no row for {prop}"
        assert any(".env" in l for l in rows), f"the {prop} row must say the value comes from `.env`"


def test_http_contract_doc_states_both_ceilings():
    text = _read(HTTP_CONTRACT_MDX)
    assert "3600" in text, "docs/tap-http-contract.mdx must state the 3600 s real-run ceiling"
    assert "300" in text, "docs/tap-http-contract.mdx must keep the 300 s test ceiling"


# -------------------------------------------------------------------- UI ---

def test_ui_shows_the_servers_message_not_a_hardcoded_five_minutes():
    text = _read(TAP_CREATE_TS)
    assert "5 minute limit" not in text, (
        "tap-create.component.ts still hardcodes '(5 minute limit)' — the server's message now names the variable"
    )


# ===========================================================================
# Story: tap timeouts keep the script's logs, report progress and a partial
# record count (plans/stories/tap-timeout-diagnostics.md) — the prose half.
#
# A timeout response now carries the script's own logs, its
# `[wrapper] streamed N records` progress lines and a partial record count.
# Every agent-facing channel has to say so AND say what to conclude from it,
# or an agent that sees "timed out" still rewrites a healthy script. One rule,
# in the same sentence window as the timeout mention:
#
#   "A run that timed out returns the script's logs, its `[wrapper] streamed N
#    records` progress lines and a partial record count. Read them first: a
#    script that reached the source and was still streaming records when the
#    timeout hit is healthy and too long, not wrong — do not rewrite it; ..."
#
# The existing sweeps above (both ceilings named everywhere, no domain bias)
# and test_tap_memory_text.py / test_tap_disk_text.py must stay green.
# ===========================================================================

_DIAG_WINDOW = 900  # chars either side of the timeout mention: one rule, one place

_TIMEOUT_MENTION = re.compile(r"tim(?:ed|es)?\s*out|timeout", re.IGNORECASE)
_LOGS = re.compile(r"\blogs?\b", re.IGNORECASE)
_PROGRESS = re.compile(r"\bprogress\b|\bstreamed\b", re.IGNORECASE)
_PARTIAL_COUNT = re.compile(r"\bpartial\b", re.IGNORECASE)
_READ_FIRST = re.compile(r"read\b[^.]{0,80}\b(first|before)\b|before\b[^.]{0,80}\bconclud", re.IGNORECASE)
_HEALTHY = re.compile(r"\bhealthy\b|\btoo long\b|\bnot wrong\b", re.IGNORECASE)

_DIAG_CHECKS = (
    ("the script's logs", _LOGS),
    ("the progress / streamed lines", _PROGRESS),
    ("the partial record count", _PARTIAL_COUNT),
    ("an instruction to read them first", _READ_FIRST),
    ("a healthy / too long / not wrong verdict", _HEALTHY),
)


def _block(text, anchor):
    """The triple-quoted block that starts at `anchor`."""
    i = text.index(anchor)
    j = text.index('\n"""', i)
    return text[i:j]


def _tool_block(text, tool_name):
    i = text.index(f'name="{tool_name}"')
    return text[i:text.find("\n        Tool(", i)]


def _assert_timeout_diagnostics_rule(region, where):
    missing_by_window = []
    for m in _TIMEOUT_MENTION.finditer(region):
        window = region[max(0, m.start() - _DIAG_WINDOW):m.end() + _DIAG_WINDOW]
        missing = [label for label, rx in _DIAG_CHECKS if not rx.search(window)]
        if not missing:
            return
        missing_by_window.append(missing)
    assert missing_by_window, f"{where} never mentions a timeout at all"
    best = min(missing_by_window, key=len)
    raise AssertionError(
        f"{where}: no timeout mention is within one sentence window of the whole rule — "
        f"closest window is missing {best}"
    )


def test_tap_workflow_reference_tells_the_agent_a_timeout_returns_logs_and_a_partial_count():
    _assert_timeout_diagnostics_rule(
        _block(_read(SERVER_PY), 'TAP_WORKFLOW_REFERENCE = """'),
        "server.py tap-workflow-reference",
    )


def test_mcp_instructions_carry_the_timeout_diagnostics_rule():
    _assert_timeout_diagnostics_rule(
        _block(_read(SERVER_PY), '_INSTRUCTIONS_TEMPLATE = """'),
        "server.py instructions",
    )


def test_test_tap_and_run_tap_descriptions_carry_the_timeout_diagnostics_rule():
    text = _read(SERVER_PY)
    _assert_timeout_diagnostics_rule(_tool_block(text, "test_tap"), "the test_tap tool description")
    _assert_timeout_diagnostics_rule(_tool_block(text, "run_tap"), "the run_tap tool description")


def test_assistant_prompt_carries_the_timeout_diagnostics_rule():
    _assert_timeout_diagnostics_rule(_read(ASSISTANT_SCALA), "AssistantAPIController.scala")


def test_the_existing_memory_and_disk_rules_are_not_reworded_away():
    # The new rule is added NEXT TO these, never in place of them
    # (test_tap_memory_text.py / test_tap_disk_text.py own the full assertions).
    text = _read(SERVER_PY)
    assert "exit code -9" in text, "the -9 / out-of-memory rule must stay verbatim"
    assert "After two consecutive failed tests" in text, "the two-failures rule must stay verbatim"
    assert "Never probe the runner environment" in text, "the no-probing rule must stay verbatim"


def test_taps_doc_states_that_a_timed_out_run_keeps_its_logs_and_partial_count():
    text = _read(TAPS_MDX)
    rows = [l for l in text.splitlines() if l.startswith("|") and "`failure`" in l]
    assert rows, "docs/taps.mdx has no `failure` status row"
    assert any(_TIMEOUT_MENTION.search(l) and _LOGS.search(l) and _PARTIAL_COUNT.search(l) for l in rows), (
        "the `failure` row must say a timed-out run keeps the script's logs and a partial record count: " + str(rows)
    )
    assert re.search(r"100,?000 records", text) and _PROGRESS.search(text), (
        "docs/taps.mdx must say the platform prints a progress line every 100,000 records / 30 s for yielding scripts"
    )
