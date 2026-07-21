package ai.datris.model

case class KafkaConsumerConfig(
    enabled: Boolean,
    bootstrapServers: String,
    groupId: String,
    pollingInterval: Int,
    topicPrefix: String
)
