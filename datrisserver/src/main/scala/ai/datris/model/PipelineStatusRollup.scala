package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class PipelineJobError(
                               processName: String,
                               description: String
                           )

case class PipelineJobRollup(
                                pipelineToken: String,
                                pipeline: String,
                                filename: String,
                                status: String, // success | error | warning | processing | timed_out
                                startedAt: String,
                                lastEventAt: String,
                                elapsed: String,
                                lastError: PipelineJobError
                            )

case class PipelineStatusRollup(
                                   allDone: Boolean,
                                   status: String, // success | error | warning | processing
                                   jobs: java.util.List[PipelineJobRollup]
                               )

case class PipelineStatusResponse(
                                     rollup: PipelineStatusRollup,
                                     events: java.util.List[PipelineStatus]
                                 )
