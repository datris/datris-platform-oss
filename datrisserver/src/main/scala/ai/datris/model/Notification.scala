package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class Notification(
                           pipeline: String,
                           publisherToken: String,
                           pipelineToken: String,
                           destination: String,
                           prefixKey: String,
                           objectStoreUrl: String,
                           objectStoreTemporaryUrl: String,
                           schema: String,
                           database: String,
                           table: String,
                           topic: String,
                           queueName: String = null
                       )