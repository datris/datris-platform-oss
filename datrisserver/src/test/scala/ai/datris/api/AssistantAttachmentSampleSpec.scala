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
  * Off: the sample is the filename, detected type, row count and the header /
  * top-level key names only. On: the sample is today's head of the file. */
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

    test("attachment sample: with the switch off the prompt contains no marker value") {
        withheld {
            val controller = new AssistantAttachmentController
            val (csvType, csvSample) = controller.extractSample("people.csv", bytes(csv))
            assert(csvType == "CSV (structured)", "detected type unchanged")
            assertNoMarker(csvSample)
            Seq("full_name", "ssn", "age", "email").foreach(c => assert(csvSample.contains(c), "column " + c + " listed: " + csvSample))
            assert(csvSample.contains("people.csv"), "filename listed: " + csvSample)
            assert(csvSample.contains("3"), "row count listed: " + csvSample)

            val (jsonType, jsonSample) = controller.extractSample("people.json", bytes(json))
            assert(jsonType == "JSON (structured)")
            assertNoMarker(jsonSample)
            Seq("full_name", "ssn", "address").foreach(k => assert(jsonSample.contains(k), "key " + k + " listed: " + jsonSample))
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
