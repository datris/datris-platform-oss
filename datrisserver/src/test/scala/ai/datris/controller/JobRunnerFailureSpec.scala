package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.StatusUtil
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** JobRunner's terminal status events on failure. A loader failure must
  * surface under the loader's name with a destination-facing message; the
  * JobRunner-level event then carries the stack trace at info level so the
  * rollup does not see a second, misleading error event. */
class JobRunnerFailureSpec extends AnyFunSuite {

    /** Captures events instead of writing to Mongo. (processName, state, code, description) */
    private class CapturingStatusUtil extends StatusUtil {
        val events = ListBuffer[(String, String, String, String)]()
        // Mirrors the real StatusUtil: a shared, last-writer-wins process name.
        var current = "SomeLoader"
        override def overrideProcessName(processName: String): Unit = current = processName
        override def info(state: String, description: String): Unit = events += ((current, state, "info", description))
        override def error(state: String, description: String): Unit = events += ((current, state, "error", description))
        override def errorAs(processName: String, state: String, description: String): Unit =
            events += ((processName, state, "error", description))
    }

    test("loaderErrorMessage is the exception message, never the class name when a message exists") {
        assert(JobRunner.loaderErrorMessage(new RuntimeException("connection refused")) == "connection refused")
        assert(JobRunner.loaderErrorMessage(new RuntimeException("  padded  ")) == "padded")
    }

    test("loaderErrorMessage falls back to the class name when the message is missing or blank") {
        assert(JobRunner.loaderErrorMessage(new IllegalStateException()) == "java.lang.IllegalStateException")
        assert(JobRunner.loaderErrorMessage(new IllegalStateException("   ")) == "java.lang.IllegalStateException")
    }

    test("loader failure: JobRunner writes an info end event with the stack, not a second error event") {
        val su = new CapturingStatusUtil
        val cause = new RuntimeException("relation \"orders\" does not exist")
        val wrapped = new RuntimeException("PostgresLoader failed: java.lang.RuntimeException: " + cause.getMessage, cause)

        val message = JobRunner.reportFailure(su, Some(("PostgresLoader", cause)), wrapped)

        assert(message == "PostgresLoader failed: relation \"orders\" does not exist")
        assert(su.events.size == 1)
        val (process, state, code, description) = su.events.head
        assert(process == "JobRunner", "terminal event is JobRunner's even though a loader last set the shared process name")
        assert(state == "end")
        assert(code == "info", "a JobRunner error event would shadow the loader's own error in the rollup")
        assert(description.startsWith("Process completed, error: PostgresLoader failed: relation \"orders\" does not exist"))
        assert(description.contains("java.lang.RuntimeException"), "stack trace stays on the event stream for the detail view")
    }

    test("job-thread failure (no loader): today's JobRunner error event with the stack is preserved") {
        val su = new CapturingStatusUtil
        val e = new IllegalStateException("schema mismatch")

        val message = JobRunner.reportFailure(su, None, e)

        assert(su.events.size == 1)
        val (process, state, code, description) = su.events.head
        // Job-thread failures keep the pre-existing attribution (the stage that
        // last set the process name, e.g. DataQuality) — unchanged behaviour.
        assert(process == "SomeLoader" && state == "end" && code == "error")
        assert(description.contains("IllegalStateException: schema mismatch"))
        assert(message.contains("IllegalStateException: schema mismatch"))
    }
}
