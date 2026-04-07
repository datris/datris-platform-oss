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
    pushToPipeline: Boolean = false,
    durationMs: Long = 0
)
