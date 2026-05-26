package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{PipelineConfig, DatrisEnvironment}
import ai.datris.util.{PipelineConfigIO, ObjectStoreUtil}
import ai.datris.model.GlobalJobContext
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecords, KafkaConsumer}
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.{Logger, LoggerFactory}

import java.time.Duration
import java.util.{Properties, UUID}
import scala.collection.JavaConverters._
import scala.collection.mutable

class KafkaConsumerRunner(
                              bootstrapServers: String,
                              groupId: String
                          ) extends  Runnable {

    private val logger: Logger = LoggerFactory.getLogger(classOf[KafkaConsumerRunner])

    private val props: Properties = {
        val p = new Properties()
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
        p
    }

    private val consumer = new KafkaConsumer[String, String](props)
    private val topics: mutable.Set[String] = mutable.Set.empty

    def addTopics(newTopics: Seq[String]): Unit = synchronized {
        topics ++= newTopics
        resubscribe()
        logger.info(s"[+] Kafka added topics: ${newTopics.mkString(", ")} | Active: ${topics.mkString(", ")}")
    }

    private def resubscribe(): Unit = {
        if (topics.nonEmpty) consumer.subscribe(topics.asJava)
        else consumer.unsubscribe()
        consumer.wakeup()
    }

    def run(): Unit = {
        logger.info("Kafka consumer started")

        while(true) {
            try {
                if (topics.nonEmpty) {
                    val records: ConsumerRecords[String, String] = consumer.poll(Duration.ofMillis(1000))
                    for (record <- records.asScala) {
                        handler(record.topic(), record.key(), record.value())
                    }
                } else {
                    Thread.sleep(500)
                }
            } catch {
                case _: org.apache.kafka.common.errors.WakeupException => // expected on resubscribe
            }
        }
    }

    private def handler(topic: String, key: String, value: String): Unit = {
        logger.info(s"[$topic] message received")

        // Determine the pipeline name and read the configuration
        val pipeline = {
            val prefix = Option(DatrisEnvironment.current)
                .map(_.kafkaConsumerConfig)
                .map(_.topicPrefix)
                .filter(_.nonEmpty)
            prefix match {
                case Some(p) => topic.stripPrefix(p).stripPrefix(".")
                case None => topic
            }
        }
        val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)

        if(config == null)
            logger.error("Pipeline: " + pipeline + " is not configured in the NoSQL database")
        else
            processData(config, value)
    }

    private def processData(config: PipelineConfig, data: String): Unit = {
        // If the incoming data is JSON or XML, process directly
        if(config.source.fileAttributes.jsonAttributes != null || config.source.fileAttributes.xmlAttributes != null) {
            // Start job
            val jobContext = new StreamNotifier().process(config, data)
            GlobalJobContext.addJobContext(jobContext)
        }
        else {
            // Write data to a unique path in the -temp bucket
            val tempLocation = "s3://" + DatrisEnvironment.current.environment + "-temp/kafka/" + UUID.randomUUID().toString + "/"
            val tempFilename = config.name + "." +  UUID.randomUUID().toString + ".tmp"
            val tempUrl = tempLocation + tempFilename
            ObjectStoreUtil.writeBucketObject(ObjectStoreUtil.getBucket(tempUrl), ObjectStoreUtil.getKey(tempUrl), data)

            // Start job
            val jobContext = new FileNotifier().process(ObjectStoreUtil.getBucket(tempUrl), ObjectStoreUtil.getKey(tempUrl))
            GlobalJobContext.addJobContext(jobContext)
        }
    }
}