"""Host-side checks for `datris doctor`.

The server answers `GET /api/v1/doctor` with the checks it can run from inside
the JVM (Vault token TTL, AI slot secrets, embedding model, disk, version
skew). This module runs the checks that need the Docker socket or the repo
checkout — anonymous volumes, container env drift, compose orphans, a stale
jar — and merges both halves into one report with the same shape.

Every check is deterministic, read-only, and prints a remediation command; it
never runs one. Values from `.env` / container env are never printed — only key
names (and hashes where two values are compared).
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from datetime import datetime, timezone

DOCTOR_VERSION = 1

# Compose services whose data lives on a volume, and the mount paths that
# hold it. An anonymous volume at one of these is lost on `down` / container
# removal (postgres orphaned 28M rows this way). Other anonymous mounts an
# image declares (vault's /vault/logs) are not data and are ignored.
STATEFUL_DATA_MOUNTS = {
    "postgres": ["/var/lib/postgresql/data", "/var/lib/postgresql"],
    "mongodb": ["/data/db", "/data/configdb"],
    "minio": ["/data"],
    "vault": ["/vault/file", "/vault/data"],
    "tei": ["/data"],
    "zookeeper": ["/var/lib/zookeeper/data", "/var/lib/zookeeper/log"],
    "kafka": ["/var/lib/kafka/data"],
}
STATEFUL_CONTAINERS = list(STATEFUL_DATA_MOUNTS)

# Services whose container env is compared with the current compose rendering
# (service name -> default container_name). `restart` does not reload env; a
# rotated MONGO_PASSWORD kept failing auth every minute until recreate.
ENV_DRIFT_SERVICES = ["datris", "mcp-server", "datris-tap-runner"]

# Vault slots checked from the host before an upgrade (server may be down).
AI_SLOT_SECRETS = [("ai-primary", "oss/ai-primary"), ("codegen", "oss/codegen"), ("embedding", "oss/embedding")]

CREDENTIAL_KEY = re.compile(r"(PASSWORD|PASSWD|TOKEN|SECRET|_KEY\b|APIKEY|API_KEY)", re.I)
ANON_VOLUME = re.compile(r"^[0-9a-f]{64}$")

# Keys `.env` may hold that no compose service is expected to forward.
ENV_KEYS_NOT_FORWARDED = re.compile(r"^(COMPOSE_|DOCKER_)")

STATUS_ICON = {"ok": "✓", "warn": "!", "error": "✗", "skip": "○"}


def result(check_id, status, detail, remediation="", surface="host", ms=0):
    severity = status if status in ("warn", "error") else "ok"
    return {
        "id": check_id,
        "status": status,
        "severity": severity,
        "detail": detail,
        "remediation": remediation,
        "surface": surface,
        "ms": ms,
    }


def _sha(value):
    return hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:8]


class Runner:
    """Thin subprocess wrapper so tests can fake docker/git/df."""

    def __init__(self, compose_file=None, project_dir=None):
        self.compose_file = compose_file
        self.project_dir = project_dir or os.getcwd()

    def has_docker(self):
        return shutil.which("docker") is not None

    def run(self, args, timeout=60, input_text=None):
        """Return (returncode, stdout, stderr); never raises on a non-zero exit."""
        try:
            p = subprocess.run(
                args, capture_output=True, text=True, timeout=timeout, cwd=self.project_dir, input=input_text
            )
            return p.returncode, p.stdout, p.stderr
        except FileNotFoundError as e:
            return 127, "", str(e)
        except subprocess.TimeoutExpired:
            return 124, "", f"timed out after {timeout}s: {' '.join(args)}"

    def compose(self, args, timeout=60, input_text=None):
        base = ["docker", "compose"]
        if self.compose_file:
            base += ["-f", self.compose_file]
        return self.run(base + list(args), timeout=timeout, input_text=input_text)

    def compose_file_path(self):
        if self.compose_file:
            return self.compose_file
        for name in ("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml"):
            p = os.path.join(self.project_dir, name)
            if os.path.isfile(p):
                return p
        return None


# ----------------------------------------------------------------- helpers

def parse_dotenv(path):
    """KEY=VALUE pairs from a .env file; comments and blanks skipped, quotes stripped."""
    env = {}
    if not path or not os.path.isfile(path):
        return env
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[len("export "):]
            key, _, value = line.partition("=")
            key = key.strip()
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            if re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", key):
                env[key] = value
    return env


def compose_config_json(runner):
    rc, out, err = runner.compose(["config", "--format", "json"])
    if rc != 0 or not out.strip():
        return None
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return None


def _container_env(runner, container):
    rc, out, _ = runner.run(["docker", "inspect", "-f", "{{json .Config.Env}}", container])
    if rc != 0 or not out.strip():
        return None
    try:
        items = json.loads(out.strip()) or []
    except json.JSONDecodeError:
        return None
    env = {}
    for item in items:
        k, _, v = item.partition("=")
        env[k] = v
    return env


def _parse_ps_json(text):
    """`docker compose ps --format json` prints one object per line on newer
    Compose and a JSON array on older ones."""
    text = text.strip()
    if not text:
        return []
    try:
        data = json.loads(text)
        return data if isinstance(data, list) else [data]
    except json.JSONDecodeError:
        rows = []
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        return rows


# ------------------------------------------------------------------ checks

def check_volumes_anonymous(runner):
    offenders = []
    seen = []
    for name in STATEFUL_CONTAINERS:
        rc, out, _ = runner.run(["docker", "inspect", "-f", "{{json .Mounts}}", name])
        if rc != 0:
            continue
        seen.append(name)
        try:
            mounts = json.loads(out.strip()) or []
        except json.JSONDecodeError:
            continue
        data_paths = STATEFUL_DATA_MOUNTS.get(name, [])
        for m in mounts:
            dest = (m.get("Destination") or "").rstrip("/")
            if m.get("Type") == "volume" and ANON_VOLUME.match(m.get("Name", "")) and dest in data_paths:
                offenders.append(f"{name} ({dest} on {m['Name'][:12]}…)")
    if not seen:
        return result("volumes.anonymous", "skip", "no stateful containers found (stack not running?)")
    if offenders:
        return result(
            "volumes.anonymous", "error",
            "stateful service(s) store data on an anonymous volume; `docker compose down` or a container removal will orphan it: "
            + "; ".join(offenders),
            "Add a named volume for the service in docker-compose.yml (see the top-level `volumes:` block), then copy the data across: "
            "`docker run --rm -v <old>:/src:ro -v <new>:/dst alpine cp -a /src/. /dst/`.",
        )
    return result("volumes.anonymous", "ok", f"named volumes on {', '.join(seen)}")


def check_volumes_dangling(runner, limit=100):
    rc, out, err = runner.run(["docker", "volume", "ls", "-qf", "dangling=true"])
    if rc != 0:
        return result("volumes.dangling", "skip", f"docker volume ls failed: {err.strip()[:120]}")
    anon = [v for v in out.split() if ANON_VOLUME.match(v)]
    if not anon:
        return result("volumes.dangling", "ok", "no dangling anonymous volumes")
    found = []
    if len(anon) > limit:
        anon = anon[:limit]
    for vol in anon:
        rc, listing, _ = runner.run(["docker", "run", "--rm", "-v", f"{vol}:/v:ro", "alpine", "ls", "-A", "/v"], timeout=120)
        if rc != 0:
            continue
        names = set(listing.split())
        kind = None
        if "PG_VERSION" in names:
            kind = "postgres"
        elif "WiredTiger" in names or "WiredTiger.wt" in names:
            kind = "mongodb"
        elif ".minio.sys" in names:
            kind = "minio"
        if kind:
            found.append(f"{vol[:12]}… looks like a {kind} data dir")
    if found:
        return result(
            "volumes.dangling", "warn",
            f"{len(found)} dangling volume(s) hold recognizable data — possibly orphaned by a container recreate: " + "; ".join(found),
            "Inspect before pruning: `docker run --rm -v <volume>:/v:ro alpine ls -la /v`. To recover, copy into the service's named volume: "
            "`docker run --rm -v <old>:/src:ro -v <new>:/dst alpine cp -a /src/. /dst/`.",
        )
    return result("volumes.dangling", "ok", f"{len(anon)} dangling anonymous volume(s), none holding recognizable data (empty ones are safe to `docker volume prune`)")


def check_vault_hcl_drift(runner):
    path = os.path.join(runner.project_dir, "docker", "vault.hcl")
    if not os.path.isfile(path):
        return result("vault.hcl_drift", "skip", "docker/vault.hcl not in this directory (standalone install?)")
    rc, started, _ = runner.run(["docker", "inspect", "-f", "{{.State.StartedAt}}", "vault"])
    if rc != 0:
        return result("vault.hcl_drift", "skip", "vault container not found")
    with open(path, "rb") as f:
        local = f.read()
    rc, inside, _ = runner.compose(["exec", "-T", "vault", "cat", "/vault/config/vault.hcl"])
    remediation = "docker compose up -d --force-recreate vault   # then restart datris so it re-mints its token"
    if rc == 0 and inside and hashlib.sha256(inside.encode("utf-8")).hexdigest() != hashlib.sha256(local).hexdigest():
        return result(
            "vault.hcl_drift", "warn",
            "docker/vault.hcl differs from the file the vault container is running with",
            remediation,
        )
    started_at = _parse_docker_time(started.strip())
    mtime = os.path.getmtime(path)
    if started_at and mtime > started_at:
        return result(
            "vault.hcl_drift", "warn",
            "docker/vault.hcl changed after the vault container started; Vault only reads its config at start "
            "(a raised max_lease_ttl is not in effect until the container is recreated)",
            remediation,
        )
    return result("vault.hcl_drift", "ok", "vault container started after the last change to docker/vault.hcl")


def _parse_docker_time(text):
    """Docker's RFC3339 timestamps carry nanoseconds; trim to what fromisoformat accepts."""
    if not text or text.startswith("0001-"):
        return None
    m = re.match(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})?$", text)
    if not m:
        return None
    frac = (m.group(2) or "")[:6].ljust(6, "0")
    tz = m.group(3) or "Z"
    iso = f"{m.group(1)}.{frac}{'+00:00' if tz == 'Z' else tz}"
    try:
        return datetime.fromisoformat(iso).timestamp()
    except ValueError:
        return None


