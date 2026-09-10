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
    host_only = [row for row in rows if row["id"] != "mcp.reachable"]
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
