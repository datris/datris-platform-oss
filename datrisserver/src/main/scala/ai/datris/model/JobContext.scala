package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.StatusUtil

case class JobContext(
    pipelineToken: String,
    metadata: PipelineMetadata,
    data: Data,
    config: PipelineConfig,
    pipelineProperties: PipelineProperties,
    state: JobState,
    thread: Thread,
    statusUtil: StatusUtil,
    tenantEnvironment: DatrisEnvironment = null
)
