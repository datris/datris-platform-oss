# Release Notes

## v1.7.7 — May 27, 2026

**Ops Activity gains a Successes pane, long lists scroll in place, and the dashboard's auto-refresh no longer yanks you back to the top.**

- **Successes pane on Ops Activity.** A new pane below Failures lists every pipeline that ran successfully in the selected window, with the run count, items processed, and last-run time. Click a row to jump to that pipeline.
- **Long lists scroll inline.** Failures, Successes, and the Per-pipeline volume table all cap at ~10 rows of height and scroll internally instead of stretching the page. Per-pipeline volume column headers stick to the top so you don't lose context as you scroll.
- **Auto-refresh preserves scroll position and expansion state.** The 30-second refresh no longer scrolls a long pipeline-volume list back to the top or collapses an open failure detail. Expand a failure, scroll where you want, leave the tab open — it stays put.
- **Numeric column headers aligned with their data** on the Per-pipeline volume table — Today, 7d avg, vs avg now line up with the numbers underneath.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- The `datris` CLI: `brew upgrade datris`.

---

See [archived release notes](release-notes/) for prior versions.
