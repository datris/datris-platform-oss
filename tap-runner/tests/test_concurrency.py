"""Story: Runner overload protection (plans/stories/runner-overload-protection.md),
the runner half.

The runner accepts at most TAP_RUNNER_MAX_CONCURRENT scripts at once (one
BoundedSemaphore shared by /execute and /execute-file, acquired after the token
check and before any body read) and answers the rest with

    503 {"error": "runner busy", "retryAfterSec": N, "active": k, "maxConcurrent": M}
    Retry-After: N            (N = TAP_RUNNER_RETRY_AFTER_SEC, default 5)

without spawning anything. /health adds `active` and `maxConcurrent`. Every
script is started through tap-runner/rlimit_exec.py, which lowers RLIMIT_AS
to TAP_RUNNER_SCRIPT_MEMORY_MB MB (0 = unlimited) and RLIMIT_NPROC to
TAP_RUNNER_SCRIPT_MAX_PROCS, then execs the real command.

The runner is exercised as a real process (`python3 app.py`) on a free loopback
port, so the env knobs are read exactly as in the container. Shim calling
convention pinned here: `python3 rlimit_exec.py <program> [args...]`, the shim
then `os.execv(<program>, [<program>, args...])`.

On macOS RLIMIT_AS cannot be lowered (setrlimit raises); the shim must tolerate
that and the RLIMIT_AS assertions are skipped there, as in
test_wrapper_memory.py. Run with `python3 -m pytest tap-runner/tests -q`.
"""
import http.client
import json
import os
import re
import resource
import socket
import subprocess
import sys
import threading
import time

import pytest

APP_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ROOT = os.path.abspath(os.path.join(APP_DIR, ".."))
APP = os.path.join(APP_DIR, "app.py")
SHIM = os.path.join(APP_DIR, "rlimit_exec.py")
TOKEN = "spec-concurrency-token-0123456789"
MB = 1024 * 1024

SLEEP_TAP = {
    "script": "import time\ndef fetch():\n    time.sleep(2)\n    return []\n",
    "wrapper": "import script\nscript.fetch()\n",
    "timeoutSec": 30,
}
SLEEP_FILE_SCRIPT = "import time\ntime.sleep(2)\n"

LIMITS_PROBE = (
    "import json, resource\n"
    "print(json.dumps({'as': resource.getrlimit(resource.RLIMIT_AS)[0],"
    " 'nproc': resource.getrlimit(resource.RLIMIT_NPROC)[0]}))\n"
)

BUSY_BODY = {"error": "runner busy", "retryAfterSec": 5, "active": 2, "maxConcurrent": 2}


# ---------------------------------------------------------------- harness

def _free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Runner:
    def __init__(self, port, proc):
        self.port = port
        self.proc = proc

    def conn(self, timeout=30):
        return http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)

    def health(self, timeout=5):
        c = self.conn(timeout)
        try:
            c.request("GET", "/health")
            r = c.getresponse()
            return r.status, json.loads(r.read().decode("utf-8") or "{}")
        finally:
            c.close()

    def post(self, path, body, token=TOKEN, timeout=60):
        c = self.conn(timeout)
        try:
            h = {"Content-Length": str(len(body))}
            if token is not None:
                h["Authorization"] = "Bearer " + token
            c.request("POST", path, body=body, headers=h)
            r = c.getresponse()
            data = r.read()
            return r.status, {k.lower(): v for k, v in r.getheaders()}, data
        finally:
            c.close()

    def execute(self, body, **kw):
        return self.post("/execute", json.dumps(body).encode("utf-8"), **kw)

    def execute_file(self, script, payload=b"x\n", **kw):
        meta = {"script": script, "timeoutSec": 30, "inputName": "in.csv"}
        return self.post("/execute-file", json.dumps(meta).encode("utf-8") + b"\n" + payload, **kw)

    def children(self):
        """Direct child processes of the runner (each running script is one)."""
        out = subprocess.run(["ps", "-A", "-o", "pid=", "-o", "ppid="], capture_output=True, text=True).stdout
        n = 0
        for line in out.splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1] == str(self.proc.pid):
                n += 1
        return n


