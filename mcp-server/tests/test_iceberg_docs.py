"""Story: Iceberg docs, release notes and end-to-end verification
(plans/stories/iceberg-docs-and-e2e.md).

Pure file-content checks over the repo that pin the docs-shaped Acceptance
bullets: the new destination page exists with a search-oriented title, it is
in the destinations nav right after S3, nothing under docs/ is a `.md` file,
the loose-columnar-files-only wording is gone from docs (changelog excepted), the
objectStore config table documents `keyFields` and the iceberg format, and the
two existing object-store pages cross-link to the new page.

Release-notes content, the Mintlify build, the Trivy scan and the manual E2E
steps need the live stack or the releaser step and are covered by the e2e
pass, not here.

The wording sweep builds its pattern from fragments so this file never
matches the sweep it enforces."""
import json
import os
import re
import subprocess

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
ICEBERG_MDX = os.path.join(DOCS, "destinations", "iceberg.mdx")
DOCS_JSON = os.path.join(DOCS, "docs.json")
PIPELINE_CONFIG_MDX = os.path.join(DOCS, "pipeline-configuration.mdx")
OBJECT_STORE_MDX = os.path.join(DOCS, "destinations", "object-store.mdx")
S3_MDX = os.path.join(DOCS, "destinations", "s3.mdx")

# The story's grep, case-insensitive — assembled from parts.
_LOOSE_FILES_ONLY = re.compile("parquet" + r"(/| or )" + "orc", re.IGNORECASE)


# ---------------------------------------------------------------- helpers ---

def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _frontmatter(path):
    """Return the YAML-ish key/value pairs between the leading `---` fences."""
    text = _read(path)
    m = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    assert m, f"{os.path.relpath(path, REPO_ROOT)} has no frontmatter block"
    fm = {}
    for line in m.group(1).splitlines():
        k, sep, v = line.partition(":")
        if sep:
            fm[k.strip()] = v.strip().strip('"').strip("'")
    return fm


def _section(text, heading, next_heading_prefix="### "):
    start = text.index(heading)
    end = text.find(next_heading_prefix, start + len(heading))
    return text[start:] if end == -1 else text[start:end]


def _table_row(section, field):
    for line in section.splitlines():
        if line.startswith(f"| `{field}`"):
            return line
    return None


# ------------------------------------------------------ Acceptance bullet 1 ---
# docs/destinations/iceberg.mdx exists with a search-oriented title and
# description and the page appears in the destinations nav.

def test_iceberg_page_exists():
    assert os.path.isfile(ICEBERG_MDX), "docs/destinations/iceberg.mdx is missing"


def test_iceberg_page_has_search_oriented_title_and_description():
    fm = _frontmatter(ICEBERG_MDX)
    assert fm.get("title"), "frontmatter has no title"
    assert fm.get("description"), "frontmatter has no description"
    title = fm["title"]
    # A task a reader would type, not a label like "Iceberg" or "Iceberg destination".
    assert title.lower().startswith("write"), title
    assert "iceberg" in title.lower(), title
    assert "iceberg" in fm["description"].lower(), fm["description"]


def test_iceberg_page_is_in_destinations_nav_right_after_s3():
    nav = json.loads(_read(DOCS_JSON))

    def _groups(node):
        if isinstance(node, dict):
            if node.get("group") == "Destinations" and "pages" in node:
                yield node
            for v in node.values():
                yield from _groups(v)
        elif isinstance(node, list):
            for v in node:
                yield from _groups(v)

    groups = list(_groups(nav))
    assert groups, "docs.json has no Destinations group"
    pages = groups[0]["pages"]
    assert "destinations/iceberg" in pages, pages
    assert "destinations/s3" in pages, pages
    assert pages.index("destinations/iceberg") == pages.index("destinations/s3") + 1, pages


# ------------------------------------------------------ Acceptance bullet 2 ---
# No `.md` files added under docs/. There is no CI guard for this today; the
# repo convention is Mintlify `.mdx` only, so pin the tracked tree.

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


# ------------------------------------------------------ Acceptance bullet 3 ---
# The story's grep over docs returns nothing except the
# historical docs/changelog.mdx entry.

def test_docs_sweep_is_clean_except_changelog():
    hits = []
    for root, dirs, files in os.walk(DOCS):
        dirs[:] = [d for d in dirs if d not in ("node_modules", ".git", "config")]
        for f in files:
            p = os.path.join(root, f)
            rel = os.path.relpath(p, REPO_ROOT)
            if rel == os.path.join("docs", "changelog.mdx"):
                continue
            try:
                with open(p, encoding="utf-8", errors="ignore") as fh:
                    for n, line in enumerate(fh, 1):
                        if _LOOSE_FILES_ONLY.search(line):
                            hits.append(f"{rel}:{n}: {line.strip()[:120]}")
            except OSError:
                pass
    assert hits == [], "\n".join(hits)


# ---------------------------------------------- Files: pipeline-configuration ---
# The objectStore table widens `fileFormat` and gains a `keyFields` row.

def test_pipeline_configuration_objectstore_table_names_iceberg_and_keyfields():
    section = _section(_read(PIPELINE_CONFIG_MDX), "### Destination > Object Store")
    ff = _table_row(section, "fileFormat")
    assert ff is not None, "no fileFormat row in the objectStore table"
    assert "iceberg" in ff.lower(), ff
    kf = _table_row(section, "keyFields")
    assert kf is not None, "no keyFields row in the objectStore table"
    wm = _table_row(section, "writeMode")
    assert wm is not None and "merge" in wm.lower(), wm


# ------------------------------------------------ Files: cross-links ---
# object-store.mdx and s3.mdx mention Iceberg and link to the new page.

def test_object_store_page_mentions_iceberg_and_links_to_new_page():
    text = _read(OBJECT_STORE_MDX)
    assert "iceberg" in text.lower()
    assert "/destinations/iceberg" in text


def test_s3_page_mentions_iceberg_and_links_to_new_page():
    text = _read(S3_MDX)
    assert "iceberg" in text.lower()
    assert "/destinations/iceberg" in text
