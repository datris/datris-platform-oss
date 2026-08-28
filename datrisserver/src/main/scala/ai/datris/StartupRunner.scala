package ai.datris

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.{PipelineConfigIO, NotificationUtil, SecretsUtil, SessionStore, UserStore}
import ai.datris.controller.KafkaConsumerRunner
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.{ApplicationArguments, ApplicationRunner}
import org.springframework.stereotype.Component

@Component
class StartupRunner extends ApplicationRunner {
    private val logger: Logger = LoggerFactory.getLogger(classOf[StartupRunner])

    @Value("${environment}")
    var environment: String = _

    @Value("${useApiKeys}")
    var useApiKeys: Boolean = _

    @Value("${useUserAuth:false}")
    var useUserAuth: Boolean = _

    @Value("${multiTenant:false}")
    var multiTenant: Boolean = _

    // Audit log — durable record of who did what. Off by default; flipping it
    // on needs a restart (read once here, like the sibling auth flags).
    @Value("${useAuditLog:false}")
    var useAuditLog: Boolean = _

    @Value("${auditLog.retentionDays:90}")
    var auditLogRetentionDays: Int = _

    @Value("${auditLog.logReads:false}")
    var auditLogLogReads: Boolean = _

    @Value("${auditLog.emitLogLine:true}")
    var auditLogEmitLogLine: Boolean = _

    @Value("${secrets.apiKeysSecretName:}")
    var apiKeysSecretName: String = _

    @Value("${secrets.postgresSecretName:}")
    var postgresSecretName: String = _

    @Value("${secrets.minIOSecretName}")
    var minIOSecretName: String = _

    @Value("${secrets.activeMQSecretName}")
    var activeMQSecretName: String = _

    @Value("${secrets.mongoDbSecretName}")
    var mongoDbSecretName: String = _

    @Value("${secrets.kafkaProducerSecretName}")
    var kafkaProducerSecretName: String = _

    @Value("${sendPipelineNotifications}")
    var sendPipelineNotifications: Boolean = _

    @Value("${ttlFileNotifierQueueMessages:60}")
    var ttlFileNotifierQueueMessages: Int = _

    @Value("${kafkaConsumer.enabled}")
    var kafkaConsumerEnabled: Boolean = _

    @Value("${kafkaConsumer.bootstrapServers}")
    var kafkaConsumerBootstrapServer: String = _

    @Value("${kafkaConsumer.groupId}")
    var kafkaConsumerGroupId: String = _

    @Value("${kafkaConsumer.topicPollingInterval}")
    var kafkaConsumerPollingInterval: Int = _

    @Value("${kafkaConsumer.topicPrefix}")
    var kafkaConsumerTopicPrefix: String = _

    @Value("${minio.server}")
    var minioServer: String = _

    @Value("${activemq.server}")
    var activeMQServer: String = _

    @Value("${mongodb.connectionString}")
    var mongoDbConnectionString: String = _

    @Value("${mongodb.database:datris}")
    var mongoDbDatabase: String = _

    @Value("${mongodb.internalDatabase:oss}")
    var mongoDbInternalDatabase: String = _

    @Value("${postgres.database:datris}")
    var postgresDatabase: String = _

    @Value("${ai.enabled:false}")
    var aiEnabled: Boolean = _

    // AI configuration — three independent, self-describing Vault secrets.
    // Each secret carries provider/endpoint/model/apiKey/version inline.
    @Value("${ai.aiPrimary.secretName:}")
    var aiPrimarySecretName: String = _

    @Value("${ai.codegen.secretName:}")
    var codegenSecretName: String = _

    @Value("${ai.embedding.secretName:}")
    var embeddingSecretName: String = _

    @Value("${ai.webSearch.secretName:}")
    var webSearchSecretName: String = _

    @Value("${ai.extendedThinking:true}")
    var extendedThinking: Boolean = _

    @Value("${secrets.qdrantSecretName:}")
    var qdrantSecretName: String = _

    @Value("${secrets.weaviateSecretName:}")
    var weaviateSecretName: String = _

