# Release Notes

## v1.7.4 — May 21, 2026

**Streamlined navigation and a Catalog-centric workflow — fewer tabs, richer Catalog, and the Assistant front-and-center.**

- **Five top-level tabs instead of ten.** Datris now opens to Assistant, MCP, Catalog, Data, Configuration — plus the Help dropdown. Same capabilities, organized around how you actually work: talk to the Assistant, manage agents through MCP, browse what you've built in Catalog, watch what's flowing in Data.
- **Catalog is the home for taps and pipelines.** Each catalog card embeds the full tap and pipeline tables — description, schedule, last-run status, all actions — with inline rename and a move-to-catalog dropdown on every row. Uncataloged is always shown, even when empty, so day-1 users have an obvious place to start.
- **Bulk move at the catalog level.** Move every tap and pipeline from one catalog into another in one click. The destination auto-expands so you can see the items land immediately.
- **Describe to Assistant from any catalog.** A button on each catalog card opens the Assistant with a fresh chat and the catalog name pre-filled — the Assistant picks up that context and assigns the right catalog to whatever it creates. Create-manually links remain right next to it for users who prefer the wizard.
- **Wizards link back to where you came from.** Editing a tap or pipeline now opens with the item's name in the page title, a "Back to Catalog" link at the top, and primary action buttons at both top and bottom of the form. The Pipeline wizard's final JSON-review step is gone — Save fires straight from the Destination step.
- **Pop out the Agent Monitor.** A new icon next to the Agent Monitor title opens both Connections and Activity Log in a separate browser window — park it on a second monitor and watch tool calls stream while you work in the Assistant. The window resizes responsively so both panes always fit.
- **Catalog state persists across navigation.** Which catalogs are open, which Taps / Pipelines sections you expanded — all preserved through refreshes and tab switches.
- **Fixed: tap rename no longer breaks scripts.** Renaming a tap inline used to leave the new tap pointing at a deleted script file. The script now follows the rename automatically.
- **Fixed: catalogs with only pipelines could lose their assignment.** Opening the pipeline edit wizard for a pipeline in a pipeline-only catalog no longer drops it into Uncataloged on save.
- **Discovery tab removed.** The Assistant is the path for adding new data sources now — its conversational flow covers everything Discovery did.
- **Getting Started tab removed.** First-run guidance lives in the Assistant's starter prompts and inline empty-state hints.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui`. No data migration needed.
- The `datris` CLI: `brew upgrade datris`.

---

See [archived release notes](release-notes/) for prior versions.