def check_env_not_forwarded(runner):
    env_path = os.path.join(runner.project_dir, ".env")
    if not os.path.isfile(env_path):
        return result("env.not_forwarded", "skip", "no .env in this directory")
    compose_path = runner.compose_file_path()
    if not compose_path or not os.path.isfile(compose_path):
        return result("env.not_forwarded", "skip", "no compose file in this directory")
    texts = []
    with open(compose_path, encoding="utf-8", errors="replace") as f:
        texts.append(f.read())
    for extra in ("docker-compose.override.yml", "docker-compose.override.yaml"):
        p = os.path.join(runner.project_dir, extra)
        if os.path.isfile(p):
            with open(p, encoding="utf-8", errors="replace") as f:
                texts.append(f.read())
    compose_text = "\n".join(texts)
    env = parse_dotenv(env_path)
    unreferenced = []
    for key, value in env.items():
        if not value or ENV_KEYS_NOT_FORWARDED.match(key):
            continue
        if re.search(r"\$\{" + re.escape(key) + r"[:}?-]", compose_text) or re.search(r"\$" + re.escape(key) + r"\b", compose_text):
            continue
        unreferenced.append(key)
    if unreferenced:
        return result(
            "env.not_forwarded", "warn",
            ".env sets " + ", ".join(sorted(unreferenced)) + " but no service in the compose file reads it, so the value never reaches a container",
            "Add the variable to the right service's `environment:` block in docker-compose.yml (the datris service for DATRIS_* / TAP_* switches), "
            "then regenerate docker-compose.standalone.yml with `python3 scripts/build-standalone-compose.py`. "
            "If the key is obsolete, remove it from .env.",
        )
    return result("env.not_forwarded", "ok", f"every non-empty .env key ({len(env)} total) is referenced by the compose file")


