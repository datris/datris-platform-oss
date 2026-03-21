package ai.datris.model


/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class Data(
                    size: Long,
                    header: List[String],                   // Contains the header names, only used to validate the header of delimited data
                    headerWithSchema: List[SchemaField],    // Header SchemaFields, this can evolve as the data moves through the pipeline
                    rows: List[String],                     // List of rows, only for delimited data
                    rawData: String,                        // Raw data, only for JSON and XML data
                    rawBytes: Array[Byte] = null            // Raw bytes, for unstructured files (PDF, etc.)
               )
