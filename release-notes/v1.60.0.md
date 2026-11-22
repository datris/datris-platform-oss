# Release Notes

## v1.60.0 — November 18, 2026

**Delete a pipeline and keep its data, and tighter boundaries for scoped users and API keys.**

- **Delete a pipeline, keep the data.** Deleting a pipeline can now leave its destination data in place. Ask for it with the keep-data option in the API, the CLI or the MCP delete tool, and the result lists what was kept. Pipelines that write to a vector store cannot be deleted this way yet. A plain delete still removes the configuration and the data, as before.
- **Scoped users start on new destinations.** A user limited to specific catalogs can no longer point a pipeline at a table, collection or storage location that already exists, unless a pipeline they can see already uses it. This covers saving, restoring an earlier version and renaming. An admin or any user with install-wide access can still adopt existing data.
- **Scoped users see less about work outside their catalogs.** Provenance fields that identify a feeding tap are left out of search results, Live Read rows and query results when the user cannot see that tap. On Snowflake, SQL Server and Databricks, a scoped user gets a preview of the pipeline's own table and cannot run custom SQL. The Operations chat no longer tells a scoped user about open incidents.
- **Chats act with the caller's own access.** An API key limited to a catalog, to its own pipelines or to specific capabilities now gets exactly that access in the Assistant and the other chats. Signed-in users and keys with install-wide access are unaffected. Tap scripts can no longer call the chats.
- **MCP tools list for signed-in users.** The tools list now loads for a user who is signed in without an API key, filtered to their role.
- **Clearer errors from schema generation.** Invalid or empty input now returns a clear message instead of a server error.
- **Reserved metadata names.** With provenance stamping on, the platform's own provenance values replace any custom metadata that uses the same names. On Qdrant, Milvus, Chroma and Weaviate, custom metadata named `id`, `text`, `chunk_index`, `filename` or `embedding` is now ignored.
- **Security hardening.** Several fixes tighten what scoped users and limited API keys can reach. We recommend upgrading.

**Upgrading:** `docker compose pull && docker compose up -d`; the server, UI and MCP server images changed. No configuration changes are required and nothing is migrated. Three things to check:

- If a script already asks a pipeline delete to keep data, that request is now honoured. Earlier versions removed the data anyway.
- If you label vector chunks with custom metadata named `filename` or another reserved name above, switch to a different name.
- If an integration calls the chats with a limited API key, it now gets that key's access, and a sign-in failure comes back as an error response instead of an error inside the stream.

Agents using the published skill get the keep-data and new-destination guidance with skill 1.7.9. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
