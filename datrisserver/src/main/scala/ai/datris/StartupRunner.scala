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

    @Value("${mongodb.database}")
    var mongoDbDatabase: String = _

    @Value("${ai.enabled:false}")
    var aiEnabled: Boolean = _

    @Value("${ai.provider:anthropic}")
    var aiProvider: String = _

    @Value("${ai.aiSecretName:}")
    var aiSecretName: String = _

    @Value("${secrets.embeddingSecretName:}")
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
            mongoDbDatabase
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
            dateTimezone = dateTimezone
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
        // AI configuration is required — CodeGen data quality and transformation depend on it
        if (!aiEnabled)
            throw new DatrisException("AI is required but not enabled. Set 'ai.enabled: true' in application.yaml")
        if (aiSecretName == null || aiSecretName.isEmpty)
            throw new DatrisException("AI is enabled but no secret is configured. Set 'ai.aiSecretName' in application.yaml (e.g., 'oss/ai')")
        if (!Seq("anthropic", "openai", "ollama").contains(aiProvider.toLowerCase))
            throw new DatrisException("Unsupported AI provider: '" + aiProvider + "'. Valid values are: anthropic, openai, ollama")

        val aiConfig = {
            val secret = SecretsUtil.getSecretMap(aiSecretName)
                .getOrElse(throw new DatrisException("AI secret not found in Vault, secret name: " + aiSecretName + ". Create it with: vault kv put secret/" + aiSecretName + " endpoint=<url> model=<model> apiKey=<key>"))
            val endpoint = secret.get("endpoint")
            if (endpoint == null)
                throw new DatrisException("'endpoint' not found in AI secret: " + aiSecretName)
            val model = secret.get("model")
            if (model == null)
                throw new DatrisException("'model' not found in AI secret: " + aiSecretName)
            val apiKey = Option(secret.get("apiKey")).getOrElse("")
            logger.info("AI provider configured: " + aiProvider + ", model: " + model + ", endpoint: " + endpoint)
            AIConfig(aiProvider, endpoint, model, apiKey)
        }
        DatrisEnvironment.init(DatrisEnvironment.values.copy(initialized = true, pipelineTopic = pipelineTopic, aiConfig = aiConfig, aiEnabled = aiEnabled))
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
