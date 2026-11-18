"""`datris delete`: without flags it deletes the pipeline and its data;
`--keep-data` (plans/stories/pipeline-delete-keep-data.md) calls
`delete_pipeline` with `keep_data`, prints "deleted (data kept)" and each
`kept` entry, and a server refusal prints the error and exits 1."""
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


class _McpWithBody(_Mcp):
    def __init__(self, body):
        super().__init__()
        self.body = body

    def __call__(self, name, args=None):
        self.calls.append((name, dict(args or {})))
        return self.body


def test_delete_keep_data_calls_delete_pipeline_with_keep_data_and_prints_the_kept_entries(monkeypatch):
    stub = _McpWithBody({
        "warnings": [],
        "kept": ["postgres table datris.public.orders", "object-store prefix dev-data.orders"],
    })
    monkeypatch.setattr(cli, "mcp", stub)
    res = _run(["delete", "orders", "--keep-data"])
    assert res.exit_code == 0, res.output
    assert stub.calls == [("delete_pipeline", {"pipeline": "orders", "keep_data": True})]
    assert "deleted (data kept)" in res.output, res.output
    assert "postgres table datris.public.orders" in res.output
    assert "object-store prefix dev-data.orders" in res.output
    assert "not supported" not in res.output


def test_delete_keep_data_refusal_prints_the_server_error_and_exits_1(monkeypatch):
    refusal = ("This pipeline writes to a vector store; its configuration cannot be deleted while keeping that data. "
               "Delete it with its data, or keep the pipeline.")
    stub = _McpWithBody({"error": refusal})
    monkeypatch.setattr(cli, "mcp", stub)
    res = CliRunner().invoke(cli.cli, ["delete", "orders", "--keep-data"])
    assert res.exit_code == 1, res.output
    assert stub.calls == [("delete_pipeline", {"pipeline": "orders", "keep_data": True})]
    assert refusal in res.output
    assert "(data kept)" not in res.output


def test_delete_without_keep_data_calls_delete_pipeline(monkeypatch):
    stub = _Mcp()
    monkeypatch.setattr(cli, "mcp", stub)
    res = _run(["delete", "orders"])
    assert res.exit_code == 0, res.output
    assert stub.calls == [("delete_pipeline", {"pipeline": "orders"})]
    assert "(data kept)" not in res.output


def test_cli_docs_document_keep_data_as_working():
    root = os.path.join(os.path.dirname(__file__), "..", "..")
    with open(os.path.join(root, "docs", "cli.mdx"), encoding="utf-8") as fh:
        text = fh.read()
    assert "`--keep-data` is not supported" not in text
    assert "copy it or point a new pipeline at the same prefix before deleting" not in text
    paragraphs = [p for p in text.split("\n\n") if "--keep-data" in p]
    assert paragraphs, "cli.mdx must document --keep-data"
    assert any("kept" in p.lower() for p in paragraphs), \
        "cli.mdx must say --keep-data keeps the data and lists what was kept: " + repr(paragraphs)


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
