# Release Notes

## v1.6.4 — April 16, 2026

Two-pass AI tap optimization and Data Catalog UX improvements.

### AI optimize pass for tap scripts

After a tap script passes its initial test, the platform now sends the working script back to the LLM for a performance-focused rewrite and re-tests the result. If the optimized script regresses (>=20% slower) or fails, the original is kept automatically.

- New `TapScriptOptimizer` utility and `POST /api/v1/tap/optimize` endpoint.
- `/api/v1/tap/test` now returns `durationMs` for timing context.
- Low-risk performance hints added to the generation prompt (session reuse, skip defensive sleeps when an API key is present, defer concurrency to the optimize pass).
- Wired into both the Create Tap wizard and the Discovery wizard's build flow.

### Discovery wizard improvements

- Auto-optimize per tap with before/after timing banner.
- Per-row stop button during the build phase.
- On-demand AI fix panel for failed items.
- Chat auto-scrolls to bottom on new messages.
- Re-show Discover Datasets button after a new user message.
- Discovery prompt updated so `multiple=true` covers entity-collection params regardless of whether the API takes one or many.

### Create Tap wizard

- Configurable test sample size (defaults to 20 records).

### Data Catalog

- Kebab menu on each tap and pipeline in the Uncataloged group with Edit, Delete, and Move to Catalog options.
- Move to Catalog shows a submenu of all other catalogs; name-clash detection aborts the move with a red banner if the target catalog already has an item with the same name.

---

See [archived release notes](release-notes/) for prior versions.
