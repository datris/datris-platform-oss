# Release Notes

## v1.8.6 — June 12, 2026

**A more decisive assistant, and taps that tell you when a credential is missing.**

- **No more "type continue to keep going."** The in-product assistants (build, ops, catalog, and search) used to sometimes end a turn by announcing the next step and then stopping — making you nudge them to carry on. Now, when the assistant has already decided what to do next, it just does it, while still pausing where it genuinely needs your decision or approval.
- **Taps surface missing credentials instead of silently returning nothing.** If a tap's API key or other credential has been deleted or is missing the field the tap needs, the run is now reported as a clear failure naming what's missing — rather than quietly completing with zero records and hiding the real cause. Runs that legitimately have no new data are unaffected.
- **Clearer MCP activity graph.** The connection graph in the MCP Activity monitor now renders at a readable size.
- **"Move to catalog" shows everything.** The per-item move menu in the Catalog now lists all your catalogs, not just the first one.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The CLI: `brew upgrade datris`.

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
