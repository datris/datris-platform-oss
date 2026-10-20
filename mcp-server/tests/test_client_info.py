"""Story: MCP server moves to the MCP Python SDK 2.x
(plans/stories/mcp-sdk-2-migration.md).

On SDK 2.x the request context is passed to handlers (no `server.request_context`)
and InitializeRequestParams exposes `client_info` (snake_case). `_read_client_info`
takes the context and reads `client_info` before the legacy `clientInfo`, so the
Agents tab keeps showing each client's name and version."""
import asyncio
import os
import sys
from types import SimpleNamespace

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import server  # noqa: E402


def _ctx(client_params):
    return SimpleNamespace(session=SimpleNamespace(client_params=client_params))


# ------------------------------------------------------ Acceptance bullet 5 ---

def test_reads_snake_case_client_info():
    ctx = _ctx(SimpleNamespace(client_info=SimpleNamespace(name="claude-code", version="2.1.7")))
    assert server._read_client_info(ctx) == ("claude-code", "2.1.7")


def test_none_ctx_is_blank():
    assert server._read_client_info(None) == ("", "")


def test_reads_real_sdk_initialize_params():
    from mcp.types import Implementation, InitializeRequestParams, ClientCapabilities
    params = InitializeRequestParams(
        protocolVersion="2025-06-18",
        capabilities=ClientCapabilities(),
        clientInfo=Implementation(name="mcp-remote", version="0.1.29"),
    )
    assert server._read_client_info(_ctx(params)) == ("mcp-remote", "0.1.29")


def test_falls_back_to_camel_case_client_info():
    ctx = _ctx(SimpleNamespace(clientInfo=SimpleNamespace(name="cursor", version="1.4")))
    assert server._read_client_info(ctx) == ("cursor", "1.4")


def test_missing_session_params_is_blank():
    assert server._read_client_info(_ctx(None)) == ("", "")
    assert server._read_client_info(SimpleNamespace(session=None)) == ("", "")


# ---------------------------- Acceptance bullet 8 (unit half): ctx threading ---

def test_call_tool_threads_ctx_into_activity_record(monkeypatch):
    from mcp.types import CallToolRequestParams
    ctx = _ctx(SimpleNamespace(client_info=SimpleNamespace(name="claude-code", version="2.1.7")))
    captured = {}

    def record(*args, **kwargs):
        captured["args"], captured["kwargs"] = args, kwargs

    monkeypatch.setattr(server, "_dispatch", lambda n, a: '{"count":0,"pipelines":[]}')
    monkeypatch.setattr(server, "_activity_record", record)
    res = asyncio.run(server.call_tool(ctx, CallToolRequestParams(name="list_pipelines", arguments={})))
    assert res.is_error is False
    assert captured.get("kwargs", {}).get("ctx") is ctx, captured


def test_activity_record_stores_client_from_ctx(monkeypatch):
    ctx = _ctx(SimpleNamespace(client_info=SimpleNamespace(name="claude-code", version="2.1.7")))
    server._activity_buffer.clear()
    server._activity_sessions.pop("sess-client-info-test", None)
    server._activity_record("sess-client-info-test", "list_pipelines", "ok", 5, "",
                            {}, '{"count":0}', "", ctx=ctx)
    row = server._activity_buffer[-1]
    assert (row["client_name"], row["client_version"]) == ("claude-code", "2.1.7")
    sess = server._activity_sessions["sess-client-info-test"]
    assert (sess["client_name"], sess["client_version"]) == ("claude-code", "2.1.7")
