package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.incident.ProfileDiff

import scala.collection.mutable

/** Pure, pipeline-agnostic profile of a set of rows: record count and, per
  * column, the inferred type, null rate, distinct count, numeric quantiles,
  * string-length quantiles for columns whose values may not be shown, and —
  * only for value-safe, low-cardinality columns — the top values.
  *
  * No Spark, no IO, no model: the recovery agent's evidence (and the twins /
  * contracts work that shares this) must be deterministic platform code.
  *
  * Privacy: a column for which `valueSafe` is false never yields a value —
  * no topK and no value quantiles, only string-length quantiles. Callers
  * decide `valueSafe`; [[SensitiveNamePattern]] is the deterministic name
  * list they combine with field protection. (The field-protection advisor is
  * AI-driven and cannot gate a pure profiler, so a name list it is.) */
object RunProfiler {

    case class Quantiles(p1: Double, p25: Double, p50: Double, p75: Double, p99: Double)

    case class ColumnStats(
        name: String,
        inferredType: String, // number | string | boolean | null | mixed
        nullRate: Double,
        distinct: Long,
        quantiles: Option[Quantiles], // numeric, value-safe columns only
        lengthQuantiles: Option[Quantiles], // columns whose values may not be shown
        topK: Option[List[(String, Long)]] // only when valueSafe(name) && distinct <= topKMaxDistinct
    )

    case class Stats(recordCount: Long, columns: List[ColumnStats])

    /** Exact distinct counting stops adding new values past this; `distinct`
      * is then a lower bound (capped). */
    val MaxDistinctTracked = 10000

    /** Quantiles come from a uniform sample of at most this many values. */
    val MaxQuantileSample = 5000

    /** How many top values a value-safe, low-cardinality column reports. */
    val TopK = 10

    /** Column names that look like they hold personal or secret values:
      * their values are never shown, whatever field protection says. Matched
      * case-insensitively against the whole name, with `_`, `-`, `.` and
      * camelCase boundaries as word breaks. */
    val SensitiveNamePattern: scala.util.matching.Regex =
        ("(?i)(^|[_\\-. ])(e?mail|email_?address|phone|mobile|ssn|social_?security|(first|last|full|user|given|family|middle|sur)?_?name|" +
            "address|street|addr|dob|birth_?date|date_?of_?birth|token|api_?key|key|secret|password|passwd|pwd|iban|card|card_?number|cc|cvv|pan)([_\\-. ]|$)").r

    /** True when the column name matches [[SensitiveNamePattern]]. */
    def looksSensitive(name: String): Boolean = {
        if (name == null) return false
        // camelCase → snake so firstName / apiKey / cardNumber split into words.
        val split = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        SensitiveNamePattern.findFirstIn(split).isDefined
    }

    private val NumericPattern = "^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$".r.pattern

    private sealed trait Kind
    private case object KNumber extends Kind
    private case object KString extends Kind
    private case object KBoolean extends Kind

    /** Deterministic reservoir sample of doubles. */
    private final class Reservoir(max: Int, seed: Long) {
        private val buf = mutable.ArrayBuffer[Double]()
        private var seen = 0L
        private val rnd = new java.util.Random(seed)
        def add(v: Double): Unit = {
            seen += 1
            if (buf.size < max) buf += v
            else {
                val j = (rnd.nextDouble() * seen).toLong
                if (j < max) buf(j.toInt) = v
            }
        }
        def isEmpty: Boolean = buf.isEmpty
        def sorted: Array[Double] = buf.toArray.sorted
    }

    private final class Acc(val name: String) {
        var nonNull = 0L
        var numbers = 0L
        var strings = 0L
        var booleans = 0L
        val counts = mutable.HashMap[String, Long]()
        var capped = false
        val values = new Reservoir(MaxQuantileSample, 17L)
        val lengths = new Reservoir(MaxQuantileSample, 31L)

        def add(v: Any): Unit = {
            nonNull += 1
            val (kind, text, num) = classify(v)
            kind match {
                case KNumber => numbers += 1; values.add(num)
                case KString => strings += 1
                case KBoolean => booleans += 1
            }
            lengths.add(text.length.toDouble)
            counts.get(text) match {
                case Some(c) => counts.put(text, c + 1)
                case None =>
                    if (counts.size < MaxDistinctTracked) counts.put(text, 1L) else capped = true
            }
        }
    }