def check_env_container_drift(runner):
    config = compose_config_json(runner)
    if not config:
        return result("env.container_drift", "skip", "docker compose config unavailable (not in a compose project directory?)")
    services = config.get("services", {})
    drifted = []
    compared = []
    credential_drift = False
    for svc in ENV_DRIFT_SERVICES:
        spec = services.get(svc)
        if not spec:
            continue
        container = spec.get("container_name") or svc
        actual = _container_env(runner, container)
        if actual is None:
            continue
        compared.append(container)
        expected = spec.get("environment") or {}
        if isinstance(expected, list):
            expected = dict(item.partition("=")[::2] for item in expected)
        keys = []
        for key, value in expected.items():
            if value is None:
                continue
            want = str(value).lower() if isinstance(value, bool) else str(value)
            have = actual.get(key)
            if have is None or have != want:
                keys.append(key)
                if CREDENTIAL_KEY.search(key):
                    credential_drift = True
        if keys:
            drifted.append((container, sorted(keys)))
    if not compared:
        return result("env.container_drift", "skip", "none of the datris containers are running")
    if drifted:
        detail = "; ".join(f"{c} was created with different env than .env/compose now say (keys: {', '.join(k)})" for c, k in drifted)
        cmd = " ".join(c for c, _ in drifted)
        return result(
            "env.container_drift", "error" if credential_drift else "warn",
            detail + " — `docker compose restart` does not reload env",
            f"docker compose up -d --force-recreate --no-deps {cmd}",
        )
    return result("env.container_drift", "ok", f"container env matches compose for {', '.join(compared)}")


