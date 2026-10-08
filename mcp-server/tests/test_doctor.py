"""Host-side doctor checks against a faked subprocess runner.

Pins: the rule each check fires on, its status, the remediation text, exit
codes, and that secret VALUES never reach the report (only key names)."""
import json
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import doctor as doc  # noqa: E402


class FakeRunner(doc.Runner):
    """Answers subprocess calls from a table keyed by the joined argv.
    Unknown commands fail like a missing container would."""

    def __init__(self, responses, project_dir, compose_file=None, docker=True):
        super().__init__(compose_file=compose_file, project_dir=project_dir)
        self.responses = responses
        self.docker = docker
        self.calls = []

    def has_docker(self):
        return self.docker

    def run(self, args, timeout=60, input_text=None):
        key = " ".join(args)
        self.calls.append(key)
        for prefix, resp in self.responses.items():
            if key == prefix or key.startswith(prefix + " ") or key.endswith(" " + prefix):
                if isinstance(resp, tuple):
                    return resp
                return 0, resp, ""
        return 1, "", f"no fake for: {key}"


ANON = "a" * 64


def mounts(*entries):
    return json.dumps([{"Type": "volume", "Name": n, "Destination": d} for n, d in entries])


# 8. volumes.anonymous
def test_volumes_anonymous_flags_64_hex_volume(tmp_path):
    r = FakeRunner({
        "docker inspect -f {{json .Mounts}} postgres": mounts((ANON, "/var/lib/postgresql/data")),
        "docker inspect -f {{json .Mounts}} mongodb": mounts(("datris_mongodb-data", "/data/db")),
    }, str(tmp_path))
    res = doc.check_volumes_anonymous(r)
    assert res["status"] == "error"
    assert "postgres" in res["detail"]
    assert "mongodb" not in res["detail"]
    assert "cp -a /src/. /dst/" in res["remediation"]


def test_volumes_anonymous_ok_when_named(tmp_path):
    r = FakeRunner({
        "docker inspect -f {{json .Mounts}} postgres": mounts(("datris_postgres-data", "/var/lib/postgresql/data")),
    }, str(tmp_path))
    res = doc.check_volumes_anonymous(r)
    assert res["status"] == "ok"
    assert "postgres" in res["detail"]


def test_volumes_anonymous_ignores_non_data_mounts(tmp_path):
    # The vault image declares VOLUME /vault/logs — anonymous by design, not data.
    r = FakeRunner({
        "docker inspect -f {{json .Mounts}} vault": mounts(("datris_vault-data", "/vault/file"), (ANON, "/vault/logs")),
    }, str(tmp_path))
    assert doc.check_volumes_anonymous(r)["status"] == "ok"


def test_mcp_reachable_treats_auth_challenge_as_up(monkeypatch):
    import requests

    class Resp:
        def __init__(self, code):
            self.status_code = code

        def close(self):
            pass

    monkeypatch.setattr(requests, "get", lambda *a, **k: Resp(401))
    assert doc.check_mcp_reachable("http://x/sse")["status"] == "ok"
    monkeypatch.setattr(requests, "get", lambda *a, **k: Resp(502))
    assert doc.check_mcp_reachable("http://x/sse")["status"] == "error"

    def boom(*a, **k):
        raise requests.ConnectionError("refused")

    monkeypatch.setattr(requests, "get", boom)
    res = doc.check_mcp_reachable("http://x/sse")
    assert res["status"] == "error" and "docker compose ps mcp-server" in res["remediation"]


def test_volumes_dangling_recognizes_postgres_dir(tmp_path):
    r = FakeRunner({
        "docker volume ls -qf dangling=true": ANON + "\nnamed-vol\n",
        f"docker run --rm -v {ANON}:/v:ro alpine ls -A /v": "PG_VERSION\nbase\nglobal\n",
    }, str(tmp_path))
    res = doc.check_volumes_dangling(r)
    assert res["status"] == "warn"
    assert "postgres data dir" in res["detail"]
    # named dangling volumes (a disabled service's data) are not probed
    assert not any("named-vol" in c for c in r.calls)


