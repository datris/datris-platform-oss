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
    version: Int = 1
)
