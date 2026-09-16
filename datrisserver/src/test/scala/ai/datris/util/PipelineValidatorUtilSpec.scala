package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, PipelineConfig}
import com.google.gson.Gson
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.JavaConverters._

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

    // --- Iceberg format / merge / keyFields rules ----------------------------
    // Every objectStore rule runs BEFORE the existing-pipeline lookup
    // (PipelineConfigIO.read), which needs a live config DB and is unreachable
    // here. Rejection cases therefore assert on the rule's own message. The
    // acceptance case uses a deterministic sentinel that sits AFTER the
    // fileFormat rule and BEFORE the lookup: provider=s3 without
    // destinationBucketOverride. If validate reports the bucket error, the
    // fileFormat rule let "iceberg" through. The in-place parquet→iceberg flip
    // rule lives inside the lookup branch and is covered by the e2e pass.

    private def objectStoreConfig(objectStore: String, schemaFields: String = """[{"name":"id","type":"string"},{"name":"name","type":"string"}]"""): PipelineConfig =
        parse(
            s"""{"name":"p",
               |"source":{"fileAttributes":{"csvAttributes":{}},"schemaProperties":{"fields":$schemaFields}},
               |"destination":{"objectStore":{"prefixKey":"p",$objectStore}}}""".stripMargin
        )

    private def validationError(cfg: PipelineConfig): Option[String] =
        try { PipelineValidatorUtil.validate(cfg); None }
        catch { case e: DatrisException => Some(e.getMessage) }

    private val s3BucketSentinel = "destinationBucketOverride"

    test("objectStore fileFormat=iceberg is accepted") {
        val cfg = objectStoreConfig(""""fileFormat":"iceberg","provider":"s3"""")
        val err = validationError(cfg)
        assert(err.isDefined && err.get.contains(s3BucketSentinel), s"expected the s3 bucket sentinel, got: $err")
        assert(!err.get.contains("fileFormat"))
    }

    test("objectStore writeMode=merge without keyFields is rejected") {
        val cfg = objectStoreConfig(""""fileFormat":"iceberg","writeMode":"merge"""")
        val err = validationError(cfg)
        assert(err.exists(_.contains("keyFields")), s"expected a keyFields error, got: $err")
    }

    test("objectStore writeMode=merge with fileFormat=parquet is rejected") {
        val cfg = objectStoreConfig(""""fileFormat":"parquet","writeMode":"merge","keyFields":["id"]""")
        val err = validationError(cfg)
        assert(err.exists(m => m.contains("merge") && m.contains("iceberg")), s"expected a merge-requires-iceberg error, got: $err")
    }

    test("objectStore keyFields without writeMode=merge is rejected") {
        val cfg = objectStoreConfig(""""fileFormat":"iceberg","keyFields":["id"]""")
        val err = validationError(cfg)
        assert(err.exists(m => m.contains("keyFields") && m.contains("merge")), s"expected a keyFields-only-for-merge error, got: $err")
    }

    test("objectStore keyFields naming a column not in the destination schema is rejected") {
        val cfg = objectStoreConfig(""""fileFormat":"iceberg","writeMode":"merge","keyFields":["id","nope"]""")
        val err = validationError(cfg)
        assert(err.exists(_.contains("nope")), s"expected an unknown-column error naming 'nope', got: $err")
    }

    test("objectStore writeToTemporaryLocation=true with fileFormat=iceberg is rejected") {
        val cfg = objectStoreConfig(""""fileFormat":"iceberg","writeToTemporaryLocation":true""")
        val err = validationError(cfg)
        assert(err.exists(_.toLowerCase.contains("writetotemporarylocation")), s"expected a writeToTemporaryLocation error, got: $err")
    }

    test("modify lowercases objectStore keyFields like partitionBy") {
        val cfg = objectStoreConfig(""""fileFormat":"iceberg","writeMode":"merge","keyFields":["ID","Name"],"partitionBy":["Name"]""")
        val out = PipelineValidatorUtil.modify(cfg)
        assert(out.destination.objectStore.keyFields.asScala.toList == List("id", "name"))
        assert(out.destination.objectStore.partitionBy.asScala.toList == List("name"))
        assert(out.destination.objectStore.fileFormat == "iceberg")
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
