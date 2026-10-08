#!/usr/bin/env python3
"""
datris-tap-runner — isolated execution sidecar for tap fetch() code.

Phase 3 / Increment 1 of tap-execution-isolation. This process runs in a container
that holds NO platform secrets (no Vault address/token, no /datris/.env, no /config)
and sits on a network with no route to the secret-bearing services. The server POSTs
a single tap run to /execute; this runner executes it as a fresh subprocess in a
per-run scratch dir and returns stdout/stderr/exitCode. Nothing here can read platform
secrets off disk or reach Vault, because they simply are not present in this container.

The same image also runs as datris-codegen-runner (codegen-script-isolation): the server
POSTs a generated data-quality / transformation script plus its input file to
/execute-file and gets the result and output file back. That service sits on an
internal-only network (no internet) and keeps its per-run files on the codegen-scratch
volume (CODEGEN_SCRATCH_DIR).
"""
import errno
import json
import os
import select
import shutil
import signal
import socket
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


# ---- /execute-file: generated DQ / transformation scripts (codegen-script-isolation) ----
# Used by the datris-codegen-runner service (same image as the tap runner, on the
# internal-only codegen-net). Request body: ONE JSON line {"script", "timeoutSec",
# "inputName", "outputName"?}, "\n", then the raw input bytes (Content-Length known).
# Response: ONE JSON line {"stdout", "stderr", "exitCode", "timedOut", "outputBytes"},
# "\n", then the output file bytes when "outputName" was given. The payload is copied
# in 64 KB chunks both ways and lands only in a per-run directory under
# CODEGEN_SCRATCH_DIR (the codegen-scratch volume in compose), removed after every run.
CODEGEN_CHUNK = 64 * 1024
CODEGEN_META_MAX = 16 * 1024 * 1024  # the metadata line carries the script text
CODEGEN_UPLOAD_IDLE_SEC = 300
SCRATCH_VOLUME_HINT = ("No space left on device in the codegen-scratch volume "
                       "(CODEGEN_SCRATCH_DIR); free disk on the Docker host and retry")


def _codegen_scratch_dir():
    """Read at request time, so a test (or a restart with new env) sees the current value."""
    return os.environ.get("CODEGEN_SCRATCH_DIR") or "/tmp"


def _safe_name(name, default):
    """Reduce a client-supplied file name to a plain basename inside the run dir."""
    base = os.path.basename(str(name or "").replace("\\", "/"))
    if base in ("", ".", "..") or base == "script.py":
        return default
    return base


# Run dirs of in-flight requests: never wiped by another run's cleanup. Creating a run
# dir and registering it, and the after-run wipe of the volume root, both hold the lock,
# so a wipe can never see a new run dir before it is registered.
_ACTIVE_RUNS = set()
_ACTIVE_LOCK = threading.Lock()


def _scratch_dedicated():
    """True when CODEGEN_SCRATCH_DIR is a volume used for nothing else (the image sets
    CODEGEN_SCRATCH_DEDICATED=1): then everything under it is ours to remove, not only
    cg_* run dirs. Without it (a bare `python3 app.py`, tests) only cg_* is touched."""
    return bool(os.environ.get("CODEGEN_SCRATCH_DIR")) and os.environ.get("CODEGEN_SCRATCH_DEDICATED") == "1"


def _codegen_tmp_dir():
    """The interpreter temp dir (the runner's /tmp tmpfs) to empty after each run, so a
    script cannot leave files there for the next one. Only when CODEGEN_SCRATCH_DIR is
    set (the codegen runner), never in the tap runner."""
    if not os.environ.get("CODEGEN_SCRATCH_DIR"):
        return None
    return os.environ.get("CODEGEN_TMP_DIR") or None


def _remove(p):
    """Remove a file, link or tree, including dirs a script made unreadable (same uid)
    and trees deeper than the recursion limit or PATH_MAX. Never raises."""
    try:
        if os.path.islink(p) or not os.path.isdir(p):
            os.unlink(p)
            return
        _remove_tree(p)
    except Exception as e:  # noqa: BLE001 - a cleanup helper must never throw
        _log("codegen cleanup: could not remove %s: %s" % (p, e))


