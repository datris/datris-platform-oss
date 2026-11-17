# Release Notes

## v1.59.0 — November 17, 2026

**Limit a user to specific catalogs, and rename a pipeline without losing anything.**

- **Scoped access by catalog.** An admin can now narrow an editor or viewer to specific data catalogs under Configuration → Users. Each grant has a level: **view** to see and read, **edit** to also create, update and run, and **own** to also delete, rename and move. Admins always keep install-wide access, and every existing user keeps the access they have today until an admin turns scoped access on for them.
- **Outside their catalogs, nothing shows.** A scoped user sees only the taps and pipelines in their catalogs everywhere: the tabs, search, lineage, run history, provenance, data discovery, the dashboard signals and MCP tools. Anything else behaves as if it does not exist, so names never leak. Grants follow a catalog when it is renamed. Free-form database queries and table browsing cover the whole install, so they are not available to scoped users.
- **The Assistant works within the user's catalogs.** It knows which catalogs the signed-in user holds, files new taps and pipelines there, and asks which one when there are several. The create wizards do the same.
- **Renaming a pipeline keeps everything.** Rename is now a single operation that keeps the destination data, version history, generated scripts, incremental position and the taps that feed the pipeline. In earlier versions the rename on the Pipelines tab was refused for most pipelines and, in the cases where it went through, could remove the destination data. Pipelines synced to Unity Catalog cannot be renamed yet, and a rename waits for a running job to finish.
- **One pipeline per vector collection.** A pipeline can no longer be created on, or moved onto, a vector collection, class or table that another pipeline already writes to. Pipelines that share one today keep working unchanged.
- **Deleting a pipeline keeps data another pipeline still uses.** When two pipelines write to the same table or collection, deleting or resetting one of them now leaves the data in place and says so in the result. The data is removed when the last pipeline that writes to it is deleted. Before, the first delete removed it for both.
- **Hardening for scoped API keys.** Keys limited to a catalog or to their own pipelines are now held to that limit in more places, including lists, lookups and saves. A scoped key may get a refusal where an earlier version accepted the request; keys with install-wide access are unaffected.
- **Stricter user updates.** Changing a user through the API now rejects a value of the wrong type instead of guessing what was meant.
- **Security updates.** The mirrored MinIO image moved to the current release, clearing the open vulnerability reports against it. No behaviour change.

**Upgrading:** `docker compose pull && docker compose up -d`; the server, UI, MCP server and MinIO images changed. No configuration changes are required and nothing is migrated: scoped access is off for every user until an admin turns it on. Agents using the published skill get the rename and scoped-access guidance with skill 1.7.4. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
