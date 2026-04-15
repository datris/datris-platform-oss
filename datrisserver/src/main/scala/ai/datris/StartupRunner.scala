package ai.datris

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model._
import ai.datris.util.{PipelineConfigIO, NotificationUtil, SecretsUtil}
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

    @Value("${multiTenant:false}")
    var multiTenant: Boolean = _

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

    @Value("${dateFormat:yyyy-MM-dd HH:mm:ss z}")
    var dateFormat: String = _

    @Value("${dateTimezone:UTC}")
    var dateTimezone: String = _

    @Value("${hosted:false}")
    var hosted: Boolean = _

    @Override
    def run(args: ApplicationArguments): Unit =  {
        initDatrisEnvironment()
        if(kafkaConsumerEnabled)
            initKafkaConsumerRunner()
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
            if(kafkaConsumerEnabled) {
                KafkaConsumerConfig(
                    kafkaConsumerEnabled,
                    kafkaConsumerBootstrapServer,
                    kafkaConsumerGroupId,
                    kafkaConsumerPollingInterval,
                    kafkaConsumerTopicPrefix
                )
            }
            else
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
            tapScriptTimeoutSeconds = tapScriptTimeoutSeconds,
            dateFormat = dateFormat,
            dateTimezone = dateTimezone,
            postgresDatabase = postgresDatabase,
            hosted = hosted
        )

        DatrisEnvironment.init(pipelineEnvironment)

        // Initialize MinIO after Pipeline init because SecretsUtil uses the Pipeline env
        val minIOConfig = {
            val secret = SecretsUtil.getSecretMap(minIOSecretName)
                .getOrElse(throw new DatrisException("MinIO secret not found, secret name: " + minIOSecretName))
            val accessKey = secret.get("accessKey")
            if(accessKey == null)
                throw new DatrisException("MinIO accessKey not found in the Secrets Manager, secret: " + minIOSecretName)
            val secretKey = secret.get("secretKey")
            if(secretKey == null)
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
            if(username == null)
                throw new DatrisException("ActiveMQ username not found in the Secrets Manager, secret: " + activeMQSecretName)
            val password = secret.get("password")
            if(password == null)
                throw new DatrisException("ActiveMQ password not found in the Secrets Manager, secret: " + activeMQSecretName)
            ActiveMQConfig(
                activeMQServer,
                username,
                password)
        }
        DatrisEnvironment.init(DatrisEnvironment.values.copy(minIOConfig = minIOConfig, activeMQConfig = activeMQConfig))

        // And Notifications, send pipeline notifications?
        val pipelineTopic = {
            if(sendPipelineNotifications)
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
            throw new DatrisException("AI is enabled but no primary secret is configured. Set 'ai.aiPrimary.secretName' in application.yaml (e.g., 'oss/ai-primary')")

        val aiConfig = loadAiConfigFromSecret(aiPrimarySecretName, "ai-primary", required = true).get
        logger.info("AI primary configured: " + aiConfig.provider + ", model: " + aiConfig.model + ", endpoint: " + aiConfig.endpoint)

        // Optional codegen AI config — None if the secret doesn't exist (codegen will fall back to main).
        val codegenAiConfig: Option[AIConfig] =
            if (codegenSecretName == null || codegenSecretName.isEmpty) None
            else loadAiConfigFromSecret(codegenSecretName, "codegen", required = false)
        codegenAiConfig.foreach(c => logger.info("AI codegen configured: " + c.provider + ", model: " + c.model + ", endpoint: " + c.endpoint))

        DatrisEnvironment.init(DatrisEnvironment.values.copy(initialized = true, pipelineTopic = pipelineTopic, aiConfig = aiConfig, codegenAiConfig = codegenAiConfig, aiEnabled = aiEnabled))
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
                throw new DatrisException("AI " + label + " secret not found in Vault: " + secretName +
                    ". Create it with: vault kv put secret/" + secretName + " provider=<anthropic|openai|ollama> endpoint=<url> model=<model> apiKey=<key>")
            else return None
        }
        val secret = secretOpt.get
        val provider = Option(secret.get("provider")).map(_.trim).getOrElse("")
        val endpoint = Option(secret.get("endpoint")).map(_.trim).getOrElse("")
        val model = Option(secret.get("model")).map(_.trim).getOrElse("")
        val apiKey = Option(secret.get("apiKey")).getOrElse("")
        val version = Option(secret.get("version")).getOrElse("")

        if (provider.isEmpty) {
            if (required) throw new DatrisException("'provider' not found in AI " + label + " secret: " + secretName)
            else return None
        }
        if (!Seq("anthropic", "openai", "ollama").contains(provider.toLowerCase))
            throw new DatrisException("Unsupported AI provider in " + label + " secret '" + secretName + "': '" + provider + "'. Valid values are: anthropic, openai, ollama")
        if (endpoint.isEmpty) {
            if (required) throw new DatrisException("'endpoint' not found in AI " + label + " secret: " + secretName)
            else return None
        }
        if (model.isEmpty) {
            if (required) throw new DatrisException("'model' not found in AI " + label + " secret: " + secretName)
            else return None
        }
        // For optional configs, an empty apiKey on a non-ollama provider means "not set" — fall back.
        if (!required && apiKey.isEmpty && provider.toLowerCase != "ollama") return None

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
            c.source.streamAttributes != null && c.source.streamAttributes.`type`.compareToIgnoreCase("kafka") ==0
        })
        val topicNames = streamingConfigs.map(c => {
            val topicPrefix = {
                if(DatrisEnvironment.values.kafkaConsumerConfig.topicPrefix != null && DatrisEnvironment.values.kafkaConsumerConfig.topicPrefix.nonEmpty)
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