    @Value("${secrets.milvusSecretName:}")
    var milvusSecretName: String = _

    @Value("${secrets.chromaSecretName:}")
    var chromaSecretName: String = _

    @Value("${secrets.pgvectorSecretName:}")
    var pgvectorSecretName: String = _

    @Value("${tapScriptTimeoutSeconds:300}")
    var tapScriptTimeoutSeconds: Int = _

    // Automatic retry of failed cron-triggered tap runs (transient failures
    // self-clear; only runs that fed nothing downstream are retried).
    @Value("${cron.retry.enabled:true}")
    var cronRetryEnabled: Boolean = _

    @Value("${cron.retry.cap:3}")
    var cronRetryCap: Int = _

    @Value("${cron.retry.backoffMinutes:5,15}")
    var cronRetryBackoffMinutes: String = _

    // Tap script output ceiling. Guards the JVM from buffering massive script
    // output and OOM'ing the server; the agent sees an actionable error and
    // can retry with a smaller chunk (e.g., shorter date window).
    @Value("${tapMaxOutputMB:100}")
    var tapMaxOutputMB: Int = _

    @Value("${dateFormat:yyyy-MM-dd HH:mm:ss z}")
    var dateFormat: String = _

    @Value("${dateTimezone:UTC}")
    var dateTimezone: String = _

    @Value("${hosted:false}")
    var hosted: Boolean = _

    // Max definition versions retained per tap/pipeline before older snapshots
    // (and their pinned script objects) are pruned. See tap-pipeline-versioning.
    @Value("${versionCap:50}")
    var versionCap: Int = _

    @Override
    def run(args: ApplicationArguments): Unit = {
        ai.datris.util.TapScriptRunner.assertIsolationConfig()
        if (!ai.datris.util.TapScriptRunner.useTapRunner)
            ai.datris.util.TapScriptRunner.warnInProcess("startup")
        initDatrisEnvironment()
        if (useAuditLog)
            logger.info("Audit log enabled: collection=" + environment + "-audit-log, retentionDays=" + auditLogRetentionDays +
                ", logReads=" + auditLogLogReads + ", emitLogLine=" + auditLogEmitLogLine)
        initUserAuth()
        // Seed v1 definition snapshots for any pre-versioning taps/pipelines so
        // their version history isn't empty. Idempotent — skips entities that
        // already have version records.
        ai.datris.util.VersionBackfill.run()
        if (kafkaConsumerEnabled)
            initKafkaConsumerRunner()
        auditServerStart()
    }

    private def auditServerStart(): Unit = {
        val md = new com.google.gson.JsonObject()
        md.addProperty("version", ai.datris.build.sbt.BuildInfo.version)
        md.addProperty("useUserAuth", useUserAuth)
        md.addProperty("useApiKeys", useApiKeys)
        ai.datris.audit.AuditLog.system("system", "start", metadata = md)
    }

    /** Idempotent: ensure the user-session TTL index exists and seed a default admin
      * if no users are present. Runs regardless of `useUserAuth` so flipping the flag
      * later is a clean toggle — no provisioning step needed. */
    private def initUserAuth(): Unit = {
        try {
            SessionStore.ensureIndex()
            val existingUsers = UserStore.list()
            if (existingUsers.nonEmpty) {
                // Upgrade safety: any pre-existing account with a null/empty
                // hash was previously loginable with ANY password (the takeover
                // hole). Login now always verifies, which would lock these
                // accounts out — so rotate each to a random bootstrap password,
                // printed once, and close the hole at the same time.
                existingUsers.filter(_.mustSetPassword).foreach { u =>
                    val pw = ai.datris.util.PasswordHasher.generateTemporary()
                    UserStore.updatePasswordHash(u.username, ai.datris.util.PasswordHasher.hash(pw))
                    logger.warn(
                        "User '" + u.username + "' had no password set (previously loginable with any password). " +
                            "Assigned a bootstrap password: " + pw + "  (shown once; log in and change it immediately)"
                    )
                }
            }
            if (existingUsers.isEmpty) {
                val now = java.time.Instant.now().toString
                // Seed with a random bootstrap password rather than a null hash.
                // A null hash made the admin account claimable by anyone who
                // reached /auth/login first (any password was accepted), so an
                // attacker could take over admin on a fresh deploy before the
                // operator's first login. The password is printed to the server
                // log exactly once here; the operator reads it from the logs to
                // log in, then changes it. It is never stored in plaintext.
                val bootstrapPassword = ai.datris.util.PasswordHasher.generateTemporary()
                UserStore.insert(User(
                    username = "admin",
                    passwordHash = ai.datris.util.PasswordHasher.hash(bootstrapPassword),
                    role = User.RoleAdmin,
                    createdAt = now,
                    updatedAt = now,
                    lastLoginAt = null
                ))
                logger.info(
                    "Seeded default admin user. Bootstrap login — username: admin  password: {}  " +
                        "(shown once; log in and change it immediately)",
                    bootstrapPassword
                )
                ai.datris.audit.AuditLog.system("user", "seed-admin", "user", "admin")
            }
        } catch {
            case e: Exception =>
                logger.warn("User-auth init failed (continuing): " + e.getMessage)
        }
    }

