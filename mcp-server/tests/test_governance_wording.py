"""Story: governance controls — say "switched on for production", give one
switch to do it, and have doctor report it
(plans/stories/governance-controls-production-preset.md).

File-content guards:
- scripts/install.sh documents DATRIS_GOVERNED in its header and its
  fresh-install block writes USE_USER_AUTH, USE_API_KEYS, USE_AUDIT_LOG and
  USE_AGENT_POLICY as `true`, only under that option.
- docker-compose.yml (and the standalone file and application.yaml) keep all
  four defaults `false` — the decided direction.
- docs/doctor.mdx has a governance.controls row among the server checks.
- docs no longer call the off-by-default controls "governed by default" /
  "governed defaults".
- No docs page or the login page tells the operator to log in with a blank or
  no password: the server seeds `admin` with a bootstrap password it logs once
  (StartupRunner "Bootstrap login"), and added users get a temporary password
  (AuthAPIController.createUser)."""
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
INSTALL_SH = os.path.join(REPO_ROOT, "scripts", "install.sh")
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
COMPOSE_STANDALONE = os.path.join(REPO_ROOT, "docker-compose.standalone.yml")
APPLICATION_YAML = os.path.join(REPO_ROOT, "datrisserver", "src", "main", "resources", "application.yaml")
LOGIN_HTML = os.path.join(REPO_ROOT, "ui", "src", "app", "login", "login.component.html")

GOVERNANCE_VARS = ("USE_USER_AUTH", "USE_API_KEYS", "USE_AUDIT_LOG", "USE_AGENT_POLICY")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _install_lines():
    return _read(INSTALL_SH).splitlines()


def _header_block():
    lines = _install_lines()
    start = next(i for i, line in enumerate(lines) if "Honors these env vars" in line)
    end = next(i for i, line in enumerate(lines) if line.strip() == "set -eu")
    return "\n".join(lines[start + 1:end])


def _fresh_install_start():
    """Index of the `FRESH_ENV=1` line inside the `else` of the existing-.env
    check — the start of the fresh-install branch."""
    lines = _install_lines()
    return next(i for i, line in enumerate(lines) if line.strip() == "FRESH_ENV=1")


def _set_env_lines(var):
    pattern = re.compile(r"\bset_env\s+[\"']?" + var + r"[\"']?\s+[\"']?true[\"']?")
    return [i for i, line in enumerate(_install_lines()) if pattern.search(line)]


def _enclosing_frames(index):
    """The `if` / function-definition lines enclosing line `index` in
    install.sh, plus the line itself (for `[ ... ] && set_env ...` and
    one-line `if ...; then ...; fi`)."""
    lines = _install_lines()
    stack = []
    for line in lines[:index]:
        s = line.strip()
        if s.startswith("#"):
            continue
        opens_if = re.match(r"^(if|elif)\b", s) is not None
        if re.match(r"^if\b", s):
            stack.append(("if", s))
        elif re.match(r"^elif\b", s) and stack:
            stack[-1] = ("if", stack[-1][1] + " ELIF " + s)
        if re.match(r"^[A-Za-z_][A-Za-z0-9_]*\s*\(\)\s*\{\s*$", s):
            stack.append(("fn", s))
        closes_if = s == "fi" or s.startswith("fi ") or s.startswith("fi;") or (opens_if and re.search(r";\s*fi\b", s))
        if closes_if and stack and stack[-1][0] == "if":
            stack.pop()
        if s == "}" and stack and stack[-1][0] == "fn":
            stack.pop()
    return [frame for _, frame in stack] + [lines[index].strip()]


def test_install_sh_header_documents_datris_governed_and_the_fresh_install_block_writes_all_four_variables():
    assert "DATRIS_GOVERNED" in _header_block(), "the 'Honors these env vars' header must document DATRIS_GOVERNED"
    fresh = _fresh_install_start()
    for var in GOVERNANCE_VARS:
        hits = _set_env_lines(var)
        assert hits, "install.sh must `set_env %s true` in the fresh-install block" % var
        assert all(i > fresh for i in hits), "%s is written outside the fresh-install branch (lines %s)" % (var, hits)


