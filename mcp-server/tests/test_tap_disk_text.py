"""Tap runner scratch is a 512 MB in-memory tmpfs shared with the per-run venv
and pip-installed packages. Left unstated, an agent downloads a 1 GB Parquet
into it (No space left on device), and only after rewriting to stream does it
discover the month exceeds PIPELINE_MAX_PAYLOAD_MB. The agent-facing text must
carry two rules in every channel an agent reads:

  1. stream-never-download: name the 512 MB scratch limit and say to read the
     remote file directly / stream it instead of saving it first;
  2. payload-budget-before-build: estimate rows x bytes against
     PIPELINE_MAX_PAYLOAD_MB before creating anything for a large source.

Pure file-content checks, modelled on test_tap_memory_text.py.
"""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
ASSISTANT_SCALA = os.path.join(REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "api", "AssistantAPIController.scala")
UTIL_DIR = os.path.join(REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "util")
GENERATOR_SCALA = os.path.join(UTIL_DIR, "TapScriptGenerator.scala")
FIXER_SCALA = os.path.join(UTIL_DIR, "TapScriptFixer.scala")
TAPS_MDX = os.path.join(REPO_ROOT, "docs", "taps.mdx")
ISOLATION_MDX = os.path.join(REPO_ROOT, "docs", "tap-execution-isolation.mdx")
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")

_SCRATCH_512 = re.compile(r"512\s?MB", re.IGNORECASE)
_NEVER_DOWNLOAD = re.compile(r"never download|never save|do not download|not download", re.IGNORECASE)
_STREAM_DIRECT = re.compile(r"(read|stream)[^.\n]{0,40}(remote file|directly|over http)", re.IGNORECASE)
_NO_SPACE = re.compile(r"No space left on device", re.IGNORECASE)
_BUDGET_VAR = re.compile(r"PIPELINE_MAX_PAYLOAD_MB")
_ESTIMATE = re.compile(r"estimate", re.IGNORECASE)
_BEFORE_BUILD = re.compile(r"before (you )?(build|creat)", re.IGNORECASE)


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


def _compose_tmpfs_mb():
    m = re.search(r"/tmp:size=(\d+)m", _read(COMPOSE))
    assert m, "docker-compose.yml must declare the tap-runner tmpfs size"
    return int(m.group(1))


def test_prose_figure_matches_compose():
    """The number quoted to agents must be the number the runner actually gets."""
    assert _compose_tmpfs_mb() == 512, "compose tmpfs changed — update every '512 MB' in prompts and docs"


def test_tap_workflow_reference_carries_both_rules():
    ref = _between(_read(SERVER_PY), 'TAP_WORKFLOW_REFERENCE = """', '\n"""')
    assert _windowed(ref, _SCRATCH_512, _NEVER_DOWNLOAD, _NO_SPACE), "tap-workflow-reference must name the 512 MB scratch and the never-download rule"
    assert _STREAM_DIRECT.search(ref), "tap-workflow-reference must say to read the remote file directly / stream it"
    assert _windowed(ref, _BUDGET_VAR, _ESTIMATE, _BEFORE_BUILD), "tap-workflow-reference must carry the payload-budget check before building"


def test_create_tap_description_carries_both_rules():
    create = _between(_read(SERVER_PY), 'name="create_tap"', "\n        Tool(")
    assert _SCRATCH_512.search(create) and _NEVER_DOWNLOAD.search(create), "create_tap must state the 512 MB scratch / never-download rule"
    assert _BUDGET_VAR.search(create) and _ESTIMATE.search(create), "create_tap must ask for a payload-budget estimate for large sources"


def test_server_instructions_diagnose_no_space_as_download():
    text = _between(_read(SERVER_PY), "Tap workflow (for step 3 Option B):", '\n"""')
    assert _windowed(text, _NO_SPACE, _SCRATCH_512, window=3), "server instructions must map 'No space left on device' to the 512 MB scratch"


def test_assistant_prompt_carries_both_rules():
    text = _read(ASSISTANT_SCALA)
    assert _windowed(text, _SCRATCH_512, _NEVER_DOWNLOAD, _NO_SPACE, window=3), "Assistant prompt must state the 512 MB scratch / never-download rule"
    assert _windowed(text, _BUDGET_VAR, _ESTIMATE, _BEFORE_BUILD, window=3), "Assistant prompt must carry the payload-budget check before building"


def test_codegen_generator_and_fixer_carry_the_disk_rule():
    gen = _read(GENERATOR_SCALA)
    assert _windowed(gen, _SCRATCH_512, _NEVER_DOWNLOAD, _NO_SPACE), "TapScriptGenerator prompt must state the 512 MB scratch / never-download rule"
    fix = _read(FIXER_SCALA)
    assert _windowed(fix, _NO_SPACE, _SCRATCH_512), "TapScriptFixer prompt must map 'No space left on device' to the scratch limit"


def test_docs_state_the_limit_and_the_rules():
    iso = _read(ISOLATION_MDX)
    assert _SCRATCH_512.search(iso) and _NEVER_DOWNLOAD.search(iso), "tap-execution-isolation.mdx must give the 512 MB figure and the never-download rule"
    taps = _read(TAPS_MDX)
    assert _windowed(taps, _SCRATCH_512, _NEVER_DOWNLOAD, _NO_SPACE), "taps.mdx must state the 512 MB scratch / never-download rule"
    assert _windowed(taps, _BUDGET_VAR, _ESTIMATE, window=3), "taps.mdx must tell readers to estimate against the payload budget"
