package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, PipelineStatusSummaryTable, TapRunLog}
import com.google.gson.{Gson, JsonArray, JsonObject}
import org.slf4j.LoggerFactory

/** Server-side computation of the Activity dashboard's operational signals:
  * failures (with the recovered flag), stale scheduled taps, and pipeline
  * volume anomalies. One definition shared by the UI (`GET
  * /api/v1/activity/signals`), the Ops chat context, and the recovery
  * agent's sweep — ported from the Activity component so the platform can
  * react to what previously only the browser could see. */
object ActivitySignals {

    private val logger = LoggerFactory.getLogger(getClass)
    private val gson = new Gson()

    val DefaultWindowMs: Long = 24L * 3600000L
    private val MaxRows = 5000

    /** Volume anomaly thresholds: |current vs prior| beyond this with at
      * least this many prior-window runs to trust the baseline. */
    val AnomalyDeltaPct: Int = 60
    val AnomalyMinPriorRuns: Int = 3

    case class FailingItem(
        kind: String, // tap | pipeline
        name: String,
        catalog: Option[String],
        reason: String,
        timeIso: Option[String],
        recovered: Boolean,
        failureCount: Int,
        pipelineToken: Option[String],
        relatedTapName: Option[String]
    ) {
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("kind", kind)
            o.addProperty("name", name)
            catalog.foreach(o.addProperty("catalog", _))
            o.addProperty("reason", reason)
            timeIso.foreach(o.addProperty("timeIso", _))
            o.addProperty("recovered", recovered)
            o.addProperty("failureCount", failureCount)
            pipelineToken.foreach(o.addProperty("pipelineToken", _))
            relatedTapName.foreach(o.addProperty("relatedTapName", _))
            o
        }
    }

    case class StaleTap(name: String, catalog: Option[String], cadenceLabel: String, cadenceMs: Long, lastRunIso: Option[String]) {
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("name", name)
            catalog.foreach(o.addProperty("catalog", _))
            o.addProperty("cadenceLabel", cadenceLabel)
            o.addProperty("cadenceMs", cadenceMs)
            lastRunIso.foreach(o.addProperty("lastRunIso", _))
            o
        }
    }

    case class PipelineVolume(name: String, catalog: Option[String], current: Long, prior: Long, priorRuns: Int, deltaPct: Option[Int]) {
        def isAnomaly: Boolean =
            priorRuns >= AnomalyMinPriorRuns && deltaPct.exists(d => math.abs(d) >= AnomalyDeltaPct)
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("name", name)
            catalog.foreach(o.addProperty("catalog", _))
            o.addProperty("current", current)
            o.addProperty("prior", prior)
            o.addProperty("priorRuns", priorRuns)
            deltaPct.foreach(d => o.addProperty("deltaPct", d))
            o.addProperty("anomaly", isAnomaly)
            o
        }
    }

    case class Signals(
        windowMs: Long,
        computedAtMs: Long,
        failing: List[FailingItem],
        staleTaps: List[StaleTap],
        volumes: List[PipelineVolume]
    ) {
        def anomalies: List[PipelineVolume] = volumes.filter(_.isAnomaly)
        def toJson: JsonObject = {
            val o = new JsonObject()
            o.addProperty("windowMs", windowMs)
            o.addProperty("computedAt", java.time.Instant.ofEpochMilli(computedAtMs).toString)
            val f = new JsonArray(); failing.foreach(x => f.add(x.toJson)); o.add("failing", f)
            val s = new JsonArray(); staleTaps.foreach(x => s.add(x.toJson)); o.add("staleTaps", s)
            val v = new JsonArray(); volumes.foreach(x => v.add(x.toJson)); o.add("volumes", v)
            val a = new JsonArray(); anomalies.foreach(x => a.add(x.toJson)); o.add("anomalies", a)
            o
        }
    }

    def compute(windowMs: Long = DefaultWindowMs): Signals = {
        val now = System.currentTimeMillis()
        val env = DatrisEnvironment.current
        val taps =
            try TapConfigIO.readAll(env.tapTableName)
            catch { case _: Exception => Nil }
        val tapByName = taps.map(t => t.name -> t).toMap

        // Tap runs since 1× window (failures) — rows are {key, value: TapRunLog}.
        val tapLogs: List[TapRunLog] =
            try NoSQLDbUtil.getItemsSinceAsJSON(env.tapLogTableName, "created_at", now - windowMs, MaxRows).flatMap { json =>
                    try {
                        val row = com.google.gson.JsonParser.parseString(json).getAsJsonObject
                        if (row.has("value")) Some(gson.fromJson(row.get("value"), classOf[TapRunLog])) else None
                    } catch { case _: Exception => None }
                }
            catch { case e: Exception => logger.debug("signals: tap log read failed: " + e.getMessage); Nil }

        // Pipeline job summaries since 2× window (current + prior, for volumes).
        val summaries: List[PipelineStatusSummaryTable] =
            try NoSQLDbUtil.getItemsSinceAsJSON(env.pipelineStatusTableName + "-summary", "created_at", now - 2 * windowMs, MaxRows).flatMap { json =>
                    try Some(gson.fromJson(json, classOf[PipelineStatusSummaryTable]))
                    catch { case _: Exception => None }
                }
            catch { case e: Exception => logger.debug("signals: summary read failed: " + e.getMessage); Nil }

        Signals(
            windowMs = windowMs,
            computedAtMs = now,
            failing = computeFailing(now, windowMs, taps.map(t => t.name -> t).toMap, tapLogs, summaries),
            staleTaps = computeStale(now, taps),
            volumes = computeVolumes(now, windowMs, summaries)
        )
    }

    // ------------------------------------------------------------------

    private def isFailureStatus(s: String): Boolean = {
        val v = Option(s).getOrElse("").toLowerCase
        v == "failure" || v == "error" || v == "timed_out"
    }

    private def computeFailing(
        now: Long,
        windowMs: Long,
        tapByName: Map[String, ai.datris.model.TapConfig],
        tapLogs: List[TapRunLog],
        summaries: List[PipelineStatusSummaryTable]
    ): List[FailingItem] = {
        val out = List.newBuilder[FailingItem]

        // Tap-side: latest failure per tap in the window; recovered when the
        // tap's current lastRunStatus is healthy (success or no_records —
        // an empty incremental poll is a healthy outcome).
        val tapFailures = tapLogs.filter(l => isFailureStatus(l.status))
        tapFailures.groupBy(_.tapName).foreach { case (name, logs) =>
            val latest = logs.maxBy(l => Option(l.runTime).getOrElse(""))
            val lastStatus = tapByName.get(name).flatMap(t => Option(t.lastRunStatus)).getOrElse("").toLowerCase
            val recovered = lastStatus == "success" || lastStatus == "no_records"
            out += FailingItem(
                kind = "tap",
                name = name,
                catalog = tapByName.get(name).flatMap(t => Option(t.catalog)),
                reason = Option(latest.error).filter(_.nonEmpty).getOrElse("Run failed"),
                timeIso = Option(latest.runTime),
                recovered = recovered,
                failureCount = logs.size,
                pipelineToken = None,
                relatedTapName = None
            )
        }

        // Pipeline-side: latest errored job per pipeline in the window;
        // recovered when a newer job for the same pipeline ended healthy.
        val tapForPipeline: Map[String, String] =
            tapByName.values.flatMap(t => Option(t.targetPipeline).filter(_.nonEmpty).map(_ -> t.name)).toMap
        val inWindow = summaries.filter(s => s.created_at != null && s.created_at.longValue() >= now - windowMs)
        inWindow.groupBy(_.json.pipeline).foreach { case (pipeline, rows) =>
            if (pipeline != null && pipeline.nonEmpty) {
                val sorted = rows.sortBy(_.created_at.longValue())
                val errors = sorted.filter(r => Option(r.json.status).exists(_.equalsIgnoreCase("error")))
                if (errors.nonEmpty) {
                    val latestError = errors.last
                    val recovered = sorted.reverse.headOption.exists(r => !Option(r.json.status).exists(_.equalsIgnoreCase("error")))
                    out += FailingItem(
                        kind = "pipeline",
                        name = pipeline,
                        catalog = None,
                        reason = Option(latestError.json.aiSummary).filter(_.nonEmpty).getOrElse("Job ended in error"),
                        timeIso = Option(latestError.json.endTime).filter(_ != null).orElse(Option(latestError.json.startTime)),
                        recovered = recovered,
                        failureCount = errors.size,
                        pipelineToken = Option(latestError.json.pipelineToken),
                        relatedTapName = tapForPipeline.get(pipeline)
                    )
                }
            }
        }

        out.result().sortBy(f => f.timeIso.getOrElse("")).reverse
    }

    /** Same conservative cadence heuristic the Activity UI uses: only cron
      * shapes we can classify get a staleness check; everything else is
      * skipped rather than guessed. */
    private[datris] def parseCronCadenceMs(cron: String): Option[Long] = {
        val c = Option(cron).getOrElse("").trim
        if (c == "0 0 * * * ?") return Some(3600000L)
        if (c == "0 0 0 * * ?") return Some(86400000L)
        if (c == "0 0 0 ? * MON-FRI") return Some(86400000L)
        if (c == "0 0 0 ? * MON") return Some(7L * 86400000L)
        val everyN = """^0\s+0\s+\*/(\d+)\s+\*\s+\*\s+\?$""".r
        c match {
            case everyN(n) => Some(n.toLong * 3600000L)
            case _ =>
                val everyNMin = """^0\s+\*/(\d+)\s+\*\s+\*\s+\*\s+\?$""".r
                c match {
                    case everyNMin(n) => Some(n.toLong * 60000L)
                    case _ => None
                }
        }
    }

    private[datris] def cronLabel(cadenceMs: Long): String = {
        if (cadenceMs == 3600000L) "hourly"
        else if (cadenceMs == 86400000L) "daily"
        else if (cadenceMs == 7L * 86400000L) "weekly"
        else if (cadenceMs < 3600000L) "every " + (cadenceMs / 60000L) + "m"
        else "every " + math.round(cadenceMs / 3600000.0) + "h"
    }

    private[datris] def computeStale(
        now: Long,
        taps: List[ai.datris.model.TapConfig],
        dateFormat: String = DatrisEnvironment.current.dateFormat,
        timezone: String = DatrisEnvironment.current.dateTimezone
    ): List[StaleTap] = {
        val sdf = new java.text.SimpleDateFormat(dateFormat)
        sdf.setTimeZone(java.util.TimeZone.getTimeZone(timezone))
        def parseMs(s: String): Option[Long] =
            Option(s).filter(_.nonEmpty).flatMap(v =>
                try Some(sdf.parse(v).getTime)
                catch { case _: Exception => None }
            )

        taps.flatMap { t =>
            if (!t.enabled || t.cronExpression == null || t.cronExpression.isEmpty) None
            else parseCronCadenceMs(t.cronExpression).flatMap { cadence =>
                parseMs(t.lastRunTime).flatMap { lastMs =>
                    if (now - lastMs > cadence * 2)
                        Some(StaleTap(t.name, Option(t.catalog), cronLabel(cadence), cadence, Option(t.lastRunTime)))
                    else None
                }
            }
        }.sortBy(_.lastRunIso.getOrElse(""))
    }

    private[datris] def computeVolumes(now: Long, windowMs: Long, summaries: List[PipelineStatusSummaryTable]): List[PipelineVolume] = {
        val winStart = now - windowMs
        val prevStart = winStart - windowMs
        summaries
            .filter(s => s.json != null && s.json.pipeline != null && s.json.pipeline.nonEmpty)
            .groupBy(_.json.pipeline)
            .map { case (pipeline, rows) =>
                var current = 0L
                var prior = 0L
                var priorRuns = 0
                rows.foreach { r =>
                    val t = r.created_at.longValue()
                    val records = r.json.recordCount.toLong
                    if (t >= winStart && t <= now) current += records
                    else if (t >= prevStart && t < winStart) { prior += records; priorRuns += 1 }
                }
                val deltaPct = if (prior > 0) Some(math.round(((current - prior).toDouble / prior) * 100).toInt) else None
                PipelineVolume(pipeline, None, current, prior, priorRuns, deltaPct)
            }
            .toList
            .sortBy(v => -math.abs(v.deltaPct.getOrElse(0)))
    }
}
