# Release Notes

## v1.6.16 — May 4, 2026

**UI cleanup, friendlier Help menu, and a more reliable bundled embedding service.**

- **Bundled embedding handles large ingest batches without errors.** The bundled embedding service no longer rejects large batches submitted by the platform, which previously surfaced as ingestion failures on long documents.
- **Secrets is now a tab inside Configuration.** Instead of a separate top-level tab, Secrets lives under Configuration alongside AI Providers, Taps, and Environment. Existing `/secrets` links continue to work — they redirect to the new location.
- **Help menu in the top bar.** The Docs link is replaced with a Help dropdown that exposes both the docs and a direct link to file an issue on GitHub.
- **Easier setup with Claude.** A new "Configuring Claude" page in the docs walks through Claude Desktop and Claude Code setup end-to-end, including a first-prompts walkthrough for an empty install.
- **Structured issue reporting.** GitHub issues now use forms that capture version, component, deployment mode, and reproduction steps, making bug reports easier to triage and faster to fix.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d`. No data migration needed.

---

See [archived release notes](release-notes/) for prior versions.
