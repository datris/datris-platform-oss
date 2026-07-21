package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** MongoDB configuration.
  *
  * Two databases are split intentionally:
  *   - `database`         — USER-facing: where pipelines write and the UI shows as
  *                          the destination. User data lives here.
  *   - `internalDatabase` — PLATFORM-internal: where the server persists its own
  *                          state (pipeline/tap configs, run status, job queues).
  *                          Never surfaced in the UI.
  *
  * Never conflate the two. See util/package.scala for the internal singleton
  * and MetadataAPIController / PipelineAPIController for the user side.
  */
case class MongoDBConfig(
    connectionString: String,
    database: String,
    internalDatabase: String
)
