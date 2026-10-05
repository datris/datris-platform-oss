package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Data, PipelineConfig, SchemaField}
import com.google.gson.{Gson, JsonParser}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._
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
        assert(AiSampleValues.safeColumnNames(List("full_name", "first name", "e-mail"), header = true) == List("full_name", "first name", "column_3"))
        assert(
            AiSampleValues.safeColumnNames(List("full_name", "ssn", "zqx@example.com", "x"), header = true) == (1 to 4).map("column_" + _).toList,
            "an @ anywhere makes the whole line data"
        )
        assert(AiSampleValues.safeColumnNames(List("ZQX-NAME-1", "ZQX-SSN-2", "918273645"), header = true) == (1 to 3).map("column_" + _).toList)
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
                Seq("people.ndjson", "people.jsonl", "people.data", "people").foreach { fname =>
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
        assert(AiSampleValues.jsonTopLevel(wide).map(_._2) == Some(List("<key>")), "one object with more than 50 keys is a map")
        // records whose key union passes 50 are capped at 50 names
        val recs = Seq((1 to 30).map(i => "\"a" + i + "\":" + i).mkString("{", ",", "}"), (1 to 30).map(i => "\"b" + i + "\":" + i).mkString("{", ",", "}"))
        val many = AiSampleValues.jsonTopLevel(recs.mkString("[", ",", "]")).get._2
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

    test("ai-refusal-fallback: a generate response posted back as a pipeline config drops aiDeclined, never persisted") {
        val generated =
            """{"aiDeclined": true, "name": "people", "source": {"schemaProperties": {"fields": [{"name":"ssn","type":"string"}]},
              | "fileAttributes": {"csvAttributes": {"delimiter": ",", "header": true, "encoding": "UTF-8"}}},
              | "destination": {"database": {"dbName": "d", "schema": "s", "table": "t", "usePostgres": true}}}""".stripMargin
        val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val viaJackson = mapper.readValue(generated, classOf[PipelineConfig])
        assert(viaJackson.name == "people")
        assert(!new Gson().toJson(viaJackson).contains("aiDeclined"))
        assert(!new Gson().toJson(new Gson().fromJson(generated, classOf[PipelineConfig])).contains("aiDeclined"))
        assert(!classOf[PipelineConfig].getDeclaredFields.exists(_.getName == "aiDeclined"))
    }

    // ---- round 2

    test("a line with person-style names or letters-dash-digits ids is data, even when every cell is identifier-shaped") {
        assert(
            AiSampleValues.safeColumnNames(List("John Smith", "Springfield", "IL", "ZQX-SSN-2", "123-45-6789"), header = true) ==
                (1 to 5).map("column_" + _).toList
        )
        assert(AiSampleValues.headerLooksLikeData(List("Jane Doe", "F", "Diabetes mellitus type 2", "MRN-12345")))
        assert(AiSampleValues.headerLooksLikeData(List("name", "mrn", "MRN-12345")), "an id-shaped cell marks the line as data")
        assert(!AiSampleValues.headerLooksLikeData(List("full_name", "ssn", "age", "email", "zip-code")))
    }

    test("profile off: a .txt file is profiled as delimited (no sniffing) and still sends no value") {
        inEnv {
            withheld {
                val ndjson = """{"name":"ZQX-NAME-1","ssn":"ZQX-SSN-2"}""" + "\n" + """{"name":"ZQX-NAME-7","ssn":"ZQX-SSN-2"}"""
                val (prompts, ai) = capturing(modelProfile)
                AIProfileUtil.profile(ndjson, "people.txt", ",", true, 100, ai)
                assertNoMarker(prompts.head)
                assert(prompts.head.contains("Per-column statistics"), prompts.head)
            }
        }
    }

    test("profile off: a .csv whose first cell starts with [ keeps the statistics path") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing(modelProfile)
                AIProfileUtil.profile("[tag],code\n[ZQX-NAME-1],ZQX-ID-5", "people.csv", ",", true, 100, ai)
                assertNoMarker(prompts.head)
                assert(prompts.head.contains("Per-column statistics") && !prompts.head.contains("structure unavailable"), prompts.head)
            }
        }
    }

    test("a JSON map whose keys are names but whose values share one shape collapses to <key>") {
        val keyed = """{"John Smith": {"dx": "ZQX-NOTE-6", "age": 41}, "ZQX-ID-5": {"dx": "ZQX-NOTE-6", "age": 7}}"""
        val sk = AiSampleValues.jsonSkeleton(keyed)
        assert(!sk.contains("John Smith"), sk)
        assertNoMarker(sk)
        assert(JsonParser.parseString(sk).getAsJsonObject.keySet().asScala.toSet == Set("<key>"), sk)

        val byName = """{"Jane Doe": {"a": 1}, "John Roe": {"a": 2}}"""
        assert(AiSampleValues.jsonSkeleton(byName) == """{"<key>":{"a":0}}""")
        assert(AiSampleValues.jsonTopLevel(byName).map(_._2) == Some(List("<key>")))
        // a record whose object fields differ in shape keeps its keys
        val record = """{"address": {"city": "ZQX-CITY-4"}, "employer": {"name": "ZQX-NAME-1", "id": 1}}"""
        val recSk = JsonParser.parseString(AiSampleValues.jsonSkeleton(record)).getAsJsonObject
        assert(recSk.has("address") && recSk.has("employer"), recSk.toString)
    }

    test("scrubErrorForModel only trusts classes and frames under known package roots") {
        val s = AiSampleValues.scrubErrorForModel(
            "zqx.patients.JaneDoeException: x\nat zqx.patients.Mrn.lookup(Mrn.java:1)\nCaused by: java.io.IOException: ZQX-SSN-2\n\tat ai.datris.util.X.y(X.scala:3)"
        )
        assert(!s.toLowerCase.contains("zqx") && !s.contains("JaneDoe"), s)
        assert(s.contains("Caused by: java.io.IOException: " + AiSampleValues.DetailsWithheld) && s.contains("at ai.datris.util.X.y(X.scala:3)"), s)
    }

    // ---- round 3 (live e2e)

    test("strict header test: the e2e first row and value-shaped cells make the line data; plain names stay") {
        val e2e = List("ZQX-NAME-1", "ZQX-SSN-2", "zqx-mail-3@example.com", "918273645")
        assert(AiSampleValues.headerLooksLikeData(e2e))
        assert(AiSampleValues.safeColumnNames(e2e, header = true) == (1 to 4).map("column_" + _).toList)
        Seq("MRN-12345", "2024-01-05", "A1-B2-C3").foreach { v =>
            assert(AiSampleValues.headerLooksLikeData(List("name", v)), v)
            assert(AiSampleValues.safeColumnNames(List("name", v), header = true) == List("column_1", "column_2"), v)
        }
        val plain = List("full_name", "ssn", "email", "age", "first name", "addr1", "phone2", "zip_code")
        assert(!AiSampleValues.headerLooksLikeData(plain))
        assert(AiSampleValues.safeColumnNames(plain, header = true) == plain)
        assert(AiSampleValues.safeColumnNames(List("id", "account", "First Name", "e.mail"), header = true) == List("id", "account", "column_3", "column_4"))
        assert(AiSampleValues.safeColumnNames(List("id", "account123"), header = true) == List("column_1", "column_2"), "more than two digits: data")
    }

    test("CSV schema generation off with header=true on the e2e first row: numbered fields") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing("[]")
                val csv = "ZQX-NAME-1,ZQX-SSN-2,zqx-mail-3@example.com,918273645\nZQX-NAME-7,ZQX-SSN-2,zqx-mail-3@example.com,41"
                val config = AISchemaUtil.buildCsvConfig("rows", csv, ",", true, ai)
                assert(prompts.isEmpty)
                assertNoMarker(config)
                assert(!config.toLowerCase.contains("zqx"), config)
                assert((1 to 4).forall(i => config.contains("\"column_" + i + "\"")), config)
            }
        }
    }

    test("JSON keys with a dash next to a digit or an @ collapse to <key>") {
        val sk = AiSampleValues.jsonSkeleton("""{"ZQX-ID-5": 1, "name": "ZQX-NAME-1"}""")
        assert(!sk.contains("ZQX") && sk.contains("<key>"), sk)
        val kept = AiSampleValues.jsonSkeleton("""{"zip-code": "x", "content.type": "y"}""")
        assert(kept.contains("zip-code") && kept.contains("content.type"), "dash/dot without a digit stays a key: " + kept)
    }

    test("profile off: a model reply that is not JSON returns the local statistics, not an error") {
        inEnv {
            withheld {
                val csv = "full_name,ssn,age\nZQX-NAME-1,ZQX-SSN-2,41\nZQX-NAME-7,,7"
                val truncated = """{"summary":{"rowCount":2,"columnCount":3,"columns":[{"name":"full_name","inferredType":"string","nul"""
                val (_, ai) = capturing(truncated)
                val out = AIProfileUtil.profile(csv, "people.csv", ",", true, 100, ai)
                assertNoMarker(out)
                val obj = JsonParser.parseString(out).getAsJsonObject
                assert(obj.get("valuesWithheld").getAsBoolean)
                assert(!obj.has("analysis"), out)
                assert(obj.getAsJsonArray("qualityIssues").size == 0 && obj.getAsJsonArray("recommendations").size == 0, out)
                assert(obj.get("note").getAsString == "the model's reply could not be parsed; statistics only")
                val cols = obj.getAsJsonObject("summary").getAsJsonArray("columns")
                assert(cols.size == 3 && cols.get(0).getAsJsonObject.get("name").getAsString == "full_name", out)
                assert(cols.get(1).getAsJsonObject.get("nullCount").getAsInt == 1, out)
                assert(obj.getAsJsonObject("summary").get("rowCount").getAsInt == 2, out)

                val (_, noJson) = capturing("I could not do that")
                val out2 = JsonParser.parseString(AIProfileUtil.profile(csv, "people.csv", ",", true, 100, noJson)).getAsJsonObject
                assert(out2.get("valuesWithheld").getAsBoolean && out2.has("note"))
            }
        }
    }

    // ---- round 4

    test("a first row with a numeric cell is data: Jane,Doe,F,42 and friends are numbered") {
        Seq(
            List("Jane", "Doe", "F", "42"),
            List("Jane", "Doe", "1984"),
            List("Jane", "Doe", "555 1234"),
            List("Smith", "4111"),
            List("jane", "doe", "acct123")
        ).foreach { row =>
            assert(AiSampleValues.headerLooksLikeData(row), row.toString)
            assert(AiSampleValues.safeColumnNames(row, header = true) == row.indices.map(i => "column_" + (i + 1)).toList, row.toString)
        }
        assert(!AiSampleValues.headerLooksLikeData(List("name", "", "addr1", "phone2")), "an empty cell or up to two digits is still a header")
    }

    test("profile off, header=true: headerless Jane,Doe,F,42 sends no first-row value") {
        inEnv {
            withheld {
                val (prompts, ai) = capturing(modelProfile)
                AIProfileUtil.profile("Jane,Doe,F,42\nJohn,Roe,M,7", "people.csv", ",", true, 100, ai)
                Seq("Jane", "Doe").foreach(v => assert(!prompts.head.contains(v), v + " leaked: " + prompts.head))
                assert(prompts.head.contains("Rows: 2") && prompts.head.contains("column_4"), prompts.head)
            }
        }
    }

    test("a UTF-8 BOM does not cost the first header cell its name") {
        assert(AiSampleValues.safeColumnNames(List("\uFEFFfull_name", "ssn"), header = true) == List("full_name", "ssn"))
        inEnv {
            withheld {
                val (_, ai) = capturing("[]")
                val config = AISchemaUtil.buildCsvConfig("people", "\uFEFFfull_name,ssn\nZQX-NAME-1,ZQX-SSN-2", ",", true, ai)
                assert(config.contains("\"full_name\"") && !config.contains("\uFEFF"), config)
                val (prompts, pai) = capturing(modelProfile)
                AIProfileUtil.profile("\uFEFFfull_name,ssn\nZQX-NAME-1,ZQX-SSN-2", "people.csv", ",", true, 100, pai)
                assert(prompts.head.contains("full_name |") && !prompts.head.contains("column_1"), prompts.head)
            }
        }
    }

    test("a nested map of same-shaped arrays collapses; a root record of same-typed scalars and a nested record object do not") {
        val nested = AiSampleValues.jsonSkeleton("""{"patients": {"smith": [1], "jones": [2]}}""")
        assert(!nested.contains("smith") && !nested.contains("jones"), nested)
        assert(JsonParser.parseString(nested).getAsJsonObject.getAsJsonObject("patients").has("<key>"), nested)

        val root = AiSampleValues.jsonSkeleton("""{"first": "a", "last": "b", "city": "c"}""")
        Seq("first", "last", "city").foreach(k => assert(root.contains("\"" + k + "\""), root))
        val rootArrays = AiSampleValues.jsonSkeleton("""{"tags": ["a"], "ids": [1]}""")
        assert(rootArrays.contains("\"tags\"") && rootArrays.contains("\"ids\""), "root arrays are a record: " + rootArrays)
        val address = AiSampleValues.jsonSkeleton("""{"address": {"city": "ZQX-CITY-4", "zip": "ZQX-ID-5"}}""")
        assert(address.contains("\"city\"") && address.contains("\"zip\""), address)
    }
}
