package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class TapRunLog(
    tapName: String,
    runTime: String,
    status: String,
    recordCount: Int = 0,
    dataType: String = null,
    logs: String = null,
    error: String = null,
    mode: String = "test",
    durationMs: Long = 0,
    publisherToken: String = null,
    // AI fix suggestion, filled in after the retry ladder is exhausted on a
    // cron failure. summary is a one-line headline for list rows; diagnosis +
    // suggestion carry the full explanation for the expanded view.
    aiSummary: String = null,
    aiDiagnosis: String = null,
    aiSuggestion: String = null,
    // Repo-backed taps only: the commit sha the run's script was read at, so
    // a run is reproducible against repo history. Null for MinIO taps.
    scriptCommitSha: String = null,
    // Milliseconds the run waited for a free runner slot (the runner answered
    // busy). Included in durationMs. 0 when it never waited; absent in rows
    // written before this field existed (Gson reads that back as 0).
    queuedMs: Long = 0
)
