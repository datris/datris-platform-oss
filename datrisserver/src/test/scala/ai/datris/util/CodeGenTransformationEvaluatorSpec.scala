package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Data, SchemaField}
import org.scalatest.funsuite.AnyFunSuite

/** Header detection (first tests), then story: field protection 9 (plans/stories/field-protection-9-ai-values-switch.md),
  * the samples the CodeGen transformation generator puts in its prompt.
  *
  * Pinned seam (`transform` calls `AIUtil.callAIWithSystem(SYSTEM_PROMPT, userPrompt, cfg)` today):
  * {{{
  * object CodeGenTransformationEvaluator {
  *     private[datris] def transformCsv(instruction: String, data: Data, delimiter: String, pipelineName: String,
  *                                      ai: (String, String) => String): CsvStagedResult
  *     private[datris] def transformRaw(instruction: String, data: Data, isJson: Boolean, pipelineName: String,
  *                                      ai: (String, String) => String): StagedPayload
  * }
  * }}}
  * No default arguments on these overloads (the public ones keep
  * `pipelineName = null`). `ai(systemPrompt, userPrompt)` returns the script
  * text; the spec's function records the prompts and throws, so no script runs.
  *
  * Off: CSV prompt carries the header and "Sample rows withheld by
  * configuration"; raw prompt carries the skeleton. On: byte-for-byte today's. */
class CodeGenTransformationEvaluatorSpec extends AnyFunSuite with AiSampleValuesMarkers {

    private val in = List("id", "first_name", "last_name", "email", "salary")

    test("script emitted a new header: it becomes the header, rows follow") {
        val lines = List("id,first_name,last_name,salary,full_name", "1,Ada,Lovelace,1200.5,Ada Lovelace", "2,Alan,Turing,1300,Alan Turing")
        val r = CodeGenTransformationEvaluator.splitHeader(lines, in, 2, ",")
        assert(r.headerFromScript)
        assert(r.header == List("id", "first_name", "last_name", "salary", "full_name"))
        assert(r.rows.size == 2)
    }

    test("script wrote data only: first row is kept as data and the input header stays") {
        val lines = List("1,Ada,Lovelace,ada@example.com,1200.5", "2,Alan,Turing,alan@example.com,1300")
        val r = CodeGenTransformationEvaluator.splitHeader(lines, in, 2, ",")
        assert(!r.headerFromScript)
        assert(r.header == in)
        assert(r.rows.size == 2)
    }

    test("all-text data with a filter (fewer rows) is not mistaken for a header when the first line recurs") {
        val lines = List("Ada,Lovelace", "Ada,Lovelace", "Grace,Hopper")
        val r = CodeGenTransformationEvaluator.splitHeader(lines, List("first_name", "last_name"), 5, ",")
        assert(!r.headerFromScript)
        assert(r.rows.size == 3)
    }

    test("unchanged header echoed back is recognised even when the row count changed") {
        val lines = List("id,first_name,last_name,email,salary", "1,Ada,Lovelace,ada@example.com,1200.5")
        val r = CodeGenTransformationEvaluator.splitHeader(lines, in, 3, ",")
        assert(r.headerFromScript && r.header == in && r.rows.size == 1)
    }

    test("empty output keeps the input header") {
        val r = CodeGenTransformationEvaluator.splitHeader(Nil, in, 3, ",")
        assert(r.header == in && r.rows.isEmpty)
    }

    test("splitLine honours quotes, doubled quotes and custom delimiters") {
        assert(CodeGenTransformationEvaluator.splitLine("a,\"b,c\",\"d\"\"e\"", ",") == List("a", "b,c", "d\"e"))
        assert(CodeGenTransformationEvaluator.splitLine("x|y|", "|") == List("x", "y", ""))
    }

    // ---- field protection 9: the sample-values switch

    private val header = List("full_name", "ssn", "age", "email")
    private val rows = List(
        "ZQX-NAME-1,ZQX-SSN-2,918273645,ZQX-EMAIL-3",
        "ZQX-NAME-7,ZQX-SSN-2,41,ZQX-EMAIL-3"
    )
    private val instruction = "add a column age_band that buckets age by decade"

