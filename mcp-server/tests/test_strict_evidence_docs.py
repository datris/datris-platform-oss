"""Story: Strict evidence mode: a governed write fails when its provenance
stamp or audit entry cannot be recorded (plans/stories/strict-evidence-mode.md).

File-content checks for the docs-shaped Acceptance bullet:
  "No .mdx page and no listed website file states 'every row' or
   'every action/write' as recorded without the condition under which it
   holds."

- Every docs/**/*.mdx page except changelog.mdx (past entries are not
  rewritten): a line that claims a stamp/receipt on every row, or an audit
  record of every write/change/denied request, also carries the condition
  (stamping on / stamping succeeds, the audit log on, or a strict-mode
  pointer). One line = one paragraph, bullet or table row in these pages.
- README.md: the "records who did what" / "same audit trail" clauses carry
  the opt-in and the strict pointer.
- The website files the story lists (separate repo, skipped when absent): the
  same rule, with the condition allowed on the claim line or the line next to
  it (title/body pairs in the .astro data arrays).
- The strict sections the claims point to exist: audit-log.mdx and
  provenance.mdx have a "Strict mode" section (AUDIT_LOG_STRICT, the 503, the
  unrecorded metric, the in-memory residual; PROVENANCE_STRICT and the XML
  exemption); configuration-reference.mdx has both rows; doctor.mdx has both
  checks.

`mintlify broken-links` and the website build are checked at review / e2e."""
import glob
import os
import re

import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
README = os.path.join(REPO_ROOT, "README.md")
WEBSITE = "/Users/toddfearn/src/datris/datris-website"
WEBSITE_FILES = [
    "src/components/Hero.astro",
    "src/components/Overview.astro",
    "src/components/Pillars.astro",
    "src/components/Governance.astro",
    "src/components/LiveRead.astro",
    "src/pages/security.astro",
    "src/pages/compare/mcp-gateways.astro",
    "src/pages/platform/mcp.astro",
    "src/content/videos/what-is-a-data-control-plane.md",
    "public/llms.txt",
]

# A per-row evidence claim: "every/each (landed) row" next to a provenance word.
ROW_CLAIM = re.compile(r"\b(every|each)\s+(landed\s+)?row\b", re.IGNORECASE)
ROW_EVIDENCE = re.compile(
    r"provenance|\bstamp(s|ed|ing)?\b|_datris_|receipt|\bcites?\b|\btraced\b|traceable|defended|\brun id\b|\bcarr(y|ies)\b",
    re.IGNORECASE,
)
SENTENCE_SPLIT = re.compile(r"(?<=[.;!?])\s+|\s+[—–]\s+")
# Explicit evidence claims that need a condition wherever they appear.
FIXED_CLAIMS = [
    re.compile(p, re.IGNORECASE)
    for p in (
        r"per landed row",
        r"provenance on every answer",
        r"record every run with provenance",
        r"every create, change, run(,| and) delete",
        r"every platform write",
        r"every denied request",
        r"complete ledger",
        r"records who did what",
        r"same audit trail every time",
        r"every run recorded",
        r"every change\b[^.]*\baudit log",
    )
]
# The condition under which the claim holds, or a pointer to strict mode.
CONDITION = re.compile(
    r"stamping (is |turned |switched )?on\b"
    r"|stamping succeeds"
    r"|with (provenance )?stamping"
    r"|audit log (is |are )?(turned |switched )?on\b"
    r"|with the audit log"
    r"|when the audit log"
    r"|USE_AUDIT_LOG"
    r"|\bstrict mode\b|_STRICT\b|#strict-mode"
    r"|best[- ]effort",
    re.IGNORECASE,
)


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _is_claim(line, fixed=FIXED_CLAIMS):
    """A row claim counts only when the provenance word is in the same
    sentence (a long table row can mention "every row" and "stamping" in
    unrelated clauses); the fixed claims count anywhere on the line."""
    for sentence in SENTENCE_SPLIT.split(line):
        if ROW_CLAIM.search(sentence) and ROW_EVIDENCE.search(sentence):
            return True
    return any(p.search(line) for p in fixed)


def _unconditioned(lines, window=0, fixed=FIXED_CLAIMS):
    out = []
    for i, line in enumerate(lines):
        if not _is_claim(line, fixed):
            continue
        ctx = "\n".join(lines[max(0, i - window): i + window + 1])
        if not CONDITION.search(ctx):
            out.append((i + 1, line.strip()[:200]))
    return out


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


