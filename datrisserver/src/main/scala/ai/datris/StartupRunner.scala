package ai.datris

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import ai.datris.util.{PipelineConfigIO, NotificationUtil, SecretsUtil, SessionStore, StagingArea, UserStore}
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

    // Agent policy — approval gate for agent-initiated actions. Off by
    // default; needs a restart to flip, like the sibling flags.
    @Value("${useAgentPolicy:false}")
    var useAgentPolicy: Boolean = _

    // Where an approved agent action is replayed to (this server).
    @Value("${server.port:8080}")
    var serverPort: Int = _

    // Recovery agent — autonomous incident loop. Off by default.
    @Value("${recoveryAgent.enabled:false}")
    var recoveryAgentEnabled: Boolean = _

    @Value("${recoveryAgent.webhookUrl:}")
    var incidentWebhookUrl: String = _

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

    // Tap wall-clock ceilings. A TEST is bounded by tapScriptTimeoutSeconds
    // (TAP_SCRIPT_TIMEOUT_SECONDS, default 300); a real or cron RUN by
    // tapRunTimeoutSeconds (TAP_RUN_TIMEOUT_SECONDS, default 3600). The run
    // ceiling is bound as a string so a blank container var reads as "unset"
    // (-1) and resolves to max(3600, tapScriptTimeoutSeconds) — an install that
    // raised the old single knob never gets a SHORTER real-run ceiling.
    @Value("${tapScriptTimeoutSeconds:${TAP_SCRIPT_TIMEOUT_SECONDS:300}}")
    var tapScriptTimeoutSeconds: Int = _

    @Value("${tapRunTimeoutSeconds:${TAP_RUN_TIMEOUT_SECONDS:}}")
    var tapRunTimeoutSecondsRaw: String = _

    // Automatic retry of failed cron-triggered tap runs (transient failures
    // self-clear; only runs that fed nothing downstream are retried).
    @Value("${cron.retry.enabled:true}")
    var cronRetryEnabled: Boolean = _

    @Value("${cron.retry.cap:3}")
    var cronRetryCap: Int = _

    @Value("${cron.retry.backoffMinutes:5,15}")
    var cronRetryBackoffMinutes: String = _

    // Per-run payload disk budget (PIPELINE_MAX_PAYLOAD_MB): a tap run's staged
    // output is stopped above it; 0 = unlimited. Bound as strings so an empty
    // container var (compose forwards the deprecated alias with no default)
    // reads as "unset" (-1) rather than failing conversion. TAP_MAX_OUTPUT_MB /
    // tapMaxOutputMB is the deprecated alias, honoured for one release when the
    // new property is not set.
    @Value("${tapMaxOutputMB:}")
    var tapMaxOutputMBRaw: String = _

    @Value("${pipelineMaxPayloadMB:${PIPELINE_MAX_PAYLOAD_MB:}}")
    var pipelineMaxPayloadMBRaw: String = _

    /** -1 when blank or not an integer. */
    private def optionalInt(raw: String): Int =
        Option(raw).map(_.trim).filter(_.nonEmpty).flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(-1)

    // Scratch destination: how many result rows ride inline on the run status
    // (resultPreview) and how long a `_scratch/` object is retained before it
    // may be swept (resultExpiresAt).
    @Value("${scratchInlineRows:200}")
    var scratchInlineRows: Int = _

    @Value("${scratchRetentionHours:24}")
    var scratchRetentionHours: Int = _

    // Pipeline payload staging: local directory for a run's staged files and
    // the cap on what a deprecated whole-payload reader may materialize. Both
    // resolve from the documented env var names directly (DATRIS_TEMP_DIR,
    // PIPELINE_MATERIALIZE_MAX_MB) as well as the Spring property.
    @Value("${datris.tempDir:${DATRIS_TEMP_DIR:/tmp/datris-staging}}")
    var tempDir: String = _

    @Value("${pipelineMaterializeMaxMB:${PIPELINE_MATERIALIZE_MAX_MB:256}}")
    var pipelineMaterializeMaxMB: Int = _

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

    // Doctor: log the cheap operational self-checks at boot (Vault token TTL,
    // AI slot secrets, embedding model, disk). Warn lines only — never blocks
    // startup. GET /api/v1/doctor and `datris doctor` run the same checks.
    @Value("${doctor.onStartup:true}")
    var doctorOnStartup: Boolean = _

    // Doctor: re-run the full server-side report every N minutes and POST
    // checks that flip to error (or recover) to recoveryAgent.webhookUrl.
    // 0 = off. Never includes the AI probes.
    @Value("${doctor.intervalMinutes:0}")
    var doctorIntervalMinutes: Int = _

    @Override
    def run(args: ApplicationArguments): Unit = {
        ai.datris.util.TapScriptRunner.assertIsolationConfig()
        if (!ai.datris.util.TapScriptRunner.useTapRunner)
            ai.datris.util.TapScriptRunner.warnInProcess("startup")
        initDatrisEnvironment()
        ai.datris.policy.PolicyReplay.port = serverPort
        if (recoveryAgentEnabled) {
            if (!useAgentPolicy)
                logger.warn(
                    "RECOVERY_AGENT_ENABLED is true but USE_AGENT_POLICY is false — the recovery agent needs the approval queue and will stay dormant until the agent policy is enabled"
                )
            else {
                ai.datris.incident.RecoveryKey.ensure()
                logger.info("Recovery agent enabled: collection=" + environment + "-incident; mode comes from the agent policy's recovery.mode (off until set)")
            }
        }
        if (useAgentPolicy)
            logger.info(
                "Agent policy enabled: collections=" + environment + "-agent-policy, " + environment + "-pending-action; approvals replay to port " + serverPort
            )
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
        // Field names deliberately avoid "apiKey" so the redactor leaves the booleans alone.
        md.addProperty("userAuth", useUserAuth)
        md.addProperty("programmaticKeys", useApiKeys)
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

        // Tap wall-clock ceilings: a test is bounded by tapScriptTimeoutSeconds,
        // a real or cron run by tapRunTimeoutSeconds. An unset run ceiling (blank
        // container var) resolves to max(3600, tapScriptTimeoutSeconds).
        val rawTapRunTimeoutSeconds = optionalInt(tapRunTimeoutSecondsRaw)
        val tapRunTimeoutSecondsRawTrimmed = Option(tapRunTimeoutSecondsRaw).map(_.trim).getOrElse("")
        if (rawTapRunTimeoutSeconds < 0 && tapRunTimeoutSecondsRawTrimmed.nonEmpty)
            logger.warn(
                "TAP_RUN_TIMEOUT_SECONDS='" + tapRunTimeoutSecondsRawTrimmed +
                    "' is not a whole number of seconds and is ignored; the run ceiling falls back to " +
                    "max(3600, tapScriptTimeoutSeconds). Set it to an integer, e.g. 3600."
            )
        val tapRunTimeoutSeconds =
            ai.datris.util.TapScriptRunner.resolveRunTimeoutSeconds(rawTapRunTimeoutSeconds, tapScriptTimeoutSeconds)
        logger.info(
            "Tap timeouts: test ceiling " + tapScriptTimeoutSeconds + "s (TAP_SCRIPT_TIMEOUT_SECONDS), run ceiling " +
                tapRunTimeoutSeconds + "s (TAP_RUN_TIMEOUT_SECONDS" +
                (if (rawTapRunTimeoutSeconds >= 0) ", explicitly set" else ", unset — defaulted to max(3600, test ceiling)") + ")"
        )

        // Payload disk budget: the new property wins; the deprecated alias is
        // honoured only when the new one is unset, with a one-line warning either way.
        val tapMaxOutputMB = optionalInt(tapMaxOutputMBRaw)
        val pipelineMaxPayloadMB = optionalInt(pipelineMaxPayloadMBRaw)
        if (tapMaxOutputMB >= 0) {
            if (pipelineMaxPayloadMB >= 0 && pipelineMaxPayloadMB == tapMaxOutputMB)
                logger.warn(
                    "TAP_MAX_OUTPUT_MB is deprecated; the per-run payload disk budget resolved to " + pipelineMaxPayloadMB +
                        " MB. Rename it to PIPELINE_MAX_PAYLOAD_MB; the alias goes away next release."
                )
            else if (pipelineMaxPayloadMB >= 0)
                logger.warn(
                    "TAP_MAX_OUTPUT_MB=" + tapMaxOutputMB + " is deprecated and ignored because PIPELINE_MAX_PAYLOAD_MB=" +
                        pipelineMaxPayloadMB + " is set. Remove TAP_MAX_OUTPUT_MB; the alias goes away next release."
                )
            else
                logger.warn(
                    "TAP_MAX_OUTPUT_MB=" + tapMaxOutputMB + " is deprecated; it is honoured as the per-run payload disk budget for this release. " +
                        "Rename it to PIPELINE_MAX_PAYLOAD_MB (MB, 0 = unlimited, default " + StagingArea.DefaultPayloadBudgetMB + ")."
                )
        }

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
            tapRunTimeoutSeconds = tapRunTimeoutSeconds,
            tapMaxOutputMB = tapMaxOutputMB,
            pipelineMaxPayloadMB = pipelineMaxPayloadMB,
            scratchInlineRows = scratchInlineRows,
            scratchRetentionHours = scratchRetentionHours,
            tempDir = tempDir,
            pipelineMaterializeMaxMB = pipelineMaterializeMaxMB,
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
            useAgentPolicy = useAgentPolicy,
            agentPolicyTableName = environment + "-agent-policy",
            pendingActionTableName = environment + "-pending-action",
            recoveryAgentEnabled = recoveryAgentEnabled,
            incidentTableName = environment + "-incident",
            incidentWebhookUrl = incidentWebhookUrl,
            auditLogLogReads = auditLogLogReads,
            auditLogEmitLogLine = auditLogEmitLogLine
        )

        DatrisEnvironment.init(pipelineEnvironment)

        // Payload staging: create the root and reclaim anything a previous
        // process left behind (a run that died before its JobRunner finally).
        try {
            StagingArea.ensureRoot()
            StagingArea.sweepOlderThan(StagingArea.SweepAge)
        } catch {
            case e: Exception => logger.warn("Staging area init failed for " + tempDir + ": " + e.getMessage)
        }

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
        // Doctor's startup subset runs BEFORE the AI-secret hard failures below
        // so a missing `oss/codegen` gets a DOCTOR line with its fix ahead of
        // the stack trace that stops the boot. Wrapped: a doctor bug never
        // blocks startup.
        ai.datris.util.DoctorService.configure(ai.datris.util.DoctorService.Slots(aiPrimarySecretName, codegenSecretName, embeddingSecretName))
        if (doctorOnStartup) {
            try ai.datris.util.DoctorService.runStartupLive()
            catch { case e: Exception => logger.warn("DOCTOR startup checks failed (continuing): " + e.getMessage) }
        }
        ai.datris.util.DoctorMonitor.configure(doctorIntervalMinutes)

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
