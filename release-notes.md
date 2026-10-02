# Release Notes

## v1.41.1 — October 2, 2026

**Security dependency updates and a skill that teaches your coding agents to use Datris.**

- **Security: updated third-party libraries.** Dependency updates close several published vulnerabilities, including high-severity ones, in libraries the server and the UI build use. No Datris behaviour changes. Upgrade at your convenience; shared installs should upgrade soon.
- **A skill for your project's coding agents.** The new `datris-platform` skill, in the repository's `skills` folder, tells Claude Code, Codex, Hermes, OpenClaw and similar agents to use Datris for data work in your own projects: loading files, pulling from APIs on a schedule, validating and landing data, building document search, and querying what has landed. It sets the access order (MCP tools first, the REST API only for code written into the project) and the rules that keep every run verifiable. A new [Agent Skill](/agent-skill) docs page gives install commands for each agent.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. The server, UI and MCP server images changed. No configuration changes are required.
