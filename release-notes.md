# Release Notes

## v1.8.2 — June 3, 2026

**Ask your data a question — conversational search comes to the Search tab.**

- **Chat search.** The Search tab has a new **Chat** mode, with a **Traditional** toggle for the structured query UI you already know. Ask a question in plain language and Datris finds the answer across all your pipelines and taps — cataloged or not — querying tables, searching documents, and replying with citations to where each answer came from. It's read-only: it looks, it never changes anything.
- **Scope to a catalog.** Narrow a chat to a single catalog (or to Uncataloged data) from the dropdown, or leave it on All to search everything.
- **Conversations survive a refresh.** Your Search chat and Assistant conversations now persist across a browser refresh, so reloading the page no longer clears the transcript.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The CLI: `brew upgrade datris`.

---

## v1.8.1 — June 2, 2026

**Orchestrate Datris taps from Apache Airflow.**

See [archived v1.8.1 release notes](release-notes/v1.8.1.md).

---

## v1.8.0 — May 29, 2026

**Write to AWS S3, query Parquet and ORC from the Assistant and Search, and stop chats actually stop.**

See [archived v1.8.0 release notes](release-notes/v1.8.0.md).

---

See [archived release notes](release-notes/) for prior versions.
