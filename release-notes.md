# Release Notes

## v1.7.9 — May 28, 2026

**Ask the Ops assistant about a failing pipeline without leaving the dashboard.**

- **New Ops chat side panel.** A collapsible chat lives on the right side of Ops → Activity. The assistant has the current failures, stale taps, and volume anomalies in mind — ask "why did `X` fail?" and it pulls the root cause; ask it to re-run a tap and it runs and reports the outcome. The panel stays mounted as you switch between Activity and Ingestion, so the conversation survives the tab change.
- **"Ask" buttons on failure and volume rows.** Click "Ask" next to a row to seed the chat with a row-specific question so you don't have to retype the tap or pipeline name.
- **Successes are expandable like Failures.** Click any row in the Successes pane to see the same event trail (begin → processing → end) you get from the Failures pane. Only one row across either pane is open at a time so the layout stays compact.
- **Claude Opus 4.8 is the new recommended CodeGen model.** New installs seed Opus 4.8 as the codegen default. Existing tenants can pick it from the model dropdown in Configuration. The older Opus versions remain selectable for anyone who wants to pin a specific version.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The `datris` CLI: `brew upgrade datris`.

---

See [archived release notes](release-notes/) for prior versions.
