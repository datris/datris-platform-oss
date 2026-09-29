# Release Notes

## v1.39.0 — September 28, 2026

**Catalogs can now be renamed, and deleting one asks whether to keep or delete its items.**

- **Rename a catalog in place.** A named catalog can be renamed from the Catalog tab; every tap and pipeline in it moves to the new name in one step. If API keys were limited to the old name, you are told which ones to update.
- **Deleting a catalog keeps its items by default.** You choose between keeping the items (they move to Uncataloged) and deleting them together with their data, which requires typing the catalog name. Items that could not be changed are listed with the reason.
- **Catalog names may now use capital letters, when created or renamed.**
- **Renaming to an existing name is refused.** Renaming a catalog to a name that already exists is refused; use Move all contents to combine catalogs.
- **No near-duplicate names.** Creating a catalog whose name differs from an existing one only by capitalisation is refused.
- **Uncataloged is protected.** It can no longer be renamed or deleted as a group; its items are deleted one at a time.
- **The assistant can rename catalogs.** Ask the Catalog assistant, or any connected agent, to rename or remove a catalog and it does so in one step. Agents can only remove a catalog while keeping its items.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. Update the server and UI images together: an older server does not support catalog rename or delete from the new UI.
