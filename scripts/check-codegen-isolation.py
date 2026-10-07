#!/usr/bin/env python3
"""Live proof that generated DQ / transformation scripts are isolated.

Run on the Docker host of a running compose stack:

    python3 scripts/check-codegen-isolation.py

It uses `docker exec <server> python3` (the datris server is the only other
member of codegen-net) to read the runner token and POST a probe script to the
runner's /execute-file, exactly as a generated script is sent. The probe tries
to reach the internet, vault, postgres, mongodb and minio; to read server-side
paths; to write outside its scratch; and lists its environment. It prints one
line per probe and exits:

    0  every probe is blocked and no secret-bearing variable is visible
    1  something the runner must not reach was reachable (named in the output)
    2  the probe could not be run (container missing, runner unreachable, ...)

Whether the runner can reach the Datris API (datris:8080) is reported but does
not fail the check: that is a documented open item.

Negative control, to prove the probe can detect a route: point it at the tap
runner, which has internet access by design. It must exit 1 with the public
host reachable:

    python3 scripts/check-codegen-isolation.py --url http://datris-tap-runner:8090
"""
import argparse
import json
import subprocess
import sys

# The script the runner executes. Standard library only; prints one JSON object.
PROBE = r'''
import json, os, re, socket, sys

def connect(host, port, timeout=3):
    try:
        s = socket.create_connection((host, port), timeout=timeout)
        s.close()
        return "reachable"
    except Exception as e:
        return "blocked (%s)" % type(e).__name__

def can_read(path):
    try:
        if os.path.isdir(path):
            os.listdir(path)
            return "reachable"
        with open(path, "rb") as f:
            f.read(1)
        return "reachable"
    except Exception as e:
        return "blocked (%s)" % type(e).__name__

def can_write(dirpath):
    p = os.path.join(dirpath, ".codegen-isolation-probe")
    try:
        with open(p, "w") as f:
            f.write("x")
        os.remove(p)
        return "reachable"
    except Exception as e:
        return "blocked (%s)" % type(e).__name__

def resolve(host):
    try:
        socket.getaddrinfo(host, 443)
        return "reachable"
    except Exception as e:
        return "blocked (%s)" % type(e).__name__

out = {
    "internet: resolve example.com": resolve("example.com"),
    "internet: connect 1.1.1.1:443": connect("1.1.1.1", 443),
    "internet: connect example.com:443": connect("example.com", 443),
    "vault:8200": connect("vault", 8200),
    "postgres:5432": connect("postgres", 5432),
    "mongodb:27017": connect("mongodb", 27017),
    "minio:9000": connect("minio", 9000),
    "read /config/application.yaml": can_read("/config/application.yaml"),
    "read /vault-token": can_read("/vault-token"),
    "read /tmp/datris-staging": can_read("/tmp/datris-staging"),
    "write /": can_write("/"),
    "write /app": can_write("/app"),
    "write /etc": can_write("/etc"),
    "write /home/runner": can_write("/home/runner"),
}
secret = re.compile(r"(TOKEN|SECRET|PASSWORD|PASSWD|API_?KEY|_KEY$|VAULT|AWS_|MONGO|POSTGRES|MINIO)", re.I)
out["_env"] = sorted(os.environ)
out["_secret_env"] = sorted(k for k in os.environ if secret.search(k))
out["_info datris:8080"] = connect("datris", 8080)
print(json.dumps(out))
'''

# Runs inside the server container: reads the token the way the server does and
# posts the probe with the /execute-file framing. Prints the runner's result line.
SENDER = r'''
import json, os, sys, urllib.request, urllib.error
url, probe = sys.argv[1], sys.argv[2]
weak = {"", "changeme-tap-runner-token", "change-me-to-a-long-random-string"}
tok = os.environ.get("TAP_RUNNER_TOKEN", "")
if tok in weak:
    try:
        tok = open(os.environ.get("TAP_RUNNER_TOKEN_FILE", "/tap-runner-token/token")).read().strip()
    except OSError:
        pass
meta = {"script": probe, "timeoutSec": 60, "inputName": "probe.txt"}
body = json.dumps(meta).encode() + b"\n" + b"probe\n"
req = urllib.request.Request(url.rstrip("/") + "/execute-file", data=body, method="POST",
                             headers={"Content-Type": "application/octet-stream",
                                      "Authorization": "Bearer " + tok})
try:
    with urllib.request.urlopen(req, timeout=120) as r:
        line = r.read().split(b"\n", 1)[0]
        print(line.decode())
except urllib.error.HTTPError as e:
    print(json.dumps({"httpError": e.code, "body": e.read()[:500].decode("utf-8", "replace")}))
    sys.exit(3)
except Exception as e:
    print(json.dumps({"transportError": "%s: %s" % (type(e).__name__, e)}))
    sys.exit(3)
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--server", default="datris", help="server container name (default: datris)")
    ap.add_argument("--url", default="http://datris-codegen-runner:8090",
                    help="runner URL as seen from the server (default: http://datris-codegen-runner:8090)")
    args = ap.parse_args()

    cmd = ["docker", "exec", "-i", args.server, "python3", "-", args.url, PROBE]
    try:
        proc = subprocess.run(cmd, input=SENDER, capture_output=True, text=True, timeout=180)
    except (OSError, subprocess.TimeoutExpired) as e:
        print("could not run the probe: %s" % e, file=sys.stderr)
        return 2
    lines = [l for l in proc.stdout.splitlines() if l.strip()]
    if not lines:
        print("no output from the probe sender:\n" + proc.stderr, file=sys.stderr)
        return 2
    try:
        result = json.loads(lines[-1])
    except ValueError:
        print("unreadable sender output:\n" + proc.stdout + proc.stderr, file=sys.stderr)
        return 2
    if "httpError" in result or "transportError" in result:
        print("runner request failed: %s" % json.dumps(result), file=sys.stderr)
        return 2
    if result.get("exitCode") != 0:
        print("probe script failed (exit %s):\n%s" % (result.get("exitCode"), result.get("stderr")), file=sys.stderr)
        return 2
    try:
        probes = json.loads(result.get("stdout", "").strip().splitlines()[-1])
    except (ValueError, IndexError):
        print("unreadable probe output:\n" + str(result.get("stdout")), file=sys.stderr)
        return 2

    failed = []
    print("runner: %s (via %s)" % (args.url, args.server))
    for name, state in probes.items():
        if name.startswith("_"):
            continue
        print("  %-36s %s" % (name, state))
        if state == "reachable":
            failed.append(name)
    print("  %-36s %s" % ("environment", ", ".join(probes.get("_env", []))))
    secrets = probes.get("_secret_env", [])
    if secrets:
        print("  %-36s %s" % ("SECRET-BEARING VARIABLES", ", ".join(secrets)))
        failed.append("secret-bearing variables: " + ", ".join(secrets))
    print("  %-36s %s  (reported only; see docs/tap-execution-isolation.mdx)" % (
        "datris:8080 (Datris API)", probes.get("_info datris:8080")))
    if failed:
        print("FAIL: reachable from the runner: " + "; ".join(failed))
        return 1
    print("OK: every probe blocked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
