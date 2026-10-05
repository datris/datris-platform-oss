"""Story: governance controls production preset
(plans/stories/governance-controls-production-preset.md).

Runs scripts/install.sh for real with DATRIS_NO_START=1 in a temp
DATRIS_DIR and checks what DATRIS_GOVERNED does to the seeded .env:
- fresh install with the option: the four variables are `true`, mode 600,
  and the first-login hint is printed;
- re-run against that directory: the upgrade notice is printed and the
  .env is byte-identical;
- fresh install without the option: none of the four is set;
- value parsing: True/on mean on, false on upgrade prints no notice, an
  unknown value stops before any .env is written.

The installer needs a running Docker daemon (preflight) and downloads
.env.example from GitHub, so these tests skip when either is missing. The
script runs in a new session with no controlling terminal, so it never
prompts."""
import os
import shutil
import stat
import subprocess

import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
INSTALL_SH = os.path.join(REPO_ROOT, "scripts", "install.sh")
GOVERNANCE_VARS = ("USE_USER_AUTH", "USE_API_KEYS", "USE_AUDIT_LOG", "USE_AGENT_POLICY")


def _docker_running():
    if shutil.which("docker") is None:
        return False
    try:
        return subprocess.run(["docker", "info"], capture_output=True, timeout=20).returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def _online():
    if shutil.which("curl") is None:
        return False
    try:
        r = subprocess.run(
            ["curl", "-fsS", "--max-time", "10", "-o", os.devnull,
             "https://raw.githubusercontent.com/datris/datris-platform-oss/main/.env.example"],
            capture_output=True, timeout=20,
        )
        return r.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


pytestmark = pytest.mark.skipif(
    not (_docker_running() and _online()),
    reason="install.sh needs a running Docker daemon and network access to GitHub",
)


def _run(install_dir, **env_overrides):
    env = {k: v for k, v in os.environ.items() if k not in GOVERNANCE_VARS and not k.startswith("DATRIS_")}
    for k in ("OPENAI_API_KEY", "XAI_API_KEY", "AZURE_OPENAI_API_KEY", "AI_PROVIDER"):
        env.pop(k, None)
    env.update({"DATRIS_DIR": str(install_dir), "DATRIS_NO_START": "1", "ANTHROPIC_API_KEY": "x"})
    env.update(env_overrides)
    return subprocess.run(
        ["sh", INSTALL_SH],
        env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=300,
        start_new_session=True,
    )


def _env_lines(install_dir):
    with open(os.path.join(str(install_dir), ".env"), encoding="utf-8") as fh:
        return [line.strip() for line in fh if line.startswith("USE_")]


def test_fresh_install_with_datris_governed_writes_all_four_true_and_rerun_leaves_env_untouched(tmp_path):
    d = tmp_path / "gov-on"
    r = _run(d, DATRIS_GOVERNED="1")
    assert r.returncode == 0, r.stdout + r.stderr
    assert sorted(_env_lines(d)) == sorted(v + "=true" for v in GOVERNANCE_VARS)
    mode = stat.S_IMODE(os.stat(str(d / ".env")).st_mode)
    assert mode == 0o600, oct(mode)
    assert "Bootstrap login" in r.stdout, "the first-login hint is printed under DATRIS_NO_START too"

    before = (d / ".env").read_bytes()
    again = _run(d, DATRIS_GOVERNED="1")
    assert again.returncode == 0, again.stdout + again.stderr
    out = again.stdout + again.stderr
    assert "DATRIS_GOVERNED is ignored on upgrade" in out
    for v in GOVERNANCE_VARS:
        assert v + "=true" in out, "the upgrade notice prints the line to add for " + v
    assert (d / ".env").read_bytes() == before, "an upgrade never edits the existing .env"


def test_fresh_install_without_datris_governed_sets_none_of_the_four(tmp_path):
    d = tmp_path / "gov-off"
    r = _run(d)
    assert r.returncode == 0, r.stdout + r.stderr
    assert _env_lines(d) == []
    assert "Governance controls are on" not in r.stdout


def test_datris_governed_values_are_read_the_same_way_in_both_branches(tmp_path):
    d = tmp_path / "gov-true"
    r = _run(d, DATRIS_GOVERNED="True")
    assert r.returncode == 0, r.stdout + r.stderr
    assert sorted(_env_lines(d)) == sorted(v + "=true" for v in GOVERNANCE_VARS)

    off = tmp_path / "gov-false"
    assert _run(off, DATRIS_GOVERNED="off").returncode == 0
    assert _env_lines(off) == []
    upgrade = _run(off, DATRIS_GOVERNED="false")
    assert upgrade.returncode == 0, upgrade.stdout + upgrade.stderr
    assert "DATRIS_GOVERNED is ignored on upgrade" not in upgrade.stdout + upgrade.stderr

    bad = tmp_path / "gov-bad"
    r = _run(bad, DATRIS_GOVERNED="maybe")
    assert r.returncode != 0
    assert "DATRIS_GOVERNED must be 1 or 0" in r.stderr
    assert not (bad / ".env").exists()
