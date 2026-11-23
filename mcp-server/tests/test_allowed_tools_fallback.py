"""Story: A chat stops when its user can no longer be verified, and the tool
list is never wider than what the server said.

`list_tools` offers the tools `GET /api/v1/mcp/tools` allows:
  - `{"filtered": true, "tools": [...]}`  -> exactly that set;
  - `{"filtered": false, ...}`            -> the full catalogue (full-access
                                             identity or keys-off install);
  - HTTP 404 (a platform older than the endpoint) -> the full catalogue when
    nothing has been remembered for the key and user in this process, or the
    remembered answer is "no filtering"; otherwise it counts as could not be
    asked and the remembered answer is kept;
  - refused (401, 403) -> no tools at once, replacing any earlier list for
    the same key and on-behalf-of user, with a stderr line saying so;
  - could not be asked (5xx, a connection error or timeout, a body that is
    not the expected JSON, a `tools` that is not a list of strings) -> what the platform last said for the same key
    and user, however old; the full catalogue when it has not answered for
    them in this process (as before), said on stderr.
A refusal or a failure to ask is re-asked 10 seconds after the request
returned, and two listings at once for one key never restore an answer older
than the one the other wrote.

The REST layer stays the boundary: `call_tool` is unchanged and a tool that
is not listed is still dispatched and decided by REST.

Seams: the HTTP fetch is faked at `requests.get` (what `_call` uses today), so
the test does not depend on whether the implementation reads the status via
`_call` or directly. The clock is faked by replacing the `time` module that
`server` sees, so the time-based rules need no sleeping.
"""
import asyncio
import json
import os
import sys
import time as _real_time

import pytest
import requests

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import server  # noqa: E402
from mcp.types import CallToolRequestParams  # noqa: E402

TOOLS_PATH = "/api/v1/mcp/tools"
FIFTEEN_MIN = 15 * 60


class _Clock:
    """Stands in for the `time` module inside `server`: `time()` and
    `monotonic()` read a settable value; everything else is the real module."""

    def __init__(self):
        self.now = 1_000_000.0

    def time(self):
        return self.now

    def monotonic(self):
        return self.now

    def __getattr__(self, name):
        return getattr(_real_time, name)


class _Resp:
    def __init__(self, status, text):
        self.status_code = status
        self.text = text
        self.ok = 200 <= status < 300
        self.headers = {"Content-Type": "application/json"}
        self.content = text.encode("utf-8")

    def json(self):
        return json.loads(self.text)

    def raise_for_status(self):
        if not self.ok:
            raise requests.HTTPError(f"{self.status_code}", response=self)


class _Platform:
    """The tools endpoint, scripted: `answer` is the next reply, either a
    (status, body) pair or an exception instance to raise."""

    def __init__(self):
        self.answer = (200, json.dumps({"filtered": True, "tools": ["get_pipeline"]}))
        self.fetches = 0
        # Runs inside each request, before it answers (a slow request, or
        # another listing finishing meanwhile).
        self.during = None

    def get(self, url, *a, **kw):
        if not str(url).endswith(TOOLS_PATH):
            raise AssertionError(f"unexpected request in a tool-listing test: {url}")
        self.fetches += 1
        answer = self.answer  # this request's reply, fixed when it is sent
        if self.during is not None:
            self.during()
        if isinstance(answer, BaseException):
            raise answer
        status, body = answer
        return _Resp(status, body)


@pytest.fixture
def env(monkeypatch):
    platform = _Platform()
    clock = _Clock()
    monkeypatch.setattr(server.requests, "get", platform.get)
    monkeypatch.setattr(server, "time", clock)
    monkeypatch.setattr(server, "_allowed_tools_cache", {})
    return platform, clock


def _listed(key="scoped-key", obo=""):
    tk = server._session_api_key.set(key)
    to = server._session_on_behalf_of.set(obo)
    try:
        return {t.name for t in asyncio.run(server.list_tools(None, None)).tools}
    finally:
        server._session_api_key.reset(tk)
        server._session_on_behalf_of.reset(to)


