package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** Story: field protection 9 (plans/stories/field-protection-9-ai-values-switch.md),
  * AI data profiling.
  *
  * Pinned seam (`profile` calls `AIUtil.callAI(prompt)` today, no system prompt):
  * {{{
  * object AIProfileUtil {
  *     private[datris] def profile(fileContent: String, filename: String, delimiter: String, header: Boolean,
  *                                 sampleSize: Int, ai: String => String): String
  * }
  * }}}
  * `ai(prompt)` returns the model's extracted text. The public overload builds
  * it from callAI + extractText and delegates. The spec runs inside a tenant
  * environment with `aiEnabled = true`, so the `ai.enabled` check may stay in
  * either overload.
  *
  * Off: the prompt carries the `AiSampleValues.columnStats` table (CSV) or a
  * value-free skeleton (JSON/XML), asks for empty `sampleValues`, and the
  * returned JSON object gains top-level `"valuesWithheld": true`.
  * On: the prompt and the returned text are byte-for-byte today's. */
class AIProfileUtilSpec extends AnyFunSuite with AiSampleValuesMarkers {

    private val csv =
        """full_name,ssn,age,email
          |ZQX-NAME-1,ZQX-SSN-2,918273645,ZQX-EMAIL-3
          |ZQX-NAME-7,ZQX-SSN-2,41,ZQX-EMAIL-3
          |ZQX-NOTE-6,ZQX-SSN-2,7,""".stripMargin

    private val json =
        """[{"full_name": "ZQX-NAME-1", "ssn": "ZQX-SSN-2", "address": {"city": "ZQX-CITY-4"}},
          | {"full_name": "ZQX-NAME-7", "ssn": "ZQX-SSN-2", "address": {"city": "ZQX-CITY-4"}}]""".stripMargin

    private val modelAnswer =
        """{"summary":{"rowCount":3,"columnCount":4,"columns":[{"name":"full_name","inferredType":"string","nullCount":0,"uniqueCount":3,"sampleValues":[]}]},"qualityIssues":[],"recommendations":[]}"""

    /** Today's prompt (AIProfileUtil.profile), copied so "unchanged" is checked byte for byte. */
    private def todaysPrompt(formatDescription: String, content: String): String =
        s"""You are a data profiling expert. Analyze the following $formatDescription file and return a JSON profile.
           |
           |Return ONLY a JSON object with no explanation, no markdown, and no code fences. Use this structure:
           |{
           |  "summary": {
           |    "rowCount": <number of data rows>,
           |    "columnCount": <number of columns>,
           |    "columns": [
           |      {
           |        "name": "<column name>",
           |        "inferredType": "<string|integer|float|boolean|date|timestamp>",
           |        "nullCount": <number of null/empty values>,
           |        "uniqueCount": <approximate unique values>,
           |        "sampleValues": ["<up to 3 sample values>"]
           |      }
           |    ]
           |  },
           |  "qualityIssues": [
           |    "<description of each issue found, e.g. missing values, outliers, inconsistent formats>"
           |  ],
           |  "recommendations": [
           |    "<suggested validation rules or transformations>"
           |  ],
           |  "suggestedDataQuality": {
           |    "aiRule": {
           |      "instruction": "<a single natural language instruction combining ALL validation checks — structural patterns (emails, phone numbers, zip codes, dates), value ranges, cross-column relationships, and business logic>",
           |      "onFailureIsError": false
           |    }
           |  }
           |}
           |
           |For suggestedDataQuality:
           |- The aiRule instruction should be a comprehensive plain-English rule covering all validations: format checks, value ranges, cross-column relationships, and business logic.
           |- Combine all checks into one instruction. Datris will generate a Python validation script from this instruction.
           |- If no validation rule is appropriate, omit the aiRule field.
           |
           |$content""".stripMargin

    private def capturing(): (ListBuffer[String], String => String) = {
        val prompts = ListBuffer[String]()
        (prompts, (p: String) => { prompts += p; modelAnswer })
    }

