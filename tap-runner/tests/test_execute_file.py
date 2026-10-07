"""Story: generated data-quality and transformation scripts run in an isolated
runner, like taps (plans/stories/codegen-script-isolation.md), the runner half.

`tap-runner/app.py` gains `POST /execute-file`. Protocol, as the story states it:

* Request body: ONE JSON line `{"script", "timeoutSec", "inputName", "outputName"?}`,
  then `\\n`, then the raw input bytes. `Content-Length` covers line + bytes.
  Same bearer check as `/execute`.
* The runner streams the input in 64 KB chunks to `<run dir>/<inputName>`, the run
  dir being `mkdtemp(prefix="cg_")` under `CODEGEN_SCRATCH_DIR` (default `/tmp`),
  runs `python3 script.py <input> [<output>]` with `_base_env(scratch)` only (no
  handed env, no packages), cwd = scratch.
* Response: ONE JSON line `{"stdout", "stderr", "exitCode", "timedOut", "outputBytes"}`,
  then `\\n`, then the output file bytes when `outputName` was given.
  `Content-Length` = line + file size.
* ENOSPC while receiving the input -> 507, JSON error naming `codegen-scratch`.
* The run dir is removed after success, script failure, timeout and a dropped
  connection; at process start, with `CODEGEN_SCRATCH_DIR` set, every leftover
  `cg_*` directory under it is deleted.

Pinned seam (where the story leaves it open):

    app.execute_file(meta, rfile, length, wfile)    # story signature, exercised via HTTP here
    CODEGEN_SCRATCH_DIR is read from os.environ at request time (not frozen at import),
        so tests can point it at tmp_path with monkeypatch.setenv.
    The input and output files are opened with the builtin `open` as seen from the
        app module's globals (so `monkeypatch.setattr(app, "open", ...)` intercepts them);
        the input file is opened for writing ("w"/"x"/"a" in mode), the output for reading.
    A timed-out script answers 200 with timedOut true, exitCode -1 and outputBytes 0,
        and no bytes after the result line.

The runner is exercised for real over loopback with raw sockets / http.client.
Run with `python3 -m pytest tap-runner/tests -q`.
"""
import errno
import glob
import http.client
import io
import json
import os
import socket
import subprocess
import sys
import threading
import time
from http.server import ThreadingHTTPServer

import pytest

os.environ["TAP_RUNNER_TOKEN"] = ""
os.environ["TAP_RUNNER_TOKEN_FILE"] = "/nonexistent/tap-runner-token"
APP_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
sys.path.insert(0, APP_DIR)

import app  # noqa: E402

CHUNK = 64 * 1024
ALLOWED_ENV = set(app.ALLOWLIST) | {"PIP_NO_CACHE_DIR"}
# Set by the interpreter / OS in every child, not handed by the runner:
# LC_CTYPE (PEP 538 locale coercion), __CF_USER_TEXT_ENCODING (macOS).
INTERPRETER_ENV = {"LC_CTYPE", "__CF_USER_TEXT_ENCODING"}


# ---------------------------------------------------------------- helpers

class _Counting:
    """Wraps a file-like object and records the size of every read and write."""

    def __init__(self, inner, log):
        self._inner = inner
        self._log = log

    def read(self, n=-1, *a):
        data = self._inner.read(n, *a) if n is not None else self._inner.read()
        self._log.append(("read", n if (n is not None and n >= 0) else float("inf"), len(data)))
        return data

    def read1(self, n=-1):
        data = self._inner.read1(n)
        self._log.append(("read", n if n >= 0 else float("inf"), len(data)))
        return data

    def readinto(self, b):
        n = self._inner.readinto(b)
        self._log.append(("read", len(b), n or 0))
        return n

    def write(self, data):
        self._log.append(("write", len(data), len(data)))
        return self._inner.write(data)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self._inner.close()
        return False

    def __iter__(self):
        return iter(self._inner)

    def __getattr__(self, name):
        return getattr(self._inner, name)


class _CountingHandler(app.Handler):
    """The real handler with its socket streams wrapped so every read and write
    of the request/response body is recorded."""
    log = []

    def setup(self):
        super().setup()
        self.rfile = _Counting(self.rfile, _CountingHandler.log)
        self.wfile = _Counting(self.wfile, _CountingHandler.log)


def _serve(handler):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


@pytest.fixture
def scratch(tmp_path, monkeypatch):
    d = tmp_path / "scratch"
    d.mkdir()
    monkeypatch.setenv("CODEGEN_SCRATCH_DIR", str(d))
    return d


@pytest.fixture
def runner(scratch):
    srv = _serve(app.Handler)
    try:
        yield srv.server_address[1]
    finally:
        srv.shutdown()


def _body(meta, payload):
    return json.dumps(meta).encode("utf-8") + b"\n" + payload


