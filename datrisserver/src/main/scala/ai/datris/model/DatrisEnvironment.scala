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
                                  aiEnabled: Boolean
                              )
