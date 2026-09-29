package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Last Unity Catalog metadata sync for one pipeline (`<env>-uc-sync`).
  * `<kind>Hash` is the SHA-256 of the SQL last applied successfully for that
  * statement group ("comments", "tags", "properties"); null means the group
  * has never succeeded (or failed last time), so the next run re-issues it.
  * `lastError` is null after a clean sync. All timestamps are ISO-8601 UTC. */
case class UnityCatalogSyncState(
    pipeline: String,
    lastSyncAt: String,
    lastRunId: String,
    commentsHash: String,
    tagsHash: String,
    propertiesHash: String,
    lastError: String
)
