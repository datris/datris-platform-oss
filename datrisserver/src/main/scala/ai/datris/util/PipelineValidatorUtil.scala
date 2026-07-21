package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import org.quartz.CronExpression

import scala.collection.JavaConverters._

object PipelineValidatorUtil {

    /** Substitute well-known placeholder values that AISchemaUtil templates emit
      * (DATABASE_NAME, SCHEMA_NAME) with sensible runtime defaults from DatrisEnvironment.
      * Runs before validate so configs that came from auto-generation are immediately usable.
      *
      * - dbName: null/empty/"DATABASE_NAME" → DatrisEnvironment.current.postgresDatabase
      * - schema: "SCHEMA_NAME" → "public"
      *
      * Table name is intentionally left alone — if it's still "TABLE_NAME" the user
      * forgot to fill it in and validation should reject it.
      */
    def applyDefaults(config: PipelineConfig): PipelineConfig = {
        if (config == null || config.destination == null || config.destination.database == null)
            return config

        val db = config.destination.database
        val needsDbName = db.dbName == null || db.dbName.isEmpty || db.dbName == "DATABASE_NAME"
        val needsSchema = db.schema == "SCHEMA_NAME"
        if (!needsDbName && !needsSchema) return config

        val newDb = db.copy(
            dbName = if (needsDbName) DatrisEnvironment.current.postgresDatabase else db.dbName,
            schema = if (needsSchema) "public" else db.schema
        )
        config.copy(destination = config.destination.copy(database = newDb))
    }

    def validate(config: PipelineConfig): Unit = {
        if (config.name == null)
            throw new DatrisException("pipeline 'name' is not defined in the JSON")
        if (config.name.length > 80)
            throw new DatrisException("pipeline 'name' cannot be greater than 80 characters")

        // Source config
        if (config.source == null)
            throw new DatrisException("pipeline 'source' is not defined in the JSON")
        if (config.source.fileAttributes == null && config.source.databaseAttributes == null)
            throw new DatrisException("Either 'source.fileAttributes' or 'source.databaseAttributes must be defined")

        if (config.source.fileAttributes != null && config.source.fileAttributes.unstructuredAttributes != null)
            validateUnstructured(config)
        else
            validateStructuredAndSemiStructured(config)
    }

    private def validateUnstructured(config: PipelineConfig): Unit = {
        if (config.source.fileAttributes.unstructuredAttributes.fileExtension == null)
            throw new DatrisException("For unstructured files, the 'source.fileAttributes.unstructuredAttributes.fileExtension' cannot be null")
        if (config.destination == null)
            throw new DatrisException("For unstructured files, the 'destination' cannot be null")
        // Unstructured files can go to objectStore or qdrant
        if (
            config.destination.objectStore == null && config.destination.qdrant == null && config.destination.weaviate == null && config.destination
                .pgvector == null && config.destination.milvus == null && config.destination.chroma == null
        )
            throw new DatrisException(
                "For unstructured files, a vector database or object store destination must be defined (objectStore, qdrant, weaviate, pgvector, milvus, or chroma)"
            )
        if (config.destination.objectStore != null && config.destination.objectStore.prefixKey == null)
            throw new DatrisException("For unstructured files with objectStore destination, the 'destination.objectStore.prefixKey' cannot be null")
    }