def _post_file(port, meta, payload, headers=None, timeout=60):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    body = _body(meta, payload)
    h = {"Content-Type": "application/octet-stream", "Content-Length": str(len(body))}
    h.update(headers or {})
    conn.request("POST", "/execute-file", body=body, headers=h)
    resp = conn.getresponse()
    data = resp.read()
    hdrs = {k.lower(): v for k, v in resp.getheaders()}
    conn.close()
    return resp.status, hdrs, data


def _split(data):
    line, sep, rest = data.partition(b"\n")
    assert sep == b"\n", "no newline after the result line: %r" % data[:200]
    return json.loads(line.decode("utf-8")), rest


def _cg_dirs(scratch):
    return sorted(glob.glob(os.path.join(str(scratch), "cg_*")))


def _wait_empty(scratch, seconds=5.0):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if not _cg_dirs(scratch):
            return []
        time.sleep(0.05)
    return _cg_dirs(scratch)


ECHO_STATS = (
    "import sys, hashlib\n"
    "data = open(sys.argv[1], 'rb').read()\n"
    "print(len(data), hashlib.sha256(data).hexdigest())\n"
)

COPY_UPPER = (
    "import sys\n"
    "with open(sys.argv[1], 'rb') as i, open(sys.argv[2], 'wb') as o:\n"
    "    for line in i:\n"
    "        o.write(line.upper())\n"
)


# ---------------------------------------------------------------- framing

def test_streams_the_body_to_the_input_file_and_returns_stdout(runner):
    """streams the body to the input file and returns stdout"""
    import hashlib
    payload = b"id,name\n" + b"".join(b"%d,row-%d\n" % (i, i) for i in range(50000))
    status, hdrs, data = _post_file(runner, {"script": ECHO_STATS, "timeoutSec": 30,
                                             "inputName": "input.csv"}, payload)
    assert status == 200, data[:500]
    result, rest = _split(data)
    assert result["exitCode"] == 0, result
    assert result["timedOut"] is False
    assert result["stdout"].split() == [str(len(payload)), hashlib.sha256(payload).hexdigest()]
    assert rest == b""
    assert result.get("outputBytes", 0) == 0


def test_returns_the_output_file_bytes_after_the_result_line(runner):
    """returns the output file bytes after the result line"""
    payload = b"".join(b"a%d,b%d\n" % (i, i) for i in range(40000))  # > 64 KB
    status, hdrs, data = _post_file(runner, {"script": COPY_UPPER, "timeoutSec": 30,
                                             "inputName": "in.csv", "outputName": "out.csv"}, payload)
    assert status == 200, data[:500]
    result, rest = _split(data)
    assert result["exitCode"] == 0, result
    assert rest == payload.upper()
    assert result["outputBytes"] == len(payload)
    assert int(hdrs["content-length"]) == len(data)


# ---------------------------------------------------------------- env

def test_the_script_sees_only_allowlisted_environment_variables(runner, monkeypatch):
    """the script sees only allowlisted environment variables"""
    monkeypatch.setenv("VAULT_TOKEN", "hvs.should-not-leak")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-should-not-leak")
    script = "import os, json\nprint(json.dumps(dict(os.environ)))\n"
    meta = {"script": script, "timeoutSec": 30, "inputName": "in.csv",
            "env": {"HANDED_SECRET": "handed-should-not-leak"},
            "packages": ["requests"]}
    status, _, data = _post_file(runner, meta, b"x\n")
    assert status == 200, data[:500]
    result, _ = _split(data)
    assert result["exitCode"] == 0, result
    env = json.loads(result["stdout"])
    assert "VAULT_TOKEN" not in env
    assert "ANTHROPIC_API_KEY" not in env
    assert "HANDED_SECRET" not in env, "handed env must be ignored on /execute-file"
    extra = set(env) - ALLOWED_ENV - INTERPRETER_ENV
    assert extra == set(), sorted(extra)


# ---------------------------------------------------------------- timeout

def test_a_timed_out_script_returns_timedOut_and_no_output(runner, scratch):
    """a timed-out script returns timedOut and no output"""
    script = (
        "import sys, time\n"
        "open(sys.argv[2], 'wb').write(b'partial')\n"
        "time.sleep(60)\n"
    )
    t0 = time.monotonic()
    status, hdrs, data = _post_file(runner, {"script": script, "timeoutSec": 1,
                                             "inputName": "in.csv", "outputName": "out.csv"}, b"x\n")
    assert time.monotonic() - t0 < 30
    assert status == 200, data[:500]
    result, rest = _split(data)
    assert result["timedOut"] is True
    assert result.get("outputBytes", 0) == 0
    assert rest == b""


# ---------------------------------------------------------------- disk full