def check_compose_orphans(runner):
    rc, services_out, err = runner.compose(["config", "--services"])
    if rc != 0:
        return result("compose.orphans", "skip", f"docker compose config failed: {err.strip()[:120]}")
    defined = set(services_out.split())
    rc, ps_out, err = runner.compose(["ps", "-a", "--format", "json"])
    if rc != 0:
        return result("compose.orphans", "skip", f"docker compose ps failed: {err.strip()[:120]}")
    rows = _parse_ps_json(ps_out)
    orphans = sorted({(r.get("Service") or r.get("Name") or "?") for r in rows if r.get("Service") not in defined})
    if orphans:
        return result(
            "compose.orphans", "warn",
            "container(s) from a previous compose file are still present and may hold ports: " + ", ".join(orphans),
            "docker compose up -d --remove-orphans",
        )
    return result("compose.orphans", "ok", f"{len(rows)} container(s), all defined in the current compose file")


def check_mcp_reachable(mcp_url, timeout=3):
    import requests
    try:
        resp = requests.get(mcp_url, timeout=timeout, stream=True)
        try:
            status = resp.status_code
        finally:
            resp.close()
    except requests.RequestException as e:
        return result(
            "mcp.reachable", "error",
            f"MCP server at {mcp_url} unreachable: {e.__class__.__name__}",
            "docker compose ps mcp-server   # then `docker compose logs mcp-server`",
        )
    if status in (401, 403):
        # Reachable — it wants an API key on the SSE URL (USE_API_KEYS=true).
        return result("mcp.reachable", "ok", f"MCP server at {mcp_url} answered HTTP {status} (up; API key required on the SSE URL)")
    if status >= 500:
        return result("mcp.reachable", "error", f"MCP server at {mcp_url} answered HTTP {status}", "docker compose logs mcp-server")
    return result("mcp.reachable", "ok", f"MCP server at {mcp_url} answered HTTP {status}")


