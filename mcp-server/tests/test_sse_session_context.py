"""Story: MCP server moves to the MCP Python SDK 2.x
(plans/stories/mcp-sdk-2-migration.md).

SDK 2.x runs each handler in the contextvars of the task that delivered the
message. On SSE that is the `/messages` POST, not the `/sse` GET that set the
session's API key, so without re-applying them every SSE tool call would reach
the Datris API with no key and be attributed to the "stdio" session."""
import asyncio
import contextvars
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import server  # noqa: E402

SID = "0123456789abcdef0123456789abcdef"


def test_endpoint_event_registers_session_vars_and_post_reapplies_them():
    sent = []

    async def send(message):
        sent.append(message)

    captured: list = []
    wrapped = server._sse_send_capturing_session(send, ("KEY123", "alice", "inc-1", "sess-a"), captured)

    async def drive():
        await wrapped({"type": "http.response.start", "status": 200, "headers": []})
        await wrapped({"type": "http.response.body",
                       "body": f"event: endpoint\r\ndata: /messages?session_id={SID}\r\n\r\n".encode()})
        await wrapped({"type": "http.response.body", "body": b"event: message\r\ndata: session_id=" + b"f" * 32})

    try:
        asyncio.run(drive())
        assert len(sent) == 3
        assert captured == [SID]
        assert server._sse_session_vars[SID] == ("KEY123", "alice", "inc-1", "sess-a")

        def in_post():
            server._apply_sse_session_vars({"query_string": f"session_id={SID}".encode()})
            return (server._session_api_key.get(), server._session_on_behalf_of.get(),
                    server._session_incident.get(), server._session_id.get())

        assert contextvars.Context().run(in_post) == ("KEY123", "alice", "inc-1", "sess-a")
    finally:
        server._sse_session_vars.pop(SID, None)


def test_unknown_session_leaves_context_untouched():
    def in_post():
        server._apply_sse_session_vars({"query_string": b"session_id=" + b"e" * 32})
        server._apply_sse_session_vars({"query_string": b""})
        return server._session_api_key.get(), server._session_id.get()

    assert contextvars.Context().run(in_post) == ("", "")


def test_sse_close_drops_session_vars(monkeypatch):
    """The /sse branch must forget the session's vars when the connection
    closes, or every SSE connection leaks an entry for the process lifetime."""
    import contextlib
    import mcp.server.sse as sse_mod
    import mcp.server.streamable_http_manager as shm_mod
    import uvicorn

    seen_during = {}

    class FakeSse:
        def __init__(self, *args, **kwargs):
            pass

        @contextlib.asynccontextmanager
        async def connect_sse(self, scope, receive, send):
            await send({"type": "http.response.start", "status": 200, "headers": []})
            await send({"type": "http.response.body",
                        "body": f"event: endpoint\r\ndata: /messages?session_id={SID}\r\n\r\n".encode()})
            yield ("r", "w")

    class FakeMgr:
        def __init__(self, *args, **kwargs):
            pass

    apps = []

    class FakeConfig:
        def __init__(self, app, **kwargs):
            apps.append(app)

    async def no_serve(self, *a, **k):
        return None

    async def fake_run(*a, **k):
        seen_during["vars"] = server._sse_session_vars.get(SID)

    monkeypatch.setattr(sse_mod, "SseServerTransport", FakeSse)
    monkeypatch.setattr(shm_mod, "StreamableHTTPSessionManager", FakeMgr)
    monkeypatch.setattr(uvicorn, "Config", FakeConfig)
    monkeypatch.setattr(uvicorn, "Server", lambda config: type("S", (), {"serve": no_serve})())
    monkeypatch.setattr(server, "_render_instructions", lambda: "instructions")
    monkeypatch.setattr(server.server, "run", fake_run)

    asyncio.run(server.run_sse(0))
    app = apps[0]

    async def send(message):
        pass

    async def receive():
        return {"type": "http.disconnect"}

    scope = {"type": "http", "path": "/sse", "method": "GET", "headers": [(b"x-api-key", b"KEYCLOSE")],
             "query_string": b""}
    try:
        asyncio.run(app(scope, receive, send))
        assert seen_during["vars"] is not None and seen_during["vars"][0] == "KEYCLOSE", seen_during
        assert SID not in server._sse_session_vars
    finally:
        server._sse_session_vars.pop(SID, None)
