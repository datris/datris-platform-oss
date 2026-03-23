package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class PipelinePull(
                          pipeline: String,
                          nextPullDate: String,
                          lastPullTimestampUsed: String
                       )