def check_disk_usage_host(runner):
    rc, root, _ = runner.run(["docker", "info", "--format", "{{.DockerRootDir}}"])
    root = root.strip()
    total = free = None
    where = root or "docker root"
    if root and os.path.isdir(root):
        try:
            usage = shutil.disk_usage(root)
            total, free = usage.total, usage.free
        except OSError:
            pass
    if total is None:
        # Docker Desktop: the data root lives inside the VM. df from a
        # throwaway container reports that disk.
        rc, out, _ = runner.run(["docker", "run", "--rm", "alpine", "df", "-Pk", "/"], timeout=120)
        if rc == 0:
            lines = [l for l in out.splitlines() if l.strip()]
            if len(lines) >= 2:
                parts = lines[-1].split()
                if len(parts) >= 4:
                    try:
                        total = int(parts[1]) * 1024
                        free = int(parts[3]) * 1024
                        where = "docker VM disk"
                    except ValueError:
                        pass
    if total is None or total <= 0:
        return result("disk.usage", "skip", "could not measure the disk holding Docker's data root")
    pct = (total - free) * 100.0 / total
    rc, sysdf, _ = runner.run(["docker", "system", "df", "--format", "{{.Type}}: {{.Reclaimable}}"])
    reclaim = "; ".join(l.strip() for l in sysdf.splitlines() if l.strip()) if rc == 0 else ""
    detail = f"{where} {pct:.0f}% used ({free / 1073741824:.1f} GB free)" + (f" — reclaimable: {reclaim}" if reclaim else "")
    remediation = "Free space before pulling images: `docker image prune`, `docker builder prune`, and check the pip-cache and object-store volumes. " \
                  "`docker compose pull` on a nearly full disk half-writes layers."
    if pct >= 95:
        return result("disk.usage", "error", detail, remediation)
    if pct >= 85:
        return result("disk.usage", "warn", detail, remediation)
    return result("disk.usage", "ok", detail)