# 9. env.not_forwarded
def test_env_not_forwarded_warns_on_unreferenced_key(tmp_path):
    (tmp_path / ".env").write_text("DATRIS_ENV=production\nOPENAI_API_KEY=sk-secret\nCOMPOSE_PROFILES=kafka\nEMPTY=\n")
    (tmp_path / "docker-compose.yml").write_text("services:\n  datris:\n    environment:\n      OPENAI_API_KEY: ${OPENAI_API_KEY:-}\n")
    r = FakeRunner({}, str(tmp_path))
    res = doc.check_env_not_forwarded(r)
    assert res["status"] == "warn"
    assert "DATRIS_ENV" in res["detail"]
    assert "OPENAI_API_KEY" not in res["detail"]
    assert "COMPOSE_PROFILES" not in res["detail"]
    assert "sk-secret" not in json.dumps(res)
    assert "build-standalone-compose.py" in res["remediation"]


def test_env_not_forwarded_ok_when_referenced(tmp_path):
    (tmp_path / ".env").write_text("DATRIS_ENV=production\n")
    (tmp_path / "docker-compose.yml").write_text('services:\n  datris:\n    environment:\n      DATRIS_ENV: "${DATRIS_ENV:-}"\n')
    assert doc.check_env_not_forwarded(FakeRunner({}, str(tmp_path)))["status"] == "ok"


def test_env_not_forwarded_ignores_update_check_switch(tmp_path):
    # DATRIS_UPDATE_CHECK is read by the doctor CLI from .env, not by a container.
    (tmp_path / ".env").write_text("DATRIS_UPDATE_CHECK=0\n")
    (tmp_path / "docker-compose.yml").write_text("services:\n  datris:\n    image: x\n")
    assert doc.check_env_not_forwarded(FakeRunner({}, str(tmp_path)))["status"] == "ok"


# 10. env.container_drift
def _compose_config(env):
    return json.dumps({"services": {"datris": {"container_name": "datris", "environment": env}}})


def test_env_container_drift_credential_key_is_error_and_value_hidden(tmp_path):
    r = FakeRunner({
        "docker compose config --format json": _compose_config({"MONGO_PASSWORD": "new-secret", "USEUSERAUTH": "false"}),
        "docker inspect -f {{json .Config.Env}} datris": json.dumps(["MONGO_PASSWORD=old-secret", "USEUSERAUTH=false", "PATH=/usr/bin"]),
    }, str(tmp_path))
    res = doc.check_env_container_drift(r)
    assert res["status"] == "error"
    assert "MONGO_PASSWORD" in res["detail"]
    assert "USEUSERAUTH" not in res["detail"]
    assert "force-recreate --no-deps datris" in res["remediation"]
    assert "old-secret" not in json.dumps(res) and "new-secret" not in json.dumps(res)


def test_env_container_drift_non_credential_is_warn_and_match_is_ok(tmp_path):
    r = FakeRunner({
        "docker compose config --format json": _compose_config({"TAPMAXOUTPUTMB": "200"}),
        "docker inspect -f {{json .Config.Env}} datris": json.dumps(["TAPMAXOUTPUTMB=100"]),
    }, str(tmp_path))
    assert doc.check_env_container_drift(r)["status"] == "warn"
    r = FakeRunner({
        "docker compose config --format json": _compose_config({"TAPMAXOUTPUTMB": "100", "PASSTHROUGH": None}),
        "docker inspect -f {{json .Config.Env}} datris": json.dumps(["TAPMAXOUTPUTMB=100"]),
    }, str(tmp_path))
    assert doc.check_env_container_drift(r)["status"] == "ok"


