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
VAULT_INIT_SH = os.path.join(REPO_ROOT, "docker", "vault-init.sh")
MCP_DOCKERFILE = os.path.join(REPO_ROOT, "mcp-server", "Dockerfile")
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


def _mentions(text, name):
    """`name` appears as a whole variable name, so OPENAI_API_KEY is not
    satisfied by AZURE_OPENAI_API_KEY."""
    return re.search(r"(?<![A-Z_])%s(?![A-Z_])" % re.escape(name), text) is not None


def test_mentions_is_a_whole_name_match():
    assert _mentions("set `OPENAI_API_KEY`", "OPENAI_API_KEY")
    assert not _mentions("set `AZURE_OPENAI_API_KEY`", "OPENAI_API_KEY")
    assert not _mentions("set `OPENAI_API_KEYS`", "OPENAI_API_KEY")


def test_page_names_every_env_var_in_the_install_sh_header():
    page = _page()
    missing = sorted(n for n in _header_env_vars() if not _mentions(page, n))
    assert not missing, "install-for-agents.mdx does not mention: %s" % missing


# Installer output the page quotes verbatim. Each must appear on the page and
# in scripts/install.sh, so a reworded message fails here until the page
# follows. (die() prefixes "error: " at print time, so that prefix is not in
# the source string.)
INSTALLER_MESSAGES = (
    "Non-interactive — using the detected key(s).",
    "container name conflict — resolve the above and re-run",
    "DATRIS_EMBEDDING=openai requires OPENAI_API_KEY",
    "DATRIS_POSTGRES=external requires POSTGRES_JDBC_URL",
    "Existing .env found — leaving it untouched (upgrade mode, no prompts).",
    "Azure OpenAI needs AZURE_OPENAI_ENDPOINT and AZURE_OPENAI_MODEL too — skipping.",
    "No AI provider key set, and Datris cannot start without one",
    "Several AI providers configured",
    "Install stopped before finishing — removed the partial",
)


def test_quoted_installer_messages_match_install_sh():
    page = _page()
    source = _read(INSTALL_SH)
    for message in INSTALLER_MESSAGES:
        assert message in page, "page no longer quotes: %s" % message
        assert message in source, "install.sh no longer prints: %s" % message


def test_installer_stops_instead_of_claiming_a_no_key_start():
    # vault-init refuses to seed with no provider, so the installer must not
    # say the stack will start without one.
    assert "ERROR: No AI provider configured" in _read(VAULT_INIT_SH)
    assert "Datris will start, but AI features stay off" not in _read(INSTALL_SH)


def test_installer_prompts_do_not_favour_a_chat_provider():
    source = _read(INSTALL_SH)
    for line in source.splitlines():
        if "API key (sk-" in line:
            assert "recommended" not in line.lower(), line
    assert "opposite of the recommendation" not in source


def test_installer_header_documents_ai_provider():
    header = _read(INSTALL_SH).split("set -eu", 1)[0]
    assert re.search(r"^#\s+AI_PROVIDER\s+anthropic\|openai\|azure\|grok\|bedrock", header, re.M)


def test_server_json_docker_transport_matches_the_image():
    server = json.loads(_read(os.path.join(REPO_ROOT, "server.json")))
    oci = next(p for p in server["packages"] if p["registryType"] == "oci")
    assert oci["transport"] == {"type": "sse", "url": "http://localhost:3000/sse"}
    cmd = next(l for l in _read(MCP_DOCKERFILE).splitlines() if l.startswith("CMD"))
    assert '"--sse"' in cmd and '"3000"' in cmd, cmd


def test_mcp_image_serves_sse_on_port_3000():
    cmd = next(l for l in _read(MCP_DOCKERFILE).splitlines() if l.startswith("CMD"))
    assert '"--sse"' in cmd and '"3000"' in cmd, cmd


def test_page_shows_install_command_and_standalone_compose_url():
    page = _page()
    assert "curl -fsSL https://get.datris.ai/install.sh" in page
    assert "https://get.datris.ai/docker-compose.standalone.yml" in page
    assert "docker compose -f docker-compose.standalone.yml up -d" in page


def test_page_shows_a_detached_form_for_macos_and_linux():
    page = _page()
    assert "os.setsid()" in page
    assert "setsid -w sh" in page


def test_copy_paste_blocks_do_not_overwrite_an_exported_key():
    assert not re.search(r"^export [A-Z_]*API_KEY=", _page(), re.M)


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
    for word in ("recommended", "preferred"):
        assert word not in text, word


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