def _full():
    return {t.name for t in server._all_tools()}


def _filtered(*names):
    return (200, json.dumps({"filtered": True, "tools": list(names)}))


# The platform answered and refused the key or user.
REFUSALS = [
    pytest.param((401, '{"error":"API key is revoked or invalid"}'), id="401-rejected-key"),
    pytest.param((401, '{"error":"The user this request acts for could not be verified",'
                       '"errorKind":"acting_user_unverified"}'), id="401-unverified-user"),
    pytest.param((403, '{"error":"capability denied","errorKind":"capability_denied"}'), id="403"),
]

# The platform could not be asked: no usable answer from a platform that has the endpoint.
UNREACHABLE = [
    pytest.param((500, '{"error":"Internal error"}'), id="500"),
    pytest.param((503, '{"error":"The user this request acts for could not be checked: the user store is unavailable",'
                       '"errorKind":"user_store_unavailable"}'), id="503-user-store"),
    pytest.param(requests.ConnectionError("connection refused"), id="connection-error"),
    pytest.param(requests.Timeout("read timed out"), id="timeout"),
    pytest.param(ValueError("Invalid header value"), id="send-value-error"),
    pytest.param(TypeError("expected string or bytes-like object"), id="send-type-error"),
    pytest.param(RuntimeError(), id="send-error-without-message"),
    pytest.param((502, "<html><body>Bad Gateway</body></html>"), id="non-json"),
    pytest.param((200, json.dumps({"filtered": True})), id="filtered-without-tools"),
    pytest.param((200, json.dumps(["get_pipeline"])), id="unexpected-shape"),
    pytest.param((200, '{"filtered": true, "tools": ['), id="malformed-json"),
    pytest.param((200, json.dumps({"filtered": True, "tools": [1, 2]})), id="tools-not-strings"),
    pytest.param((200, json.dumps({"filtered": True, "tools": [{"name": "get_pipeline"}]})), id="tools-of-objects"),
    pytest.param((200, json.dumps({"filtered": True, "tools": [["get_pipeline"]]})), id="tools-of-lists"),
    pytest.param((200, json.dumps({"filtered": True, "tools": "get_pipeline"})), id="tools-a-string"),
    pytest.param((200, json.dumps({"filtered": True, "tools": None})), id="tools-null"),
]

FAILURES = REFUSALS + UNREACHABLE


# --- the server answered -----------------------------------------------------

def test_filtered_answer_lists_exactly_that_set(env):
    platform, _ = env
    platform.answer = _filtered("get_pipeline", "list_pipelines")
    assert _listed() == {"get_pipeline", "list_pipelines"}


def test_filtered_answer_with_no_tools_lists_none(env):
    platform, _ = env
    platform.answer = _filtered()
    assert _listed() == set()


def test_unfiltered_answer_lists_the_full_catalogue(env):
    platform, _ = env
    platform.answer = (200, json.dumps({"filtered": False, "tools": ["get_pipeline"]}))
    assert _listed() == _full()


def test_not_found_from_an_older_platform_keeps_the_full_catalogue(env):
    platform, _ = env
    platform.answer = (404, json.dumps({"timestamp": "2026-01-01T00:00:00.000+00:00", "status": 404,
                                        "error": "Not Found", "path": TOOLS_PATH}))
    assert _listed() == _full()


NOT_FOUND = (404, '{"error":"Not Found"}')


def test_not_found_after_a_narrowed_list_keeps_the_narrowed_list(env, capsys):
    platform, clock = env
    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    clock.now += 61
    platform.answer = NOT_FOUND
    assert _listed() == {"get_pipeline"}
    err = capsys.readouterr().err.lower()
    assert "could not ask" in err and "full catalog" not in err, err
    clock.now += 11
    assert _listed() == {"get_pipeline"}
    assert platform.fetches == 3


