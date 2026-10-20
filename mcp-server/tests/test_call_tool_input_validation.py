"""Story: MCP server moves to the MCP Python SDK 2.x
(plans/stories/mcp-sdk-2-migration.md).

SDK 1.x validated tool arguments against inputSchema before the handler ran;
2.x does not. call_tool validates itself so a bad enum, a missing required
property or a wrong type is refused with the same "Input validation error: "
text, never reaches the REST API, and shows as an error in the activity feed."""
import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import server  # noqa: E402
from mcp.types import CallToolRequestParams  # noqa: E402


def _call(monkeypatch, name, arguments):
    dispatched, recorded = [], []
    monkeypatch.setattr(server, "_dispatch", lambda n, a: dispatched.append((n, a)) or '{"ok":true}')
    monkeypatch.setattr(server, "_activity_record", lambda *a, **k: recorded.append(a))
    res = asyncio.run(server.validated_call_tool(None, CallToolRequestParams(name=name, arguments=arguments)))
    return res, dispatched, recorded


def test_validated_handler_is_the_registered_one():
    entry = server.server._request_handlers["tools/call"]
    assert getattr(entry, "handler", entry) is server.validated_call_tool


def _schema(name):
    return next(t for t in server._all_tools() if t.name == name).input_schema


def test_bad_enum_is_refused_before_dispatch(monkeypatch):
    dest = _schema("create_pipeline")["properties"]["destination"]
    assert "enum" in dest, dest
    args = {k: "x" for k in _schema("create_pipeline").get("required", [])}
    args["destination"] = "bogusdest"
    res, dispatched, recorded = _call(monkeypatch, "create_pipeline", args)
    assert res.is_error is True
    text = res.content[0].text
    assert text.startswith("Input validation error: "), text
    assert "'bogusdest' is not one of" in text, text
    assert dispatched == []
    # Activity feed: status "error" with the validation message.
    (row,) = recorded
    assert row[2] == "error" and row[7] == text, row


def test_missing_required_is_refused(monkeypatch):
    res, dispatched, _ = _call(monkeypatch, "get_pipeline", {})
    assert res.is_error is True
    assert res.content[0].text == "Input validation error: 'pipeline' is a required property"
    assert dispatched == []


def test_wrong_type_is_refused(monkeypatch):
    res, dispatched, _ = _call(monkeypatch, "get_pipeline", {"pipeline": 123})
    assert res.is_error is True
    assert res.content[0].text == "Input validation error: 123 is not of type 'string'"
    assert dispatched == []


def test_valid_call_with_reason_is_dispatched_without_reason(monkeypatch):
    assert "reason" in _schema("delete_pipeline")["properties"]
    required = _schema("delete_pipeline").get("required", [])
    args = {k: "p1" for k in required}
    args["reason"] = "cleanup"
    res, dispatched, _ = _call(monkeypatch, "delete_pipeline", args)
    assert res.is_error is False, res.content[0].text
    assert dispatched == [("delete_pipeline", {k: "p1" for k in required})]


def test_unknown_tool_skips_validation(monkeypatch):
    res, dispatched, _ = _call(monkeypatch, "no_such_tool", {"anything": 1})
    assert dispatched == [("no_such_tool", {"anything": 1})]