    private def initDatrisEnvironment(): Unit = {
        // Set default values based upon the environment name
        val fileNotifierQueue = environment + "-file-notifier"
        val pipelineTableName = environment + "-pipeline"
        val archivedMetadataTableName = environment + "-archived-metadata"
        val pipelineStatusTableName = environment + "-pipeline-status"
        val fileNotifierMessageTableName = environment + "-file-notifier-message"
        val pipelinePullTableName = environment + "-data-pull"

        val kafkaConsumerConfig = {
            if (kafkaConsumerEnabled) {
                KafkaConsumerConfig(
                    kafkaConsumerEnabled,
                    kafkaConsumerBootstrapServer,
                    kafkaConsumerGroupId,
                    kafkaConsumerPollingInterval,
                    kafkaConsumerTopicPrefix
                )
            } else
                null
        }

        val mongoDbConfig = MongoDBConfig(
            mongoDbConnectionString,
            mongoDbDatabase,
            mongoDbInternalDatabase
        )

        val pipelineEnvironment = DatrisEnvironment(
            initialized = false,
            environment,
            fileNotifierQueue,
            ttlFileNotifierQueueMessages,
            pipelineTopic = null,
            pipelineTableName,
            archivedMetadataTableName,
            pipelineStatusTableName,
            fileNotifierMessageTableName,
            pipelinePullTableName,
            useApiKeys,
            apiKeysSecretName,
            postgresSecretName,
            mongoDbSecretName,
            kafkaProducerSecretName,
            kafkaConsumerConfig,
            mongoDbConfig,
            minIOConfig = null,
            activeMQConfig = null,
            aiConfig = null,
            aiEnabled = false,
            embeddingSecretName,
            qdrantSecretName,
            weaviateSecretName,
            milvusSecretName,
            chromaSecretName,
            pgvectorSecretName,
            multiTenant,
            tapTableName = environment + "-tap",
            tapLogTableName = environment + "-tap-log",
            tapLedgerTableName = environment + "-tap-ledger",
            tapPromptTableName = environment + "-tap-prompt",
            tapScriptTimeoutSeconds = tapScriptTimeoutSeconds,
            tapMaxOutputMB = tapMaxOutputMB,
            dateFormat = dateFormat,
            dateTimezone = dateTimezone,
            postgresDatabase = postgresDatabase,
            hosted = hosted,
            useUserAuth = useUserAuth,
            userTableName = environment + "-user",
            userSessionTableName = environment + "-user-session",
            versionCap = versionCap,
            cronRetryEnabled = cronRetryEnabled,
            cronRetryCap = cronRetryCap,
            cronRetryBackoffMinutes = cronRetryBackoffMinutes,
            useAuditLog = useAuditLog,
            auditLogTableName = environment + "-audit-log",
            auditLogRetentionDays = auditLogRetentionDays,
            auditLogLogReads = auditLogLogReads,
            auditLogEmitLogLine = auditLogEmitLogLine
        )

        DatrisEnvironment.init(pipelineEnvironment)

        // Initialize MinIO after Pipeline init because SecretsUtil uses the Pipeline env
        val minIOConfig = {
            val secret = SecretsUtil.getSecretMap(minIOSecretName)
                .getOrElse(throw new DatrisException("MinIO secret not found, secret name: " + minIOSecretName))
            val accessKey = secret.get("accessKey")
            if (accessKey == null)
                throw new DatrisException("MinIO accessKey not found in the Secrets Manager, secret: " + minIOSecretName)
            val secretKey = secret.get("secretKey")
            if (secretKey == null)
                throw new DatrisException("MinIO secretKey not found in the Secrets Manager, secret: " + minIOSecretName)
            MinIOConfig(
                minioServer,
                accessKey,
                secretKey
            )
        }

        val activeMQConfig = {
            val secret = SecretsUtil.getSecretMap(activeMQSecretName)
                .getOrElse(throw new DatrisException("ActiveMQ secret not found, secret name: " + activeMQSecretName))
            val username = secret.get("username")
            if (username == null)
                throw new DatrisException("ActiveMQ username not found in the Secrets Manager, secret: " + activeMQSecretName)
            val password = secret.get("password")
            if (password == null)
                throw new DatrisException("ActiveMQ password not found in the Secrets Manager, secret: " + activeMQSecretName)
            ActiveMQConfig(
                activeMQServer,
                username,
                password
            )
        }
        DatrisEnvironment.init(DatrisEnvironment.values.copy(minIOConfig = minIOConfig, activeMQConfig = activeMQConfig))

        // And Notifications, send pipeline notifications?
        val pipelineTopic = {
            if (sendPipelineNotifications)
                "VirtualTopic." + environment + "-pipeline-notification"
            else
                null
        }
        // AI configuration is required — CodeGen data quality and transformation depend on it.
        // Three independent secrets, each fully self-describing (provider/endpoint/model/apiKey/version
        // all live inside the Vault secret).
        if (!aiEnabled)
            throw new DatrisException("AI is required but not enabled. Set 'ai.enabled: true' in application.yaml")
        if (aiPrimarySecretName == null || aiPrimarySecretName.isEmpty)
            throw new DatrisException(
                "AI is enabled but no primary secret is configured. Set 'ai.aiPrimary.secretName' in application.yaml (e.g., 'oss/ai-primary')"
            )

        val aiConfig = loadAiConfigFromSecret(aiPrimarySecretName, "ai-primary", required = true).get
        logger.info("AI primary configured: " + aiConfig.provider + ", model: " + aiConfig.model + ", endpoint: " + aiConfig.endpoint)

        // Optional codegen AI config — None if the secret doesn't exist (codegen will fall back to main).
        val codegenAiConfig: Option[AIConfig] =
            if (codegenSecretName == null || codegenSecretName.isEmpty) None
            else loadAiConfigFromSecret(codegenSecretName, "codegen", required = false)
        codegenAiConfig.foreach(c => logger.info("AI codegen configured: " + c.provider + ", model: " + c.model + ", endpoint: " + c.endpoint))

        // Optional web-search config. Mirrors the Embedding pattern — its own
        // provider, endpoint, model, and apiKey, independent of AI Primary. When
        // the configured provider matches the main AI call we attach the tool
        // natively (fastest); otherwise we make a separate search call and inject
        // the results as context.
        val webSearchConfig: Option[WebSearchConfig] =
            if (webSearchSecretName == null || webSearchSecretName.isEmpty) None
            else loadWebSearchConfigFromSecret(webSearchSecretName)
        webSearchConfig.foreach(c =>
            logger.info("Web search configured: provider=" + c.provider + ", model=" + c.model + ", enabled=" + c.enabled + ", maxUses=" + c.maxUses)
        )

        DatrisEnvironment.init(DatrisEnvironment.values.copy(
            initialized = true,
            pipelineTopic = pipelineTopic,
            aiConfig = aiConfig,
            codegenAiConfig = codegenAiConfig,
            webSearchConfig = webSearchConfig,
            aiEnabled = aiEnabled,
            extendedThinking = extendedThinking
        ))
    }

