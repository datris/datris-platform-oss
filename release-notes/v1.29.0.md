# Release Notes

## v1.29.0 — September 10, 2026

**Datris can now diagnose its own deployment.**

- **Doctor.** A built-in operational self-check finds the problems that used to be discovered only after an outage: a Vault token about to expire or silently capped at Vault's default ceiling, an AI provider secret that is missing or incomplete so the server would not come back after an upgrade, an embedding model that was never pulled, a disk about to fill, and a mixed-version stack. Every finding comes with the command that fixes it. Nothing is changed automatically.
- **Four places to run it.** The cheap checks run at every server start and appear in the log as `DOCTOR` lines (a healthy install logs no warnings). The full set runs on demand from Configuration → Doctor in the UI, from the new `run_doctor` MCP tool for agents diagnosing a failure, and from the new `datris doctor` CLI command.
- **Host checks from the CLI.** Run on the machine that runs Docker, `datris doctor` also catches data sitting on an anonymous volume that a `down` would orphan, data left behind on a dangling volume by a container recreate, a container still running with an old `.env` after a `restart`, containers from a previous Compose file, a stale from-source build, and an unreachable MCP server. `datris doctor --pre-upgrade` works with the server stopped and is now step 0 of the upgrade guide.
- **Optional AI probe.** Ask for it explicitly and the doctor sends a minimal request through each configured AI model to confirm the key and model id still work. It costs a few tokens and is never run automatically.
- **Version stamps.** The server reports the exact build it is running, and the UI image now carries its version, so the doctor can tell when server, UI, MCP server and CLI have drifted apart.
- **Fresh-install AI defaults refreshed.** New installs now seed the currently recommended models: Claude Fable 5.1 for the Anthropic assistant, GPT-5.6 Sol for OpenAI, and Claude Sonnet 5 as the overload fallback. Code generation stays on Claude Opus 5. Existing installs keep whatever they have configured.

**Upgrading**

Run `datris doctor --pre-upgrade` first, then `docker compose pull && docker compose up -d --force-recreate`. No configuration changes required. CLI users on pip or Homebrew: upgrade to 1.29.0 to get the `doctor` command.
