# Release Notes

## v1.38.2 — September 26, 2026

**Object-store ingestion is more forgiving: a bad file no longer blocks its neighbours, and root-level uploads work.**

- **One bad file no longer stops the other files uploaded at the same time.** When several files land in the raw bucket together and one of them cannot be read (empty, malformed header, unknown pipeline), the others still run in the same pass. The failure is logged with its bucket and file name.
- **Uploads dropped in the root of the raw bucket are now ingested.** A file placed directly in the bucket, with no folder prefix, is picked up like any other instead of failing with a naming-convention error.
- **Every file in a multi-file event is ingested.** An event carrying several files for the same bucket now processes each distinct file; previously only one of them was kept.

**Upgrading**

Run `docker compose pull && docker compose up -d --force-recreate`. Only the server image changed; no configuration changes are needed. Failed files are still not retried automatically, as before.
