# Release Notes

## v1.48.0 — October 13, 2026

**Back up a catalog as one file and restore it anywhere.**

- **Download a catalog.** The download icon on a catalog's card in the Catalog tab saves the whole catalog as a zip archive: every tap and pipeline definition, each tap's script and incremental state, and the validation schemas its pipelines reference. Secret values, destination data, run history and version history are never included, so the archive is safe to keep with your other configuration backups.
- **Import a catalog.** The new **Import catalog** button restores an archive on the same install or another one, under the original name or a new one. A restore never merges into an existing catalog: if the catalog or any archived tap or pipeline name already exists, the restore is refused and each clash is listed, with nothing changed. Choose **Overwrite catalog** and type its name to replace the contents of an existing catalog, or skip the incremental tap state to make a clone that starts from scratch.
- **Secrets stay yours.** The archive lists the secret names the catalog needs, and the restore result names any that do not exist yet on the target. Inline REST bearer tokens and API keys on a pipeline are removed on export; re-enter them after the restore.
- **Restored taps start disabled.** Taps come back with a **Disabled** badge in place of their schedule. Click the badge to enable the tap, or to test it first; scheduled taps need a passing test before they can be enabled.
- **From the terminal and from agents.** `datris catalog export` and `datris catalog restore` do the same from the CLI, and agents get the `export_catalog` and `restore_catalog` MCP tools, with the same refuse-on-clash and confirm-to-overwrite rules.

**Upgrading**

- The server, UI and MCP server images changed: run `docker compose pull && docker compose up -d`.
- No configuration changes are required. The new endpoints and tools are additive; scoped API keys see them only with the pipeline read (export) or pipeline create (restore) capability.
