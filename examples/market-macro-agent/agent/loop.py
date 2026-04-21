"""
agent/loop.py

The agentic loop.  Calls the Anthropic API repeatedly until
stop_reason == "end_turn", executing tool calls in between.

Tool definitions are loaded dynamically from the MCP server at startup.
Data sourcing lives on the platform as taps; the agent has no local tools.

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

from agent.config import MISSION, MCP_TOOL_ALLOWLIST
from agent.executor import execute_tool
from agent.mcp_client import get_tools, get_resources_text, get_server_instructions
from agent.pipeline_store import store

_client = anthropic.AsyncAnthropic(api_key=os.environ.get("ANTHROPIC_API_KEY"))
MODEL = os.environ.get("MODEL", "claude-sonnet-4-6")


async def _build_tools() -> list[dict]:
    """Return the filtered MCP tool list — no local tools."""
    mcp_tools = await get_tools()
    if MCP_TOOL_ALLOWLIST:
        mcp_tools = [t for t in mcp_tools if t["name"] in MCP_TOOL_ALLOWLIST]
    return mcp_tools


async def run(
    user_text: str,
    history: list[dict],
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

    # Assemble the system prompt from three sources, in order of authority:
    #   1. MCP server instructions (the platform's own workflow rules — poll
    #      get_pipeline_status, persisted/persistedReason handling, etc.)
    #   2. Agent-specific mission (identity, financial-only scope, response style)
    #   3. MCP resources (pipeline config reference, etc.)
    server_instructions = get_server_instructions()
    resources = await get_resources_text()

    parts: list[str] = []
    if server_instructions:
        parts.append("--- Datris Platform Workflow ---\n" + server_instructions)
    parts.append(MISSION)
    if resources:
        parts.append(resources)
    system_prompt = "\n\n".join(parts)

    max_iters = 25
    hit_max_iters = True
    for _ in range(max_iters):

        await store.increment_api_calls()

        # Stream the response so text arrives token-by-token in the browser
        streamed_text = ""
        content_blocks = []
        stop_reason = None

        async with _client.messages.stream(
            model=MODEL,
            max_tokens=4096,
            system=system_prompt,
            tools=tools,
            messages=history,
        ) as stream:
            async for event in stream:
                if event.type == "content_block_start":
                    if event.content_block.type == "text":
                        streamed_text = ""
                elif event.type == "content_block_delta":
                    if event.delta.type == "text_delta":
                        streamed_text += event.delta.text
                        yield {"event": "partial_text", "data": {"text": streamed_text}}

            response = await stream.get_final_message()

        stop_reason = response.stop_reason
        print(f"[loop] stop_reason={stop_reason}, blocks={len(response.content)}")

        # ── Max tokens — truncated response, treat as end_turn ───────────
        if stop_reason == "max_tokens":
            text = "".join(
                b.text for b in response.content if hasattr(b, "text")
            )
            if text.strip():
                yield {"event": "answer", "data": {"text": text.strip()}}
            else:
                yield {"event": "answer", "data": {"text": "Response was too long — please try a more specific question."}}
            hit_max_iters = False
            break

        history.append({"role": "assistant", "content": response.content})

        # ── Terminal turn ──────────────────────────────────────────────────
        if stop_reason == "end_turn":
            text = "".join(
                b.text for b in response.content if hasattr(b, "text")
            )
            print(f"[loop] answer text length={len(text.strip())}")
            if text.strip():
                yield {"event": "answer", "data": {"text": text.strip()}}
            hit_max_iters = False
            break

        # ── Tool-use turn ──────────────────────────────────────────────────
        if stop_reason == "tool_use":
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

                if len(result_str) > 2000:
                    total_len = len(result_str)
                    result_str = result_str[:1500] + f"\n\n[... result truncated — {total_len} chars total]"

                tool_results.append({
                    "type":        "tool_result",
                    "tool_use_id": block.id,
                    "content":     result_str,
                })

            if tool_results:
                history.append({"role": "user", "content": tool_results})

    if hit_max_iters:
        yield {"event": "answer", "data": {"text": (
            f"I had to stop after {max_iters} steps without reaching a final answer. "
            "Try a more specific question, or ask me to focus on one data source at a time."
        )}}

    # Surface the final history so the caller can persist it
    yield {"event": "history", "data": {"history": history}}
