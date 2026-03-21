package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class DatasetStatusSummary(
                                   createdAtTimestamp: String,
                                   createdAt: Long,
                                   updatedAt: Long,
                                   dataset: String,
                                   pipelineToken: String,
                                   process: String,
                                   startTime: String,
                                   endTime: String,
                                   totalTime: String,
                                   status: String
                       )

case class DatasetStatusSummaryTable(
                                        pipeline_token: String,
                                        json: DatasetStatusSummary,
                                        created_at: Number
                                    )

