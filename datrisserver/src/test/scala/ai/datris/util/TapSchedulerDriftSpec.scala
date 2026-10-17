package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.incident.{Incident, IncidentRunner}
import ai.datris.model.TapConfig
import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._
import scala.collection.mutable

/** Story: Schema-drift signal (plans/stories/self-healing-4-signals.md),
  * Acceptance bullet 2 — the drift hook lives in TapScheduler's cron path.
  *
  * Seams pinned here (fireCronRun's run thread cannot be driven without
  * TapRunner, so the post-run step is its own method):
  * {{{
  * object TapScheduler {
  *   /** Called by fireCronRun after TapRunner.run(tap, mode = "run", trigger = "cron")
  *     * returns, with `before` = the tap fired and `fresh` = the tap re-read from the
  *     * store. Only when fresh.lastRunStatus == "success" and
  *     * SchemaDrift.detect(before.lastRunColumns, fresh.lastRunColumns) is Some:
  *     * IncidentRunner.open(Incident.KindSchemaDrift, "tap", tap.name, trigger) with
  *     * trigger = {added, removed, previousColumns, currentColumns, runTime}.
  *     * Returns what open returned (None when nothing opened). */
  *   private[util] def driftAfterCronRun(before: TapConfig, fresh: TapConfig): Option[String]
  * }
  * object IncidentRunner {
  *   /** Spec seam: when non-null, open(...) delegates to it entirely. */
  *   @volatile private[datris] var openOverride: (String, String, String, JsonObject) => Option[String] = null
  * }
  * object Incident { val KindSchemaDrift = "schema_drift" }
  * }}} */
class TapSchedulerDriftSpec extends AnyFunSuite {

    private case class Opened(kind: String, resourceType: String, name: String, trigger: JsonObject)

    private def cols(names: String*): java.util.List[String] = java.util.Arrays.asList(names: _*)

    private val before = TapConfig(
        name = "prices",
        description = "d",
        targetPipeline = "orders",
        cronExpression = "0 * * * * ?",
        lastRunStatus = "success",
        lastRunTime = "2026-10-16 09:00:00",
        lastRunColumns = cols("id", "price")
    )

    private def withOpenCapture[T](body: mutable.ArrayBuffer[Opened] => T): T = {
        val opened = mutable.ArrayBuffer[Opened]()
        val saved = IncidentRunner.openOverride
        IncidentRunner.openOverride = (kind: String, rt: String, name: String, trigger: JsonObject) => {
            opened.synchronized { opened += Opened(kind, rt, name, trigger) }
            Some("inc_drift000001")
        }
        try body(opened)
        finally IncidentRunner.openOverride = saved
    }

    private def strings(o: JsonObject, k: String): List[String] = {
        assert(o.has(k) && o.get(k).isJsonArray, "trigger has array " + k + ": " + o)
        o.getAsJsonArray(k).asScala.map(_.getAsString).toList
    }

    test("schema_drift kind constant") {
        assert(Incident.KindSchemaDrift == "schema_drift")
    }

    test("a cron run that succeeds with changed columns opens exactly one schema_drift incident naming added/removed") {
        withOpenCapture { opened =>
            val fresh = before.copy(lastRunTime = "2026-10-16 10:00:00", lastRunColumns = cols("id", "amount", "currency"))
            val r = TapScheduler.driftAfterCronRun(before, fresh)
            assert(r.contains("inc_drift000001"), r)
            assert(opened.size == 1, opened)
            val o = opened.head
            assert(o.kind == Incident.KindSchemaDrift)
            assert(o.resourceType == "tap")
            assert(o.name == "prices")
            assert(strings(o.trigger, "added").toSet == Set("amount", "currency"), o.trigger)
            assert(strings(o.trigger, "removed") == List("price"), o.trigger)
            assert(strings(o.trigger, "previousColumns").toSet == Set("id", "price"), o.trigger)
            assert(strings(o.trigger, "currentColumns").toSet == Set("id", "amount", "currency"), o.trigger)
            assert(o.trigger.has("runTime") && o.trigger.get("runTime").getAsString == "2026-10-16 10:00:00", o.trigger)
        }
    }

    test("a failed run opens no drift incident, even with different columns") {
        withOpenCapture { opened =>
            val fresh = before.copy(lastRunStatus = "failure", lastRunError = "boom", lastRunColumns = cols("id", "amount"))
            assert(TapScheduler.driftAfterCronRun(before, fresh).isEmpty)
            assert(opened.isEmpty, opened)
        }
    }

    test("unchanged columns (any order) open none") {
        withOpenCapture { opened =>
            val fresh = before.copy(lastRunTime = "2026-10-16 10:00:00", lastRunColumns = cols("price", "id"))
            assert(TapScheduler.driftAfterCronRun(before, fresh).isEmpty)
            assert(opened.isEmpty, opened)
        }
    }

    test("a first run (no previous columns) opens none") {
        withOpenCapture { opened =>
            val first = before.copy(lastRunColumns = null)
            val fresh = before.copy(lastRunColumns = cols("id", "price", "region"))
            assert(TapScheduler.driftAfterCronRun(first, fresh).isEmpty)
            assert(opened.isEmpty, opened)
        }
    }

    private def source(rel: String): String = {
        val file = Seq(Paths.get(rel), Paths.get("datrisserver", rel)).find(Files.isRegularFile(_))
            .getOrElse(fail(s"cannot find $rel from ${Paths.get("").toAbsolutePath}"))
        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    }

    test("the cron path calls the drift hook; the manual-run path (TapAPIController) has no drift hook") {
        val scheduler = source("src/main/scala/ai/datris/util/TapScheduler.scala")
        val fire = scheduler.indexOf("private def fireCronRun")
        assert(fire >= 0, "fireCronRun exists")
        val body = scheduler.substring(fire)
        assert(body.contains("driftAfterCronRun("), "fireCronRun invokes driftAfterCronRun after the run")

        val controller = source("src/main/scala/ai/datris/api/TapAPIController.scala")
        assert(!controller.contains("SchemaDrift"), "no drift detection on manual runs")
        assert(!controller.contains("KindSchemaDrift"), "no drift incident from manual runs")
        assert(!controller.contains("driftAfterCronRun"), "no drift hook from manual runs")
    }
}
