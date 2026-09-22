"""Story: agents size a tap run against the budgets actually in force, not the
documented defaults (plans/stories/tap-sizing-effective-budgets.md) — the
MCP-server half.

Every channel currently hard-codes "default 4096 MB" / "default 3600 s", and
no read endpoint carries the resolved numbers, so on an install that raised
`PIPELINE_MAX_PAYLOAD_MB` an agent declares a breach against a value that is
not in force. After this story:

  * `GET /api/v1/version` carries six additive string fields
    (`pipelineMaxPayloadMB`, `tapScriptTimeoutSeconds`, `tapRunTimeoutSeconds`
    and a `...Source` for each), and `get_version` passes them through;
  * `get_version`'s description names those fields and says to call it before
    sizing;
  * the four server.py sizing locations point at `get_version` for the value
    in force and keep the documented numbers labelled as defaults only.

Schema/dispatch checks are modelled on test_tap_test_limit.py (`_base_tools()`
plus a stubbed `_call`); the text checks are anchor-cut sections in the style
of test_tap_sizing_text.py. The "default next to the variable" sweep is
assembled from string parts so this file never matches its own sweep.
"""
import json
import os
import re
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SERVER_PY = os.path.join(REPO_ROOT, "mcp-server", "server.py")
ASSISTANT_SCALA = os.path.join(
    REPO_ROOT, "datrisserver", "src", "main", "scala", "ai", "datris", "api", "AssistantAPIController.scala"
)
VERSION_MDX = os.path.join(REPO_ROOT, "docs", "api-reference", "version-api.mdx")
OPENAPI = os.path.join(REPO_ROOT, "docs", "openapi.yaml")
TAPS_MDX = os.path.join(REPO_ROOT, "docs", "taps.mdx")
MCP_MDX = os.path.join(REPO_ROOT, "docs", "mcp-server.mdx")

BUDGET_VAR = "PIPELINE_MAX_PAYLOAD_MB"
BUDGET_FIELDS = (
    "pipelineMaxPayloadMB",
    "pipelineMaxPayloadMBSource",
    "tapScriptTimeoutSeconds",
    "tapScriptTimeoutSecondsSource",
    "tapRunTimeoutSeconds",
    "tapRunTimeoutSecondsSource",
)
READBACK_TOOL = "get_version"

# Assembled from parts so this file never matches its own sweep.
_DEFAULT_NUMBER = re.compile("de" + "fault" + r"\s*4096", re.IGNORECASE)
_EXCUSED = re.compile(READBACK_TOOL + "|on this install", re.IGNORECASE)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _between(text, start_anchor, end_anchor, start_from=0):
    i = text.index(start_anchor, start_from)
    j = text.find(end_anchor, i + len(start_anchor))
    return text[i:] if j < 0 else text[i:j]


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


def _sizing_sections():
    text = _read(SERVER_PY)
    return {
        "instructions": _between(text, "Tap workflow (for step 3 Option B):", '\n"""'),
        "reference": _between(text, 'TAP_WORKFLOW_REFERENCE = """', '\n"""'),
        "create_tap": _between(text, 'name="create_tap"', "\n        Tool("),
        "run_tap": _between(text, 'name="run_tap"', "\n        Tool("),
    }


def _windowed(text, *patterns, window=3):
    lines = text.splitlines()
    for k in range(len(lines)):
        chunk = "\n".join(lines[k:k + window])
        if all(p.search(chunk) for p in patterns):
            return True
    return False


class _Captured:
    """Stub the HTTP seam and return a server that raised the disk budget."""

    payload = {
        "version": "x",
        "useTapRunner": "false",
        "pipelineMaxPayloadMB": "16384",
        "pipelineMaxPayloadMBSource": "env",
        "tapScriptTimeoutSeconds": "300",
        "tapScriptTimeoutSecondsSource": "default",
        "tapRunTimeoutSeconds": "7200",
        "tapRunTimeoutSecondsSource": "env",
    }

    def __init__(self):
        self.calls = []

    def call(self, method, path, timeout=300, **kwargs):
        self.calls.append((method, path, kwargs))
        return json.dumps(self.payload)


