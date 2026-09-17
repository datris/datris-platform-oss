# Release Notes

## v1.31.0 — September 16, 2026

**Pipelines can now land data as Apache Iceberg tables.**

- **Iceberg as an output format.** Choose `iceberg` as the file format on an object-store or S3 destination and each run commits a real Iceberg table at the pipeline's location on the built-in MinIO or on AWS S3: Parquet data files plus the table metadata that tells any Iceberg-aware engine which files make up the table. Every run is one atomic commit, so a failed run leaves the previous snapshot intact and readers never see a half-written table. No new destination type and no extra service to run. See [Iceberg](https://docs.datris.ai/destinations/iceberg).
- **Upserts by key.** A new `merge` write mode, alongside `append` and `overwrite`, updates rows that match the configured key fields and inserts the rest. Re-running the same data leaves the row count unchanged. Key fields work the same way as on the database and warehouse destinations.
- **Partitioning and schema evolution.** Partition the table by one or more columns. New nullable columns in the destination schema are added to the table automatically; type changes, dropped columns, and new required columns are refused before anything is written, with a message naming the column, and the table stays at its previous snapshot.
- **Readable without Datris.** The table is fully described by the files at its location, so standard Iceberg tooling opens it by path with the same bucket credentials the pipeline uses. Time travel, catalog registration, table maintenance, and Iceberg as a tap source are not in this release; the tables written today will not need rewriting when they arrive.
- **Snapshot in query results.** Querying an object-store pipeline now reports which table snapshot the rows came from, for Iceberg tables. The pipeline wizard, the MCP tools, and the Assistant all know the new format and modes.
- **Lineage shows the format.** Object-store destinations in the lineage graph are labelled with their table or file format.
- **Fix: querying a pipeline that has never run.** Querying an object-store pipeline before its first run now returns an empty result instead of an error. As a consequence, a pipeline whose bucket does not exist also reads as empty.
- **Overlapping runs no longer interleave.** Two runs of pipelines that write the same object-store location now run one after the other, for every object-store format.
- **S3 credential changes apply immediately.** Correcting or rotating S3 credentials in a secret now takes effect on the next run without a restart.

**Upgrading**

Run `datris doctor --pre-upgrade` first, then `docker compose pull && docker compose up -d --force-recreate`. No configuration changes required; existing Parquet and ORC pipelines are unaffected and the default format is still Parquet. Switching an existing Parquet or ORC pipeline to Iceberg in place is refused unless `deleteBeforeWrite` is set, because the old files and the new table cannot share a location; point it at a new prefix instead to keep the old files. The server image is about 50 MB larger.
