package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

case class PipelineMetadata(
    pipeline: String,
    dataFileName: String,
    dataFilePath: String,
    publisherToken: String,
    bulkUpload: Boolean // If true, the dataFilePath contains the path to the bulk upload files
)
