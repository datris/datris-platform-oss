#!/usr/bin/env python3
"""
datris-tap-runner — isolated execution sidecar for tap fetch() code.

Phase 3 / Increment 1 of tap-execution-isolation. This process runs in a container
that holds NO platform secrets (no Vault address/token, no /datris/.env, no /config)
and sits on a network with no route to the secret-bearing services. The server POSTs
a single tap run to /execute; this runner executes it as a fresh subprocess in a
per-run scratch dir and returns stdout/stderr/exitCode. Nothing here can read platform
secrets off disk or reach Vault, because they simply are not present in this container.
"""
import errno
import json
import os
import select
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("TAP_RUNNER_PORT", "8090"))
def _token():
    env = os.environ.get("TAP_RUNNER_TOKEN", "")
    weak = {"", "changeme-tap-runner-token", "change-me-to-a-long-random-string"}
    if env not in weak:
        return env
    path = os.environ.get("TAP_RUNNER_TOKEN_FILE", "/tap-runner-token/token")
    try:
        with open(path) as f:
            minted = f.read().strip()
        if minted:
            return minted
    except OSError:
        pass
    return env

TOKEN = _token()

# Benign system env vars the Python runtime / TLS may need. Mirror of the server's
# NonSecretEnvVars. The tap subprocess gets exactly these (from the runner's own env)
# plus the per-run vars handed in the request — nothing else.
ALLOWLIST = {
    "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "OLDPWD", "LANG", "TERM",
    "TZ", "TMPDIR", "TMP", "TEMP", "HOSTNAME", "PYTHONPATH", "PYTHONHOME",
    "PYTHONUNBUFFERED", "VIRTUAL_ENV", "LD_LIBRARY_PATH", "SSL_CERT_FILE",
    "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE",
}


def _base_env(scratch):
    """Allowlist-only env, with HOME/cache pointed at writable scratch (root FS is read-only)."""
    env = {k: v for k, v in os.environ.items() if k in ALLOWLIST}
    env["HOME"] = scratch
    env["TMPDIR"] = scratch
    env["PIP_NO_CACHE_DIR"] = "1"
    return env


def _install_packages(packages, scratch):
    """Install tap-declared packages into a throwaway --system-site-packages venv.
    No --break-system-packages (a venv is not externally-managed). venv-create + pip
    run with an allowlist-only env so a package's setup.py sees no handed secrets.
    Returns the venv interpreter path, or None when no packages are declared."""
    if not packages:
        return None
    build_env = _base_env(scratch)
    venv = os.path.join(scratch, "venv")
    r = subprocess.run([sys.executable, "-m", "venv", "--system-site-packages", venv],
                       capture_output=True, text=True, env=build_env)
    if r.returncode != 0:
        raise RuntimeError("venv create failed: " + ((r.stderr or r.stdout) or "")[:500])
    pip = os.path.join(venv, "bin", "pip")
    r = subprocess.run([pip, "install", "--quiet", *packages],
                       capture_output=True, text=True, env=build_env)
    if r.returncode != 0:
        raise RuntimeError("pip install failed: " + ((r.stderr or r.stdout) or "")[:500])
    return os.path.join(venv, "bin", "python3")


# Streaming protocol (streaming-pipeline Phase 4). A request carrying
# `recordsStream: true` gets a chunked, non-JSON response: the bytes the tap
# wrote to DATRIS_TAP_OUTPUT (a FIFO in the run's scratch) as they arrive, then
# one trailer — the byte 0x1E followed by a JSON line
# {"stdout", "stderr", "exitCode", "timedOut"}. The wrapper never emits a raw
# 0x1E (JSON escapes control characters; the verbatim xml/text branch strips
# it), so the FIRST 0x1E in the stream is the trailer separator.
RECORD_TRAILER_SENTINEL = b"\x1e"
FIFO_READ_SIZE = 64 * 1024


def _decode(chunks):
    return b"".join(chunks).decode("utf-8", errors="replace")


def _drain(pipe, sink):
    """Read a child pipe to EOF on its own thread (a full pipe would otherwise stall the child)."""
    try:
        while True:
            data = pipe.read(FIFO_READ_SIZE)
            if not data:
                break
            sink.append(data)
    finally:
        pipe.close()


def _pump_fifo(fd, write):
    """Forward whatever is readable on the non-blocking FIFO. Returns
    "data" when bytes were forwarded, "eof" when read() returned 0 (no writer
    connected yet, or the writer closed), "empty" on EAGAIN (writer connected,
    nothing buffered)."""
    forwarded = False
    while True:
        try:
            data = os.read(fd, FIFO_READ_SIZE)
        except BlockingIOError:
            return "data" if forwarded else "empty"
        except OSError as e:
            if e.errno in (errno.EAGAIN, errno.EWOULDBLOCK):
                return "data" if forwarded else "empty"
            raise
        if not data:
            return "data" if forwarded else "eof"
        write(data)
        forwarded = True


