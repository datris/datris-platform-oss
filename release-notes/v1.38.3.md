# Release Notes

## v1.38.3 — September 27, 2026

**Clearer errors for files whose names cannot be parsed.**

- **The real ingest error is reported.** When an uploaded file's name cannot be matched to a pipeline, the log now says so instead of reporting an unrelated internal error. Files that fail later in ingestion still record their status exactly as before.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. Only the server image changed; no configuration changes are needed.
