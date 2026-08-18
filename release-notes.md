# Release Notes

## v1.18.0 — August 18, 2026

**Build taps in any language — HTTP taps.**

A tap can now be a service **you** host — written in Rust, Go, TypeScript, a serverless function, or an existing internal system. Datris calls your endpoint on every run (manual, scheduled, or test) and everything else works exactly like a Python tap: schedules, per-run parameters, incremental sync state, run history, and automatic retries. Your service keeps its own upstream credentials — at most a single optional token is ever sent to it — and no tap code runs on the Datris platform at all.

Create one by choosing **HTTP Endpoint** in the tap wizard, with the CLI, or by asking an agent over MCP. The wire contract, an incremental-sync pattern, and a complete Rust example are documented at [docs.datris.ai/tap-http-contract](https://docs.datris.ai/tap-http-contract). Test-run errors also got smarter about the most common first-contact mistakes when pointing Datris at a new endpoint.

**A more focused tap wizard.**

Creating a tap in the UI is now a two-way choice: paste your own Python script, or point at an HTTP endpoint you host. AI-generated scripts remain fully available through the Assistant and connected agents. The wizard detects structured vs. document output automatically from your test run, and a passing script is kept exactly as you tested it — the automatic performance-rewrite pass after a successful test has been removed.

**GitHub-stored taps: the editor always opens the latest code.**

Opening a repository-backed tap now loads the newest version of its script from the repo, with a notice explaining that runs keep the previously saved version until you test and save. Two long-standing annoyances are fixed: taps no longer show a false "script was changed" warning whenever an unrelated file in the repository was updated, and the AI test diagnosis now works for repository-stored scripts.

**Catalog quality of life.**

Columns in the taps and pipelines tables can be drag-resized, and your layout is remembered. Background refresh no longer interrupts what you're doing — resizing a column, choosing a catalog to move an item into, or confirming a delete all hold steady until you're done.

**Upgrading**

Standard upgrade: `docker compose pull && docker compose up -d`. Everything is additive — existing taps, schedules, and scripts work unchanged.

---

## v1.17.0 — August 17, 2026

**New AI provider: Grok (xAI).**

See the [full v1.17.0 notes](release-notes/v1.17.0.md) for details.
