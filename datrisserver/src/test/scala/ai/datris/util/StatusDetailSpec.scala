package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.PipelineStatus
import com.google.gson.Gson
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** plans/stories/run-status-message-not-stacktrace.md, Resolved details (a):
  * the optional `detail` field on a status event. */
class StatusDetailSpec extends AnyFunSuite {

    private def event(detail: String) =
        PipelineStatus(0, "t", "orders", "JobRunner", "", "tok", "f.csv", "end", "error", "Process completed, error: boom", 1L, detail = detail)

    test("the stored event JSON omits detail when it is null and carries it when set") {
        val gson = new Gson
        assert(!gson.toJson(event(null)).contains("\"detail\""))
        val json = gson.toJson(event("java.lang.RuntimeException: boom\n\tat x.y(Z.scala:1)"))
        assert(json.contains("\"detail\":\"java.lang.RuntimeException: boom"))
        assert(gson.fromJson(json, classOf[PipelineStatus]).detail.startsWith("java.lang.RuntimeException: boom"))
        assert(gson.fromJson(gson.toJson(event(null)), classOf[PipelineStatus]).detail == null, "old events read back with no detail")
    }

    test("the 3-argument info/error reach subclasses that override only the 2-argument methods, with the detail") {
        val seen = ListBuffer[(String, String, String)]()
        val su = new StatusUtil {
            override def info(state: String, description: String): Unit = seen += ((description, "info", currentDetail))
            override def error(state: String, description: String): Unit = seen += ((description, "error", currentDetail))
        }
        su.error("end", "Process completed, error: boom", "TRACE")
        su.info("end", "Process completed, error: Loader failed: x", "TRACE2")
        su.error("end", "plain")
        assert(seen.toList == List(
            ("Process completed, error: boom", "error", "TRACE"),
            ("Process completed, error: Loader failed: x", "info", "TRACE2"),
            ("plain", "error", null)
        ))
    }
}
