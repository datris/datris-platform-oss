package ai.datris

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.QueueMessage
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

class FileNotifierBatchSpec extends AnyFunSuite {

    private def minioEvent(bucket: String, rawKey: String): String =
        s"""{"Records":[{"eventVersion":"2.0","eventSource":"minio:s3","eventName":"s3:ObjectCreated:Put",""" +
            s""""s3":{"s3SchemaVersion":"1.0","configurationId":"Config","bucket":{"name":"$bucket","arn":"arn:aws:s3:::$bucket"},""" +
            s""""object":{"key":"$rawKey","size":10,"eTag":"e","sequencer":"1"}}}]}"""

    test("dispatch: a failing first record does not stop records 2 and 3, returns 1, and does not throw") {
        val records = Seq(("oss-raw", "bad.a.pipeline.csv"), ("oss-raw", "good.b.pipeline.csv"), ("oss-raw", "good.c.pipeline.csv"))
        val called = ListBuffer[(String, String)]()
        val failures = ScheduledBatchTasks.dispatch(
            records,
            (bucket: String, key: String) => {
                called += ((bucket, key))
                if (key.startsWith("bad")) throw new RuntimeException("Invalid CSV header line, parsed 0")
            }
        )
        assert(failures == 1)
        assert(called.toList == records.toList)
    }

    test("dispatch: all records succeeding returns 0") {
        val records = Seq(("oss-raw", "a.x.pipeline.csv"), ("oss-raw", "b.x.pipeline.csv"))
        val called = ListBuffer[(String, String)]()
        val failures = ScheduledBatchTasks.dispatch(records, (b: String, k: String) => { called += ((b, k)); () })
        assert(failures == 0)
        assert(called.size == 2)
    }

    test("recordsOf: URL-encoded keys are decoded") {
        val msg = QueueMessage("m1", minioEvent("oss-raw", "a%2Fb.pipeline.csv"), "r1")
        assert(ScheduledBatchTasks.recordsOf(msg) == Seq(("oss-raw", "a/b.pipeline.csv")))
    }

    test("recordsOf: a null Records array returns empty") {
        val msg = QueueMessage("m2", """{"Records":null}""", "r2")
        assert(ScheduledBatchTasks.recordsOf(msg).isEmpty)
    }
}
