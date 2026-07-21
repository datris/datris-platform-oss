package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** One immutable snapshot of a tap or pipeline *definition* at a point in time.
  * Lives in the append-only `<env>-tap-version` / `<env>-pipeline-version`
  * collections. The live config document is always the latest version N; these
  * records hold 1..N. See plans/tap-pipeline-versioning.md.
  *
  * Stored flat (not nested under `value`) so the collection can be queried by
  * `entityName` and pruned/deleted by `key`, mirroring the `<env>-tap-log`
  * idiom.
  *
  * @param key        unique doc key, `entityName + "|" + version`
  * @param entityName tap or pipeline name
  * @param version    monotonic per entity, starts at 1
  * @param config     full config snapshot as a raw JSON string (Gson-serialized
  *                   TapConfig / PipelineConfig). Parsed back on restore/diff.
  * @param scriptPath taps only — the script object this version pinned; null for pipelines
  * @param changeNote optional human/agent note ("created", "restored from version 3", ...)
  * @param createdAt  ISO-ish timestamp in the env's configured format
  * @param createdBy  actor — username (useUserAuth), API key label (useApiKeys/MCP), or "system"
  */
case class EntityVersion(
    key: String,
    entityName: String,
    version: Int,
    config: String,
    scriptPath: String = null,
    changeNote: String = null,
    createdAt: String = null,
    createdBy: String = null
)
