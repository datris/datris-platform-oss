package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.audit.StrictAuditGate
import jakarta.servlet.http.HttpServletRequest
import org.apache.kafka.clients.consumer.{ConsumerRecord, ConsumerRecords}
import org.apache.kafka.common.TopicPartition
import org.mockito.Mockito.mock
import org.scalatest.funsuite.AnyFunSuite

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
}
