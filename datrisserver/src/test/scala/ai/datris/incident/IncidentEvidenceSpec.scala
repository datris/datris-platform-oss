package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.RunProfiler
import ai.datris.util.RunProfiler.{ColumnStats, Quantiles, Stats}
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant

/** Story: Incident evidence model (plans/stories/self-healing-1-evidence-model.md),
  * Acceptance bullets 2 and 3 — `RunProfiler.diff` maths, `Thresholds.evaluate`
  * verdicts (table-driven) and the `IncidentEvidence` JSON round-trip.
  *
  * Units: `recordDeltaPct` / `recordDeltaVsMeanOfLastN` / `maxRecordDeltaPct`
  * are percentages (30,112 -> 29,870 is -0.80, i.e. -0.80 %); null rates and
  * DQ pass rates are fractions (0.0-1.0). */
class IncidentEvidenceSpec extends AnyFunSuite {

    private def num(name: String, q: Quantiles, nullRate: Double = 0.0): ColumnStats =
        ColumnStats(name, "number", nullRate, 100L, Some(q), None, None)

    private def str(name: String, nullRate: Double = 0.0): ColumnStats =
        ColumnStats(name, "string", nullRate, 3L, None, None, Some(List(("a", 2L), ("b", 1L))))

    private val q = Quantiles(1.0, 10.0, 20.0, 30.0, 99.0)

    private val baseStats = Stats(1000L, List(num("amount", q), str("status"), str("region")))

    private def round2(d: Double): Double = BigDecimal(d).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble

    private val t0 = Instant.parse("2026-10-16T10:00:00Z")

    private def profile(
        stats: Stats,
        dqPassRate: Double = 1.0,
        runId: String = "run_base",
        source: String = "landed-sample"
    ): RunProfile = RunProfile(
        runId = runId,
        source = source,
        recordCount = stats.recordCount,
        columns = stats.columns,
        dqFailures = 0,
        dqPassRate = dqPassRate,
        durationMs = 1234L,
        scriptSha = Some("abc123"),
        tapVersion = Some(3),
        pipelineVersion = None,
        sampledRows = stats.recordCount,
        computedAt = t0
    )

    private val defaults = Thresholds(
        maxRecordDeltaPct = 25.0,
        allowColumnRemoval = false,
        allowTypeChange = false,
        minDqPassRate = None,
        maxNullRateIncrease = 0.10
    )

    private def emptyDiff = ProfileDiff(
        columnsAdded = Nil,
        columnsRemoved = Nil,
        typeChanges = Nil,
        recordDeltaPct = Some(0.0),
        recordDeltaVsMeanOfLastN = None,
        lastN = 0,
        nullRateDeltas = Nil,
        quantileShift = Nil,
        dqPassRateDelta = Some(0.0)
    )

    // ------------------------------------------------------------------
    // Bullet 2: diff maths
    // ------------------------------------------------------------------

    test("diff reports columnsAdded / columnsRemoved / typeChanges exactly") {
        val candidate = Stats(
            1000L,
            List(
                ColumnStats("amount", "string", 0.0, 100L, None, None, None), // number -> string
                str("status"),
                str("channel") // added; region removed
            )
        )
        val d = RunProfiler.diff(baseStats, candidate, Nil)
        assert(d.columnsAdded == List("channel"))
        assert(d.columnsRemoved == List("region"))
        assert(d.typeChanges == List(("amount", "number", "string")))
    }

    test("an identical profile diffs to no column changes and a zero record delta") {
        val d = RunProfiler.diff(baseStats, baseStats, Seq(1000L, 1000L))
        assert(d.columnsAdded.isEmpty && d.columnsRemoved.isEmpty && d.typeChanges.isEmpty)
        assert(d.recordDeltaPct.map(round2) == Some(0.0))
    }

    test("recordDeltaPct of 30,112 -> 29,870 is -0.80 (2 dp)") {
        val d = RunProfiler.diff(baseStats.copy(recordCount = 30112L), baseStats.copy(recordCount = 29870L), Nil)
        assert(d.recordDeltaPct.map(round2) == Some(-0.80), d.recordDeltaPct)
    }

    test("recordDeltaVsMeanOfLastN uses the given counts and lastN = counts.size") {
        // mean(100, 200, 300) = 200; candidate 220 is +10 % against it.
        val d = RunProfiler.diff(baseStats.copy(recordCount = 999L), baseStats.copy(recordCount = 220L), Seq(100L, 200L, 300L))
        assert(d.lastN == 3)
        assert(d.recordDeltaVsMeanOfLastN.map(round2) == Some(10.0), d.recordDeltaVsMeanOfLastN)

        val none = RunProfiler.diff(baseStats, baseStats, Nil)
        assert(none.lastN == 0)
        assert(none.recordDeltaVsMeanOfLastN.isEmpty)
    }