def test_env_container_drift_covers_the_codegen_runner(tmp_path):
    """datris-codegen-runner shares the tap runner's token: a stale TAP_RUNNER_TOKEN
    there is a credential drift like anywhere else."""
    assert "datris-codegen-runner" in doc.ENV_DRIFT_SERVICES
    config = json.dumps({"services": {"datris-codegen-runner": {
        "container_name": "datris-codegen-runner",
        "environment": {"TAP_RUNNER_TOKEN": "new-token", "CODEGEN_SCRATCH_DIR": "/scratch"}}}})
    r = FakeRunner({
        "docker compose config --format json": config,
        "docker inspect -f {{json .Config.Env}} datris-codegen-runner":
            json.dumps(["TAP_RUNNER_TOKEN=old-token", "CODEGEN_SCRATCH_DIR=/scratch"]),
    }, str(tmp_path))
    res = doc.check_env_container_drift(r)
    assert res["status"] == "error"
    assert "datris-codegen-runner" in res["detail"] and "TAP_RUNNER_TOKEN" in res["detail"]
    assert "force-recreate --no-deps datris-codegen-runner" in res["remediation"]
    assert "old-token" not in json.dumps(res) and "new-token" not in json.dumps(res)
    r = FakeRunner({
        "docker compose config --format json": config,
        "docker inspect -f {{json .Config.Env}} datris-codegen-runner":
            json.dumps(["TAP_RUNNER_TOKEN=new-token", "CODEGEN_SCRATCH_DIR=/scratch"]),
    }, str(tmp_path))
    res = doc.check_env_container_drift(r)
    assert res["status"] == "ok" and "datris-codegen-runner" in res["detail"]


def test_compose_orphans_handles_both_ps_json_shapes(tmp_path):
    for ps in (
        json.dumps([{"Service": "datris"}, {"Service": "ollama"}]),
        '{"Service": "datris"}\n{"Service": "ollama"}\n',
    ):
        r = FakeRunner({
            "docker compose config --services": "datris\nmongodb\n",
            "docker compose ps -a --format json": ps,
        }, str(tmp_path))
        res = doc.check_compose_orphans(r)
        assert res["status"] == "warn"
        assert "ollama" in res["detail"]
        assert res["remediation"] == "docker compose up -d --remove-orphans"


def test_vault_hcl_drift_uses_container_start_vs_file_mtime(tmp_path):
    hcl = tmp_path / "docker" / "vault.hcl"
    hcl.parent.mkdir()
    hcl.write_text('max_lease_ttl = "87600h"\n')
    os.utime(hcl, (1_800_000_000, 1_800_000_000))  # 2027 — after the container started
    r = FakeRunner({
        "docker inspect -f {{.State.StartedAt}} vault": "2026-09-01T10:00:00.123456789Z\n",
        "docker compose exec -T vault cat /vault/config/vault.hcl": 'max_lease_ttl = "87600h"\n',
    }, str(tmp_path))
    res = doc.check_vault_hcl_drift(r)
    assert res["status"] == "warn"
    assert "changed after the vault container started" in res["detail"]
    assert "--force-recreate vault" in res["remediation"]
    os.utime(hcl, (1_700_000_000, 1_700_000_000))  # 2023 — before
    assert doc.check_vault_hcl_drift(r)["status"] == "ok"
    r.responses["docker compose exec -T vault cat /vault/config/vault.hcl"] = 'max_lease_ttl = "768h"\n'
    assert doc.check_vault_hcl_drift(r)["status"] == "warn"


