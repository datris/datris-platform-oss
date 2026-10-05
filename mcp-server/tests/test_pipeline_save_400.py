"""Story: An invalid pipeline config is a 400, not a 500
(plans/stories/pipeline-save-validation-400.md).

POST /api/v1/pipeline now answers a validator refusal with HTTP 400 and the
same body as before, {"error": "<message>"}. create_pipeline must surface that
as an error carrying the server's message, exactly as it did for the 500.
The generate call is stubbed the same way test_field_protection_tools.py does;
the save goes through the real `_call` with `requests.post` answering a
genuine 400 response, so the status is what changes and nothing else."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


MESSAGE = "Preset 'hipaa-safe-harbor': field 'phone' looks like phone and has no protection."


class _Resp:
    def __init__(self, status_code, body):
        self.status_code = status_code
        self.text = body
        self.ok = 200 <= status_code < 300

    def json(self):
        return json.loads(self.text)


class _Server:
    """Generate returns a two-field schema; the save answers `status` with
    {"error": MESSAGE}; any other request is recorded."""

    def __init__(self, status):
        self.status = status
        self.posts = []
        self.gets = []

    def upload_content(self, path, content_b64, filename, data=None):
        assert path == "/api/v1/pipeline/generate"
        return json.dumps({
            "name": data["pipeline"],
            "source": {
                "fileAttributes": {"csvAttributes": {"delimiter": ",", "header": True}},
                "schemaProperties": {"fields": [
                    {"name": "mrn", "type": "string"},
                    {"name": "phone", "type": "string"},
                ]},
            },
        })

    def post(self, url, **kwargs):
        self.posts.append((url, kwargs.get("json")))
        if url.endswith("/api/v1/pipeline"):
            return _Resp(self.status, json.dumps({"error": MESSAGE}))
        return _Resp(200, "")

    def get(self, url, **kwargs):
        self.gets.append(url)
        return _Resp(200, json.dumps({"name": "x"}))


def _args():
    return {
        "pipeline": "fp_400",
        "destination": "postgres",
        "table": "fp_400",
        "filename": "fp_400.csv",
        "content_text": "mrn,phone\n1,555-0100\n",
    }


@pytest.fixture(params=[400, 500], ids=["status-400", "status-500-older-server"])
def srv(request, monkeypatch):
    s = _Server(request.param)
    monkeypatch.setattr(server, "_upload_content", s.upload_content)
    monkeypatch.setattr(server.requests, "post", s.post)
    monkeypatch.setattr(server.requests, "get", s.get)
    return s


def test_a_400_from_pipeline_save_surfaces_as_an_error_with_the_servers_message(srv):
    raw = server._dispatch("create_pipeline", _args())
    out = json.loads(raw)
    assert "error" in out, out
    assert "status" not in out, out
    assert MESSAGE in out["error"], out
    assert any(u.endswith("/api/v1/pipeline") for u, _ in srv.posts), srv.posts
    # A refused save is never reported as created and is not read back.
    assert not any("pipeline?" in u or u.endswith("/api/v1/pipeline") for u in srv.gets), srv.gets


def test_save_failed_treats_a_400_error_body_as_a_failure():
    assert server._save_failed(json.dumps({"error": MESSAGE}))
    assert not server._save_failed(json.dumps({"warnings": []}))
