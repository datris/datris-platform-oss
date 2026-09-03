package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

/** What one pipeline run read. `kind` is `tap` for tap-fed runs and
  * `upload` for direct uploads / file drops. Tap identity mirrors the fields
  * ProvenanceStamper writes, so a stamped row and its run-lineage doc agree. */
case class RunLineageInput(
    kind: String,
    tapName: String = null,
    tapRunTime: String = null,
    scriptSha: String = null,
    source: String = null,
    filename: String = null
)

/** One destination a run actually invoked. `datasetId` is the lineage node
  * id (`dataset:<kind>:<coords>`) when the destination is a landed dataset,
  * null for pass-through targets (REST endpoint). `status` is per
  * destination: SUCCESS, ERROR, or UNKNOWN when the job failed before this
  * loader finished (its outcome was never observed). */
case class RunLineageOutput(
    kind: String,
    coords: String = null,
    datasetId: String = null,
    status: String = "UNKNOWN",
    recordCount: Int = 0,
    error: String = null
)

/** One document per pipeline run in `<env>-run-lineage`: what the run read,
  * what it wrote, and how it ended. Written by JobRunner after the loaders
  * complete, regardless of `provenance.stamp` (it is metadata, not a row
  * mutation), and never allowed to fail the job. */
case class RunLineage(
    runId: String,
    pipeline: String,
    configVersion: Int = 1,
    input: RunLineageInput = null,
    outputs: java.util.List[RunLineageOutput] = null,
    recordCount: Int = 0,
    dataType: String = null,
    startedAt: String = null,
    completedAt: String = null,
    durationMs: Long = 0,
    status: String = null
)