def test_a_write_that_hits_ENOSPC_returns_507_naming_the_codegen_scratch_volume(runner, scratch, monkeypatch):
    """a write that hits ENOSPC returns 507 naming the codegen-scratch volume"""
    real_open = open

    class _Full:
        def __init__(self, f):
            self._f = f

        def write(self, data):
            raise OSError(errno.ENOSPC, "No space left on device")

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            self._f.close()
            return False

        def __getattr__(self, name):
            return getattr(self._f, name)

    def fake_open(path, mode="r", *a, **kw):
        f = real_open(path, mode, *a, **kw)
        if os.path.basename(str(path)) == "in.csv" and any(c in mode for c in "wxa"):
            return _Full(f)
        return f

    monkeypatch.setattr(app, "open", fake_open, raising=False)
    status, _, data = _post_file(runner, {"script": ECHO_STATS, "timeoutSec": 30,
                                          "inputName": "in.csv"}, b"x" * (3 * CHUNK))
    assert status == 507, (status, data[:500])
    assert b"codegen-scratch" in data
    json.loads(data.decode("utf-8").strip().splitlines()[0])  # a JSON error, not a traceback
    assert _wait_empty(scratch) == []


# ---------------------------------------------------------------- cleanup

def test_the_run_directory_is_gone_after_success_a_non_zero_exit_a_timeout_and_a_dropped_connection(runner, scratch):
    """the run directory is gone after success, a non-zero exit, a timeout and a dropped connection"""
    # success
    status, _, data = _post_file(runner, {"script": ECHO_STATS, "timeoutSec": 30, "inputName": "in.csv"}, b"a\n")
    assert status == 200 and _split(data)[0]["exitCode"] == 0
    assert _wait_empty(scratch) == [], "run dir left after success"

    # non-zero exit
    status, _, data = _post_file(runner, {"script": "import sys\nsys.exit(3)\n", "timeoutSec": 30,
                                          "inputName": "in.csv", "outputName": "out.csv"}, b"a\n")
    assert status == 200 and _split(data)[0]["exitCode"] == 3
    assert _wait_empty(scratch) == [], "run dir left after a non-zero exit"

    # timeout
    status, _, data = _post_file(runner, {"script": "import time\ntime.sleep(60)\n", "timeoutSec": 1,
                                          "inputName": "in.csv"}, b"a\n")
    assert status == 200 and _split(data)[0]["timedOut"] is True
    assert _wait_empty(scratch) == [], "run dir left after a timeout"

    # dropped connection mid-upload: promise 1 MB, send a little, hang up
    meta = json.dumps({"script": ECHO_STATS, "timeoutSec": 30, "inputName": "in.csv"}).encode() + b"\n"
    s = socket.create_connection(("127.0.0.1", runner))
    s.sendall(b"POST /execute-file HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\n"
              b"Content-Length: %d\r\n\r\n" % (len(meta) + 1024 * 1024))
    s.sendall(meta + b"y" * 4096)
    time.sleep(0.5)
    s.close()
    assert _wait_empty(scratch, 10) == [], "run dir left after a dropped upload"

    # dropped connection while the script runs (the runner's response write fails)
    body = _body({"script": "import time, sys\ntime.sleep(1)\nopen(sys.argv[2],'wb').write(b'z'*(1<<20))\n",
                  "timeoutSec": 30, "inputName": "in.csv", "outputName": "out.csv"}, b"a\n")
    s = socket.create_connection(("127.0.0.1", runner))
    s.sendall(b"POST /execute-file HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\n"
              b"Content-Length: %d\r\n\r\n" % len(body) + body)
    s.close()
    time.sleep(1.5)
    assert _wait_empty(scratch, 10) == [], "run dir left after the client went away mid-run"


