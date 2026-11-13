# Release Notes

## v1.58.2 — November 13, 2026

**The Assistant's schema preview uses the rows the tap test already returned, and stops cleanly when there is nothing to preview.**

- **No second wait for the schema preview.** When the Assistant tests a tap and then proposes column types, the proposal is now built from the rows that test returned instead of running the tap again. The preview appears right after the test, and a slow source is fetched once instead of twice. Other MCP clients get the same behaviour.
- **Live and changing sources preview reliably.** A source that returns different rows on every run, such as a live stream, could pass its test and then return nothing on the second run, so the preview failed. The preview now shows the rows the test produced. The tap runs again only when you ask for more rows, when the tap has changed since the test, or when the test is more than 30 minutes old.
- **A failed preview is reported, not skipped over.** If a tap returns no rows to infer types from, the Assistant now says so and tells you what to do next. It no longer asks you to confirm column types for a table it never showed.
- **Security updates.** Two build-time libraries used by the UI toolchain were updated to patched versions, clearing the open vulnerability reports. No behaviour change.

**Upgrading:** `docker compose pull && docker compose up -d`; the server and MCP server images changed. No configuration changes are required. CLI users: `pip install -U datris-mcp-server` or `brew upgrade datris`.