    private def validateStructuredAndSemiStructured(config: PipelineConfig): Unit = {
        // Source schema properties
        if (config.source.schemaProperties == null)
            throw new DatrisException("'source.schemaProperties' must be defined")

        // Destination config
        if (config.destination == null)
            throw new DatrisException("The 'destination' section must exist")

        // Used to determine if keyFields exist in the schema properties
        val schemaFieldNames = {
            if (config.destination.schemaProperties != null)
                config.destination.schemaProperties.fields.asScala.map(_.name)
            else
                config.source.schemaProperties.fields.asScala.map(_.name)
        }

        // Source database attributes
        if (config.source.databaseAttributes != null) {
            if (config.source.databaseAttributes.`type` == null)
                throw new DatrisException("If 'source.databaseAttributes' is defined, the 'type' field must also be defined")
            if (
                config.source.databaseAttributes.`type`.compareToIgnoreCase("postgres") != 0 &&
                config.source.databaseAttributes.`type`.compareToIgnoreCase("mssql") != 0 &&
                config.source.databaseAttributes.`type`.compareToIgnoreCase("mysql") != 0
            ) {
                throw new DatrisException("The only supported 'databaseAttributes.type's are currently 'postgres', 'mysql' and 'mssql'")
            }
            if (config.source.databaseAttributes.`type`.compareToIgnoreCase("postgres") == 0) {
                if (config.source.databaseAttributes.postgresSecretsName == null)
                    throw new DatrisException("If 'source.databaseAttributes' is defined, the 'postgresSecretsName' field must also be defined")
            }
            if (config.source.databaseAttributes.`type`.compareToIgnoreCase("mssql") == 0) {
                if (config.source.databaseAttributes.mssqlSecretsName == null)
                    throw new DatrisException("If 'source.databaseAttributes' is defined, the 'mssqlSecretsName' field must also be defined")
            }
            if (config.source.databaseAttributes.`type`.compareToIgnoreCase("mysql") == 0) {
                if (config.source.databaseAttributes.mysqlSecretsName == null)
                    throw new DatrisException("If 'source.databaseAttributes' is defined, the 'mysqlSecretsName' field must also be defined")
            }
            if (config.source.databaseAttributes.cronExpression == null)
                throw new DatrisException("If 'source.databaseAttributes' is defined, the 'cronExpression' field must also be defined")
            if (!CronExpression.isValidExpression(config.source.databaseAttributes.cronExpression))
                throw new DatrisException("If 'source.databaseAttributes.cronExpression' is invalid: " + config.source.databaseAttributes.cronExpression)

            if (config.source.databaseAttributes.sqlOverride == null) {
                if (config.source.databaseAttributes.table == null)
                    throw new DatrisException("If 'source.databaseAttributes' is defined, the 'table' field must also be defined")
                if (config.source.databaseAttributes.timestampFieldName == null)
                    throw new DatrisException("If 'source.databaseAttributes' is defined, 'timestampFieldName' must also be defined")
            }
        }

        // Preprocessor
        if (config.preprocessor != null) {
            if (config.preprocessor.endpoint == null)
                throw new DatrisException("If 'preprocessor' is defined, the 'endpoint' must be defined")
        }

        // Data quality
        if (config.dataQuality != null) {
            if (config.dataQuality.validateFileHeader && config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes == null)
                throw new DatrisException("In the 'dataQuality' section, 'validateFileHeader' = true is only valid for delimited (CSV) files")
            if (config.dataQuality.validationSchema != null) {
                if (
                    config.source.fileAttributes != null && config.source.fileAttributes.jsonAttributes == null && config.source.fileAttributes.xmlAttributes == null
                )
                    throw new DatrisException("In the 'dataQuality' section, 'validationSchema' is only valid for JSON or XML files")
            }
        }

        // Transformation
        if (config.transformation != null) {
            if (
                config.source.fileAttributes != null &&
                config.source.fileAttributes.csvAttributes == null &&
                config.source.fileAttributes.jsonAttributes == null &&
                config.source.fileAttributes.xmlAttributes == null
            )
                throw new DatrisException("A 'transformation' section is only supported for CSV, JSON, and XML files")
            if (config.transformation.rowFunctions != null) {
                config.transformation.rowFunctions.forEach(function => {
                    if (function.function.compareToIgnoreCase("javascript") != 0 && function.function.compareToIgnoreCase("restEndpoint") != 0)
                        throw new DatrisException("For the 'transformation.rowFunctions' section, only 'javascript' and 'restEndpoint' functions are supported")
                    if (function.parameters == null || function.parameters.isEmpty)
                        throw new DatrisException("For the 'transformation.rowFunctions' section, parameters are required")
                })
            }
        }

        // Destination object store
        if (config.destination.objectStore != null) {
            if (config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes == null)
                throw new DatrisException("A destination of 'objectStore' is only supported for CSV files")
            if (config.destination.objectStore.prefixKey == null)
                throw new DatrisException("If the 'destination.objectStore' section is defined, the 'destination.objectStore.prefixKey' must be defined")
            if (config.destination.objectStore.partitionBy != null) {
                config.destination.objectStore.partitionBy.forEach(field => {
                    if (!schemaFieldNames.contains(field))
                        throw new DatrisException("'partitionBy' field name: " + field + " is not in the schema properties for this pipeline")
                })
            }
            if (config.destination.objectStore.fileFormat != null) {
                if (config.destination.objectStore.fileFormat.compareTo("parquet") != 0 && config.destination.objectStore.fileFormat.compareTo("orc") != 0)
                    throw new DatrisException("If the 'destination.objectStore.fileFormat' is defined, it must be either 'parquet' or 'orc'")
            }

            // Provider must be 'minio' (default) or 's3'.
            val provider = if (config.destination.objectStore.provider != null) config.destination.objectStore.provider.toLowerCase else "minio"
            if (provider.compareTo("minio") != 0 && provider.compareTo("s3") != 0)
                throw new DatrisException("'destination.objectStore.provider' must be either 'minio' or 's3'")

            // S3-specific config-level checks. Region lives in the credentials
            // secret, not the pipeline config — CredentialResolver surfaces the
            // missing-region error at resolve time with a clear message, so we
            // do not check it here.
            if (provider.compareTo("s3") == 0) {
                if (config.destination.objectStore.destinationBucketOverride == null)
                    throw new DatrisException(
                        "When 'destination.objectStore.provider' is 's3', 'destinationBucketOverride' is required (no global default S3 bucket exists)"
                    )
                if (config.destination.objectStore.endpoint != null && config.destination.objectStore.endpoint.toLowerCase.startsWith("http://"))
                    throw new DatrisException(
                        "When 'destination.objectStore.provider' is 's3', the 'endpoint' must use https:// (got: " + config.destination.objectStore.endpoint + ")"
                    )
            }

            // Get the existing configuration
            val existingConfig = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, config.name)
            if (existingConfig != null) {
                if (existingConfig.destination.objectStore != null) {
                    if (existingConfig.destination.objectStore.partitionBy != null && config.destination.objectStore.partitionBy == null)
                        throw new DatrisException(
                            "Cannot change an existing object store pipeline from no partition to partitioned. Delete all S3 data for this pipeline first and then re-register"
                        )
                    if (existingConfig.destination.objectStore.partitionBy == null && config.destination.objectStore.partitionBy != null)
                        throw new DatrisException(
                            "Cannot change an existing object store pipeline from partitioned to not partitioned. Delete all S3 data for this pipeline first and then re-register"
                        )
                }
            }
        }

