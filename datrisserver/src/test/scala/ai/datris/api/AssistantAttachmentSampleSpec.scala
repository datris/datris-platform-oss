package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.AiSampleValuesMarkers
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets

/** Story: field protection 9 (plans/stories/field-protection-9-ai-values-switch.md),
  * the sample an Assistant attachment hands to the chat.
  *
  * Pinned seam: the existing helper becomes visible to specs, no other change
  * to its shape:
  * {{{
  * class AssistantAttachmentController {
  *     private[datris] def extractSample(filename: String, bytes: Array[Byte]): (String, String)  // (detectedType, sample)
  * }
  * }}}
  * Off: the sample is the filename, detected type, row count and names only.
  * For CSV the attachment endpoint has no header option, so line 1 is NEVER
  * printed (decision 2026-10-05, after live e2e showed a headerless file's
  * first row reaching the model): columns are always `column_1..N` and every
  * line counts as a row. JSON lists top-level key names. On: the sample is
  * today's head of the file. */
class AssistantAttachmentSampleSpec extends AnyFunSuite with AiSampleValuesMarkers {

    private val csv =
        """full_name,ssn,age,email
          |ZQX-NAME-1,ZQX-SSN-2,918273645,ZQX-EMAIL-3
          |ZQX-NAME-7,ZQX-SSN-2,41,ZQX-EMAIL-3
          |ZQX-NOTE-6,ZQX-SSN-2,7,ZQX-CITY-4""".stripMargin

    private val json =
        """[{"full_name": "ZQX-NAME-1", "ssn": "ZQX-SSN-2", "address": {"city": "ZQX-CITY-4"}},
          | {"full_name": "ZQX-NAME-7", "ssn": "ZQX-SSN-2", "address": {"city": "ZQX-CITY-4"}}]""".stripMargin

    private def bytes(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

    /** `column_1 .. column_n` listed, and no `column_(n+1)`. */
    private def assertNumberedColumnsOnly(sample: String, n: Int): Unit = {
        (1 to n).foreach(i => assert(sample.contains("column_" + i), "column_" + i + " listed: " + sample))
        assert(!sample.contains("column_" + (n + 1)), "only " + n + " columns: " + sample)
    }

    /** Every line counts as a row: "<n> rows" / "rows: <n>" style, and no other row count. */
    private def assertRowCount(sample: String, n: Int): Unit = {
        val rowCount = ("(?i)(\\b" + n + "\\s+(data\\s+)?(rows?|lines?)\\b|\\b(rows?|lines?)\\b\\W{0,3}" + n + "\\b)").r
        assert(rowCount.findFirstIn(sample).isDefined, "row count " + n + " (every line is a row) listed: " + sample)
        val wrong = ("(?i)(\\b" + (n - 1) + "\\s+(data\\s+)?(rows?|lines?)\\b|\\b(rows?|lines?)\\b\\W{0,3}" + (n - 1) + "\\b)").r
        assert(wrong.findFirstIn(sample).isEmpty, "line 1 must not be dropped as a header: " + sample)
    }

    test("attachment sample: with the switch off the prompt contains no marker value") {
        withheld {
            val controller = new AssistantAttachmentController
            val (csvType, csvSample) = controller.extractSample("people.csv", bytes(csv))
            assert(csvType == "CSV (structured)", "detected type unchanged")
            assertNoMarker(csvSample)
            assertNumberedColumnsOnly(csvSample, 4)
            Seq("full_name", "ssn", "age", "email").foreach(c =>
                assert(!("\\b" + c + "\\b").r.findFirstIn(csvSample).isDefined, "line 1 (header cell " + c + ") must never be printed: " + csvSample)
            )
            assert(csvSample.contains("people.csv"), "filename listed: " + csvSample)
            assertRowCount(csvSample, 4)

            val (jsonType, jsonSample) = controller.extractSample("people.json", bytes(json))
            assert(jsonType == "JSON (structured)")
            assertNoMarker(jsonSample)
            Seq("full_name", "ssn", "address").foreach(k => assert(jsonSample.contains(k), "key " + k + " listed: " + jsonSample))
        }
    }

    test("attachment sample: a headerless CSV with the switch off yields numbered columns and none of its first-row values") {
        withheld {
            val headerless =
                """ZQX-NAME-1,ZQX-SSN-2,zqx-mail-3@example.com,918273645
                  |ZQX-NAME-7,ZQX-SSN-2,zqx-mail-3@example.com,41""".stripMargin
            val (csvType, sample) = new AssistantAttachmentController().extractSample("rows.csv", bytes(headerless))
            assert(csvType == "CSV (structured)")
            assertNoMarker(sample)
            Seq("ZQX-NAME-1", "ZQX-SSN-2", "zqx-mail-3@example.com", "918273645").foreach(v =>
                assert(!sample.toLowerCase.contains(v.toLowerCase), "first-row value " + v + " reached the sample: " + sample)
            )
            assert(!sample.toLowerCase.contains("zqx"), "no part of a value: " + sample)
            assertNumberedColumnsOnly(sample, 4)
            assertRowCount(sample, 2)
        }
    }

    test("attachment sample: with the switch on the prompt is unchanged from today") {
        sampled {
            val controller = new AssistantAttachmentController
            val (csvType, csvSample) = controller.extractSample("people.csv", bytes(csv))
            assert(csvType == "CSV (structured)")
            assert(csvSample == csv, "today: the first lines of the file as-is")
            val (_, jsonSample) = controller.extractSample("people.json", bytes(json))
            assert(jsonSample == json)
        }
    }
}
