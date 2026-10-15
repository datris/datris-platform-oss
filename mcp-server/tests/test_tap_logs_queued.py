"""Story: Runner overload protection (plans/stories/runner-overload-protection.md),
Acceptance bullet 6, the CLI half: `datris tap logs <name>` appends
`, queued Nms` to a run's summary line when its `queuedMs` is > 0, and prints
nothing about queueing when it is 0 or absent (logs stored before the change).

`cli.mcp` (the synchronous MCP-call helper) is monkeypatched, so no transport
is touched."""
import os
import sys

from click.testing import CliRunner

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import cli  # noqa: E402

LOGS = [
    {"status": "success", "runTime": "2026-10-14T10:00:00Z", "recordCount": 5, "durationMs": 15300,
     "mode": "run", "queuedMs": 12300},
    {"status": "success", "runTime": "2026-10-14T09:00:00Z", "recordCount": 4, "durationMs": 900,
     "mode": "run", "queuedMs": 0},
    {"status": "success", "runTime": "2026-10-14T08:00:00Z", "recordCount": 3, "durationMs": 800,
     "mode": "run"},
]


def _run(monkeypatch, logs):
    calls = []

    def fake(name, args=None):
        calls.append((name, dict(args or {})))
        return logs

    monkeypatch.setattr(cli, "mcp", fake)
    res = CliRunner().invoke(cli.cli, ["tap", "logs", "my_tap"], catch_exceptions=False)
    assert res.exit_code == 0, res.output
    assert calls and calls[0][0] == "get_tap_logs"
    return res.output


def test_tap_logs_prints_queued_time_when_present(monkeypatch):
    out = _run(monkeypatch, LOGS)
    lines = [l for l in out.splitlines() if "2026-10-14T" in l]
    assert len(lines) == 3, out
    assert "queued 12300ms" in lines[0], lines[0]
    assert lines[0].rstrip().endswith(", queued 12300ms"), lines[0]


def test_tap_logs_prints_nothing_about_queueing_when_zero_or_absent(monkeypatch):
    out = _run(monkeypatch, LOGS)
    lines = [l for l in out.splitlines() if "2026-10-14T" in l]
    assert "queued" not in lines[1], lines[1]
    assert "queued" not in lines[2], lines[2]
    only_old = _run(monkeypatch, LOGS[1:])
    assert "queued" not in only_old, only_old