    test("quantileShift is (p50 delta) / (baseline p75 - p25), and 0 when the IQR is 0") {
        val flat = Stats(10L, List(num("amount", Quantiles(5.0, 5.0, 5.0, 5.0, 5.0))))
        val moved = Stats(10L, List(num("amount", Quantiles(7.0, 7.0, 7.0, 7.0, 7.0))))
        val zero = RunProfiler.diff(flat, moved, Nil)
        assert(zero.quantileShift == List(("amount", 0.0)))

        // baseline p25 = 10, p50 = 20, p75 = 30 -> IQR 20; candidate p50 = 25 -> 5 / 20 = 0.25
        val cand = Stats(1000L, List(num("amount", Quantiles(1.0, 10.0, 25.0, 30.0, 99.0)), str("status"), str("region")))
        val shifted = RunProfiler.diff(baseStats, cand, Nil)
        val s = shifted.quantileShift.toMap
        assert(s.get("amount").map(round2) == Some(0.25), shifted.quantileShift)
        assert(!s.contains("status"), "only numeric columns carry a quantile shift")
    }

    test("nullRateDeltas is the 5 largest by magnitude, sign preserved") {
        val names = List("c1", "c2", "c3", "c4", "c5", "c6", "c7")
        val baseRates = List(0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0)
        val candRates = List(0.30, 0.01, 0.10, 0.20, 0.02, 0.15, 0.05) // deltas .30 .01 -.40 .20 .02 .15 .05
        val b = Stats(100L, names.zip(baseRates).map { case (n, r) => str(n, r) })
        val c = Stats(100L, names.zip(candRates).map { case (n, r) => str(n, r) })
        val d = RunProfiler.diff(b, c, Nil)
        assert(d.nullRateDeltas.size == 5, d.nullRateDeltas)
        val got = d.nullRateDeltas.map { case (n, v) => n -> round2(v) }.toMap
        assert(got == Map("c3" -> -0.40, "c1" -> 0.30, "c4" -> 0.20, "c6" -> 0.15, "c7" -> 0.05))
    }

    // ------------------------------------------------------------------
    // Bullet 3: Thresholds.evaluate, table-driven
    // ------------------------------------------------------------------

    test("defaults pass an identical profile") {
        val base = profile(baseStats)
        val d = RunProfiler.diff(baseStats, baseStats, Seq(1000L, 1000L, 1000L))
        val (checks, verdict) = defaults.evaluate(base, d.copy(dqPassRateDelta = Some(0.0)))
        assert(verdict == "pass", checks)
        assert(checks.nonEmpty && checks.forall(_.passed), checks)
    }

    private case class Row(label: String, thresholds: Thresholds, baseline: RunProfile, diff: ProfileDiff, verdict: String)

    private val base = profile(baseStats)

    private val table: List[Row] = List(
        Row("-26 % rows", defaults, base, emptyDiff.copy(recordDeltaPct = Some(-26.0)), "fail"),
        Row("-24 % rows", defaults, base, emptyDiff.copy(recordDeltaPct = Some(-24.0)), "pass"),
        Row("one removed column", defaults, base, emptyDiff.copy(columnsRemoved = List("region")), "needs-human"),
        Row("one removed column, removal allowed", defaults.copy(allowColumnRemoval = true), base, emptyDiff.copy(columnsRemoved = List("region")), "pass"),
        Row("one type change", defaults, base, emptyDiff.copy(typeChanges = List(("amount", "number", "string"))), "needs-human"),
        Row("one added column", defaults, base, emptyDiff.copy(columnsAdded = List("channel")), "pass"),
        Row("null-rate +0.11", defaults, base, emptyDiff.copy(nullRateDeltas = List(("status", 0.11))), "fail"),
        Row("null-rate +0.09", defaults, base, emptyDiff.copy(nullRateDeltas = List(("status", 0.09))), "pass"),
        Row("DQ 0.985 vs baseline 1.0 (default min = baseline - 0.01)", defaults, base, emptyDiff.copy(dqPassRateDelta = Some(-0.015)), "fail"),
        Row("DQ 0.995 vs baseline 1.0", defaults, base, emptyDiff.copy(dqPassRateDelta = Some(-0.005)), "pass"),
        Row("DQ 0.985 with minDqPassRate = 0.9", defaults.copy(minDqPassRate = Some(0.9)), base, emptyDiff.copy(dqPassRateDelta = Some(-0.015)), "pass"),
        Row("DQ 0.85 with minDqPassRate = 0.9", defaults.copy(minDqPassRate = Some(0.9)), base, emptyDiff.copy(dqPassRateDelta = Some(-0.15)), "fail")
    )

