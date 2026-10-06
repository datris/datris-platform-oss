"""Story: doctor update check (plans/stories/doctor-update-check.md).

Installer side of the opt-out:
- the install.sh header documents DATRIS_UPDATE_CHECK, and
  docs/install-for-agents.mdx names it (test_agent_install_docs.py
  parses the header, so both must move together);
- a fresh `DATRIS_NO_START=1 sh scripts/install.sh` prints a notice naming
  DATRIS_UPDATE_CHECK=0, and with DATRIS_UPDATE_CHECK=0 exported the written
  .env holds that line. The notice is not printed on upgrade.

The run-level tests need a running Docker daemon (preflight) and GitHub
access (the installer downloads .env.example), so they skip without either."""
import os
import shutil
import subprocess

import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
INSTALL_SH = os.path.join(REPO_ROOT, "scripts", "install.sh")
AGENT_PAGE = os.path.join(REPO_ROOT, "docs", "install-for-agents.mdx")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _header():
    lines = _read(INSTALL_SH).splitlines()
    start = next(i for i, line in enumerate(lines) if "Honors these env vars" in line)
    end = next(i for i, line in enumerate(lines) if line.strip() == "set -eu")
    return "\n".join(lines[start + 1:end])


def test_install_sh_header_documents_update_check_opt_out():
    assert "DATRIS_UPDATE_CHECK" in _header()


def test_install_for_agents_page_names_update_check():
    assert "DATRIS_UPDATE_CHECK" in _read(AGENT_PAGE)


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


needs_stack = pytest.mark.skipif(
    not (_docker_running() and _online()),
    reason="install.sh needs a running Docker daemon and network access to GitHub",
)


def _run(install_dir, **env_overrides):
    env = {k: v for k, v in os.environ.items() if not k.startswith("DATRIS_") and not k.startswith("USE_")}
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
        return [line.strip() for line in fh if line.startswith("DATRIS_UPDATE_CHECK")]


@needs_stack
def test_fresh_install_prints_update_check_notice_and_not_on_upgrade(tmp_path):
    d = tmp_path / "fresh"
    r = _run(d)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "DATRIS_UPDATE_CHECK=0" in r.stdout
    assert "datris.ai" in r.stdout
    assert _env_lines(d) == [], "without the opt-out the installer writes no DATRIS_UPDATE_CHECK line"

    again = _run(d)
    assert again.returncode == 0, again.stdout + again.stderr
    assert "DATRIS_UPDATE_CHECK=0" not in again.stdout, "the notice is for fresh installs only"


@needs_stack
def test_fresh_install_with_opt_out_writes_it_to_env(tmp_path):
    d = tmp_path / "optout"
    r = _run(d, DATRIS_UPDATE_CHECK="0")
    assert r.returncode == 0, r.stdout + r.stderr
    assert _env_lines(d) == ["DATRIS_UPDATE_CHECK=0"]
