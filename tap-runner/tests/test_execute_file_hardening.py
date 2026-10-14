"""/execute-file hardening found in E2E (codegen-script-isolation):

* ENOSPC reported by the final flush / close of the buffered input file (not by a
  write) still answers 507 promptly over a real socket; the body is not re-drained.
* Nothing a run writes outside its run dir survives into the next run: on a
  dedicated scratch volume (CODEGEN_SCRATCH_DEDICATED=1) everything under
  CODEGEN_SCRATCH_DIR is removed after each run and at start, and CODEGEN_TMP_DIR
  (the runner's /tmp) is emptied after each run and at start.
"""
import errno
import http.client
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


@pytest.fixture
def runner():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield srv.server_address[1]
    finally:
        srv.shutdown()


def _raw_post(port, meta, payload, timeout=20):
    """POST over a plain socket; returns (status, body) or raises socket.timeout."""
    body = json.dumps(meta).encode() + b"\n" + payload
    s = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    try:
        s.sendall(b"POST /execute-file HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\n"
                  b"Content-Length: %d\r\n\r\n" % len(body))
        s.sendall(body)
        data = b""
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            data += chunk
    finally:
        s.close()
    head, _, rest = data.partition(b"\r\n\r\n")
    status = int(head.split(b" ")[1]) if head else 0
    return status, rest


