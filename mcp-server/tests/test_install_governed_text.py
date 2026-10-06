"""Story: governance controls production preset
(plans/stories/governance-controls-production-preset.md), Step 3.

With USE_USER_AUTH and USE_API_KEYS on, `GET /api/v1/health/services`
answers 401 to a keyless caller (RoleEnforcementInterceptor), and a fresh
install has no key yet. The installer's post-boot store check must
therefore be skipped, with a message, when the governance controls are on,
instead of retrying for minutes and reporting "Server not answering".

The run-level tests use DATRIS_NO_START=1 and never reach this check, so this
guard reads the script text."""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
INSTALL_SH = os.path.join(REPO_ROOT, "scripts", "install.sh")


def _lines():
    with open(INSTALL_SH, encoding="utf-8") as fh:
        return fh.read().splitlines()


def _code(line):
    return line.strip() and not line.strip().startswith("#")


def test_post_boot_store_check_is_skipped_with_a_message_when_governed():
    lines = _lines()
    probe = [i for i, l in enumerate(lines) if _code(l) and "curl" in l and "/api/v1/health/services" in l and "HEALTH=" in l]
    assert len(probe) == 1, "expected one keyless health/services probe loop: %s" % probe
    probe = probe[0]

    # Walk back to the `if` that opens the block holding the probe, tracking
    # nesting so inner if/fi pairs are skipped.
    depth = 0
    branch = None
    opener = None
    for i in range(probe - 1, -1, -1):
        s = lines[i].strip()
        if not _code(lines[i]):
            continue
        if s == "fi" or s.startswith("fi ") or s.startswith("fi;"):
            depth += 1
            continue
        if re.match(r"^(elif|else)\b", s) and depth == 0 and branch is None:
            branch = (i, s)
            continue
        if re.match(r"^if\b", s) and not re.search(r";\s*fi\b", s):
            if depth == 0:
                opener = (i, s)
                break
            depth -= 1
    assert opener is not None, "the probe is not inside an if block"
    assert branch is not None, "the probe must sit in an elif/else branch after a governed skip, not in the first branch"
    assert "GOVERNED_ON" in opener[1] and '"1"' in opener[1], "the block must first test GOVERNED_ON = 1: %s" % (opener,)
    assert "GOVERNED_ON" not in branch[1] or '!=' in branch[1], branch

    governed_branch = "\n".join(lines[opener[0] + 1:branch[0]])
    assert "Skipping the post-boot store check" in governed_branch
    assert "x-api-key" in governed_branch, "the skip message says how to run the check with a key"
    assert "HEALTH=" not in governed_branch, "the governed branch must not poll the endpoint"
