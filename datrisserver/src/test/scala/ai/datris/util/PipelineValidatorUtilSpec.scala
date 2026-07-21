package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import ai.datris.model.{DatrisException, PipelineConfig}
import com.google.gson.Gson
import org.scalatest.funsuite.AnyFunSuite

class PipelineValidatorUtilSpec extends AnyFunSuite {

    private val gson = new Gson()

    private def parse(json: String): PipelineConfig = gson.fromJson(json, classOf[PipelineConfig])

    test("missing name is rejected") {
        val e = intercept[DatrisException] { PipelineValidatorUtil.validate(parse("{}")) }
        assert(e.getMessage.contains("'name'"))
    }

    test("name longer than 80 characters is rejected") {
        val longName = "x" * 81
        val e = intercept[DatrisException] {
            PipelineValidatorUtil.validate(parse(s"""{"name":"$longName"}"""))
        }
        assert(e.getMessage.contains("80"))
    }

    test("missing source is rejected") {
        val e = intercept[DatrisException] {
            PipelineValidatorUtil.validate(parse("""{"name":"p"}"""))
        }
        assert(e.getMessage.contains("'source'"))
    }

    test("source without fileAttributes or databaseAttributes is rejected") {
        val e = intercept[DatrisException] {
            PipelineValidatorUtil.validate(parse("""{"name":"p","source":{}}"""))
        }
        assert(e.getMessage.contains("fileAttributes"))
    }

    test("applyDefaults is a no-op when destination or database is absent") {
        val config = parse("""{"name":"p"}""")
        assert(PipelineValidatorUtil.applyDefaults(config) eq config)
        val noDb = parse("""{"name":"p","destination":{}}""")
        assert(PipelineValidatorUtil.applyDefaults(noDb) eq noDb)
    }

    test("applyDefaults leaves a fully-specified database config unchanged") {
        val config = parse("""{"name":"p","destination":{"database":{"dbName":"mydb","schema":"myschema"}}}""")
        assert(PipelineValidatorUtil.applyDefaults(config) eq config)
    }
}