    test("profile (CSV): with the switch off the prompt contains no marker value") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing()
                val out = AIProfileUtil.profile(csv, "people.csv", ",", true, 100, ai)
                assert(prompts.size == 1, "profiling still asks the model, from statistics")
                val p = prompts.head
                assertNoMarker(p)
                Seq("full_name", "ssn", "age", "email").foreach(c => assert(p.contains(c), "column " + c + " named: " + p))
                assert(p.contains("sampleValues"), p)
                val obj = JsonParser.parseString(out).getAsJsonObject
                assert(obj.has("valuesWithheld") && obj.get("valuesWithheld").getAsBoolean, "valuesWithheld: true in " + out)
                assert(obj.has("summary"), "the model's profile is still returned: " + out)
                assertNoMarker(out)
            }
        }
    }

    test("profile (JSON): with the switch off the prompt contains no marker value") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing()
                val out = AIProfileUtil.profile(json, "people.json", ",", true, 100, ai)
                assert(prompts.size == 1)
                assertNoMarker(prompts.head)
                Seq("full_name", "ssn", "address", "city").foreach(k => assert(prompts.head.contains(k), "key " + k + " kept: " + prompts.head))
                assert(JsonParser.parseString(out).getAsJsonObject.get("valuesWithheld").getAsBoolean, out)
            }
        }
    }

    test("profile: with the switch on the prompt is unchanged from today") {
        inEnv {
            sampled {
                val (prompts, ai) = capturing()
                val out = AIProfileUtil.profile(csv, "people.csv", ",", true, 100, ai)
                assert(prompts.size == 1)
                assert(prompts.head == todaysPrompt("CSV (delimiter: \",\")", csv))
                assertHasMarkers(prompts.head, Seq("ZQX-NAME-1", "ZQX-SSN-2", "ZQX-EMAIL-3"))
                assert(out == modelAnswer, "response unchanged (no valuesWithheld): " + out)

                val (jsonPrompts, jsonAi) = capturing()
                AIProfileUtil.profile(json, "people.json", ",", true, 100, jsonAi)
                assert(jsonPrompts.head == todaysPrompt("JSON", json))
            }
        }
    }

    // ---- Follow-up 1 (2026-10-05): switch off, the model call throws → statistics-only fallback ----
    //  Same shape as the unparseable-reply fallback: local `summary.columns`,
    //  empty `qualityIssues` / `recommendations`, `valuesWithheld: true`, and a
    //  `note` naming the cause without echoing the provider's text. On-mode
    //  still propagates the failure.

    private val providerText = "The model declined this request (stop_reason: refusal) ZQX-PROVIDER-SECRET"

    private def assertStatisticsOnly(out: String, expectColumns: Seq[String]): Unit = {
        val obj = JsonParser.parseString(out).getAsJsonObject
        assert(obj.has("valuesWithheld") && obj.get("valuesWithheld").getAsBoolean, out)
        val cols = obj.getAsJsonObject("summary").getAsJsonArray("columns")
        val names = (0 until cols.size()).map(i => cols.get(i).getAsJsonObject.get("name").getAsString)
        assert(names == expectColumns, "local statistics columns: " + out)
        (0 until cols.size()).foreach(i =>
            assert(cols.get(i).getAsJsonObject.getAsJsonArray("sampleValues").size() == 0, out)
        )
        assert(obj.getAsJsonArray("qualityIssues").size() == 0, out)
        assert(obj.getAsJsonArray("recommendations").size() == 0, out)
        assert(obj.has("note") && obj.get("note").isJsonPrimitive && obj.get("note").getAsString.nonEmpty, "note string: " + out)
        val note = obj.get("note").getAsString
        assert(
            !note.contains("ZQX-PROVIDER-SECRET") && !note.contains("declined this request") && !note.contains("stop_reason"),
            "note must not echo provider text: " + note
        )
        assert(!out.contains("ZQX-PROVIDER-SECRET"), out)
        assertNoMarker(out)
    }

    test("profile (CSV, switch off): a model refusal falls back to local statistics without throwing") {
        inEnv {
            withheld {
                val ai: String => String = _ => throw new DatrisException(providerText)
                val out = AIProfileUtil.profile(csv, "people.csv", ",", true, 100, ai)
                assertStatisticsOnly(out, Seq("full_name", "ssn", "age", "email"))
                val summary = JsonParser.parseString(out).getAsJsonObject.getAsJsonObject("summary")
                assert(summary.get("rowCount").getAsInt == 3, out)
            }
        }
    }

    test("profile (CSV, switch off): a provider timeout falls back to local statistics without throwing") {
        inEnv {
            withheld {
                val ai: String => String = _ => throw new RuntimeException("java.net.SocketTimeoutException: Read timed out ZQX-PROVIDER-SECRET")
                val out = AIProfileUtil.profile(csv, "people.csv", ",", true, 100, ai)
                assertStatisticsOnly(out, Seq("full_name", "ssn", "age", "email"))
            }
        }
    }

    test("profile (JSON, switch off): a throwing model call still returns the fallback (no local column stats)") {
        inEnv {
            withheld {
                val ai: String => String = _ => throw new DatrisException(providerText)
                val out = AIProfileUtil.profile(json, "people.json", ",", true, 100, ai)
                assertStatisticsOnly(out, Seq.empty)
            }
        }
    }

    test("profile (switch on): a throwing model call still propagates (unchanged)") {
        inEnv {
            sampled {
                val ai: String => String = _ => throw new DatrisException(providerText)
                val e = intercept[DatrisException](AIProfileUtil.profile(csv, "people.csv", ",", true, 100, ai))
                assert(e.getMessage.contains("declined this request"), e.getMessage)
                val timeout: String => String = _ => throw new RuntimeException("Read timed out")
                intercept[RuntimeException](AIProfileUtil.profile(csv, "people.csv", ",", true, 100, timeout))
            }
        }
    }
}
