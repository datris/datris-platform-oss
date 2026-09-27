package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, PipelineConfig, PipelineMetadata}
import ai.datris.util.StatusUtil
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** FileNotifier.process error handling. A key that cannot be attributed to a
  * pipeline must surface its own parse error, never the unrelated
  * "pipelineToken ... was not found in the NoSQL table" error StatusUtil
  * raises when no archived-metadata row exists. Failures after the metadata
  * is archived keep writing the end/error status event. */
class FileNotifierErrorSpec extends AnyFunSuite {

    /** Captures events instead of writing to Mongo. (state, code, description) */
    private class CapturingStatusUtil extends StatusUtil {
        val events = ListBuffer[(String, String, String)]()
        override def info(state: String, description: String): Unit = events += ((state, "info", description))
        override def warn(state: String, description: String): Unit = events += ((state, "warning", description))
        override def error(state: String, description: String): Unit = events += ((state, "error", description))
        override def errorAs(processName: String, state: String, description: String): Unit =
            events += ((state, "error", description))
    }

    private val archiveMustNotBeCalled: (String, PipelineMetadata) => Unit =
        (_, _) => fail("archiveMetadata must not be called for a key that cannot be parsed")

    private val readConfigMustNotBeCalled: String => PipelineConfig =
        _ => fail("readConfig must not be called for a key that cannot be parsed")

    test("single-token key: rethrows the naming-convention DatrisException, writes no status event") {
        for (key <- Seq("noprefixsingletoken", "some/prefix/onetoken")) {
            val su = new CapturingStatusUtil
            val notifier = new FileNotifier(su, archiveMustNotBeCalled, readConfigMustNotBeCalled)

            val e = intercept[DatrisException](notifier.process("oss-raw", key))

            assert(
                e.getMessage.contains("Could not parse the pipeline and/or filename"),
                s"key=$key: expected the naming-convention error, got: ${e.getMessage}"
            )
            assert(!e.getMessage.contains("was not found in the NoSQL table"), s"key=$key: masked by the pipelineToken lookup error")
            assert(!e.getMessage.startsWith("FileNotifier error:"), s"key=$key: expected the original exception, not a wrapper")
            assert(su.events.isEmpty, s"key=$key: no status event may be written before the key is attributed, got ${su.events}")
        }
    }

    test("post-parse failure still writes the end/error status event and rethrows the original") {
        val su = new CapturingStatusUtil
        val archived = ListBuffer[(String, PipelineMetadata)]()
        val notifier = new FileNotifier(su, (token: String, md: PipelineMetadata) => archived += ((token, md)), (_: String) => null)

        val e = intercept[DatrisException](notifier.process("oss-raw", "orders.tok.x.pipeline.csv"))

        assert(e.getMessage.contains("Pipeline: orders is not configured"), s"got: ${e.getMessage}")
        assert(!e.getMessage.startsWith("FileNotifier error:"), "expected the original exception, not a wrapper")
        assert(archived.size == 1, "metadata is archived before the config lookup")

        val begins = su.events.filter { case (state, code, _) => state == "begin" && code == "info" }
        assert(begins.size == 1, s"events: ${su.events}")
        assert(begins.head._3.contains("Data received"))

        val errors = su.events.filter { case (_, code, _) => code == "error" }
        assert(errors.size == 1, s"exactly one error event expected, got ${su.events}")
        val (state, _, description) = errors.head
        assert(state == "end")
        assert(description.contains("Process completed, error: "))
        assert(description.contains("is not configured"))
        assert(su.events.indexOf(begins.head) < su.events.indexOf(errors.head), "begin precedes the end/error event")
    }

    test("archive write failure is reported as itself") {
        val su = new CapturingStatusUtil
        val boom = new RuntimeException("mongo down")
        val notifier =
            new FileNotifier(su, (_: String, _: PipelineMetadata) => throw boom, readConfigMustNotBeCalled)

        val e = intercept[Exception](notifier.process("oss-raw", "orders.tok.x.pipeline.csv"))

        assert(e eq boom, s"expected the original archive exception, got: $e")
        assert(!su.events.exists(_._2 == "error"), s"no error event may be written before metadata is archived, got ${su.events}")
    }
}
