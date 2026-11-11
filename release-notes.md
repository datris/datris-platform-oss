# Release Notes

## v1.58.0 — November 11, 2026

**The Assistant previews real rows before it creates a pipeline, and proposes checks you confirm on a card.**

- **Column types come from the data, confirmed by you.** When the Assistant builds a tap and a pipeline, it now tests the tap first and shows the rows it produced as a card with a recommended type for every column and the evidence behind it. Change any type, keep everything as text, or pull more rows; the confirmed list becomes the pipeline's schema, so PostgreSQL, Snowflake, Databricks and SQL Server land typed columns on the first run instead of text. Other MCP clients get the same proposal as a table and a question.
- **Data-quality checks and transformations are proposed, not imposed.** If you ask for checks or transformations, the Assistant suggests them on a card with a checkbox per rule. Untick, edit, add your own, or clear them all; only the rules you confirm are used, word for word. For data-quality checks you also choose on the card whether a failing row rejects the run or only logs a warning.
- **Fewer questions where they do not apply.** A vector-store pipeline has no column schema and no rules to choose, so the Assistant goes straight from the sample rows to creating the pipeline. For MongoDB and vector destinations the sample card is informational and needs no click. Questions with a "None" choice now treat it as exclusive, so picking it clears the other boxes.
- **Builds recover from transient failures.** A tap test or a model call that fails on a rate limit or a timeout is retried automatically, and a reloaded chat picks up every earlier card and tool call so the Assistant does not lose its place.
- **Security updates.** A bundled server library and the base images of the UI and MCP server were updated to patched versions, and the mirrored MinIO image moved to the current release, clearing the open vulnerability reports against the published images. No behaviour change.

**Upgrading:** `docker compose pull && docker compose up -d`; the server, UI, MCP server and MinIO images changed. No configuration changes are required. Agents using the published skill get the updated tap workflow with skill 1.6.0. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
