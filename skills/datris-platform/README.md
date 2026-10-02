# datris-platform skill

An agent skill that teaches a coding agent to use Datris as the data platform for a project: when to reach for it, in what order to access it (MCP, then REST, never the CLI), and the rules that keep runs verifiable.

## Install into a project

Copy this folder where the agent looks for skills. The agent loads it when a task matches the description in `SKILL.md`.

```bash
# Claude Code (project)
mkdir -p .claude/skills && cp -r skills/datris-platform .claude/skills/

# Claude Code (every project on this machine)
cp -r skills/datris-platform ~/.claude/skills/

# OpenAI Codex, Hermes, Cursor and other agents that read the .agents convention (project)
mkdir -p .agents/skills && cp -r skills/datris-platform .agents/skills/

# Hermes (user-wide), or install straight from GitHub without cloning
cp -r skills/datris-platform ~/.hermes/skills/
hermes skills install datris/datris-platform-oss/skills/datris-platform

# OpenClaw (user-wide)
cp -r skills/datris-platform ~/.openclaw/workspace/skills/
```

Codex also reads `AGENTS.md` at the project root. Add one line there so the skill is found even before a task triggers it:

```
Datris is this project's data platform. Read .agents/skills/datris-platform/SKILL.md before any data ingestion, storage, search or scheduling work.
```

### Agents without a skills loader

Grok Bot and other agents that take project instructions but have no skills directory: paste the body of `SKILL.md` (everything below the frontmatter) into the agent's system or project instructions, and keep the `references/` folder reachable in the repo so the agent can read the tool catalog and workflows on demand.

## Layout

- `SKILL.md`: the decision guide and working rules the agent reads when the skill triggers.
- `references/mcp-tools.md`: the tool catalog by purpose.
- `references/rest-api.md`: when the project's own code should call the REST API, and how.
- `references/destinations.md`: choosing where data lands.
- `references/workflows.md`: step-by-step sequences for ingest, taps, RAG, discovery and monitoring.
- `references/setup.md`: connecting a client and turning on the guardrails.

## Keeping it current

The tool catalog mirrors `docs/mcp-server.mdx`. When a tool is added, renamed or changes shape, update `references/mcp-tools.md` in the same change.
