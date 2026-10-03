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
    provenance: ProvenanceConfig = null,
    // Field protection options (absent/null ⇒ defaults). Only read when a
    // source field carries `protect`. See FieldProtection.
    protection: ProtectionConfig = null,
    // Source of authority (lineage L5b). null/true ⇒ the pipeline's datasets may
    // be the system of record (a single destination is authoritative by
    // default; several need Destination.authoritative to pick one). false ⇒
    // every dataset this pipeline lands is a derived copy (a rollup, a replica,
    // a vector index built from a table). Boxed so "unset" survives Gson.
    authoritative: java.lang.Boolean = null,
    // Opt-in Unity Catalog metadata push (absent/null ⇒ off). Databricks
    // destinations only (PipelineValidatorUtil). See UnityCatalogMetadataSync.
    unityCatalog: UnityCatalogSync = null
)

/** `unityCatalog: {"enabled": true}` — after each successful Databricks load,
  * push a table comment, `_datris_*` column comments, four stable tags and
  * run-level TBLPROPERTIES to Unity Catalog, and publish External Lineage
  * (tap or upload → pipeline → table). Each knob drops its group.
  *
  * The knobs are boxed and null means on: Spring's `@RequestBody` Jackson
  * mapper does not apply Scala default arguments (an absent Boolean arrives
  * as `false`), and Gson skips constructors on config DB reads. Read them
  * through `commentsOn` / `tagsOn` / `propertiesOn` / `lineageOn` /
  * `registerOn`.
  *
  * Install default (`DATRIS_UNITY_CATALOG_DEFAULT=enabled`): a Databricks
  * pipeline with NO block behaves as `{"enabled": true}` (every knob on).
  * An explicit block always decides (`{"enabled": false}` opts out); the
  * kill switch `DATRIS_UNITY_CATALOG_SYNC=false` still beats everything; and
  * object-store Iceberg pipelines are never defaulted (registration needs a
  * per-pipeline credentialsSecret + catalog). Runtime readers resolve this
  * through `UnityCatalogSync.effective`. */
case class UnityCatalogSync @JsonCreator() (
    @JsonProperty("enabled") enabled: Boolean = false,
    @JsonProperty("comments") comments: java.lang.Boolean = null,
    @JsonProperty("tags") tags: java.lang.Boolean = null,
    @JsonProperty("properties") properties: java.lang.Boolean = null,
    // Publish External Metadata + External Lineage (tap/upload → pipeline →
    // table) over the workspace REST API. See UnityCatalogLineagePublisher.
    @JsonProperty("lineage") lineage: java.lang.Boolean = null,
    // Object-store Iceberg destinations only (IcebergCatalogRegistrar): the
    // Platform secret naming the Unity Catalog workspace, and the UC catalog
    // and schema the table is registered under as <catalog>.<schema>.<pipeline>.
    // A Databricks destination ignores these (its coordinates come from Database).
    @JsonProperty("credentialsSecret") credentialsSecret: String = null,
    @JsonProperty("catalog") catalog: String = null,
    // Absent ⇒ null (Jackson/Gson skip Scala defaults); read via schemaOrDefault.
    @JsonProperty("schema") schema: String = null,
    @JsonProperty("register") register: java.lang.Boolean = null,
    // Object-store Iceberg only: `register` (null/absent: register after each
    // commit, story 4), `rest` (every commit goes through the Iceberg REST
    // catalog at the pipeline's prefix, IcebergRestSession) or `managed` (the
    // catalog chooses the table's location in the pipeline's bucket, e.g. a
    // Databricks managed table; commits go through the catalog). Read via
    // catalogModeOrDefault / restMode / managedMode / throughCatalog.
    @JsonProperty("catalogMode") catalogMode: String = null
) {
    def this() = this(false, null, null, null, null, null, null, null, null, null)

    def commentsOn: Boolean = UnityCatalogSync.on(comments)
    def tagsOn: Boolean = UnityCatalogSync.on(tags)
    def propertiesOn: Boolean = UnityCatalogSync.on(properties)
    def lineageOn: Boolean = UnityCatalogSync.on(lineage)
    def registerOn: Boolean = UnityCatalogSync.on(register)
    def schemaOrDefault: String = Option(schema).map(_.trim).filter(_.nonEmpty).getOrElse("default")
    def catalogModeOrDefault: String = Option(catalogMode).map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("register")
    def restMode: Boolean = catalogModeOrDefault == "rest"
    def managedMode: Boolean = catalogModeOrDefault == "managed"

    /** Every commit goes through the Iceberg REST catalog (rest or managed). */
    def throughCatalog: Boolean = restMode || managedMode
}

object UnityCatalogSync {

    /** Unset (null) ⇒ on; only an explicit `false` drops a group. */
    def on(b: java.lang.Boolean): Boolean = b == null || b.booleanValue

    /** `enabledBy` values: an explicit pipeline block decided, or the install default filled in a missing block. */
    object EnabledBy {
        val Pipeline = "pipeline"
        val Default = "default"
    }

    /** The block the install default fills in: enabled, every knob on (null). */
    val DefaultBlock: UnityCatalogSync = UnityCatalogSync(enabled = true)

    /** The one place the per-pipeline block and the install default are
      * resolved. Some((block, enabledBy)) when Unity Catalog metadata is on for
      * this pipeline, None when off. An explicit block always decides (so
      * `{"enabled": false}` opts out of the default); a missing block is
      * defaulted on only for a Databricks destination. The kill switch
      * (`DATRIS_UNITY_CATALOG_SYNC`) is not part of this; hooks check it after. */
    def effective(config: PipelineConfig, defaultEnabled: Boolean): Option[(UnityCatalogSync, String)] = {
        if (config == null) return None
        val uc = config.unityCatalog
        if (uc != null) {
            if (uc.enabled) Some((uc, EnabledBy.Pipeline)) else None
        } else if (defaultEnabled && isDatabricks(config)) Some((DefaultBlock, EnabledBy.Default))
        else None
    }