    private val json =
        """[{"full_name": "ZQX-NAME-1", "ssn": "ZQX-SSN-2", "age": 918273645, "address": {"city": "ZQX-CITY-4"}},
          | {"full_name": "ZQX-NAME-7", "ssn": "ZQX-SSN-2", "age": 41, "address": {"city": "ZQX-CITY-4"}}]""".stripMargin

    private val xml =
        """<people><person id="ZQX-ID-5"><full_name>ZQX-NAME-1</full_name><ssn>ZQX-SSN-2</ssn></person></people>"""

    private def csvData(): Data = Data(rows.map(_.length.toLong + 1L).sum, header, header.map(SchemaField(_, "string")), rows, null)
    private def rawData(raw: String): Data = Data(raw.length.toLong, null, null, null, raw)

    private def capture(call: ((String, String) => String) => Any): (String, String) = {
        val seen = scala.collection.mutable.ListBuffer[(String, String)]()
        ignoringAfterCapture(call((s, u) => { seen += ((s, u)); throw new PromptCaptured }))
        assert(seen.size == 1, "exactly one model call expected, got " + seen.size)
        seen.head
    }

    test("CodeGen transformation (CSV): with the switch off the prompt contains no marker value") {
        inEnv {
            withheld {
                val (system, user) = capture(ai => CodeGenTransformationEvaluator.transformCsv(instruction, csvData(), ",", null, ai))
                assertNoMarker(system)
                assertNoMarker(user)
                header.foreach(c => assert(user.contains(c), "column " + c + " named: " + user))
                assert(user.contains("Sample rows withheld by configuration"), user)
                assert(user.contains(instruction), user)
            }
        }
    }

    test("CodeGen transformation (raw JSON and XML): with the switch off the prompt contains no marker value") {
        inEnv {
            withheld {
                val (s1, jsonUser) = capture(ai => CodeGenTransformationEvaluator.transformRaw(instruction, rawData(json), true, null, ai))
                assertNoMarker(s1)
                assertNoMarker(jsonUser)
                Seq("full_name", "ssn", "age", "address", "city").foreach(k => assert(jsonUser.contains(k), "key " + k + " kept: " + jsonUser))

                val (s2, xmlUser) = capture(ai => CodeGenTransformationEvaluator.transformRaw(instruction, rawData(xml), false, null, ai))
                assertNoMarker(s2)
                assertNoMarker(xmlUser)
                Seq("people", "person", "full_name", "ssn").foreach(k => assert(xmlUser.contains(k), k + " kept: " + xmlUser))
            }
        }
    }

    test("CodeGen transformation: with the switch on the prompt is unchanged from today") {
        inEnv {
            sampled {
                val (_, csvUser) = capture(ai => CodeGenTransformationEvaluator.transformCsv(instruction, csvData(), ",", null, ai))
                val expectedCsv =
                    s"""Format: CSV (delimiter: ",")
                       |Columns: ${header.mkString(",")}
                       |Sample rows:
                       |${rows.mkString("\n")}
                       |
                       |Transformation: "$instruction"
                       |
                       |The input CSV file has a header row as the first line. Read with the csv module using the appropriate delimiter.
                       |Write a header row FIRST (the output column names, in order), then the data rows. Use the same delimiter.""".stripMargin
                assert(csvUser == expectedCsv)

                val data = rawData(json)
                val (_, jsonUser) = capture(ai => CodeGenTransformationEvaluator.transformRaw(instruction, data, true, null, ai))
                val expectedJson =
                    s"""Format: JSON
                       |Sample data (first 2000 chars):
                       |${CodeGenRuleEvaluator.sampleDocument(data, 2000)}
                       |
                       |Transformation: "$instruction"
                       |
                       |Parse the file as JSON using the json module. Write the transformed JSON to the output file.""".stripMargin
                assert(jsonUser == expectedJson)
                assertHasMarkers(jsonUser, Seq("ZQX-NAME-1", "ZQX-CITY-4"))
            }
        }
    }
}
