package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** Story: field protection 9 (plans/stories/field-protection-9-ai-values-switch.md),
  * schema generation from an uploaded file and from a pasted sample.
  *
  * Pinned seam (the helpers call `AIUtil.callAI(prompt, cfg)` today, no system
  * prompt, so the injected function takes the one prompt and returns the model's
  * extracted text):
  * {{{
  * object AISchemaUtil {
  *     private[datris] def buildCsvConfig(pipeline: String, fileContent: String, delimiter: String, header: Boolean,
  *                                        ai: String => String): String
  *     private[datris] def generateJsonSchema(sampleData: String, ai: String => String): String
  *     private[datris] def generateXsdSchema(sampleData: String, ai: String => String): String
  * }
  * }}}
  * The public overloads build `ai` from `DatrisEnvironment.aiConfigForCodegen`
  * (callAI + extractText) and delegate. The switch is `AiSampleValues.enabled`
  * (system property `datris.aiSampleValues`, set and restored here).
  *
  * Off: CSV makes no model call and returns `buildCsvConfigAllStrings(...)`;
  * JSON Schema / XSD get the skeleton plus "values withheld; infer from
  * structure only". On: the prompt is byte-for-byte today's. */
class AISchemaUtilSpec extends AnyFunSuite with AiSampleValuesMarkers {

    private val csv =
        """full_name,ssn,age,email
          |ZQX-NAME-1,ZQX-SSN-2,918273645,ZQX-EMAIL-3
          |ZQX-NAME-7,ZQX-SSN-2,41,ZQX-EMAIL-3""".stripMargin

    private val jsonSample =
        """[{"full_name": "ZQX-NAME-1", "ssn": "ZQX-SSN-2", "age": 918273645,
          |  "address": {"city": "ZQX-CITY-4", "zip": "ZQX-ID-5"}},
          | {"full_name": "ZQX-NAME-7", "ssn": "ZQX-SSN-2", "age": 41,
          |  "address": {"city": "ZQX-CITY-4", "zip": "ZQX-ID-5"}}]""".stripMargin

    private val xmlSample =
        """<people>
          |  <person id="ZQX-ID-5"><full_name>ZQX-NAME-1</full_name><ssn>ZQX-SSN-2</ssn><age>918273645</age></person>
          |  <person id="ZQX-ID-5"><full_name>ZQX-NAME-7</full_name><ssn>ZQX-SSN-2</ssn><age>41</age></person>
          |</people>""".stripMargin

    /** Today's CSV prompt (AISchemaUtil.buildCsvPrompt), copied so "unchanged" is checked byte for byte. */
    private def todaysCsvPrompt(content: String, delimiter: String): String =
        s"""You are a data schema expert. Analyze the following delimited file content and return ONLY a JSON array of field definitions.
           |Use this exact format: [{"name": "column_name", "type": "data_type"}, ...]
           |Valid types are: boolean, int, bigint, float, double, string, date, timestamp.
           |The delimiter is: $delimiter
           |Return only the JSON array with no explanation, no markdown, and no code fences.
           |
           |File content:
           |$content""".stripMargin

    private def todaysJsonSchemaPrompt(sample: String): String =
        s"""You are a JSON Schema expert. Generate a JSON Schema (Draft 4) for validating the following JSON data.
           |The schema MUST use "$$schema": "http://json-schema.org/draft-04/schema#".
           |Include type constraints, required fields, and format validations where appropriate.
           |Return ONLY the JSON Schema, no explanation, no markdown, no code fences.
           |
           |Sample data:
           |$sample""".stripMargin

    private def todaysXsdPrompt(sample: String): String =
        s"""You are an XML Schema expert. Generate a W3C XML Schema (XSD) for validating the following XML data.
           |Include element definitions, type constraints, and required attributes.
           |Return ONLY the XSD, no explanation, no markdown, no code fences.
           |
           |Sample data:
           |$sample""".stripMargin

