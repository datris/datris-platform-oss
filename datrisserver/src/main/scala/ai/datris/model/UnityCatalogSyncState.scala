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
  * prefixed `uc-lineage:`, register failures `uc-register:`, REST catalog lines `uc-rest:`. All timestamps are ISO-8601 UTC. */
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
    lineageRelationshipIds: java.util.Map[String, String] = null,
    // Iceberg register (IcebergCatalogRegistrar, object-store destinations).
    // Null on older docs ⇒ "never registered". `registeredMetadataLocation` is
    // our metadata file at the last register (or at the last stale-pointer
    // check); failures and the stale warning are `uc-register:` lines in
    // `lastError`.
    registeredMetadataLocation: String = null,
    lastRegisterAt: String = null,
    // Iceberg via RESTCatalog (`unityCatalog.catalogMode: rest`,
    // IcebergRestSession). Null on older docs ⇒ mode `register`.
    // `catalogMode` is what the last run did: `register`, `rest` (the commit
    // went through the catalog) or `refused` (the catalog path was refused or
    // failed before the commit and the run wrote path-based; the reason is in
    // `restRefusedReason`). `restMetadataLocation` / `lastRestCommitAt` are
    // the metadata file and time of the last catalog commit.
    catalogMode: String = null,
    restMetadataLocation: String = null,
    lastRestCommitAt: String = null,
    restRefusedReason: String = null,
    // `<catalog>.<schema>.<table>` of a table the catalog created for this
    // pipeline at a location Datris refused (a Databricks managed table):
    // never written to, but it exists in the catalog until an admin drops it.
    // Cleared by the next successful catalog commit.
    restCreatedTable: String = null
)
