"""Story: Relicense the platform repo to Apache 2.0
(plans/stories/apache-license-1-platform-repo.md).

File-content checks for the Acceptance bullets that can be checked from
the source tree:
  1. No "AGPL" in README.md, docs/ or CONTRIBUTING.md, except the
     historical docs/changelog.mdx entries.
  2. `head -2 LICENSE` is the Apache 2.0 title; the file is the full
     canonical text including the appendix, byte-identical to the
     apache.org download (sha256 recorded below; e2e re-checks it live).
  4. Source-side half: each of the four Dockerfiles sets
     org.opencontainers.image.licenses="Apache-2.0" after its final FROM.
     (`docker inspect` on the released images is e2e.)
  7. mcp-server package metadata stays Apache-2.0 (regression guard;
     passes today).

Bullets 3 (gh licenseInfo after merge), 5 (dependency scan summaries on
the PR) and 6 (mintlify render without broken links) are e2e only."""
import hashlib
import os
import re

import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LICENSE = os.path.join(REPO_ROOT, "LICENSE")

DOCKERFILES = [
    "Dockerfile",
    "ui/Dockerfile",
    "mcp-server/Dockerfile",
    "tap-runner/Dockerfile",
]


def _read(rel):
    with open(os.path.join(REPO_ROOT, rel), encoding="utf-8") as f:
        return f.read()


def _agpl_hits():
    hits = []
    targets = [os.path.join(REPO_ROOT, "README.md"),
               os.path.join(REPO_ROOT, "CONTRIBUTING.md")]
    for root, dirs, files in os.walk(os.path.join(REPO_ROOT, "docs")):
        dirs[:] = [d for d in dirs if d not in ("node_modules", ".mintlify")]
        for name in files:
            targets.append(os.path.join(root, name))
    changelog = os.path.join(REPO_ROOT, "docs", "changelog.mdx")
    for path in targets:
        if os.path.abspath(path) == changelog:
            continue  # historical entries for earlier versions are allowed
        try:
            with open(path, encoding="utf-8") as f:
                for n, line in enumerate(f, 1):
                    if "AGPL" in line:
                        hits.append(f"{os.path.relpath(path, REPO_ROOT)}:{n}: {line.strip()}")
        except UnicodeDecodeError:
            continue
    return hits


def test_no_agpl_in_readme_docs_contributing():
    hits = _agpl_hits()
    assert hits == [], "AGPL still referenced:\n" + "\n".join(hits)


# sha256 of https://www.apache.org/licenses/LICENSE-2.0.txt, downloaded
# 2026-10-29 when these specs were written.
APACHE_2_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"


def test_license_head_is_apache_title():
    # The canonical file opens with a blank line, so `head -2` prints the
    # blank line and the title.
    with open(LICENSE, encoding="utf-8") as f:
        head_two = [f.readline().strip(), f.readline().strip()]
    assert "Apache License" in head_two
    with open(LICENSE, encoding="utf-8") as f:
        non_blank = [l.strip() for l in f if l.strip()][:2]
    assert non_blank == ["Apache License", "Version 2.0, January 2004"]


def test_license_sha256_matches_canonical_apache_text():
    with open(LICENSE, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()
    assert digest == APACHE_2_SHA256, (
        "LICENSE is not byte-identical to apache.org LICENSE-2.0.txt")


def test_license_is_full_canonical_apache_text():
    text = _read("LICENSE")
    assert "http://www.apache.org/licenses/" in text
    assert "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION" in text
    assert "END OF TERMS AND CONDITIONS" in text
    # Appendix kept, with the boilerplate left as template text.
    assert "APPENDIX: How to apply the Apache License to your work." in text
    assert "Copyright [yyyy] [name of copyright owner]" in text
    assert text.rstrip().endswith("limitations under the License.")
    assert "AFFERO" not in text and "GNU" not in text


@pytest.mark.parametrize("rel", DOCKERFILES)
def test_dockerfile_final_stage_has_apache_license_label(rel):
    lines = _read(rel).splitlines()
    from_idx = [i for i, l in enumerate(lines) if re.match(r"\s*FROM\s", l, re.I)]
    assert from_idx, f"{rel} has no FROM"
    final_stage = "\n".join(lines[from_idx[-1] + 1:])
    assert re.search(
        r'^\s*LABEL\s+.*org\.opencontainers\.image\.licenses="Apache-2\.0"',
        final_stage, re.M), f"{rel}: no Apache-2.0 licenses label after final FROM"


def test_mcp_server_package_license_unchanged():
    text = _read("mcp-server/pyproject.toml")
    assert 'license = {text = "Apache-2.0"}' in text
    assert "License :: OSI Approved :: Apache Software License" in text