def test_not_found_after_a_refusal_still_lists_no_tools(env):
    platform, clock = env
    platform.answer = (401, '{"error":"API key is revoked or invalid"}')
    assert _listed() == set()
    clock.now += 11
    platform.answer = NOT_FOUND
    assert _listed() == set()
    clock.now += 11
    assert _listed() == set()


def test_not_found_after_an_unfiltered_answer_lists_the_full_catalogue(env):
    platform, clock = env
    platform.answer = (200, json.dumps({"filtered": False, "tools": []}))
    assert _listed() == _full()
    clock.now += 61
    platform.answer = NOT_FOUND
    assert _listed() == _full()


def test_not_found_after_a_failure_with_nothing_remembered_lists_the_full_catalogue(env):
    platform, clock = env
    platform.answer = (500, '{"error":"Internal error"}')
    assert _listed() == _full()
    clock.now += 11
    platform.answer = NOT_FOUND
    assert _listed() == _full()
    clock.now += 61
    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    clock.now += 61
    platform.answer = NOT_FOUND
    assert _listed() == {"get_pipeline"}


ODD_BODIES = [None, b"\xff\xfe", "", "null", "0", "[]", '"x"', '{"filtered": "yes"}', '{"filtered": true, "tools": {}}',
              '{"filtered": true, "tools": [null]}', '{"filtered": true, "tools": [1.5, true]}',
              '{"filtered": true, "tools": [{"a": [1]}]}', '{"filtered": true, "tools": ["a", ["b"]]}']


@pytest.mark.parametrize("body", ODD_BODIES)
@pytest.mark.parametrize("status", [200, 404, 401, 500, None])
def test_classification_never_raises_for_any_body(status, body):
    kind, _ = server._classify_allowed_tools(status, body)
    assert kind in ("answer", "not_found", "refused", "unreachable")
    if status == 200:
        assert kind == "unreachable", body


# --- the server could not answer ---------------------------------------------

def test_a_failure_building_the_request_counts_as_could_not_ask(env, monkeypatch, capsys):
    platform, clock = env

    def broken_headers():
        raise ValueError("header could not be built")

    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    clock.now += 61
    monkeypatch.setattr(server, "_headers", broken_headers)
    assert server._fetch_allowed_tools()[0] is None
    assert _listed() == {"get_pipeline"}
    assert "could not ask" in capsys.readouterr().err.lower()


@pytest.mark.parametrize("failure", [ValueError("bad url"), KeyError("x"), RuntimeError()])
def test_fetching_the_listing_never_raises(env, failure):
    platform, _ = env
    platform.answer = failure
    status, reason = server._fetch_allowed_tools()
    assert status is None and isinstance(reason, str) and reason


@pytest.mark.parametrize("failure", REFUSALS)
def test_a_refusal_with_nothing_cached_lists_no_tools(env, failure, capsys):
    platform, _ = env
    platform.answer = failure
    assert _listed() == set()
    err = capsys.readouterr().err.lower()
    assert "no tools" in err, err
    assert "refused" in err, err
    assert "full catalog" not in err, err


@pytest.mark.parametrize("failure", UNREACHABLE)
def test_could_not_ask_before_any_answer_lists_the_full_catalogue(env, failure, capsys):
    platform, _ = env
    platform.answer = failure
    assert _listed() == _full()
    err = capsys.readouterr().err.lower()
    assert "could not ask" in err, err
    assert "full catalog" in err, err


@pytest.mark.parametrize("failure", REFUSALS)
def test_a_refusal_after_a_good_answer_lists_no_tools_at_once(env, failure, capsys):
    platform, clock = env
    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    clock.now += 61
    platform.answer = failure
    assert _listed() == set()
    err = capsys.readouterr().err.lower()
    assert "no tools" in err and "full catalog" not in err, err


@pytest.mark.parametrize("failure", UNREACHABLE)
def test_could_not_ask_after_a_good_answer_lists_the_last_good_set(env, failure):
    platform, clock = env
    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    clock.now += 61  # past the success TTL, so the next listing fetches again
    platform.answer = failure
    assert _listed() == {"get_pipeline"}
    clock.now += 10 * 60
    assert _listed() == {"get_pipeline"}


