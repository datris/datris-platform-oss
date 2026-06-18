# Release Notes

## v1.8.7 — June 14, 2026

**Drop a file into the Assistant and it builds the pipeline for you.**

- **Drag a file into the chat.** Attach a CSV, JSON, XML, or document right in the Assistant conversation. The assistant reads a sample, works out the shape, and proposes where to put it — then, once you confirm, creates the pipeline, loads your data, and reports how many rows landed. No wizard, no manual schema step.
- **It confirms before it builds.** The assistant picks a sensible default destination for your file and names the alternatives, so you can steer it to Postgres, MongoDB, an object store, or a vector store before anything is created — and it asks about an upsert key if that makes sense for your data.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The CLI: `brew upgrade datris`.

---

## v1.8.6 — June 12, 2026

**A more decisive assistant, and taps that tell you when a credential is missing.**

See [archived v1.8.6 release notes](release-notes/v1.8.6.md).

---

## v1.8.5 — June 11, 2026

**Claude Fable 5 — Anthropic's most capable model, now selectable.**

See [archived v1.8.5 release notes](release-notes/v1.8.5.md).

---

## v1.8.4 — June 7, 2026

**A domain-neutral assistant — guidance that fits whatever data you work with.**

See [archived v1.8.4 release notes](release-notes/v1.8.4.md).

---

## v1.8.3 — June 4, 2026

**Organize your catalog by chatting with it.**

See [archived v1.8.3 release notes](release-notes/v1.8.3.md).

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