        // Destination database
        if (config.destination.database != null) {
            if (config.destination.database.dbName == null)
                throw new DatrisException("If the 'destination.database' section is defined, the 'destination.database.dbName' must be defined")
            if (!config.destination.database.useMongoDB && config.destination.database.schema == null)
                throw new DatrisException("If the 'destination.database' section is defined, the 'destination.database.schema' must be defined")
            if (config.destination.database.table == null)
                throw new DatrisException("If the 'destination.database' section is defined, the 'destination.database.table' must be defined")
            if (config.destination.database.keyFields != null) {
                config.destination.database.keyFields.forEach(field => {
                    if (!schemaFieldNames.contains(field))
                        throw new DatrisException("Key field: " + field + " is not in the schema properties for this pipeline")
                })
            }

            // Exactly one engine: the flags share dbName/schema/table/warehouse,
            // whose meanings differ per engine, and JobRunner dispatches each
            // flag independently — two flags true would run two loaders against
            // the same destination block.
            val engineFlags = Seq(
                config.destination.database.usePostgres,
                config.destination.database.useMongoDB,
                config.destination.database.useSnowflake,
                config.destination.database.useDatabricks
            ).count(identity)
            if (engineFlags != 1) {
                throw new DatrisException(
                    "For the 'destination.database' section, you must select exactly one of usePostgres, useMongoDB, useSnowflake, or useDatabricks" +
                        (if (engineFlags > 1) " — multiple are currently set" else "")
                )
            }

            if (config.destination.database.useSnowflake) {
                if (config.destination.database.credentialsSecret == null)
                    throw new DatrisException(
                        "When 'destination.database.useSnowflake' is true, 'credentialsSecret' is required — the name of a Platform secret holding 'account', 'user', and 'privateKey' (or 'password'). Create it on Configuration → Secrets → Platform"
                    )
                if (config.destination.database.warehouse == null)
                    throw new DatrisException(
                        "When 'destination.database.useSnowflake' is true, 'warehouse' is required — the Snowflake virtual warehouse that runs the load"
                    )
            }

            if (config.destination.database.useDatabricks) {
                if (config.destination.database.credentialsSecret == null)
                    throw new DatrisException(
                        "When 'destination.database.useDatabricks' is true, 'credentialsSecret' is required — the name of a Platform secret holding 'host', plus 'clientId'/'clientSecret' (service principal OAuth) or 'token' (personal access token). Create it on Configuration → Secrets → Platform"
                    )
                if (config.destination.database.warehouse == null)
                    throw new DatrisException(
                        "When 'destination.database.useDatabricks' is true, 'warehouse' is required — the Databricks SQL warehouse ID (SQL Warehouses → Connection details, the trailing segment of the HTTP path)"
                    )
            }
        }

