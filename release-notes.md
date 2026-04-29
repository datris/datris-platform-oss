# Release Notes

## v1.6.14 — April 29, 2026

**Reliable run-completion signals, smarter agent calls, cleaner run history.**

- **Agents get a single "are we done?" signal when watching a pipeline load.** Polling a publisher token returns a rollup with a clear `allDone` boolean and a per-job outcome (success / warning / error / processing / timed out), so agents no longer have to interpret the raw event stream to figure out whether a run is complete.
- **Pipeline status by publisher token works reliably for completed runs.** A storage path that occasionally hid completed runs from the publisher-token query is fixed; the query is now backed by an indexed top-level field, with a fallback for older rows so existing data resolves without a migration.
- **`run_tap` no longer ships the records array back to the agent.** A push run returns `recordCount`, `publisherToken`, and the `persisted` / `persistedReason` flags — enough to verify ingestion via `get_pipeline_status` without bloating the agent's context. Use `test_tap` to preview a script's output (capped at 20 sample rows with a `recordsTruncated` flag).
- **Duplicate `run_tap` calls are suppressed.** The agent skips a `run_tap` for a tap that's already in flight in the same session, and the platform debounces push runs to one per tap per 5 seconds. Prevents accidental duplicate ingestion from parallel tool calls, double-clicks, and transport retries (`persistedReason: already_running` or `debounced`).
- **Every tap run now produces visible logs.** The script wrapper emits start / fetch / record-count lifecycle lines on every run, so run history shows useful output even when the user's script never calls `print()`.
- **Secret values are masked in stored tap logs and exception messages.** A tap script that incidentally printed an API key or Vault-loaded credential would previously have surfaced the raw value in run history; those values are now redacted in the persisted log and the error string.
- **Deleting a tap now cleans up its run history.** Previously, run-history rows accumulated indefinitely and could resurface under a recreated tap with the same name.
- **Agent activity log shows full requests and responses.** The expanded view in the agent monitor no longer truncates request arguments or response bodies.

---

See [archived release notes](release-notes/) for prior versions.
