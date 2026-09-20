package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.Gson
import ai.datris.model.{Notification, DatrisEnvironment, DatrisException}
import ai.datris.model._

import scala.collection.JavaConverters._

class ActiveMQLoader(jobContext: JobContext) {
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)

        val activeMQConfig = config.destination.activeMQ
        statusUtil.info("begin", "Sending data to ActiveMQ queue: " + activeMQConfig.queueName)

        val recordsSent = sendRecords(activeMQConfig.queueName)
        statusUtil.info("processing", "Records sent to ActiveMQ queue: " + recordsSent.toString)

        sendNotification()
        statusUtil.info("end", "Process completed")
    }

    private def sendRecords(queueName: String): Long = {
        val data = jobContext.data
        val staged = data.staged

        // Dispatch on the staged format: delimited rows stream through
        // rowIterator(); JSON / XML / text still go out as one message built
        // from the (materialize-gated) rawData accessor.
        if (staged == null || staged.isEmpty)
            throw new DatrisException("No data available to send to ActiveMQ")
        staged.format match {
            case StagedFormat.Delimited(_) if data.header != null =>
                sendStructuredData(queueName, data)
            case StagedFormat.NdJson | StagedFormat.Xml | StagedFormat.Text =>
                data.materializeFor("ActiveMQ destination single-message mode (JSON/XML/text payload)")
                val rawData = data.rawData
                if (rawData != null && rawData.trim.nonEmpty) sendRawData(queueName, rawData)
                else throw new DatrisException("No data available to send to ActiveMQ")
            case _ =>
                throw new DatrisException("No data available to send to ActiveMQ")
        }
    }

    private def sendStructuredData(queueName: String, data: Data): Long = {
        val header = data.header

        val delimiter = {
            if (
                config.source != null
                && config.source.fileAttributes != null
                && config.source.fileAttributes.csvAttributes != null
                && config.source.fileAttributes.csvAttributes.delimiter != null
            )
                config.source.fileAttributes.csvAttributes.delimiter
            else
                ","
        }

        var count: Long = 0
        val gson = new Gson()

        val rows = data.rowIterator()
        try
            rows.foreach { row =>
                val fields = row.split(delimiter, -1)
                val jsonMap = new java.util.LinkedHashMap[String, String]()
                header.indices.foreach { i =>
                    val value = if (i < fields.length) fields(i) else ""
                    jsonMap.put(header(i), value)
                }

                val json = gson.toJson(jsonMap)
                QueueUtil.add(queueName, json)
                count += 1
            }
        finally rows.close()

        count
    }

    private def sendRawData(queueName: String, rawData: String): Long = {
        QueueUtil.add(queueName, rawData)
        1
    }

    private def sendNotification(): Unit = {
        val activeMQConfig = config.destination.activeMQ
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "activemq",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            activeMQConfig.queueName
        )
        val gson = new Gson
        val jsonNotification = gson.toJson(notification)

        val attributes = new java.util.HashMap[String, String]
        attributes.put("pipeline", config.name)
        attributes.put("destination", "activemq")
        attributes.put("queueName", activeMQConfig.queueName)

        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}