        // Destination Kafka
        if (config.destination.kafka != null) {
            if (config.destination.kafka.topic == null)
                throw new DatrisException("If the 'destination.kafka' section is defined, the 'topic' must be defined")
            if (config.destination.kafka.keyField != null) {
                if (!schemaFieldNames.map(_.toLowerCase).contains(config.destination.kafka.keyField.toLowerCase))
                    throw new DatrisException(
                        "destination.kafka.keyField '" + config.destination.kafka.keyField + "' is not in the schema properties for this pipeline"
                    )
            }
        }

        // Destination ActiveMQ
        if (config.destination.activeMQ != null) {
            if (config.destination.activeMQ.queueName == null)
                throw new DatrisException("If the 'destination.activeMQ' section is defined, the 'queueName' must be defined")
        }

        // Destination RestEndpoint
        if (config.destination.restEndpoint != null) {
            if (config.destination.restEndpoint.endpoint == null)
                throw new DatrisException("If the 'destination.restEndpoint' section is defined, the 'endpoint' must be defined")
        }

        // Validate semi-structured (JSON, XML)
        if (config.source.fileAttributes != null && (config.source.fileAttributes.jsonAttributes != null || config.source.fileAttributes.xmlAttributes != null))
            validateSemiStructured(config)

        // Validate columns
        validateColumns(sourceSchema = true, config)
        if (config.destination.schemaProperties != null)
            validateColumns(sourceSchema = false, config)
    }

    private def validateColumns(sourceSchema: Boolean, config: PipelineConfig): Unit = {
        val fields = {
            if (sourceSchema)
                config.source.schemaProperties.fields
            else
                config.destination.schemaProperties.fields
        }

        if (fields != null) {
            val fieldNames = fields.asScala.map(_.name).filter(_ != null).map(_.toLowerCase)
            val duplicates = fieldNames.groupBy(identity).collect { case (name, occurrences) if occurrences.size > 1 => name }
            if (duplicates.nonEmpty)
                throw new DatrisException(
                    "Duplicate field name(s) found in " + (if (sourceSchema) "source" else "destination") + " schema: " + duplicates.mkString(", ")
                )

            fields.asScala.foreach(field => {
                if (field.name == null)
                    throw new DatrisException("Column name cannot be null")
                if (!field.name.matches("([A-Za-z0-9\\_]+)"))
                    throw new DatrisException("Column name: " + field.name + " is invalid.  Valid characters are a-z, 0-9 and _")
                if (
                    field.`type` == null ||
                    (
                        field.`type`.compareToIgnoreCase("boolean") != 0 &&
                            field.`type`.compareToIgnoreCase("int") != 0 &&
                            field.`type`.compareToIgnoreCase("tinyint") != 0 &&
                            field.`type`.compareToIgnoreCase("smallint") != 0 &&
                            field.`type`.compareToIgnoreCase("bigint") != 0 &&
                            field.`type`.compareToIgnoreCase("float") != 0 &&
                            field.`type`.compareToIgnoreCase("double") != 0 &&
                            !field.`type`.toLowerCase.startsWith("decimal(") &&
                            field.`type`.compareToIgnoreCase("string") != 0 &&
                            !field.`type`.toLowerCase.startsWith("varchar(") &&
                            !field.`type`.toLowerCase.startsWith("char(") &&
                            field.`type`.compareToIgnoreCase("date") != 0 &&
                            field.`type`.compareToIgnoreCase("timestamp") != 0
                    )
                ) {
                    throw new DatrisException(
                        "Invalid field type passed: " + field.`type` + ", supported types include boolean, int, tinyint, smallint, bigint, float, double, decimal(?,?), string, varchar(?), char(?), date, and timestamp"
                    )
                }
            })
        }
    }

    private def validateSemiStructured(config: PipelineConfig): Unit = {
        val message =
            "For JSON and XML pipelines, the source schema must have only one field named '_json' or '_xml' according to the source file type, with a field type of 'string'"
        if (config.source.schemaProperties.fields.size != 1)
            throw new DatrisException(message)
        if (config.source.schemaProperties.fields.get(0).`type`.compareToIgnoreCase("string") != 0)
            throw new DatrisException(message)
        if (config.source.fileAttributes != null && config.source.fileAttributes.jsonAttributes != null) {
            if (config.source.schemaProperties.fields.get(0).name.compareToIgnoreCase("_json") != 0)
                throw new DatrisException(message)
        }
        if (config.source.fileAttributes != null && config.source.fileAttributes.xmlAttributes != null) {
            if (config.source.schemaProperties.fields.get(0).name.compareToIgnoreCase("_xml") != 0)
                throw new DatrisException(message)
        }

        if (config.destination.schemaProperties != null) {
            val message =
                "For JSON and XML pipelines, the destination schema must have only one field named '_json' or '_xml' according to the source file type, with a field type of 'string'"
            if (config.destination.schemaProperties.fields.size != 1)
                throw new DatrisException(message)
            if (config.destination.schemaProperties.fields.get(0).`type`.compareToIgnoreCase("string") != 0)
                throw new DatrisException(message)
            if (config.source.fileAttributes != null && config.source.fileAttributes.jsonAttributes != null) {
                if (config.destination.schemaProperties.fields.get(0).name.compareToIgnoreCase("_json") != 0)
                    throw new DatrisException(message)
            }
            if (config.source.fileAttributes != null && config.source.fileAttributes.xmlAttributes != null) {
                if (config.destination.schemaProperties.fields.get(0).name.compareToIgnoreCase("_xml") != 0)
                    throw new DatrisException(message)
            }
        }
    }

    def modify(config: PipelineConfig): PipelineConfig = {
        val sourceSchemaProperties = {
            if (config.source.schemaProperties != null) {
                val fields =
                    config.source.schemaProperties.fields.asScala.map(field => SchemaField(field.name.toLowerCase, field.`type`.toLowerCase)).toList.asJava
                SchemaProperties(config.source.schemaProperties.dbName, fields)
            } else
                null
        }

        val destinationSchemaProperties = {
            if (config.destination.schemaProperties != null) {
                // For JSON or XML fields, define 1 field as a string
                if (config.source.fileAttributes != null && config.source.fileAttributes.jsonAttributes != null) {
                    val fields = List(SchemaField("_json", "string")).asJava
                    SchemaProperties(config.destination.schemaProperties.dbName, fields)
                } else if (config.source.fileAttributes != null && config.source.fileAttributes.xmlAttributes != null) {
                    val fields = List(SchemaField("_xml", "string")).asJava
                    SchemaProperties(config.destination.schemaProperties.dbName, fields)
                } else {
                    val fields =
                        config.destination.schemaProperties.fields.asScala.map(field => SchemaField(field.name.toLowerCase, field.`type`.toLowerCase)).toList.asJava
                    SchemaProperties(config.destination.schemaProperties.dbName, fields)
                }
            } else {
                null
            }
        }

        val objectStore = {
            if (config.destination.objectStore == null)
                null
            else {
                val partitionBy = {
                    if (config.destination.objectStore.partitionBy != null)
                        config.destination.objectStore.partitionBy.asScala.map(_.toLowerCase).toList.asJava
                    else
                        null
                }
                val fileFormat = {
                    if (config.source.fileAttributes != null && config.source.fileAttributes.unstructuredAttributes != null)
                        null
                    else if (config.destination.objectStore.fileFormat != null)
                        config.destination.objectStore.fileFormat
                    else
                        "parquet" // default output file format
                }
                val destinationBucketOverride = {
                    if (config.destination.objectStore.destinationBucketOverride != null)
                        config.destination.objectStore.destinationBucketOverride
                    else
                        null
                }
                config.destination.objectStore.copy(
                    prefixKey = config.destination.objectStore.prefixKey.toLowerCase,
                    partitionBy = partitionBy,
                    fileFormat = fileFormat,
                    destinationBucketOverride = destinationBucketOverride
                )
            }
        }

        val database = {
            // Key fields must be lower case
            if (config.destination.database != null) {
                val fields = {
                    if (config.destination.database.keyFields != null)
                        config.destination.database.keyFields.asScala.map(_.toLowerCase).toList.asJava
                    else
                        null
                }
                config.destination.database.copy(keyFields = fields)
            } else
                null
        }

        val fileAttributes = {
            // If we have database attributes, automatically enforce the file attributes
            if (config.source.databaseAttributes != null) {
                val delimiter = {
                    if (config.source.databaseAttributes.outputDelimiter != null)
                        config.source.databaseAttributes.outputDelimiter
                    else
                        ","
                }
                val csvAttributes = CsvAttributes(
                    delimiter = delimiter,
                    header = false,
                    "UTF-8"
                )
                FileAttributes(csvAttributes, null, null, null, null, null)
            } else
                config.source.fileAttributes
        }

        val source = config.source.copy(schemaProperties = sourceSchemaProperties, fileAttributes = fileAttributes)
        val destination = Destination(
            destinationSchemaProperties,
            database,
            objectStore,
            config.destination.restEndpoint,
            config.destination.kafka,
            config.destination.activeMQ,
            config.destination.qdrant,
            config.destination.weaviate,
            config.destination.pgvector,
            config.destination.milvus,
            config.destination.chroma
        )

        config.copy(source = source, destination = destination)
    }
}