def _run_streaming(python, wrapper_path, script_path, run_env, scratch, timeout, write):
    """Run the wrapper with DATRIS_TAP_OUTPUT pointed at a FIFO, forwarding the
    record bytes through `write` while the child runs. Returns the trailer dict.
    The FIFO is opened O_RDONLY | O_NONBLOCK before the child starts and polled
    alongside it, so a script that never opens it (raises first) cannot deadlock
    the runner; timeout and non-zero exit still produce a trailer."""
    fifo = os.path.join(scratch, "records.fifo")
    os.mkfifo(fifo, 0o600)
    run_env = dict(run_env)
    run_env["DATRIS_TAP_OUTPUT"] = fifo
    fd = os.open(fifo, os.O_RDONLY | os.O_NONBLOCK)
    proc = None
    out_chunks, err_chunks = [], []
    timed_out = False
    try:
        proc = subprocess.Popen([python, wrapper_path, script_path], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, env=run_env, cwd=scratch)
        t_out = threading.Thread(target=_drain, args=(proc.stdout, out_chunks), daemon=True)
        t_err = threading.Thread(target=_drain, args=(proc.stderr, err_chunks), daemon=True)
        t_out.start()
        t_err.start()
        deadline = time.monotonic() + timeout
        while True:
            state = _pump_fifo(fd, write)
            if proc.poll() is not None:
                # Child gone: forward what is still buffered in the pipe, then stop.
                while _pump_fifo(fd, write) == "data":
                    pass
                break
            if time.monotonic() > deadline:
                timed_out = True
                proc.kill()
                proc.wait()
                while _pump_fifo(fd, write) == "data":
                    pass
                break
            if state == "empty":
                # A writer is connected and the pipe is empty: wake on data.
                select.select([fd], [], [], 0.25)
            elif state == "eof":
                # No writer yet (or it already closed): poll the child.
                time.sleep(0.05)
        t_out.join(5)
        t_err.join(5)
        exit_code = -1 if timed_out else proc.returncode
        return {"stdout": _decode(out_chunks), "stderr": _decode(err_chunks),
                "exitCode": exit_code, "timedOut": timed_out}
    finally:
        if proc is not None and proc.poll() is None:
            # The response side failed (client went away): don't leave the tap running.
            proc.kill()
            proc.wait()
        os.close(fd)


def execute(body, stream=None):
    """Run one tap. Legacy (stream is None): returns the JSON result dict.
    Streaming (stream is a write(bytes) callback): forwards record bytes through
    it as they arrive and returns the trailer dict."""
    script = body.get("script") or ""
    wrapper = body.get("wrapper") or ""
    handed = body.get("env") or {}
    packages = body.get("packages") or []
    timeout = int(body.get("timeoutSec") or 300)

    scratch = tempfile.mkdtemp(prefix="tap_", dir="/tmp")
    try:
        script_path = os.path.join(scratch, "script.py")
        wrapper_path = os.path.join(scratch, "wrapper.py")
        with open(script_path, "w") as f:
            f.write(script)
        with open(wrapper_path, "w") as f:
            f.write(wrapper)

        python = _install_packages(packages, scratch) or "python3"

        # Run env: allowlist + the per-run vars the server handed us (platform DATRIS_*,
        # tap params, the tap's own secret). Start from the allowlist, never the full env.
        run_env = _base_env(scratch)
        run_env.update({str(k): ("" if v is None else str(v)) for k, v in handed.items()})

        if stream is not None:
            return _run_streaming(python, wrapper_path, script_path, run_env, scratch, timeout, stream)

        try:
            proc = subprocess.run([python, wrapper_path, script_path],
                                  capture_output=True, text=True, timeout=timeout,
                                  env=run_env, cwd=scratch)
            return {"stdout": proc.stdout, "stderr": proc.stderr,
                    "exitCode": proc.returncode, "timedOut": False}
        except subprocess.TimeoutExpired as e:
            out = e.stdout.decode() if isinstance(e.stdout, bytes) else (e.stdout or "")
            err = e.stderr.decode() if isinstance(e.stderr, bytes) else (e.stderr or "")
            return {"stdout": out, "stderr": err, "exitCode": -1, "timedOut": True}
    finally:
        shutil.rmtree(scratch, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.1 so the streaming path can use chunked transfer encoding. Every
    # response still closes the connection (one run per connection, as before).
    protocol_version = "HTTP/1.1"

    def _send(self, code, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True

    def _send_stream(self, body):
        """Streaming sibling of _send: chunked record bytes, then the 0x1E trailer."""
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        def write(data):
            if data:
                self.wfile.write(b"%x\r\n" % len(data))
                self.wfile.write(data)
                self.wfile.write(b"\r\n")

        try:
            trailer = execute(body, stream=write)
        except (BrokenPipeError, ConnectionResetError):
            # The server dropped the socket (over-budget abort); the tap process
            # was already killed in _run_streaming's finally. Nothing to report to.
            return
        except Exception as e:  # noqa: BLE001 - headers are out; report via the trailer
            trailer = {"stdout": "", "stderr": "tap-runner: " + str(e), "exitCode": -1, "timedOut": False}
        try:
            write(RECORD_TRAILER_SENTINEL + json.dumps(trailer).encode("utf-8") + b"\n")
            self.wfile.write(b"0\r\n\r\n")
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/execute":
            self._send(404, {"error": "not found"})
            return
        if TOKEN and self.headers.get("Authorization", "") != "Bearer " + TOKEN:
            self._send(401, {"error": "unauthorized"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length) if length > 0 else b"{}"
            body = json.loads(raw.decode("utf-8"))
            if body.get("recordsStream") is True:
                self._send_stream(body)
                return
            result = execute(body)
            self._send(200, result)
        except Exception as e:  # noqa: BLE001 - report any failure as 500 to the server
            self._send(500, {"error": str(e)})

    def log_message(self, *args):  # silence default request logging
        pass


if __name__ == "__main__":
    print("tap-runner listening on :%d" % PORT, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
