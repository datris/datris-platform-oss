"""
agent/mcp_client.py

Persistent MCP client connection over SSE with automatic reconnection.

Uses a custom SSE transport instead of the built-in sse_client to avoid
a connection-pool issue where the SSE read stream and POST writer share
the same httpx client and interfere with each other.

Lifecycle:
    await start("http://localhost:3000/sse")  # call once at startup (non-blocking)
    tools = await get_tools()                  # returns [] until connected
    result = await call_tool("list_pipelines", {})
    await stop()                               # call once at shutdown

A supervisor task owns the connection and reconnects automatically with
exponential backoff (2s → 30s cap) whenever the SSE stream drops or the
initial handshake fails.
"""

import asyncio
import json
import logging
from typing import Any

import httpx
from httpx_sse import aconnect_sse

log = logging.getLogger("datris.mcp")

_sse_client: httpx.AsyncClient | None = None
_post_client: httpx.AsyncClient | None = None
_endpoint: str | None = None
_reader_task: asyncio.Task | None = None
_supervisor_task: asyncio.Task | None = None
_pending: dict[int, asyncio.Future] = {}
_tools_cache: list[dict] | None = None
_resources_cache: dict[str, str] = {}
_server_instructions: str = ""
_msg_id: int = 0
_sse_cm: Any = None  # context manager for SSE connection
_connected: bool = False
_stopping: bool = False


def _next_id() -> int:
    global _msg_id
    _msg_id += 1
    return _msg_id


async def _send_and_wait(method: str, params: dict, timeout: float = 10.0) -> dict:
    """Send a JSON-RPC request and wait for the matching response."""
    call_id = _next_id()
    loop = asyncio.get_event_loop()
    fut: asyncio.Future = loop.create_future()
    _pending[call_id] = fut

    await _post_client.post(_endpoint, json={
        "jsonrpc": "2.0",
        "id": call_id,
        "method": method,
        "params": params,
    })

    try:
        return await asyncio.wait_for(fut, timeout)
    finally:
        _pending.pop(call_id, None)


async def _connect_once(url: str, timeout: float = 15.0) -> None:
    """
    Single connection attempt. Opens SSE, performs handshake, lists tools
    and resources. Raises on any failure; caller is the supervisor.
    """
    global _sse_client, _post_client, _endpoint, _reader_task, _tools_cache, _sse_cm, _connected

    _sse_client = httpx.AsyncClient(timeout=httpx.Timeout(5, read=300))
    _post_client = httpx.AsyncClient(
        timeout=30,
        limits=httpx.Limits(max_keepalive_connections=0),
    )

    try:
        async with asyncio.timeout(timeout):
            _sse_cm = aconnect_sse(_sse_client, "GET", url)
            sse = await _sse_cm.__aenter__()

            async def _read_sse():
                global _endpoint
                try:
                    async for event in sse.aiter_sse():
                        if event.event == "endpoint":
                            base = url.rsplit("/", 1)[0]
                            _endpoint = base + event.data
                            log.debug("MCP endpoint: %s", _endpoint)
                        elif event.event == "message":
                            data = json.loads(event.data)
                            rid = data.get("id")
                            fut = _pending.get(rid)
                            if fut and not fut.done():
                                fut.set_result(data)
                            else:
                                log.debug("Unmatched response id=%s", rid)
                except asyncio.CancelledError:
                    raise
                except Exception as e:
                    log.warning("SSE reader exited: %s", e)

            _reader_task = asyncio.create_task(_read_sse())

            # Wait for endpoint announcement
            for _ in range(50):
                if _endpoint:
                    break
                await asyncio.sleep(0.1)
            if not _endpoint:
                raise ConnectionError("No endpoint received from MCP SSE stream")

            resp = await _send_and_wait("initialize", {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "datris-agent", "version": "1.0"},
            }, timeout=10)
            init_result = resp.get("result", {})
            log.debug("MCP initialized: %s", list(init_result.get("capabilities", {}).keys()))

            global _server_instructions
            _server_instructions = init_result.get("instructions", "") or ""
            if _server_instructions:
                print(f"[mcp] Loaded server instructions ({len(_server_instructions)} chars)")

            await _post_client.post(_endpoint, json={
                "jsonrpc": "2.0",
                "method": "notifications/initialized",
            })

            resp = await _send_and_wait("tools/list", {}, timeout=10)
            tools = resp.get("result", {}).get("tools", [])

            _tools_cache = [
                {
                    "name": t["name"],
                    "description": t.get("description", ""),
                    "input_schema": t.get("inputSchema", {"type": "object", "properties": {}}),
                }
                for t in tools
            ]

            await _read_resources()

    except Exception:
        await _teardown()
        raise

    _connected = True
    print(f"[datris] MCP: ✓ connected — {len(_tools_cache)} tools available")


async def _teardown() -> None:
    """Close SSE + POST clients, cancel reader, clear state. Does not cancel supervisor."""
    global _sse_client, _post_client, _endpoint, _reader_task, _tools_cache, _sse_cm, _connected

    _connected = False

    # Cancel any pending futures so in-flight callers fail fast
    for fut in list(_pending.values()):
        if not fut.done():
            fut.set_exception(ConnectionError("MCP connection lost"))
    _pending.clear()

    if _reader_task and not _reader_task.done():
        _reader_task.cancel()
        try:
            await _reader_task
        except (asyncio.CancelledError, Exception):
            pass
    _reader_task = None

    if _sse_cm:
        try:
            await _sse_cm.__aexit__(None, None, None)
        except Exception:
            pass
    _sse_cm = None

    if _sse_client:
        try:
            await _sse_client.aclose()
        except Exception:
            pass
    _sse_client = None

    if _post_client:
        try:
            await _post_client.aclose()
        except Exception:
            pass
    _post_client = None

    _endpoint = None
    _tools_cache = None


