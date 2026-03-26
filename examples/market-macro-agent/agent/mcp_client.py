"""
agent/mcp_client.py

Persistent MCP client connection over SSE.

Uses a custom SSE transport instead of the built-in sse_client to avoid
a connection-pool issue where the SSE read stream and POST writer share
the same httpx client and interfere with each other.

Lifecycle:
    await connect("http://localhost:3000/sse")   # call once at startup
    tools = await get_tools()                     # Anthropic-formatted tool defs
    result = await call_tool("list_pipelines", {})
    await disconnect()                            # call once at shutdown
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
_responses: asyncio.Queue | None = None
_tools_cache: list[dict] | None = None
_resources_cache: dict[str, str] = {}
_msg_id: int = 0
_sse_cm: Any = None  # context manager for SSE connection


def _next_id() -> int:
    global _msg_id
    _msg_id += 1
    return _msg_id


async def connect(url: str, timeout: float = 15.0) -> None:
    """Open an SSE connection to the Datris MCP server and discover tools."""
    global _sse_client, _post_client, _endpoint, _reader_task, _responses, _tools_cache, _sse_cm

    _sse_client = httpx.AsyncClient(timeout=httpx.Timeout(5, read=300))
    _post_client = httpx.AsyncClient(
        timeout=30,
        limits=httpx.Limits(max_keepalive_connections=0),
    )
    _responses = asyncio.Queue()

    try:
        async with asyncio.timeout(timeout):
            # Open SSE stream
            _sse_cm = aconnect_sse(_sse_client, "GET", url)
            sse = await _sse_cm.__aenter__()

            # Read events in background
            async def _read_sse():
                global _endpoint
                try:
                    async for event in sse.aiter_sse():
                        if event.event == "endpoint":
                            # Build full URL from relative endpoint
                            base = url.rsplit("/", 1)[0]  # e.g. http://localhost:3000
                            _endpoint = base + event.data
                            log.debug("MCP endpoint: %s", _endpoint)
                        elif event.event == "message":
                            data = json.loads(event.data)
                            await _responses.put(data)
                except asyncio.CancelledError:
                    pass
                except Exception as e:
                    log.debug("SSE reader stopped: %s", e)

            _reader_task = asyncio.create_task(_read_sse())

            # Wait for endpoint
            for _ in range(50):
                if _endpoint:
                    break
                await asyncio.sleep(0.1)
            if not _endpoint:
                raise ConnectionError("No endpoint received from MCP SSE stream")

            # Initialize
            init_id = _next_id()
            await _post_client.post(_endpoint, json={
                "jsonrpc": "2.0",
                "id": init_id,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "datris-agent", "version": "1.0"},
                },
            })
            resp = await asyncio.wait_for(_responses.get(), 10)
            log.debug("MCP initialized: %s", list(resp.get("result", {}).get("capabilities", {}).keys()))

            # Send initialized notification
            await _post_client.post(_endpoint, json={
                "jsonrpc": "2.0",
                "method": "notifications/initialized",
            })

            # List tools
            tools_id = _next_id()
            await _post_client.post(_endpoint, json={
                "jsonrpc": "2.0",
                "id": tools_id,
                "method": "tools/list",
                "params": {},
            })
            resp = await asyncio.wait_for(_responses.get(), 10)
            tools = resp.get("result", {}).get("tools", [])

            _tools_cache = [
                {
                    "name": t["name"],
                    "description": t.get("description", ""),
                    "input_schema": t.get("inputSchema", {"type": "object", "properties": {}}),
                }
                for t in tools
            ]
            log.info("MCP connected — %d tools available", len(_tools_cache))

            # Read all MCP resources
            await _read_resources()

    except Exception:
        await disconnect()
        raise


async def _read_resources() -> None:
    """Read all MCP resources and cache their content."""
    # List resources
    rid = _next_id()
    await _post_client.post(_endpoint, json={
        "jsonrpc": "2.0", "id": rid,
        "method": "resources/list", "params": {},
    })
    try:
        resp = await asyncio.wait_for(_responses.get(), 10)
    except asyncio.TimeoutError:
        print("[mcp] resources/list timed out — skipping")
        return

    resources = resp.get("result", {}).get("resources", [])

    for r in resources:
        rid2 = _next_id()
        await _post_client.post(_endpoint, json={
            "jsonrpc": "2.0", "id": rid2,
            "method": "resources/read", "params": {"uri": r["uri"]},
        })
        try:
            resp2 = await asyncio.wait_for(_responses.get(), 10)
        except asyncio.TimeoutError:
            continue
        contents = resp2.get("result", {}).get("contents", [])
        text = "\n".join(c.get("text", "") for c in contents)
        # Cap resource size to keep system prompt reasonable
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


async def disconnect() -> None:
    """Cleanly shut down the MCP connection."""
    global _sse_client, _post_client, _endpoint, _reader_task, _responses, _tools_cache, _sse_cm

    if _reader_task:
        _reader_task.cancel()
        try:
            await _reader_task
        except (asyncio.CancelledError, Exception):
            pass
    if _sse_cm:
        try:
            await _sse_cm.__aexit__(None, None, None)
        except Exception:
            pass
    if _sse_client:
        await _sse_client.aclose()
    if _post_client:
        await _post_client.aclose()

    _sse_client = None
    _post_client = None
    _endpoint = None
    _reader_task = None
    _responses = None
    _tools_cache = None
    _sse_cm = None
    log.info("MCP disconnected")


async def get_tools() -> list[dict]:
    """Return tool definitions in Anthropic format (name, description, input_schema)."""
    if _tools_cache is None:
        raise RuntimeError("MCP client not connected — call connect() first")
    return list(_tools_cache)


async def call_tool(name: str, arguments: dict[str, Any]) -> dict:
    """Execute a tool on the MCP server and return the result as a dict."""
    if _post_client is None or _endpoint is None or _responses is None:
        raise RuntimeError("MCP client not connected — call connect() first")

    call_id = _next_id()
    print(f"[mcp] call_tool: {name} (id={call_id}) args={str(arguments)}")

    r = await _post_client.post(_endpoint, json={
        "jsonrpc": "2.0",
        "id": call_id,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    })
    print(f"[mcp] POST status: {r.status_code}")

    # Wait for the response with our ID (skip notifications)
    while True:
        try:
            resp = await asyncio.wait_for(_responses.get(), 120)
        except asyncio.TimeoutError:
            print(f"[mcp] TIMEOUT waiting for response: {name} (id={call_id})")
            return {"error": f"MCP tool call timed out: {name}"}
        print(f"[mcp] response id={resp.get('id')} (waiting for {call_id}): {str(resp)}")
        if resp.get("id") == call_id:
            break

    # Handle JSON-RPC error responses
    if "error" in resp:
        err = resp["error"]
        print(f"[mcp] tool error: {name} — {err}")
        return {"error": err.get("message", str(err))}

    result = resp.get("result", {})
    content = result.get("content", [])

    # Combine text blocks
    texts = [block["text"] for block in content if block.get("type") == "text"]
    combined = "\n".join(texts)

    print(f"[mcp] result: {name} → {combined}")

    # Try to parse as JSON; fall back to raw text
    try:
        return json.loads(combined)
    except (json.JSONDecodeError, TypeError):
        return {"text": combined, "is_error": result.get("isError", False)}


def is_connected() -> bool:
    """Check whether the MCP client has an active connection."""
    return _endpoint is not None and _post_client is not None