@pytest.mark.parametrize("failure", UNREACHABLE)
def test_could_not_ask_long_after_the_last_good_answer_still_lists_it(env, failure):
    platform, clock = env
    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    platform.answer = failure
    clock.now += FIFTEEN_MIN + 1
    assert _listed() == {"get_pipeline"}
    clock.now += 24 * 3600
    assert _listed() == {"get_pipeline"}


@pytest.mark.parametrize("failure", UNREACHABLE)
def test_could_not_ask_after_a_refusal_still_lists_no_tools(env, failure, capsys):
    platform, clock = env
    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    clock.now += 61
    platform.answer = (401, '{"error":"API key is revoked or invalid"}')
    assert _listed() == set()
    clock.now += 11
    platform.answer = failure
    assert _listed() == set()
    err = capsys.readouterr().err.lower()
    assert "full catalog" not in err, err


def test_last_good_unfiltered_answer_is_kept_through_a_short_outage(env):
    platform, clock = env
    platform.answer = (200, json.dumps({"filtered": False, "tools": []}))
    assert _listed() == _full()
    clock.now += 61
    platform.answer = requests.ConnectionError("connection refused")
    assert _listed() == _full()


def test_a_refused_key_loses_its_list_at_once(env):
    platform, clock = env
    platform.answer = _filtered("get_pipeline", "delete_pipeline")
    assert _listed("revoked-later") == {"get_pipeline", "delete_pipeline"}
    platform.answer = (401, '{"error":"API key is revoked or invalid"}')
    clock.now += 61  # the good answer's reuse has ended, so the next listing asks again
    assert _listed("revoked-later") == set()
    start = clock.now
    while clock.now - start <= FIFTEEN_MIN + 120:
        clock.now += 11
        assert _listed("revoked-later") == set(), f"listed again {clock.now - start:.0f}s after the refusal"


def test_recovery_after_a_refusal_serves_the_new_answer(env):
    platform, clock = env
    platform.answer = (403, '{"error":"capability denied","errorKind":"capability_denied"}')
    assert _listed() == set()
    clock.now += 11
    platform.answer = _filtered("list_pipelines")
    assert _listed() == {"list_pipelines"}


def test_listing_is_never_wider_than_the_last_answer_the_server_gave(env):
    platform, clock = env
    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    for i in range(200):
        clock.now += 13
        f = FAILURES[i % len(FAILURES)].values[0]
        platform.answer = f
        assert _listed() <= {"get_pipeline"}, f"wider after failure {i}"


def test_a_failure_is_cached_for_ten_seconds(env):
    platform, clock = env
    platform.answer = (500, '{"error":"Internal error"}')
    assert _listed() == _full()
    assert platform.fetches == 1
    clock.now += 9
    assert _listed() == _full()
    assert platform.fetches == 1, "a failure must be served from the cache for 10 seconds"
    clock.now += 2
    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    assert platform.fetches == 2, "a failure must not be cached longer than 10 seconds"


def test_a_refusal_is_cached_for_ten_seconds(env):
    platform, clock = env
    platform.answer = (401, '{"error":"API key is revoked or invalid"}')
    assert _listed() == set()
    clock.now += 9
    assert _listed() == set()
    assert platform.fetches == 1
    clock.now += 2
    platform.answer = _filtered("get_pipeline")
    assert _listed() == {"get_pipeline"}
    assert platform.fetches == 2


@pytest.mark.parametrize("failure", FAILURES)
def test_a_slow_failure_is_cached_from_when_the_request_returned(env, failure):
    platform, clock = env

    def slow():
        clock.now += 15  # the request takes its full timeout

    platform.during = slow
    platform.answer = failure
    first = _listed()
    assert _listed() == first
    assert _listed() == first
    assert platform.fetches == 1, "three listings in a row must make one request"


