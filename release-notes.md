# Release Notes

## v1.10.0 — July 9, 2026

**Databricks destination — load, upsert, and query your Databricks workspace.**

- **Load into Databricks.** Point any structured pipeline at your own Databricks workspace and Datris loads it as governed Delta tables in Unity Catalog: tables are created automatically, the schema evolves as new columns appear, and natural-key upserts keep scheduled re-runs duplicate-free. Loaded tables are immediately queryable across the workspace — notebooks, dashboards, and lineage all see them natively. Available in the pipeline wizard and through the AI assistant.
- **Secure by default.** Connects as a dedicated service principal you control, with least-privilege access to a single catalog; credentials live in a platform secret, never in pipeline configs. The docs include a copy-paste grant script. Works out of the box with serverless SQL warehouses — nothing runs in your cloud account and there's no cluster to manage.
- **Safe full refreshes.** Pipelines that replace their table on each run swap the contents atomically — readers never see a half-loaded or empty table, and a failed run leaves the previous data intact.
- **Forgiving setup.** Pasted workspace URLs and warehouse connection paths are cleaned up automatically in whatever shape they arrive, and connection problems come back as plain-language errors that say exactly what to fix.
- **The assistant can verify and query Databricks.** Ask it to confirm a load landed or answer questions over the data — read-only, using the pipeline's own credentials — and it can browse your catalogs, schemas, tables, and columns along the way.
- **Cleaner deletes.** Removing a pipeline together with its data also cleans up the table it created in Databricks.
- **CLI catch-up.** `datris ingest` can now target Snowflake and Databricks directly, and pipeline listings label Snowflake and Databricks destinations.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d`.
- The CLI: `brew upgrade datris`.
- Using Databricks requires a one-time setup in your workspace — see the [Databricks destination guide](https://docs.datris.ai/destinations/databricks).

---

## v1.9.0 — July 8, 2026

**Snowflake destination — load, upsert, and query your Snowflake account.**

See [archived v1.9.0 release notes](release-notes/v1.9.0.md).

---

## v1.8.12 — July 1, 2026

**Claude Sonnet 5 support.**

See [archived v1.8.12 release notes](release-notes/v1.8.12.md).

---

## v1.8.11 — June 29, 2026

**Switch AI providers freely, and an assistant that finishes the job.**

See [archived v1.8.11 release notes](release-notes/v1.8.11.md).

---

## v1.8.10 — June 24, 2026

**Your secrets and configuration now persist across restarts and rebuilds.**

See [archived v1.8.10 release notes](release-notes/v1.8.10.md).

---

## v1.8.9 — June 22, 2026

**Version history for taps and pipelines, plus a faster assistant.**

See [archived v1.8.9 release notes](release-notes/v1.8.9.md).

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
