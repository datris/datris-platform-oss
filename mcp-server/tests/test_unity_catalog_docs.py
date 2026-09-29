"""Story: Unity Catalog 1: config + metadata push (Databricks)
(plans/stories/unity-catalog-1-metadata-push.md).

File-content checks that pin the docs-shaped Acceptance bullet: the Databricks
grant script gains `GRANT APPLY TAG ON SCHEMA`, databricks.mdx has a
`Unity Catalog metadata` heading, the pipeline-config reference resource the
MCP server and the in-platform Assistant both read mentions `unityCatalog` and
`APPLY TAG`, configuration-reference.mdx lists `DATRIS_UNITY_CATALOG_SYNC`, and
nothing under docs/ is a `.md` file."""
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
DATABRICKS_MDX = os.path.join(DOCS, "destinations", "databricks.mdx")
CONFIG_REFERENCE_MDX = os.path.join(DOCS, "configuration-reference.mdx")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _grant_script(text):
    """The ```sql block that holds the catalog/schema grants (the one that
    grants USE CATALOG to the service principal)."""
    for block in re.findall(r"```sql\n(.*?)```", text, re.DOTALL):
        if "GRANT USE CATALOG" in block:
            return block
    raise AssertionError("databricks.mdx has no grant script (```sql block with GRANT USE CATALOG)")


# --------------------------------------------------------- grant script ---

def test_grant_script_contains_apply_tag_on_schema():
    script = _grant_script(_read(DATABRICKS_MDX))
    assert "GRANT APPLY TAG ON SCHEMA" in script, script
    # Same principal placeholder as the rest of the script.
    apply_tag = [l for l in script.splitlines() if "GRANT APPLY TAG ON SCHEMA" in l]
    assert any("<sp-application-id>" in l for l in apply_tag), apply_tag


# ------------------------------------------------ databricks.mdx heading ---

def test_databricks_page_has_unity_catalog_metadata_heading():
    headings = [l.strip() for l in _read(DATABRICKS_MDX).splitlines() if l.startswith("#")]
    assert any(re.match(r"^#+\s+Unity Catalog metadata\s*$", h) for h in headings), headings


def test_databricks_config_example_shows_unity_catalog_block():
    assert '"unityCatalog"' in _read(DATABRICKS_MDX)


# ---------------------------------------- PIPELINE_CONFIG_REFERENCE text ---

def test_pipeline_config_reference_mentions_unity_catalog_and_apply_tag():
    ref = server.PIPELINE_CONFIG_REFERENCE
    assert "unityCatalog" in ref
    assert "APPLY TAG" in ref


# ------------------------------------------- configuration-reference.mdx ---

def test_configuration_reference_lists_unity_catalog_sync_switch():
    text = _read(CONFIG_REFERENCE_MDX)
    rows = [l for l in text.splitlines() if l.startswith("| `DATRIS_UNITY_CATALOG_SYNC`")]
    assert rows, "no DATRIS_UNITY_CATALOG_SYNC row in configuration-reference.mdx"
    assert "`true`" in rows[0], rows[0]


# ------------------------------------------------------ no .md in docs/ ---

def test_no_markdown_files_tracked_under_docs():
    out = subprocess.run(
        ["git", "ls-files", "docs"], cwd=REPO_ROOT, capture_output=True, text=True, check=True
    ).stdout.split()
    md = [p for p in out if p.lower().endswith(".md")]
    assert md == [], md


def test_no_markdown_files_on_disk_under_docs():
    md = []
    for root, dirs, files in os.walk(DOCS):
        dirs[:] = [d for d in dirs if d not in ("node_modules", ".git", "config")]
        md += [os.path.relpath(os.path.join(root, f), REPO_ROOT) for f in files if f.lower().endswith(".md")]
    assert md == [], md
