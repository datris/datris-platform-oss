# Release Notes

## v1.30.1 — September 15, 2026

**A smaller install, and docs written for the way people search.**

- **Minimal install.** The installation guide now leads with a minimal path that skips the bundled embedding server and the bundled PostgreSQL, and fits in a 4 GB Docker memory allocation. The full stack needs about 8 GB. The numbers were measured on a live stack and replace the old "fits an 8 GB host" guidance. See [Installation](https://docs.datris.ai/installation).
- **OpenAI embeddings by default when you have a key.** On a fresh install with an OpenAI key present, embeddings now use OpenAI instead of the local embedding server, whichever provider handles chat. That avoids the 2 GB model download and the resident container. Existing installs keep whatever embedding provider they already have; set the embedding provider explicitly to keep embeddings local.
- **A recipe for agents that write to your database.** New guide: [Give Claude Code a safe way to load data into PostgreSQL](https://docs.datris.ai/recipes/claude-code-postgres). It connects Claude Code over MCP, gates deletes and table rewrites behind human approval, loads a table, and shows the audit and lineage records left behind.
- **Docs pages named for the task.** Destination, MCP, policy, audit, lineage, and Airflow pages are now titled for what you are trying to do, such as "Load data into Snowflake from an AI agent or pipeline" and "Embed documents into Qdrant for RAG". URLs are unchanged.
- **More reliable release builds.** The UI image no longer builds its assets under emulation, which had caused intermittent build failures on the arm64 image.

**Upgrading**

Run `datris doctor --pre-upgrade` first, then `docker compose pull && docker compose up -d --force-recreate`. No configuration changes required.
