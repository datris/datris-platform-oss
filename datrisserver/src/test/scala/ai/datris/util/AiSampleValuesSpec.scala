package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Story: field protection 9 (plans/stories/field-protection-9-ai-values-switch.md),
  * one switch so no row value reaches a model from the config and code helpers.
  *
  * Pinned symbols:
  * {{{
  * object ai.datris.util.AiSampleValues {
  *     def enabled: Boolean   // read at call time: sys.props "datris.aiSampleValues", else env DATRIS_AI_SAMPLE_VALUES;
  *                            // trimmed + lowercased; only "false" turns it off; unset / unknown = on
  *     def jsonSkeleton(sample: String): String   // same keys and shape; scalars -> "<string>", 0, true, null;
  *                                                // arrays reduced to one element per distinct shape
  *     def xmlSkeleton(sample: String): String    // element + attribute names kept; text and attribute values emptied;
  *                                                // secure parser (no external entities)
  *     def columnStats(header: List[String], rows: Iterator[String], delimiter: String): List[ColumnStat]
  * }
  * ColumnStat(name, inferredType, count, nulls, distinct, minLength, maxLength)  // numeric fields Int or Long
  * }}}
  * ColumnStat is only read through the list `columnStats` returns, so where the
  * case class lives is not pinned. A null is an empty (after trim) value. */
class AiSampleValuesSpec extends AnyFunSuite {

    private val Key = "datris.aiSampleValues"

    private def withSwitch[A](value: Option[String])(body: => A): A = {
        val previous = sys.props.get(Key)
        value match {
            case Some(v) => sys.props(Key) = v
            case None => sys.props -= Key
        }
        try body
        finally previous match {
                case Some(v) => sys.props(Key) = v
                case None => sys.props -= Key
            }
    }

    private val Markers = Seq("ZQX-NAME-1", "ZQX-SSN-2", "ZQX-EMAIL-3", "ZQX-CITY-4", "ZQX-ID-5", "ZQX-NOTE-6", "918273645", "ZQX-NAME-7")

    private def assertNoMarker(text: String): Unit =
        Markers.foreach(m => assert(!text.contains(m), "marker " + m + " survived in:\n" + text))

    test("defaults to on and treats unknown values as on") {
        assume(sys.env.get("DATRIS_AI_SAMPLE_VALUES").isEmpty, "DATRIS_AI_SAMPLE_VALUES is set in this shell")
        withSwitch(None) { assert(AiSampleValues.enabled, "unset must mean on") }
        withSwitch(Some("true")) { assert(AiSampleValues.enabled) }
        withSwitch(Some(" TRUE ")) { assert(AiSampleValues.enabled) }
        withSwitch(Some("maybe")) { assert(AiSampleValues.enabled, "unknown values are on") }
        withSwitch(Some("no")) { assert(AiSampleValues.enabled, "only 'false' turns it off") }
        withSwitch(Some("0")) { assert(AiSampleValues.enabled, "only 'false' turns it off") }
        withSwitch(Some("")) { assert(AiSampleValues.enabled, "empty is on") }
    }

    test("false turns it off") {
        withSwitch(Some("false")) { assert(!AiSampleValues.enabled) }
        withSwitch(Some(" FALSE ")) { assert(!AiSampleValues.enabled, "trimmed and case-insensitive") }
        withSwitch(Some("False")) { assert(!AiSampleValues.enabled) }
        // read at call time, not cached at first use
        withSwitch(Some("false")) {
            assert(!AiSampleValues.enabled)
            sys.props(Key) = "true"
            assert(AiSampleValues.enabled, "the switch must be read at call time")
        }
    }

    test("a JSON skeleton keeps keys and shapes and contains none of the input values") {
        val sample =
            """[
              |  {"full_name": "ZQX-NAME-1", "ssn": "ZQX-SSN-2", "age": 918273645, "active": false, "note": null,
              |   "address": {"city": "ZQX-CITY-4", "zip": "ZQX-ID-5"}, "tags": ["ZQX-NOTE-6", "ZQX-EMAIL-3"]},
              |  {"full_name": "ZQX-NAME-7", "ssn": "ZQX-SSN-2", "age": 918273645, "active": true, "note": null,
              |   "address": {"city": "ZQX-CITY-4", "zip": "ZQX-ID-5"}, "tags": []}
              |]""".stripMargin
        val skeleton = AiSampleValues.jsonSkeleton(sample)
        assertNoMarker(skeleton)

        val parsed = JsonParser.parseString(skeleton)
        assert(parsed.isJsonArray, "same top-level shape: " + skeleton)
        val arr = parsed.getAsJsonArray
        assert(arr.size == 1, "two records of the same shape reduce to one representative: " + skeleton)
        val rec = arr.get(0).getAsJsonObject
        Seq("full_name", "ssn", "age", "active", "note", "address", "tags").foreach(k => assert(rec.has(k), "key " + k + " kept: " + skeleton))
        assert(rec.get("full_name").getAsString == "<string>", skeleton)
        assert(rec.get("age").isJsonPrimitive && rec.get("age").getAsJsonPrimitive.isNumber && rec.get("age").getAsDouble == 0.0, skeleton)
        assert(rec.get("active").isJsonPrimitive && rec.get("active").getAsJsonPrimitive.isBoolean, skeleton)
        assert(rec.get("note").isJsonNull, skeleton)
        val address = rec.get("address").getAsJsonObject
        assert(address.has("city") && address.has("zip"), "nested keys kept: " + skeleton)
        assert(address.get("city").getAsString == "<string>", skeleton)
        assert(rec.get("tags").isJsonArray, skeleton)
        assert(rec.get("tags").getAsJsonArray.size <= 1, "array of strings reduced to one representative: " + skeleton)
    }

