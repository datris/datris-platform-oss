"""Story: Agent discovery 2 — one docs page for installing Datris from an
agent (headless) (plans/stories/agent-discovery-2-install-for-agents-page.md).

File-content checks for docs/install-for-agents.mdx: it is in the docs
navigation under every "Connect your agent" group, names every environment
variable the installer's header documents (parsed from scripts/install.sh,
so a new variable fails here until the page is updated), shows the install
and standalone commands, the public health endpoints, the MCP connection
details and the skill link, and carries no hosted/managed/trial wording.
installation.mdx and README.md link to it."""
import json
import os
import re

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DOCS = os.path.join(REPO_ROOT, "docs")
PAGE = os.path.join(DOCS, "install-for-agents.mdx")
DOCS_JSON = os.path.join(DOCS, "docs.json")
INSTALL_SH = os.path.join(REPO_ROOT, "scripts", "install.sh")
INSTALLATION_MDX = os.path.join(DOCS, "installation.mdx")
README = os.path.join(REPO_ROOT, "README.md")


def _read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _page():
    return _read(PAGE)


def _groups(node, name):
    """Every navigation group dict named `name`, anywhere in docs.json."""
    found = []
    if isinstance(node, dict):
        if node.get("group") == name:
            found.append(node)
        for value in node.values():
            found.extend(_groups(value, name))
    elif isinstance(node, list):
        for value in node:
            found.extend(_groups(value, name))
    return found


def _header_env_vars():
    """Env var names from the install.sh header block ("Honors these env
    vars ..." up to `set -eu`). Slash-joined suffixes inherit the first
    name's prefix: SNOWFLAKE_ACCOUNT/USER -> SNOWFLAKE_ACCOUNT, SNOWFLAKE_USER."""
    lines = _read(INSTALL_SH).splitlines()
    start = next(i for i, line in enumerate(lines) if "Honors these env vars" in line)
    end = next(i for i, line in enumerate(lines) if line.strip() == "set -eu")
    block = "\n".join(lines[start + 1:end])
    names = set()
    for group in re.findall(r"[A-Z][A-Z0-9_]*(?:/[A-Z][A-Z0-9_]*)*", block):
        parts = [p for p in group.split("/") if p]
        if "_" not in parts[0]:
            continue
        prefix = parts[0].split("_", 1)[0] + "_"
        names.add(parts[0])
        for part in parts[1:]:
            names.add(part if part.startswith(prefix) else prefix + part)
    return names


def test_page_exists_and_is_listed_under_every_connect_your_agent_group():
    assert os.path.isfile(PAGE), "docs/install-for-agents.mdx is missing"
    groups = _groups(json.loads(_read(DOCS_JSON)), "Connect your agent")
    assert len(groups) >= 2, "expected the Connect your agent group in both navigation views"
    for group in groups:
        assert group["pages"][0] == "install-for-agents", group["pages"]


def test_page_has_frontmatter():
    head = _page().split("---")[1]
    for field in ("title:", "sidebarTitle:", "description:"):
        assert field in head, field


def test_header_parser_finds_the_known_variables():
    names = _header_env_vars()
    for expected in ("DATRIS_DIR", "ANTHROPIC_API_KEY", "OPENAI_API_KEY",
                     "SNOWFLAKE_USER", "DATABRICKS_CLIENT_SECRET", "DATRIS_NO_START"):
        assert expected in names, expected


def test_page_names_every_env_var_in_the_install_sh_header():
    page = _page()
    missing = sorted(n for n in _header_env_vars() if n not in page)
    assert not missing, "install-for-agents.mdx does not mention: %s" % missing


def test_page_shows_install_command_and_standalone_compose_url():
    page = _page()
    assert "curl -fsSL https://get.datris.ai/install.sh" in page
    assert "https://get.datris.ai/docker-compose.standalone.yml" in page
    assert "docker compose -f docker-compose.standalone.yml up -d" in page


def test_page_shows_a_detached_form_for_macos_and_linux():
    page = _page()
    assert "os.setsid()" in page
    assert "setsid -w sh" in page


def test_page_names_both_public_health_endpoints():
    page = _page()
    assert "/api/v1/version" in page
    assert "/api/v1/health/services" in page


def test_page_names_mcp_connection_details():
    page = _page()
    for needle in ("DATRIS_API_URL", "DATRIS_API_KEY", "datris-mcp-server",
                   "datrisai/datris-mcp-server", "http://localhost:3000/sse"):
        assert needle in page, needle


def test_page_links_to_agent_skill():
    assert "](/agent-skill)" in _page()


def test_page_has_no_hosted_managed_or_trial_wording():
    text = _page().lower().replace("self-hosted", "")
    for word in ("hosted", "managed", "trial"):
        assert not re.search(r"\b%s\b" % word, text), word


def test_page_does_not_favour_a_chat_provider():
    text = _page().lower()
    assert "recommended" not in text


def test_no_md_files_under_docs_and_no_mcp_mdx():
    bad = []
    for root, dirs, files in os.walk(DOCS):
        dirs[:] = [d for d in dirs if d != "node_modules"]
        for name in files:
            if name.endswith(".md") or name == "mcp.mdx":
                bad.append(os.path.relpath(os.path.join(root, name), REPO_ROOT))
    assert not bad, bad


def test_installation_and_readme_link_to_the_page():
    assert "](/install-for-agents)" in _read(INSTALLATION_MDX)
    assert "](https://docs.datris.ai/install-for-agents)" in _read(README)
