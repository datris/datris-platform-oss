"""Story: CLI sends DATRIS_API_KEY to the MCP server
(plans/stories/cli-mcp-api-key-and-server-version.md) — the CLI half.

Drives the real `_connect()` / `_call_tool()` path with `cli.aconnect_sse`
and `cli.httpx.AsyncClient` replaced by recording fakes, so no network is
touched. Asserts on the headers the CLI actually sends and on what a user
sees when the SSE GET comes back 401."""
import asyncio
import json
import os
import sys

import pytest
from click.testing import CliRunner

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import cli  # noqa: E402


# ---------------------------------------------------------------- fakes ---

class _Event:
    def __init__(self, event, data):
        self.event = event
        self.data = data


class _Resp:
    def __init__(self, status_code):
        self.status_code = status_code
        self.headers = {"content-type": "application/json" if status_code != 200 else "text/event-stream"}


class _Transport:
    """Shared record of every SSE GET and POST the CLI makes."""

    def __init__(self, sse_status=200, tool_text="[]"):
        self.sse_status = sse_status
        self.tool_text = tool_text
        self.sse_calls = []   # (method, url, headers)
        self.posts = []       # (url, headers, body)


def _install(monkeypatch, transport):
    class FakeSource:
        def __init__(self):
            self.response = _Resp(transport.sse_status)

        async def aiter_sse(self):
            if transport.sse_status != 200:
                # httpx_sse raises SSEError on a non-event-stream body
                from httpx_sse import SSEError
                raise SSEError("Expected response header Content-Type to contain 'text/event-stream'")
            yield _Event("endpoint", "/messages/?session_id=test")
            await asyncio.Event().wait()  # stay open until the reader is cancelled

    class FakeSseCM:
        def __init__(self, client, method, url, **kwargs):
            transport.sse_calls.append((method, url, dict(kwargs.get("headers") or {})))

        async def __aenter__(self):
            return FakeSource()

        async def __aexit__(self, *exc):
            return False

    class FakeClient:
        def __init__(self, *args, **kwargs):
            pass

        async def post(self, url, *args, **kwargs):
            body = kwargs.get("json")
            transport.posts.append((url, dict(kwargs.get("headers") or {}), body))
            if isinstance(body, dict) and "id" in body:
                if body.get("method") == "tools/call":
                    reply = {"jsonrpc": "2.0", "id": body["id"],
                             "result": {"content": [{"type": "text", "text": transport.tool_text}]}}
                else:
                    reply = {"jsonrpc": "2.0", "id": body["id"], "result": {}}
                await cli._responses.put(reply)
            return _Resp(202)

        async def get(self, *args, **kwargs):
            return _Resp(200)

        async def aclose(self):
            pass

    monkeypatch.setattr(cli, "aconnect_sse", FakeSseCM)
    monkeypatch.setattr(cli.httpx, "AsyncClient", FakeClient)


@pytest.fixture(autouse=True)
def _reset_cli_globals(monkeypatch):
    for name in ("_endpoint", "_post_client", "_sse_client", "_responses", "_reader_task", "_sse_cm"):
        monkeypatch.setattr(cli, name, None)
    monkeypatch.setattr(cli, "_msg_id", 0)
    yield


def _run_tool_call():
    async def go():
        try:
            return await cli._call_tool("list_pipelines")
        finally:
            await cli._disconnect()
    return asyncio.run(go())


def _methods(transport):
    return [b.get("method") for _, _, b in transport.posts]


# ------------------------------------------------------ Acceptance bullet 1 ---

def test_connect_sends_x_api_key_when_env_set(monkeypatch):
    monkeypatch.setenv("DATRIS_API_KEY", "abc")
    t = _Transport()
    _install(monkeypatch, t)

    _run_tool_call()

    assert len(t.sse_calls) == 1
    _, _, sse_headers = t.sse_calls[0]
    assert sse_headers.get("x-api-key") == "abc", f"SSE GET headers: {sse_headers}"

    assert _methods(t) == ["initialize", "notifications/initialized", "tools/call"]
    for url, headers, body in t.posts:
        assert headers.get("x-api-key") == "abc", f"POST {body.get('method')} headers: {headers}"


