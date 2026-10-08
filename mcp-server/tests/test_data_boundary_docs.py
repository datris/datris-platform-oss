"""Story: Data boundary: wording that is true in every configuration, and a
data-flow matrix in the docs (plans/stories/data-boundary-matrix.md).

File-content checks for the docs-shaped Acceptance bullets:
- docs/production/data-flows.mdx exists with a 6-row x 4-column matrix whose
  every cell has a verdict, and every non-"never" cell carries a note
  reference; the page is in the Production nav right after
  production/security-architecture.
- The page says the chat assistants have no local-model option; the security
  page's "Air-gapped operation" paragraph no longer says Ollama covers chat.
- The page lists what DATRIS_AI_SAMPLE_VALUES=false does not cover (features
  that read data by design, tap script review and diagnosis, embeddings), and
  field-protection.mdx links to the page.
- Anthropic and OpenAI appear together wherever either is named on the page.

The PR-description evidence table, the git-diff scope, `mintlify
broken-links` and the website build are checked at review / e2e, not here."""
import json
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
DATA_FLOWS_MDX = os.path.join(DOCS, "production", "data-flows.mdx")
SECURITY_MDX = os.path.join(DOCS, "production", "security-architecture.mdx")
FIELD_PROTECTION_MDX = os.path.join(DOCS, "transformation", "field-protection.mdx")
AI_CONFIG_MDX = os.path.join(DOCS, "ai-configuration.mdx")
DOCS_JSON = os.path.join(DOCS, "docs.json")

# The six kinds of data the brief names, in order, as row-label patterns.
ROW_PATTERNS = [
    ("row values and samples", r"values|samples"),
    ("column names and schema", r"column names|schema"),
    ("source credentials", r"source credential"),
    ("AI provider keys", r"provider key"),
    ("logs and error text", r"\blogs?\b|error"),
    ("generated scripts", r"script"),
]


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _page():
    assert os.path.exists(DATA_FLOWS_MDX), "docs/production/data-flows.mdx does not exist"
    return _read(DATA_FLOWS_MDX)