async def _supervisor(url: str) -> None:
    """
    Owns the connection lifecycle. Loops: connect → wait for reader to exit
    → tear down → backoff → retry. Exits only when _stopping is set.
    """
    backoff = 2.0
    while not _stopping:
        try:
            await _connect_once(url)
            backoff = 2.0  # reset after successful connect
        except Exception as e:
            print(f"[datris] MCP: ✗ connect failed ({e}), retrying in {backoff:.0f}s")
            try:
                await asyncio.sleep(backoff)
            except asyncio.CancelledError:
                return
            backoff = min(backoff * 2, 30.0)
            continue

        # Connected — wait for reader task to exit (disconnect or cancellation)
        try:
            await _reader_task
        except (asyncio.CancelledError, Exception):
            pass

        if _stopping:
            break

        print(f"[datris] MCP: ✗ SSE reader exited, reconnecting in {backoff:.0f}s")
        await _teardown()
        try:
            await asyncio.sleep(backoff)
        except asyncio.CancelledError:
            return
        backoff = min(backoff * 2, 30.0)


async def _read_resources() -> None:
    """Read all MCP resources and cache their content."""
    try:
        resp = await _send_and_wait("resources/list", {}, timeout=10)
    except asyncio.TimeoutError:
        print("[mcp] resources/list timed out — skipping")
        return

    resources = resp.get("result", {}).get("resources", [])
    _resources_cache.clear()

    for r in resources:
        try:
            resp2 = await _send_and_wait("resources/read", {"uri": r["uri"]}, timeout=10)
        except asyncio.TimeoutError:
            continue
        contents = resp2.get("result", {}).get("contents", [])
        text = "\n".join(c.get("text", "") for c in contents)
        if len(text) > 4000:
            text = text[:4000] + "\n\n[... truncated for brevity]"
        _resources_cache[r["name"]] = text
        print(f"[mcp] Loaded resource: {r['name']} ({len(text)} chars)")


async def get_resources_text() -> str:
    """Return all cached MCP resources as a single string for the system prompt."""
    if not _resources_cache:
        return ""
    parts = []
    for name, text in _resources_cache.items():
        parts.append(f"--- {name} ---\n{text}")
    return "\n\n".join(parts)


def get_server_instructions() -> str:
    """Return the `instructions` field from the MCP server's initialize response."""
    return _server_instructions


async def start(url: str) -> None:
    """
    Launch the connection supervisor. Returns immediately — the supervisor
    runs in the background and reconnects forever until stop() is called.
    """
    global _supervisor_task, _stopping
    _stopping = False
    _supervisor_task = asyncio.create_task(_supervisor(url))


async def stop() -> None:
    """Shut down the supervisor and close the connection."""
    global _supervisor_task, _stopping
    _stopping = True

    if _supervisor_task and not _supervisor_task.done():
        _supervisor_task.cancel()
        try:
            await _supervisor_task
        except (asyncio.CancelledError, Exception):
            pass
    _supervisor_task = None

    await _teardown()
    log.info("MCP stopped")


async def get_tools() -> list[dict]:
    """Return tool definitions in Anthropic format. Empty list if not connected."""
    if _tools_cache is None:
        return []
    return list(_tools_cache)


async def call_tool(name: str, arguments: dict[str, Any]) -> dict:
    """Execute a tool on the MCP server and return the result as a dict."""
    if not _connected or _post_client is None or _endpoint is None:
        return {"error": "Datris MCP server is unavailable. Retrying in the background — try again shortly."}

    call_id = _next_id()
    print(f"[mcp] call_tool: {name} (id={call_id}) args={str(arguments)}")

    loop = asyncio.get_event_loop()
    fut: asyncio.Future = loop.create_future()
    _pending[call_id] = fut

    try:
        r = await _post_client.post(_endpoint, json={
            "jsonrpc": "2.0",
            "id": call_id,
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments},
        })
        print(f"[mcp] POST status: {r.status_code}")
    except Exception as e:
        _pending.pop(call_id, None)
        return {"error": f"MCP POST failed: {e}"}

    try:
        resp = await asyncio.wait_for(fut, 120)
    except asyncio.TimeoutError:
        print(f"[mcp] TIMEOUT waiting for response: {name} (id={call_id})")
        return {"error": f"MCP tool call timed out: {name}"}
    except ConnectionError as e:
        return {"error": f"MCP connection lost during tool call: {e}"}
    finally:
        _pending.pop(call_id, None)

    print(f"[mcp] response id={resp.get('id')} (waiting for {call_id})")

    if "error" in resp:
        err = resp["error"]
        print(f"[mcp] tool error: {name} — {err}")
        return {"error": err.get("message", str(err))}

    result = resp.get("result", {})
    content = result.get("content", [])

    texts = [block["text"] for block in content if block.get("type") == "text"]
    combined = "\n".join(texts)

    print(f"[mcp] result: {name} → {combined}")

    try:
        return json.loads(combined)
    except (json.JSONDecodeError, TypeError):
        return {"text": combined, "is_error": result.get("isError", False)}


def is_connected() -> bool:
    """Return True only when the SSE stream is live and handshake complete."""
    return _connected
