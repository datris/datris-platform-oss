# Release Notes

## v1.41.0 — October 2, 2026

**Iceberg tables that work on Databricks, Unity Catalog metadata on by default, and a security fix for API keys.**

- **Iceberg pipelines can now target Databricks Unity Catalog.** A new managed mode lets the catalog choose where the table lives while Datris writes to it through the catalog, so Iceberg output on Databricks is governed, queryable and kept current after every run. The pipeline status shows the table and its location. Deleting the pipeline leaves the catalog's data in place.
- **Unity Catalog metadata is on by default for Databricks pipelines.** Descriptions, tags, properties and lineage are written without a per-pipeline opt-in. A pipeline can still turn it off explicitly, and a single environment setting restores the previous opt-in behaviour. The status endpoint tells you where the setting came from.
- **Clearer guidance when a catalog cannot do what you asked.** Deleting a catalog reports any Unity Catalog cleanup it could not complete, and saving a pipeline that points an Iceberg register mode at Databricks warns right away and names the mode that works.
- **Safer Iceberg history for adopted tables.** Tables Datris adopts from an existing catalog keep a longer metadata history and record where they came from.
- **Security: revoked API keys are now rejected immediately.** Revoke a key and every request made with it is refused from that moment, and the refusal is recorded in the audit log under the key's name. We recommend upgrading.
- **API errors are returned as JSON.** Server errors no longer come back as raw text; clients, the UI and the CLI get a short, readable message. Agents calling tools over MCP see failures flagged as errors instead of ordinary results, and the CLI explains when its key was rejected.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. The server, UI and MCP server images changed. Databricks pipelines that never configured Unity Catalog now get metadata written after each load; set `DATRIS_UNITY_CATALOG_DEFAULT=disabled` to keep the old opt-in behaviour, or add `unityCatalog: {enabled: false}` to an individual pipeline. See [Unity Catalog](/destinations/unity-catalog).