_DIR_FLAGS = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)


def _remove_tree(top):
    """Iterative, fd-relative removal that never goes more than two levels deep: every
    subdirectory found below the top level is first renamed up into `top` (flattening
    the tree), so neither recursion depth nor path length depends on how deep a script
    nested its directories. At most two directory fds are open at once."""
    try:
        os.chmod(top, 0o700)
    except OSError:
        pass
    root_fd = os.open(top, _DIR_FLAGS)
    try:
        queue = [None]  # None = top itself; otherwise a name directly under top
        counter = 0
        while queue:
            name = queue.pop()
            if name is not None:
                try:
                    os.chmod(name, 0o700, dir_fd=root_fd)
                except (OSError, NotImplementedError):
                    pass
                try:
                    fd = os.open(name, _DIR_FLAGS, dir_fd=root_fd)
                except OSError:
                    continue
            else:
                fd = root_fd
            try:
                try:
                    entries = list(os.scandir(fd))
                except OSError:
                    entries = []
                for e in entries:
                    try:
                        is_dir = e.is_dir(follow_symlinks=False)
                    except OSError:
                        is_dir = False
                    if not is_dir:
                        try:
                            os.unlink(e.name, dir_fd=fd)
                        except OSError:
                            pass
                    elif fd == root_fd:
                        queue.append(e.name)
                    else:
                        try:
                            # rename(2) of a directory to a new parent needs write on the moved dir
                            os.chmod(e.name, 0o700, dir_fd=fd)
                        except (OSError, NotImplementedError):
                            pass
                        while True:
                            counter += 1
                            flat = ".cg_flat_%d" % counter
                            try:
                                os.rename(e.name, flat, src_dir_fd=fd, dst_dir_fd=root_fd)
                                queue.append(flat)
                                break
                            except FileExistsError:
                                continue
                            except OSError as err:
                                if err.errno in (errno.EEXIST, errno.ENOTEMPTY):
                                    continue
                                break
            finally:
                if fd != root_fd:
                    os.close(fd)
            if name is not None:
                try:
                    os.rmdir(name, dir_fd=root_fd)
                except OSError:
                    pass
    finally:
        os.close(root_fd)
    os.rmdir(top)


def _log(msg):
    print(msg, file=sys.stderr, flush=True)


def _empty_dir(root, keep=()):
    try:
        names = os.listdir(root)
    except OSError:
        return
    for n in names:
        p = os.path.join(root, n)
        if p in keep:
            continue
        _remove(p)


def _cleanup_after_run(root):
    """Leave nothing a finished run wrote: on a dedicated scratch volume, everything
    except other in-flight run dirs (a script can write ../ into the volume root);
    and the interpreter temp dir."""
    if _scratch_dedicated():
        with _ACTIVE_LOCK:
            _empty_dir(root, set(_ACTIVE_RUNS))
    tmp = _codegen_tmp_dir()
    if tmp and os.path.realpath(tmp) != os.path.realpath(root):
        _empty_dir(tmp)


def sweep_codegen_scratch(root):
    """At start: remove what a killed runner left. Every cg_* run dir always; on a
    dedicated volume everything else too, and the interpreter temp dir."""
    if _scratch_dedicated():
        _empty_dir(root)
    else:
        try:
            names = os.listdir(root)
        except OSError:
            names = []
        for n in names:
            p = os.path.join(root, n)
            if n.startswith("cg_") and os.path.isdir(p) and not os.path.islink(p):
                _remove(p)
    tmp = _codegen_tmp_dir()
    if tmp and os.path.realpath(tmp) != os.path.realpath(root):
        _empty_dir(tmp)


class CodegenUploadError(Exception):
    """The client went away (or stalled) before sending the whole input."""


class CodegenNoSpace(Exception):
    """ENOSPC while receiving the input."""