def test_build_stale_jar_compares_git_head(tmp_path):
    (tmp_path / "docker-compose.yml").write_text("services:\n  datris:\n    build: .\n")
    (tmp_path / ".git").mkdir()
    r = FakeRunner({"git rev-parse HEAD": "abcdef1234567890\n"}, str(tmp_path))
    res = doc.check_build_stale_jar(r, {"gitHeadCommit": "1234567890abcdef", "builtAtMillis": "1700000000000"})
    assert res["status"] == "warn"
    assert "abcdef123456" in res["detail"]
    assert "sbt clean assembly" in res["remediation"]
    assert doc.check_build_stale_jar(r, {"gitHeadCommit": "abcdef1234567890", "builtAtMillis": str(2_000_000_000_000)})["status"] == "ok"
    (tmp_path / "docker-compose.yml").write_text("services:\n  datris:\n    image: datrisai/datris-server:latest\n    #build: .\n")
    assert doc.check_build_stale_jar(r, {"gitHeadCommit": "x"})["status"] == "skip"


def test_vault_ai_slots_host_flags_missing_codegen(tmp_path):
    good = json.dumps({"data": {"data": {"provider": "anthropic", "endpoint": "https://x", "model": "m", "apiKey": "k"}}})
    r = FakeRunner({
        "docker compose exec -T datris cat /vault-token/token": "hvs.token\n",
        "docker compose exec -T -e VAULT_TOKEN=hvs.token -e VAULT_ADDR=http://127.0.0.1:8200 vault vault kv get -format=json secret/oss/ai-primary": good,
        "docker compose exec -T -e VAULT_TOKEN=hvs.token -e VAULT_ADDR=http://127.0.0.1:8200 vault vault kv get -format=json secret/oss/embedding": good,
        "docker compose exec -T -e VAULT_TOKEN=hvs.token -e VAULT_ADDR=http://127.0.0.1:8200 vault vault kv get -format=json secret/oss/codegen": (2, "", "No value found at secret/data/oss/codegen"),
    }, str(tmp_path))
    res = doc.check_vault_ai_slots_host(r)
    assert res["status"] == "error"
    assert "oss/codegen" in res["detail"]
    assert "vault kv put secret/oss/codegen" in res["remediation"]
    # A vault CLI that cannot talk to Vault at all is a skip, not "everything missing".
    r.responses = {"docker compose exec -T datris cat /vault-token/token": "hvs.token\n"}
    assert doc.check_vault_ai_slots_host(r)["status"] == "skip"


# 11. docker missing → all host checks skip; skips don't affect the exit code
def test_no_docker_all_host_checks_skip(tmp_path, monkeypatch):
    r = FakeRunner({}, str(tmp_path), docker=False)
    monkeypatch.setattr(doc, "check_mcp_reachable", lambda url, timeout=3: doc.result("mcp.reachable", "ok", "up"))
    rows = doc.run_host_checks(r, "http://localhost:3000/sse")
    # version.update rides along with mcp.reachable in the no-docker branch
    # (plans/stories/doctor-update-check.md Step 3); without a server version it
    # is a skip of its own, not a "docker not on PATH" skip.
    assert "version.update" in [row["id"] for row in rows]
    host_only = [row for row in rows if row["id"] not in ("mcp.reachable", "version.update")]
    assert host_only and all(row["status"] == "skip" for row in host_only)
    assert all("docker not on PATH" in row["detail"] for row in host_only)
    report = doc.merge_report({"surface": {"server": "1.28.2"}, "checks": [doc.result("vault.token_ttl", "ok", "fine", surface="server")]}, rows, "1.28.2")
    assert doc.exit_code(report) == 0


# 12. exit codes
def test_exit_codes():
    ok = doc.merge_report({"checks": [doc.result("a", "ok", "")]}, [doc.result("b", "skip", "")], "1")
    warn = doc.merge_report({"checks": [doc.result("a", "warn", "", "fix")]}, [], "1")
    err = doc.merge_report({"checks": [doc.result("a", "ok", "")]}, [doc.result("b", "error", "", "fix")], "1")
    down = doc.merge_report(None, [doc.result("b", "ok", "")], "1", server_error="ConnectionError", datris_url="http://x")
    assert doc.exit_code(ok) == 0
    assert doc.exit_code(warn) == 1
    assert doc.exit_code(err) == 2
    assert doc.exit_code(down, server_unreachable=True) == 3
    assert down["checks"][0]["id"] == "server.reachable" and down["checks"][0]["status"] == "error"


