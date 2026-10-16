package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.RunProfiler.{ColumnStats, Quantiles}
import org.scalatest.funsuite.AnyFunSuite

/** Story: Incident evidence model (plans/stories/self-healing-1-evidence-model.md),
  * Acceptance bullet 1 — the pure, pipeline-agnostic run profiler.
  *
  * The 100-row fixture is built so every quantile falls inside a run of equal
  * values: p1/p25/p50/p75/p99 are the same under nearest-rank, linear
  * interpolation or floor-index definitions, so the hand-computed values do not
  * depend on which quantile method the implementation picks. */
class RunProfilerSpec extends AnyFunSuite {

    // amount (sorted): idx 0-9 = 10, 10-39 = 20, 40-59 = 30, 60-89 = 40, 90-99 = 50
    private val amounts: Vector[Int] =
        Vector.fill(10)(10) ++ Vector.fill(30)(20) ++ Vector.fill(20)(30) ++ Vector.fill(30)(40) ++ Vector.fill(10)(50)

    // status: ok x60, late x30, void x10
    private val statuses: Vector[String] = Vector.fill(60)("ok") ++ Vector.fill(30)("late") ++ Vector.fill(10)("void")

    /** A deterministic permutation of 0..99 (37 is coprime with 100), so the
      * profiler cannot rely on input order. */
    private val order: Vector[Int] = (0 until 100).map(i => (i * 37) % 100).toVector

    /** CSV-shaped rows: every value is a string, nulls are explicit. */
    private def csvRows: Iterator[Map[String, Any]] = order.iterator.map { i =>
        Map[String, Any](
            "amount" -> amounts(i).toString,
            "status" -> statuses(i),
            "email" -> "u%03d@example.com".format(i), // 16 chars each
            "code" -> ("c" + (i % 60)), // 60 distinct
            "note" -> (if (i < 20) null else "n" + (i % 4)) // 20 % null
        )
    }

    /** NDJSON-shaped rows: native numbers, and a nullable field that is
      * sometimes null and sometimes absent altogether. */
    private def ndjsonRows: Iterator[Map[String, Any]] = order.iterator.map { i =>
        val base = Map[String, Any]("amount" -> amounts(i), "flag" -> (i % 2 == 0))
        if (i < 10) base + ("note" -> null)
        else if (i < 20) base // key absent
        else base + ("note" -> ("n" + (i % 4)))
    }

    private val notEmail: String => Boolean = _ != "email"

    private def col(stats: RunProfiler.Stats, name: String): ColumnStats =
        stats.columns.find(_.name == name).getOrElse(fail("no column " + name + " in " + stats.columns.map(_.name)))

    test("record count, null rate, distinct and quantiles match hand-computed values on a 100-row fixture") {
        val stats = RunProfiler.profile(csvRows, notEmail)
        assert(stats.recordCount == 100L)

        val amount = col(stats, "amount")
        assert(amount.nullRate == 0.0)
        assert(amount.distinct == 5L)
        assert(amount.quantiles == Some(Quantiles(10.0, 20.0, 30.0, 40.0, 50.0)))

        val note = col(stats, "note")
        assert(math.abs(note.nullRate - 0.20) < 1e-9, note)

        assert(col(stats, "status").distinct == 3L)
        assert(col(stats, "code").distinct == 60L)
        assert(col(stats, "email").distinct == 100L)
    }

    test("a numeric column parsed from strings is inferredType number; text columns are string") {
        val stats = RunProfiler.profile(csvRows, notEmail)
        assert(col(stats, "amount").inferredType == "number")
        assert(col(stats, "status").inferredType == "string")
        assert(col(stats, "email").inferredType == "string")
    }

    test("a column failing valueSafe has topK = None and lengthQuantiles set") {
        val email = col(RunProfiler.profile(csvRows, notEmail), "email")
        assert(email.topK.isEmpty)
        assert(email.lengthQuantiles == Some(Quantiles(16.0, 16.0, 16.0, 16.0, 16.0)))
        assert(email.quantiles.isEmpty, "a string column carries no numeric quantiles")
    }

    test("a safe column with 60 distinct values has topK = None (over the default topKMaxDistinct of 50)") {
        val code = col(RunProfiler.profile(csvRows, notEmail), "code")
        assert(code.distinct == 60L)
        assert(code.topK.isEmpty)
    }

    test("a safe column with 3 distinct values has the 3 (value, count) pairs sorted by count") {
        val status = col(RunProfiler.profile(csvRows, notEmail), "status")
        assert(status.topK == Some(List(("ok", 60L), ("late", 30L), ("void", 10L))))
    }

    test("NDJSON-shaped rows: native numbers profile the same, absent keys count as null, booleans are boolean") {
        val stats = RunProfiler.profile(ndjsonRows, notEmail)
        assert(stats.recordCount == 100L)
        val amount = col(stats, "amount")
        assert(amount.inferredType == "number")
        assert(amount.quantiles == Some(Quantiles(10.0, 20.0, 30.0, 40.0, 50.0)))
        assert(math.abs(col(stats, "note").nullRate - 0.20) < 1e-9)
        assert(col(stats, "flag").inferredType == "boolean")
    }

    test("an empty iterator profiles to zero records without throwing") {
        val stats = RunProfiler.profile(Iterator.empty, notEmail)
        assert(stats.recordCount == 0L)
        assert(stats.columns.isEmpty)
    }
}