def _receive_input(rfile, remaining, path):
    """Copy exactly `remaining` bytes from the socket to `path`, 64 KB at a time.
    Always consumes the whole body (so the client can read the answer). ENOSPC on a
    write, the final flush or the close (a buffered file reports a full disk when it
    flushes) raises CodegenNoSpace once the body is consumed."""
    no_space = False
    f = open(path, "wb")
    try:
        while remaining > 0:
            chunk = rfile.read(min(CODEGEN_CHUNK, remaining))
            if not chunk:
                raise CodegenUploadError("client closed the connection mid-upload")
            remaining -= len(chunk)
            if no_space:
                continue
            try:
                f.write(chunk)
            except OSError as e:
                if e.errno != errno.ENOSPC:
                    raise
                no_space = True
        if not no_space:
            try:
                f.flush()
            except OSError as e:
                if e.errno != errno.ENOSPC:
                    raise
                no_space = True
    finally:
        try:
            f.close()
        except OSError as e:
            if e.errno != errno.ENOSPC:
                raise
            no_space = True
    if no_space:
        raise CodegenNoSpace()


def _kill_group(proc):
    """Kill the script's whole process group, so a child it spawned cannot keep writing
    into scratch after the run (a process that calls setsid() itself escapes this)."""
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError, OSError):
        pass


def _run_codegen(script_path, in_path, out_path, scratch, timeout):
    argv = ["python3", script_path, in_path] + ([out_path] if out_path else [])
    proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            env=_base_env(scratch), cwd=scratch, start_new_session=True)
    try:
        out, err = proc.communicate(timeout=timeout)
        timed_out = False
    except subprocess.TimeoutExpired:
        # Still running is a timeout; an exited script whose stray child held the pipes is not.
        script_running = proc.poll() is None
        _kill_group(proc)
        try:
            out, err = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            out, err = b"", b""
        timed_out = script_running
    finally:
        # The script has exited (or was killed): take any leftover children with it.
        _kill_group(proc)
        if proc.poll() is None:
            proc.kill()
            proc.wait()
    return {"stdout": (out or b"").decode("utf-8", errors="replace"),
            "stderr": (err or b"").decode("utf-8", errors="replace"),
            "exitCode": -1 if timed_out else proc.returncode, "timedOut": timed_out}


def execute_file(meta, rfile, length, wfile, send_error=None, send_head=None):
    """Run one generated script against a streamed input file.

    `meta` is the parsed metadata line; `rfile` holds `length` more bytes (the input).
    `send_head(content_length)` writes the 200 status line and headers; `send_error(code, obj)`
    writes a JSON error response. Both default to bare writes on `wfile` (no HTTP framing)
    so the function can be driven directly. The run dir is removed on every exit path."""
    script = meta.get("script") or ""
    timeout = int(meta.get("timeoutSec") or 300)
    in_name = _safe_name(meta.get("inputName"), "input")
    out_name = meta.get("outputName")
    out_name = _safe_name(out_name, "output") if out_name else None
    if out_name == in_name:
        out_name = "output_" + in_name

    if send_error is None:
        def send_error(code, obj):
            wfile.write(json.dumps(obj).encode("utf-8") + b"\n")
    if send_head is None:
        def send_head(_n):
            pass

    root = _codegen_scratch_dir()
    scratch = None
    cleaned = []

    def cleanup():
        """Remove this run's dir and, on a dedicated volume, anything else a run left. Idempotent."""
        if cleaned:
            return
        cleaned.append(True)
        if scratch:
            try:
                _remove(scratch)
            finally:
                with _ACTIVE_LOCK:
                    _ACTIVE_RUNS.discard(scratch)
        try:
            _cleanup_after_run(root)
        except Exception as e:  # noqa: BLE001 - a wipe failure must not fail a finished run
            _log("codegen cleanup after run failed: %s" % e)

    try:
        try:
            with _ACTIVE_LOCK:
                scratch = tempfile.mkdtemp(prefix="cg_", dir=root)
                _ACTIVE_RUNS.add(scratch)
            script_path = os.path.join(scratch, "script.py")
            with open(script_path, "w") as f:
                f.write(script)
            in_path = os.path.join(scratch, in_name)
            out_path = os.path.join(scratch, out_name) if out_name else None
        except OSError as e:
            if e.errno == errno.ENOSPC:
                # Nothing of the body was read yet: drain it so the client sees the 507.
                _discard(rfile, length)
                cleanup()
                send_error(507, {"error": SCRATCH_VOLUME_HINT})
                return
            raise
        try:
            _receive_input(rfile, length, in_path)
        except CodegenNoSpace:
            # _receive_input consumed the whole body; answer at once.
            cleanup()
            send_error(507, {"error": SCRATCH_VOLUME_HINT})
            return

        result = _run_codegen(script_path, in_path, out_path, scratch, timeout)
        size = 0
        out_file = None
        try:
            if out_path and not result["timedOut"] and os.path.isfile(out_path):
                out_file = open(out_path, "rb")
                size = os.fstat(out_file.fileno()).st_size
            # Clean up BEFORE answering: the open handle keeps the output readable after
            # its directory is gone, and the client never sees the end of a response
            # while the run's files still exist.
            cleanup()
            result["outputBytes"] = size
            line = json.dumps(result).encode("utf-8") + b"\n"
            send_head(len(line) + size)
            for i in range(0, len(line), CODEGEN_CHUNK):
                wfile.write(line[i:i + CODEGEN_CHUNK])
            left = size
            while left > 0:
                data = out_file.read(min(CODEGEN_CHUNK, left))
                if not data:
                    # Cannot happen once the script exited; pad so Content-Length holds.
                    data = b"\0" * min(CODEGEN_CHUNK, left)
                wfile.write(data)
                left -= len(data)
            wfile.flush()
        finally:
            if out_file is not None:
                out_file.close()
    finally:
        cleanup()


