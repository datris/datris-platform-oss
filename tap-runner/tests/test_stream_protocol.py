"""Story: Streaming pipeline Phase 4 — taps stop buffering the payload
(plans/stories/streaming-pipeline-phase4.md), the sidecar half.

`tap-runner/app.py` gains a streaming execution path (story Step 3). Protocol
pinned here, as the story states it:

* Request `POST /execute` with `"recordsStream": true` (same body otherwise:
  script, wrapper, env, packages, timeoutSec).
* The runner mkfifos in its per-run scratch, points `DATRIS_TAP_OUTPUT` at the
  FIFO in the child's env, opens it `O_RDONLY | O_NONBLOCK` and polls it
  alongside the child, streaming the bytes into a **chunked** HTTP response
  while the script runs.
* After the child exits (or times out) it appends one trailer: the byte
  `0x1E` followed by a JSON line `{"stdout", "stderr", "exitCode", "timedOut"}`.
* The streaming response is NOT `application/json` (the server falls back to
  the legacy JSON body when it sees that content type).
* A script that never opens the FIFO (raises first) must still produce a
  trailer — no deadlock. Timeout and non-zero exit still produce a trailer.
* Scratch (FIFO included) is wiped in `finally`.
* A request without `recordsStream` is byte-for-byte the legacy behaviour:
  `application/json` body with stdout/stderr/exitCode/timedOut and no
  `DATRIS_TAP_OUTPUT` in the child's env.

The runner is exercised for real over loopback with `http.client` (which
decodes chunked framing). The "wrapper" handed to it is a minimal stand-in that
honours `DATRIS_TAP_OUTPUT`; the real wrapper is TapScriptRunner's and is
covered by TapStagingSpec. No pytest fixtures exist under tap-runner/ today —
run with `python3 -m pytest tap-runner/tests -q`."""
import glob
import http.client
import json
import os
import sys
import threading
import time
from http.server import ThreadingHTTPServer

import pytest

# No auth for the test server: a weak token + a token file that does not
# exist resolve to TOKEN == "" at import time.
os.environ["TAP_RUNNER_TOKEN"] = ""
os.environ["TAP_RUNNER_TOKEN_FILE"] = "/nonexistent/tap-runner-token"
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import app  # noqa: E402

SENTINEL = b"\x1e"


@pytest.fixture(scope="module")
def runner():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        yield srv.server_address[1]
    finally:
        srv.shutdown()