def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def test_leftover_cg_directories_are_removed_at_start(tmp_path):
    """leftover cg_ directories are removed at start"""
    scratch = tmp_path / "scratch"
    (scratch / "cg_killed1").mkdir(parents=True)
    (scratch / "cg_killed1" / "in.csv").write_bytes(b"left over")
    (scratch / "cg_killed2" / "nested").mkdir(parents=True)
    (scratch / "keep_me").mkdir()
    port = _free_port()
    env = dict(os.environ, CODEGEN_SCRATCH_DIR=str(scratch), TAP_RUNNER_PORT=str(port),
               TAP_RUNNER_TOKEN="", TAP_RUNNER_TOKEN_FILE="/nonexistent/tap-runner-token")
    proc = subprocess.Popen([sys.executable, os.path.join(APP_DIR, "app.py")], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 15
        healthy = False
        while time.monotonic() < deadline:
            try:
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
                c.request("GET", "/health")
                healthy = c.getresponse().status == 200
                c.close()
                if healthy:
                    break
            except OSError:
                time.sleep(0.1)
        assert healthy, "runner did not come up"
        assert _cg_dirs(scratch) == [], "leftover cg_ dirs survived runner start"
        assert (scratch / "keep_me").is_dir(), "only cg_* directories may be swept"
    finally:
        proc.kill()
        proc.wait()


# ---------------------------------------------------------------- streaming

def test_no_read_or_write_of_the_payload_exceeds_64_KB(scratch, monkeypatch):
    """no read or write of the payload exceeds 64 KB"""
    _CountingHandler.log = []
    file_log = []
    real_open = open

    def counting_open(path, mode="r", *a, **kw):
        f = real_open(path, mode, *a, **kw)
        if os.path.basename(str(path)) in ("in.csv", "out.csv"):
            return _Counting(f, file_log)
        return f

    monkeypatch.setattr(app, "open", counting_open, raising=False)
    srv = _serve(_CountingHandler)
    try:
        payload = b"q" * (2 * 1024 * 1024 + 17)
        status, _, data = _post_file(srv.server_address[1],
                                     {"script": "import shutil, sys\nshutil.copyfile(sys.argv[1], sys.argv[2])\n",
                                      "timeoutSec": 60, "inputName": "in.csv", "outputName": "out.csv"},
                                     payload)
    finally:
        srv.shutdown()
    assert status == 200, data[:500]
    result, rest = _split(data)
    assert rest == payload
    socket_big = [e for e in _CountingHandler.log if e[1] > CHUNK]
    assert socket_big == [], "socket read/write over 64 KB: %r" % socket_big[:5]
    file_big = [e for e in file_log if e[1] > CHUNK]
    assert file_big == [], "scratch file read/write over 64 KB: %r" % file_big[:5]
    assert any(e[0] == "write" for e in file_log), "input file not written through app's open()"
    assert any(e[0] == "read" for e in file_log), "output file not read through app's open()"


# ---------------------------------------------------------------- names

def test_input_and_output_names_are_reduced_to_a_basename(runner, scratch, tmp_path):
    """input and output names are reduced to a basename"""
    script = (
        "import os, sys, json\n"
        "open(sys.argv[2], 'wb').write(open(sys.argv[1], 'rb').read())\n"
        "print(json.dumps([os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])]))\n"
    )
    meta = {"script": script, "timeoutSec": 30,
            "inputName": "../../escaped-in.csv", "outputName": "../escaped-out.csv"}
    status, _, data = _post_file(runner, meta, b"hello\n")
    assert status == 200, data[:500]
    result, rest = _split(data)
    assert result["exitCode"] == 0, result
    assert rest == b"hello\n"
    inp, out = json.loads(result["stdout"])
    assert os.path.basename(inp) == "escaped-in.csv"
    assert os.path.basename(out) == "escaped-out.csv"
    run_dir = os.path.realpath(os.path.dirname(inp))
    assert os.path.realpath(os.path.dirname(out)) == run_dir
    assert os.path.basename(run_dir).startswith("cg_")
    assert os.path.dirname(run_dir) == os.path.realpath(str(scratch))
    assert not glob.glob(os.path.join(str(tmp_path), "**", "escaped-*"), recursive=True)


# ---------------------------------------------------------------- auth / legacy

def test_execute_file_without_the_token_is_401(runner, monkeypatch):
    """/execute-file without the token is 401"""
    monkeypatch.setattr(app, "TOKEN", "s3cret-runner-token")
    status, _, data = _post_file(runner, {"script": ECHO_STATS, "timeoutSec": 30, "inputName": "in.csv"}, b"a\n")
    assert status == 401, (status, data[:200])
    status, _, data = _post_file(runner, {"script": ECHO_STATS, "timeoutSec": 30, "inputName": "in.csv"}, b"a\n",
                                 headers={"Authorization": "Bearer wrong"})
    assert status == 401
    status, _, data = _post_file(runner, {"script": ECHO_STATS, "timeoutSec": 30, "inputName": "in.csv"}, b"a\n",
                                 headers={"Authorization": "Bearer s3cret-runner-token"})
    assert status == 200, data[:500]


def test_execute_still_behaves_as_before(runner):
    """/execute still behaves as before"""
    wrapper = "import sys, os\nexec(open(sys.argv[1]).read())\nprint('X=' + os.environ.get('X', ''))\n"
    body = {"script": "print('hi')\n", "wrapper": wrapper, "env": {"X": "handed"}, "timeoutSec": 30}
    conn = http.client.HTTPConnection("127.0.0.1", runner, timeout=30)
    conn.request("POST", "/execute", body=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    raw = resp.read()
    ctype = resp.getheader("Content-Type")
    conn.close()
    assert resp.status == 200
    assert ctype == "application/json"
    obj = json.loads(raw)
    assert obj["exitCode"] == 0 and obj["timedOut"] is False
    assert obj["stdout"].split() == ["hi", "X=handed"]
