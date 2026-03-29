"""
agent/loop.py

The agentic loop.  Calls the Anthropic API repeatedly until
stop_reason == "end_turn", executing tool calls in between.

Tool definitions are loaded dynamically from the MCP server at startup,
plus the local ingest_data tool defined in config.py.

Yields server-sent event dicts so the FastAPI endpoint can stream
updates to the browser in real time:

    {"event": "tool_start",   "data": {"name": ..., "input": ...}}
    {"event": "tool_end",     "data": {"name": ..., "result": ...}}
    {"event": "partial_text", "data": {"text": ...}}
    {"event": "answer",       "data": {"text": ...}}
    {"event": "error",        "data": {"message": ...}}
"""

import json
import os
from typing import AsyncIterator

import anthropic

from agent.config import MISSION, AUTO_MODE_ADDENDUM, INGEST_TOOL_DEF, MCP_TOOL_ALLOWLIST
from agent.executor import execute_tool
from agent.mcp_client import get_tools, get_resources_text
from agent.pipeline_store import store

_client = anthropic.AsyncAnthropic(api_key=os.environ.get("ANTHROPIC_API_KEY"))
MODEL = os.environ.get("MODEL", "claude-sonnet-4-20250514")


async def _build_tools() -> list[dict]:
    """Combine filtered MCP tools with the local ingest_data tool."""
    mcp_tools = await get_tools()
    if MCP_TOOL_ALLOWLIST:
        mcp_tools = [t for t in mcp_tools if t["name"] in MCP_TOOL_ALLOWLIST]
    return mcp_tools + [INGEST_TOOL_DEF]


async def run(
    user_text: str,
    history: list[dict],
    auto_mode: bool = False,
) -> AsyncIterator[dict]:
    """
    Async generator.  Appends the user turn to history, runs the agentic
    loop, yields SSE event dicts, and returns the updated history list.

    Caller owns the history list and persists it between turns.
    """

    history = list(history)  # local copy
    history.append({"role": "user", "content": user_text})

    await store.add_activity("user", f'Query: "{user_text[:55]}"')

    tools = await _build_tools()

    # Append MCP resources (pipeline config reference, etc.) so Claude learns from the server
    resources = await get_resources_text()
    system_prompt = MISSION
    if auto_mode:
        system_prompt += AUTO_MODE_ADDENDUM
    if resources:
        system_prompt += "\n\n" + resources

    max_iters = 12
    for _ in range(max_iters):

        await store.increment_api_calls()

        response = await _client.messages.create(
            model=MODEL,
            max_tokens=4096,
            system=system_prompt,
            tools=tools,
            messages=history,
        )

        print(f"[loop] stop_reason={response.stop_reason}, blocks={len(response.content)}")

        # ── Max tokens — truncated response, treat as end_turn ───────────
        if response.stop_reason == "max_tokens":
            # Don't append incomplete tool_use blocks to history
            text = "".join(
                b.text for b in response.content if hasattr(b, "text")
            )
            if text.strip():
                yield {"event": "answer", "data": {"text": text.strip()}}
            else:
                yield {"event": "answer", "data": {"text": "Response was too long — please try a more specific question."}}
            break

        history.append({"role": "assistant", "content": response.content})

        # ── Terminal turn ──────────────────────────────────────────────────
        if response.stop_reason == "end_turn":
            text = "".join(
                b.text for b in response.content if hasattr(b, "text")
            )
            print(f"[loop] answer text length={len(text.strip())}")
            if text.strip():
                yield {"event": "answer", "data": {"text": text.strip()}}
            break

        # ── Tool-use turn ──────────────────────────────────────────────────
        if response.stop_reason == "tool_use":
            # Surface any partial reasoning text first
            partial = "".join(
                b.text for b in response.content if hasattr(b, "text") and b.text.strip()
            )
            if partial:
                yield {"event": "partial_text", "data": {"text": partial}}

            tool_blocks = [b for b in response.content if b.type == "tool_use"]
            tool_results = []

            for block in tool_blocks:
                await store.add_activity("tool", f"Tool call: {block.name}")
                yield {"event": "tool_start", "data": {
                    "id": block.id, "name": block.name, "input": block.input,
                }}

                try:
                    result = await execute_tool(block.name, block.input)
                except Exception as e:
                    result = {"error": str(e)}

                yield {"event": "tool_end", "data": {
                    "id": block.id, "name": block.name,
                    "result_preview": str(result)[:120],
                }}

                # Ensure content is never empty — Anthropic rejects empty tool results
                result_str = json.dumps(result) if result else '{"status": "ok"}'
                if not result_str or result_str == '""':
                    result_str = '{"status": "ok"}'

                # Truncate large results (e.g. base64 content from ingest_data)
                # to prevent conversation history from growing too large
                if len(result_str) > 2000:
                    # Keep a summary instead of the full content
                    truncated = result_str[:1500]
                    result_str = truncated + f'... [truncated, {len(result_str)} chars total]"'

                tool_results.append({
                    "type":        "tool_result",
                    "tool_use_id": block.id,
                    "content":     result_str,
                })

            if tool_results:
                history.append({"role": "user", "content": tool_results})

    # Surface the final history so the caller can persist it
    yield {"event": "history", "data": {"history": history}}
