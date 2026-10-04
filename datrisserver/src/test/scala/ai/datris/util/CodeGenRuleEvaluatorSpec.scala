package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Data, SchemaField}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** Story: field protection 9 (plans/stories/field-protection-9-ai-values-switch.md),
  * the samples the CodeGen data-quality generator puts in its prompt.
  *
  * Pinned seam (`evaluate` calls `AIUtil.callAIWithSystem(SYSTEM_PROMPT, userPrompt, cfg)` today):
  * {{{
  * object CodeGenRuleEvaluator {
  *     private[datris] def evaluateCsv(rule: String, data: Data, delimiter: String,
  *                                     ai: (String, String) => String): List[(Int, String)]
  *     private[datris] def evaluateRaw(rule: String, data: Data, isJson: Boolean,
  *                                     ai: (String, String) => String): List[(Int, String)]
  * }
  * }}}
  * `ai(systemPrompt, userPrompt)` returns the model's extracted text (the
  * script). The public overloads build it from callAIWithSystem + extractText
  * with `DatrisEnvironment.aiConfigForCodegen` and delegate. The spec's AI
  * function records the prompts and then throws, so no script runs here; what
  * the helper does after that is not asserted.
  *
  * Off: CSV prompt carries the header (and schema types) and the line
  * "Sample rows withheld by configuration"; raw prompt carries the
  * `AiSampleValues.jsonSkeleton` / `xmlSkeleton` of the sample.
  * On: the user prompt is byte-for-byte today's. */
class CodeGenRuleEvaluatorSpec extends AnyFunSuite with AiSampleValuesMarkers {

    private val header = List("full_name", "ssn", "age", "email")
    private val rows = List(
        "ZQX-NAME-1,ZQX-SSN-2,918273645,ZQX-EMAIL-3",
        "ZQX-NAME-7,ZQX-SSN-2,41,ZQX-EMAIL-3"
    )
    private val rule = "age must be a positive whole number"

    private val json =
        """[{"full_name": "ZQX-NAME-1", "ssn": "ZQX-SSN-2", "age": 918273645, "address": {"city": "ZQX-CITY-4"}},
          | {"full_name": "ZQX-NAME-7", "ssn": "ZQX-SSN-2", "age": 41, "address": {"city": "ZQX-CITY-4"}}]""".stripMargin

    private val xml =
        """<people><person id="ZQX-ID-5"><full_name>ZQX-NAME-1</full_name><ssn>ZQX-SSN-2</ssn></person></people>"""

    private def csvData(): Data = Data(rows.map(_.length.toLong + 1L).sum, header, header.map(SchemaField(_, "string")), rows, null)
    private def rawData(raw: String): Data = Data(raw.length.toLong, null, null, null, raw)

    private def capture(call: ((String, String) => String) => Any): (String, String) = {
        val seen = ListBuffer[(String, String)]()
        ignoringAfterCapture(call((s, u) => { seen += ((s, u)); throw new PromptCaptured }))
        assert(seen.size == 1, "exactly one model call expected, got " + seen.size)
        seen.head
    }

    test("CodeGen rule (CSV): with the switch off the prompt contains no marker value") {
        inEnv {
            withheld {
                val (system, user) = capture(ai => CodeGenRuleEvaluator.evaluateCsv(rule, csvData(), ",", ai))
                assertNoMarker(system)
                assertNoMarker(user)
                header.foreach(c => assert(user.contains(c), "column " + c + " named: " + user))
                assert(user.contains("Sample rows withheld by configuration"), user)
                assert(user.contains(rule), "the rule is still sent: " + user)
            }
        }
    }

    test("CodeGen rule (raw JSON and XML): with the switch off the prompt contains no marker value") {
        inEnv {
            withheld {
                val (s1, jsonUser) = capture(ai => CodeGenRuleEvaluator.evaluateRaw(rule, rawData(json), isJson = true, ai))
                assertNoMarker(s1)
                assertNoMarker(jsonUser)
                Seq("full_name", "ssn", "age", "address", "city").foreach(k => assert(jsonUser.contains(k), "key " + k + " kept: " + jsonUser))
                assert(jsonUser.contains(rule), jsonUser)

                val (s2, xmlUser) = capture(ai => CodeGenRuleEvaluator.evaluateRaw(rule, rawData(xml), isJson = false, ai))
                assertNoMarker(s2)
                assertNoMarker(xmlUser)
                Seq("people", "person", "full_name", "ssn").foreach(k => assert(xmlUser.contains(k), k + " kept: " + xmlUser))
            }
        }
    }

    test("CodeGen rule: with the switch on the prompt is unchanged from today") {
        inEnv {
            sampled {
                val (_, csvUser) = capture(ai => CodeGenRuleEvaluator.evaluateCsv(rule, csvData(), ",", ai))
                val expectedCsv =
                    s"""Format: CSV (delimiter: ",")
                       |Columns: ${header.mkString(",")}
                       |Sample rows:
                       |${rows.mkString("\n")}
                       |
                       |Rule: "$rule"
                       |
                       |The CSV file has a header row as the first line. Read with the csv module using the appropriate delimiter.""".stripMargin
                assert(csvUser == expectedCsv)
                assertHasMarkers(csvUser, Seq("ZQX-NAME-1", "ZQX-SSN-2"))

                val data = rawData(json)
                val (_, jsonUser) = capture(ai => CodeGenRuleEvaluator.evaluateRaw(rule, data, isJson = true, ai))
                val expectedJson =
                    s"""Format: JSON
                       |Sample data (first 2000 chars):
                       |${CodeGenRuleEvaluator.sampleDocument(data, 2000)}
                       |
                       |Rule: "$rule"
                       |
                       |Parse the file as a JSON array of objects using the json module.""".stripMargin
                assert(jsonUser == expectedJson)
                assertHasMarkers(jsonUser, Seq("ZQX-NAME-1", "ZQX-CITY-4"))
            }
        }
    }
}
