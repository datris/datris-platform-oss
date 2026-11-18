"""`datris delete --keep-data --json` (plans/stories/pipeline-delete-keep-data.md,
review finding 5): a server refusal prints the error JSON and exits 1, as
without --json; a successful keep-data delete prints the body and exits 0."""
import json
import os
import sys

from click.testing import CliRunner

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import cli  # noqa: E402

_REFUSAL = ("This pipeline writes to a vector store; its configuration cannot be deleted while keeping that data. "
            "Delete it with its data, or keep the pipeline.")


class _Mcp:
    def __init__(self, body):
        self.calls = []
        self.body = body

    def __call__(self, name, args=None):
        self.calls.append((name, dict(args or {})))
        return self.body


def test_keep_data_json_refusal_prints_the_error_json_and_exits_1(monkeypatch):
    stub = _Mcp({"error": _REFUSAL})
    monkeypatch.setattr(cli, "mcp", stub)
    res = CliRunner().invoke(cli.cli, ["delete", "orders", "--keep-data", "--json"])
    assert res.exit_code == 1, res.output
    assert stub.calls == [("delete_pipeline", {"pipeline": "orders", "keep_data": True})]
    assert json.loads(res.output) == {"error": _REFUSAL}


def test_keep_data_json_success_prints_the_body_and_exits_0(monkeypatch):
    body = {"warnings": [], "kept": ["postgres table datris.public.orders"]}
    monkeypatch.setattr(cli, "mcp", _Mcp(body))
    res = CliRunner().invoke(cli.cli, ["delete", "orders", "--keep-data", "--json"])
    assert res.exit_code == 0, res.output
    assert json.loads(res.output) == body
