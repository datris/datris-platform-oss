package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class Status(
                     processName: String,
                     publisherToken: String,
                     pipelineToken: String,
                     filename: String,
                     state: String,
                     code: String,
                     description: String
                 )