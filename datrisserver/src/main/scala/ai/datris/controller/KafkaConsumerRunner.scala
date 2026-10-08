package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineConfig, DatrisEnvironment}
import ai.datris.util.{PipelineConfigIO, ObjectStoreUtil}
import ai.datris.model.GlobalJobContext
import org.apache.kafka.clients.consumer.{Consumer, ConsumerConfig, ConsumerRecord, ConsumerRecords, KafkaConsumer}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.{Logger, LoggerFactory}

import java.time.Duration
import java.util.{Properties, UUID}
import scala.collection.JavaConverters._
import scala.collection.mutable

class KafkaConsumerRunner(
    bootstrapServers: String,
    groupId: String
) extends Runnable {

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

    // Strict evidence mode: consumption pauses while the audit log is not
    // accepting entries (see GatedPoller).
    private val poller = new KafkaConsumerRunner.GatedPoller(
        consumer,
        () => ai.datris.audit.AuditLog.acceptingWrites,
        (r: ConsumerRecord[String, String]) => handler(r.topic(), r.key(), r.value()),
        logger
    )

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

    @volatile private var running = true
    @volatile private var loopStarted = false
    private val exited = new java.util.concurrent.CountDownLatch(1)

    /** Stop consuming (server shutdown): the loop finishes the record in hand,
      * closes the consumer (auto-commit commits only records already handled,
      * whose audit entries are queued) and exits. Waits up to `timeoutMs`;
      * true when the loop has ended (or never started). */
    def stop(timeoutMs: Long): Boolean = {
        running = false
        try consumer.wakeup()
        catch { case _: Exception => }
        !loopStarted || exited.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    def run(): Unit = {
        logger.info("Kafka consumer started")
        loopStarted = true
        KafkaConsumerRunner.active = this
        try {
            while (running) {
                try {
                    if (topics.nonEmpty) {
                        poller.pollOnce(Duration.ofMillis(1000))
                    } else {
                        Thread.sleep(500)
                    }
                } catch {
                    case _: org.apache.kafka.common.errors.WakeupException => // expected on resubscribe and stop
                    case _: InterruptedException if !running =>
                }
            }
            logger.info("Kafka consumer stopped")
        } finally {
            try consumer.close(Duration.ofSeconds(5))
            catch { case e: Exception => logger.warn("Kafka consumer close: " + e.getMessage) }
            exited.countDown()
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

        if (config == null)
            logger.error("Pipeline: " + pipeline + " is not configured in the NoSQL database")
        else {
            val md = new com.google.gson.JsonObject()
            md.addProperty("trigger", "kafka")
            md.addProperty("topic", topic)
            // Collapsed by the audit writer: one record per pipeline per minute with a count.
            ai.datris.audit.AuditLog.system("pipeline", "ingest", "pipeline", config.name, md)
            processData(config, value)
        }
    }

    private def processData(config: PipelineConfig, data: String): Unit = {
        // If the incoming data is JSON or XML, process directly
        if (config.source.fileAttributes.jsonAttributes != null || config.source.fileAttributes.xmlAttributes != null) {
            // Start job
            val jobContext = new StreamNotifier().process(config, data)
            GlobalJobContext.addJobContext(jobContext)
        } else {
            // Write data to a unique path in the -temp bucket
            val tempLocation = "s3://" + DatrisEnvironment.current.environment + "-temp/kafka/" + UUID.randomUUID().toString + "/"
            val tempFilename = config.name + "." + UUID.randomUUID().toString + ".tmp"
            val tempUrl = tempLocation + tempFilename
            ObjectStoreUtil.writeBucketObject(ObjectStoreUtil.getBucket(tempUrl), ObjectStoreUtil.getKey(tempUrl), data)

            // Start job
            val jobContext = new FileNotifier().process(ObjectStoreUtil.getBucket(tempUrl), ObjectStoreUtil.getKey(tempUrl))
            GlobalJobContext.addJobContext(jobContext)
        }
    }
}

object KafkaConsumerRunner {

    /** The running consumer, for [[KafkaConsumerLifecycle]]. */
    @volatile private[datris] var active: KafkaConsumerRunner = null

    /** Server shutdown: stop the running consumer, if any. */
    def stopActive(timeoutMs: Long): Unit = {
        val r = active
        if (r != null && !r.stop(timeoutMs))
            LoggerFactory.getLogger(classOf[KafkaConsumerRunner]).warn("Kafka consumer did not stop within " + timeoutMs + "ms")
    }

    /** One poll of the consumer, gated by AUDIT_LOG_STRICT (`accepting` is
      * AuditLog.acceptingWrites, always true in default mode).
      *
      * Gate closed: the assigned partitions are paused and the consumer keeps
      * polling (empty results), so it stays in its group and auto-commit keeps
      * committing the current position; anything a rebalance hands back
      * before it is paused is rewound, not handled. Gate open again: the
      * paused partitions resume. If the gate closes partway through a batch,
      * every partition is rewound to its first unhandled record, so
      * auto-commit never passes a message that was not ingested. */
    private[controller] class GatedPoller(
        consumer: Consumer[String, String],
        accepting: () => Boolean,
        handle: ConsumerRecord[String, String] => Unit,
        logger: Logger
    ) {
        private var gateClosed = false

        def pollOnce(timeout: Duration): Unit = {
            if (!accepting()) {
                if (!gateClosed) {
                    logger.warn("Kafka consumer paused: audit log not accepting entries (AUDIT_LOG_STRICT)")
                    gateClosed = true
                }
                consumer.pause(consumer.assignment())
                val stray = consumer.poll(timeout)
                if (!stray.isEmpty) rewindOffsets(stray, Map.empty).foreach { case (tp, off) => consumer.seek(tp, off) }
                return
            }
            if (gateClosed) {
                consumer.resume(consumer.paused())
                logger.info("Kafka consumer resumed: audit log accepting entries again")
                gateClosed = false
            }
            val records = consumer.poll(timeout)
            val processed = mutable.Map[TopicPartition, Long]()
            val it = records.asScala.iterator
            var stop = false
            while (!stop && it.hasNext) {
                val record = it.next()
                if (!accepting()) {
                    rewindOffsets(records, processed.toMap).foreach { case (tp, off) => consumer.seek(tp, off) }
                    stop = true
                } else {
                    handle(record)
                    processed(new TopicPartition(record.topic(), record.partition())) = record.offset()
                }
            }
        }
    }

    /** For a batch interrupted by the strict audit gate: the offset to seek
      * each partition back to (its first record not yet handled). Partitions
      * fully handled are absent. */
    private[controller] def rewindOffsets(records: ConsumerRecords[String, String], processed: Map[TopicPartition, Long]): Map[TopicPartition, Long] =
        records.partitions().asScala.toList.flatMap { tp =>
            val recs = records.records(tp).asScala
            val next = processed.get(tp) match {
                case Some(last) => recs.find(_.offset() > last)
                case None => recs.headOption
            }
            next.map(r => tp -> r.offset())
        }.toMap
}