    /** Load a WebSearchConfig from a self-describing Vault secret. Mirrors the
      * embedding loader — the secret stands alone with its own provider, endpoint,
      * model, and apiKey. apiKey resolves through `AIUtil.resolveApiKey` (env-var
      * fallback for single-tenant deployments). */
    private def loadWebSearchConfigFromSecret(secretName: String): Option[WebSearchConfig] = {
        SecretsUtil.getSecretMap(secretName).flatMap { secret =>
            val provider = Option(secret.get("provider")).map(_.trim.toLowerCase).getOrElse("")
            if (!Seq("anthropic", "openai").contains(provider)) {
                logger.warn(
                    "Web search secret " + secretName + " has missing or invalid provider: '" + provider + "' — disabling web search. Valid values are: anthropic, openai"
                )
                None
            } else {
                val enabled = Option(secret.get("enabled")).exists(_.trim.equalsIgnoreCase("true"))
                val endpoint = Option(secret.get("endpoint")).map(_.trim).getOrElse("")
                val model = Option(secret.get("model")).map(_.trim).getOrElse("")
                val rawKey = Option(secret.get("apiKey")).getOrElse("")
                val version = Option(secret.get("version")).getOrElse("")
                val maxUses =
                    try Option(secret.get("maxUses")).map(_.trim.toInt).getOrElse(3)
                    catch {
                        case e: Exception =>
                            logger.debug("Invalid maxUses in web search secret " + secretName + ", defaulting to 3", e)
                            3
                    }
                val apiKey = ai.datris.util.AIUtil.resolveApiKey(rawKey, provider, DatrisEnvironment.values.multiTenant, DatrisEnvironment.values.environment)
                if (apiKey != rawKey && apiKey.nonEmpty)
                    logger.info("Web search apiKey resolved from the shared key store or " + provider.toUpperCase + "_API_KEY env var (secret has no apiKey)")
                Some(WebSearchConfig(enabled, provider, endpoint, model, apiKey, version, maxUses))
            }
        }
    }

