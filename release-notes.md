# Release Notes

## v{version} — {date}

**A pipeline run is no longer limited by memory.**

- **Payload size no longer limits a run.** Files uploaded over the API, archives uploaded as a batch, files picked up from the object store and records returned by taps all stream to disk as they arrive instead of being held in the server's memory. A multi-GB upload or tap run now completes on the default 2 GB heap, and the server's memory use no longer grows with the size of the data passing through it.
- **The tap output cap becomes a per-run disk budget.** `PIPELINE_MAX_PAYLOAD_MB` (default 4096 MB, `0` = unlimited) is the most one run may stage on disk; a run that exceeds it stops with a message naming the variable and leaves nothing behind. Installs that never set the old `TAP_MAX_OUTPUT_MB` go from a 100 MB in-memory ceiling to 4 GB of disk. `TAP_MAX_OUTPUT_MB` is still honoured for one more release, with a warning at startup; rename it now.
- **Uploads honour the same budget.** An uploaded file — or the combined contents of a batch archive — above the run budget fails with the same disk-budget message. The upload size ceiling stays at 1 GB by default and is now configurable with `DATRIS_MAX_UPLOAD_MB`.
- **Features that must read the whole payload say so.** Deduplication, JavaScript row functions, JSON/XML schema validation, single-call REST preprocessors and destinations, and single-message Kafka, ActiveMQ and scratch destinations keep working exactly as before below `PIPELINE_MATERIALIZE_MAX_MB` (256 MB). Above it the run stops with a message naming the feature and the setting, instead of running out of memory. CodeGen rules and transformations, provenance stamping and every database, warehouse and object-store destination stream and are never subject to it.
- **REST preprocessors and destinations can batch.** A `batchSize` on a REST preprocessor or destination sends the rows in batches of that size, each call carrying its batch number and the batch count; a REST row function in batch mode accepts a batch size as its sixth parameter. Without a batch size, both keep today's single call.
- **Record counts are right for more shapes of data.** Newline-delimited JSON input reports its record count instead of 1; a quoted value with an embedded newline counts and loads as one row, including through a CodeGen transformation and into an object-store destination; a quoted value containing the delimiter lands in one column of an object-store destination instead of being split.
- **Bad input fails at the door.** Invalid JSON on a JSON pipeline is rejected when it is received, before a run is created; a file whose header contains none of the pipeline's columns fails with a message naming both sides instead of loading blank rows.
- **Testing a tap returns a sample.** A tap test returns the first 20 records and says whether more were cut, instead of every record.
- **`datris doctor` watches the staging area.** Two new checks report whether the staging area is writable with room for one run, and whether run directories left behind by a crash are accumulating.

**Upgrading**

Run `datris doctor --pre-upgrade` first, then `docker compose pull && docker compose up -d --force-recreate`. Both the server and the tap-runner images must be updated together; an older tap-runner still works but keeps its previous in-memory path. The compose file adds one named volume, `datris-staging`, for staged payloads — it is created on first start and needs no action. The bundled compose file now needs Docker Compose v2. No configuration changes are required; if `.env` sets `TAP_MAX_OUTPUT_MB`, rename it to `PIPELINE_MAX_PAYLOAD_MB` before the next release.
