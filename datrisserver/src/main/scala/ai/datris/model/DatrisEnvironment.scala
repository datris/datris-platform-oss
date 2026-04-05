package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

object DatrisEnvironment {
    var values: DatrisEnvironment = _

    def init(environment: DatrisEnvironment): Unit = {
        values = environment
    }

    /** Returns the per-request tenant environment if set, otherwise the global singleton. */
    def current: DatrisEnvironment = TenantContext.get().getOrElse(values)

    /** Build a tenant-specific environment by replacing the environment string
      * and all derived names, while keeping global infrastructure config. */
    def forEnvironment(env: String): DatrisEnvironment = {
        // Load tenant's AI config from Vault
        val tenantAiConfig = try {
            val provider = values.aiConfig.provider
            val secret = ai.datris.util.SecretsUtil.getSecretMap(env + "/" + provider)
            secret.map { s =>
                import scala.collection.JavaConverters._
                val map = s.asScala
                AIConfig(
                    provider,
                    map.getOrElse("endpoint", values.aiConfig.endpoint),
                    map.getOrElse("model", values.aiConfig.model),
                    map.getOrElse("apiKey", values.aiConfig.apiKey)
                )
            }.getOrElse(values.aiConfig)
        } catch {
            case _: Exception => values.aiConfig
        }

        values.copy(
            environment = env,
            fileNotifierQueue = env + "-file-notifier",
            pipelineTableName = env + "-pipeline",
            archivedMetadataTableName = env + "-archived-metadata",
            pipelineStatusTableName = env + "-pipeline-status",
            fileNotifierMessageTableName = env + "-file-notifier-message",
            dataPullTableName = env + "-data-pull",
            pipelineTopic = if (values.pipelineTopic != null) "VirtualTopic." + env + "-pipeline-notification" else null,
            apiKeysSecretName = env + "/api-keys",
            postgresSecretName = env + "/postgres",
            mongoDbSecretName = env + "/mongodb",
            kafkaProducerSecretName = env + "/kafka-producer",
            embeddingSecretName = env + "/embedding",
            qdrantSecretName = env + "/qdrant",
            weaviateSecretName = env + "/weaviate",
            milvusSecretName = env + "/milvus",
            chromaSecretName = env + "/chroma",
            pgvectorSecretName = env + "/pgvector",
            aiConfig = tenantAiConfig,
            tapTableName = env + "-tap",
            tapLogTableName = env + "-tap-log"
        )
    }
}

case class DatrisEnvironment(
                                  initialized: Boolean, // Determines if the environment is fully initialized
                                  environment: String,
                                  fileNotifierQueue: String,
                                  ttlFileNotifierQueueMessages: Int,
                                  pipelineTopic: String,
                                  pipelineTableName: String,
                                  archivedMetadataTableName: String,
                                  pipelineStatusTableName: String,
                                  fileNotifierMessageTableName: String,
                                  dataPullTableName: String,
                                  useApiKeys: Boolean,
                                  apiKeysSecretName: String,
                                  postgresSecretName: String,
                                  mongoDbSecretName: String,
                                  kafkaProducerSecretName: String,
                                  kafkaConsumerConfig: KafkaConsumerConfig,
                                  mongoDbConfig: MongoDBConfig,
                                  minIOConfig: MinIOConfig,
                                  activeMQConfig: ActiveMQConfig,
                                  aiConfig: AIConfig,
                                  aiEnabled: Boolean,
                                  embeddingSecretName: String,
                                  qdrantSecretName: String,
                                  weaviateSecretName: String,
                                  milvusSecretName: String,
                                  chromaSecretName: String,
                                  pgvectorSecretName: String,
                                  multiTenant: Boolean,
                                  tapTableName: String = null,
                                  tapLogTableName: String = null,
                                  tapScriptTimeoutSeconds: Int = 300,
                                  dateFormat: String = "yyyy-MM-dd HH:mm:ss z",
                                  dateTimezone: String = "UTC"
                              )
