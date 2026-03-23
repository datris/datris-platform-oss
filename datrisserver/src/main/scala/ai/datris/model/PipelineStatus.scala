package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class PipelineStatus(
                            id: Int, // TODO: remove later
                            dateTime: String,
                            pipeline: String,
                            processName: String,
                            publisherToken: String,
                            pipelineToken: String,
                            filename: String,
                            state: String,
                            code: String,
                            description: String,
                            epoch: Long
                        )

case class PipelineStatusTable(
                                 pipeline_token: String,
                                 json: PipelineStatus,
                                 created_at: Long
                             )