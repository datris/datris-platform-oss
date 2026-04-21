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

    /** Resolve which AIConfig to use for code-generation tasks (tap scripts, AI DQ,
      * AI transformations, schema generation, NL→SQL). Falls back to the main aiConfig
      * when no codegen config has been configured. Reads `.current` so multi-tenant
      * resolution happens automatically per request. */
    def aiConfigForCodegen: AIConfig = {
        val env = current
        env.codegenAiConfig.getOrElse(env.aiConfig)
    }

    /** Build a tenant-specific environment by replacing the environment string
      * and all derived names, while keeping global infrastructure config.
      *
      * Per-tenant AI overrides live at fixed paths that mirror the global slots:
      *   {env}/ai-primary, {env}/codegen, {env}/embedding
      * Each is fully self-describing — provider/endpoint/model/apiKey are read from
      * the secret itself, no path derivation. */
    def forEnvironment(env: String): DatrisEnvironment = {
        val tenantAiConfig = loadTenantAiConfig(env + "/ai-primary").getOrElse(values.aiConfig)
        val tenantCodegenAiConfig: Option[AIConfig] =
            loadTenantAiConfig(env + "/codegen").orElse(values.codegenAiConfig)
        // Embedding secret falls through to the global default when no per-tenant
        // override exists, matching the AI primary/codegen fallback behavior. Trial
        // provisioning intentionally does not seed {env}/embedding so the Configuration
        // UI can detect "Datris-managed (default)" by the absence of the override.
        val tenantEmbeddingSecretName =
            if (ai.datris.util.SecretsUtil.getSecretMap(env + "/embedding").isDefined) env + "/embedding"
            else values.embeddingSecretName

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
            embeddingSecretName = tenantEmbeddingSecretName,
            qdrantSecretName = env + "/qdrant",
            weaviateSecretName = env + "/weaviate",
            milvusSecretName = env + "/milvus",
            chromaSecretName = env + "/chroma",
            pgvectorSecretName = env + "/pgvector",
            aiConfig = tenantAiConfig,
            codegenAiConfig = tenantCodegenAiConfig,
            tapTableName = env + "-tap",
            tapLogTableName = env + "-tap-log",
            tapLedgerTableName = env + "-tap-ledger",
            tapPromptTableName = env + "-tap-prompt",
            postgresDatabase = env
        )
    }

    /** Reload AI configuration from Vault without restarting.
      * Called after the UI saves new AI secrets. */
    def reloadAiConfig(): Unit = synchronized {
        val env = values.environment
        val aiConfig = loadTenantAiConfig(env + "/ai-primary").getOrElse(values.aiConfig)
        val codegenAiConfig: Option[AIConfig] = loadTenantAiConfig(env + "/codegen").orElse(values.codegenAiConfig)
        values = values.copy(aiConfig = aiConfig, codegenAiConfig = codegenAiConfig)
    }

    /** Load a tenant AI override from a self-describing Vault secret.
      * Returns None when the secret doesn't exist or has an empty apiKey (zombie record). */
    private def loadTenantAiConfig(path: String): Option[AIConfig] = {
        try {
            ai.datris.util.SecretsUtil.getSecretMap(path).flatMap { s =>
                import scala.collection.JavaConverters._
                val map = s.asScala
                val provider = map.getOrElse("provider", "").trim
                val endpoint = map.getOrElse("endpoint", "").trim
                val apiKey   = map.getOrElse("apiKey", "")
                if (provider.isEmpty || endpoint.isEmpty || (apiKey.isEmpty && provider.toLowerCase != "ollama")) None
                else Some(AIConfig(
                    provider,
                    endpoint,
                    map.getOrElse("model", ""),
                    apiKey,
                    map.getOrElse("version", "")
                ))
            }
        } catch { case _: Exception => None }
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
                                  tapLedgerTableName: String = null,
                                  tapPromptTableName: String = null,
                                  tapScriptTimeoutSeconds: Int = 300,
                                  dateFormat: String = "yyyy-MM-dd HH:mm:ss z",
                                  dateTimezone: String = "UTC",
                                  postgresDatabase: String = "datris",
                                  codegenAiConfig: Option[AIConfig] = None,
                              hosted: Boolean = false
                              ) {
    /** True for trial-droplet tenants. Trials have AI configuration locked at the
      * server level — see SecretsAPIController.rejectIfTrialAiSecret. The convention
      * is enforced by the website's provision-trial.ts which always assigns
      * `trial-{slug}-{shortid}` env names. */
    def isTrial: Boolean = environment != null && environment.startsWith("trial-")
}