def _section(text, heading_re):
    lines = text.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^(#+)\s+(.*?)\s*$", line)
        if m and re.match(heading_re, m.group(2), re.IGNORECASE):
            level = len(m.group(1))
            body = []
            for nxt in lines[i + 1:]:
                n = re.match(r"^(#+)\s", nxt)
                if n and len(n.group(1)) <= level:
                    break
                body.append(nxt)
            return "\n".join(body)
    return None


def _tables(text):
    """Every markdown table as a list of rows, each a list of stripped cells
    (header row first, separator row dropped)."""
    tables, cur = [], []
    for line in text.splitlines() + [""]:
        s = line.strip()
        if s.startswith("|"):
            cells = [c.strip() for c in s.strip("|").split("|")]
            if all(re.fullmatch(r":?-{3,}:?", c) for c in cells if c):
                continue
            cur.append(cells)
        elif cur:
            tables.append(cur)
            cur = []
    return tables


def _matrix(text):
    """The first table whose body rows cover all six data kinds."""
    for t in _tables(text):
        body = t[1:]
        labels = [r[0].lower() for r in body]
        if len(body) >= 6 and all(
            any(re.search(pat, l) for l in labels) for _, pat in ROW_PATTERNS
        ):
            return t
    return None


def _strip_markup(cell):
    return re.sub(r"<[^>]+>|\*|`", "", cell).strip()


def _nav_pages(node):
    if isinstance(node, str):
        yield node
    elif isinstance(node, list):
        for item in node:
            yield from _nav_pages(item)
    elif isinstance(node, dict):
        for v in node.values():
            yield from _nav_pages(v)


# ------------------------------------------------ bullet 1: the matrix ---

def test_data_flows_page_title():
    text = _page()
    assert re.search(r'^title:\s*"?Data Flows"?\s*$', text, re.MULTILINE), \
        'page title must be "Data Flows"'


def test_data_flows_matrix_is_6_rows_by_4_columns():
    text = _page()
    m = _matrix(text)
    assert m is not None, "no table on the page has a row for each of the six data kinds"
    header, body = m[0], m[1:]
    assert len(body) == 6, f"matrix must have exactly 6 rows, has {len(body)}"
    assert len(header) == 5, f"matrix must have a label column plus 4 columns, header is {header}"
    for row in body:
        assert len(row) == 5, f"row {row[0]!r} does not have 4 cells: {row}"


def test_data_flows_every_cell_has_a_verdict_and_non_never_cells_reference_a_note():
    text = _page()
    m = _matrix(text)
    assert m is not None, "matrix not found"
    for row in m[1:]:
        for col, cell in zip(m[0][1:], row[1:]):
            plain = _strip_markup(cell)
            assert plain, f"empty cell: row {row[0]!r}, column {col!r}"
            if re.fullmatch(r"(?i)never\.?", plain):
                continue
            # A non-"never" verdict must point at a per-row note (footnote
            # number, superscript, or a link to a note anchor).
            assert re.search(r"\d|\[\^|<sup>|\]\(#", cell), \
                f"non-'never' cell has no note reference: row {row[0]!r}, column {col!r}: {cell!r}"


def test_data_flows_non_never_cells_name_feature_and_switch_in_notes():
    """The per-row notes must name the switches that move a cell."""
    text = _page()
    for switch in ("DATRIS_AI_SAMPLE_VALUES", "DATRIS_TAP_SECRET_SCOPE", "protect"):
        assert switch in text, f"switch {switch} is not named on the page"
    assert re.search(r"(?i)agent policy", text), "Agent Policy is not named as a switch"
    assert re.search(r"(?i)capabilit", text), "API key capabilities are not named as a switch"
    assert _section(text, r"^Switches$") is not None, 'page has no "Switches" section'


def test_data_flows_in_production_nav_after_security_architecture():
    d = json.loads(_read(DOCS_JSON))
    found = False

    def walk(node):
        nonlocal found
        if isinstance(node, dict):
            if node.get("group") == "Production":
                pages = [p for p in node.get("pages", []) if isinstance(p, str)]
                assert "production/data-flows" in pages, pages
                i = pages.index("production/security-architecture")
                assert pages[i + 1] == "production/data-flows", pages
                found = True
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(d)
    assert found, "no Production nav group in docs.json"


# ---------------------------- bullet 3: chat assistants, no local model ---

def test_data_flows_says_chat_assistants_have_no_local_model_option():
    text = _page()
    flat = re.sub(r"\s+", " ", text)
    sentences = re.split(r"(?<=[.!?])\s", flat)
    assert any(
        re.search(r"(?i)assistant", s)
        and re.search(r"(?i)local model|ollama", s)
        and re.search(r"(?i)\b(no|not|cannot|can't|only|need|needs|require|requires)\b", s)
        for s in sentences
    ), "no sentence states that the chat assistants cannot run on a local model"


def test_security_air_gapped_paragraph_no_longer_says_ollama_covers_chat():
    body = _section(_read(SECURITY_MDX), r"^Air-gapped operation$")
    assert body is not None, 'security-architecture.mdx has no "Air-gapped operation" section'
    flat = re.sub(r"\s+", " ", body)
    for s in re.split(r"(?<=[.!?])\s", flat):
        if re.search(r"(?i)ollama|local model", s) and re.search(r"(?i)\bcover", s):
            assert not re.search(r"(?i)\bchat\b", s), f"still says local models cover chat: {s!r}"
    assert re.search(r"(?i)(chat|assistant)[^.]*(cloud|provider)", flat), \
        "air-gapped paragraph must say the chat assistants need a supported cloud provider"


def test_security_page_links_to_data_flows():
    text = _read(SECURITY_MDX)
    body = _section(text, r"^Where data can go$")
    assert body is not None, 'security-architecture.mdx has no "Where data can go" section'
    assert "/production/data-flows" in body


# ------------------------ bullet 4: what the sample-values switch skips ---

def test_data_flows_lists_what_sample_values_false_does_not_cover():
    text = _page()
    assert "DATRIS_AI_SAMPLE_VALUES=false" in text or "`DATRIS_AI_SAMPLE_VALUES`" in text
    low = re.sub(r"\s+", " ", text.lower())
    # Features that read data by design (as in field-protection.mdx).
    assert "search chat" in low, "Search chat not listed as reading data by design"
    assert "answer endpoint" in low, "the answer endpoint not listed as reading data by design"
    assert re.search(r"assistant[^.]*(query results|live read)", low), \
        "the Assistant reading query results / Live Read not listed"
    # Tap script review and diagnosis.
    assert re.search(r"tap script[^.]*review", low) and "diagnos" in low, \
        "tap script review and diagnosis not listed as uncovered"
    # Embeddings.
    assert re.search(r"embedding[^.]*(not covered|does not cover|not affected|unaffected|still)|"
                     r"(not cover|does not cover|doesn't cover)[^.]*embedding", low), \
        "embeddings not listed as uncovered by DATRIS_AI_SAMPLE_VALUES=false"


def test_field_protection_what_still_sees_raw_data_links_to_data_flows():
    body = _section(_read(FIELD_PROTECTION_MDX), r"^What still sees raw data$")
    assert body is not None
    assert "/production/data-flows" in body, \
        '"What still sees raw data" must link to /production/data-flows'


def test_ai_configuration_links_to_data_flows():
    assert "/production/data-flows" in _read(AI_CONFIG_MDX)


# ------------------------------------- bullet 6: provider parity on page ---

def test_data_flows_names_anthropic_and_openai_together():
    text = _page()
    assert "Anthropic" in text and "OpenAI" in text, "page must name Anthropic and OpenAI"
    # Each paragraph / table row / list item that names one names the other.
    blocks = re.split(r"\n\s*\n|\n(?=\s*[|\-*] )|\n(?=\s*\d+\. )", text)
    for b in blocks:
        has_a, has_o = "Anthropic" in b, "OpenAI" in b
        assert has_a == has_o, f"names only one of Anthropic/OpenAI: {b.strip()[:200]!r}"
