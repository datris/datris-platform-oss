# Release Notes

## v1.6.20 — May 6, 2026

**Reliable job-status polling, re-ingest that doesn't overwrite your config, and first-class catalog tooling.**

- **Polling an upload's job status now gives you a clear answer.** Previously, when a file finished processing successfully, the response was a raw stream of progress events with no terminal status — agents and scripts couldn't tell "still running" from "done." Job status now returns a rollup with a single `allDone` flag and an aggregate outcome (`success`, `warning`, `error`), plus per-job error detail when something fails. Poll the rollup; act on the outcome. No more guessing.
- **Re-ingesting a file preserves your pipeline's config.** `datris ingest` against an existing pipeline used to silently rewrite the config from CLI flags only — wiping out the catalog, custom validation rules, and any other fields you'd set through the UI or via an agent. Re-ingest now uploads into the existing pipeline as-is. To start over with a different config, delete the pipeline first.
- **Catalogs without the read-modify-write dance.** New `--catalog` flag on `datris ingest` for new pipelines, new optional `catalog` argument on the `create_pipeline` MCP tool, and a new `set_catalog` MCP tool that retags an existing pipeline or tap in one call. Empty catalog clears the label back to Uncataloged. See [Data Catalog](https://docs.datris.ai/data-catalog) for the full picture.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d`. No data migration needed.
- The `datris` CLI: `brew upgrade datris`.

---

See [archived release notes](release-notes/) for prior versions.
