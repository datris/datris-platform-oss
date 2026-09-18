"""Story: Scratch results over MCP and the CLI, and the KEEP-OR-SCRATCH rule
(plans/stories/scratch-mcp-cli-prompts.md) — the CLI half.

Pins `datris pipeline result <token> [--offset] [--limit] [--out] [--json]`
by monkeypatching `cli.mcp` (the synchronous MCP-call helper every command
goes through) so no transport is touched."""
import json
import os
import sys

import pytest
from click.testing import CliRunner

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import cli  # noqa: E402


class _Mcp:
    """Records every mcp(name, args) call and serves canned pages."""

    def __init__(self, pages=None, single=None):
        self.calls = []
        self.pages = list(pages or [])
        self.single = single

    def __call__(self, name, args=None):
        self.calls.append((name, dict(args or {})))
        if self.single is not None:
            return self.single
        if self.pages:
            return self.pages.pop(0)
        return {"records": [], "rowCount": 0, "returnedCount": 0, "offset": 0, "truncated": False}


def _run(argv):
    return CliRunner().invoke(cli.cli, argv, catch_exceptions=False)


# ------------------------------------------------------ Acceptance bullet 5 ---

def test_pipeline_result_calls_get_pipeline_result_once_with_token(monkeypatch):
    stub = _Mcp(single={"records": [{"id": 1}], "rowCount": 1, "returnedCount": 1, "offset": 0,
                        "truncated": False, "resultUri": "s3a://x/y", "resultExpiresAt": "2026-09-19T00:00:00Z"})
    monkeypatch.setattr(cli, "mcp", stub)
    res = _run(["pipeline", "result", "TOK"])
    assert res.exit_code == 0, res.output
    assert len(stub.calls) == 1
    name, args = stub.calls[0]
    assert name == "get_pipeline_result"
    assert args.get("pipeline_token") == "TOK"
    assert "offset" not in args and "limit" not in args


def test_pipeline_result_prints_showing_n_of_m_and_records(monkeypatch):
    stub = _Mcp(single={"records": [{"id": 1}, {"id": 2}], "rowCount": 2, "returnedCount": 2, "offset": 0,
                        "truncated": False, "resultUri": "s3a://x/y", "resultExpiresAt": "2026-09-19T00:00:00Z"})
    monkeypatch.setattr(cli, "mcp", stub)
    res = _run(["pipeline", "result", "TOK"])
    assert res.exit_code == 0, res.output
    assert "showing 2 of 2" in res.output
    assert "expires" in res.output.lower()
    assert '"id": 1' in res.output or "'id': 1" in res.output or "1" in res.output


def test_pipeline_result_json_prints_raw_payload(monkeypatch):
    payload = {"records": [{"id": 1}], "rowCount": 1, "returnedCount": 1, "offset": 0, "truncated": False,
               "resultUri": "s3a://x/y", "resultExpiresAt": "2026-09-19T00:00:00Z"}
    stub = _Mcp(single=payload)
    monkeypatch.setattr(cli, "mcp", stub)
    res = _run(["pipeline", "result", "TOK", "--json"])
    assert res.exit_code == 0, res.output
    assert json.loads(res.output) == payload


def test_pipeline_result_offset_and_limit_pass_through(monkeypatch):
    stub = _Mcp(single={"records": [], "rowCount": 0, "returnedCount": 0, "offset": 40, "truncated": False,
                        "resultUri": "s3a://x/y", "resultExpiresAt": "2026-09-19T00:00:00Z"})
    monkeypatch.setattr(cli, "mcp", stub)
    res = _run(["pipeline", "result", "TOK", "--offset", "40", "--limit", "20"])
    assert res.exit_code == 0, res.output
    _, args = stub.calls[0]
    assert args.get("offset") == 40
    assert args.get("limit") == 20


def test_pipeline_result_out_pages_until_not_truncated(monkeypatch, tmp_path):
    pages = [
        {"records": [{"n": 1}, {"n": 2}], "rowCount": 5, "returnedCount": 2, "offset": 0, "truncated": True},
        {"records": [{"n": 3}, {"n": 4}], "rowCount": 5, "returnedCount": 2, "offset": 2, "truncated": True},
        {"records": [{"n": 5}], "rowCount": 5, "returnedCount": 1, "offset": 4, "truncated": False},
    ]
    stub = _Mcp(pages=pages)
    monkeypatch.setattr(cli, "mcp", stub)
    out = tmp_path / "rows.jsonl"
    res = _run(["pipeline", "result", "TOK", "--limit", "2", "--out", str(out)])
    assert res.exit_code == 0, res.output

    assert [c[0] for c in stub.calls] == ["get_pipeline_result"] * 3
    offsets = [c[1].get("offset", 0) for c in stub.calls]
    assert offsets == sorted(offsets) and len(set(offsets)) == 3, offsets
    assert all(c[1].get("pipeline_token") == "TOK" for c in stub.calls)
    assert all(c[1].get("limit") == 2 for c in stub.calls)

    lines = out.read_text().splitlines()
    assert [json.loads(l) for l in lines] == [{"n": 1}, {"n": 2}, {"n": 3}, {"n": 4}, {"n": 5}]
    assert "5" in res.output  # total written


def test_pipeline_result_404_and_410_are_explained(monkeypatch, tmp_path):
    # The endpoint's own 404 body (QueryAPIController.errorBody of ScratchResultException).
    monkeypatch.setattr(cli, "mcp", _Mcp(single={
        "error": "Only scratch pipelines have a result; job 'TOK' did not write one"}))
    res = _run(["pipeline", "result", "TOK"])
    assert "only scratch pipelines have a result" in res.output.lower(), res.output

    # Spring's default 404 body from a server that predates the route (version mismatch).
    monkeypatch.setattr(cli, "mcp", _Mcp(single={
        "timestamp": "2026-09-18T14:00:00.000+00:00", "status": 404, "error": "Not Found",
        "path": "/api/v1/pipeline/result"}))
    res = _run(["pipeline", "result", "TOK"])
    assert "only scratch pipelines have a result" in res.output.lower(), res.output
    assert "predates scratch results" in res.output.lower(), res.output

    # The endpoint's own 410 body.
    monkeypatch.setattr(cli, "mcp", _Mcp(single={
        "error": "Scratch results expire after 24 hour(s); this one is gone — run the pipeline again"}))
    res = _run(["pipeline", "result", "TOK"])
    assert "expired" in res.output.lower() and "run the pipeline again" in res.output.lower(), res.output


def test_existing_flat_commands_unchanged():
    names = set(cli.cli.commands.keys())
    assert {"pipelines", "ingest", "doctor"} <= names
    assert "pipeline" in names, "pipeline group missing"
