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

    /** Resolve which AIConfig the interactive chat agents (Assistant, Ops,
      * Catalog, Search chat) run on. Chat uses the **primary** model — fast and
      * conversational — while artifact generation (tap scripts, NL→SQL, AI
      * DQ/transform, schema gen) uses [[aiConfigForCodegen]]. Kept as a separate
      * accessor so the two can diverge (e.g. a future dedicated `ai.chat` slot)
      * without touching every chat controller. Reads `.current` for per-request
      * tenant resolution. */
    def aiConfigForChat: AIConfig = current.aiConfig

    /** Build a tenant-specific environment by replacing the environment string
      * and all derived names, while keeping global infrastructure config.
      *
      * Per-tenant AI overrides live at fixed paths that mirror the global slots:
      *   {env}/ai-primary, {env}/codegen, {env}/embedding, {env}/web-search
      * Each is fully self-describing — provider/endpoint/model/apiKey are read from
      * the secret itself, no path derivation. */
    def forEnvironment(env: String): DatrisEnvironment = {
        val tenantAiConfig = loadTenantAiConfig(env + "/ai-primary").getOrElse(values.aiConfig)
        val tenantCodegenAiConfig: Option[AIConfig] =
            loadTenantAiConfig(env + "/codegen").orElse(values.codegenAiConfig)
        val tenantWebSearchConfig: Option[WebSearchConfig] =
            loadTenantWebSearchConfig(env + "/web-search").orElse(values.webSearchConfig)
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
            webSearchConfig = tenantWebSearchConfig,
            tapTableName = env + "-tap",
            tapLogTableName = env + "-tap-log",
            tapLedgerTableName = env + "-tap-ledger",
            tapPromptTableName = env + "-tap-prompt",
            userTableName = env + "-user",
            userSessionTableName = env + "-user-session",
            postgresDatabase = env
        )
    }

    /** Reload AI configuration from Vault without restarting.
      * Called after the UI saves new AI secrets. */
    def reloadAiConfig(): Unit = synchronized {
        val env = values.environment
        val aiConfig = loadTenantAiConfig(env + "/ai-primary").getOrElse(values.aiConfig)
        val codegenAiConfig: Option[AIConfig] = loadTenantAiConfig(env + "/codegen").orElse(values.codegenAiConfig)
        val webSearchConfig: Option[WebSearchConfig] = loadTenantWebSearchConfig(env + "/web-search").orElse(values.webSearchConfig)
        values = values.copy(aiConfig = aiConfig, codegenAiConfig = codegenAiConfig, webSearchConfig = webSearchConfig)
    }

    /** Load a tenant AI override from a self-describing Vault secret.
      * Returns None when the secret doesn't exist or has an empty apiKey (zombie record).
      * apiKey resolves through `AIUtil.resolveApiKey`, which adds env-var fallback for
      * single-tenant deployments only — multi-tenant tenants must have their own keys. */
    private def loadTenantAiConfig(path: String): Option[AIConfig] = {
        try {
            ai.datris.util.SecretsUtil.getSecretMap(path).flatMap { s =>
                import scala.collection.JavaConverters._
                val map = s.asScala
                val provider = map.getOrElse("provider", "").trim
                val endpoint = map.getOrElse("endpoint", "").trim
                val rawKey   = map.getOrElse("apiKey", "")
                val apiKey =
                    if (provider.toLowerCase == "ollama") rawKey
                    else ai.datris.util.AIUtil.resolveApiKey(rawKey, provider, values.multiTenant, path.takeWhile(_ != '/'))
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

    /** Load the web-search override from a self-describing Vault secret. Mirrors
      * the embedding loader — the secret carries its own provider/endpoint/model/apiKey
      * so it can stand alone independent of AI Primary. apiKey resolution mirrors
      * loadTenantAiConfig (env-var fallback for single-tenant). */
    private def loadTenantWebSearchConfig(path: String): Option[WebSearchConfig] = {
        try {
            ai.datris.util.SecretsUtil.getSecretMap(path).flatMap { s =>
                import scala.collection.JavaConverters._
                val map = s.asScala
                val provider = map.getOrElse("provider", "").trim.toLowerCase
                if (!Seq("anthropic", "openai").contains(provider)) None
                else {
                    val enabled  = map.getOrElse("enabled", "false").trim.equalsIgnoreCase("true")
                    val endpoint = map.getOrElse("endpoint", "").trim
                    val model    = map.getOrElse("model", "").trim
                    val rawKey   = map.getOrElse("apiKey", "")
                    val version  = map.getOrElse("version", "")
                    val maxUses  = try map.getOrElse("maxUses", "3").trim.toInt catch { case _: Exception => 3 }
                    val apiKey   = ai.datris.util.AIUtil.resolveApiKey(rawKey, provider, values.multiTenant, path.takeWhile(_ != '/'))
                    Some(WebSearchConfig(enabled, provider, endpoint, model, apiKey, version, maxUses))
                }
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
                                  tapMaxOutputMB: Int = 100,
                                  dateFormat: String = "yyyy-MM-dd HH:mm:ss z",
                                  dateTimezone: String = "UTC",
                                  postgresDatabase: String = "datris",
                                  codegenAiConfig: Option[AIConfig] = None,
                                  webSearchConfig: Option[WebSearchConfig] = None,
                                  extendedThinking: Boolean = true,
                              hosted: Boolean = false,
                              useUserAuth: Boolean = false,
                              userTableName: String = null,
                              userSessionTableName: String = null,
                              // Max definition versions retained per entity. Older
                              // version records (and, for taps, their pinned script
                              // objects) are pruned beyond this cap. Configurable.
                              versionCap: Int = 50
                              ) {
    /** Append-only definition-version collections. Derived from the live table
      * names so they track per-tenant naming automatically (`<env>-tap-version`,
      * `<env>-pipeline-version`) without separate wiring in StartupRunner /
      * forEnvironment. */
    def tapVersionTableName: String = tapTableName + "-version"
    def pipelineVersionTableName: String = pipelineTableName + "-version"

    /** True for trial-droplet tenants. Trials have AI configuration locked at the
      * server level — see SecretsAPIController.rejectIfTrialAiSecret. The convention
      * is enforced by the website's provision-trial.ts which always assigns
      * `trial-{slug}-{shortid}` env names. */
    def isTrial: Boolean = environment != null && environment.startsWith("trial-")
}
