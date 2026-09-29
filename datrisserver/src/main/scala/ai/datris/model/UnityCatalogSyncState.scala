package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** Last Unity Catalog metadata sync for one pipeline (`<env>-uc-sync`).
  * `<kind>Hash` is the SHA-256 of the SQL last applied successfully for that
  * statement group ("comments", "tags", "properties"); null means the group
  * has never succeeded (or failed last time), so the next run re-issues it.
  * `lastError` is null after a clean sync; lineage failures are lines
  * prefixed `uc-lineage:`. All timestamps are ISO-8601 UTC. */
case class UnityCatalogSyncState(
    pipeline: String,
    lastSyncAt: String,
    lastRunId: String,
    commentsHash: String,
    tagsHash: String,
    propertiesHash: String,
    lastError: String,
    // Lineage publish (UnityCatalogLineagePublisher). Null on docs written
    // before lineage existed ⇒ "never published", which forces a full publish.
    // `lineageHash` covers the definitional payload (config version, column
    // lineage, tap script); `lineageRelationshipIds` caches the UC relationship
    // ids under keys "source" (tap/upload → pipeline) and "table" (pipeline →
    // table) so steady-state runs PATCH run properties without listing.
    lineageHash: String = null,
    lastLineageAt: String = null,
    lineageRelationshipIds: java.util.Map[String, String] = null
)
