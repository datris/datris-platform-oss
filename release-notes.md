# Release Notes

## v1.63.0 — November 25, 2026

**Queries that can only read, a time limit on every query, and bounds on how much one query can ask for.**

- **Warehouse queries are read-only in more ways.** On SQL Server, Snowflake and Databricks, a query is one statement, and functions that act outside the query are refused with the function named: cancelling other sessions, calling out over the network, reading a secret, advancing a sequence, reading files by path.
- **Model and search calls are refused in Snowflake and Databricks queries.** Run those in the warehouse itself and read the result through Datris.
- **Every query has a time limit.** 30 seconds, or 5 minutes when you ask for all rows. This now covers MongoDB and unlimited warehouse reads, which had none.
- **A MongoDB read that is too large is refused, never cut short.** The message tells you to split the read into ranges with a filter. The ceiling is 32 MB by default and can be raised.
- **Very long query text is refused** with its length and the limit in the message.
- **SQL Server column names that end in a reserved word read correctly.**
- **pgvector table names longer than PostgreSQL keeps.** New names over 63 bytes are refused with a clear message. Deleting a pipeline no longer removes a table another pipeline still writes to; the result says the table was kept and why.
- **Optional read-only login for PostgreSQL queries.** Give Datris a second, low-privilege PostgreSQL user and read-back queries use it, while loads keep the loading user. `datris doctor` checks it.
- **Tap guidance for MongoDB reads is corrected.** New tap scripts filter correctly, split large reads, and fail the run when a read is refused.
- **Security hardening.** Several fixes tighten what a query can do and how query text is checked. We recommend upgrading.

**Upgrading:** update the CLI first (`pip install -U datris-mcp-server` or `brew upgrade datris`) and run `datris doctor --pre-upgrade`, then `docker compose pull && docker compose up -d`; the server and MCP server images changed. Nothing is migrated. Things to check:

- **Tap scripts that read a whole MongoDB collection.** A read over 32 MB is now refused. Split it into ranges with a filter, or raise the ceiling in `.env` (see the configuration reference).
- **Tap scripts written before this release that filter a MongoDB read** may have been reading the whole collection. Regenerate them, or check that the script sends its filter the way the tap docs now show.
- **Unlimited warehouse and MongoDB reads longer than 5 minutes** are cancelled.
- **Snowflake queries that use AI, Cortex or model functions** are refused. A few ordinary queries that look like one are refused too, for example a CTE named `ai_...` with a column list; the query API docs list each case and its way round.
- **Databricks queries that use `ai_` functions, or read a file path in place of a table,** are refused.
- **SQL Server queries that use curly-brace escapes** for dates, functions or joins are refused. Write them in plain T-SQL; the message shows how.
- **SQL Server columns named like a control word** (for example `set`, `open`, `close`, `add`) need brackets.
- **pgvector pipelines with a schema or table name over 63 bytes** keep working and can be edited, but cannot be renamed to another over-long name or restored after deletion.
- **`datris doctor` in scripts.** The new PostgreSQL check appears only if you set up the read-only login. It is a warning when that login is a superuser or cannot connect, and a warning makes a full run exit non-zero.

Agents using the published skill get the updated guidance with skill 1.7.22.
