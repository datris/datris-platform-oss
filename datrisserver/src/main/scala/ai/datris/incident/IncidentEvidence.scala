package ai.datris.incident

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.RunProfiler
import ai.datris.util.RunProfiler.{ColumnStats, Quantiles}
import com.google.gson.{JsonArray, JsonElement, JsonObject}

import java.time.Instant
import scala.collection.JavaConverters._

/** Deterministic, platform-computed evidence for one incident: what the
  * resource fed before it broke (baseline), what a scratch run produced
  * (candidate, story 3), what landed after the fix (real), the diff, who is
  * downstream, the thresholds in force and a verdict. Never computed by the
  * model, and never carrying a value from a protected or sensitive-looking
  * column (see [[RunProfiler]]).
  *
  * Units: record deltas and `maxRecordDeltaPct` are percentages; null rates
  * and DQ pass rates are fractions (0.0-1.0). */
case class RunProfile(
    runId: String,
    source: String, // landed-sample | scratch | snapshot | lineage-only
    recordCount: Long,
    columns: List[ColumnStats],
    dqFailures: Int,
    dqPassRate: Double,
    durationMs: Long,
    scriptSha: Option[String],
    tapVersion: Option[Int],
    pipelineVersion: Option[Int],
    sampledRows: Long,
    computedAt: Instant
)

case class ProfileDiff(
    columnsAdded: List[String],
    columnsRemoved: List[String],
    typeChanges: List[(String, String, String)], // (column, from, to)
    recordDeltaPct: Option[Double],
    recordDeltaVsMeanOfLastN: Option[Double],
    lastN: Int,
    nullRateDeltas: List[(String, Double)], // top 5 by |delta|, sign preserved
    quantileShift: List[(String, Double)], // per numeric column: (p50 delta) / (baseline p75 - p25), 0 when IQR = 0
    dqPassRateDelta: Option[Double]
)

case class BlastRadius(
    downstreamPipelines: List[String],
    downstreamDatasets: List[(String, String)], // (kind, coords)
    ucTables: List[String],
    consumersFromContracts: List[String] = Nil,
    lastDownstreamRunAt: Option[String],
    agentsThatQueriedLast7d: Option[List[String]], // None = unknown (reads not audited)
    secretName: Option[String] = None
)

/** A pipeline incident's stored AI script, before the first action and now
  * (story 5a's version numbers). `after == before` once a failed verification
  * restored it. */
case class ScriptVersionChange(kind: String, before: Int, after: Int)

case class ThresholdCheck(name: String, passed: Boolean, observed: String, limit: String)

case class Thresholds(
    maxRecordDeltaPct: Double,
    allowColumnRemoval: Boolean,
    allowTypeChange: Boolean,
    minDqPassRate: Option[Double],
    maxNullRateIncrease: Double
) {

    /** Every check, and the verdict: `needs-human` when a column was removed
      * or a type changed and the policy does not allow it (a person decides
      * whether that shape change was intended — this outranks `fail`);
      * `fail` when the record delta, a null-rate increase or the DQ pass rate
      * breaches its limit; otherwise `pass`. An added column never fails. */
    def evaluate(baseline: RunProfile, diff: ProfileDiff): (List[ThresholdCheck], String) = {
        val eps = 1e-9
        def f2(d: Double): String = Thresholds.fmt("%.2f", d)
        def num(d: Double): String = if (d == math.rint(d) && math.abs(d) < 1e15) d.toLong.toString else d.toString

        val record = diff.recordDeltaPct match {
            case Some(d) => ThresholdCheck("maxRecordDeltaPct", math.abs(d) <= maxRecordDeltaPct + eps, f2(d), num(maxRecordDeltaPct))
            case None => ThresholdCheck("maxRecordDeltaPct", passed = true, "n/a", num(maxRecordDeltaPct))
        }
        val removal = ThresholdCheck(
            "allowColumnRemoval",
            allowColumnRemoval || diff.columnsRemoved.isEmpty,
            diff.columnsRemoved.size.toString,
            if (allowColumnRemoval) "allowed" else "0"
        )
        val typeChange = ThresholdCheck(
            "allowTypeChange",
            allowTypeChange || diff.typeChanges.isEmpty,
            diff.typeChanges.size.toString,
            if (allowTypeChange) "allowed" else "0"
        )
        val maxIncrease = diff.nullRateDeltas.map(_._2).filter(_ > 0).reduceOption(_ max _).getOrElse(0.0)
        val nulls = ThresholdCheck("maxNullRateIncrease", maxIncrease <= maxNullRateIncrease + eps, f2(maxIncrease), f2(maxNullRateIncrease))
        val minDq = minDqPassRate.getOrElse(baseline.dqPassRate - 0.01)
        val dq = diff.dqPassRateDelta match {
            case Some(d) =>
                val observed = baseline.dqPassRate + d
                ThresholdCheck("minDqPassRate", observed + eps >= minDq, Thresholds.fmt("%.3f", observed), Thresholds.fmt("%.3f", minDq))
            case None => ThresholdCheck("minDqPassRate", passed = true, "n/a", Thresholds.fmt("%.3f", minDq))
        }
        val checks = List(record, removal, typeChange, nulls, dq)
        val verdict =
            if (!removal.passed || !typeChange.passed) Thresholds.NeedsHuman
            else if (checks.exists(!_.passed)) Thresholds.Fail
            else Thresholds.Pass
        (checks, verdict)
    }
}

