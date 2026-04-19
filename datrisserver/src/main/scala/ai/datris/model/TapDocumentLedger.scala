package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class TapDocumentLedger(
    uri: String,
    tapName: String,
    stagedPath: String,
    filename: String,
    contentHash: String,
    firstSeenAt: String,
    lastSeenAt: String,
    status: String,
    metadata: java.util.Map[String, String] = null
)
