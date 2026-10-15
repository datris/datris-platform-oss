"""Story: Dev, QA and production: the environments doc page
(plans/stories/multi-env-1-docs.md).

File-content checks for the docs-only Acceptance bullets:
  1. docs/production/environments.mdx exists, has title/description
     frontmatter, the 8 `##` sections in order, no x.y.z version in the body.
  2. docs.json Production group order.
  3. security-architecture.mdx and data-catalog.mdx each link
     /production/environments exactly once.
  4. Every internal link on the new page resolves to a file (and heading,
     for anchors).
  5. "Limits today" has exactly four bullets, matching plan stories
     B, F, D, E in that order.
  7. No .md file under docs/ (regression guard; passes today).

Bullet 6 (three fresh installs, dev -> qa -> prod promotion, no-commit
restore, 409 on divergent head) needs live stacks and is e2e only.
`mintlify broken-links` is checked at review / e2e."""
import glob
import json
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
PAGE = os.path.join(DOCS, "production", "environments.mdx")

SECTIONS = [
    r"why three installs",
    r"make definitions portable",
    r"github layout",
    r"configure each environment",
    r"promote a catalog",
    r"upgrade order",
    r"point agents and the cli at an environment",
    r"limits today",
]


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _page():
    assert os.path.isfile(PAGE), "docs/production/environments.mdx does not exist"
    return _read(PAGE)


def _split_frontmatter(text):
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.DOTALL)
    assert m, "page has no --- frontmatter block"
    return m.group(1), m.group(2)


def _slug(heading):
    h = re.sub(r"[`*_]", "", heading).strip().lower()
    h = re.sub(r"[^a-z0-9 -]", "", h)
    return re.sub(r"\s+", "-", h)


def _headings(text):
    return [m.group(2) for m in re.finditer(r"^(#{1,6})\s+(.*?)\s*$", text, re.MULTILINE)]