def test_install_sh_does_not_write_the_four_variables_when_datris_governed_is_unset():
    for var in GOVERNANCE_VARS:
        hits = _set_env_lines(var)
        assert hits, "install.sh has no `set_env %s true` to guard" % var
        for i in hits:
            frames = _enclosing_frames(i)
            assert any("gov" in f.lower() for f in frames), (
                "`set_env %s true` (line %d) is not gated on DATRIS_GOVERNED / the governed prompt: %s" % (var, i + 1, frames)
            )
    # Nothing else writes them: no other set_env, echo or printf of the four.
    for n, line in enumerate(_install_lines(), start=1):
        s = line.strip()
        if s.startswith("#"):
            continue
        for var in GOVERNANCE_VARS:
            if re.search(r"\bset_env\s+[\"']?" + var + r"\b", s) and not re.search(r"\bset_env\s+[\"']?" + var + r"[\"']?\s+[\"']?true", s):
                raise AssertionError("line %d writes %s with a value other than true: %s" % (n, var, s))
            if re.search(var + r"=\S", s) and re.search(r"(>>|>)\s*\"?\$?\{?ENV_FILE", s):
                raise AssertionError("line %d writes %s straight into .env: %s" % (n, var, s))


def test_docker_compose_still_defaults_the_four_variables_to_false():
    for path in (COMPOSE, COMPOSE_STANDALONE):
        text = _read(path)
        for var in GOVERNANCE_VARS:
            refs = re.findall(r"\$\{" + var + r"(:-[^}]*)?\}", text)
            assert refs, "%s no longer references %s" % (os.path.basename(path), var)
            assert all(r == ":-false" for r in refs), "%s: %s must default to false, got %s" % (os.path.basename(path), var, refs)
        assert re.search(r"REQUIRE_API_KEY:\s*\"?\$\{USE_API_KEYS:-false\}", text), (
            "%s: the MCP server's REQUIRE_API_KEY must still follow USE_API_KEYS, default false" % os.path.basename(path)
        )
    yaml = _read(APPLICATION_YAML)
    for key in ("useUserAuth", "useApiKeys", "useAuditLog", "useAgentPolicy"):
        assert re.search(r"^" + key + r':\s*"?false"?\s*$', yaml, re.M), "application.yaml: %s must stay false" % key


def test_docs_doctor_mdx_has_a_governance_controls_row():
    text = _read(os.path.join(DOCS, "doctor.mdx"))
    rows = [line for line in text.splitlines() if line.startswith("| `governance.controls`")]
    assert len(rows) == 1, "docs/doctor.mdx 'The checks' table needs exactly one governance.controls row"
    row = rows[0]
    assert "warn" in row.lower(), row
    for var in GOVERNANCE_VARS:
        assert var in row, "the row names %s: %s" % (var, row)
    lines = text.splitlines()
    gov = lines.index(row)
    cli_only = next(i for i, line in enumerate(lines) if line.startswith("| `volumes.anonymous`"))
    assert gov < cli_only, "governance.controls is a server check; it belongs above the CLI-only rows"


def test_mcp_server_and_quick_start_do_not_say_governed_by_default_or_governed_defaults():
    for name in ("mcp-server.mdx", "quick-start.mdx"):
        text = _read(os.path.join(DOCS, name)).lower()
        for phrase in ("governed by default", "governed defaults"):
            assert phrase not in text, "docs/%s still says %r" % (name, phrase)


_BLANK_PASSWORD_PATTERNS = (
    r"leave[^.\n]{0,40}\bblank\b",
    r"\bblank password\b",
    r"\bempty password\b",
    r"\bnull password\b",
    r"\bno password until\b",
    r"\bwithout a password\b",
    r"(admin`?|log ?in|first login|user)[^.\n]{0,30}\bwith (no|an empty|a blank|a null) password\b",
)

_FIRST_LOGIN_FILES = (
    os.path.join(DOCS, "quick-start.mdx"),
    os.path.join(DOCS, "user-auth.mdx"),
    os.path.join(DOCS, "configuration-reference.mdx"),
    LOGIN_HTML,
)


def test_quick_start_user_auth_configuration_reference_and_the_login_page_do_not_say_log_in_with_a_blank_or_no_password():
    found = []
    for path in _FIRST_LOGIN_FILES:
        for n, line in enumerate(_read(path).splitlines(), start=1):
            for pattern in _BLANK_PASSWORD_PATTERNS:
                if re.search(pattern, line, re.I):
                    found.append("%s:%d: %s" % (os.path.relpath(path, REPO_ROOT), n, line.strip()))
                    break
    assert not found, "first login is admin + the bootstrap password from the server log:\n" + "\n".join(found)


def test_first_login_wording_says_where_to_read_the_bootstrap_password():
    for name in ("quick-start.mdx", "user-auth.mdx", "configuration-reference.mdx"):
        text = _read(os.path.join(DOCS, name))
        assert "Bootstrap login" in text or "docker compose logs" in text, (
            "docs/%s must say the bootstrap password is in the server log" % name
        )
    html = _read(LOGIN_HTML)
    assert re.search(r"\b(server )?logs?\b", html, re.I), "the login page hint must point at the server log for the bootstrap password"
