# Release Notes

## v1.61.0 — November 22, 2026

**Deletes that tell you what happened, columns that land under their own names, and firmer boundaries for scoped users and API keys.**

- **Delete and reset report what was left behind.** When a table, collection or set of files cannot be removed, the result names it and gives the reason, in the UI, the CLI and the MCP tools. The pipeline's configuration is still deleted. A store that does not answer no longer holds up the delete.
- **Reset no longer re-ingests after a failed clear.** If a vector collection could not be cleared, the reset keeps its record of ingested documents, so nothing is loaded twice. Run the reset again once the store is available.
- **Columns an AI transformation adds reach your database table.** A new column from a transformation is added to the table as text. If a table cannot take it, the run still succeeds, loads every other column, and shows a warning naming the column and the reason.
- **Object-store files with a missing column.** A file that lacks a column of the pipeline's schema now loads with that column empty. Rows that lack an Iceberg merge key or a partition column are refused before anything is written, with a message naming the column, instead of duplicating rows or landing them in the wrong partition.
- **Parquet and ORC queries return every column.** A query now returns every column the pipeline's files hold, including columns added after the first load.
- **More column names load as they are.** On object stores, a column name with a dot, a space or a backtick loads under exactly that name. Saving a pipeline again keeps the column names its schema already has.
- **Clearer refusals on save.** A table, schema, collection or column name a destination cannot hold, or a column type the platform does not know, is refused when you save, with a message naming it, instead of failing at load time.
- **Read-only queries.** PostgreSQL queries from the query tools and from tap scripts are read-only and limited to five minutes each. MongoDB queries and listings cover your data and leave out the platform's own records.
- **Restoring an earlier version is checked like a save.** A version that would not be accepted today is refused with the reason, and nothing is written. Restoring a deleted pipeline or tap needs permission to create one.
- **API keys.** A limit the platform does not enforce can no longer be put on a new key, and the Keys page marks existing keys that carry one so you can replace them.
- **Security hardening.** Several fixes tighten what scoped users and limited API keys can reach, in the API, the MCP tools and the chats. We recommend upgrading.

**Upgrading:** `docker compose pull && docker compose up -d`; the server, UI and MCP server images changed. Nothing is migrated. Six things to check:

- **Object stores match columns by name.** If a pipeline relied on column order, for example a destination schema listed in a different order from the file, each value now lands under its own column name.
- **Transformation columns appear in SQL tables.** If an AI transformation adds columns, expect them as new text columns after the next run. Earlier versions left them out without saying so.
- **A missing merge key or partition column stops the whole run.** On a pipeline that writes to both a database and an object store, earlier versions still loaded the database. Now nothing loads until the file carries the column.
- **pgvector pipelines must name their table.** A pipeline saved without a table name keeps loading into the table it already uses and shows a warning on each run until you set one.
- **Limited API keys.** A key whose limit the platform never enforced no longer grants that access. Replace such keys with ones created in this version. The compose file carries a setting to keep the earlier behaviour while you replace them; refresh your `docker-compose.yml` if you want it.
- **Tap scripts that query PostgreSQL.** A script that writes through the query route, or reads for more than five minutes in one query, needs changing: split a large read into several queries.

Agents using the published skill get the scoped-key guidance with skill 1.7.13. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
