# Release Notes

## v1.8.9 — June 22, 2026

**Version history for taps and pipelines, plus a faster assistant.**

- **Version history for taps and pipelines.** Every time you create or change a tap or pipeline, Datris saves a snapshot of it. Open **Version History** to see what changed and when, compare any two versions side by side, and roll back — or forward — to an earlier version. Nothing is ever overwritten; restoring a version simply adds a new one. AI agents can review, compare, and restore versions too.
- **Configuration that sticks.** AI provider and model changes made in the Configuration tab now save reliably and persist across restarts.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. Version history begins with your next edit; your existing taps and pipelines are seeded automatically on first start. No manual migration needed.
- The CLI: `brew upgrade datris`.

---

## v1.8.8 — June 18, 2026

**Stronger isolation for tap scripts, plus a catalog readability fix.**

See [archived v1.8.8 release notes](release-notes/v1.8.8.md).

---

## v1.8.7 — June 14, 2026

**Drop a file into the Assistant and it builds the pipeline for you.**

See [archived v1.8.7 release notes](release-notes/v1.8.7.md).

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