def _post(port, body, timeout=30):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    conn.request("POST", "/execute", body=json.dumps(body).encode("utf-8"),
                 headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    data = resp.read()
    headers = {k.lower(): v for k, v in resp.getheaders()}
    conn.close()
    return resp.status, headers, data


def _split(data):
    """(record bytes, trailer dict) — the trailer is the JSON after the last 0x1E."""
    assert SENTINEL in data, "no 0x1E trailer sentinel in the streamed response"
    records, _, trailer = data.rpartition(SENTINEL)
    return records, json.loads(trailer.decode("utf-8").strip())


# A stand-in wrapper that honours DATRIS_TAP_OUTPUT the way the real one will.
STREAMING_WRAPPER = """
import json, os, sys, time
n = int(os.environ.get("N_RECORDS", "5"))
out = os.environ["DATRIS_TAP_OUTPUT"]
with open(out, "w") as f:
    for i in range(n):
        f.write(json.dumps({"id": i, "pad": "x" * 40}) + "\\n")
        f.flush()
print(json.dumps({"type": "json", "count": n, "columns": ["id", "pad"]}))
print("[wrapper] done", file=sys.stderr)
"""

LEGACY_WRAPPER = """
import json, os
print(json.dumps({"type": "json", "data": [{"id": 1}], "saw_output_var": os.environ.get("DATRIS_TAP_OUTPUT")}))
"""


def _scratch_dirs():
    return set(glob.glob("/tmp/tap_*"))


# ----------------------------------------------------------- streaming path ---

def test_streaming_response_carries_records_then_a_0x1e_trailer(runner):
    before = _scratch_dirs()
    status, headers, data = _post(runner, {
        "script": "", "wrapper": STREAMING_WRAPPER, "recordsStream": True,
        "env": {"N_RECORDS": "5000"}, "packages": [], "timeoutSec": 30,
    })
    assert status == 200
    assert "application/json" not in headers.get("content-type", ""), headers
    assert headers.get("transfer-encoding", "").lower() == "chunked", headers

    records, trailer = _split(data)
    lines = records.decode("utf-8").splitlines()
    assert len(lines) == 5000
    assert json.loads(lines[0]) == {"id": 0, "pad": "x" * 40}
    assert json.loads(lines[-1])["id"] == 4999

    assert trailer["exitCode"] == 0
    assert trailer["timedOut"] is False
    envelope = json.loads(trailer["stdout"].strip())
    assert envelope["type"] == "json" and envelope["count"] == 5000
    assert "data" not in envelope
    assert "[wrapper] done" in trailer["stderr"]
    assert set(trailer) >= {"stdout", "stderr", "exitCode", "timedOut"}

    # Scratch (with the FIFO) is gone once the response is complete.
    assert _scratch_dirs() - before == set()


def test_zero_records_is_an_empty_body_before_the_trailer(runner):
    status, _, data = _post(runner, {
        "script": "", "wrapper": STREAMING_WRAPPER, "recordsStream": True,
        "env": {"N_RECORDS": "0"}, "packages": [], "timeoutSec": 30,
    })
    assert status == 200
    records, trailer = _split(data)
    assert records == b""
    assert trailer["exitCode"] == 0 and trailer["timedOut"] is False
    assert json.loads(trailer["stdout"].strip())["count"] == 0


def test_script_that_raises_before_opening_the_fifo_still_gets_a_trailer(runner):
    # A blocking open() on the FIFO would deadlock here; the story mandates
    # O_RDONLY | O_NONBLOCK plus polling alongside the child.
    wrapper = "raise RuntimeError('boom before any write')\n"
    started = time.time()
    status, _, data = _post(runner, {
        "script": "", "wrapper": wrapper, "recordsStream": True,
        "env": {}, "packages": [], "timeoutSec": 30,
    }, timeout=20)
    assert time.time() - started < 15, "runner must not block on an unopened FIFO"
    assert status == 200
    records, trailer = _split(data)
    assert records == b""
    assert trailer["exitCode"] != 0
    assert trailer["timedOut"] is False
    assert "boom before any write" in trailer["stderr"]


def test_nonzero_exit_mid_write_still_gets_a_trailer_with_the_partial_bytes(runner):
    wrapper = """
import json, os, sys
out = os.environ["DATRIS_TAP_OUTPUT"]
with open(out, "w") as f:
    f.write(json.dumps({"id": 1}) + "\\n"); f.flush()
    f.write(json.dumps({"id": 2}) + "\\n"); f.flush()
    print("dying", file=sys.stderr, flush=True)
    os._exit(137)
"""
    status, _, data = _post(runner, {
        "script": "", "wrapper": wrapper, "recordsStream": True,
        "env": {}, "packages": [], "timeoutSec": 30,
    })
    assert status == 200
    records, trailer = _split(data)
    assert records.decode("utf-8").splitlines() == ['{"id": 1}', '{"id": 2}']
    assert trailer["exitCode"] == 137
    assert trailer["timedOut"] is False
    assert trailer["stdout"].strip() == "", "no envelope was printed"
    assert "dying" in trailer["stderr"]


def test_timeout_produces_a_trailer_with_timedout_true(runner):
    wrapper = """
import json, os, time
out = os.environ["DATRIS_TAP_OUTPUT"]
with open(out, "w") as f:
    f.write(json.dumps({"id": 1}) + "\\n"); f.flush()
    time.sleep(30)
"""
    started = time.time()
    status, _, data = _post(runner, {
        "script": "", "wrapper": wrapper, "recordsStream": True,
        "env": {}, "packages": [], "timeoutSec": 1,
    }, timeout=25)
    assert time.time() - started < 15, "timeoutSec=1 must end the run well before the socket timeout"
    assert status == 200
    records, trailer = _split(data)
    assert records.decode("utf-8").splitlines() == ['{"id": 1}']
    assert trailer["timedOut"] is True
    assert trailer["exitCode"] == -1


def test_child_env_is_still_allowlist_plus_handed_vars_on_the_streaming_path(runner):
    wrapper = """
import json, os, sys
keys = sorted(k for k in os.environ if k not in ("DATRIS_TAP_OUTPUT",))
out = os.environ["DATRIS_TAP_OUTPUT"]
open(out, "w").close()
print(json.dumps({"keys": keys, "handed": os.environ.get("DATRIS_TAP_PARAM_x")}))
"""
    _, _, data = _post(runner, {
        "script": "", "wrapper": wrapper, "recordsStream": True,
        "env": {"DATRIS_TAP_PARAM_x": "1"}, "packages": [], "timeoutSec": 30,
    })
    _, trailer = _split(data)
    seen = json.loads(trailer["stdout"].strip())
    assert seen["handed"] == "1"
    extra = set(seen["keys"]) - set(app.ALLOWLIST) - {"DATRIS_TAP_PARAM_x", "PIP_NO_CACHE_DIR"}
    assert extra == set(), f"streaming path leaked env vars into the tap: {extra}"


# -------------------------------------------------------------- legacy path ---

def test_request_without_recordsstream_is_the_legacy_json_response(runner):
    status, headers, data = _post(runner, {
        "script": "", "wrapper": LEGACY_WRAPPER, "env": {}, "packages": [], "timeoutSec": 30,
    })
    assert status == 200
    assert headers.get("content-type", "").startswith("application/json")
    assert SENTINEL not in data
    body = json.loads(data.decode("utf-8"))
    assert set(body) == {"stdout", "stderr", "exitCode", "timedOut"}
    assert body["exitCode"] == 0 and body["timedOut"] is False
    envelope = json.loads(body["stdout"].strip())
    assert envelope["data"] == [{"id": 1}], "legacy path still carries records inline on stdout"
    assert envelope["saw_output_var"] is None, "legacy requests must not set DATRIS_TAP_OUTPUT"


def test_explicit_recordsstream_false_is_legacy_too(runner):
    status, headers, data = _post(runner, {
        "script": "", "wrapper": LEGACY_WRAPPER, "recordsStream": False,
        "env": {}, "packages": [], "timeoutSec": 30,
    })
    assert status == 200
    assert headers.get("content-type", "").startswith("application/json")
    assert json.loads(json.loads(data)["stdout"].strip())["saw_output_var"] is None
