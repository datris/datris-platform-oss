"""Story: Streaming pipeline Phase 4 — taps stop buffering the payload
(plans/stories/streaming-pipeline-phase4.md), the prose half (story Step 1:
"compose/.env.example/docs/MCP text").

Not an Acceptance bullet on its own, but the Verify block runs this directory
and the old text is actively misleading once the cap is a disk budget: an
agent told "default 100MB, the whole batch is buffered before pipeline
loading" will chunk a fetch that no longer needs chunking. Pure file-content
checks, modelled on test_iceberg_docs.py."""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
TAPS_MDX = os.path.join(REPO_ROOT, "docs", "taps.mdx")
MCP_MDX = os.path.join(REPO_ROOT, "docs", "mcp-server.mdx")
CONFIG_REF_MDX = os.path.join(REPO_ROOT, "docs", "configuration-reference.mdx")
ENV_EXAMPLE = os.path.join(REPO_ROOT, ".env.example")
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
COMPOSE_STANDALONE = os.path.join(REPO_ROOT, "docker-compose.standalone.yml")

NEW_VAR = "PIPELINE_MAX_PAYLOAD_MB"
OLD_VAR = "TAP_MAX_OUTPUT_MB"
# Assembled from parts so this file never matches its own sweep.
_OLD_DEFAULT = re.compile(r"default\s*100\s*MB", re.IGNORECASE)
_BUFFERED = re.compile("whole batch is " + "buffered", re.IGNORECASE)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# ------------------------------------------------------------ MCP server ---

def test_mcp_server_names_the_disk_budget_not_the_100mb_heap_cap():
    text = _read(SERVER_PY)
    assert NEW_VAR in text, "server.py never mentions PIPELINE_MAX_PAYLOAD_MB"
    assert not _OLD_DEFAULT.search(text), "server.py still advertises a 100 MB default"
    assert not _BUFFERED.search(text), "server.py still says the batch is buffered in memory"


def test_mcp_server_keeps_chunking_as_advice_not_a_limit():
    text = _read(SERVER_PY)
    # The run_error cause list still exists and still points at params /
    # incremental state — as advice.
    assert "Common `run_error` causes" in text
    assert "params" in text and "Incremental sync" in text


# ------------------------------------------------------------------ docs ---

def test_taps_doc_output_size_guard_describes_the_disk_budget():
    text = _read(TAPS_MDX)
    assert "### Output Size Guard" in text
    section = text[text.index("### Output Size Guard"):]
    section = section[: section.find("\n## ")] if "\n## " in section else section
    assert NEW_VAR in section
    assert "4096" in section
    assert OLD_VAR in section, "the alias must be documented as deprecated for one release"
    assert re.search(r"deprecat", section, re.IGNORECASE), "alias needs a deprecation note"
    assert not _OLD_DEFAULT.search(section)
    assert not _BUFFERED.search(section)


def test_mcp_doc_run_tap_row_names_the_new_var():
    text = _read(MCP_MDX)
    row = next((l for l in text.splitlines() if l.startswith("| `run_tap`")), None)
    assert row, "run_tap row missing from docs/mcp-server.mdx"
    assert NEW_VAR in row
    assert not _OLD_DEFAULT.search(row)


def test_configuration_reference_has_the_new_var_with_its_default():
    text = _read(CONFIG_REF_MDX)
    rows = [l for l in text.splitlines() if l.startswith(f"| `{NEW_VAR}`")]
    assert rows, f"no `{NEW_VAR}` row in the container env table"
    assert "4096" in rows[0]
    assert "unlimited" in rows[0].lower(), "0 = unlimited must be stated"
    old_rows = [l for l in text.splitlines() if l.startswith(f"| `{OLD_VAR}`")]
    assert old_rows, "the alias row must stay for one release"
    assert re.search(r"deprecat", old_rows[0], re.IGNORECASE)


# ---------------------------------------------------------------- compose ---

def test_env_example_documents_the_new_var_and_the_alias():
    text = _read(ENV_EXAMPLE)
    assert NEW_VAR in text
    assert OLD_VAR in text, "alias comment must stay for one release"


def test_compose_forwards_the_new_var_with_default_4096_and_keeps_the_alias():
    for path in (COMPOSE, COMPOSE_STANDALONE):
        text = _read(path)
        assert re.search(r"\$\{PIPELINE_MAX_PAYLOAD_MB:-(\$\{TAP_MAX_OUTPUT_MB:-)?4096\}?\}", text), f"{os.path.basename(path)} must forward {NEW_VAR} defaulting to 4096"
        assert OLD_VAR in text, f"{os.path.basename(path)} must keep forwarding the alias for one release"
        # The alias must no longer default to 100: that would make the alias
        # always "set" and pin every install at 100 MB.
        assert not re.search(r"\$\{" + OLD_VAR + r":-100\}", text), f"{os.path.basename(path)} still defaults {OLD_VAR} to 100"