# 13. report shape and pre-upgrade never touching the server
def test_merge_report_shape_and_summary():
    server = {"doctorVersion": 1, "surface": {"server": "1.28.2", "cli": "1.28.2"},
              "checks": [doc.result("vault.token_ttl", "warn", "ttl 28d", "fix", surface="server", ms=12)]}
    report = doc.merge_report(server, [doc.result("volumes.anonymous", "ok", "named")], "1.28.2")
    assert set(report) == {"doctorVersion", "ranAt", "mode", "surface", "summary", "checks"}
    assert report["summary"] == {"ok": 1, "warn": 1, "error": 0, "skip": 0}
    assert report["surface"] == {"cli": "1.28.2", "server": "1.28.2"}
    for c in report["checks"]:
        assert set(c) == {"id", "status", "severity", "detail", "remediation", "surface", "ms"}
    text = doc.render_human(report)
    assert "! vault.token_ttl" in text and "↳ fix" in text
    assert "✓ volumes.anonymous" in text
    assert "1 ok, 1 warn, 0 error, 0 skipped" in text


def test_pre_upgrade_runs_vault_slots_not_server_dependent_checks(tmp_path, monkeypatch):
    r = FakeRunner({}, str(tmp_path))
    called = []
    monkeypatch.setattr(doc, "check_mcp_reachable", lambda *a, **k: called.append("mcp") or doc.result("mcp.reachable", "ok", ""))
    rows = doc.run_host_checks(r, "http://localhost:3000/sse", pre_upgrade=True)
    ids = [row["id"] for row in rows]
    assert "vault.ai_slots" in ids
    assert "build.stale_jar" not in ids and "mcp.reachable" not in ids
    assert called == []


def test_parse_dotenv_strips_quotes_and_comments(tmp_path):
    p = tmp_path / ".env"
    p.write_text('# comment\nA=1\nB="two words"\nexport C=\'x\'\nbad line\n')
    assert doc.parse_dotenv(str(p)) == {"A": "1", "B": "two words", "C": "x"}


def test_parse_docker_time():
    assert doc._parse_docker_time("2026-09-01T10:00:00.123456789Z") == pytest.approx(1_788_256_800.123456, abs=1)
    assert doc._parse_docker_time("0001-01-01T00:00:00Z") is None


# 14. version.update — disclosed, opt-out update check
#     (plans/stories/doctor-update-check.md). The only check that leaves the
#     machine: one GET to datris.ai carrying version, os and arch.
class _UpdateResp:
    def __init__(self, code=200, body=None, text=None):
        self.status_code = code
        self._body = body
        self.text = text if text is not None else (json.dumps(body) if body is not None else "")

    def json(self):
        if self._body is None:
            raise ValueError("not json")
        return self._body

    def close(self):
        pass


def _no_request(*a, **k):
    raise AssertionError("requests.get must not be called: %r %r" % (a, k))


@pytest.fixture
def update_env(monkeypatch):
    """Process env with no DATRIS_UPDATE_CHECK set."""
    monkeypatch.delenv("DATRIS_UPDATE_CHECK", raising=False)
    return monkeypatch


def test_version_update_constants():
    assert doc.UPDATE_CHECK_ENV == "DATRIS_UPDATE_CHECK"
    assert doc.UPDATE_URL.startswith("https://get.datris.ai/version")


def test_version_update_off_via_process_env(update_env):
    import requests
    update_env.setattr(requests, "get", _no_request)
    for value in ("0", "false", "off", "no", "OFF"):
        update_env.setenv("DATRIS_UPDATE_CHECK", value)
        res = doc.check_version_update({"version": "1.43.0"}, {})
        assert res["id"] == "version.update"
        assert res["status"] == "skip", value
        assert "DATRIS_UPDATE_CHECK=0" in res["detail"]