    /** Load an AIConfig from a self-describing Vault secret. The secret must contain
      * `provider`, `endpoint`, `model`, and `apiKey`. `version` is optional.
      *
      * @param required when true, missing/empty fields throw; when false, returns None.
      */
    private def loadAiConfigFromSecret(secretName: String, label: String, required: Boolean): Option[AIConfig] = {
        val secretOpt = SecretsUtil.getSecretMap(secretName)
        if (secretOpt.isEmpty) {
            if (required)
                throw new DatrisException(
                    "AI " + label + " secret not found in Vault: " + secretName +
                        ". Create it with: vault kv put secret/" + secretName + " provider=<anthropic|openai|azure|bedrock|grok|ollama> endpoint=<url> model=<model> apiKey=<key>"
                )
            else return None
        }
        val secret = secretOpt.get
        val provider = Option(secret.get("provider")).map(_.trim).getOrElse("")
        val endpoint = Option(secret.get("endpoint")).map(_.trim).getOrElse("")
        val model = Option(secret.get("model")).map(_.trim).getOrElse("")
        val rawKey = Option(secret.get("apiKey")).getOrElse("")
        val version = Option(secret.get("version")).getOrElse("")

        if (provider.isEmpty) {
            if (required) throw new DatrisException("'provider' not found in AI " + label + " secret: " + secretName)
            else return None
        }
        if (!Seq("anthropic", "openai", "azure", "bedrock", "grok", "ollama").contains(provider.toLowerCase))
            throw new DatrisException(
                "Unsupported AI provider in " + label + " secret '" + secretName + "': '" + provider + "'. Valid values are: anthropic, openai, azure, bedrock, grok, ollama"
            )
        // Bedrock derives its invoke URL from the resolved AWS region + model at
        // request time, so a blank endpoint is valid (an explicit one overrides —
        // GovCloud / VPC endpoints).
        if (endpoint.isEmpty && provider.toLowerCase != "bedrock") {
            if (required) throw new DatrisException(
                if (provider.toLowerCase == "azure")
                    "'endpoint' not found in AI " + label + " secret: " + secretName +
                        ". Azure has no default endpoint — set your resource URL, e.g. https://YOUR-RESOURCE.openai.azure.com/openai/v1/chat/completions"
                else "'endpoint' not found in AI " + label + " secret: " + secretName
            )
            else return None
        }
        if (model.isEmpty) {
            if (required) throw new DatrisException(
                provider.toLowerCase match {
                    case "azure" => "'model' not found in AI " + label + " secret: " + secretName + ". For Azure, set it to your deployment name."
                    case "bedrock" => "'model' not found in AI " + label + " secret: " + secretName +
                            ". For Bedrock, set it to an invokable model id (e.g. anthropic.claude-sonnet-5, or a cross-region inference profile like us.anthropic....)."
                    case _ => "'model' not found in AI " + label + " secret: " + secretName
                }
            )
            else return None
        }

        // Resolve apiKey: secret value first, env-var fallback for single-tenant.
        // Ollama doesn't need a key; Bedrock has no API-key concept at all —
        // AWS credentials resolve separately at request-signing time (ai-keys
        // AWS fields / env vars / default credential chain), so a missing or
        // invalid credential surfaces on the first call, not at startup.
        // Azure's key is OPTIONAL, not absent: the normal resolveApiKey tiers
        // (ai-keys azureApiKey, slot key, AZURE_OPENAI_API_KEY env) still apply,
        // but when they all come up empty the section is valid anyway — Entra ID
        // credentials (ai-keys SP trio / AZURE_* env vars / managed identity)
        // resolve per request in AzureEntraSupport, and a missing or invalid
        // setup surfaces on the first call with an error naming every fix.
        val keylessProviders = Set("ollama", "bedrock")
        val keyOptionalProviders = keylessProviders + "azure"
        // grok's env var follows xAI's convention (XAI_API_KEY), not the derived
        // GROK_API_KEY the generic rule would produce.
        val keyEnvVar = provider.toLowerCase match {
            case "azure" => "AZURE_OPENAI_API_KEY"
            case "grok" => "XAI_API_KEY"
            case _ => provider.toUpperCase + "_API_KEY"
        }
        val apiKey =
            if (keylessProviders.contains(provider.toLowerCase)) rawKey
            else ai.datris.util.AIUtil.resolveApiKey(rawKey, provider, DatrisEnvironment.values.multiTenant, DatrisEnvironment.values.environment)
        if (apiKey != rawKey && apiKey.nonEmpty)
            logger.info("AI " + label + " apiKey resolved from the shared key store or " + keyEnvVar + " env var (secret has no apiKey)")
        if (apiKey.isEmpty && !keyOptionalProviders.contains(provider.toLowerCase)) {
            if (required) throw new DatrisException("'apiKey' not found in AI " + label + " secret: " + secretName +
                " and no " + keyEnvVar + " environment variable is set")
            else return None
        }

        Some(AIConfig(provider, endpoint, model, apiKey, version))
    }

    private def initKafkaConsumerRunner(): Unit = {
        val runner = new KafkaConsumerRunner(
            DatrisEnvironment.values.kafkaConsumerConfig.bootstrapServers,
            DatrisEnvironment.values.kafkaConsumerConfig.groupId
        )

        // Find the pipeline configurations with streaming sources
        val configs = PipelineConfigIO.readAll(DatrisEnvironment.values.pipelineTableName)
        val streamingConfigs = configs.filter(c => {
            c.source.streamAttributes != null && c.source.streamAttributes.`type`.compareToIgnoreCase("kafka") == 0
        })
        val topicNames = streamingConfigs.map(c => {
            val topicPrefix = {
                if (DatrisEnvironment.values.kafkaConsumerConfig.topicPrefix != null && DatrisEnvironment.values.kafkaConsumerConfig.topicPrefix.nonEmpty)
                    DatrisEnvironment.values.kafkaConsumerConfig.topicPrefix
                else
                    ""
            }
            topicPrefix + "." + c.name
        })

        runner.addTopics(topicNames)

        new Thread(runner).start()
    }
}
