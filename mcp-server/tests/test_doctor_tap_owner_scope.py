"""plans/stories/tap-runs-hold-owner-scope.md: the server's new `tap.owner_scope`
row (warn at most in this release) reaches `datris doctor` unchanged, and the
CLI needs no change for it.

These pin what the story says about the Python side ("No Python change:
merge_report passes server rows through, and run_doctor returns them"), so
they pass before and after the server change:

- the row passes through merge_report byte for byte;
- a warn from it makes a full `datris doctor` exit 1 (warn), never 2 (error);
- `datris doctor --pre-upgrade` never asks the server, so a server finding can
  neither change its exit code nor withhold the upgrade command.
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import doctor as doc  # noqa: E402

from test_doctor import FakeRunner  # noqa: E402

OWNER_ROW = doc.result(
    "tap.owner_scope", "warn",
    "2 tap(s) will read or feed less in the next release: rita-finance -> finance-ledger (session:rita); "
    "orphan -> sales-orders (gone-agent)",
    "Point each tap at a pipeline its owner may load into, or recreate it as an identity that may.",
    surface="server", ms=3,
)


def _server_report(*rows):
    return {"doctorVersion": 1, "surface": {"server": "1.62.0"}, "checks": list(rows)}


def test_server_tap_owner_scope_row_passes_through_merge_report_unchanged():
    before = json.dumps(OWNER_ROW, sort_keys=True)
    report = doc.merge_report(_server_report(OWNER_ROW), [doc.result("volumes.anonymous", "ok", "named")], "1.62.0")
    rows = [c for c in report["checks"] if c["id"] == "tap.owner_scope"]
    assert len(rows) == 1
    assert json.dumps(rows[0], sort_keys=True) == before
    assert report["summary"]["warn"] == 1 and report["summary"]["error"] == 0


def test_a_tap_owner_scope_warning_is_exit_1_never_2():
    report = doc.merge_report(_server_report(OWNER_ROW), [doc.result("volumes.anonymous", "ok", "named")], "1.62.0")
    assert doc.exit_code(report) == 1
    text = doc.render_human(report)
    assert "! tap.owner_scope" in text
    assert "rita-finance -> finance-ledger (session:rita)" in text


def _no_network(monkeypatch):
    monkeypatch.delenv("MULTI_TENANT", raising=False)
    monkeypatch.setenv("DATRIS_UPDATE_CHECK", "0")
    monkeypatch.setattr(doc, "check_mcp_reachable", lambda *a, **k: doc.result("mcp.reachable", "ok", ""))
    monkeypatch.setattr(doc, "Runner", lambda compose_file=None, project_dir=None: FakeRunner({}, project_dir, docker=False))


def test_pre_upgrade_never_asks_the_server_so_a_server_finding_cannot_withhold_the_upgrade_command(tmp_path, monkeypatch):
    from click.testing import CliRunner
    import cli
    _no_network(monkeypatch)
    calls = []

    def fake_fetch(*a, **k):
        calls.append(a)
        return _server_report(OWNER_ROW), {"version": "1.62.0"}, None

    monkeypatch.setattr(doc, "fetch_server", fake_fetch)
    (tmp_path / ".env").write_text("USE_API_KEYS=true\n")
    res = CliRunner().invoke(cli.cli, ["doctor", "--pre-upgrade", "--project-dir", str(tmp_path)])
    assert calls == [], "pre-upgrade must not query the server"
    assert res.exit_code == 0, res.output
    assert "Ready to upgrade" in res.output
    assert "docker compose pull && docker compose up -d --remove-orphans" in res.output
    assert "tap.owner_scope" not in res.output


def test_full_doctor_shows_the_row_and_its_remediation_and_exits_1(tmp_path, monkeypatch):
    from click.testing import CliRunner
    import cli
    _no_network(monkeypatch)
    monkeypatch.setattr(doc, "fetch_server", lambda *a, **k: (_server_report(OWNER_ROW), {"version": "1.62.0"}, None))
    (tmp_path / ".env").write_text("USE_API_KEYS=true\n")
    res = CliRunner().invoke(cli.cli, ["doctor", "--project-dir", str(tmp_path)])
    assert "tap.owner_scope" in res.output
    assert "rita-finance -> finance-ledger (session:rita)" in res.output
    assert "Point each tap at a pipeline" in res.output
    # host checks all skip without Docker; the only warn is the server row
    assert res.exit_code == 1, res.output
