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
