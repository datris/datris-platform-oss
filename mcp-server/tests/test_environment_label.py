"""Story: Environment label everywhere, part 2: MCP server instructions, tool
echo, CLI (plans/stories/multi-env-2b-environment-label-mcp.md).

Pins:
- `_environment_label()` reads `environmentLabel` from GET /api/v1/version,
  is cached in-process like the destinations cache, and never raises (None on
  a missing key, an HTTP error or a connection failure).
- `_render_instructions()` opens with "You are connected to the **<label>**
  environment" when a label is set, adds a confirm sentence for `production`,
  and is byte-identical to the unlabelled render when unset.
- `call_tool` adds `"environment": <label>` to JSON-object, non-error results
  of the nine `_ECHO_ENVIRONMENT_TOOLS` and leaves everything else verbatim.

Server calls are stubbed the way test_call_tool_is_error.py does it
(`_dispatch` / `_activity_record`) and the way the destinations cache tests
would (`server.requests.get`)."""
import asyncio
import itertools
import json
import os
import re
import sys

import pytest
import requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import server  # noqa: E402

ECHO_TOOLS = [
    "restore_catalog",
    "delete_catalog",
    "delete_pipeline",
    "delete_tap",
    "delete_tap_secret",
    "update_tap",
    "update_secret",
    "restore_tap_version",
    "restore_pipeline_version",
]

_FIXED_DESTS = ["postgres", "mongodb", "objectstore", "kafka", "snowflake", "databricks"]


# ------------------------------------------------------------------ helpers ---

_clock_base = itertools.count(1)


@pytest.fixture
def clock(monkeypatch):
    """A fake wall clock far past any earlier test's clock, so whatever the
    label cache holds from a previous test has expired (TTL is the
    destinations TTL). Tests advance it with clock['now'] += secs."""
    state = {"now": 10_000_000_000.0 + next(_clock_base) * 1_000_000.0}
    monkeypatch.setattr(server.time, "time", lambda: state["now"])
    return state


class _VersionResp:
    def __init__(self, status_code, body):
        self.status_code = status_code
        self.text = body if isinstance(body, str) else json.dumps(body)
        self.content = self.text.encode("utf-8")
        self.ok = status_code < 400
        self.headers = {"Content-Type": "application/json"}

    def json(self):
        return json.loads(self.text)

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}")


def _install_version_get(monkeypatch, responder):
    calls = []

    def fake_get(url, *args, **kwargs):
        calls.append((url, kwargs))
        return responder(url)

    monkeypatch.setattr(server.requests, "get", fake_get)
    return calls


def _version_calls(calls):
    return [u for (u, _k) in calls if u.split("?")[0].endswith("/api/v1/version")]


def _set_label(monkeypatch, label):
    # raising=True: the symbol must exist (fails today with AttributeError).
    monkeypatch.setattr(server, "_environment_label", lambda: label, raising=True)


def _unlabelled_render():
    return server._render_destination_templates(server._INSTRUCTIONS_TEMPLATE, list(_FIXED_DESTS))


@pytest.fixture
def fixed_dests(monkeypatch):
    monkeypatch.setattr(server, "_available_structured_destinations", lambda: list(_FIXED_DESTS))


def _call_tool(monkeypatch, name, body):
    monkeypatch.setattr(server, "_dispatch", lambda n, a: body)
    monkeypatch.setattr(server, "_activity_record", lambda *a, **k: None)
    return asyncio.run(server.call_tool(name, {}))


# ------------------------------------------------- Acceptance bullet 1: fetch ---

def test_label_fetched_and_cached(monkeypatch, clock):
    calls = _install_version_get(monkeypatch, lambda url: _VersionResp(200, {"version": "1.50.0", "environmentLabel": "qa"}))
    assert server._environment_label() == "qa"
    assert server._environment_label() == "qa"
    assert len(_version_calls(calls)) == 1, f"second call inside the TTL must be served from cache: {calls}"
    url, kwargs = calls[0]
    assert url == f"{server.DATRIS_API_URL}/api/v1/version"
    assert "headers" in kwargs, "the /version fetch must send _headers() (API key / identity)"
    # Past the TTL it fetches again.
    clock["now"] += server.DESTINATIONS_CACHE_TTL_SECS + 1
    assert server._environment_label() == "qa"
    assert len(_version_calls(calls)) == 2