def test_no_docs_page_claims_every_row_or_every_write_without_the_condition():
    offenders = []
    for path in sorted(glob.glob(os.path.join(DOCS, "**", "*.mdx"), recursive=True)):
        rel = os.path.relpath(path, DOCS)
        if rel == "changelog.mdx":
            continue
        for n, line in _unconditioned(_read(path).splitlines()):
            offenders.append(f"docs/{rel}:{n}: {line}")
    assert not offenders, "unconditioned evidence claims:\n" + "\n".join(offenders)


def test_readme_audit_claims_carry_the_opt_in_and_strict_pointer():
    lines = _read(README).splitlines()
    # The story names README.md:31 and :34 only (the audit clauses).
    readme_claims = [re.compile(r"records who did what|same audit trail every time", re.IGNORECASE)]
    offenders = _unconditioned(lines, fixed=readme_claims)
    assert not offenders, "README.md:\n" + "\n".join(f"{n}: {l}" for n, l in offenders)
    audit_lines = [l for l in lines if re.search(r"records who did what|same audit trail", l, re.IGNORECASE)]
    assert audit_lines, "the README audit clauses are gone; update this test with their new wording"
    for l in audit_lines:
        assert re.search(r"\bstrict mode\b|_STRICT\b|#strict-mode", l, re.IGNORECASE), "README audit clause has no strict pointer: " + l[:200]


@pytest.mark.skipif(not os.path.isdir(WEBSITE), reason="datris-website checkout not present")
def test_listed_website_files_claim_nothing_without_the_condition():
    offenders = []
    for rel in WEBSITE_FILES:
        path = os.path.join(WEBSITE, rel)
        assert os.path.exists(path), "listed website file is missing: " + rel
        for n, line in _unconditioned(_read(path).splitlines(), window=1):
            offenders.append(f"{rel}:{n}: {line}")
    assert not offenders, "unconditioned evidence claims on the website:\n" + "\n".join(offenders)


@pytest.mark.skipif(not os.path.isdir(WEBSITE), reason="datris-website checkout not present")
def test_website_security_page_strict_sentence_does_not_claim_xml():
    text = _read(os.path.join(WEBSITE, "src/pages/security.astro"))
    strict = [l for l in text.splitlines() if re.search(r"\bstrict mode\b|_STRICT\b", l, re.IGNORECASE)]
    assert strict, "security.astro has no sentence on strict mode"
    for l in strict:
        assert not re.search(r"\bevery (pipeline|source|format)\b|including XML", l, re.IGNORECASE), l.strip()[:200]


def test_audit_log_page_has_a_strict_mode_section():
    text = _read(os.path.join(DOCS, "audit-log.mdx"))
    sec = _section(text, r"^strict mode")
    assert sec is not None, "docs/audit-log.mdx has no 'Strict mode' section"
    assert "AUDIT_LOG_STRICT" in sec
    assert "503" in sec, "the refusal status is documented"
    assert "datris_audit_unrecorded_total" in sec
    assert re.search(r"in[- ]memory", sec, re.IGNORECASE), "the in-memory residual is stated"
    assert re.search(r"hard kill|killed|crash", sec, re.IGNORECASE), "the hard-kill residual is stated"
    assert "datris_audit_dropped_total" in text, "the default-mode drop metric is still documented"


def test_provenance_page_has_a_strict_mode_section_naming_the_xml_exemption():
    text = _read(os.path.join(DOCS, "provenance.mdx"))
    sec = _section(text, r"^strict mode")
    assert sec is not None, "docs/provenance.mdx has no 'Strict mode' section"
    assert "PROVENANCE_STRICT" in sec
    assert re.search(r"XML", sec) and re.search(r"exempt", sec, re.IGNORECASE), "XML sources are named as exempt"
    assert re.search(r"unstamped", sec, re.IGNORECASE), "XML still loads unstamped under strict"
    # What happens today when stamping fails is documented (default mode).
    assert re.search(r"stamping skipped|loads? unstamped|loaded unstamped", text, re.IGNORECASE)


def test_configuration_reference_lists_both_switches():
    text = _read(os.path.join(DOCS, "configuration-reference.mdx"))
    rows = {k: [l for l in text.splitlines() if l.startswith("|") and f"`{k}`" in l] for k in ("AUDIT_LOG_STRICT", "PROVENANCE_STRICT")}
    for k, found in rows.items():
        assert found, f"configuration-reference.mdx has no {k} row"
        assert re.search(r"`false`", found[0]), f"{k} documents its default false: {found[0][:200]}"
    assert re.search(r"XML", rows["PROVENANCE_STRICT"][0]), "the PROVENANCE_STRICT row names the XML exemption"


def test_doctor_page_documents_both_checks():
    text = _read(os.path.join(DOCS, "doctor.mdx"))
    assert "`audit.strict`" in text
    assert "`provenance.strict`" in text
