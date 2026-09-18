package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonArray

/** Pointer to a scratch-destination run's result, recorded once on the status
  * stream by ScratchLoader (StatusUtil.scratchResult) and copied onto the job
  * rollup by PipelineStatusUtil.classifyJob.
  *
  * - resultUri: the object written, s3a://<env>-data/_scratch/<pipeline>/<token>.jsonl
  * - resultRowCount: records in that object
  * - resultExpiresAt: ISO-8601 UTC instant after which the object may be swept
  * - resultPreview: the first scratchInlineRows records (byte-capped too)
  * - resultTruncated: true when the preview holds fewer records than the object
  */
case class ScratchResult(
    resultUri: String,
    resultRowCount: Long,
    resultExpiresAt: String,
    resultPreview: JsonArray,
    resultTruncated: Boolean
)
