# Release Notes

## v1.16.3 — August 14, 2026

**Assistant fix: taps that read data already in Datris no longer ask for database credentials.**

When you asked the Assistant to build a tap whose fetch logic depends on data a pipeline already maintains — for example, an external-API tap driven by a list of ids kept fresh in a Datris table — it would either ask you for database credentials or offer to freeze a snapshot of the list into the script. Neither was ever necessary: tap scripts can read platform-stored data live on every scheduled run, with no extra credentials.

The Assistant now knows this. It builds such taps to read the platform data fresh each run, keeps the tap's secret limited to the external source's API key, and will no longer pop a credentials form for access it already has. The same guidance now reaches agents connected over MCP (Claude Desktop, Claude Code, and others).

**Upgrading**

Standard upgrade: `docker compose pull && docker compose up -d`. Start a new Assistant conversation after upgrading — existing conversations keep the older behavior until reopened.

---

## v1.16.2 — August 13, 2026

**Azure OpenAI without API keys — Microsoft Entra ID authentication.**

See the [full v1.16.2 notes](release-notes/v1.16.2.md) for details.
