# Release Notes

## v1.26.1 — September 2, 2026

**Claude Fable 5.1 is now the recommended chat model, plus security and dependency updates.**

- **Claude Fable 5.1 support.** Anthropic's newest model, released September 1, is now selectable in the model picker for both the Assistant and code generation, on the direct Anthropic provider and on Amazon Bedrock. It is the recommended chat model on both; code generation keeps Claude Opus 5 as its recommendation. Fresh Bedrock installs default to Fable 5.1. Existing installs keep whatever models they have configured — pick Fable 5.1 in Configuration when you're ready. On Bedrock, Fable 5.1 needs the same one-time data-retention opt-in as Fable 5.
- **Security update.** The UI's build tooling picks up fixes for several recently disclosed high-severity advisories in a third-party component. No functional changes.
- **UI framework update** to the latest Angular patch release.
- **CLI and MCP server via pip.** The published package now pins the MCP SDK to the supported major version, so a fresh `pip install datris-mcp-server` no longer pulls an incompatible SDK that fails at startup.

**Upgrading**

`docker compose pull && docker compose up -d --force-recreate`. No configuration changes required. CLI users on pip or Homebrew: upgrade to 1.26.1.

---

Older releases: see the [release-notes/](release-notes/) directory or the [changelog on the docs site](https://docs.datris.ai/changelog).