def test_version_update_off_via_dotenv(update_env):
    import requests
    update_env.setattr(requests, "get", _no_request)
    res = doc.check_version_update({"version": "1.43.0"}, {"DATRIS_UPDATE_CHECK": "0"})
    assert res["status"] == "skip"
    assert "DATRIS_UPDATE_CHECK=0" in res["detail"]

    # process env wins: 1 in the environment overrides 0 in .env
    calls = []
    update_env.setattr(requests, "get", lambda *a, **k: calls.append((a, k)) or _UpdateResp(200, {"latest": "1.43.0"}))
    update_env.setenv("DATRIS_UPDATE_CHECK", "1")
    res = doc.check_version_update({"version": "1.43.0"}, {"DATRIS_UPDATE_CHECK": "0"})
    assert len(calls) == 1
    assert res["status"] == "ok"


def test_version_update_empty_process_env_does_not_override_dotenv(update_env):
    import requests
    update_env.setattr(requests, "get", _no_request)
    update_env.setenv("DATRIS_UPDATE_CHECK", "")
    res = doc.check_version_update({"version": "1.43.0"}, {"DATRIS_UPDATE_CHECK": "0"})
    assert res["status"] == "skip"
    assert "DATRIS_UPDATE_CHECK=0" in res["detail"]


def test_version_update_skips_without_server_version(update_env):
    import requests
    update_env.setattr(requests, "get", _no_request)
    res = doc.check_version_update(None, {})
    assert res["id"] == "version.update"
    assert res["status"] == "skip"
    assert "nothing sent" in res["detail"]


def test_version_update_network_failure_is_skip_not_error(update_env):
    import requests

    def raiser(exc):
        def get(*a, **k):
            raise exc
        return get

    fakes = [
        raiser(requests.ConnectionError("no route")),
        raiser(requests.Timeout("3s")),
        lambda *a, **k: _UpdateResp(500, None, text="oops"),
        lambda *a, **k: _UpdateResp(200, None, text="<html>not json</html>"),
        lambda *a, **k: _UpdateResp(200, {"unexpected": "shape"}),
        lambda *a, **k: _UpdateResp(200, {"latest": "not-a-version"}),
    ]
    for fake in fakes:
        update_env.setattr(requests, "get", fake)
        res = doc.check_version_update({"version": "1.43.0"}, {})
        assert res["id"] == "version.update"
        assert res["status"] == "skip", res
        assert res["status"] != "error"
        report = doc.merge_report({"checks": []}, [res], "1.43.0")
        assert doc.exit_code(report) == 0
    # the unreachable wording from the story
    update_env.setattr(requests, "get", fakes[0])
    assert "could not reach datris.ai" in doc.check_version_update({"version": "1.43.0"}, {})["detail"]


def test_version_update_reports_newer(update_env):
    import requests
    update_env.setattr(requests, "get", lambda *a, **k: _UpdateResp(200, {"latest": "1.44.0"}))
    res = doc.check_version_update({"version": "1.43.0"}, {})
    assert res["id"] == "version.update"
    assert res["status"] == "warn"
    assert "1.44.0" in res["detail"] and "1.43.0" in res["detail"]
    assert "docker compose pull" in res["remediation"]
    assert "datris doctor --pre-upgrade" in res["remediation"]

    for latest in ("1.43.0", "1.42.9"):
        update_env.setattr(requests, "get", lambda *a, _l=latest, **k: _UpdateResp(200, {"latest": _l}))
        res = doc.check_version_update({"version": "1.43.0"}, {})
        assert res["status"] == "ok", latest
        assert "1.43.0" in res["detail"]

    # integer-tuple comparison, not string: 1.10.0 is newer than 1.9.0
    update_env.setattr(requests, "get", lambda *a, **k: _UpdateResp(200, {"latest": "1.10.0"}))
    assert doc.check_version_update({"version": "1.9.0"}, {})["status"] == "warn"
    # a suffix on the running version is ignored
    update_env.setattr(requests, "get", lambda *a, **k: _UpdateResp(200, {"latest": "1.43.0"}))
    assert doc.check_version_update({"version": "1.43.0-SNAPSHOT"}, {})["status"] == "ok"


