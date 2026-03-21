package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

case class QueueMessage(
    messageId: String,
    body: String,
    receiptHandle: String
)

case class QueueSendResult(
    messageId: String
)
