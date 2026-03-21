package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

case class DatasetConfig(
                            name: String,
                            source: Source,
                            preprocessor: RestEndpoint,
                            dataQuality: DataQuality,
                            transformation: Transformation,
                            destination: Destination
                        )

case class Source(
                     schemaProperties: SchemaProperties,
                     fileAttributes: FileAttributes,
                     streamAttributes: StreamAttributes,
                     databaseAttributes: DatabaseAttributes
                 )

case class Destination(
                          schemaProperties: SchemaProperties,
                          database: Database,
                          objectStore: ObjectStore,
                          restEndpoint: RestEndpoint,
                          kafka: Kafka,
                          activeMQ: ActiveMQ,
                          qdrant: QdrantConfig,
                          weaviate: WeaviateConfig,
                          pgvector: PGVectorConfig,
                          milvus: MilvusConfig,
                          chroma: ChromaConfig
                      )

case class QdrantConfig(
                           collectionName: String,
                           chunking: ChunkingConfig,
                           metadata: java.util.Map[String, String],
                           embeddingSecretName: String,
                           qdrantSecretName: String
                       )

case class WeaviateConfig(
                             className: String,
                             chunking: ChunkingConfig,
                             metadata: java.util.Map[String, String],
                             embeddingSecretName: String,
                             weaviateSecretName: String
                         )

case class PGVectorConfig(
                             tableName: String,
                             schemaName: String,
                             chunking: ChunkingConfig,
                             metadata: java.util.Map[String, String],
                             embeddingSecretName: String,
                             postgresSecretName: String
                         )

case class MilvusConfig(
                           collectionName: String,
                           chunking: ChunkingConfig,
                           metadata: java.util.Map[String, String],
                           embeddingSecretName: String,
                           milvusSecretName: String
                       )

case class ChromaConfig(
                           collectionName: String,
                           chunking: ChunkingConfig,
                           metadata: java.util.Map[String, String],
                           embeddingSecretName: String,
                           chromaSecretName: String
                       )

case class ChunkingConfig(
                             strategy: String = "recursive",
                             chunkSize: Int = 500,
                             chunkOverlap: Int = 50
                         )

case class SchemaProperties(
                               dbName: String,
                               fields: java.util.List[SchemaField]
                           )

case class DataQuality(
                          validateFileHeader: Boolean,
                          validationSchema: String,
                          aiRule: AIRule,
                          rowRules: java.util.ArrayList[RowRule],
                          columnRules: java.util.List[ColumnRule]
                      )

case class AIRule(
                     instruction: String,
                     onFailureIsError: Boolean,
                     sample: Boolean = false,
                     sampleSize: Int = 200
                 )

case class RowRule(
                      function: String,
                      parameters: java.util.List[String],
                      onFailureIsError: Boolean
                  )

case class ColumnRule(
                         columnName: String,
                         function: String,
                         parameter: String,
                         batchSize: Int = 100,
                         onFailureIsError: Boolean,
                         description: String
                     )

case class Transformation(
                             trimColumnWhitespace: Boolean,
                             deduplicate: Boolean,
                             rowFunctions: java.util.List[RowFunction],
                             aiTransformation: AITransformation
                         )

case class AITransformation(
                               instruction: String,
                               sample: Boolean = false,
                               sampleSize: Int = 200
                           )

case class RowFunction(
                          function: String,
                          parameters: java.util.List[String]
                      )

case class FileAttributes(
                             csvAttributes: CsvAttributes,
                             jsonAttributes: JsonAttributes,
                             xmlAttributes: XmlAttributes,
                             xlsAttributes: XlsAttributes,
                             unstructuredAttributes: UnstructuredAttributes,
                             readOptions: java.util.Map[String, String]
                         )

case class CsvAttributes(
                            delimiter: String,
                            header: Boolean,
                            encoding: String,     // UTF-8, ISO-8859-1, etc]
                        )

case class JsonAttributes(
                             everyRowContainsObject: Boolean,   // If true, each row of the file contains a JSON object
                             encoding: String,     // UTF-8, ISO-8859-1, etc]
                         )

case class XmlAttributes(
                            everyRowContainsObject: Boolean,    // If true, each row of the file contains an XML object
                            encoding: String,     // UTF-8, ISO-8859-1, etc
                        )

case class XlsAttributes(
                            worksheet: Int,
                            tempCsvFileDelimiter: String
                        )

case class UnstructuredAttributes(
                                     fileExtension: String,
                                     preserveFilename: Boolean
                                 )

case class StreamAttributes(
                               `type`: String
                           )

case class DatabaseAttributes(
                                 `type`: String,
                                 postgresSecretsName: String,
                                 mssqlSecretsName: String,
                                 mysqlSecretsName: String,
                                 cronExpression: String,
                                 database: String,
                                 schema: String,
                                 table: String,
                                 includeFields: java.util.List[String],
                                 timestampFieldName: String,
                                 sqlOverride: String,
                                 outputDelimiter: String
                             )
case class RestEndpoint(
                           endpoint: String,
                           async: Boolean = false,
                           bearerToken: String = null,
                           timeoutSeconds: Int = 300
                       )
case class ObjectStore(
                          prefixKey: String,
                          partitionBy: java.util.List[String],
                          destinationBucketOverride: String,
                          fileFormat: String,
                          writeToTemporaryLocation: Boolean,
                          deleteBeforeWrite: Boolean,
                          writeMode: String
                      )

case class Database(
                       dbName: String, // Database name
                       schema: String,
                       table: String, // Table name
                       keyFields: java.util.List[String],
                       manageTableManually: Boolean,
                       truncateBeforeWrite: Boolean,
                       useTransaction: Boolean,
                       usePostgres: Boolean,
                       useMongoDB: Boolean,
                       options: java.util.List[String]
                   )

case class Kafka(
                    topic: String,
                    keyField: String,
                    overrideBootstrapServers: String,
                    timeoutMs: Int = 10000
                )

case class ActiveMQ @JsonCreator() (
                    @JsonProperty("queueName") queueName: String
                    )

