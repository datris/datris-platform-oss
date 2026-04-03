package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

case class PipelineConfig(
                            name: String,
                            source: Source = null,
                            preprocessor: RestEndpoint = null,
                            dataQuality: DataQuality = null,
                            transformation: Transformation = null,
                            destination: Destination = null
                        )

case class Source(
                     schemaProperties: SchemaProperties = null,
                     fileAttributes: FileAttributes = null,
                     streamAttributes: StreamAttributes = null,
                     databaseAttributes: DatabaseAttributes = null
                 )

case class Destination(
                          schemaProperties: SchemaProperties = null,
                          database: Database = null,
                          objectStore: ObjectStore = null,
                          restEndpoint: RestEndpoint = null,
                          kafka: Kafka = null,
                          activeMQ: ActiveMQ = null,
                          qdrant: QdrantConfig = null,
                          weaviate: WeaviateConfig = null,
                          pgvector: PGVectorConfig = null,
                          milvus: MilvusConfig = null,
                          chroma: ChromaConfig = null
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
                               fields: java.util.List[SchemaField],
                               schemaVersion: Int = 1
                           )

case class DataQuality(
                          validateFileHeader: Boolean = false,
                          validationSchema: String = null,
                          aiRule: AIRule = null
                      )

case class AIRule @JsonCreator() (
                     @JsonProperty("instruction") instruction: String,
                     @JsonProperty("onFailureIsError") onFailureIsError: Boolean = false
                 )


case class Transformation(
                             trimColumnWhitespace: Boolean = false,
                             deduplicate: Boolean = false,
                             rowFunctions: java.util.List[RowFunction] = null,
                             aiTransformation: AITransformation = null
                         )

case class AITransformation @JsonCreator() (
                               @JsonProperty("instruction") instruction: String
                           )

case class RowFunction(
                          function: String,
                          parameters: java.util.List[String]
                      )

case class FileAttributes(
                             csvAttributes: CsvAttributes = null,
                             jsonAttributes: JsonAttributes = null,
                             xmlAttributes: XmlAttributes = null,
                             xlsAttributes: XlsAttributes = null,
                             unstructuredAttributes: UnstructuredAttributes = null,
                             readOptions: java.util.Map[String, String] = null
                         )

case class CsvAttributes(
                            delimiter: String = ",",
                            header: Boolean = true,
                            encoding: String = "UTF-8"
                        )

case class JsonAttributes(
                             everyRowContainsObject: Boolean = false,
                             encoding: String = "UTF-8"
                         )

case class XmlAttributes(
                            everyRowContainsObject: Boolean = false,
                            encoding: String = "UTF-8"
                        )

case class XlsAttributes(
                            worksheet: Int,
                            tempCsvFileDelimiter: String
                        )

case class UnstructuredAttributes(
                                     fileExtension: String = null,
                                     preserveFilename: Boolean = false
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
                           apiKey: String = null,
                           timeoutSeconds: Int = 0,
                           timeoutMs: Int = 300000
                       )
case class ObjectStore(
                          prefixKey: String = null,
                          partitionBy: java.util.List[String] = null,
                          destinationBucketOverride: String = null,
                          fileFormat: String = null,
                          writeToTemporaryLocation: Boolean = false,
                          deleteBeforeWrite: Boolean = false,
                          writeMode: String = null
                      )

case class Database(
                       dbName: String = null,
                       schema: String = null,
                       table: String = null,
                       keyFields: java.util.List[String] = null,
                       manageTableManually: Boolean = false,
                       truncateBeforeWrite: Boolean = false,
                       useTransaction: Boolean = true,
                       usePostgres: Boolean = false,
                       useMongoDB: Boolean = false,
                       options: java.util.List[String] = null
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

