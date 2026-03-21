package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class DatasetStatus(
                            id: Int, // TODO: remove later
                            dateTime: String,
                            dataset: String,
                            processName: String,
                            publisherToken: String,
                            pipelineToken: String,
                            filename: String,
                            state: String,
                            code: String,
                            description: String,
                            epoch: Long
                        )

case class DatasetStatusTable(
                                 pipeline_token: String,
                                 json: DatasetStatus,
                                 created_at: Long
                             )