"""Story: Field protection 2: docs, OpenAPI, MCP create_pipeline `protect`,
agent skill (plans/stories/field-protection-2-surfaces.md).

File-content checks for the docs-shaped Acceptance bullet: the new
transformation/field-protection.mdx page lists the four methods and has the
"What still sees raw data" and "Source retention" sections, docs.json lists
the page, dropping-columns.mdx links to it, the shared pipeline-config
reference mentions `protect` and `hmac`, and nothing under docs/ is `.md`."""
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
FIELD_PROTECTION_MDX = os.path.join(DOCS, "transformation", "field-protection.mdx")
DROPPING_COLUMNS_MDX = os.path.join(DOCS, "transformation", "dropping-columns.mdx")
DOCS_JSON = os.path.join(DOCS, "docs.json")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _section(text, heading_re):
    """Body of the first heading matching heading_re, up to the next heading
    of the same or higher level."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^(#+)\s+(.*?)\s*$", line)
        if m and re.match(heading_re, m.group(2)):
            level = len(m.group(1))
            body = []
            for nxt in lines[i + 1:]:
                n = re.match(r"^(#+)\s", nxt)
                if n and len(n.group(1)) <= level:
                    break
                body.append(nxt)
            return "\n".join(body)
    return None


def _headings(text):
    return [l.strip() for l in text.splitlines() if l.startswith("#")]


def _page_paths(node):
    """Every page path string anywhere in the docs.json navigation tree."""
    if isinstance(node, str):
        yield node
    elif isinstance(node, list):
        for item in node:
            yield from _page_paths(item)
    elif isinstance(node, dict):
        for v in node.values():
            yield from _page_paths(v)


# ------------------------------------------------- field-protection.mdx ---

def test_field_protection_page_lists_hmac_mask_redact_drop():
    assert os.path.exists(FIELD_PROTECTION_MDX), "docs/transformation/field-protection.mdx does not exist"
    text = _read(FIELD_PROTECTION_MDX)
    assert re.search(r"^title:\s*\"?Field Protection \(PII / PHI\)\"?\s*$", text, re.MULTILINE), \
        "page title must be \"Field Protection (PII / PHI)\""
    for method in ("hmac", "mask", "redact", "drop"):
        assert f"`{method}`" in text or f'"{method}"' in text, f"method {method} not named on the page"


def test_field_protection_page_has_what_still_sees_raw_data_section():
    text = _read(FIELD_PROTECTION_MDX)
    body = _section(text, r"^What still sees raw data$")
    assert body is not None, _headings(text)
    assert body.strip(), "the What still sees raw data section is empty"


def test_field_protection_page_has_source_retention_section_naming_purge_source():
    text = _read(FIELD_PROTECTION_MDX)
    body = _section(text, r"^Source retention$")
    assert body is not None, _headings(text)
    assert "purgeSource" in body, body


# ------------------------------------------------------------- docs.json ---

def test_docs_json_lists_transformation_field_protection():
    nav = json.loads(_read(DOCS_JSON))
    pages = list(_page_paths(nav))
    assert "transformation/field-protection" in pages, "docs.json does not list transformation/field-protection"
    # Placed right after transformation/dropping-columns in the same group.
    raw = _read(DOCS_JSON)
    assert re.search(
        r'"transformation/dropping-columns"\s*,\s*"transformation/field-protection"', raw
    ), "transformation/field-protection must follow transformation/dropping-columns"


# ------------------------------------------------- dropping-columns.mdx ---

def test_dropping_columns_links_to_field_protection():
    text = _read(DROPPING_COLUMNS_MDX)
    assert "/transformation/field-protection" in text, "dropping-columns.mdx does not link field-protection"


# ---------------------------------------- PIPELINE_CONFIG_REFERENCE text ---

def test_pipeline_config_reference_mentions_protect_and_hmac():
    ref = server.PIPELINE_CONFIG_REFERENCE
    assert re.search(r'"protect"|`protect`|\bprotect\b:', ref), \
        "PIPELINE_CONFIG_REFERENCE must name the protect key"
    assert "hmac" in ref


# ------------------------------------------------------ no .md in docs/ ---

def test_no_md_files_under_docs():
    tracked = subprocess.run(
        ["git", "ls-files", "docs"], cwd=REPO_ROOT, capture_output=True, text=True, check=True
    ).stdout.split()
    md = [p for p in tracked if p.lower().endswith(".md")]
    for root, dirs, files in os.walk(DOCS):
        dirs[:] = [d for d in dirs if d not in ("node_modules", ".git", "config")]
        md += [os.path.relpath(os.path.join(root, f), REPO_ROOT) for f in files if f.lower().endswith(".md")]
    assert md == [], md
