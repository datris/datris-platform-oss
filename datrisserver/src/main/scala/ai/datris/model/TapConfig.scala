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
    catalog: String = null
)
