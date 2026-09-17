package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class PipelineJobError(
    processName: String,
    description: String,
    // One-line AI fix-suggestion headline when a suggestion event exists for
    // this job; agents polling get_pipeline_status see it without replaying
    // the event stream.
    aiSummary: String = null
)

case class PipelineJobRollup(
    pipelineToken: String,
    pipeline: String,
    filename: String,
    status: String, // success | error | warning | processing | timed_out
    startedAt: String,
    lastEventAt: String,
    elapsed: String,
    lastError: PipelineJobError,
    // Scratch-destination result (story: scratch-destination-server). Boxed and
    // null on every job that carries no scratch result, so Gson omits them and
    // existing clients see today's payload unchanged.
    resultUri: String = null,
    resultRowCount: java.lang.Long = null,
    resultExpiresAt: String = null,
    resultPreview: com.google.gson.JsonArray = null,
    resultTruncated: java.lang.Boolean = null
)

case class PipelineStatusRollup(
    allDone: Boolean,
    status: String, // success | error | warning | processing
    jobs: java.util.List[PipelineJobRollup]
)

case class PipelineStatusResponse(
    rollup: PipelineStatusRollup,
    events: java.util.List[PipelineStatus]
)
