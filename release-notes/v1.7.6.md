# Release Notes

## v1.7.6 — May 26, 2026

**A new Ops Activity dashboard, Postgres pipelines learn upsert, per-run tap parameters, and a safer agent workflow.**

- **Ops Activity dashboard — at-a-glance ingestion health.** A new tab under **Ops** pulls every tap and pipeline run in a rolling window (24h / 7d / 30d) into KPI tiles, time-series charts of runs and items ingested, a per-pipeline 7-day volume table, and a Failures pane that dedupes by item with attempt counts. Each unrecovered row has a **Re-run** button — for pipeline failures with an upstream tap, the button re-runs that tap to retry the load so you don't have to hunt for it in the Catalog.
- **Postgres pipelines upsert on conflict when `keyFields` is set.** Matches the semantics Mongo has always had. Backfills over already-loaded dates, incremental taps with overlap, and "load again with the same key" flows now upsert instead of failing with a duplicate-key error. If you retrofit `keyFields` onto an existing table, the platform adds the matching unique index for you on the next load — or surfaces a clear remediation message if existing data violates the proposed key.
- **Per-run tap parameters.** Run a tap with caller-supplied values — date ranges, ticker lists, page cursors, batch sizes — without rewriting the script or the secret. Pass `params` to `run_tap` and the script reads them as env vars for that one run only. Scheduled cron runs see an empty params bag and fall back to script defaults, so the same script handles both manual ad-hoc calls and unattended schedules without branching.
- **"No records" is no longer a failure.** A tap that runs cleanly and returns zero rows — a polling tap on a quiet day, an incremental tap that's caught up, a market tap on a weekend — now records a distinct `no_records` status with a neutral badge. Doesn't count in the Failures tile, doesn't fire bogus "recovered" badges, doesn't train agents to interpret "no new data" as "platform broken."
- **Large tap outputs fail fast with an actionable error.** A backfill that exceeds the size cap (default 100 MB) now stops with a clear message telling you and the Assistant to chunk the source range smaller — instead of OOM-killing the server. Multiple smaller runs all land in the same destination pipeline; with `keyFields` set, overlapping ranges upsert safely.
- **Concurrent tap runs no longer race.** Two pipelines that loaded data in the same millisecond previously risked landing each other's records in the wrong destination under specific timing. Fixed at the source; no action required.
- **Secret values stay masked in Configuration.** The Secrets tab now masks any field whose name suggests a credential — passwords, tokens, API keys, signing keys, certificates, and named variants of those. No action required.
- **Tighter Assistant workflow.** Three new disciplines:
  - **Scheduling lives on the tap.** Say "every morning" or "at market open" and the Assistant sets the CRON expression on the tap itself — instead of handing back a `cron` line for you to wire up yourself.
  - **Test before first run.** A newly-created or just-edited script is validated before any real run, and before being put on a schedule — no more "guaranteed-bad nightly run" the next time the cron fires.
  - **No confabulated progress.** If the Assistant intended to do N things and only did M of them, it tells you which M happened rather than narrating all N as complete.

  The credential form the Assistant pops up now asks only for true secrets — not for configuration values you already typed into chat.
- **Agent Monitor stays responsive during long tool calls.** Running a slow tool no longer freezes the Connections viz or the Activity Log — they keep streaming. Returning to the Agent Monitor tab refreshes the log to current server state instead of showing a stale empty view.
- **Configurable JVM heap.** The bundled `datris` service has a sensible default heap size for small hosts (8 GB) and a clean way to bump it on larger machines via `.env`. See the installation guide for the suggested sizings per host RAM.
- **Ops tab moved after Search** in the top navigation. Mostly cosmetic; route URLs are unchanged so bookmarks and deep links still work.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed. Existing Postgres pipelines with `keyFields` automatically pick up the upsert path on the next load.
- The `datris` CLI: `brew upgrade datris`.

---

See [archived release notes](release-notes/) for prior versions.
