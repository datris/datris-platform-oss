"""Story: pipeline-delete-drift-warning-and-404.md, Part 2.

GET /api/v1/tap?name=<missing> answers 404 instead of 500, and DELETE
/api/v1/tap?name=<missing> answers 404 instead of 200, both with the body
{"error":"Tap: <name> not found"}. get_tap and delete_tap go through the real
`_call` with `requests.get` / `requests.delete` answering a genuine 404 (and a
500, as an older server does for GET), so the status is what changes and the
tool output does not."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


BODY = '{"error":"Tap: nope not found"}'


class _Resp:
    def __init__(self, status_code, body):
        self.status_code = status_code
        self.text = body
        self.ok = 200 <= status_code < 300

    def json(self):
        return json.loads(self.text)


class _Server:
    def __init__(self, status):
        self.status = status
        self.calls = []

    def _answer(self, method, url):
        self.calls.append((method, url))
        if "/api/v1/tap?" in url:
            return _Resp(self.status, BODY)
        return _Resp(200, "")

    def get(self, url, **kwargs):
        return self._answer("get", url)

    def delete(self, url, **kwargs):
        return self._answer("delete", url)


@pytest.fixture(params=[404, 500], ids=["status-404", "status-500-older-server"])
def srv(request, monkeypatch):
    s = _Server(request.param)
    monkeypatch.setattr(server.requests, "get", s.get)
    monkeypatch.setattr(server.requests, "delete", s.delete)
    return s


def test_get_tap_returns_the_404_body_unchanged(srv):
    out = server._dispatch("get_tap", {"name": "nope"})
    assert out == BODY, out
    assert ("get", server.DATRIS_API_URL + "/api/v1/tap?name=nope") in srv.calls, srv.calls


def test_delete_tap_returns_the_404_body_unchanged(srv):
    out = server._dispatch("delete_tap", {"name": "nope"})
    assert out == BODY, out
    assert ("delete", server.DATRIS_API_URL + "/api/v1/tap?name=nope") in srv.calls, srv.calls
