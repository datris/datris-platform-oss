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

    // --- keyFields schema-membership rule ------------------------------------
    // Non-Mongo engines store columns, so key fields must exist as schema
    // columns. Mongo stores whole `_json` documents and upserts by matching
    // keys INSIDE the document (MongoDBLoader.upsertJSON) — its keys
    // legitimately never appear in the schema and must not be rejected.

    private val keyFieldConfigTemplate =
        """{"name":"p",
          |"source":{"fileAttributes":{},"schemaProperties":{"fields":[{"name":"_json","type":"string"}]}},
          |"destination":{"database":{"dbName":"db","schema":"public","table":"t",%s,"keyFields":["id"]}}}""".stripMargin

    test("postgres destination: keyFields must be schema columns") {
        val cfg = parse(keyFieldConfigTemplate.format(""""usePostgres":true"""))
        val e = intercept[DatrisException] { PipelineValidatorUtil.validate(cfg) }
        assert(e.getMessage.contains("Key field"))
    }

    test("mongo destination: keyFields need not be schema columns (upsert matches inside _json)") {
        val cfg = parse(keyFieldConfigTemplate.format(""""useMongoDB":true"""))
        val thrown =
            try { PipelineValidatorUtil.validate(cfg); None }
            catch { case e: DatrisException => Some(e) }
        // Other unrelated validations may still fire on this minimal config —
        // the exemption only guarantees the keyFields rule itself is skipped.
        assert(!thrown.exists(_.getMessage.contains("Key field")))
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