def _section(text, heading_re):
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^(#+)\s+(.*?)\s*$", line)
        if m and re.search(heading_re, m.group(2), re.IGNORECASE):
            level = len(m.group(1))
            body = []
            for nxt in lines[i + 1:]:
                n = re.match(r"^(#+)\s", nxt)
                if n and len(n.group(1)) <= level:
                    break
                body.append(nxt)
            return "\n".join(body)
    return None


# ---- bullet 1 ---------------------------------------------------------------

def test_environments_page_exists_with_title_and_description_frontmatter():
    front, _ = _split_frontmatter(_page())
    keys = [l.split(":", 1)[0].strip() for l in front.splitlines() if l.strip()]
    assert keys == ["title", "description"], f"frontmatter keys are {keys}, expected exactly title + description"
    title = re.search(r'^title:\s*"(.*)"\s*$', front, re.MULTILINE)
    assert title, "title is not double-quoted"
    assert title.group(1) == "Dev, QA and production", title.group(1)
    assert re.search(r'^description:\s*".+"\s*$', front, re.MULTILINE), "description is not a non-empty double-quoted string"


def test_environments_page_has_the_eight_sections_in_order():
    _, body = _split_frontmatter(_page())
    h2 = [m.group(1) for m in re.finditer(r"^##\s+(.*?)\s*$", body, re.MULTILINE)]
    assert len(h2) == len(SECTIONS), f"expected {len(SECTIONS)} ## sections, got {len(h2)}: {h2}"
    for got, want in zip(h2, SECTIONS):
        assert re.fullmatch(want, got.strip().lower().replace("`", "")), f"section {got!r} where {want!r} was expected; all: {h2}"


def test_environments_page_body_has_no_version_number():
    _, body = _split_frontmatter(_page())
    hits = [l.strip() for l in body.splitlines() if re.search(r"\b\d+\.\d+\.\d+\b", l)]
    assert not hits, "version numbers in body (say 'the current release'):\n" + "\n".join(hits)


# ---- bullet 2 ---------------------------------------------------------------

def test_docs_json_production_group_order():
    nav = json.loads(_read(os.path.join(DOCS, "docs.json")))["navigation"]["tabs"]
    groups = [g for tab in nav for g in tab.get("groups", []) if g.get("group") == "Production"]
    assert len(groups) == 1, "exactly one Production group"
    assert groups[0]["pages"] == [
        "production/security-architecture",
        "production/data-flows",
        "production/upgrades",
        "production/environments",
        "production/vulnerability-disclosure",
        "production/licensing-support",
    ], groups[0]["pages"]


# ---- bullet 3 ---------------------------------------------------------------

LINK_TO_PAGE = re.compile(r"\]\(/production/environments(#[a-z0-9-]+)?\)")


def test_security_architecture_links_environments_once():
    text = _read(os.path.join(DOCS, "production", "security-architecture.mdx"))
    n = len(LINK_TO_PAGE.findall(text))
    assert n == 1, f"security-architecture.mdx links /production/environments {n} times, expected 1"
    # The paragraph sits after the operator checklist and is about governed QA.
    after = text.split("## Operator checklist for production", 1)
    assert len(after) == 2 and LINK_TO_PAGE.search(after[1]), "the link is not after the operator checklist"
    para = [p for p in after[1].split("\n\n") if LINK_TO_PAGE.search(p)][0]
    assert re.search(r"\bQA\b", para) and re.search(r"governance|governed", para, re.IGNORECASE), para[:300]


def test_data_catalog_links_environments_once():
    text = _read(os.path.join(DOCS, "data-catalog.mdx"))
    n = len(LINK_TO_PAGE.findall(text))
    assert n == 1, f"data-catalog.mdx links /production/environments {n} times, expected 1"


# ---- bullet 4 ---------------------------------------------------------------

def test_every_internal_link_on_the_environments_page_resolves():
    _, body = _split_frontmatter(_page())
    links = sorted(set(re.findall(r"\]\((/[^)\s]*)\)", body)) | set(re.findall(r'href="(/[^"]*)"', body)))
    assert links, "the page links no other docs page"
    missing = []
    for link in links:
        path, _, anchor = link.lstrip("/").partition("#")
        if not path:
            target = PAGE
        else:
            target = os.path.join(DOCS, path + ".mdx")
            if not os.path.isfile(target):
                missing.append(f"MISSING {link} (no docs/{path}.mdx)")
                continue
        if anchor:
            slugs = {_slug(h) for h in _headings(_read(target))}
            if anchor not in slugs:
                missing.append(f"MISSING {link} (no heading with anchor #{anchor})")
    assert not missing, "\n".join(missing)


# ---- bullet 5 ---------------------------------------------------------------

# Plan §6 stories B, F, D, E, in the order the story lists them.
LIMIT_MATCHERS = [
    ("B: CodeGen scripts regenerate on restore",
     r"regenerat", r"codegen|code ?gen"),
    ("F: no dry run / preview",
     r"dry run", r"overwrite|preview"),
    ("D: restored taps start disabled, no CLI test+enable",
     r"disabled", r"enable in order|enable_catalog_ordered"),
    ("E: agent policy cannot exempt one key",
     r"agent policy|policy", r"restore_catalog|\bkey\b"),
]


def test_limits_today_has_exactly_four_bullets_matching_b_f_d_e():
    sec = _section(_page(), r"^limits today$")
    assert sec is not None, "no 'Limits today' section"
    bullets = [l for l in sec.splitlines() if re.match(r"^\s*[-*]\s+\S", l)]
    assert len(bullets) == 4, f"expected exactly 4 bullets, got {len(bullets)}:\n" + "\n".join(bullets)
    for line in bullets:
        assert not line.startswith((" ", "\t")), "bullets are top-level, one line each: " + line[:200]
    for (name, a, b), line in zip(LIMIT_MATCHERS, bullets):
        assert re.search(a, line, re.IGNORECASE) and re.search(b, line, re.IGNORECASE), f"bullet does not match story {name}: {line[:300]}"
    # Every bullet line is a single source line (no wrapped continuation).
    lines = sec.splitlines()
    for i, l in enumerate(lines):
        if l in bullets and i + 1 < len(lines):
            nxt = lines[i + 1]
            assert not nxt.strip() or re.match(r"^\s*[-*]\s+\S", nxt), "bullet wraps onto a second line: " + l[:120]


# ---- bullet 7 ---------------------------------------------------------------

def test_no_md_files_under_docs():
    found = [os.path.relpath(p, REPO_ROOT) for p in glob.glob(os.path.join(DOCS, "**", "*.md"), recursive=True)]
    assert not found, "docs are .mdx only: " + ", ".join(found)
