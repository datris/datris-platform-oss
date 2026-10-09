"""Story: OIDC single sign-on 1 (plans/stories/oidc-sso-login.md), Acceptance
bullet "test_oidc_compose_docs.py passes".

Both compose files forward the OIDC settings into the datris service (so
`datris doctor`'s env.not_forwarded check passes and an operator can turn SSO
on from .env), and the three docs pages the story rewrites name them. Pure
file-content checks, same shape as test_payload_budget_text.py."""
import os
import re

import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
COMPOSE = os.path.join(REPO_ROOT, "docker-compose.yml")
COMPOSE_STANDALONE = os.path.join(REPO_ROOT, "docker-compose.standalone.yml")
USER_AUTH_MDX = os.path.join(REPO_ROOT, "docs", "user-auth.mdx")
SECURITY_MDX = os.path.join(REPO_ROOT, "docs", "production", "security-architecture.mdx")
CONFIG_REF_MDX = os.path.join(REPO_ROOT, "docs", "configuration-reference.mdx")

# compose key (relaxed binding onto oidc.* in application.yaml) -> .env variable
COMPOSE_LINES = {
    "OIDC_ENABLED": "OIDC_ENABLED",
    "OIDC_ISSUER": "OIDC_ISSUER",
    "OIDC_CLIENTID": "OIDC_CLIENT_ID",
    "OIDC_REDIRECTURI": "OIDC_REDIRECT_URI",
    "OIDC_SCOPES": "OIDC_SCOPES",
    "OIDC_USERNAMECLAIM": "OIDC_USERNAME_CLAIM",
    "OIDC_DEFAULTROLE": "OIDC_DEFAULT_ROLE",
}
ENV_VARS = sorted(set(COMPOSE_LINES.values()))


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _datris_service(text):
    """The `datris:` service block (up to the next top-level service)."""
    m = re.search(r"^  datris:\s*$", text, re.MULTILINE)
    assert m, "no datris service in compose file"
    rest = text[m.end():]
    nxt = re.search(r"^  [A-Za-z0-9_-]+:\s*$", rest, re.MULTILINE)
    return rest[: nxt.start()] if nxt else rest


# --------------------------------------------------------------- compose ---

@pytest.mark.parametrize("path", [COMPOSE, COMPOSE_STANDALONE], ids=["compose", "standalone"])
def test_compose_forwards_every_oidc_setting(path):
    block = _datris_service(_read(path))
    for key, var in COMPOSE_LINES.items():
        assert re.search(r"^\s+%s:\s*\"\$\{%s:-[^}]*\}\"" % (key, var), block, re.MULTILINE), \
            "%s: datris service missing %s: \"${%s:-...}\"" % (os.path.basename(path), key, var)


@pytest.mark.parametrize("path", [COMPOSE, COMPOSE_STANDALONE], ids=["compose", "standalone"])
def test_compose_oidc_defaults_keep_sso_off(path):
    block = _datris_service(_read(path))
    assert re.search(r'OIDC_ENABLED:\s*"\$\{OIDC_ENABLED:-false\}"', block), "OIDC must default to off"
    assert re.search(r'OIDC_USERNAMECLAIM:\s*"\$\{OIDC_USERNAME_CLAIM:-email\}"', block)
    assert re.search(r'OIDC_DEFAULTROLE:\s*"\$\{OIDC_DEFAULT_ROLE:-\}"', block), "default role must default to empty"


@pytest.mark.parametrize("path", [COMPOSE, COMPOSE_STANDALONE], ids=["compose", "standalone"])
def test_compose_oidc_lines_follow_useuserauth(path):
    block = _datris_service(_read(path))
    assert block.index("USEUSERAUTH:") < block.index("OIDC_ENABLED:")


def test_compose_never_carries_the_client_secret():
    # The client secret lives in Vault (oss/oidc), never in .env / compose.
    for path in (COMPOSE, COMPOSE_STANDALONE):
        assert "OIDC_CLIENT_SECRET" not in _read(path)
        assert "OIDC_CLIENTSECRET" not in _read(path)


# ------------------------------------------------------------------ docs ---

def test_user_auth_doc_has_sso_section_naming_every_setting():
    text = _read(USER_AUTH_MDX)
    m = re.search(r"^#+ Single sign-on \(OIDC\)\s*$", text, re.MULTILINE)
    assert m, 'docs/user-auth.mdx has no "Single sign-on (OIDC)" section'
    for var in ENV_VARS:
        assert var in text, "user-auth.mdx does not name %s" % var
    assert "oss/oidc" in text and "clientSecret" in text
    assert "/api/v1/auth/oidc/callback" in text, "redirect URI to register is not documented"
    assert re.search(r"MFA|multi-factor", text, re.IGNORECASE)


def test_configuration_reference_names_every_setting_and_the_secret():
    text = _read(CONFIG_REF_MDX)
    for var in ENV_VARS:
        assert var in text, "configuration-reference.mdx does not name %s" % var
    assert "oss/oidc" in text
    assert re.search(r"oidc\.(enabled|issuer|clientId)", text), "oidc.* keys not documented"


def test_security_architecture_drops_the_no_sso_bullet_and_names_oidc():
    text = _read(SECURITY_MDX)
    assert "No OIDC / SSO integration yet" not in text
    assert "OIDC_ENABLED" in text
    # MFA bullet reworded: comes from the identity provider through SSO.
    assert re.search(r"MFA[^\n]*identity provider|identity provider[^\n]*MFA", text, re.IGNORECASE)
