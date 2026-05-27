# Release Notes

## v1.7.8 — May 27, 2026

**The Assistant stops second-guessing itself when the data is already there.**

- **No more apology loops.** When the Assistant verifies platform state and the pipeline / tap it created earlier is present in the list, it now treats that as evidence the work was done — instead of retracting a prior turn's "done" claim and rebuilding from scratch.
- **"Show me X" goes straight to the data.** When you ask to see / list / show data and a matching pipeline already exists, the Assistant now jumps to the destination's query tool (Mongo / Postgres / vector search) and returns the actual rows or documents. It no longer asks you which sources, providers, or schedules to use for a pipeline you already have.
- **Long catalogs no longer hide existing resources.** The tools the Assistant uses to inventory pipelines and taps now return a compact summary with names at the top. Previously, on environments with many pipelines, an entry near the end of the list could slip past the Assistant's scan — the new shape makes every name impossible to miss.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The `datris` CLI: `brew upgrade datris`.

---

See [archived release notes](release-notes/) for prior versions.
