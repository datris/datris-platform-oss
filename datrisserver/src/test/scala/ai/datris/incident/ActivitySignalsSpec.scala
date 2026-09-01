package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{PipelineStatusSummary, PipelineStatusSummaryTable, TapConfig}
import ai.datris.util.ActivitySignals
import org.scalatest.funsuite.AnyFunSuite

class ActivitySignalsSpec extends AnyFunSuite {

    private val Fmt = "yyyy-MM-dd HH:mm:ss"
    private val Hour = 3600000L
    private val Day = 86400000L

    private def tap(name: String, cron: String, lastRun: String, enabled: Boolean = true) =
        TapConfig(name = name, description = "d", targetPipeline = null, cronExpression = cron, enabled = enabled, lastRunTime = lastRun)

    private def fmt(ms: Long): String = {
        val sdf = new java.text.SimpleDateFormat(Fmt)
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"))
        sdf.format(new java.util.Date(ms))
    }

    test("classifies the known cron shapes") {
        assert(ActivitySignals.parseCronCadenceMs("0 0 * * * ?").contains(Hour))
        assert(ActivitySignals.parseCronCadenceMs("0 0 0 * * ?").contains(Day))
        assert(ActivitySignals.parseCronCadenceMs("0 0 0 ? * MON-FRI").contains(Day))
        assert(ActivitySignals.parseCronCadenceMs("0 0 0 ? * MON").contains(7 * Day))
        assert(ActivitySignals.parseCronCadenceMs("0 0 */2 * * ?").contains(2 * Hour))
        assert(ActivitySignals.parseCronCadenceMs("0 */15 * * * ?").contains(15 * 60000L))
    }

    test("unclassifiable crons are skipped, not guessed") {
        assert(ActivitySignals.parseCronCadenceMs("0 30 9 ? * TUE").isEmpty)
        assert(ActivitySignals.parseCronCadenceMs("").isEmpty)
        assert(ActivitySignals.parseCronCadenceMs(null).isEmpty)
    }

    test("cadence labels read naturally") {
        assert(ActivitySignals.cronLabel(Hour) == "hourly")
        assert(ActivitySignals.cronLabel(Day) == "daily")
        assert(ActivitySignals.cronLabel(7 * Day) == "weekly")
        assert(ActivitySignals.cronLabel(2 * Hour) == "every 2h")
        assert(ActivitySignals.cronLabel(15 * 60000L) == "every 15m")
    }

    test("a tap past 2x its cadence is stale; one within it is not") {
        val now = 1000L * Day
        val stale = ActivitySignals.computeStale(
            now,
            List(
                tap("late-hourly", "0 0 * * * ?", fmt(now - 3 * Hour)),
                tap("ok-hourly", "0 0 * * * ?", fmt(now - Hour)),
                tap("late-daily", "0 0 0 * * ?", fmt(now - 3 * Day))
            ),
            Fmt,
            "UTC"
        )
        assert(stale.map(_.name).toSet == Set("late-hourly", "late-daily"))
        assert(stale.find(_.name == "late-hourly").exists(_.cadenceLabel == "hourly"))
    }

    test("disabled, cron-less, unclassifiable and never-run taps are skipped") {
        val now = 1000L * Day
        val stale = ActivitySignals.computeStale(
            now,
            List(
                tap("disabled", "0 0 * * * ?", fmt(now - 10 * Hour), enabled = false),
                tap("no-cron", null, fmt(now - 10 * Hour)),
                tap("weird-cron", "0 30 9 ? * TUE", fmt(now - 30 * Day)),
                tap("never-ran", "0 0 * * * ?", null)
            ),
            Fmt,
            "UTC"
        )
        assert(stale.isEmpty)
    }

    private def summary(pipeline: String, atMs: Long, records: Int) = PipelineStatusSummaryTable(
        pipeline_token = "t-" + atMs,
        json = PipelineStatusSummary(
            createdAtTimestamp = "x",
            createdAt = atMs,
            updatedAt = atMs,
            pipeline = pipeline,
            pipelineToken = "t-" + atMs,
            process = "p",
            startTime = "s",
            endTime = "e",
            totalTime = "1s",
            status = "success",
            recordCount = records
        ),
        created_at = atMs
    )

    test("a big swing with a solid baseline is an anomaly; a thin baseline is not") {
        val now = 1000L * Day
        val win = Day
        val rows =
            List(
                summary("dropped", now - win - 2 * Hour, 100),
                summary("dropped", now - win - 4 * Hour, 100),
                summary("dropped", now - win - 6 * Hour, 100),
                summary("dropped", now - Hour, 10)
            ) ++
                List(summary("thin", now - win - 2 * Hour, 100), summary("thin", now - Hour, 1)) ++
                List(
                    summary("steady", now - win - 2 * Hour, 50),
                    summary("steady", now - win - 3 * Hour, 50),
                    summary("steady", now - win - 5 * Hour, 50),
                    summary("steady", now - Hour, 500)
                )
        val vols = ActivitySignals.computeVolumes(now, win, rows)
        val dropped = vols.find(_.name == "dropped").get
        assert(dropped.deltaPct.exists(_ <= -90))
        assert(dropped.isAnomaly)
        assert(!vols.find(_.name == "thin").get.isAnomaly)
        val steady = vols.find(_.name == "steady").get
        assert(steady.deltaPct.contains(233)) // 500 vs 150 baseline
        assert(steady.isAnomaly) // over-volume counts too
    }

    test("no prior data means no delta and no anomaly") {
        val now = 1000L * Day
        val vols = ActivitySignals.computeVolumes(now, Day, List(summary("new", now - Hour, 500)))
        val n = vols.find(_.name == "new").get
        assert(n.deltaPct.isEmpty && !n.isAnomaly)
    }
}
