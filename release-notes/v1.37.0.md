# Release Notes

## v1.37.0 — September 24, 2026

**Agents get live, validated reads from any source, and only land what's worth keeping.**

- **The scratch destination is now called Live Read.** Same behaviour, same configuration; only the name changed in the wizard, the Pipelines list, the run detail page, the docs, the Assistant and the CLI. The first mention on each surface still says which JSON key it maps to, so nothing you have written needs to change.
- **The Assistant offers Live Read.** When it asks where data should go, it now names Live Read alongside the structured destinations, with a one-line description, and still never picks it unless you do. It leaves Live Read out when you have asked for a schedule.
- **The docs page moved.** Live Read has its own page under Destinations; the old address redirects.
- **The CLI signs in to the MCP server.** On installs with API keys turned on, every CLI command now sends the key you already export for `datris doctor`. A missing key gives a one-line message instead of a connection error, and a rejected key gives a one-line message and a non-zero exit instead of raw output and success. The query-string form still works.
- **A clean exit on every command.** The CLI no longer prints a stray traceback after a successful command, and commands that make several calls no longer reuse a stale connection.
- **MCP clients see the real version.** The MCP server now reports its own version instead of the library it is built on.
- **Claude Opus 5.5 and Grok 4.7.** Both are in the model catalog. Opus 5.5 is the recommended and first-boot default for code generation on Anthropic and Bedrock; Grok 4.7 is the recommended and first-boot default for Grok. The chat default is unchanged.

**Upgrading**

Run `datris doctor --pre-upgrade`, then `docker compose pull && docker compose up -d --force-recreate`. Update all images together, including the UI and the MCP server. Existing installs keep the models they already have; the new defaults apply only on first boot. If you use API keys and run the CLI, export `DATRIS_API_KEY` once and drop any `?api_key=` you had put on the MCP server address.
