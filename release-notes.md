# Release Notes

## v1.57.1 — November 6, 2026

**Security maintenance, and the Assistant's question cards stay cards.**

- **Security updates.** Two of the server's bundled libraries were updated to patched versions, clearing the open vulnerability reports against the published server image. No behaviour change.
- **Question cards no longer turn into text.** In longer Assistant conversations, a follow-up set of clarifying questions could come out as a typed-out description of a card instead of the card itself. The Assistant now remembers earlier cards the same way it remembers its other tool calls, so every set of questions renders as a card you can answer.

**Upgrading:** `docker compose pull && docker compose up -d`; the server, UI and MCP server images changed. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.

## v1.57.0 — November 4, 2026

**Microsoft SQL Server joins the structured destinations, with read-back and on-demand typing.**

- **Load into SQL Server or Azure SQL.** A pipeline can now land rows in a SQL Server database you already run, the same way it lands in PostgreSQL: the table is created from the destination schema, new columns are added on later runs, key fields upsert, and truncate-before-write replaces the rows inside a transaction. CSV, JSON and XML sources all work; JSON and XML documents land whole in a checked column. Reserved words such as `order` or `key` are safe as table and column names, and boolean columns accept the usual spellings (true/false, yes/no, y/n, on/off, 1/0) and reject anything else rather than silently storing a wrong value.
- **Sign in the way your database expects.** Credentials live in a Platform secret with a generic auth method: SQL login, Microsoft Entra ID (service principal, user password, or managed identity when the server runs on Azure), or Kerberos with a password or a keytab. The installer can collect SQL Server credentials at install time, and the new destination appears in the pipeline wizard, the Assistant, the MCP server and the Catalog only when a complete credential exists.
- **Read it back.** Agents, the Search tab and the CLI can run read-only queries against a SQL Server destination and browse its tables, with results capped the same way as for Snowflake and Databricks. Pipelines whose columns landed as text get the Catalog **text** badge and the propose-and-apply typing flow, which validates every value before changing a column and rolls back completely if anything fails.
- **Every destination is its own choice.** The Assistant's destination question card now lists each available destination as a separate option (and Live Read when no schedule was asked for) instead of folding some into another option's description.
- **Dependency updates.** Third-party packages in the web UI were updated to patched versions. No user action is needed.

**Upgrading:** `docker compose pull && docker compose up -d`; the server, UI and MCP server images changed. No configuration changes are required. To use SQL Server, create a Platform secret under Configuration → Secrets → Platform (see the SQL Server destination docs). API keys created before this release with an explicit capability list need the SQL Server query capability added, or a re-issue, before they can query SQL Server; keys made from the bundles and templates after upgrade have it already. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.

## v1.56.0 — November 3, 2026

**Self-healing stops re-checking the same benign volume spike, and recovery mode is set where you manage the pipeline.**

- **No more repeat triage of a benign volume signal.** When the recovery agent has already looked at a pipeline's volume change and closed it as benign, the incident sweep no longer re-opens an incident for that same signal every few minutes. A genuinely new change in volume still opens one. On an instance where one pipeline's feed kept tripping the volume check, this removes hundreds of unnecessary AI calls a day.
- **Prompt caching for Anthropic.** Chat and agent requests sent directly to Anthropic now mark the system prompt and tool list for prompt caching, so repeat calls in a session reuse the cached prefix at a fraction of the input cost. The server log shows the cache token counts for each call. Bedrock, OpenAI, Azure and Grok requests are unchanged.
- **Set self-healing per pipeline or tap from its page.** The pipeline page, the pipeline edit page and the tap run page now show a **Self-healing** row with the effective recovery mode and where it comes from (instance default or override). Admins can switch it to Off, Propose or Autopilot right there, or send it back to the instance default. The Agent Policy page's override editor shows these recovery entries properly instead of a blank row.

**Upgrading:** `docker compose pull && docker compose up -d`; the server and UI images changed. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