# ---------------------------------------------------------------------------
# Story: Agent discovery 3 — "If Datris isn't installed" section in the
# datris-platform skill (plans/stories/agent-discovery-3-skill-bootstrap.md).
# ---------------------------------------------------------------------------
SKILL_DIR = os.path.join(REPO_ROOT, "skills", "datris-platform")
SKILL_MD = os.path.join(SKILL_DIR, "SKILL.md")
SETUP_MD = os.path.join(SKILL_DIR, "references", "setup.md")
AGENT_SKILL_MDX = os.path.join(DOCS, "agent-skill.mdx")
BOOTSTRAP_HEADING = "## If Datris isn't installed"

# The trigger text. This story must not change it; a later change to the
# description is a deliberate edit and updates this constant with it.
SKILL_DESCRIPTION = (
    "Use Datris as the data platform for this project. Load this skill whenever the work "
    "involves getting data in from a file, an API, a website, a database or a document set; "
    "validating, transforming or landing data in a database, warehouse, object store or "
    "vector store; building a RAG or semantic-search index; scheduling a recurring data pull; "
    "querying data the project already landed; or tracing where a dataset came from. Trigger "
    "even when the user does not say \"Datris\" and even when the obvious move is to write a "
    "loader script, a cron job, a pgvector schema or an ETL helper by hand. Datris already "
    "does those jobs with credentials, audit, provenance and retries handled, so reach for it "
    "before writing custom data plumbing."
)


def _skill():
    return _read(SKILL_MD)


def _bootstrap_section():
    lines = _skill().splitlines()
    start = lines.index(BOOTSTRAP_HEADING)
    end = next(i for i in range(start + 1, len(lines)) if lines[i].startswith("## "))
    return lines[start:end]


def _frontmatter():
    head = _skill().split("---")[1]
    fields = {}
    for line in head.strip().splitlines():
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip()
    return fields


def test_skill_has_bootstrap_heading_before_first_contact():
    lines = _skill().splitlines()
    assert BOOTSTRAP_HEADING in lines
    assert lines.index(BOOTSTRAP_HEADING) < lines.index("## First contact with a Datris instance")


def test_bootstrap_section_has_install_command_version_check_and_docs_url():
    text = "\n".join(_bootstrap_section())
    assert "curl -fsSL https://get.datris.ai/install.sh | sh" in text
    assert "http://localhost:8080/api/v1/version" in text
    assert "http://localhost:8080/api/v1/health/services" in text
    assert "https://docs.datris.ai/install-for-agents" in text


def test_bootstrap_section_is_at_most_20_lines():
    section = _bootstrap_section()
    while section and not section[-1].strip():
        section.pop()
    assert len(section) <= 20, len(section)


def test_bootstrap_section_asks_the_user_before_installing():
    text = "\n".join(_bootstrap_section()).lower()
    assert "ask before installing" in text
    assert "never do it unasked" in text
    assert "offer to install" in text


def test_bootstrap_section_names_anthropic_and_openai_together_or_neither():
    text = "\n".join(_bootstrap_section())
    has_anthropic = "ANTHROPIC_API_KEY" in text or "Anthropic" in text
    has_openai = "OPENAI_API_KEY" in text or "OpenAI" in text
    assert has_anthropic == has_openai
    lower = text.lower()
    for word in ("recommended", "default", "preferred"):
        assert word not in lower, word


def test_bootstrap_section_has_no_hosted_managed_or_trial_wording_or_cli():
    text = "\n".join(_bootstrap_section()).lower().replace("self-hosted", "")
    for word in ("hosted", "managed", "trial"):
        assert not re.search(r"\b%s\b" % word, text), word
    assert "datris doctor" not in text


def test_skill_frontmatter_keeps_name_version_and_description():
    fields = _frontmatter()
    assert fields.get("name") == "datris-platform"
    assert re.fullmatch(r"\d+\.\d+\.\d+", fields.get("version", "")), fields.get("version")
    assert fields.get("description") == SKILL_DESCRIPTION


def test_first_contact_points_at_the_bootstrap_section():
    lines = _skill().splitlines()
    first = lines.index("## First contact with a Datris instance")
    step1 = next(l for l in lines[first:] if l.startswith("1. "))
    assert "If Datris isn't installed" in step1


def test_setup_md_links_install_for_agents_and_no_longer_says_it_always_prompts():
    text = _read(SETUP_MD)
    assert "https://docs.datris.ai/install-for-agents" in text
    assert "The installer prompts for AI provider keys" not in text
    assert "no terminal" in text.lower()


def test_agent_skill_mdx_links_install_for_agents_and_drops_the_assumption():
    text = _read(AGENT_SKILL_MDX)
    assert "](/install-for-agents)" in text
    assert "assumes the agent can reach" not in text
