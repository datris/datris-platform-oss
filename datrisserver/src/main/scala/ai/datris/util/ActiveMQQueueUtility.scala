package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisEnvironment, QueueMessage, QueueSendResult}
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.pool.PooledConnectionFactory
import javax.jms.ConnectionFactory
import org.slf4j.LoggerFactory

import javax.jms._
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

class ActiveMQQueueUtility(val connectionFactory: ConnectionFactory) extends QueueUtility {
    private val logger = LoggerFactory.getLogger(classOf[ActiveMQQueueUtility])

    override def add(queueName: String, json: String): QueueSendResult = {
        sendMessage(queueName, json, fifo = false)
    }

    override def addFifo(queueName: String, json: String): QueueSendResult = {
        // ActiveMQ preserves message ordering per queue by default,
        // but we set JMSXGroupID for explicit group ordering
        sendMessage(queueName, json, fifo = true)
    }

    override def receiveMessages(queueName: String, maxMessages: Int = 1, longPolling: Boolean = false): java.util.List[QueueMessage] = {
        val connection = connectionFactory.createConnection()
        try {
            connection.start()
            val session = connection.createSession(false, org.apache.activemq.ActiveMQSession.INDIVIDUAL_ACKNOWLEDGE)
            val destination = session.createQueue(queueName)
            val consumer = session.createConsumer(destination)

            val timeoutMs = if (longPolling) 3000L else 100L
            val messages = ListBuffer[QueueMessage]()

            var count = 0
            var continue = true
            while (count < maxMessages && continue) {
                val msg = consumer.receive(timeoutMs)
                if (msg != null) {
                    msg match {
                        case textMsg: TextMessage =>
                            messages += QueueMessage(
                                messageId = textMsg.getJMSMessageID,
                                body = textMsg.getText,
                                receiptHandle = textMsg.getJMSMessageID
                            )
                        case _ =>
                            logger.warn(s"Received non-text message of type: ${msg.getClass.getName}, skipping")
                    }
                    msg.acknowledge()
                    count += 1
                } else {
                    continue = false
                }
            }

            consumer.close()
            session.close()
            messages.asJava
        } finally {
            connection.close()
        }
    }

    override def deleteMessage(queueName: String, receiptHandle: String): Unit = {
        // In JMS, messages are acknowledged rather than deleted.
        // With CLIENT_ACKNOWLEDGE mode, messages are acknowledged at receive time.
        // For ActiveMQ, you can use message selectors or advisory topics for
        // more granular control. In this implementation, messages are consumed
        // (removed from queue) upon receive. This is a no-op for compatibility.
        logger.debug(s"deleteMessage called for queue: $queueName, receiptHandle: $receiptHandle (no-op in ActiveMQ - messages consumed on receive)")
    }

    override def getQueueArn(queueName: String): String = {
        // ActiveMQ doesn't have ARNs. Return a synthetic identifier for compatibility.
        s"activemq:queue:$queueName"
    }

    private def sendMessage(queueName: String, json: String, fifo: Boolean): QueueSendResult = {
        val connection = connectionFactory.createConnection()
        try {
            connection.start()
            val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
            val destination = session.createQueue(queueName)
            val producer = session.createProducer(destination)
            producer.setDeliveryMode(DeliveryMode.PERSISTENT)

            val message = session.createTextMessage(json)

            if (fifo) {
                message.setStringProperty("JMSXGroupID", "pipeline-message-group")
            }

            producer.send(message)
            val messageId = message.getJMSMessageID

            producer.close()
            session.close()

            QueueSendResult(messageId = messageId)
        } finally {
            connection.close()
        }
    }
}

object ActiveMQUtilBuilder {
    def build(): QueueUtility = {
        val connectionFactory = new ActiveMQConnectionFactory()
        connectionFactory.setBrokerURL(DatrisEnvironment.values.activeMQConfig.server)
        connectionFactory.setUserName(DatrisEnvironment.values.activeMQConfig.username)
        connectionFactory.setPassword(DatrisEnvironment.values.activeMQConfig.password)

        connectionFactory.setTrustAllPackages(false)
        connectionFactory.setTrustedPackages(java.util.Arrays.asList("ai.datris"))

        val pooledFactory = new PooledConnectionFactory()
        pooledFactory.setConnectionFactory(connectionFactory)
        pooledFactory.setMaxConnections(5)
        pooledFactory.setIdleTimeout(30000)

        new ActiveMQQueueUtility(pooledFactory)
    }
}