def test_label_absent_on_old_server(monkeypatch, clock):
    _install_version_get(monkeypatch, lambda url: _VersionResp(200, {"version": "1.49.0", "environment": "local"}))
    assert server._environment_label() is None


@pytest.mark.parametrize("failure", ["http500", "connection_error", "non_json"])
def test_label_fetch_failure_is_none_not_raise(monkeypatch, clock, failure):
    def responder(url):
        if failure == "http500":
            return _VersionResp(500, {"error": "boom"})
        if failure == "connection_error":
            raise requests.exceptions.ConnectionError("connection refused")
        return _VersionResp(200, "<html>not json</html>")

    _install_version_get(monkeypatch, responder)
    assert server._environment_label() is None


# ------------------------------------------ Acceptance bullet 2: instructions ---

def test_instructions_open_with_environment_line_when_set(monkeypatch, fixed_dests):
    _set_label(monkeypatch, "qa")
    rendered = server._render_instructions()
    assert rendered.startswith("You are connected to the **qa** environment."), rendered[:300]
    first_para = rendered.split("\n\n", 1)[0]
    assert "Name it when you report a change." in first_para
    # The template follows unchanged after the prepended paragraph.
    assert rendered.endswith(_unlabelled_render())


def test_instructions_unchanged_when_unset(monkeypatch, fixed_dests):
    _set_label(monkeypatch, None)
    assert server._render_instructions() == _unlabelled_render()


def test_production_adds_confirm_sentence_and_names_no_domain(monkeypatch, fixed_dests):
    _set_label(monkeypatch, "qa")
    qa_prefix = server._render_instructions()[: -len(_unlabelled_render())]
    _set_label(monkeypatch, "production")
    rendered = server._render_instructions()
    base = _unlabelled_render()
    assert rendered.endswith(base)
    prefix = rendered[: -len(base)]
    assert prefix.startswith("You are connected to the **production** environment.")
    assert "confirm" in prefix.lower(), prefix
    assert "confirm" not in qa_prefix.lower(), f"the confirm sentence is production-only: {qa_prefix!r}"
    assert len(prefix) > len(qa_prefix.replace("**qa**", "**production**"))
    # Generic wording only: no proper nouns mid-sentence (Datris itself aside).
    text = prefix.replace("**production**", "production")
    for sentence in re.split(r"(?<=[.!?:;])\s+", text.strip()):
        words = re.findall(r"[A-Za-z][\w'-]*", sentence)
        for w in words[1:]:
            assert not w[0].isupper() or w == "Datris", f"proper noun / domain word {w!r} in {sentence!r}"


# ------------------------------------------------ Acceptance bullet 4: echo ---

@pytest.mark.parametrize("tool", ECHO_TOOLS)
def test_delete_and_update_tools_echo_environment(monkeypatch, tool):
    assert tool in server._ECHO_ENVIRONMENT_TOOLS
    assert len(server._ECHO_ENVIRONMENT_TOOLS) == 9
    _set_label(monkeypatch, "qa")

    body = json.dumps({"status": "ok", "name": "thing"})
    res = _call_tool(monkeypatch, tool, body)
    assert res.isError is False
    assert json.loads(res.content[0].text) == {"status": "ok", "name": "thing", "environment": "qa"}

    # Non-JSON bodies come back verbatim.
    res = _call_tool(monkeypatch, tool, "Deleted.")
    assert res.content[0].text == "Deleted."

    # Error bodies come back verbatim.
    err = json.dumps({"error": "not found"})
    res = _call_tool(monkeypatch, tool, err)
    assert res.isError is True
    assert res.content[0].text == err

    # Unset label: today's output exactly.
    _set_label(monkeypatch, None)
    res = _call_tool(monkeypatch, tool, body)
    assert res.content[0].text == body


@pytest.mark.parametrize("tool", ["list_pipelines", "get_version", "run_tap", "run_pipeline"])
def test_read_tools_do_not_echo_environment(monkeypatch, tool):
    assert tool not in server._ECHO_ENVIRONMENT_TOOLS
    _set_label(monkeypatch, "qa")
    body = json.dumps({"version": "1.50.0", "environmentLabel": "qa", "count": 1})
    res = _call_tool(monkeypatch, tool, body)
    assert res.content[0].text == body
