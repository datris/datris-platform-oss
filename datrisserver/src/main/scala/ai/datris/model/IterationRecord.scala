package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class IterationRecord(
    attempt: Int = 0,
    trigger: String = null,
    scriptDigest: String = null,
    outcome: String = null,
    recordCount: Int = 0,
    durationMs: Long = 0,
    error: String = null,
    diagnosis: String = null,
    appliedChange: String = null
)