    /** `GET /pipelines/{name}/unity-catalog` fields from `effective`:
      * (enabled, enabledBy, lineageEnabled). enabledBy is "pipeline" whenever
      * the pipeline has its own block (including an `{"enabled": false}`
      * opt-out), "default" when the install default turned it on, null when
      * Unity Catalog is off and the pipeline has no block. */
    def stateFields(config: PipelineConfig, defaultEnabled: Boolean): (Boolean, String, Boolean) = {
        val eff = effective(config, defaultEnabled)
        val enabledBy =
            if (config != null && config.unityCatalog != null) EnabledBy.Pipeline
            else eff.map(_._2).orNull
        (eff.isDefined, enabledBy, eff.exists(_._1.lineageOn))
    }

    private def isDatabricks(config: PipelineConfig): Boolean =
        config.destination != null && config.destination.database != null && config.destination.database.useDatabricks

    /** `DATRIS_UNITY_CATALOG_DEFAULT` (or the `datris.unityCatalogDefault`
      * system property, which wins): `enabled` (any case, trimmed) turns the
      * install default on; unset, blank or anything else is `disabled`. Read on
      * every call. */
    def defaultEnabledFromEnv: Boolean =
        sys.props
            .get("datris.unityCatalogDefault")
            .orElse(sys.env.get("DATRIS_UNITY_CATALOG_DEFAULT"))
            .exists(_.trim.equalsIgnoreCase("enabled"))
}

/** `protection: {"purgeSource": false}` keeps the ingest object(s) a
  * protected run was read from. Boxed and null means on, for the same reason
  * as `UnityCatalogSync` (Jackson skips Scala defaults, Gson skips
  * constructors); read through `ProtectionConfig.purgeSourceOn`. */
case class ProtectionConfig @JsonCreator() (
    @JsonProperty("purgeSource") purgeSource: java.lang.Boolean = null
) {
    def this() = this(null)
}

object ProtectionConfig {

    /** True unless the pipeline sets `protection.purgeSource` to false. */
    def purgeSourceOn(config: PipelineConfig): Boolean =
        config == null || config.protection == null || config.protection.purgeSource == null ||
            config.protection.purgeSource.booleanValue()
}

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
    chroma: ChromaConfig = null,
    // Scratch result: the run lands as one JSON-lines object under
    // `_scratch/<pipeline>/` in the built-in object store and hands the caller a
    // pointer plus the first page of rows on the run status. Presence is the
    // whole config. Exclusive — cannot be combined with any other destination
    // (see PipelineValidatorUtil), never catalogued, never a lineage dataset.
    scratch: ScratchConfig = null,
    // Which destination kind is the golden copy when a pipeline lands into more
    // than one (postgres | mongodb | snowflake | databricks | objectstore | kafka
    // | activemq | qdrant | weaviate | pgvector | milvus | chroma).
    // Ignored for a single destination (implicitly authoritative); absent with
    // several ⇒ all "undeclared" in lineage until a human decides. Never
    // inferred. `scratch` is not a valid value: it is always the sole
    // destination and never a lineage dataset, so the authority check rejects it.
    authoritative: String = null
)

/** `destination.scratch: {}` — empty on purpose; presence is the whole config. */
case class ScratchConfig()

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

/** `delimiter` may arrive null: Spring's `@RequestBody` Jackson mapper
  * (ParameterNamesModule, no DefaultScalaModule) ignores Scala default
  * arguments, so a body that omits it stores null. Every read goes through
  * `effectiveDelimiter` / `CsvAttributes.delimiterOf`, which fall back to ","
  * without rewriting the stored config. */
case class CsvAttributes(
    delimiter: String = ",",
    header: Boolean = true,
    encoding: String = "UTF-8"
) {
    def effectiveDelimiter: String = CsvAttributes.resolveDelimiter(delimiter)
}

object CsvAttributes {
    val DefaultDelimiter: String = ","

    /** Null or empty ⇒ ",". */
    def resolveDelimiter(delimiter: String): String =
        if (delimiter == null || delimiter.isEmpty) DefaultDelimiter else delimiter

    /** The source csvAttributes delimiter of `config`, else "," (null-safe on
      * config / source / fileAttributes / csvAttributes). */
    def delimiterOf(config: PipelineConfig): String =
        if (
            config != null && config.source != null && config.source.fileAttributes != null
            && config.source.fileAttributes.csvAttributes != null
        )
            config.source.fileAttributes.csvAttributes.effectiveDelimiter
        else DefaultDelimiter
}

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
    timeoutMs: Int = 300000,
    // Destination role only: rows per HTTP call. 0 (default) = one call
    // carrying every row, exactly as before batching existed. When > 0 each
    // call carries `batchSize` rows plus top-level `batch` / `ofBatches`.
    batchSize: Int = 0
)
case class ObjectStore(
    prefixKey: String = null,
    partitionBy: java.util.List[String] = null,
    destinationBucketOverride: String = null,
    fileFormat: String = null,
    writeToTemporaryLocation: Boolean = false,
    deleteBeforeWrite: Boolean = false,
    writeMode: String = null,
    // MERGE ON columns. Only applies to writeMode=merge on fileFormat=iceberg;
    // same name and meaning as Database.keyFields. Ignored otherwise.
    keyFields: java.util.List[String] = null,
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
