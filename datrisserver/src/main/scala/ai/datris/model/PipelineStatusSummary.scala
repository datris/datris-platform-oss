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
    dataType: String = null,
    // One-line AI fix-suggestion headline for failed jobs, so the Ops →
    // Ingestion list can show it inline without a click-through. Full
    // diagnosis/suggestion live on the PipelineStatus suggestion event.
    aiSummary: String = null
)

case class PipelineStatusSummaryTable(
    pipeline_token: String,
    json: PipelineStatusSummary,
    created_at: Number
)
