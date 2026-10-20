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
