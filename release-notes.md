# Release Notes

## vNEXT — unreleased

**Catalogs can now be renamed, and deleting one asks whether to keep or delete its items.**

- **Rename a catalog in place.** A named catalog can be renamed from the Catalog tab; every tap and pipeline in it moves to the new name in one step. Renaming into an existing catalog merges the two, and a clash with an item of the same name is reported without changing anything. If API keys were limited to the old name, you are told which ones to update.
- **Deleting a catalog keeps its items by default.** You choose between keeping the items (they move to Uncataloged) and deleting them together with their data, which requires typing the catalog name. The same choice appears on catalog groups in the Taps and Pipelines tabs. Items that could not be changed are listed with the reason.
- **Uncataloged is protected.** It can no longer be renamed or deleted as a group; its items are deleted one at a time.
- **The assistant can rename catalogs.** Ask the Catalog assistant, or any connected agent, to rename or remove a catalog and it does so in one step. Agents can only remove a catalog while keeping its items.

## v1.38.3 — September 27, 2026

**Clearer errors for files whose names cannot be parsed.**

- **The real ingest error is reported.** When an uploaded file's name cannot be matched to a pipeline, the log now says so instead of reporting an unrelated internal error. Files that fail later in ingestion still record their status exactly as before.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. Only the server image changed; no configuration changes are needed.
