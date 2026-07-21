package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class PipelineStatusSummary(
    createdAtTimestamp: String,
    createdAt: Long,
    updatedAt: Long,
    pipeline: String,
    pipelineToken: String,
    process: String,
    startTime: String,
    endTime: String,
    totalTime: String,
    status: String,
    recordCount: Int = 0,
    dataType: String = null
)

case class PipelineStatusSummaryTable(
    pipeline_token: String,
    json: PipelineStatusSummary,
    created_at: Number
)
