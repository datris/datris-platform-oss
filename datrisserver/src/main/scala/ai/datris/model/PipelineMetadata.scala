package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class PipelineMetadata(
    pipeline: String,
    dataFileName: String,
    dataFilePath: String,
    publisherToken: String,
    bulkUpload: Boolean, // If true, the dataFilePath contains the path to the bulk upload files
    // Tap-fed jobs only: identity of the feeding tap run, threaded through so
    // ProvenanceStamper can name the run without a lookup. Null on direct
    // uploads, file drops, and archived metadata written before these existed.
    tapName: String = null,
    tapRunTime: String = null,
    tapScriptSha: String = null,
    tapSource: String = null
)

/** Carrier for tap-run identity from TapRunner into StreamNotifier, so a
  * stamped row can say which tap run produced it. `source` is the tap's
  * declared source (URL host for HTTP taps, `tap:<name>` otherwise) — never
  * credentials. */
case class TapFeedInfo(
    tapName: String,
    runTime: String,
    scriptCommitSha: String,
    source: String
)
