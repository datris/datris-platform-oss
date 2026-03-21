package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.util.StatusUtil

case class JobContext(
                         pipelineToken: String,
                         metadata: DatasetMetadata,
                         data: Data,
                         config: DatasetConfig,
                         datasetProperties: DatasetProperties,
                         state: JobState,
                         thread: Thread,
                         statusUtil: StatusUtil
                     )