@pytest.fixture
def captured(monkeypatch):
    c = _Captured()
    monkeypatch.setattr(server, "_call", c.call)
    return c


# --------------------------------------------------- tool description ---

def test_get_version_description_names_the_budget_fields():
    desc = _tool(READBACK_TOOL).description
    for field in ("pipelineMaxPayloadMB", "tapScriptTimeoutSeconds", "tapRunTimeoutSeconds"):
        assert field in desc, f"{READBACK_TOOL} description must name `{field}`: {desc!r}"
    assert "Source" in desc, "the description must say each budget carries a `...Source` field: " + repr(desc)
    assert re.search(r"siz(e|ing)", desc, re.IGNORECASE), (
        "the description must tell the agent to call it before sizing a large source: " + repr(desc)
    )


# --------------------------------------------------------- dispatch ---

def test_get_version_dispatch_passes_the_budget_fields_through(captured):
    out = json.loads(server._dispatch(READBACK_TOOL, {}))
    assert ("get", "/api/v1/version") in [(m, p) for m, p, _ in captured.calls], captured.calls
    for field in BUDGET_FIELDS:
        assert field in out, f"{READBACK_TOOL} dropped `{field}` from the version response: " + repr(sorted(out))
    assert out["pipelineMaxPayloadMB"] == "16384"
    assert out["pipelineMaxPayloadMBSource"] == "env"
    assert out["tapRunTimeoutSeconds"] == "7200"
    assert "mcpServerVersion" in out, "the MCP server version must still be added"
    assert out["version"] == "x"


# ------------------------------------------------------ sizing text ---

def test_sizing_text_reads_budgets_from_get_version():
    for where, section in _sizing_sections().items():
        assert _windowed(section, re.compile(re.escape(READBACK_TOOL)), re.compile(BUDGET_VAR), window=3), (
            f"the {where} sizing text must point at `{READBACK_TOOL}` within 3 lines of {BUDGET_VAR}"
        )

    for path in (SERVER_PY, ASSISTANT_SCALA):
        name = os.path.relpath(path, REPO_ROOT)
        for i, line in enumerate(_read(path).splitlines(), 1):
            if BUDGET_VAR in line and _DEFAULT_NUMBER.search(line) and not _EXCUSED.search(line):
                raise AssertionError(
                    f"{name}:{i} quotes the documented default next to {BUDGET_VAR} without naming the value in "
                    f"force (`{READBACK_TOOL}` or 'on this install'): " + line.strip()[:160]
                )


# ------------------------------------------------------------- docs ---

def test_docs_name_the_readback():
    version_doc = _read(VERSION_MDX)
    for field in BUDGET_FIELDS:
        rows = [l for l in version_doc.splitlines() if l.startswith(f"| `{field}`")]
        assert rows, f"no `{field}` row in docs/api-reference/version-api.mdx"

    schema = _between(_read(OPENAPI), "VersionResponse:", "\n    KillJobResponse:")
    for field in BUDGET_FIELDS:
        assert re.search(r"^\s+" + field + r":", schema, re.MULTILINE), (
            f"`{field}` missing from the VersionResponse schema in docs/openapi.yaml"
        )

    reqs = _between(_read(TAPS_MDX), "## Script Requirements", "\n## ")
    assert READBACK_TOOL in reqs, (
        f"docs/taps.mdx Script Requirements must tell the reader to read the value in force from `{READBACK_TOOL}`"
    )

    row = next((l for l in _read(MCP_MDX).splitlines() if l.startswith(f"| `{READBACK_TOOL}`")), None)
    assert row, f"{READBACK_TOOL} row missing from docs/mcp-server.mdx"
    assert re.search(r"budget", row, re.IGNORECASE), "the get_version row must mention the tap budgets: " + row
