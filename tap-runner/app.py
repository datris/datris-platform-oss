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
import json
import os
import shutil
import subprocess
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("TAP_RUNNER_PORT", "8090"))
TOKEN = os.environ.get("TAP_RUNNER_TOKEN", "")

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


def execute(body):
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
    def _send(self, code, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

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
            result = execute(json.loads(raw.decode("utf-8")))
            self._send(200, result)
        except Exception as e:  # noqa: BLE001 - report any failure as 500 to the server
            self._send(500, {"error": str(e)})

    def log_message(self, *args):  # silence default request logging
        pass


if __name__ == "__main__":
    print("tap-runner listening on :%d" % PORT, flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