def test_a_could_not_ask_finishing_after_a_concurrent_refusal_keeps_the_refusal(env):
    platform, clock = env
    platform.answer = _filtered("get_pipeline", "delete_pipeline")
    assert _listed() == {"get_pipeline", "delete_pipeline"}
    clock.now += 61

    def other_listing_is_refused():
        # A second listing for the same key and user runs to completion while
        # the first request is still in flight, and is refused.
        platform.during = None
        platform.answer = (401, '{"error":"API key is revoked or invalid"}')
        assert server._allowed_tool_names() == set()

    platform.during = other_listing_is_refused
    platform.answer = requests.ConnectionError("connection refused")
    assert _listed() == set()
    assert platform.fetches == 3
    clock.now += 11
    platform.answer = requests.ConnectionError("connection refused")
    assert _listed() == set()


def test_a_could_not_ask_finishing_after_a_concurrent_answer_keeps_that_answer(env):
    platform, clock = env
    platform.answer = _filtered("get_pipeline", "delete_pipeline")
    assert _listed() == {"get_pipeline", "delete_pipeline"}
    clock.now += 61

    def other_listing_answers():
        platform.during = None
        platform.answer = _filtered("get_pipeline")
        assert server._allowed_tool_names() == {"get_pipeline"}

    platform.during = other_listing_answers
    platform.answer = requests.ConnectionError("connection refused")
    assert _listed() == {"get_pipeline"}
    clock.now += 61
    platform.answer = requests.ConnectionError("connection refused")
    assert _listed() == {"get_pipeline"}


def test_recovery_after_a_failure_serves_the_new_answer(env):
    platform, clock = env
    platform.answer = _filtered("get_pipeline")
    _listed()
    clock.now += 61
    platform.answer = requests.ConnectionError("connection refused")
    assert _listed() == {"get_pipeline"}
    clock.now += 11
    platform.answer = _filtered("list_pipelines")
    assert _listed() == {"list_pipelines"}


def test_last_good_is_kept_per_key_and_on_behalf_of_user(env):
    platform, clock = env
    platform.answer = _filtered("get_pipeline", "delete_pipeline")
    assert _listed("ui-key", "root") == {"get_pipeline", "delete_pipeline"}
    platform.answer = _filtered("get_pipeline")
    assert _listed("ui-key", "bob") == {"get_pipeline"}
    clock.now += 61
    platform.answer = requests.ConnectionError("connection refused")
    assert _listed("ui-key", "bob") == {"get_pipeline"}
    assert _listed("ui-key", "root") == {"get_pipeline", "delete_pipeline"}
    # Neither user's last good answer is served to a user or key with none:
    # they get the full catalogue, as before any answer.
    assert _listed("ui-key", "carol") == _full()
    assert _listed("other-key", "bob") == _full()


def test_a_refusal_for_one_user_leaves_another_users_list(env):
    platform, clock = env
    platform.answer = _filtered("get_pipeline", "delete_pipeline")
    assert _listed("ui-key", "root") == {"get_pipeline", "delete_pipeline"}
    platform.answer = (401, '{"error":"The user this request acts for could not be verified",'
                            '"errorKind":"acting_user_unverified"}')
    assert _listed("ui-key", "gone") == set()
    clock.now += 61
    platform.answer = requests.ConnectionError("connection refused")
    assert _listed("ui-key", "root") == {"get_pipeline", "delete_pipeline"}
    assert _listed("ui-key", "gone") == set()


# --- unchanged: call_tool is decided by REST, not by the listing -------------

def test_a_tool_that_is_not_listed_is_still_dispatched(env, monkeypatch):
    platform, _ = env
    platform.answer = _filtered("get_pipeline")
    assert "list_pipelines" not in _listed()
    calls = []

    def dispatch(name, args):
        calls.append(name)
        return '{"count":0,"pipelines":[]}'

    monkeypatch.setattr(server, "_dispatch", dispatch)
    monkeypatch.setattr(server, "_activity_record", lambda *a, **k: None)
    res = asyncio.run(server.validated_call_tool(None, CallToolRequestParams(name="list_pipelines", arguments={})))
    assert calls == ["list_pipelines"]
    assert res.is_error is False