    test("a JSON skeleton keeps one element per distinct shape") {
        val sample = """[{"a": "ZQX-NAME-1"}, {"a": "ZQX-NAME-7"}, {"b": "ZQX-SSN-2", "c": 918273645}]"""
        val skeleton = AiSampleValues.jsonSkeleton(sample)
        assertNoMarker(skeleton)
        val arr = JsonParser.parseString(skeleton).getAsJsonArray
        assert(arr.size == 2, "two distinct shapes, two representatives: " + skeleton)
        assert(skeleton.contains("\"a\"") && skeleton.contains("\"b\"") && skeleton.contains("\"c\""), skeleton)
    }

    test("an XML skeleton keeps element and attribute names and contains none of the input text") {
        val sample =
            """<?xml version="1.0" encoding="UTF-8"?>
              |<people>
              |  <person id="ZQX-ID-5" city="ZQX-CITY-4">
              |    <full_name>ZQX-NAME-1</full_name>
              |    <ssn>ZQX-SSN-2</ssn>
              |    <age>918273645</age>
              |    <!-- ZQX-NOTE-6 -->
              |    <note><![CDATA[ZQX-EMAIL-3]]></note>
              |  </person>
              |</people>""".stripMargin
        val skeleton = AiSampleValues.xmlSkeleton(sample)
        assertNoMarker(skeleton)
        Seq("people", "person", "full_name", "ssn", "age", "note").foreach(e => assert(skeleton.contains(e), "element " + e + " kept: " + skeleton))
        assert(skeleton.contains("id=") && skeleton.contains("city="), "attribute names kept: " + skeleton)
    }

    test("an XML skeleton never resolves an external entity") {
        val secret = Files.createTempFile("ai-sample-values-xxe", ".txt")
        try {
            Files.write(secret, "ZQX-SSN-2".getBytes(StandardCharsets.UTF_8))
            val sample =
                s"""<?xml version="1.0"?>
                   |<!DOCTYPE r [ <!ENTITY x SYSTEM "${secret.toUri}"> ]>
                   |<r><v>&x;</v></r>""".stripMargin
            val out =
                try AiSampleValues.xmlSkeleton(sample)
                catch { case _: Exception => "" } // refusing the document is also fine
            assert(!out.contains("ZQX-SSN-2"), "external entity content leaked: " + out)
        } finally Files.deleteIfExists(secret)
    }

    test("column stats contain counts, lengths and types and none of the input values") {
        val header = List("full_name", "ssn", "age", "city")
        val rows = Iterator(
            "ZQX-NAME-1,ZQX-SSN-2,41,ZQX-CITY-4",
            "ZQX-NAME-7,ZQX-SSN-2,918273645,",
            "\"ZQX-NOTE-6, Jr\",ZQX-SSN-2,7,ZQX-CITY-4"
        )
        val stats = AiSampleValues.columnStats(header, rows, ",")
        assert(stats.map(_.name) == header)

        val byName = stats.map(s => s.name -> s).toMap
        val name = byName("full_name")
        assert(name.count.toLong == 3L)
        assert(name.nulls.toLong == 0L)
        assert(name.distinct.toLong == 3L)
        assert(name.minLength.toLong == 10L, "ZQX-NAME-1 is 10 chars")
        assert(name.maxLength.toLong == 14L, "quoted value with an embedded delimiter is one value (ZQX-NOTE-6, Jr)")
        assert(name.inferredType == "string")

        val ssn = byName("ssn")
        assert(ssn.distinct.toLong == 1L && ssn.count.toLong == 3L)

        val age = byName("age")
        assert(age.inferredType == "int", "type comes from DestTypeInference: " + age)
        assert(age.minLength.toLong == 1L && age.maxLength.toLong == 9L)

        val city = byName("city")
        assert(city.nulls.toLong == 1L, "an empty value is a null: " + city)
        assert(city.maxLength.toLong == 10L)

        stats.foreach { s =>
            s.productIterator.foreach(v => assertNoMarker(String.valueOf(v)))
            assertNoMarker(s.toString)
        }
    }
}
