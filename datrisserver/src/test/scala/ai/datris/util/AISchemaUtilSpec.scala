package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{AIRefusalException, DatrisException}
import com.google.gson.{JsonObject, JsonParser}
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
  * structure only". On: the prompt is byte-for-byte today's.
  *
  * Story ai-refusal-fallback (plans/stories/ai-refusal-fallback.md), same seam.
  * Pinned type: `ai.datris.model.AIRefusalException(message: String) extends
  * DatrisException`. On-mode (switch at its default): an `ai` that throws
  * `AIRefusalException` yields exactly the fields/config of
  * `buildCsvConfigAllStrings(pipeline, fileContent, delimiter, header)` plus a
  * top-level `"aiDeclined": true`; an `ai` that throws a plain
  * `DatrisException` or `RuntimeException` still propagates. Off-mode is
  * unchanged and never carries `aiDeclined`. */
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

    // ---- Follow-up 2 (2026-10-05): switch off, numbered columns → csvAttributes.header false ----
    //  When CSV schema generation numbers the columns because line 1 is data
    //  (header=false, or header=true but line 1 reads as data), the generated
    //  config's `source.fileAttributes.csvAttributes.header` is false so a
    //  pipeline saved from it does not skip its first data row. Real header
    //  names kept → header stays true.

    private val headerless =
        """ZQX-NAME-1,ZQX-SSN-2,zqx-mail-3@example.com,918273645
          |ZQX-NAME-7,ZQX-SSN-2,zqx-mail-8@example.com,41""".stripMargin

    private val realHeader =
        """full_name,ssn,email,age
          |ZQX-NAME-1,ZQX-SSN-2,zqx-mail-3@example.com,918273645""".stripMargin

    private def parsed(config: String): (List[String], Boolean) = {
        val root = com.google.gson.JsonParser.parseString(config).getAsJsonObject
        val src = root.getAsJsonObject("source")
        val fs = src.getAsJsonObject("schemaProperties").getAsJsonArray("fields")
        val names = (0 until fs.size()).map(i => fs.get(i).getAsJsonObject.get("name").getAsString).toList
        val header = src.getAsJsonObject("fileAttributes").getAsJsonObject("csvAttributes").get("header").getAsBoolean
        (names, header)
    }

    private val numbered = List("column_1", "column_2", "column_3", "column_4")

    test("CSV schema generation (switch off): header=true on a headerless file numbers the columns and sets header false") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing(typedFields)
                val config = AISchemaUtil.buildCsvConfig("people", headerless, ",", true, ai)
                assert(prompts.isEmpty)
                val (names, header) = parsed(config)
                assert(names == numbered, config)
                assert(!header, "csvAttributes.header must be false when line 1 is data: " + config)
                assertNoMarker(config)
                assert(!config.contains("zqx-mail"), config)
            }
        }
    }

    test("CSV schema generation (switch off): header=false numbers the columns and keeps header false") {
        inEnv {
            withheld {
                val (_, ai) = capturing(typedFields)
                val (names, header) = parsed(AISchemaUtil.buildCsvConfig("people", headerless, ",", false, ai))
                assert(names == numbered)
                assert(!header)
            }
        }
    }

    test("CSV schema generation (switch off): a real header keeps its names and header true") {
        inEnv {
            withheld {
                val (_, ai) = capturing(typedFields)
                val (names, header) = parsed(AISchemaUtil.buildCsvConfig("people", realHeader, ",", true, ai))
                assert(names == List("full_name", "ssn", "email", "age"))
                assert(header, "real header names kept → header stays true")
            }
        }
    }

    // ---- Story ai-refusal-fallback (2026-10-05): on-mode, the model declines → all-string fallback ----

    private val declined: String => String =
        _ => throw new AIRefusalException("The model declined this request (stop_reason: refusal). ZQX-PROVIDER-SECRET")

    private def obj(config: String): JsonObject = JsonParser.parseString(config).getAsJsonObject

    test("on-mode CSV generation falls back to all-string fields with aiDeclined when the model declines") {
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("people", csv, ",", true, declined)
                val got = obj(config)
                assert(got.has("aiDeclined") && got.get("aiDeclined").getAsBoolean, "aiDeclined: true in " + config)
                got.remove("aiDeclined")
                val expected = obj(AISchemaUtil.buildCsvConfigAllStrings("people", csv, ",", true))
                assert(got == expected, "same config as buildCsvConfigAllStrings apart from aiDeclined: " + config)
                assert(!config.contains("ZQX-PROVIDER-SECRET"), config)
                assert(!config.contains("valuesWithheld"), config)
            }
        }
    }

    test("on-mode CSV generation still propagates other failures") {
        inEnv {
            sampled {
                val badKey: String => String = _ => throw new DatrisException("AI API returned 401: invalid x-api-key")
                val e = intercept[DatrisException](AISchemaUtil.buildCsvConfig("people", csv, ",", true, badKey))
                assert(!e.isInstanceOf[AIRefusalException])
                assert(e.getMessage.contains("401"), e.getMessage)
                val timeout: String => String = _ => throw new RuntimeException("java.net.SocketTimeoutException: Read timed out")
                val t = intercept[RuntimeException](AISchemaUtil.buildCsvConfig("people", csv, ",", true, timeout))
                assert(t.getMessage.contains("Read timed out"), t.getMessage)
            }
        }
    }

    test("on-mode CSV generation without a decline carries no aiDeclined") {
        inEnv {
            sampled {
                val (_, ai) = capturing(typedFields)
                val config = AISchemaUtil.buildCsvConfig("people", csv, ",", true, ai)
                assert(!obj(config).has("aiDeclined"), config)
            }
        }
    }

    test("off-mode is unchanged: no model call, all-string config, no aiDeclined") {
        inEnv {
            withheld {
                val calls = ListBuffer[String]()
                val ai: String => String = p => { calls += p; throw new AIRefusalException("declined") }
                val config = AISchemaUtil.buildCsvConfig("people", csv, ",", true, ai)
                assert(calls.isEmpty, "no model call with the switch off")
                assert(config == AISchemaUtil.buildCsvConfigAllStrings("people", csv, ",", true), config)
                assert(!obj(config).has("aiDeclined"), config)
            }
        }
    }

    test("JSON Schema and XSD generation keep propagating a decline (no local fallback)") {
        inEnv {
            sampled {
                intercept[AIRefusalException](AISchemaUtil.generateJsonSchema(jsonSample, declined))
                intercept[AIRefusalException](AISchemaUtil.generateXsdSchema(xmlSample, declined))
            }
        }
    }

    test("on-mode decline on a headerless file: numbered columns and header false, as off-mode, no valuesWithheld") {
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("people", headerless, ",", true, declined)
                assert(obj(config).get("aiDeclined").getAsBoolean, config)
                val (names, header) = parsed(config)
                assert(names == numbered, config)
                assert(!header, "csvAttributes.header must be false when line 1 is data: " + config)
                assert(!config.contains("valuesWithheld"), config)
                assertNoMarker(config)
                assert(!config.contains("zqx-mail"), config)
                val (names2, header2) = parsed(AISchemaUtil.buildCsvConfig("people", headerless, ",", false, declined))
                assert(names2 == numbered && !header2)
            }
        }
    }

    test("on-mode decline keeps a mixed real header exactly as buildCsvConfigAllStrings names it, header true") {
        inEnv {
            sampled {
                val mixed = "First Name,order-date,amount\nZQX-NAME-1,ZQX-DATE-9,918273645"
                val config = AISchemaUtil.buildCsvConfig("orders", mixed, ",", true, declined)
                val got = obj(config)
                assert(got.get("aiDeclined").getAsBoolean, config)
                val (names, header) = parsed(config)
                assert(names == List("First Name", "order-date", "amount"), config)
                assert(header, config)
                got.remove("aiDeclined")
                assert(got == obj(AISchemaUtil.buildCsvConfigAllStrings("orders", mixed, ",", true)), config)
                assert(!config.contains("valuesWithheld"), config)
            }
        }
    }

    test("off-mode still numbers non-identifier header cells (strict safe naming unchanged)") {
        inEnv {
            withheld {
                val mixed = "First Name,order-date,amount\nZQX-NAME-1,ZQX-DATE-9,918273645"
                val (names, _) = parsed(AISchemaUtil.buildCsvConfig("orders", mixed, ",", true, declined))
                assert(names.last == "amount" && names.take(2).forall(_.startsWith("column_")), names.toString)
            }
        }
    }

    // ---- E2E finding (2026-10-05): a declined result never has a blank or duplicate field name ----

    private def assertNamesUsable(names: List[String], config: String): Unit = {
        // Unicode-aware: format/control characters and blank "letters" removed,
        // Unicode spaces (non-breaking included) stripped.
        def key(n: String): String =
            n.replaceAll("[\\p{Cf}\\p{Cc}\\u115F\\u1160\\u3164\\uFFA0\\u2800]", "").replaceAll("^[\\p{Z}\\s]+|[\\p{Z}\\s]+$", "").toLowerCase(
                java.util.Locale.ROOT
            )
        assert(names.forall(n => key(n).nonEmpty), "blank field name: " + config)
        assert(names.map(key).distinct.size == names.size, "duplicate field name: " + config)
    }

    test("on-mode decline on a 0-byte file gives column_1, as the off-mode result does, never a blank name") {
        val off = inEnv(withheld(parsed(AISchemaUtil.buildCsvConfig("empty", "", ",", true, declined))))
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("empty", "", ",", true, declined)
                assert(obj(config).get("aiDeclined").getAsBoolean, config)
                val (names, header) = parsed(config)
                assert(names == List("column_1"), config)
                assert((names, header) == off, "same names and header flag as off-mode: " + off + " vs " + config)
                assertNamesUsable(names, config)
            }
        }
    }

    test("on-mode decline: blank header cells take column_N for their position; real names are kept as written") {
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("t", "First Name,,amount,\nZQX-NAME-1,x,918273645,y", ",", true, declined)
                val (names, header) = parsed(config)
                assert(names == List("First Name", "column_2", "amount", "column_4"), config)
                assert(header, "line 1 is still the header: " + config)
                assertNamesUsable(names, config)
            }
        }
    }

    test("on-mode decline: a repeated header name (any case) takes column_N; a numbered name never collides") {
        inEnv {
            sampled {
                val (names, _) = parsed(AISchemaUtil.buildCsvConfig("t", "id,ID,name\n1,2,x", ",", true, declined))
                assert(names == List("id", "column_2", "name"))
                val c2 = AISchemaUtil.buildCsvConfig("t", "a,,column_2\n1,2,3", ",", true, declined)
                val (names2, _) = parsed(c2)
                assert(names2 == List("a", "column_2_2", "column_2"), c2)
                assertNamesUsable(names2, c2)
            }
        }
    }

    // ---- Review round 3: whitespace/invisible cells, newline-only files, escaping ----

    test("on-mode decline: a whitespace-only cell is blank and takes column_N") {
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("t", "name,\" \",amount\nx,y,z", ",", true, declined)
                val (names, header) = parsed(config)
                assert(names == List("name", "column_2", "amount"), config)
                assert(header, config)
                assertNamesUsable(names, config)
            }
        }
    }

    test("on-mode decline: a name that differs only by padding is a duplicate") {
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("t", "id,\" id\",name\nx,y,z", ",", true, declined)
                val (names, _) = parsed(config)
                assert(names == List("id", "column_2", "name"), config)
                assertNamesUsable(names.map(_.trim), config)
            }
        }
    }

    test("on-mode decline: a cell holding only a byte-order mark is blank") {
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("t", "name,\uFEFF,amount\nx,y,z", ",", true, declined)
                val (names, _) = parsed(config)
                assert(names == List("name", "column_2", "amount"), config)
                assert(!config.contains("\uFEFF"), config)
            }
        }
    }

    test("on-mode decline on a newline-only file gives column_1 (no 500), as off-mode does") {
        Seq("\n", "\n\n", "\r\n").foreach { content =>
            val off = inEnv(withheld(parsed(AISchemaUtil.buildCsvConfig("empty", content, ",", true, declined))))
            assert(off._1 == List("column_1"), "off-mode on " + content.map(_.toInt))
            inEnv {
                sampled {
                    val config = AISchemaUtil.buildCsvConfig("empty", content, ",", true, declined)
                    assert(obj(config).get("aiDeclined").getAsBoolean, config)
                    assert(parsed(config) == off, config)
                }
            }
        }
    }

    test("on-mode decline: header names are JSON-escaped and come back exactly as the raw cells") {
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("t", "na\\me,d\te\nx,y", ",", true, declined)
                val (names, header) = parsed(config)
                assert(names == List("na\\me", "d\te"), config)
                assert(header, config)
            }
        }
    }

    // ---- Review round 4: non-breaking/blank-letter cells; JSON-safe name and delimiter ----

    test("on-mode decline: a duplicate padded with a non-breaking space takes column_N") {
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("t", "id,\u00A0id,name\nx,y,z", ",", true, declined)
                val (names, header) = parsed(config)
                assert(names == List("id", "column_2", "name"), config)
                assert(header, config)
                assertNamesUsable(names, config)
            }
        }
    }

    test("on-mode decline: a cell holding only a Hangul filler (renders blank) takes column_N") {
        inEnv {
            sampled {
                val config = AISchemaUtil.buildCsvConfig("t", "name,\u3164,amount\nx,y,z", ",", true, declined)
                val (names, _) = parsed(config)
                assert(names == List("name", "column_2", "amount"), config)
                assertNamesUsable(names, config)
            }
        }
    }

    /** Today's config text (AISchemaUtil.buildConfig + csvAttributes), copied so
      * "byte-identical for ordinary names and delimiters" is checked exactly. */
    private def todaysAllStringsConfig(pipeline: String, fields: Seq[String], delimiter: String, header: Boolean): String = {
        val fieldsJson = fields.map(f => s"""{"name":"$f","type":"string"}""").mkString("[", ",", "]")
        s"""{
           |  "name": "$pipeline",
           |  "source": {
           |    "schemaProperties": {
           |      "fields": $fieldsJson
           |    },
           |    "fileAttributes": {
           |      "csvAttributes": { "delimiter": "$delimiter", "header": $header, "encoding": "UTF-8" }
           |    }
           |  },
           |  "destination": { "database": { "dbName": "DATABASE_NAME", "schema": "SCHEMA_NAME", "table": "TABLE_NAME", "usePostgres": true } }
           |}""".stripMargin
    }

    test("ordinary pipeline names and delimiters (including the \\t tab form) produce byte-identical config text") {
        Seq("," -> "a,b", "|" -> "a|b", ";" -> "a;b", "\\t" -> "a\tb").foreach { case (d, line) =>
            Seq("people", "my_pipeline_2").foreach { name =>
                val got = AISchemaUtil.buildCsvConfigAllStrings(name, line + "\nx", d, true)
                assert(got == todaysAllStringsConfig(name, Seq("a", "b"), d, true), "delimiter " + d + ": " + got)
            }
        }
        inEnv {
            sampled {
                val (_, ai) = capturing(typedFields)
                val got = AISchemaUtil.buildCsvConfig("people", csv, "\\t", true, ai)
                assert(got.contains("\"delimiter\": \"\\t\""), got)
                assert(got.contains("\"name\": \"people\""), got)
            }
        }
    }

    test("on-mode decline with a quote in the pipeline name or the delimiter still returns the fallback (no 500)") {
        inEnv {
            sampled {
                val c1 = AISchemaUtil.buildCsvConfig("a\"b", csv, ",", true, declined)
                val o1 = obj(c1)
                assert(o1.get("aiDeclined").getAsBoolean, c1)
                assert(o1.get("name").getAsString == "a\"b", c1)
                Seq("\"", "\\").foreach { d =>
                    val c = AISchemaUtil.buildCsvConfig("people", "x" + d + "y\n1" + d + "2", d, true, declined)
                    val o = obj(c)
                    assert(o.get("aiDeclined").getAsBoolean, c)
                    val attrs = o.getAsJsonObject("source").getAsJsonObject("fileAttributes").getAsJsonObject("csvAttributes")
                    assert(attrs.get("delimiter").getAsString == d, c)
                }
            }
        }
    }
}
