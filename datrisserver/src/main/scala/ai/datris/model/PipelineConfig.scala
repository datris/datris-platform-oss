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
    destination: Destination = null,
    catalog: String = null,
    createdByKeyLabel: String = null,
    // Monotonic definition version; immutable snapshots
    // 1..N live in <env>-pipeline-version. Absent field → 1.
    version: Int = 1,
    // Free-form discovery labels ranked by /catalog/find. java.util.List
    // because Gson round-trips this document and its EntityVersion snapshots.
    tags: java.util.List[String] = null,
    // Opt-in provenance stamping (absent/null ⇒ off). See ProvenanceStamper.
    provenance: ProvenanceConfig = null
)

case class ProvenanceConfig @JsonCreator() (
    @JsonProperty("stamp") stamp: Boolean = false,
    // Subset of ProvenanceStamper.AllFields to stamp; null/empty ⇒ all.
    @JsonProperty("fields") fields: java.util.List[String] = null
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
    chunkOverlap: Int = 50,
    // Optional token-count cap enforced during chunking. When set, the
    // chunker stops merging segments before they cross this estimate (via
    // the same TokenCounter the embedding guard uses). Best practice:
    // ~80% of the embedding model's input cap. Without it, the embedding
    // guard is the only safety net.
    maxChunkTokens: Int = 0,
    // Heuristic chars-per-token ratio used when maxChunkTokens is set.
    // Matches the embedding config knob of the same name; lower is more
    // conservative. 2.0 over-counts on English prose.
    tokensPerCharRatio: Double = 2.0
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
    writeMode: String = null,
    // "minio" (default, back-compat) or "s3". Selects the credential
    // path and the per-bucket S3A overrides applied at write time.
    // Region for "s3" lives in the credentialsSecret, not here.
    provider: String = "minio",
    // null => AWS default for provider=s3; ignored for minio.
    endpoint: String = null,
    // Vault secret holding accessKey/secretKey/region (and optional
    // sessionToken) for provider=s3. null + provider=s3 falls back to
    // the AWS DefaultAWSCredentialsProviderChain (instance role).
    credentialsSecret: String = null
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
    useSnowflake: Boolean = false,
    useDatabricks: Boolean = false, // dbName = Unity Catalog catalog
    warehouse: String = null, // Snowflake virtual warehouse name, or Databricks SQL warehouse ID
    role: String = null, // Snowflake role to assume (optional)
    credentialsSecret: String = null, // names a Platform-tab secret holding account/user/auth (Snowflake) or host + clientId/clientSecret or token (Databricks)
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
