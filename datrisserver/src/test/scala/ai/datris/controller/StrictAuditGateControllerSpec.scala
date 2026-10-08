package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.StrictAuditGate
import jakarta.servlet.http.HttpServletRequest
import org.apache.kafka.clients.consumer.{ConsumerRecord, ConsumerRecords, MockConsumer, OffsetResetStrategy}
import org.apache.kafka.common.TopicPartition
import org.mockito.Mockito.mock
import org.scalatest.funsuite.AnyFunSuite
import org.slf4j.LoggerFactory

import java.time.Duration

import scala.collection.JavaConverters._

/** Strict evidence mode (plans/stories/strict-evidence-mode.md): ingest
  * triggers that never pass the HTTP audit gate (the MinIO webhook, the Kafka
  * consumer) refuse or pause while the audit log is not accepting entries. */
class StrictAuditGateControllerSpec extends AnyFunSuite {

    test("the MinIO webhook answers 503 and queues nothing while the strict audit gate is closed") {
        assume(sys.env.getOrElse("MINIO_WEBHOOK_TOKEN", "").isEmpty, "token configured in this environment")
        val gate = new StrictAuditGate
        gate.close()
        try {
            val payload = """{"Records":[{"s3":{"bucket":{"name":"b"},"object":{"key":"k"}}}]}"""
            // fileNotifierQueue is null in the gate's environment: reaching
            // QueueUtil.add would throw, so a 503 here means nothing was queued.
            val resp = new MinioWebhookController().handleMinioEvent(payload, mock(classOf[HttpServletRequest]))
            assert(resp.getStatusCode.value() == 503, resp.toString)
            assert(resp.getHeaders.getFirst("Retry-After") != null)
            assert(resp.getBody.contains("AUDIT_LOG_STRICT"), resp.getBody)
        } finally gate.reopen()
    }

    private def rec(tp: TopicPartition, offset: Long) = new ConsumerRecord[String, String](tp.topic(), tp.partition(), offset, "k", "v")

    test("a Kafka batch interrupted by the gate rewinds each partition to its first unhandled record") {
        val p0 = new TopicPartition("t", 0)
        val p1 = new TopicPartition("t", 1)
        val p2 = new TopicPartition("t", 2)
        val records = new ConsumerRecords[String, String](
            Map(
                p0 -> List(rec(p0, 10), rec(p0, 11), rec(p0, 12)).asJava,
                p1 -> List(rec(p1, 5), rec(p1, 6)).asJava,
                p2 -> List(rec(p2, 7)).asJava
            ).asJava
        )
        // p0 handled through 11, p1 fully handled, p2 not reached.
        val rewind = KafkaConsumerRunner.rewindOffsets(records, Map(p0 -> 11L, p1 -> 6L))
        assert(rewind == Map(p0 -> 12L, p2 -> 7L))
    }

    test("the Kafka consumer pauses and keeps polling while the gate is closed, then resumes") {
        val tp = new TopicPartition("t", 0)
        val consumer = new MockConsumer[String, String](OffsetResetStrategy.EARLIEST)
        consumer.assign(List(tp).asJava)
        consumer.updateBeginningOffsets(Map(tp -> java.lang.Long.valueOf(0L)).asJava)
        @volatile var open = false
        val handled = scala.collection.mutable.ArrayBuffer[Long]()
        val poller = new KafkaConsumerRunner.GatedPoller(
            consumer,
            () => open,
            (r: ConsumerRecord[String, String]) => handled += r.offset(),
            LoggerFactory.getLogger(getClass)
        )

        consumer.addRecord(rec(tp, 0))
        consumer.addRecord(rec(tp, 1))
        poller.pollOnce(Duration.ofMillis(10))
        assert(consumer.paused().asScala == Set(tp), "assigned partitions are paused")
        assert(handled.isEmpty, "nothing handled while the gate is closed")
        assert(consumer.position(tp) == 0L, "the position did not move")
        poller.pollOnce(Duration.ofMillis(10)) // still polling while closed (keeps group membership)
        assert(consumer.paused().asScala == Set(tp))

        open = true
        consumer.addRecord(rec(tp, 0))
        consumer.addRecord(rec(tp, 1))
        poller.pollOnce(Duration.ofMillis(10))
        assert(consumer.paused().isEmpty, "resumed once the gate reopened")
        assert(handled.toList == List(0L, 1L))
        assert(consumer.position(tp) == 2L)
    }

    test("the Kafka consumer rewinds to the first unhandled record when the gate closes mid-batch") {
        val tp = new TopicPartition("t", 0)
        val consumer = new MockConsumer[String, String](OffsetResetStrategy.EARLIEST)
        consumer.assign(List(tp).asJava)
        consumer.updateBeginningOffsets(Map(tp -> java.lang.Long.valueOf(0L)).asJava)
        @volatile var open = true
        val handled = scala.collection.mutable.ArrayBuffer[Long]()
        val poller = new KafkaConsumerRunner.GatedPoller(
            consumer,
            () => open,
            (r: ConsumerRecord[String, String]) => { handled += r.offset(); if (r.offset() == 1L) open = false },
            LoggerFactory.getLogger(getClass)
        )
        (0 until 4).foreach(i => consumer.addRecord(rec(tp, i.toLong)))
        poller.pollOnce(Duration.ofMillis(10))
        assert(handled.toList == List(0L, 1L))
        assert(consumer.position(tp) == 2L, "auto-commit would commit offset 2, the first record not ingested")
    }
}