object Thresholds {
    val Pass = "pass"
    val Fail = "fail"
    val NeedsHuman = "needs-human"
    val Pending = "pending"

    /** Locale-independent number formatting for check strings. */
    private[incident] def fmt(pattern: String, d: Double): String = String.format(java.util.Locale.ROOT, pattern, Double.box(d))

    val Default: Thresholds = Thresholds(25.0, allowColumnRemoval = false, allowTypeChange = false, minDqPassRate = None, maxNullRateIncrease = 0.10)
}

case class IncidentEvidence(
    baseline: Option[RunProfile],
    candidate: Option[RunProfile],
    real: Option[RunProfile],
    diff: Option[ProfileDiff],
    blastRadius: BlastRadius,
    thresholds: Thresholds,
    checks: List[ThresholdCheck],
    verdict: String, // pass | fail | needs-human | pending
    computedAt: Instant,
    scriptVersions: List[ScriptVersionChange] = Nil // pipeline incidents only; absent for taps
) {

    def toJson: JsonObject = IncidentEvidence.Codec.evidence(this)

    /** One line of numbers and counts only — never a column value. */
    def summaryLine: String = {
        val sb = new StringBuilder
        sb.append("verdict=").append(verdict)
        val after = candidate.orElse(real)
        (baseline, after) match {
            case (Some(b), Some(a)) => sb.append("; records ").append(b.recordCount).append(" -> ").append(a.recordCount)
            case (Some(b), None) => sb.append("; baseline records ").append(b.recordCount)
            case _ =>
        }
        diff.foreach { d =>
            d.recordDeltaPct.foreach(p => sb.append(" (").append(Thresholds.fmt("%+.2f", p)).append("%)"))
            sb.append("; columns +").append(d.columnsAdded.size).append(" -").append(d.columnsRemoved.size)
                .append(", ").append(d.typeChanges.size).append(" type change(s)")
            d.dqPassRateDelta.foreach(x => sb.append("; DQ pass rate ").append(Thresholds.fmt("%+.3f", x)))
        }
        sb.append("; downstream ").append(blastRadius.downstreamPipelines.size).append(" pipeline(s), ")
            .append(blastRadius.ucTables.size).append(" Unity Catalog table(s)")
        blastRadius.agentsThatQueriedLast7d match {
            case Some(a) => sb.append(", ").append(a.size).append(" agent identity(ies) queried in 7d")
            case None => sb.append(", readers unknown")
        }
        val failed = checks.count(!_.passed)
        if (checks.nonEmpty) sb.append("; ").append(failed).append(" of ").append(checks.size).append(" check(s) failed")
        sb.toString
    }
}

object IncidentEvidence {

    def fromJson(o: JsonObject): IncidentEvidence = Codec.readEvidence(o)