def test_version_update_sends_only_version_os_arch(update_env):
    import platform
    import requests
    calls = []

    def get(*a, **k):
        calls.append((a, k))
        return _UpdateResp(200, {"latest": "1.43.0"})

    update_env.setattr(requests, "get", get)
    update_env.setenv("DATRIS_API_KEY", "should-never-be-sent")
    doc.check_version_update({"version": "1.43.0"}, {"DATRIS_API_KEY": "nor-this"})
    assert len(calls) == 1, "exactly one request"
    args, kwargs = calls[0]
    url = args[0] if args else kwargs.get("url")
    assert url == doc.UPDATE_URL
    params = kwargs.get("params") or {}
    assert set(params) == {"version", "os", "arch"}
    assert params["version"] == "1.43.0"
    assert params["os"] == platform.system().lower()
    assert params["arch"] == platform.machine().lower()
    headers = kwargs.get("headers") or {}
    assert set(headers) == {"User-Agent"}, headers
    assert headers["User-Agent"].startswith("datris-doctor/")
    assert not any(h.lower() == "x-api-key" for h in headers)
    assert "should-never-be-sent" not in json.dumps(kwargs, default=str)
    assert "nor-this" not in json.dumps(kwargs, default=str)
    assert not any(k in kwargs for k in ("cookies", "data", "json", "auth"))
    assert kwargs.get("timeout") is not None and kwargs["timeout"] <= 3


def test_pre_upgrade_makes_no_update_request(tmp_path, monkeypatch):
    import requests
    r = FakeRunner({}, str(tmp_path))
    called = []
    monkeypatch.setattr(doc, "check_mcp_reachable", lambda *a, **k: doc.result("mcp.reachable", "ok", ""))
    monkeypatch.setattr(doc, "check_version_update", lambda *a, **k: called.append("update") or doc.result("version.update", "ok", ""))
    monkeypatch.setattr(requests, "get", _no_request)
    rows = doc.run_host_checks(r, "http://localhost:3000/sse", version_info={"version": "1.43.0"}, pre_upgrade=True, dotenv={})
    assert "version.update" not in [row["id"] for row in rows]
    assert called == []
    # same for the no-docker pre-upgrade branch
    r = FakeRunner({}, str(tmp_path), docker=False)
    rows = doc.run_host_checks(r, "http://localhost:3000/sse", version_info={"version": "1.43.0"}, pre_upgrade=True, dotenv={})
    assert "version.update" not in [row["id"] for row in rows]
    assert called == []


def test_full_mode_runs_version_update_with_dotenv(tmp_path, monkeypatch):
    r = FakeRunner({}, str(tmp_path))
    seen = []
    monkeypatch.setattr(doc, "check_mcp_reachable", lambda *a, **k: doc.result("mcp.reachable", "ok", ""))

    def fake_update(version_info, env, *a, **k):
        seen.append((version_info, env))
        return doc.result("version.update", "ok", "up to date (1.43.0)")

    monkeypatch.setattr(doc, "check_version_update", fake_update)
    vi = {"version": "1.43.0"}
    dotenv = {"DATRIS_UPDATE_CHECK": "0"}
    rows = doc.run_host_checks(r, "http://localhost:3000/sse", version_info=vi, dotenv=dotenv)
    ids = [row["id"] for row in rows]
    assert "version.update" in ids
    assert ids.index("version.update") > ids.index("mcp.reachable")
    assert seen == [(vi, dotenv)]
