package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{QueueMessage, QueueSendResult}

trait QueueUtility {
    def add(queueName: String, json: String): QueueSendResult

    def addFifo(queueName: String, json: String): QueueSendResult

    def receiveMessages(queueName: String, maxMessages: Int = 1, longPolling: Boolean = false): java.util.List[QueueMessage]

    def deleteMessage(queueName: String, receiptHandle: String): Unit

    def getQueueArn(queueName: String): String
}
