package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.{StatusUtil, TransformedColumns}

case class JobContext(
    pipelineToken: String,
    metadata: PipelineMetadata,
    data: Data,
    config: PipelineConfig,
    pipelineProperties: PipelineProperties,
    state: JobState,
    thread: Thread,
    statusUtil: StatusUtil,
    tenantEnvironment: DatrisEnvironment = null,
    // The columns this run's AI transformation added, on a pipeline with a SQL
    // table destination (TransformedColumns.reconcile); null otherwise. Shared
    // by the run's loaders, which record a column they leave out.
    transformationColumns: TransformedColumns.RunColumns = null
)