    private[incident] object Codec {

        // ---- write ----

        private def strArr(l: List[String]): JsonArray = { val a = new JsonArray(); l.foreach(a.add); a }

        def quantiles(q: Quantiles): JsonObject = {
            val o = new JsonObject()
            o.addProperty("p1", q.p1); o.addProperty("p25", q.p25); o.addProperty("p50", q.p50)
            o.addProperty("p75", q.p75); o.addProperty("p99", q.p99)
            o
        }

        def column(c: ColumnStats): JsonObject = {
            val o = new JsonObject()
            o.addProperty("name", c.name)
            o.addProperty("inferredType", c.inferredType)
            o.addProperty("nullRate", c.nullRate)
            o.addProperty("distinct", c.distinct)
            c.quantiles.foreach(q => o.add("quantiles", quantiles(q)))
            c.lengthQuantiles.foreach(q => o.add("lengthQuantiles", quantiles(q)))
            c.topK.foreach { t =>
                val a = new JsonArray()
                t.foreach { case (v, n) => val e = new JsonObject(); e.addProperty("value", v); e.addProperty("count", n); a.add(e) }
                o.add("topK", a)
            }
            o
        }

        def profile(p: RunProfile): JsonObject = {
            val o = new JsonObject()
            o.addProperty("runId", p.runId)
            o.addProperty("source", p.source)
            o.addProperty("recordCount", p.recordCount)
            val cols = new JsonArray(); p.columns.foreach(c => cols.add(column(c))); o.add("columns", cols)
            o.addProperty("dqFailures", p.dqFailures)
            o.addProperty("dqPassRate", p.dqPassRate)
            o.addProperty("durationMs", p.durationMs)
            p.scriptSha.foreach(o.addProperty("scriptSha", _))
            p.tapVersion.foreach(v => o.addProperty("tapVersion", v))
            p.pipelineVersion.foreach(v => o.addProperty("pipelineVersion", v))
            o.addProperty("sampledRows", p.sampledRows)
            o.addProperty("computedAt", p.computedAt.toString)
            o
        }

        private def pairs(l: List[(String, Double)], valueKey: String): JsonArray = {
            val a = new JsonArray()
            l.foreach { case (c, v) => val e = new JsonObject(); e.addProperty("column", c); e.addProperty(valueKey, v); a.add(e) }
            a
        }

        def diff(d: ProfileDiff): JsonObject = {
            val o = new JsonObject()
            o.add("columnsAdded", strArr(d.columnsAdded))
            o.add("columnsRemoved", strArr(d.columnsRemoved))
            val tc = new JsonArray()
            d.typeChanges.foreach { case (c, f, t) =>
                val e = new JsonObject(); e.addProperty("column", c); e.addProperty("from", f); e.addProperty("to", t); tc.add(e)
            }
            o.add("typeChanges", tc)
            d.recordDeltaPct.foreach(o.addProperty("recordDeltaPct", _))
            d.recordDeltaVsMeanOfLastN.foreach(o.addProperty("recordDeltaVsMeanOfLastN", _))
            o.addProperty("lastN", d.lastN)
            o.add("nullRateDeltas", pairs(d.nullRateDeltas, "delta"))
            o.add("quantileShift", pairs(d.quantileShift, "shift"))
            d.dqPassRateDelta.foreach(o.addProperty("dqPassRateDelta", _))
            o
        }

        def blast(b: BlastRadius): JsonObject = {
            val o = new JsonObject()
            o.add("downstreamPipelines", strArr(b.downstreamPipelines))
            val ds = new JsonArray()
            b.downstreamDatasets.foreach { case (k, c) => val e = new JsonObject(); e.addProperty("kind", k); e.addProperty("coords", c); ds.add(e) }
            o.add("downstreamDatasets", ds)
            o.add("ucTables", strArr(b.ucTables))
            o.add("consumersFromContracts", strArr(b.consumersFromContracts))
            b.lastDownstreamRunAt.foreach(o.addProperty("lastDownstreamRunAt", _))
            b.agentsThatQueriedLast7d.foreach(a => o.add("agentsThatQueriedLast7d", strArr(a)))
            b.secretName.foreach(o.addProperty("secretName", _))
            o
        }

        def thresholds(t: Thresholds): JsonObject = {
            val o = new JsonObject()
            o.addProperty("maxRecordDeltaPct", t.maxRecordDeltaPct)
            o.addProperty("allowColumnRemoval", t.allowColumnRemoval)
            o.addProperty("allowTypeChange", t.allowTypeChange)
            t.minDqPassRate.foreach(o.addProperty("minDqPassRate", _))
            o.addProperty("maxNullRateIncrease", t.maxNullRateIncrease)
            o
        }

        def check(c: ThresholdCheck): JsonObject = {
            val o = new JsonObject()
            o.addProperty("name", c.name)
            o.addProperty("passed", c.passed)
            o.addProperty("observed", c.observed)
            o.addProperty("limit", c.limit)
            o
        }

        def evidence(e: IncidentEvidence): JsonObject = {
            val o = new JsonObject()
            e.baseline.foreach(p => o.add("baseline", profile(p)))
            e.candidate.foreach(p => o.add("candidate", profile(p)))
            e.real.foreach(p => o.add("real", profile(p)))
            e.diff.foreach(d => o.add("diff", diff(d)))
            o.add("blastRadius", blast(e.blastRadius))
            o.add("thresholds", thresholds(e.thresholds))
            val cs = new JsonArray(); e.checks.foreach(c => cs.add(check(c))); o.add("checks", cs)
            o.addProperty("verdict", e.verdict)
            o.addProperty("computedAt", e.computedAt.toString)
            if (e.scriptVersions.nonEmpty) {
                val sv = new JsonArray()
                e.scriptVersions.foreach { c =>
                    val x = new JsonObject()
                    x.addProperty("kind", c.kind); x.addProperty("before", c.before); x.addProperty("after", c.after)
                    sv.add(x)
                }
                o.add("scriptVersions", sv)
            }
            o
        }

        // ---- read (tolerant: a missing or malformed field takes its empty value) ----

        private def el(o: JsonObject, k: String): Option[JsonElement] =
            if (o == null || !o.has(k) || o.get(k).isJsonNull) None else Some(o.get(k))

        private def str(o: JsonObject, k: String): Option[String] = el(o, k).filter(_.isJsonPrimitive).map(_.getAsString)
        private def dbl(o: JsonObject, k: String): Option[Double] =
            el(o, k).filter(_.isJsonPrimitive).flatMap(e =>
                try Some(e.getAsDouble)
                catch { case _: Exception => None }
            )
        private def lng(o: JsonObject, k: String): Option[Long] =
            el(o, k).filter(_.isJsonPrimitive).flatMap(e =>
                try Some(e.getAsLong)
                catch { case _: Exception => None }
            )
        private def int(o: JsonObject, k: String): Option[Int] = lng(o, k).map(_.toInt)
        private def bool(o: JsonObject, k: String): Option[Boolean] =
            el(o, k).filter(_.isJsonPrimitive).flatMap(e =>
                try Some(e.getAsBoolean)
                catch { case _: Exception => None }
            )
        private def obj(o: JsonObject, k: String): Option[JsonObject] = el(o, k).filter(_.isJsonObject).map(_.getAsJsonObject)
        private def objs(o: JsonObject, k: String): List[JsonObject] =
            el(o, k).filter(_.isJsonArray).map(_.getAsJsonArray.asScala.toList.filter(_.isJsonObject).map(_.getAsJsonObject)).getOrElse(Nil)
        private def strs(o: JsonObject, k: String): Option[List[String]] =
            el(o, k).filter(_.isJsonArray).map(_.getAsJsonArray.asScala.toList.filter(x => x.isJsonPrimitive).map(_.getAsString))
        private def instant(o: JsonObject, k: String): Instant =
            str(o, k).flatMap(s =>
                try Some(Instant.parse(s))
                catch { case _: Exception => None }
            ).getOrElse(Instant.EPOCH)

        def readQuantiles(o: JsonObject): Quantiles =
            Quantiles(
                dbl(o, "p1").getOrElse(0.0),
                dbl(o, "p25").getOrElse(0.0),
                dbl(o, "p50").getOrElse(0.0),
                dbl(o, "p75").getOrElse(0.0),
                dbl(o, "p99").getOrElse(0.0)
            )

        def readColumn(o: JsonObject): ColumnStats =
            ColumnStats(
                name = str(o, "name").getOrElse(""),
                inferredType = str(o, "inferredType").getOrElse("null"),
                nullRate = dbl(o, "nullRate").getOrElse(0.0),
                distinct = lng(o, "distinct").getOrElse(0L),
                quantiles = obj(o, "quantiles").map(readQuantiles),
                lengthQuantiles = obj(o, "lengthQuantiles").map(readQuantiles),
                topK = el(o, "topK").filter(_.isJsonArray).map(_ => objs(o, "topK").map(e => (str(e, "value").getOrElse(""), lng(e, "count").getOrElse(0L))))
            )

        def readProfile(o: JsonObject): RunProfile =
            RunProfile(
                runId = str(o, "runId").getOrElse(""),
                source = str(o, "source").getOrElse("lineage-only"),
                recordCount = lng(o, "recordCount").getOrElse(0L),
                columns = objs(o, "columns").map(readColumn),
                dqFailures = int(o, "dqFailures").getOrElse(0),
                dqPassRate = dbl(o, "dqPassRate").getOrElse(1.0),
                durationMs = lng(o, "durationMs").getOrElse(0L),
                scriptSha = str(o, "scriptSha"),
                tapVersion = int(o, "tapVersion"),
                pipelineVersion = int(o, "pipelineVersion"),
                sampledRows = lng(o, "sampledRows").getOrElse(0L),
                computedAt = instant(o, "computedAt")
            )

        private def readPairs(o: JsonObject, k: String, valueKey: String): List[(String, Double)] =
            objs(o, k).map(e => (str(e, "column").getOrElse(""), dbl(e, valueKey).getOrElse(0.0)))

        def readDiff(o: JsonObject): ProfileDiff =
            ProfileDiff(
                columnsAdded = strs(o, "columnsAdded").getOrElse(Nil),
                columnsRemoved = strs(o, "columnsRemoved").getOrElse(Nil),
                typeChanges = objs(o, "typeChanges").map(e => (str(e, "column").getOrElse(""), str(e, "from").getOrElse(""), str(e, "to").getOrElse(""))),
                recordDeltaPct = dbl(o, "recordDeltaPct"),
                recordDeltaVsMeanOfLastN = dbl(o, "recordDeltaVsMeanOfLastN"),
                lastN = int(o, "lastN").getOrElse(0),
                nullRateDeltas = readPairs(o, "nullRateDeltas", "delta"),
                quantileShift = readPairs(o, "quantileShift", "shift"),
                dqPassRateDelta = dbl(o, "dqPassRateDelta")
            )

        def readBlast(o: JsonObject): BlastRadius =
            BlastRadius(
                downstreamPipelines = strs(o, "downstreamPipelines").getOrElse(Nil),
                downstreamDatasets = objs(o, "downstreamDatasets").map(e => (str(e, "kind").getOrElse(""), str(e, "coords").getOrElse(""))),
                ucTables = strs(o, "ucTables").getOrElse(Nil),
                consumersFromContracts = strs(o, "consumersFromContracts").getOrElse(Nil),
                lastDownstreamRunAt = str(o, "lastDownstreamRunAt"),
                agentsThatQueriedLast7d = strs(o, "agentsThatQueriedLast7d"),
                secretName = str(o, "secretName")
            )

        def readThresholds(o: JsonObject): Thresholds =
            Thresholds(
                maxRecordDeltaPct = dbl(o, "maxRecordDeltaPct").getOrElse(Thresholds.Default.maxRecordDeltaPct),
                allowColumnRemoval = bool(o, "allowColumnRemoval").getOrElse(false),
                allowTypeChange = bool(o, "allowTypeChange").getOrElse(false),
                minDqPassRate = dbl(o, "minDqPassRate"),
                maxNullRateIncrease = dbl(o, "maxNullRateIncrease").getOrElse(Thresholds.Default.maxNullRateIncrease)
            )

        def readEvidence(o: JsonObject): IncidentEvidence =
            IncidentEvidence(
                baseline = obj(o, "baseline").map(readProfile),
                candidate = obj(o, "candidate").map(readProfile),
                real = obj(o, "real").map(readProfile),
                diff = obj(o, "diff").map(readDiff),
                blastRadius = obj(o, "blastRadius").map(readBlast).getOrElse(BlastRadius(Nil, Nil, Nil, Nil, None, None, None)),
                thresholds = obj(o, "thresholds").map(readThresholds).getOrElse(Thresholds.Default),
                checks = objs(o, "checks").map(c =>
                    ThresholdCheck(
                        str(c, "name").getOrElse(""),
                        bool(c, "passed").getOrElse(false),
                        str(c, "observed").getOrElse(""),
                        str(c, "limit").getOrElse("")
                    )
                ),
                verdict = str(o, "verdict").getOrElse(Thresholds.Pending),
                computedAt = instant(o, "computedAt"),
                scriptVersions = objs(o, "scriptVersions").flatMap(c =>
                    str(c, "kind").map(k => ScriptVersionChange(k, int(c, "before").getOrElse(0), int(c, "after").getOrElse(0)))
                )
            )
    }
}