    table.foreach { r =>
        test("thresholds: " + r.label + " -> " + r.verdict) {
            val (checks, verdict) = r.thresholds.evaluate(r.baseline, r.diff)
            assert(verdict == r.verdict, checks)
        }
    }

    test("-26 % rows fails with the maxRecordDeltaPct check false") {
        val (checks, verdict) = defaults.evaluate(base, emptyDiff.copy(recordDeltaPct = Some(-26.0)))
        assert(verdict == "fail")
        val c = checks.find(_.name == "maxRecordDeltaPct").getOrElse(fail("no maxRecordDeltaPct check in " + checks))
        assert(!c.passed)
    }

    test("one added column passes and the diff lists it") {
        val cand = baseStats.copy(columns = baseStats.columns :+ str("channel"))
        val d = RunProfiler.diff(baseStats, cand, Nil)
        assert(d.columnsAdded == List("channel"))
        val (_, verdict) = defaults.evaluate(base, d.copy(dqPassRateDelta = Some(0.0)))
        assert(verdict == "pass")
    }

    // ------------------------------------------------------------------
    // Bullet 3: JSON round-trip
    // ------------------------------------------------------------------

    private val fullEvidence = IncidentEvidence(
        baseline = Some(base),
        candidate = Some(profile(baseStats, dqPassRate = 0.99, runId = "run_scratch", source = "scratch")),
        real = Some(profile(baseStats.copy(recordCount = 990L), dqPassRate = 0.98, runId = "run_real")),
        diff = Some(
            ProfileDiff(
                columnsAdded = List("channel"),
                columnsRemoved = List("region"),
                typeChanges = List(("amount", "number", "string")),
                recordDeltaPct = Some(-1.0),
                recordDeltaVsMeanOfLastN = Some(-0.5),
                lastN = 3,
                nullRateDeltas = List(("status", 0.02)),
                quantileShift = List(("amount", 0.25)),
                dqPassRateDelta = Some(-0.02)
            )
        ),
        blastRadius = BlastRadius(
            downstreamPipelines = List("orders", "orders_daily"),
            downstreamDatasets = List(("postgres", "datris.public.orders")),
            ucTables = List("main.sales.orders"),
            consumersFromContracts = Nil,
            lastDownstreamRunAt = Some("2026-10-16T09:00:00Z"),
            agentsThatQueriedLast7d = Some(List("agent-a", "agent-b")),
            secretName = Some("orders-api")
        ),
        thresholds = defaults.copy(minDqPassRate = Some(0.95)),
        checks = List(ThresholdCheck("maxRecordDeltaPct", passed = true, observed = "-1.00", limit = "25")),
        verdict = "pass",
        computedAt = t0
    )

    private val sparseEvidence = IncidentEvidence(
        baseline = Some(
            profile(baseStats, source = "lineage-only").copy(scriptSha = None, tapVersion = None, pipelineVersion = None)
        ),
        candidate = None,
        real = None,
        diff = None,
        blastRadius = BlastRadius(
            downstreamPipelines = Nil,
            downstreamDatasets = Nil,
            ucTables = Nil,
            lastDownstreamRunAt = None,
            agentsThatQueriedLast7d = None
        ),
        thresholds = defaults,
        checks = Nil,
        verdict = "pending",
        computedAt = t0
    )

    test("toJson / fromJson round-trips every field") {
        assert(IncidentEvidence.fromJson(fullEvidence.toJson) == fullEvidence)
    }

    test("toJson / fromJson round-trips the Nones (and keeps None distinct from an empty agents list)") {
        assert(IncidentEvidence.fromJson(sparseEvidence.toJson) == sparseEvidence)
        val knownNobody = sparseEvidence.copy(blastRadius = sparseEvidence.blastRadius.copy(agentsThatQueriedLast7d = Some(Nil)))
        assert(IncidentEvidence.fromJson(knownNobody.toJson) == knownNobody)
    }

    test("the round-trip survives a trip through JSON text") {
        val text = fullEvidence.toJson.toString
        val back = IncidentEvidence.fromJson(com.google.gson.JsonParser.parseString(text).getAsJsonObject)
        assert(back == fullEvidence)
    }
}
