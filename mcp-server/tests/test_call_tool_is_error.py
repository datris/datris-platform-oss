"""call_tool must flag REST error bodies and exceptions with isError so MCP
clients do not treat a denial as a successful text result."""
import asyncio
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import server  # noqa: E402


def _run(dispatch, monkeypatch):
    monkeypatch.setattr(server, "_dispatch", dispatch)
    monkeypatch.setattr(server, "_activity_record", lambda *a, **k: None)
    return asyncio.run(server.call_tool("list_pipelines", {}))


def test_is_error_payload_rules():
    f = server._is_error_payload
    assert f('{"error":"API key is revoked or invalid"}')
    assert f('{"error":"capability denied","errorKind":"capability_denied","key":"k","required":"x","route":"r"}')
    assert not f('[{"name":"p"}]')
    assert not f('{"count":1,"pipelines":[]}')
    assert not f('{"rows":[1],"error":"partial: 1 skipped"}')
    assert not f("plain text")
    assert not f("")


def test_rest_error_body_sets_is_error(monkeypatch):
    res = _run(lambda name, args: '{"error":"API key is revoked or invalid"}', monkeypatch)
    assert res.isError is True
    assert json.loads(res.content[0].text)["error"] == "API key is revoked or invalid"


def test_data_result_is_not_error(monkeypatch):
    res = _run(lambda name, args: '{"count":2,"pipelines":[{"name":"a"},{"name":"b"}]}', monkeypatch)
    assert res.isError is False
    assert json.loads(res.content[0].text)["count"] == 2


def test_exception_sets_is_error(monkeypatch):
    def boom(name, args):
        raise RuntimeError("connection refused")
    res = _run(boom, monkeypatch)
    assert res.isError is True
    assert json.loads(res.content[0].text) == {"error": "connection refused"}