def _start_runner(tmp_path, **env_overrides):
    port = _free_port()
    scratch = tmp_path / "scratch"
    scratch.mkdir(exist_ok=True)
    env = {k: v for k, v in os.environ.items() if not k.startswith(("TAP_RUNNER", "CODEGEN_"))}
    env.update({
        "TAP_RUNNER_PORT": str(port),
        "TAP_RUNNER_TOKEN": TOKEN,
        "TAP_RUNNER_TOKEN_FILE": "/nonexistent/tap-runner-token",
        "CODEGEN_SCRATCH_DIR": str(scratch),
    })
    env.update({k: str(v) for k, v in env_overrides.items()})
    proc = subprocess.Popen([sys.executable, APP], cwd=APP_DIR, env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    r = Runner(port, proc)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise AssertionError("runner exited at start with code %s" % proc.returncode)
        try:
            if r.health(timeout=1)[0] == 200:
                return r
        except OSError:
            pass
        time.sleep(0.1)
    proc.kill()
    raise AssertionError("runner did not answer /health within 15 s")


@pytest.fixture
def runner2(tmp_path):
    """A runner capped at two concurrent scripts."""
    r = _start_runner(tmp_path, TAP_RUNNER_MAX_CONCURRENT=2)
    try:
        yield r
    finally:
        r.proc.kill()
        r.proc.wait()


class _Monitor:
    """Polls /health and the runner's child count while a burst is in flight."""

    def __init__(self, runner):
        self.runner = runner
        self.active = []
        self.children = []
        self.health_status = []
        self.health_latency = []
        self._stop = threading.Event()
        self._t = threading.Thread(target=self._loop, daemon=True)

    def _loop(self):
        while not self._stop.is_set():
            t0 = time.monotonic()
            try:
                status, body = self.runner.health(timeout=2)
                self.health_status.append(status)
                self.health_latency.append(time.monotonic() - t0)
                self.active.append(body.get("active"))
            except Exception as e:  # noqa: BLE001 - recorded as a failed health call
                self.health_status.append(repr(e))
            self.children.append(self.runner.children())
            time.sleep(0.05)

    def __enter__(self):
        self._t.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        self._t.join(5)
        return False


def _burst(fn, n):
    """Fire n calls of fn at the same instant; returns their results in order."""
    results = [None] * n
    gate = threading.Barrier(n)

    def one(i):
        gate.wait()
        try:
            results[i] = fn()
        except Exception as e:  # noqa: BLE001
            results[i] = e

    ts = [threading.Thread(target=one, args=(i,)) for i in range(n)]
    for t in ts:
        t.start()
    for t in ts:
        t.join(60)
    return results


def _assert_busy(status, hdrs, data):
    assert status == 503, (status, data[:300])
    assert json.loads(data.decode("utf-8")) == BUSY_BODY, data
    assert hdrs.get("retry-after") == "5", hdrs


# ------------------------------------------------ Acceptance bullet 1: /execute

def test_execute_burst_over_the_cap_gets_one_503_and_never_a_third_child(runner2):
    with _Monitor(runner2) as mon:
        results = _burst(lambda: runner2.execute(SLEEP_TAP), 3)
    for r in results:
        assert not isinstance(r, Exception), r
    statuses = sorted(r[0] for r in results)
    assert statuses == [200, 200, 503], [(r[0], r[2][:200]) for r in results]
    busy = [r for r in results if r[0] == 503][0]
    _assert_busy(*busy)
    for r in results:
        if r[0] == 200:
            assert json.loads(r[2].decode("utf-8"))["exitCode"] == 0, r[2][:300]

    seen = [a for a in mon.active if a is not None]
    assert seen, "/health must report `active` (got no value during the burst)"
    assert max(seen) <= 2, "active in /health exceeded the cap: %s" % seen
    assert max(seen) == 2, "the two sleepers must show as active: %s" % seen
    assert max(mon.children) <= 2, "a third script process was spawned: %s" % mon.children

    # Slots are released once the sleepers finish.
    status, _, data = runner2.execute(SLEEP_TAP)
    assert status == 200, data[:300]
    assert runner2.health()[1].get("active") == 0


# ------------------------------------------- Acceptance bullet 1: /execute-file

def test_execute_file_burst_over_the_cap_gets_one_503_and_releases(runner2):
    with _Monitor(runner2) as mon:
        results = _burst(lambda: runner2.execute_file(SLEEP_FILE_SCRIPT), 3)
    for r in results:
        assert not isinstance(r, Exception), r
    assert sorted(r[0] for r in results) == [200, 200, 503], [(r[0], r[2][:200]) for r in results]
    _assert_busy(*[r for r in results if r[0] == 503][0])
    seen = [a for a in mon.active if a is not None]
    assert seen and max(seen) <= 2, seen
    assert max(mon.children) <= 2, mon.children

    status, _, data = runner2.execute_file(SLEEP_FILE_SCRIPT)
    assert status == 200, data[:300]


def test_execute_file_503_arrives_before_the_body_is_read(runner2):
    # Fill both slots, then send /execute-file headers announcing a 64 MB body
    # and only the metadata line. A runner that read the body before deciding
    # would sit waiting for the rest; the busy answer must come at once.
    fillers = [threading.Thread(target=runner2.execute, args=(SLEEP_TAP,)) for _ in range(2)]
    for t in fillers:
        t.start()
    try:
        time.sleep(0.6)
        meta = json.dumps({"script": SLEEP_FILE_SCRIPT, "timeoutSec": 30, "inputName": "in.csv"}).encode() + b"\n"
        s = socket.create_connection(("127.0.0.1", runner2.port), timeout=5)
        try:
            s.sendall(
                b"POST /execute-file HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + TOKEN.encode() +
                b"\r\nContent-Type: application/octet-stream\r\nContent-Length: %d\r\n\r\n" % (len(meta) + 64 * MB) + meta
            )
            buf = b""
            t0 = time.monotonic()
            try:
                while b"\r\n\r\n" not in buf:
                    chunk = s.recv(65536)
                    if not chunk:
                        break
                    buf += chunk
            except socket.timeout:
                pytest.fail("no response within 5 s: the runner is reading the body before answering busy")
            assert time.monotonic() - t0 < 5
            head, _, rest = buf.partition(b"\r\n\r\n")
            assert head.startswith(b"HTTP/1.1 503") or head.startswith(b"HTTP/1.0 503"), head[:200]
            assert re.search(rb"(?im)^retry-after:\s*5\s*$", head), head
        finally:
            s.close()
    finally:
        for t in fillers:
            t.join(30)


def test_health_is_answered_while_all_slots_are_full(runner2):
    fillers = [threading.Thread(target=runner2.execute, args=(SLEEP_TAP,)) for _ in range(2)]
    for t in fillers:
        t.start()
    try:
        time.sleep(0.6)
        t0 = time.monotonic()
        status, body = runner2.health(timeout=2)
        assert time.monotonic() - t0 < 2
        assert status == 200
        assert body.get("ok") is True
        assert body.get("active") == 2, body
        assert body.get("maxConcurrent") == 2, body
    finally:
        for t in fillers:
            t.join(30)


def test_health_reports_counters_when_idle(runner2):
    status, body = runner2.health()
    assert status == 200
    assert body == {"ok": True, "active": 0, "maxConcurrent": 2}, body


def test_default_cap_is_four(tmp_path):
    r = _start_runner(tmp_path)
    try:
        assert r.health()[1].get("maxConcurrent") == 4
    finally:
        r.proc.kill()
        r.proc.wait()


def test_401_400_and_failed_requests_never_consume_a_slot(runner2):
    # More rejected requests than there are slots: a leaked slot would show.
    for _ in range(3):
        status, _, _ = runner2.execute(SLEEP_TAP, token="wrong-token")
        assert status == 401
        status, _, _ = runner2.execute_file(SLEEP_FILE_SCRIPT, token="wrong-token")
        assert status == 401
        status, _, data = runner2.post("/execute-file", b"not json at all\nrest")
        assert status == 400, data[:200]
        status, _, data = runner2.post("/execute", b"{broken json")
        assert status >= 400, data[:200]
    assert runner2.health()[1].get("active") == 0
    results = _burst(lambda: runner2.execute(SLEEP_TAP), 2)
    assert [r[0] for r in results] == [200, 200], [(r[0], r[2][:200]) for r in results]


# ------------------------------------------------------ Acceptance bullet 2: shim

def _run_shim(tmp_path, env_overrides, script=LIMITS_PROBE):
    probe = tmp_path / "probe.py"
    probe.write_text(script, encoding="utf-8")
    env = {k: v for k, v in os.environ.items() if not k.startswith("TAP_RUNNER")}
    env.update({k: str(v) for k, v in env_overrides.items()})
    assert os.path.isfile(SHIM), "tap-runner/rlimit_exec.py is missing"
    return subprocess.run([sys.executable, SHIM, sys.executable, str(probe)],
                          capture_output=True, text=True, env=env, timeout=60)


def test_shim_lowers_nproc_and_address_space_in_the_child(tmp_path):
    p = _run_shim(tmp_path, {"TAP_RUNNER_SCRIPT_MEMORY_MB": 300, "TAP_RUNNER_SCRIPT_MAX_PROCS": 37})
    assert p.returncode == 0, p.stderr
    got = json.loads(p.stdout.strip().splitlines()[-1])
    assert got["nproc"] == 37, got
    if sys.platform != "darwin":
        assert got["as"] == 300 * MB, got


def test_shim_zero_memory_leaves_address_space_unlimited(tmp_path):
    p = _run_shim(tmp_path, {"TAP_RUNNER_SCRIPT_MEMORY_MB": 0, "TAP_RUNNER_SCRIPT_MAX_PROCS": 37})
    assert p.returncode == 0, p.stderr
    got = json.loads(p.stdout.strip().splitlines()[-1])
    assert got["as"] == resource.RLIM_INFINITY, got
    assert got["nproc"] == 37, got


@pytest.mark.skipif(sys.platform == "darwin", reason="RLIMIT_AS is not enforced on macOS")
def test_shim_default_address_space_is_1024_mb(tmp_path):
    p = _run_shim(tmp_path, {})
    assert p.returncode == 0, p.stderr
    got = json.loads(p.stdout.strip().splitlines()[-1])
    assert got["as"] == 1024 * MB, got
    assert got["nproc"] == 64, got


@pytest.fixture
def limited_runner(tmp_path):
    r = _start_runner(tmp_path, TAP_RUNNER_SCRIPT_MEMORY_MB=300, TAP_RUNNER_SCRIPT_MAX_PROCS=37)
    try:
        yield r
    finally:
        r.proc.kill()
        r.proc.wait()


def _check_limits(stdout):
    got = json.loads(stdout.strip().splitlines()[-1])
    assert got["nproc"] == 37, "script was not started through the rlimit shim: %s" % got
    if sys.platform != "darwin":
        assert got["as"] == 300 * MB, got


def _stream_trailer(data):
    _, sep, trailer = data.partition(b"\x1e")
    assert sep, "no trailer in streamed response: %r" % data[:300]
    return json.loads(trailer.decode("utf-8"))


def test_every_runner_lane_starts_scripts_through_the_shim(limited_runner):
    tap = {"script": "def fetch():\n    return []\n", "wrapper": LIMITS_PROBE, "timeoutSec": 30}

    # /execute, non-streaming (subprocess.run)
    status, _, data = limited_runner.execute(tap)
    assert status == 200, data[:300]
    res = json.loads(data.decode("utf-8"))
    assert res["exitCode"] == 0, res
    _check_limits(res["stdout"])

    # /execute, streaming (_run_streaming's Popen)
    status, _, data = limited_runner.execute(dict(tap, recordsStream=True))
    assert status == 200, data[:300]
    trailer = _stream_trailer(data)
    assert trailer["exitCode"] == 0, trailer
    _check_limits(trailer["stdout"])

    # /execute-file (_run_codegen's Popen)
    status, _, data = limited_runner.execute_file(LIMITS_PROBE)
    assert status == 200, data[:300]
    res = json.loads(data.split(b"\n", 1)[0].decode("utf-8"))
    assert res["exitCode"] == 0, res
    _check_limits(res["stdout"])


@pytest.mark.skipif(sys.platform == "darwin", reason="RLIMIT_AS is not enforced on macOS")
def test_a_script_over_the_memory_limit_dies_with_memoryerror_not_a_timeout(tmp_path):
    r = _start_runner(tmp_path, TAP_RUNNER_SCRIPT_MEMORY_MB=256)
    try:
        tap = {
            "script": "def fetch():\n    b = bytearray(2 * 1024 * 1024 * 1024)\n    return [len(b)]\n",
            "wrapper": "import script\nscript.fetch()\n",
            "timeoutSec": 60,
            "recordsStream": True,
        }
        status, _, data = r.execute(tap)
        assert status == 200, data[:300]
        trailer = _stream_trailer(data)
        assert trailer["exitCode"] != 0, trailer
        assert trailer["timedOut"] is False, trailer
        assert "MemoryError" in trailer["stderr"], trailer["stderr"][-500:]
    finally:
        r.proc.kill()
        r.proc.wait()


# --------------------------------- Acceptance bullet 7 (static half): compose

def _service_block(text, name):
    m = re.search(r"^  %s:\n((?:    .*\n|\s*\n)+)" % re.escape(name), text, re.M)
    assert m, "service %s not found" % name
    return m.group(1)


LIMITS = {
    "datris-tap-runner": [
        r'cpus:\s*"\$\{TAP_RUNNER_CPUS:-2\}"',
        r'mem_limit:\s*"\$\{TAP_RUNNER_MEMORY:-2g\}"',
        r"pids_limit:\s*\$\{TAP_RUNNER_PIDS:-256\}",
        r'TAP_RUNNER_MAX_CONCURRENT:\s*"\$\{TAP_RUNNER_MAX_CONCURRENT:-4\}"',
        r'TAP_RUNNER_SCRIPT_MEMORY_MB:\s*"\$\{TAP_RUNNER_SCRIPT_MEMORY_MB:-1024\}"',
    ],
    "datris-codegen-runner": [
        r'cpus:\s*"\$\{CODEGEN_RUNNER_CPUS:-1\}"',
        r'mem_limit:\s*"\$\{CODEGEN_RUNNER_MEMORY:-1g\}"',
        r"pids_limit:\s*\$\{CODEGEN_RUNNER_PIDS:-128\}",
        r'TAP_RUNNER_MAX_CONCURRENT:\s*"\$\{CODEGEN_RUNNER_MAX_CONCURRENT:-4\}"',
        r'TAP_RUNNER_SCRIPT_MEMORY_MB:\s*"\$\{CODEGEN_RUNNER_SCRIPT_MEMORY_MB:-1024\}"',
    ],
}


@pytest.mark.parametrize("compose", ["docker-compose.yml", "docker-compose.standalone.yml"])
@pytest.mark.parametrize("service", sorted(LIMITS))
def test_runner_services_carry_resource_limits_and_concurrency(compose, service):
    with open(os.path.join(ROOT, compose)) as f:
        block = _service_block(f.read(), service)
    for pattern in LIMITS[service]:
        assert re.search(pattern, block), "%s in %s lacks %s" % (service, compose, pattern)


@pytest.mark.parametrize("compose", ["docker-compose.yml", "docker-compose.standalone.yml"])
def test_queue_timeout_is_forwarded_to_the_server(compose):
    with open(os.path.join(ROOT, compose)) as f:
        block = _service_block(f.read(), "datris")
    assert "TAP_RUNNER_QUEUE_TIMEOUT_SECONDS" in block, "datris must receive TAP_RUNNER_QUEUE_TIMEOUT_SECONDS"
