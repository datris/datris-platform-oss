# Release Notes

## v1.8.1 — June 2, 2026

**Orchestrate Datris taps from Apache Airflow.**

- **Run taps from Airflow.** A new `airflow-provider-datris` package adds an operator that triggers a tap, waits for the pipeline to finish, streams Datris logs into the Airflow task log, and reports run tokens and row counts back to Airflow. Cancelling the DAG run cancels the Datris job.
- **Date-windowed backfills.** Taps can now take per-run parameters, so an Airflow DAG can pass its logical date (or any window) into the tap for that run — backfills and incremental loads work without editing the tap.
- **No double-firing.** A tap is scheduled by Datris or Airflow, never both: if a tap has a Datris cron, the Airflow operator declines to trigger it. To drive a tap from Airflow, leave its cron empty.

**Upgrading**

- Existing installs: `docker compose pull && docker compose up -d --force-recreate datris ui mcp-server`. No data migration needed.
- Install the Airflow provider where Airflow runs: `pip install airflow-provider-datris`.

---

## v1.8.0 — May 29, 2026

**Write to AWS S3, query Parquet and ORC from the Assistant and Search, and stop chats actually stop.**

See [archived v1.8.0 release notes](release-notes/v1.8.0.md).

---

## v1.7.9 — May 28, 2026

**Ask the Ops assistant about a failing pipeline without leaving the dashboard.**

See [archived v1.7.9 release notes](release-notes/v1.7.9.md).

---

See [archived release notes](release-notes/) for prior versions.
