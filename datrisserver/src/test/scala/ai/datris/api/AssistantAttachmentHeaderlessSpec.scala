package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.AiSampleValuesMarkers
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets

/** Field protection 9, review follow-up: with the switch off, a headerless
  * CSV attachment never lists its first row as column names, and a JSON map
  * keyed by data never lists its keys. */
class AssistantAttachmentHeaderlessSpec extends AnyFunSuite with AiSampleValuesMarkers {

    test("attachment off: a headerless CSV lists numbered columns and counts every row") {
        withheld {
            val csv = "zqx-a@example.com,918273645,ZQX-ID-5\nzqx-b@example.com,41,ZQX-ID-5"
            val (_, sample) = new AssistantAttachmentController().extractSample("people.csv", csv.getBytes(StandardCharsets.UTF_8))
            assertNoMarker(sample)
            assert(!sample.contains("example.com"), sample)
            assert(sample.contains("column_1, column_2, column_3"), sample)
            assert(sample.contains("Rows: 2"), sample)
        }
    }

    test("attachment off: JSON keyed by data lists <key>, not the keys") {
        withheld {
            val json = """{"zqx-alice@example.com": {"a": "ZQX-NAME-1"}, "zqx-bob@example.com": {"a": "ZQX-NAME-7"}}"""
            val (_, sample) = new AssistantAttachmentController().extractSample("people.json", json.getBytes(StandardCharsets.UTF_8))
            assertNoMarker(sample)
            assert(!sample.contains("example.com"), sample)
            assert(sample.contains("<key>"), sample)
        }
    }

    test("attachment off: a headerless CSV of identifier-shaped values (names, ids) is numbered, line 1 never sent") {
        withheld {
            val csv = "Jane Doe,F,Diabetes mellitus type 2,MRN-12345\nJohn Roe,M,Asthma,MRN-777"
            val (_, sample) = new AssistantAttachmentController().extractSample("patients.csv", csv.getBytes(StandardCharsets.UTF_8))
            Seq("Jane Doe", "Diabetes", "MRN-12345").foreach(v => assert(!sample.contains(v), v + " leaked: " + sample))
            assert(sample.contains("column_1, column_2, column_3, column_4") && sample.contains("Rows: 2"), sample)
        }
    }

    test("attachment off: JSON keyed by person names lists <key>") {
        withheld {
            val json = """{"Jane Doe": {"a": 1}, "John Roe": {"a": 2}}"""
            val (_, sample) = new AssistantAttachmentController().extractSample("p.json", json.getBytes(StandardCharsets.UTF_8))
            assert(!sample.contains("Jane Doe") && !sample.contains("John Roe") && sample.contains("<key>"), sample)
        }
    }
}