def check_build_stale_jar(runner, version_info):
    compose_path = runner.compose_file_path()
    if not compose_path or not os.path.isfile(compose_path):
        return result("build.stale_jar", "skip", "no compose file in this directory")
    with open(compose_path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    if not re.search(r"^\s*build:\s*\.\s*(#.*)?$", text, re.M):
        return result("build.stale_jar", "skip", "server image is pulled, not built from source")
    if not os.path.isdir(os.path.join(runner.project_dir, ".git")):
        return result("build.stale_jar", "skip", "not a git checkout")
    if not version_info:
        return result("build.stale_jar", "skip", "server version info unavailable")
    jar_sha = (version_info.get("gitHeadCommit") or "unknown").strip()
    built_at = version_info.get("builtAtMillis")
    rc, head, _ = runner.run(["git", "rev-parse", "HEAD"])
    head = head.strip()
    remediation = "sbt clean assembly && docker compose build --no-cache datris && docker compose up -d datris"
    if jar_sha == "unknown" or not jar_sha:
        return result("build.stale_jar", "warn", "running jar carries no git commit stamp (built before this check existed)", remediation)
    if rc == 0 and head and head != jar_sha:
        built = _fmt_millis(built_at)
        return result(
            "build.stale_jar", "warn",
            f"running server was built from {jar_sha[:12]} at {built}; checkout is at {head[:12]}",
            remediation,
        )
    try:
        built_secs = int(built_at) / 1000.0
    except (TypeError, ValueError):
        built_secs = None
    newest = _newest_mtime(os.path.join(runner.project_dir, "datrisserver", "src"))
    if built_secs and newest and newest > built_secs:
        return result(
            "build.stale_jar", "warn",
            f"datrisserver/src has files newer ({_fmt_secs(newest)}) than the running jar ({_fmt_millis(built_at)})",
            remediation,
        )
    return result("build.stale_jar", "ok", f"running jar matches checkout {head[:12]}" if head else f"jar built from {jar_sha[:12]}")


def _newest_mtime(root):
    newest = 0.0
    if not os.path.isdir(root):
        return None
    for dirpath, _, files in os.walk(root):
        for name in files:
            try:
                newest = max(newest, os.path.getmtime(os.path.join(dirpath, name)))
            except OSError:
                continue
    return newest or None


def _fmt_millis(ms):
    try:
        return _fmt_secs(int(ms) / 1000.0)
    except (TypeError, ValueError):
        return "unknown time"


def _fmt_secs(secs):
    return datetime.fromtimestamp(secs, tz=timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def check_vault_ai_slots_host(runner):
    """Pre-upgrade: read the AI slot secrets through the vault container so a
    missing `oss/codegen` is caught before the new server crash-loops on it."""
    rc, token, err = runner.compose(["exec", "-T", "datris", "cat", "/vault-token/token"])
    token = token.strip()
    if rc != 0 or not token:
        return result("vault.ai_slots", "skip", "could not read the server's Vault token (datris container not running)")
    problems, fixes, fine = [], [], []
    for label, name in AI_SLOT_SECRETS:
        # The vault image's CLI defaults to https://127.0.0.1:8200; the
        # compose listener is plain HTTP, so point it explicitly.
        rc, out, err = runner.compose([
            "exec", "-T", "-e", f"VAULT_TOKEN={token}", "-e", "VAULT_ADDR=http://127.0.0.1:8200",
            "vault", "vault", "kv", "get", "-format=json", f"secret/{name}",
        ])
        if rc != 0 and "No value found" not in (out + err):
            return result("vault.ai_slots", "skip", f"vault CLI failed inside the container: {(err or out).strip()[:160]}")
        if rc != 0:
            problems.append(f"Vault secret `{name}` ({label}) missing" + ("; the server will refuse to start" if label == "ai-primary" else ""))
            fixes.append(f"vault kv put secret/{name} provider=<anthropic|openai|azure|bedrock|grok|ollama> endpoint=<url> model=<model> apiKey=<key>")
            continue
        try:
            data = json.loads(out).get("data", {}).get("data", {}) or {}
        except (json.JSONDecodeError, AttributeError):
            data = {}
        provider = (data.get("provider") or "").strip().lower()
        missing = [f for f in ("provider", "model") if not (data.get(f) or "").strip()]
        if not (data.get("endpoint") or "").strip() and provider != "bedrock":
            missing.append("endpoint")
        if missing:
            problems.append(f"secret `{name}` ({label}) is missing " + ", ".join(f"'{m}'" for m in missing))
            fixes.append(f"vault kv patch secret/{name} " + " ".join(f"{m}=<value>" for m in missing))
        else:
            fine.append(f"{label} ({provider}/{data.get('model')})")
    if problems:
        return result("vault.ai_slots", "error", "; ".join(problems), "; ".join(fixes))
    return result("vault.ai_slots", "ok", ", ".join(fine))


# ------------------------------------------------------------ orchestration

def _timed(fn, *args, **kwargs):
    start = time.time()
    try:
        r = fn(*args, **kwargs)
    except Exception as e:  # a doctor bug must never take the report down
        name = getattr(fn, "__name__", "check").replace("check_", "").replace("_", ".", 1)
        r = result(name, "error", f"check failed: {e.__class__.__name__}: {e}", "This is a doctor bug — report it with the CLI output.")
    r["ms"] = int((time.time() - start) * 1000)
    return r


def run_host_checks(runner, mcp_url, version_info=None, pre_upgrade=False):
    """Every host-side check, in report order. Without Docker on PATH every
    check is a skip that says so."""
    if not runner.has_docker():
        ids = ["volumes.anonymous", "volumes.dangling", "vault.hcl_drift", "env.not_forwarded", "env.container_drift",
               "compose.orphans", "build.stale_jar", "disk.usage"]
        if pre_upgrade:
            ids.append("vault.ai_slots")
        rows = [result(i, "skip", "docker not on PATH — run `datris doctor` on the machine running Docker") for i in ids]
        if not pre_upgrade:
            rows.append(_timed(check_mcp_reachable, mcp_url))
        return rows
    rows = [
        _timed(check_volumes_anonymous, runner),
        _timed(check_volumes_dangling, runner),
        _timed(check_vault_hcl_drift, runner),
        _timed(check_env_not_forwarded, runner),
        _timed(check_env_container_drift, runner),
        _timed(check_compose_orphans, runner),
        _timed(check_disk_usage_host, runner),
    ]
    if pre_upgrade:
        rows.append(_timed(check_vault_ai_slots_host, runner))
    else:
        rows.append(_timed(check_build_stale_jar, runner, version_info))
        rows.append(_timed(check_mcp_reachable, mcp_url))
    return rows


def fetch_server(datris_url, api_key, cli_version, probes="", timeout=60):
    """Return (doctor_report, version_info, error). `error` is a short reason
    when the server could not be reached; an auth failure comes back as a
    report-shaped error row instead (the server IS reachable)."""
    import requests
    headers = {"x-api-key": api_key} if api_key else {}
    params = {"cli": cli_version}
    if probes:
        params["probes"] = probes
    try:
        resp = requests.get(f"{datris_url}/api/v1/doctor", headers=headers, params=params, timeout=timeout)
    except requests.RequestException as e:
        return None, None, f"{e.__class__.__name__}: {e}"
    version_info = None
    try:
        v = requests.get(f"{datris_url}/api/v1/version", headers=headers, timeout=10)
        if v.status_code == 200:
            version_info = v.json()
    except (requests.RequestException, ValueError):
        pass
    if resp.status_code in (401, 403):
        report = {
            "checks": [result(
                "server.auth", "error",
                f"server at {datris_url} rejected the request (HTTP {resp.status_code})",
                "Set DATRIS_API_KEY to a key holding config:read (Configuration → API-Keys) and re-run.",
                surface="server",
            )]
        }
        return report, version_info, None
    if resp.status_code != 200:
        report = {"checks": [result("server.doctor", "error", f"GET /api/v1/doctor returned HTTP {resp.status_code}: {resp.text[:200]}",
                                    "Upgrade the server — this check needs a release that ships the doctor endpoint.", surface="server")]}
        return report, version_info, None
    try:
        return resp.json(), version_info, None
    except ValueError:
        return None, version_info, "server returned a non-JSON doctor response"


def merge_report(server_report, host_checks, cli_version, mode="full", server_error=None, datris_url=""):
    checks = []
    surface = {"cli": cli_version}
    if server_report:
        surface.update(server_report.get("surface") or {})
        checks.extend(server_report.get("checks") or [])
    elif server_error is not None:
        checks.append(result(
            "server.reachable", "error",
            f"Datris server at {datris_url} unreachable ({server_error}); server-side checks skipped",
            "docker compose ps datris   # then `docker compose logs datris`; set DATRIS_URL if the server is elsewhere",
            surface="cli",
        ))
    checks.extend(host_checks)
    summary = {s: sum(1 for c in checks if c.get("status") == s) for s in ("ok", "warn", "error", "skip")}
    return {
        "doctorVersion": DOCTOR_VERSION,
        "ranAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "mode": mode,
        "surface": surface,
        "summary": summary,
        "checks": checks,
    }


def exit_code(report, server_unreachable=False):
    """0 all ok, 1 any warn, 2 any error, 3 server unreachable (server checks skipped)."""
    if server_unreachable:
        return 3
    statuses = {c.get("status") for c in report.get("checks", [])}
    if "error" in statuses:
        return 2
    if "warn" in statuses:
        return 1
    return 0


def render_human(report):
    lines = []
    width = max((len(c["id"]) for c in report.get("checks", [])), default=10)
    for c in report.get("checks", []):
        icon = STATUS_ICON.get(c.get("status"), "?")
        lines.append(f"  {icon} {c['id'].ljust(width)}  {c.get('detail', '')}")
        if c.get("status") in ("warn", "error") and c.get("remediation"):
            lines.append(f"      ↳ {c['remediation']}")
    s = report.get("summary", {})
    surface = report.get("surface", {})
    versions = ", ".join(f"{k} {v}" for k, v in surface.items())
    lines.append("")
    lines.append(f"  {s.get('ok', 0)} ok, {s.get('warn', 0)} warn, {s.get('error', 0)} error, {s.get('skip', 0)} skipped   ({versions})")
    return "\n".join(lines)
