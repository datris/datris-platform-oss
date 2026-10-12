"""Story: Show the AI model in use (plans/stories/show-ai-model.md) — the
MCP-server half.

`POST /api/v1/tap/generate` now returns `provider` and `model` (the CodeGen
slot that actually generated the script, or the primary when no CodeGen slot
is set). `create_tap` with `instruction` forwards them as
`generatedWith: {provider, model}` in its success JSON so an agent can tell
the user which model wrote the script. An older server that does not return
the keys yields no `generatedWith` at all (additive, never a null/empty stub),
and the explicit-`script` lane never carries it (no model was involved).

Stubs the `_call` seam like test_test_before_cron.py."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402


SCRIPT = "def fetch():\n    return [{'a': 1, 'b': 2}]\n"


def _stub(monkeypatch, gen_body):
    calls = []

    def call(method, path, timeout=300, **kwargs):
        calls.append((method, path, kwargs))
        if method == "get" and path.startswith("/api/v1/tap?name="):
            return json.dumps({"error": "Tap not found"})
        if method == "post" and path == "/api/v1/tap/generate":
            return json.dumps(gen_body)
        if method == "get" and path == "/api/v1/code-repo":
            return json.dumps({"enabled": False})
        if method == "post" and path == "/api/v1/tap/script":
            return json.dumps({"scriptPath": "tap-scripts/probe_1.py", "storage": "minio"})
        if method == "post" and path == "/api/v1/tap":
            return json.dumps(kwargs.get("json") or {})
        return ""

    monkeypatch.setattr(server, "_call", call)
    return calls


def test_create_tap_instruction_carries_generated_with_when_server_returns_it(monkeypatch):
    _stub(monkeypatch, {
        "script": SCRIPT,
        "packages": [],
        "scriptPath": "tap-scripts/probe_9ea4e291.py",
        "injectedPrompts": [],
        "provider": "anthropic",
        "model": "claude-opus-5-5",
    })
    out = json.loads(server._dispatch("create_tap", {
        "name": "probe", "instruction": "return three rows with a and b", "target_pipeline": "probe",
    }))
    assert "error" not in out, out
    assert out.get("generatedWith") == {"provider": "anthropic", "model": "claude-opus-5-5"}, out


def test_create_tap_instruction_has_no_generated_with_against_older_server(monkeypatch):
    _stub(monkeypatch, {
        "script": SCRIPT,
        "packages": [],
        "scriptPath": "tap-scripts/probe_9ea4e291.py",
        "injectedPrompts": [],
    })
    out = json.loads(server._dispatch("create_tap", {
        "name": "probe", "instruction": "return three rows with a and b", "target_pipeline": "probe",
    }))
    assert "error" not in out, out
    assert "generatedWith" not in out, out


def test_create_tap_with_explicit_script_has_no_generated_with(monkeypatch):
    calls = _stub(monkeypatch, {"script": SCRIPT, "provider": "anthropic", "model": "claude-opus-5-5"})
    out = json.loads(server._dispatch("create_tap", {
        "name": "probe", "script": SCRIPT, "target_pipeline": "probe",
    }))
    assert "error" not in out, out
    assert "generatedWith" not in out, out
    assert not any(p == "/api/v1/tap/generate" for _, p, _ in calls), calls