def _discard(rfile, remaining):
    while remaining > 0:
        chunk = rfile.read(min(CODEGEN_CHUNK, remaining))
        if not chunk:
            return
        remaining -= len(chunk)


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

    def _execute_file(self):
        """POST /execute-file. Never reads the whole body or builds the whole response
        in memory: the metadata line is read on its own, the rest is streamed."""
        self.close_connection = True
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            length = -1
        if length < 0:
            self._send(411, {"error": "Content-Length required"})
            return
        # A stalled upload must not pin a thread forever; the run itself is bounded by timeoutSec.
        self.connection.settimeout(CODEGEN_UPLOAD_IDLE_SEC)
        line = self.rfile.readline(min(length, CODEGEN_META_MAX) + 1)
        if not line.endswith(b"\n"):
            self._send(400, {"error": "metadata line missing or too long"})
            return
        try:
            meta = json.loads(line.decode("utf-8"))
            if not isinstance(meta, dict):
                raise ValueError("metadata is not an object")
        except ValueError as e:
            self._send(400, {"error": "bad metadata line: " + str(e)})
            return

        def send_head(n):
            self.connection.settimeout(None)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(n))
            self.send_header("Connection", "close")
            self.end_headers()

        try:
            execute_file(meta, self.rfile, length - len(line), self.wfile,
                         send_error=self._send, send_head=send_head)
        except (CodegenUploadError, BrokenPipeError, ConnectionResetError, socket.timeout):
            # Client went away (or stalled): the run dir is already gone; nobody to answer.
            return
        except Exception as e:  # noqa: BLE001 - report to the server when headers are not out yet
            try:
                self._send(500, {"error": str(e)})
            except Exception:  # noqa: BLE001
                pass

    def do_POST(self):
        if self.path not in ("/execute", "/execute-file"):
            self._send(404, {"error": "not found"})
            return
        if TOKEN and self.headers.get("Authorization", "") != "Bearer " + TOKEN:
            self._send(401, {"error": "unauthorized"})
            return
        if self.path == "/execute-file":
            self._execute_file()
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
    if os.environ.get("CODEGEN_SCRATCH_DIR"):
        # A killed runner leaves its in-flight run dirs behind; remove them before serving.
        try:
            sweep_codegen_scratch(os.environ["CODEGEN_SCRATCH_DIR"])
        except Exception as e:  # noqa: BLE001 - never refuse to serve over a sweep failure
            _log("codegen scratch sweep at start failed (serving anyway): %s" % e)
    print("tap-runner listening on :%d" % PORT, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
