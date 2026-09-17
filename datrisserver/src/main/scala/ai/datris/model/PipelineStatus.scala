package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class PipelineStatus(
    id: Int, // TODO: remove later
    dateTime: String,
    pipeline: String,
    processName: String,
    publisherToken: String,
    pipelineToken: String,
    filename: String,
    state: String,
    code: String,
    description: String,
    epoch: Long,
    // AI fix suggestion, set only on the suggestion event written after a job
    // failure. summary is a one-line headline; diagnosis + suggestion carry
    // the full advisory text for the detail view.
    aiSummary: String = null,
    aiDiagnosis: String = null,
    aiSuggestion: String = null,
    // Scratch-destination result pointer, set only on the event ScratchLoader
    // writes after landing the object (StatusUtil.scratchResult).
    scratchResult: ScratchResult = null
)

case class PipelineStatusTable(
    pipeline_token: String,
    json: PipelineStatus,
    created_at: Long
)