# ------------------------------------------------------ Acceptance bullet 2 ---

def test_connect_sends_no_api_key_when_env_unset(monkeypatch):
    monkeypatch.delenv("DATRIS_API_KEY", raising=False)
    url = "http://localhost:3000/sse?api_key=fromquery"
    monkeypatch.setattr(cli, "MCP_URL", url)
    t = _Transport()
    _install(monkeypatch, t)

    _run_tool_call()

    assert len(t.sse_calls) == 1
    _, sse_url, sse_headers = t.sse_calls[0]
    assert sse_url == url, "query-string api_key URL must be passed through untouched"
    assert not any(k.lower() == "x-api-key" for k in sse_headers)
    assert _methods(t) == ["initialize", "notifications/initialized", "tools/call"]
    for _, headers, _ in t.posts:
        assert not any(k.lower() == "x-api-key" for k in headers)


# ------------------------------------------------------ Acceptance bullet 3 ---

def test_connect_401_prints_one_line_hint(monkeypatch):
    monkeypatch.delenv("DATRIS_API_KEY", raising=False)
    t = _Transport(sse_status=401)
    _install(monkeypatch, t)

    result = CliRunner().invoke(cli.cli, ["pipelines"])

    assert result.exit_code == 1, result.output
    # a click.ClickException surfaces as SystemExit(1); anything else is an
    # unhandled exception that would print a traceback outside CliRunner
    assert isinstance(result.exception, SystemExit), repr(result.exception)
    assert "DATRIS_API_KEY" in result.output
    assert "Traceback" not in result.output
    assert "No endpoint from MCP server" not in result.output
    assert t.posts == [], "no POST should be attempted after a 401 on the SSE GET"


def test_connect_401_with_key_set_says_rejected(monkeypatch):
    monkeypatch.setenv("DATRIS_API_KEY", "wrong")
    t = _Transport(sse_status=401)
    _install(monkeypatch, t)

    result = CliRunner().invoke(cli.cli, ["pipelines"])

    assert result.exit_code == 1, result.output
    assert isinstance(result.exception, SystemExit), repr(result.exception)
    assert "rejected DATRIS_API_KEY" in result.output
    assert "export DATRIS_API_KEY" not in result.output
    assert "Traceback" not in result.output
    assert t.posts == []


def test_tool_result_invalid_key_exits_with_rejected_hint(monkeypatch):
    # The MCP server accepts any non-empty key at /sse; the Datris API
    # rejects a wrong one inside the tool call.
    monkeypatch.setenv("DATRIS_API_KEY", "abc")
    t = _Transport(tool_text=json.dumps({"error": "Invalid x-api-key: abc"}))
    _install(monkeypatch, t)

    result = CliRunner().invoke(cli.cli, ["pipelines"])

    assert result.exit_code == 1, result.output
    assert isinstance(result.exception, SystemExit), repr(result.exception)
    assert "rejected DATRIS_API_KEY" in result.output
    assert "abc" not in result.output
    assert "Traceback" not in result.output


def test_tool_data_mentioning_invalid_key_is_not_a_rejection(monkeypatch):
    # e.g. tap logs from a vendor API that also uses x-api-key
    monkeypatch.setenv("DATRIS_API_KEY", "abc")
    t = _Transport(tool_text=json.dumps([{"error": "vendor: Invalid x-api-key"}]))
    _install(monkeypatch, t)

    result = CliRunner().invoke(cli.cli, ["pipelines", "--json"])

    assert result.exit_code == 0, result.output
    assert "rejected DATRIS_API_KEY" not in result.output
    assert "vendor: Invalid x-api-key" in result.output
