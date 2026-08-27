package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.SchemaField
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

/** The widening lattice is the safety boundary of destination-side typing: a
  * type it emits must hold for every value it saw. Each rule that keeps a
  * column `string` (leading zeros, over-Long integers, mixed kinds) exists
  * because the typed column would otherwise break a real landed value. */
class DestTypeInferenceSpec extends AnyFunSuite {

    private def col(values: String*): String = DestTypeInference.inferColumn(values)

    // ---- classify / single-column lattice ----

    test("integers within Int range infer int") {
        assert(col("1", "42", "-7", "+5") == "int")
    }

    test("integers beyond Int range widen to bigint") {
        assert(col("1", "3000000000") == "bigint")
    }

    test("integers beyond Long stay string (double would lose precision)") {
        assert(col("99999999999999999999999") == "string")
    }

    test("decimals infer double; ints mixed with decimals widen to double") {
        assert(col("1.5", ".5", "2.") == "double")
        assert(col("1", "2.5") == "double")
        assert(col("1e5", "2.5E-3") == "double")
    }

    test("booleans infer boolean, case-insensitively") {
        assert(col("true", "FALSE", "True") == "boolean")
    }

    test("boolean mixed with anything else is string") {
        assert(col("true", "1") == "string")
        assert(col("false", "2026-01-01") == "string")
    }

    test("ISO dates infer date; invalid calendar dates stay string") {
        assert(col("2026-01-31", "1999-12-01") == "date")
        assert(col("2026-13-45") == "string")
    }

    test("ISO timestamps infer timestamp; date+timestamp widens to timestamp") {
        assert(col("2026-01-31T10:15:30", "2026-01-31 10:15:30.123", "2026-01-31T10:15:30Z") == "timestamp")
        assert(col("2026-01-31", "2026-01-31T10:15:30") == "timestamp")
    }

    test("date or timestamp mixed with numerics is string") {
        assert(col("2026-01-31", "42") == "string")
    }

    test("leading-zero integers (zip codes, ids) stay string") {
        assert(col("07030", "10001") == "string")
        // a single zero is a number, not an identifier
        assert(col("0", "1") == "int")
    }

    test("empty and null values don't vote; all-empty column is string") {
        assert(col("", "5", null, "7") == "int")
        assert(col("", "", "") == "string")
        assert(col() == "string")
    }

    test("free text is string and poisons any typed column") {
        assert(col("hello") == "string")
        assert(col("1", "2", "N/A") == "string")
    }

    // ---- evidence: samples + string reason ----

    private def evidence(values: String*): (String, List[String], DestStringReason) = {
        val (t, samples, reason) = DestTypeInference.inferColumnWithEvidence(values)
        (t, samples.asScala.toList, reason)
    }

    test("evidence agrees with inferColumn and collects distinct samples in order") {
        val (t, samples, reason) = evidence("1", "2", "1", "3")
        assert(t == "int")
        assert(samples == List("1", "2", "3"))
        assert(reason == null)
    }

    test("evidence caps samples at 5 distinct values and skips empties") {
        val (t, samples, _) = evidence("", "1", "2", null, "3", "4", "5", "6", "7")
        assert(t == "int")
        assert(samples == List("1", "2", "3", "4", "5"))
    }

    test("evidence truncates long values") {
        val long = "x" * 200
        val (_, samples, _) = evidence(long)
        assert(samples.head.length == DestTypeInference.maxSampleLength + 1) // + ellipsis
        assert(samples.head.endsWith("…"))
    }

    test("a dirty value blocking an otherwise-typed column yields a reason") {
        val (t, _, reason) = evidence("1", "2", "N/A", "3")
        assert(t == "string")
        assert(reason == DestStringReason("N/A", "int"))
    }

    test("the reason reports the first dirty value even when it comes first") {
        val (t, _, reason) = evidence("N/A", "1.5", "2")
        assert(t == "string")
        assert(reason == DestStringReason("N/A", "double"))
    }

    test("no reason when the column is plain text or mixed typed kinds") {
        // plain text: nothing typed was blocked
        assert(evidence("hello", "world")._3 == null)
        // mixed kinds (boolean vs int) widen to string without a dirty value
        val (t, _, reason) = evidence("true", "1")
        assert(t == "string")
        assert(reason == null)
    }

    // ---- field assembly ----

    test("inferFieldsFromRecords matches names case-insensitively, stringifies values, and never retypes _json/_xml") {
        val fields = DestTypeInference.inferFieldsFromRecords(
            List("id", "active", "_json"),
            Seq(
                Map[String, Any]("ID" -> "3", "Active" -> "true", "_json" -> "{\"a\":1}"),
                Map[String, Any]("id" -> "4", "active" -> "false", "_json" -> "{\"b\":2}")
            )
        ).asScala.toList
        assert(fields.map(f => f.name -> f.`type`) ==
            List("id" -> "int", "active" -> "boolean", "_json" -> "string"))
        // _json keeps its samples but never carries a reason
        assert(fields(2).samples.asScala.nonEmpty)
        assert(fields(2).stringReason == null)
    }

    test("inferFieldsFromRecords attaches per-column evidence") {
        val fields = DestTypeInference.inferFieldsFromRecords(
            List("qty"),
            Seq(Map[String, Any]("qty" -> "1"), Map[String, Any]("qty" -> "N/A"))
        ).asScala.toList
        assert(fields.head.`type` == "string")
        assert(fields.head.stringReason == DestStringReason("N/A", "int"))
        assert(fields.head.samples.asScala.toList == List("1", "N/A"))
    }

    // ---- helpers used by propose/apply ----

    test("allString and hasTypedField") {
        val allStr = List(SchemaField("a", "string"), SchemaField("b", "STRING")).asJava
        val typed = List(SchemaField("a", "string"), SchemaField("b", "int")).asJava
        assert(DestTypeInference.allString(allStr))
        assert(!DestTypeInference.allString(typed))
        assert(!DestTypeInference.hasTypedField(allStr))
        assert(DestTypeInference.hasTypedField(typed))
        assert(!DestTypeInference.allString(new java.util.ArrayList[SchemaField]()))
    }
}