    private val typedFields =
        """[{"name":"full_name","type":"string"},{"name":"ssn","type":"string"},{"name":"age","type":"bigint"},{"name":"email","type":"string"}]"""

    private val xsd = """<?xml version="1.0"?><xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"/>"""

    private def capturing(answer: String): (ListBuffer[String], String => String) = {
        val prompts = ListBuffer[String]()
        (prompts, (p: String) => { prompts += p; answer })
    }

    // ---- CSV schema generation

    test("CSV schema generation: with the switch off the prompt contains no marker value (and no model call is made)") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing(typedFields)
                val config = AISchemaUtil.buildCsvConfig("people", csv, ",", true, ai)
                assert(prompts.isEmpty, "no model call with the switch off; got: " + prompts.mkString("\n---\n"))
                prompts.foreach(assertNoMarker)
                assert(config == AISchemaUtil.buildCsvConfigAllStrings("people", csv, ",", true), "all-string config from the header")
                assertNoMarker(config)
            }
        }
    }

    test("CSV schema generation: with the switch on the prompt is unchanged from today") {
        inEnv {
            sampled {
                val (prompts, ai) = capturing(typedFields)
                val config = AISchemaUtil.buildCsvConfig("people", csv, ",", true, ai)
                assert(prompts.size == 1)
                assert(prompts.head == todaysCsvPrompt(csv, ","))
                assertHasMarkers(prompts.head, Seq("ZQX-NAME-1", "ZQX-SSN-2"))
                assert(config.contains("\"bigint\""), "the model's types are used: " + config)
                assert(!config.contains("valuesWithheld"), config)
            }
        }
    }

    // ---- JSON Schema

    test("JSON Schema generation: with the switch off the prompt contains no marker value") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing("""{"$schema":"http://json-schema.org/draft-04/schema#","type":"array"}""")
                val schema = AISchemaUtil.generateJsonSchema(jsonSample, ai)
                assert(prompts.size == 1, "the model still builds the schema, from the skeleton")
                val p = prompts.head
                assertNoMarker(p)
                Seq("full_name", "ssn", "age", "address", "city", "zip").foreach(k => assert(p.contains(k), "key " + k + " kept: " + p))
                assert(p.toLowerCase.contains("values withheld"), "prompt says values are withheld: " + p)
                assert(p.toLowerCase.contains("infer from structure only"), p)
                assert(schema.contains("draft-04"), schema)
            }
        }
    }

    test("JSON Schema generation: with the switch on the prompt is unchanged from today") {
        inEnv {
            sampled {
                val (prompts, ai) = capturing("""{"type":"array"}""")
                AISchemaUtil.generateJsonSchema(jsonSample, ai)
                assert(prompts.size == 1)
                assert(prompts.head == todaysJsonSchemaPrompt(jsonSample))
                assertHasMarkers(prompts.head, Seq("ZQX-NAME-1", "ZQX-CITY-4"))
            }
        }
    }

    // ---- XSD

    test("XSD generation: with the switch off the prompt contains no marker value") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing(xsd)
                val out = AISchemaUtil.generateXsdSchema(xmlSample, ai)
                assert(prompts.size == 1)
                val p = prompts.head
                assertNoMarker(p)
                Seq("people", "person", "full_name", "ssn", "age", "id=").foreach(k => assert(p.contains(k), k + " kept: " + p))
                assert(p.toLowerCase.contains("values withheld"), p)
                assert(p.toLowerCase.contains("infer from structure only"), p)
                assert(out.contains("xs:schema"), out)
            }
        }
    }

    test("XSD generation: with the switch on the prompt is unchanged from today") {
        inEnv {
            sampled {
                val (prompts, ai) = capturing(xsd)
                AISchemaUtil.generateXsdSchema(xmlSample, ai)
                assert(prompts.size == 1)
                assert(prompts.head == todaysXsdPrompt(xmlSample))
                assertHasMarkers(prompts.head, Seq("ZQX-NAME-1", "ZQX-ID-5"))
            }
        }
    }
}
