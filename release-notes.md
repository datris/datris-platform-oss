# Release Notes

## v1.8.3 — June 4, 2026

**Organize your catalog by chatting with it.**

- **Catalog assistant.** The Catalog tab has a new side-panel assistant for tidying things up. Ask it to group your taps and pipelines into catalogs, move items around, or suggest a cleaner structure — it lays out a plan first and only makes changes once you approve them, and the catalog updates as it works. It sticks to organizing: questions about your data go to Search, and running or fixing pipelines stays in Ops.
- **Right where you're working.** The assistant lives in a collapsible right rail (toggle it with Cmd/Ctrl + backslash) so the catalog stays in view while you reorganize. Clicking **Describe to Assistant** on a catalog now opens this panel in place instead of jumping to another tab, and the conversation sticks around as you navigate away and back.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The CLI: `brew upgrade datris`.

---

## v1.8.2 — June 3, 2026

**Ask your data a question — conversational search comes to the Search tab.**

See [archived v1.8.2 release notes](release-notes/v1.8.2.md).

---

## v1.8.1 — June 2, 2026

**Orchestrate Datris taps from Apache Airflow.**

See [archived v1.8.1 release notes](release-notes/v1.8.1.md).

---

See [archived release notes](release-notes/) for prior versions.
