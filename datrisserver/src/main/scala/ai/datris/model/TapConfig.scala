package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class TapConfig(
    name: String,
    description: String,
    scriptPath: String = null,
    targetPipeline: String,
    packages: java.util.List[String] = null,
    secretName: String = null,
    cronExpression: String = null,
    enabled: Boolean = true,
    tapType: String = "structured",
    lastRunStatus: String = null,
    lastRunTime: String = null,
    lastRunRecordCount: Int = 0,
    lastRunError: String = null,
    lastRunDataType: String = null,
    lastRunColumns: java.util.List[String] = null,
    lastTestRunStatus: String = null,
    lastTestRunTime: String = null,
    lastTestRunRecordCount: Int = 0,
    lastTestRunError: String = null,
    lastTestRunDataType: String = null,
    lastTestRunColumns: java.util.List[String] = null,
    createdAt: String = null,
    updatedAt: String = null,
    catalog: String = null,
    createdByKeyLabel: String = null,
    // Monotonic definition version. The live document is always the latest
    // version N; immutable snapshots 1..N live in <env>-tap-version. Defaults
    // to 1 so pre-versioning taps deserialize cleanly (absent field → 1).
    version: Int = 1,
    // What initiated the last real run: "cron" | "manual". Only cron-triggered
    // failures are auto-retried; a manual run stamps this and stops the retry
    // ladder (the user is at the wheel). Absent on pre-existing docs → null →
    // never auto-retried until their next run stamps it.
    lastRunTrigger: String = null,
    // True only when the failed run provably fed nothing downstream (script
    // errored before any pipeline submission), so a re-run cannot double-write.
    lastRunRetrySafe: Boolean = false,
    // Consecutive automatic retries since the last successful run. Reset to 0
    // on success/no_records; capped by cronRetryCap.
    retryCount: Int = 0,
    // Script storage backend: null/"minio" ⇒ built-in object store (scriptPath
    // is authoritative); "github" ⇒ the tenant's configured code repository
    // (scriptRepoPath + scriptCommitSha are authoritative, scriptPath unused).
    // Flat strings rather than a sealed ref type because Gson round-trips this
    // document and its EntityVersion snapshots.
    scriptStorage: String = null,
    // Repo-relative path of the script when scriptStorage == "github".
    scriptRepoPath: String = null,
    // Commit SHA the script is pinned to. Runs read exactly this commit; the
    // drift-pull endpoint advances it when the user accepts external edits.
    scriptCommitSha: String = null,
    // Tap implementation kind: null/"python" ⇒ Python script executed by the
    // platform (current behavior); "http" ⇒ user-hosted endpoint speaking the
    // tap HTTP contract (endpointUrl is authoritative; script/packages fields
    // unused). Flat string for the same Gson round-trip reason as scriptStorage.
    scriptKind: String = null,
    // Endpoint POSTed to on each run when scriptKind == "http".
    endpointUrl: String = null
) {

    /** True when this tap is a user-hosted HTTP endpoint rather than a
      * platform-executed Python script. Gson serializes fields only, so this
      * derived accessor never appears in stored documents. */
    def isHttp: Boolean = "http".equalsIgnoreCase(scriptKind)
}
