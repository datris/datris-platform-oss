"""Story: Delete keeps a hand-edited repository script and warns; missing
pipeline GET is 404 (plans/stories/pipeline-delete-drift-warning-and-404.md).

GET /api/v1/pipeline?pipeline=<missing> now answers 404 instead of 500 with
the same body, {"error": "Pipeline: <name> is not configured in the NoSQL
database"}. get_pipeline goes through the real `_call` with `requests.get`
answering a genuine 404 (and a 500, as an older server does), so the status
is what changes and the tool output does not."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


BODY = '{"error": "Pipeline: nope is not configured in the NoSQL database"}'


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
        self.gets = []

    def get(self, url, **kwargs):
        self.gets.append((url, kwargs.get("params")))
        if url.endswith("/api/v1/pipeline"):
            return _Resp(self.status, BODY)
        return _Resp(200, "")


@pytest.fixture(params=[404, 500], ids=["status-404", "status-500-older-server"])
def srv(request, monkeypatch):
    s = _Server(request.param)
    monkeypatch.setattr(server.requests, "get", s.get)
    return s


def test_get_pipeline_returns_the_404_body_unchanged(srv):
    out = server._dispatch("get_pipeline", {"pipeline": "nope"})
    assert out == BODY, out
    assert any(u.endswith("/api/v1/pipeline") and p == {"pipeline": "nope"} for u, p in srv.gets), srv.gets
