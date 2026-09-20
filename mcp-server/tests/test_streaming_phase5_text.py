"""Story: Streaming pipeline Phase 5 — uploads, doctor, cleanup
(plans/stories/streaming-pipeline-phase5.md), the prose half (story Step 11):
the `run_doctor` tool description adds the staging area to the list of what
the doctor checks, and docs/doctor.mdx gains a row for each new check.

Pure text checks, modelled on test_payload_budget_text.py and the `_tool`
helper in test_scratch_tools.py."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCTOR_MDX = os.path.join(REPO_ROOT, "docs", "doctor.mdx")


def _tool(name):
    for t in server._base_tools():
        if t.name == name:
            return t
    raise AssertionError(f"tool {name} not in _base_tools()")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# ------------------------------------------------------------ MCP server ---

def test_run_doctor_description_names_the_staging_area():
    desc = _tool("run_doctor").description
    assert "staging" in desc.lower(), "run_doctor must tell the agent the report covers the staging area"
    # The existing list survives — the staging area is added to it, not swapped in.
    for kept in ("Vault token", "AI slot", "embedding model", "disk usage", "version skew"):
        assert kept.lower() in desc.lower(), f"run_doctor description lost '{kept}'"
    assert "Do NOT call run_doctor as part of the normal workflow" in desc


# ------------------------------------------------------------------ docs ---

def test_doctor_doc_has_a_row_for_each_staging_check():
    text = _read(DOCTOR_MDX)
    rows = {l.split("|")[1].strip().strip("`"): l for l in text.splitlines() if l.startswith("| `")}
    assert "staging.area" in rows, "docs/doctor.mdx needs a `staging.area` row"
    assert "staging.orphans" in rows, "docs/doctor.mdx needs a `staging.orphans` row"
    area = rows["staging.area"]
    assert "DATRIS_TEMP_DIR" in area or "datris-staging" in area, "the row should name where the staging area lives"
    orphans = rows["staging.orphans"]
    assert "24" in orphans, "the orphan age (24 h) should be stated"
