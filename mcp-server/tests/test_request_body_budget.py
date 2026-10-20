"""Story: MCP server moves to the MCP Python SDK 2.x
(plans/stories/mcp-sdk-2-migration.md).

SDK 2.x caps HTTP request bodies at 4 MiB by default, which would break inline
`upload_file` / `create_pipeline` content and `restore_catalog` archives. The
server derives its cap from the documented upload ceiling DATRIS_MAX_UPLOAD_MB
(default 1024), scaled 4/3 for base64 plus 1 MiB of JSON-RPC envelope, and
passes it to every HTTP transport."""
import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import server  # noqa: E402

MIB = 1024 * 1024
SDK_DEFAULT_CAP = 4 * MIB


def _expected(mb: int) -> int:
    return mb * MIB * 4 // 3 + MIB


# ------------------------------------------------------ Acceptance bullet 4 ---

def test_cap_tracks_datris_max_upload_mb(monkeypatch):
    monkeypatch.delenv("DATRIS_MAX_UPLOAD_MB", raising=False)
    default_cap = server._max_request_body_bytes()
    assert default_cap == _expected(1024), default_cap
    assert default_cap > SDK_DEFAULT_CAP

    monkeypatch.setenv("DATRIS_MAX_UPLOAD_MB", "2048")
    doubled = server._max_request_body_bytes()
    assert doubled == _expected(2048), doubled
    # The payload part doubles; the envelope allowance stays constant.
    assert doubled - MIB == 2 * (default_cap - MIB)


def test_cap_admits_one_gib_base64_archive(monkeypatch):
    monkeypatch.delenv("DATRIS_MAX_UPLOAD_MB", raising=False)
    one_gib = 1024 * MIB
    base64_len = 4 * ((one_gib + 2) // 3)  # padded base64 length of a 1 GiB archive
    envelope = 64 * 1024  # JSON-RPC wrapper, tool name, catalog name, reason, ...
    assert server._max_request_body_bytes() > base64_len + envelope


# ------------------------------------------ Files: transport wiring (5285+) ---

def test_every_http_transport_gets_the_cap(monkeypatch):
    """run_sse builds SseServerTransport + StreamableHTTPSessionManager and
    run_streamable_http builds a second StreamableHTTPSessionManager; all three
    must receive max_request_body_size=_max_request_body_bytes()."""
    import mcp.server.sse as sse_mod
    import mcp.server.streamable_http_manager as shm_mod
    import uvicorn

    monkeypatch.setenv("DATRIS_MAX_UPLOAD_MB", "7")
    want = _expected(7)
    seen = []

    class FakeSse:
        def __init__(self, *args, **kwargs):
            seen.append(("sse", args, kwargs))

    class FakeMgr:
        def __init__(self, *args, **kwargs):
            seen.append(("streamable", args, kwargs))

    async def no_serve(self, *a, **k):
        return None

    monkeypatch.setattr(sse_mod, "SseServerTransport", FakeSse)
    monkeypatch.setattr(shm_mod, "StreamableHTTPSessionManager", FakeMgr)
    monkeypatch.setattr(uvicorn.Server, "serve", no_serve)

    asyncio.run(server.run_sse(0))
    asyncio.run(server.run_streamable_http(0))

    kinds = [k for k, _, _ in seen]
    assert kinds.count("sse") == 1 and kinds.count("streamable") == 2, seen
    for kind, args, kwargs in seen:
        assert kwargs.get("max_request_body_size") == want, (kind, kwargs)
        if kind == "sse":
            assert args[:1] == ("/messages",) or kwargs.get("endpoint") == "/messages", (args, kwargs)
        else:
            # Out of scope: idle timeout and session cap stay at SDK defaults.
            assert "session_idle_timeout" not in kwargs and "max_sessions" not in kwargs, kwargs
            assert kwargs.get("stateless") is True, kwargs
