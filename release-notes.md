# Release Notes

## v1.50.0 — October 15, 2026

**Busy hosts stay up, and Claude Opus 5.5 becomes the default chat model.**

- **Busy hosts stay up.** Tap scripts and AI rule scripts now run a bounded number at a time, inside CPU, memory and process limits. When the runner is full, a run waits its turn instead of piling on; its run history shows how long it queued, and `datris doctor` warns when runs had to wait or were turned away. All limits are adjustable in `.env`.
- **Claude Opus 5.5 is the default chat model for Anthropic.** New installs on the Anthropic and Bedrock paths seed Claude Opus 5.5 for the Assistant and other chat features (it was already the code-generation default), and the model picker recommends it. Existing installs keep the model they have; change it any time on Configuration → AI Providers.

**Upgrading**

- The server, UI, MCP server and script runner images changed: run `docker compose pull && docker compose up -d`.
- The new compose file gives both script runners CPU, memory and process ceilings and each script a 1 GB memory limit. These are ceilings, not reservations, so an idle install uses no more memory than before. Tap scripts that need more than 1 GB, more than one CPU, or multi-threaded numeric libraries can raise the limits in `.env`; the [Tap Execution & Isolation](https://docs.datris.ai/tap-execution-isolation#configuration) docs list each setting.
- No other configuration changes are required.