def _wait_listing(path, expected, seconds=5.0):
    """The handler cleans up after the response is sent: poll briefly."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        got = sorted(os.listdir(path))
        if got == sorted(expected):
            return got
        time.sleep(0.05)
    return sorted(os.listdir(path))


def _post(port, meta, payload):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
    body = json.dumps(meta).encode() + b"\n" + payload
    conn.request("POST", "/execute-file", body=body,
                 headers={"Content-Type": "application/octet-stream", "Content-Length": str(len(body))})
    resp = conn.getresponse()
    data = resp.read()
    conn.close()
    line, _, rest = data.partition(b"\n")
    return resp.status, json.loads(line), rest


class _FullOnClose:
    """A file whose writes are buffered fine but whose flush and/or close hit ENOSPC."""

    def __init__(self, f, fail_flush):
        self._f = f
        self._fail_flush = fail_flush

    def write(self, data):
        return len(data)

    def flush(self):
        if self._fail_flush:
            raise OSError(errno.ENOSPC, "No space left on device")

    def close(self):
        self._f.close()
        raise OSError(errno.ENOSPC, "No space left on device")

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


@pytest.mark.parametrize("fail_flush", [False, True])
def test_enospc_on_flush_or_close_answers_507_promptly(runner, tmp_path, monkeypatch, fail_flush):
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    monkeypatch.setenv("CODEGEN_SCRATCH_DIR", str(scratch))
    real_open = open

    def fake_open(path, mode="r", *a, **kw):
        f = real_open(path, mode, *a, **kw)
        if os.path.basename(str(path)) == "in.csv" and any(c in mode for c in "wxa"):
            return _FullOnClose(f, fail_flush)
        return f

    monkeypatch.setattr(app, "open", fake_open, raising=False)
    t0 = time.monotonic()
    status, body = _raw_post(runner, {"script": "print(1)\n", "timeoutSec": 30, "inputName": "in.csv"},
                             b"z" * (5 * 1024 * 1024))
    assert time.monotonic() - t0 < 10, "the 507 must not wait for a socket timeout"
    assert status == 507, (status, body[:300])
    assert b"codegen-scratch" in body
    assert _wait_listing(scratch, []) == []


@pytest.fixture
def dedicated(tmp_path, monkeypatch):
    scratch = tmp_path / "scratch"
    tmpdir = tmp_path / "runner-tmp"
    scratch.mkdir()
    tmpdir.mkdir()
    monkeypatch.setenv("CODEGEN_SCRATCH_DIR", str(scratch))
    monkeypatch.setenv("CODEGEN_SCRATCH_DEDICATED", "1")
    monkeypatch.setenv("CODEGEN_TMP_DIR", str(tmpdir))
    return scratch, tmpdir


def test_files_written_outside_the_run_dir_do_not_survive_into_the_next_run(runner, dedicated):
    scratch, tmpdir = dedicated
    writer = (
        "import os\n"
        "open('../escape.txt', 'w').write('left behind')\n"
        "os.makedirs('../escape_dir/inner', exist_ok=True)\n"
        "open('../escape_dir/inner/f', 'w').write('x')\n"
        "os.chmod('../escape_dir/inner', 0)\n"
        "open(%r, 'w').write('in tmp')\n" % os.path.join(str(tmpdir), "x")
    )
    status, result, _ = _post(runner, {"script": writer, "timeoutSec": 30, "inputName": "in.csv"}, b"a\n")
    assert status == 200 and result["exitCode"] == 0, result
    assert _wait_listing(scratch, []) == []
    assert _wait_listing(tmpdir, []) == []

    reader = (
        "import os, json\n"
        "print(json.dumps([sorted(n for n in os.listdir('..') if not os.path.abspath(n).startswith(os.getcwd())), "
        "sorted(os.listdir(%r))]))\n" % str(tmpdir)
    )
    status, result, _ = _post(runner, {"script": reader, "timeoutSec": 30, "inputName": "in.csv"}, b"a\n")
    assert status == 200 and result["exitCode"] == 0, result
    siblings, tmp_entries = json.loads(result["stdout"])
    assert [n for n in siblings if not n.startswith("cg_")] == [], siblings
    assert tmp_entries == []
    assert _wait_listing(scratch, []) == []


def test_without_a_dedicated_volume_only_run_dirs_are_touched(runner, tmp_path, monkeypatch):
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    (scratch / "keep_me.txt").write_text("not ours")
    monkeypatch.setenv("CODEGEN_SCRATCH_DIR", str(scratch))
    monkeypatch.delenv("CODEGEN_SCRATCH_DEDICATED", raising=False)
    status, result, _ = _post(runner, {"script": "print(1)\n", "timeoutSec": 30, "inputName": "in.csv"}, b"a\n")
    assert status == 200 and result["exitCode"] == 0
    assert _wait_listing(scratch, ["keep_me.txt"]) == ["keep_me.txt"]


def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def test_a_dedicated_volume_and_the_tmp_dir_are_emptied_at_start(tmp_path):
    scratch = tmp_path / "scratch"
    tmpdir = tmp_path / "runner-tmp"
    (scratch / "cg_killed").mkdir(parents=True)
    (scratch / "escape.txt").write_text("left by a script")
    (scratch / "escape_dir" / "inner").mkdir(parents=True)
    os.chmod(scratch / "escape_dir" / "inner", 0)
    # depth 2 below a top-level entry, locked: must still be removed
    (scratch / "escape2" / "a" / "b" / "c").mkdir(parents=True)
    (scratch / "escape2" / "a" / "b" / "c" / "leaf").write_text("x")
    os.chmod(scratch / "escape2" / "a" / "b", 0)
    (scratch / "escape3" / "a" / "b" / "c").mkdir(parents=True)
    os.chmod(scratch / "escape3" / "a" / "b", 0o500)
    tmpdir.mkdir()
    (tmpdir / "x").write_text("left in tmp")
    port = _free_port()
    env = dict(os.environ, CODEGEN_SCRATCH_DIR=str(scratch), CODEGEN_SCRATCH_DEDICATED="1",
               CODEGEN_TMP_DIR=str(tmpdir), TAP_RUNNER_PORT=str(port),
               TAP_RUNNER_TOKEN="", TAP_RUNNER_TOKEN_FILE="/nonexistent/tap-runner-token")
    proc = subprocess.Popen([sys.executable, os.path.join(APP_DIR, "app.py")], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 15
        healthy = False
        while time.monotonic() < deadline and not healthy:
            try:
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
                c.request("GET", "/health")
                healthy = c.getresponse().status == 200
                c.close()
            except OSError:
                time.sleep(0.1)
        assert healthy, "runner did not come up"
        assert os.listdir(scratch) == []
        assert os.listdir(tmpdir) == []
    finally:
        proc.kill()
        proc.wait()


# ---------------------------------------------------------------- deep trees / stray children
# Review round 3: a tree deeper than the recursion limit (and, on Linux, deeper than
# PATH_MAX via chdir) must neither fail the run nor survive it, nor crash the start-up sweep.

DEEP = 1500

DEEP_SCRIPT = (
    "import os\n"
    "base = os.getcwd()\n"
    "for start in ('.', '..'):\n"
    "    os.chdir(base)\n"
    "    os.chdir(start)\n"
    "    os.mkdir('deep_' + str(len(start)))\n"
    "    os.chdir('deep_' + str(len(start)))\n"
    "    for i in range(%d):\n"
    "        os.mkdir('a')\n"
    "        os.chdir('a')\n"
    "    open('leaf', 'w').write('x')\n"
    "os.chdir(base)\n"
    "for start in ('.', '..'):\n"
    "    d = os.path.join(start, 'locked_' + str(len(start)))\n"
    "    os.makedirs(os.path.join(d, 'a', 'b', 'c'))\n"
    "    open(os.path.join(d, 'a', 'b', 'c', 'leaf'), 'w').write('x')\n"
    "    os.chmod(os.path.join(d, 'a', 'b'), 0o500)\n"
    "    os.makedirs(os.path.join(d, 'z', 'y', 'x'))\n"
    "    os.chmod(os.path.join(d, 'z', 'y'), 0)\n"
    "print('ok')\n" % DEEP
)


def test_a_tree_deeper_than_the_recursion_limit_is_removed_and_the_run_succeeds(runner, dedicated):
    scratch, tmpdir = dedicated
    status, result, _ = _post(runner, {"script": DEEP_SCRIPT, "timeoutSec": 60, "inputName": "in.csv"}, b"a\n")
    assert status == 200, result
    assert result["exitCode"] == 0, result
    assert _wait_listing(scratch, [], 15) == []
    # The runner still works afterwards.
    status, result, _ = _post(runner, {"script": "print(2)\n", "timeoutSec": 30, "inputName": "in.csv"}, b"a\n")
    assert status == 200 and result["stdout"].strip() == "2"


def _make_deep(root, depth):
    cwd = os.getcwd()
    try:
        os.chdir(root)
        for _ in range(depth):
            os.mkdir("a")
            os.chdir("a")
        open("leaf", "w").write("x")
    finally:
        os.chdir(cwd)


def test_the_start_up_sweep_survives_a_deep_tree(tmp_path):
    scratch = tmp_path / "scratch"
    (scratch / "cg_deep").mkdir(parents=True)
    (scratch / "loose_deep").mkdir()
    _make_deep(str(scratch / "cg_deep"), DEEP)
    _make_deep(str(scratch / "loose_deep"), DEEP)
    port = _free_port()
    env = dict(os.environ, CODEGEN_SCRATCH_DIR=str(scratch), CODEGEN_SCRATCH_DEDICATED="1",
               TAP_RUNNER_PORT=str(port), TAP_RUNNER_TOKEN="",
               TAP_RUNNER_TOKEN_FILE="/nonexistent/tap-runner-token")
    env.pop("CODEGEN_TMP_DIR", None)
    proc = subprocess.Popen([sys.executable, os.path.join(APP_DIR, "app.py")], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 30
        healthy = False
        while time.monotonic() < deadline and not healthy:
            if proc.poll() is not None:
                break
            try:
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
                c.request("GET", "/health")
                healthy = c.getresponse().status == 200
                c.close()
            except OSError:
                time.sleep(0.1)
        assert healthy, "runner did not come up: " + (proc.stdout.read().decode()[-2000:] if proc.poll() is not None else "")
        assert os.listdir(scratch) == []
    finally:
        proc.kill()
        proc.wait()


def test_a_child_the_script_leaves_running_is_killed_with_it(runner, dedicated):
    scratch, _ = dedicated
    late = os.path.join(str(scratch), "late.txt")
    child = "import time; time.sleep(1.5); open(%r, 'w').write('x')" % late
    script = (
        "import subprocess, sys\n"
        "subprocess.Popen([sys.executable, '-c', %r],\n"
        "                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)\n"
        "print('spawned')\n" % child
    )
    status, result, _ = _post(runner, {"script": script, "timeoutSec": 30, "inputName": "in.csv"}, b"a\n")
    assert status == 200 and result["exitCode"] == 0, result
    assert result["timedOut"] is False
    time.sleep(2.5)
    assert not os.path.exists(late), "the stray child kept running after the run"


def test_enospc_creating_the_input_file_answers_507_promptly(runner, tmp_path, monkeypatch):
    """No free inode: open() of the input file itself fails with ENOSPC. The body is
    drained once and the client gets a prompt 507, with nothing left in scratch."""
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    monkeypatch.setenv("CODEGEN_SCRATCH_DIR", str(scratch))
    real_open = open

    def fake_open(path, mode="r", *a, **kw):
        if os.path.basename(str(path)) == "in.csv" and any(c in mode for c in "wxa"):
            raise OSError(errno.ENOSPC, "No space left on device")
        return real_open(path, mode, *a, **kw)

    monkeypatch.setattr(app, "open", fake_open, raising=False)
    t0 = time.monotonic()
    status, body = _raw_post(runner, {"script": "print(1)\n", "timeoutSec": 30, "inputName": "in.csv"},
                             b"z" * (5 * 1024 * 1024))
    assert time.monotonic() - t0 < 10, "the 507 must not wait for a socket timeout"
    assert status == 507, (status, body[:300])
    assert b"codegen-scratch" in body
    assert _wait_listing(scratch, []) == []
