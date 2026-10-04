package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Data, PipelineConfig, SchemaField}
import com.google.gson.{Gson, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** Field protection 9, security review follow-ups: names that are values
  * (headerless files, header=false, maps keyed by data, namespace URIs),
  * NDJSON profiling, failed-run fix suggestions, and the generate flag on
  * pipeline save. */
class AiSampleValuesReviewSpec extends AnyFunSuite with AiSampleValuesMarkers {

    private val headerless =
        """ZQX-NAME-1,ZQX-SSN-2,918273645,ZQX-EMAIL-3
          |ZQX-NAME-7,ZQX-SSN-2,41,ZQX-EMAIL-3""".stripMargin

    private val modelProfile = """{"summary":{"rowCount":2,"columnCount":4,"columns":[]},"qualityIssues":[],"recommendations":[]}"""

    private def capturing(answer: String): (ListBuffer[String], String => String) = {
        val prompts = ListBuffer[String]()
        (prompts, (p: String) => { prompts += p; answer })
    }

    // ---- safeColumnNames

    test("safeColumnNames: header=false numbers every column; non-identifiers become column_N; a data-looking line is all numbered") {
        assert(AiSampleValues.safeColumnNames(List("full_name", "ssn"), header = false) == List("column_1", "column_2"))
        assert(AiSampleValues.safeColumnNames(List("full_name", "First Name", "e-mail"), header = true) == List("full_name", "First Name", "e-mail"))
        assert(
            AiSampleValues.safeColumnNames(List("full_name", "ssn", "zqx@example.com", "123"), header = true) ==
                List("full_name", "ssn", "column_3", "column_4")
        )
        assert(
            AiSampleValues.safeColumnNames(List("ZQX-NAME-1", "ZQX-SSN-2", "918273645"), header = true) ==
                List("ZQX-NAME-1", "ZQX-SSN-2", "column_3"),
            "identifier-shaped cells pass the pattern by design; only the majority rule treats a line as data"
        )
        assert(AiSampleValues.safeColumnNames(List("a@b.c", "918273645", "x"), header = true) == List("column_1", "column_2", "column_3"))
    }

    // ---- CSV schema generation, switch off

    test("CSV schema generation off with header=false: fields are column_1..N, no value becomes a name") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing("[]")
                val config = AISchemaUtil.buildCsvConfig("people", headerless, ",", false, ai)
                assert(prompts.isEmpty, "no model call")
                assertNoMarker(config)
                val fields =
                    JsonParser.parseString(config).getAsJsonObject.getAsJsonObject("source").getAsJsonObject("schemaProperties").getAsJsonArray("fields")
                assert((0 until fields.size).map(i => fields.get(i).getAsJsonObject.get("name").getAsString) == (1 to 4).map("column_" + _), config)
                assert(config.contains("\"header\": false"), config)
            }
        }
    }

    test("CSV schema generation off with header=true on a headerless file with email/number cells: those cells are numbered") {
        inEnv {
            withheld {
                val (_, ai) = capturing("[]")
                val config = AISchemaUtil.buildCsvConfig("people", "zqx-a@example.com,918273645\n1,2", ",", true, ai)
                assert(!config.contains("zqx-a@example.com") && !config.contains("918273645"), config)
                assert(config.contains("column_1") && config.contains("column_2"), config)
            }
        }
    }

    // ---- profile, switch off

    test("profile off: an .ndjson file is skeletonised, its first record is never split as a header") {
        inEnv {
            withheld {
                val ndjson = """{"name":"ZQX-NAME-1","ssn":"ZQX-SSN-2"}""" + "\n" + """{"name":"ZQX-NAME-7","ssn":"ZQX-SSN-2"}"""
                Seq("people.ndjson", "people.jsonl", "people.txt").foreach { fname =>
                    val (prompts, ai) = capturing(modelProfile)
                    val out = AIProfileUtil.profile(ndjson, fname, ",", true, 100, ai)
                    assertNoMarker(prompts.head)
                    assert(prompts.head.contains("\"name\"") && prompts.head.contains("\"ssn\""), fname + ": " + prompts.head)
                    assert(JsonParser.parseString(out).getAsJsonObject.get("valuesWithheld").getAsBoolean)
                }
            }
        }
    }

    test("profile off: a headerless CSV profiled with header=true never sends row 1 as column names") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing(modelProfile)
                AIProfileUtil.profile("zqx-a@example.com,918273645,ZQX-ID-5\nzqx-b@example.com,7,ZQX-ID-5", "people.csv", ",", true, 100, ai)
                assertNoMarker(prompts.head)
                assert(!prompts.head.contains("zqx-a@example.com"), prompts.head)
                assert(prompts.head.contains("column_1") && prompts.head.contains("Rows: 2"), "line 1 is counted as data: " + prompts.head)
            }
        }
    }

    // ---- JSON keys that are data, XML namespace URIs

    test("a JSON map keyed by data collapses to <key>; so does an object with more than 50 keys") {
        val keyed = """{"users":{"zqx-alice@example.com":{"a":"ZQX-NAME-1"},"zqx-bob@example.com":{"a":"ZQX-NAME-7","b":1}}}"""
        val sk = AiSampleValues.jsonSkeleton(keyed)
        assert(!sk.contains("example.com"), sk)
        assertNoMarker(sk)
        val users = JsonParser.parseString(sk).getAsJsonObject.getAsJsonObject("users")
        assert(users.keySet().size == 1 && users.has("<key>"), sk)
        assert(users.getAsJsonObject("<key>").has("a") && users.getAsJsonObject("<key>").has("b"), "value shapes merged: " + sk)

        val wide = (1 to 60).map(i => "\"k" + i + "\":" + i).mkString("{", ",", "}")
        val wideSk = JsonParser.parseString(AiSampleValues.jsonSkeleton(wide)).getAsJsonObject
        assert(wideSk.keySet().size == 1 && wideSk.has("<key>"), wideSk.toString)

        assert(AiSampleValues.jsonTopLevel("""{"zqx-alice@example.com":1}""").map(_._2) == Some(List("<key>")))
        val many = AiSampleValues.jsonTopLevel(wide).get._2
        assert(many.size == 51 && many.last.contains("10 more"), many.toString)
    }

    test("an XML skeleton keeps namespace prefixes and blanks their URIs") {
        val sk = AiSampleValues.xmlSkeleton("""<r xmlns:p="urn:ZQX-NS" xmlns="urn:ZQX-DEFAULT"><p:v>ZQX-NAME-1</p:v></r>""")
        assert(!sk.contains("ZQX"), sk)
        assert(sk.contains("xmlns:p=\"\"") && sk.contains("p:v"), sk)
    }

    // ---- CodeGen Columns line

    test("CodeGen CSV prompt off: a header that is not the stored schema and reads as data is numbered") {
        inEnv {
            withheld {
                val header = List("zqx-a@example.com", "918273645", "ZQX-ID-5")
                val rows = List("zqx-b@example.com,7,ZQX-ID-5")
                val data = Data(10L, header, List(SchemaField("email", "string"), SchemaField("n", "int"), SchemaField("id", "string")), rows, null)
                val seen = ListBuffer[String]()
                ignoringAfterCapture(CodeGenRuleEvaluator.evaluateCsv("n > 0", data, ",", (_, u) => { seen += u; throw new PromptCaptured }))
                assertNoMarker(seen.head)
                assert(!seen.head.contains("example.com"), seen.head)
                assert(seen.head.contains("column_1,column_2,column_3"), seen.head)
            }
        }
    }

    // ---- failed-run fix suggestions

    private val failure =
        """ai.datris.model.DatrisException: Pipeline error: Aborting processing this pipeline, 2 error(s) were found while performing data quality rules:
          |Data quality CodeGen failure, row: 0, reason: ssn ZQX-SSN-2 is not a valid SSN
          |Data quality CodeGen failure, row: 1, reason: name ZQX-NAME-1 is too long
          |	at ai.datris.util.DataQuality.dumpResults(DataQuality.scala:144)
          |	at ai.datris.controller.JobRunner.run(JobRunner.scala:280)
          |Caused by: ai.datris.model.DatrisException: CodeGen validation script failed (exit code 1): Traceback (most recent call last):
          |  File "/tmp/dq_codegen_1.py", line 3, in <module>
          |ValueError: invalid literal for int() with base 10: 'ZQX-ID-5'
          |	... 4 more""".stripMargin

    test("fix suggestion off: DQ reasons and script stderr never reach the prompt; classes, frames and fixed prefixes do") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing("""{"summary":"s","diagnosis":"d","suggestion":"x"}""")
                val fix = FixSuggestionUtil.suggest("pipeline", "{\"name\":\"people\"}", failure, "stdout ZQX-EMAIL-3", ai)
                assert(fix != null && fix.summary == "s")
                val p = prompts.head
                assertNoMarker(p)
                assert(p.contains("ai.datris.model.DatrisException: Pipeline error: Aborting processing this pipeline, 2 error(s)"), p)
                assert(p.contains("CodeGen validation script failed (exit code 1):"), p)
                assert(p.contains("at ai.datris.util.DataQuality.dumpResults(DataQuality.scala:144)"), p)
                assert(p.contains(AiSampleValues.DetailsWithheld), p)
            }
        }
    }

    test("fix suggestion on: the prompt is unchanged (the error goes as it is)") {
        inEnv {
            sampled {
                val (prompts, ai) = capturing("""{"summary":"s"}""")
                FixSuggestionUtil.suggest("pipeline", "{}", failure, null, ai)
                assertHasMarkers(prompts.head, Seq("ZQX-SSN-2", "ZQX-ID-5"))
            }
        }
    }

    test("scrubErrorForModel withholds lines that only look like class names or frames") {
        val s = AiSampleValues.scrubErrorForModel("www.zqx-site.com\nat ZQXNAME(ZQX-SSN-2)\nzqx.Alice: hello\njava.lang.IllegalStateException: ZQX-NAME-1")
        assert(!s.contains("ZQX") && !s.contains("zqx"), s)
        assert(s.contains("java.lang.IllegalStateException: " + AiSampleValues.DetailsWithheld), s)
    }

    // ---- valuesWithheld on pipeline save

    test("a generate response posted back as a pipeline config drops valuesWithheld (Jackson and Gson), never persisted") {
        val generated =
            """{"valuesWithheld": true, "name": "people", "source": {"schemaProperties": {"fields": [{"name":"column_1","type":"string"}]},
              | "fileAttributes": {"csvAttributes": {"delimiter": ",", "header": false, "encoding": "UTF-8"}}},
              | "destination": {"database": {"dbName": "d", "schema": "s", "table": "t", "usePostgres": true}}}""".stripMargin
        // Spring's @RequestBody mapper (ParameterNamesModule, unknown properties ignored).
        val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val viaJackson = mapper.readValue(generated, classOf[PipelineConfig])
        assert(viaJackson.name == "people")
        // PipelineConfigIO persists with Gson.
        assert(!new Gson().toJson(viaJackson).contains("valuesWithheld"))
        val viaGson = new Gson().fromJson(generated, classOf[PipelineConfig])
        assert(!new Gson().toJson(viaGson).contains("valuesWithheld"))
        assert(!classOf[PipelineConfig].getDeclaredFields.exists(_.getName == "valuesWithheld"))
    }
}
