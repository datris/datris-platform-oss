package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{Notification, DatrisEnvironment, DatrisException}
import ai.datris.model._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.{Logger, LoggerFactory}

import java.util.Properties
import scala.collection.JavaConverters._

object KafkaLoader {
    private val logger: Logger = LoggerFactory.getLogger(classOf[KafkaLoader])

    @volatile private var sharedProducer: KafkaProducer[String, String] = _
    private val lock = new Object()

    private def getOrCreateProducer(overrideBootstrapServers: String, timeoutMs: Int): KafkaProducer[String, String] = {
        if (sharedProducer == null) {
            lock.synchronized {
                if (sharedProducer == null) {
                    val props = new Properties()
                    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
                    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
                    props.put(ProducerConfig.ACKS_CONFIG, "all")
                    val timeout = (if (timeoutMs <= 0) 10000 else timeoutMs).toString
                    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeout)
                    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout)
                    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeout)

                    // Retrieve secrets for bootstrapServers and optional username/password
                    val secrets = SecretsRetrieverUtil.kafkaProducerSecrets()
                    val bootstrapServers = if (overrideBootstrapServers != null) overrideBootstrapServers else secrets.bootstrapServers
                    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)

                    if (secrets.username != null && secrets.password != null) {
                        props.put("sasl.mechanism", "PLAIN")
                        props.put("security.protocol", "SASL_PLAINTEXT")
                        props.put("sasl.jaas.config",
                            "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"" +
                                secrets.username + "\" password=\"" + secrets.password + "\";")
                    }

                    sharedProducer = new KafkaProducer[String, String](props)
                    logger.info("Kafka shared producer created, bootstrap servers: " + bootstrapServers)
                }
            }
        }
        sharedProducer
    }
}

class KafkaLoader(jobContext: JobContext) {
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)

        val kafkaConfig = config.destination.kafka
        statusUtil.info("begin", "Sending data to Kafka topic: " + kafkaConfig.topic)

        val producer = KafkaLoader.getOrCreateProducer(kafkaConfig.overrideBootstrapServers, kafkaConfig.timeoutMs)

        val recordsSent = sendRecords(producer, kafkaConfig.topic, kafkaConfig.keyField)
        statusUtil.info("processing", "Records sent to Kafka topic: " + recordsSent.toString)

        sendNotification()
        statusUtil.info("end", "Process completed")
    }

    private def sendRecords(producer: KafkaProducer[String, String], topic: String, keyField: String): Long = {
        val data = jobContext.data

        if (data.rawData != null && data.rawData.trim.nonEmpty) {
            // Send the entire raw data as a single JSON record
            sendRawData(producer, topic, keyField, data.rawData)
        } else if (data.header != null && data.rows != null) {
            // Send structured data (header + rows) as JSON records
            sendStructuredData(producer, topic, keyField, data)
        } else {
            throw new DatrisException("No data available to send to Kafka")
        }
    }

    private def sendStructuredData(producer: KafkaProducer[String, String], topic: String, keyField: String, data: Data): Long = {
        val header = data.header
        val keyIndex = if (keyField != null) header.indexWhere(_.equalsIgnoreCase(keyField)) else -1

        val delimiter = {
            if (config.source != null
                && config.source.fileAttributes != null
                && config.source.fileAttributes.csvAttributes != null
                && config.source.fileAttributes.csvAttributes.delimiter != null)
                config.source.fileAttributes.csvAttributes.delimiter
            else
                ","
        }

        var count: Long = 0
        val gson = new Gson()

        data.rows.foreach { row =>
            val fields = row.split(delimiter, -1)
            val jsonMap = new java.util.LinkedHashMap[String, String]()
            header.indices.foreach { i =>
                val value = if (i < fields.length) fields(i) else ""
                jsonMap.put(header(i), value)
            }

            val value = gson.toJson(jsonMap)
            val key = if (keyIndex >= 0 && keyIndex < fields.length) fields(keyIndex) else null

            val record = new ProducerRecord[String, String](topic, key, value)
            producer.send(record).get()
            count += 1
        }

        count
    }

    private def sendRawData(producer: KafkaProducer[String, String], topic: String, keyField: String, rawData: String): Long = {
        val key = {
            if (keyField != null) {
                try {
                    val parsed = com.google.gson.JsonParser.parseString(rawData)
                    if (parsed.isJsonObject && parsed.getAsJsonObject.has(keyField))
                        parsed.getAsJsonObject.get(keyField).getAsString
                    else
                        null
                } catch {
                    case _: Exception => null
                }
            } else null
        }

        val record = new ProducerRecord[String, String](topic, key, rawData)
        producer.send(record).get()
        1
    }

    private def sendNotification(): Unit = {
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "kafka",
            null,
            null,
            null,
            null,
            null,
            null,
            config.destination.kafka.topic
        )
        val gson = new Gson
        val jsonNotification = gson.toJson(notification)

        val attributes = new java.util.HashMap[String, String]
        attributes.put("pipeline", config.name)
        attributes.put("destination", "kafka")
        attributes.put("topic", config.destination.kafka.topic)

        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}
