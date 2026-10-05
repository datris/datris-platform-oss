package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class Status(
    processName: String,
    publisherToken: String,
    pipelineToken: String,
    filename: String,
    state: String,
    code: String,
    description: String,
    // Optional long-form text for the detail view (e.g. the full stack trace
    // of a failed run). Null when absent; summaries never read it.
    detail: String = null
)
