"""`datris delete --keep-data` must not pretend to keep data: the server
always deletes a pipeline's destination data with its configuration, so the
flag exits non-zero before anything is deleted."""
import os
import sys

from click.testing import CliRunner

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import cli  # noqa: E402


class _Mcp:
    def __init__(self):
        self.calls = []

    def __call__(self, name, args=None):
        self.calls.append((name, dict(args or {})))
        return {"text": "deleted"}


def _run(argv):
    return CliRunner().invoke(cli.cli, argv, catch_exceptions=False)


def test_delete_keep_data_refuses_and_deletes_nothing(monkeypatch):
    stub = _Mcp()
    monkeypatch.setattr(cli, "mcp", stub)
    res = _run(["delete", "orders", "--keep-data"])
    assert res.exit_code != 0, res.output
    assert stub.calls == [], "nothing may be deleted when --keep-data is refused"
    assert "--keep-data is not supported" in res.output
    assert "(data kept)" not in res.output


def test_delete_without_keep_data_calls_delete_pipeline(monkeypatch):
    stub = _Mcp()
    monkeypatch.setattr(cli, "mcp", stub)
    res = _run(["delete", "orders"])
    assert res.exit_code == 0, res.output
    assert stub.calls == [("delete_pipeline", {"pipeline": "orders"})]
    assert "(data kept)" not in res.output


def test_cli_docs_no_longer_offer_keep_data():
    root = os.path.join(os.path.dirname(__file__), "..", "..")
    with open(os.path.join(root, "docs", "cli.mdx"), encoding="utf-8") as fh:
        text = fh.read()
    assert "datris delete my_pipeline --keep-data" not in text
    assert "`--keep-data` is not supported" in text


class _McpWithWarnings(_Mcp):
    def __init__(self, warnings):
        super().__init__()
        self.warnings = warnings

    def __call__(self, name, args=None):
        self.calls.append((name, dict(args or {})))
        return {"warnings": self.warnings}


def test_delete_prints_each_warning_after_the_success_line(monkeypatch):
    stub = _McpWithWarnings([
        "Unity Catalog still holds main.sales.orders; have an admin drop it",
        "second warning",
    ])
    monkeypatch.setattr(cli, "mcp", stub)
    res = _run(["delete", "orders"])
    assert res.exit_code == 0, res.output
    lines = [l.strip() for l in res.output.splitlines() if l.strip()]
    assert lines[0] == "✓ Pipeline 'orders' deleted", lines
    assert lines[1:] == [
        "⚠ Unity Catalog still holds main.sales.orders; have an admin drop it",
        "⚠ second warning",
    ], lines


def test_delete_with_empty_warnings_prints_only_the_success_line(monkeypatch):
    monkeypatch.setattr(cli, "mcp", _McpWithWarnings([]))
    res = _run(["delete", "orders"])
    assert res.exit_code == 0, res.output
    assert "⚠" not in res.output
