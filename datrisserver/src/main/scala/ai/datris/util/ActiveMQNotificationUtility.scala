package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{NotificationPublishResult, DatrisEnvironment}
import org.apache.activemq.ActiveMQConnectionFactory
import org.slf4j.LoggerFactory

import javax.jms._

class ActiveMQNotificationUtility(val connectionFactory: ActiveMQConnectionFactory) extends NotificationUtility {
    private val logger = LoggerFactory.getLogger(classOf[ActiveMQNotificationUtility])

    override def add(topicName: String, json: String): NotificationPublishResult = {
        publishMessage(topicName, json, filter = Map.empty, fifo = false)
    }

    override def add(topicName: String, json: String, filter: Map[String, String]): NotificationPublishResult = {
        publishMessage(topicName, json, filter, fifo = false)
    }

    override def addFifo(topicName: String, json: String, filter: Map[String, String]): NotificationPublishResult = {
        publishMessage(topicName, json, filter, fifo = true)
    }

    private def publishMessage(topicName: String, json: String, filter: Map[String, String], fifo: Boolean): NotificationPublishResult = {
        var connection: Connection = null
        var session: Session = null
        try {
            connection = connectionFactory.createConnection()
            connection.start()
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)

            val topic = session.createTopic(topicName)
            val producer = session.createProducer(topic)
            producer.setDeliveryMode(DeliveryMode.PERSISTENT)

            val message = session.createTextMessage(json)
            message.setStringProperty("Content-Type", "application/json")

            // Set filter properties
            filter.foreach { case (name, value) =>
                message.setStringProperty(name, value)
            }

            // Set group ID for FIFO ordering
            if (fifo) {
                message.setStringProperty("JMSXGroupID", "pipeline-message-group")
            }

            producer.send(message)
            val messageId = message.getJMSMessageID

            logger.info(s"Published message via JMS to topic '$topicName', messageId: $messageId, filter: $filter, fifo: $fifo")
            NotificationPublishResult(messageId = messageId)
        } finally {
            if (session != null) session.close()
            if (connection != null) connection.close()
        }
    }
}

object ActiveMQNotificationUtilBuilder {
    def build(): NotificationUtility = {
        val connectionFactory = new ActiveMQConnectionFactory()
        connectionFactory.setBrokerURL(DatrisEnvironment.current.activeMQConfig.server)
        connectionFactory.setUserName(DatrisEnvironment.current.activeMQConfig.username)
        connectionFactory.setPassword(DatrisEnvironment.current.activeMQConfig.password)

        connectionFactory.setTrustAllPackages(false)
        connectionFactory.setTrustedPackages(java.util.Arrays.asList("ai.datris"))

        new ActiveMQNotificationUtility(connectionFactory)
    }
}