    private def classify(v: Any): (Kind, String, Double) = v match {
        case b: Boolean => (KBoolean, b.toString, 0d)
        case n: java.math.BigDecimal => (KNumber, n.toPlainString, n.doubleValue())
        case n: BigDecimal => (KNumber, n.bigDecimal.toPlainString, n.toDouble)
        case n: Number if !n.doubleValue().isNaN && !n.doubleValue().isInfinite => (KNumber, n.toString, n.doubleValue())
        case other =>
            val s = other.toString
            val t = s.trim
            if (t.nonEmpty && NumericPattern.matcher(t).matches()) {
                val d =
                    try t.toDouble
                    catch { case _: NumberFormatException => Double.NaN }
                if (!d.isNaN && !d.isInfinite) (KNumber, s, d) else (KString, s, 0d)
            } else if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) (KBoolean, s, 0d)
            else (KString, s, 0d)
    }

    private def isNull(v: Any): Boolean = v == null || v == None

    private def unwrap(v: Any): Any = v match {
        case Some(x) => x
        case x => x
    }

    /** Nearest-rank quantiles over a sorted array (non-empty). */
    private def quantiles(sorted: Array[Double]): Quantiles = {
        val n = sorted.length
        def q(p: Double): Double = {
            val idx = math.max(0, math.min(n - 1, math.ceil(p * n).toInt - 1))
            sorted(idx)
        }
        Quantiles(q(0.01), q(0.25), q(0.50), q(0.75), q(0.99))
    }

    /** Single pass over `rows`. A key absent from a row counts as null for
      * that row. Columns are listed in first-seen order. */
    def profile(rows: Iterator[Map[String, Any]], valueSafe: String => Boolean, topKMaxDistinct: Int = 50): Stats = {
        val accs = mutable.LinkedHashMap[String, Acc]()
        var count = 0L
        rows.foreach { row =>
            count += 1
            if (row != null) row.foreach { case (k, raw) =>
                if (k != null) {
                    val acc = accs.getOrElseUpdate(k, new Acc(k))
                    val v = unwrap(raw)
                    if (!isNull(v)) acc.add(v)
                }
            }
        }
        val columns = accs.values.toList.map { a =>
            val safe =
                try valueSafe(a.name)
                catch { case _: Exception => false }
            val kinds = List(a.numbers > 0, a.strings > 0, a.booleans > 0).count(identity)
            val inferred =
                if (a.nonNull == 0) "null"
                else if (kinds > 1) "mixed"
                else if (a.numbers > 0) "number"
                else if (a.booleans > 0) "boolean"
                else "string"
            val distinct = a.counts.size.toLong
            val q = if (safe && inferred == "number" && !a.values.isEmpty) Some(quantiles(a.values.sorted)) else None
            val lq = if (!safe && !a.lengths.isEmpty) Some(quantiles(a.lengths.sorted)) else None
            val top =
                if (safe && !a.capped && a.nonNull > 0 && distinct <= topKMaxDistinct)
                    Some(a.counts.toList.sortBy { case (v, c) => (-c, v) }.take(TopK))
                else None
            ColumnStats(
                name = a.name,
                inferredType = inferred,
                nullRate = if (count == 0) 0.0 else (count - a.nonNull).toDouble / count.toDouble,
                distinct = distinct,
                quantiles = q,
                lengthQuantiles = lq,
                topK = top
            )
        }
        Stats(count, columns)
    }

    /** Deterministic before/after comparison. `lastNCounts` are the record
      * counts of the most recent successful runs (any order); the DQ delta is
      * left to the caller (Stats carries no DQ). Record deltas are
      * percentages; null-rate deltas are fractions. */
    def diff(baseline: Stats, candidate: Stats, lastNCounts: Seq[Long]): ProfileDiff = {
        // Column names match case-insensitively: a warehouse that folds
        // unquoted identifiers (Snowflake → UPPER) must not read as every
        // column removed and re-added. The baseline's spelling is reported.
        def key(n: String): String = if (n == null) null else n.toLowerCase(java.util.Locale.ROOT)
        val bCols = baseline.columns.reverse.map(c => key(c.name) -> c).toMap
        val cCols = candidate.columns.reverse.map(c => key(c.name) -> c).toMap
        val added = candidate.columns.map(_.name).filterNot(n => bCols.contains(key(n)))
        val removed = baseline.columns.map(_.name).filterNot(n => cCols.contains(key(n)))
        val common = baseline.columns.filter(c => cCols.contains(key(c.name))).map(b => (b, cCols(key(b.name))))

        val typeChanges = common.collect {
            case (b, c) if b.inferredType != c.inferredType && b.inferredType != "null" && c.inferredType != "null" =>
                (b.name, b.inferredType, c.inferredType)
        }

        val recordDeltaPct =
            if (baseline.recordCount > 0) Some((candidate.recordCount - baseline.recordCount).toDouble / baseline.recordCount.toDouble * 100.0)
            else None

        val counts = lastNCounts.toList
        val mean = if (counts.isEmpty) 0.0 else counts.map(_.toDouble).sum / counts.size
        val vsMean = if (counts.nonEmpty && mean > 0) Some((candidate.recordCount.toDouble - mean) / mean * 100.0) else None

        val nullDeltas = common
            .map { case (b, c) => (b.name, c.nullRate - b.nullRate) }
            .filter { case (_, d) => math.abs(d) > 1e-12 }
            .sortBy { case (n, d) => (-math.abs(d), n) }
            .take(5)

        val shifts = common.collect {
            case (b, c) if b.quantiles.isDefined && c.quantiles.isDefined =>
                val bq = b.quantiles.get
                val iqr = bq.p75 - bq.p25
                val s = if (iqr == 0.0) 0.0 else (c.quantiles.get.p50 - bq.p50) / iqr
                (b.name, if (s.isNaN || s.isInfinite) 0.0 else s)
        }

        ProfileDiff(
            columnsAdded = added,
            columnsRemoved = removed,
            typeChanges = typeChanges,
            recordDeltaPct = recordDeltaPct,
            recordDeltaVsMeanOfLastN = vsMean,
            lastN = counts.size,
            nullRateDeltas = nullDeltas,
            quantileShift = shifts,
            dqPassRateDelta = None
        )
    }
}
