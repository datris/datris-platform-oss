package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, ObjectStore, PipelineConfig}
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

    private def objectStoreConfig(
        objectStore: String,
        schemaFields: String = """[{"name":"id","type":"string"},{"name":"name","type":"string"}]"""
    ): PipelineConfig =
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

    // --- existing-pipeline format flip -----------------------------------------
    // The call site sits behind PipelineConfigIO.read, so the rule is exercised
    // directly through the extracted helper.

    private def flipError(existingFormat: String, updatedFormat: String, deleteBeforeWrite: Boolean = false): Option[String] =
        try {
            PipelineValidatorUtil.checkIcebergFormatFlip(
                ObjectStore(prefixKey = "p", fileFormat = existingFormat),
                ObjectStore(prefixKey = "p", fileFormat = updatedFormat, deleteBeforeWrite = deleteBeforeWrite)
            )
            None
        } catch { case e: DatrisException => Some(e.getMessage) }

    test("existing default-format pipeline flipped to iceberg is rejected without deleteBeforeWrite") {
        val err = flipError(null, "iceberg")
        assert(err.exists(m => m.contains("iceberg") && m.contains("deleteBeforeWrite")), s"got: $err")
    }

    test("existing iceberg pipeline flipped to default format is rejected without deleteBeforeWrite") {
        val err = flipError("iceberg", null)
        assert(err.exists(m => m.contains("iceberg") && m.contains("deleteBeforeWrite")), s"got: $err")
    }

    test("iceberg format flip in either direction is accepted with deleteBeforeWrite=true") {
        assert(flipError(null, "iceberg", deleteBeforeWrite = true).isEmpty)
        assert(flipError("iceberg", null, deleteBeforeWrite = true).isEmpty)
        assert(flipError("parquet", "iceberg", deleteBeforeWrite = true).isEmpty)
    }

    test("parquet to orc flip is not subject to the iceberg rule") {
        assert(flipError("parquet", "orc").isEmpty)
        assert(flipError("iceberg", "iceberg").isEmpty)
    }

    test("objectStore writeMode is trimmed before the merge rules apply") {
        val cfg = objectStoreConfig(""""fileFormat":"iceberg","writeMode":" merge """")
        val err = validationError(cfg)
        assert(err.exists(_.contains("keyFields")), s"expected a keyFields error, got: $err")
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

    // --- S3 endpoint SSRF guard (story: objectstore-endpoint-ssrf-and-error-bodies, B1) ------
    // The guard is added in the provider=s3 block AFTER the existing https://
    // check, which itself sits AFTER the destinationBucketOverride check. So the
    // bucket sentinel above cannot prove the endpoint rule passed; the only
    // deterministic point after the endpoint block is the existing-pipeline
    // lookup, `PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, ...)`.
    // DatrisEnvironment.current is null in unit tests, so reaching the lookup
    // throws a NullPointerException, never a DatrisException. Acceptance cases
    // therefore assert "NPE at the lookup"; rejection cases assert the
    // SsrfGuard DatrisException text. SsrfGuard honours the
    // `datris.allowPrivateEgress` system property as the runtime equivalent of
    // DATRIS_ALLOW_PRIVATE_EGRESS; it is set and cleared with try/finally so it
    // never leaks into other suites in the forked test JVM.

    private val ssrfMarker = "private, loopback, or link-local"

    private def s3EndpointConfig(provider: String, endpoint: String): PipelineConfig =
        objectStoreConfig(s""""provider":"$provider","destinationBucketOverride":"my-bucket","endpoint":"$endpoint"""")

    /** Runs validate and returns whatever it throws (None on success). */
    private def validationOutcome(cfg: PipelineConfig): Option[Throwable] =
        try { PipelineValidatorUtil.validate(cfg); None }
        catch { case t: Throwable => Some(t) }

    private def assertReachedExistingPipelineLookup(outcome: Option[Throwable], label: String): Unit = {
        assert(outcome.isDefined, s"$label: expected the existing-pipeline lookup to fail on the null DatrisEnvironment, but validate returned")
        assert(
            !outcome.get.isInstanceOf[DatrisException],
            s"$label: endpoint rule must have passed and validate must reach the existing-pipeline lookup; got DatrisException: ${outcome.get.getMessage}"
        )
        assert(outcome.get.isInstanceOf[NullPointerException], s"$label: expected the null-DatrisEnvironment NPE sentinel, got ${outcome.get}")
    }

    /** The DatrisException message validate threw, or a readable failure when
      * the endpoint was NOT rejected (i.e. validate fell through to the lookup NPE). */
    private def ssrfRejection(cfg: PipelineConfig): String =
        validationOutcome(cfg) match {
            case Some(e: DatrisException) => e.getMessage
            case Some(other) => fail(s"endpoint was not rejected: validate reached the existing-pipeline lookup ($other)")
            case None => fail("endpoint was not rejected: validate returned normally")
        }

    private def withPrivateEgress[A](enabled: Boolean)(body: => A): A = {
        val key = "datris.allowPrivateEgress"
        val previous = sys.props.get(key)
        if (enabled) sys.props(key) = "true" else sys.props -= key
        try body
        finally previous match {
                case Some(v) => sys.props(key) = v
                case None => sys.props -= key
            }
    }

    test("provider=s3 with a loopback endpoint is rejected with the SsrfGuard message") {
        withPrivateEgress(enabled = false) {
            val err = ssrfRejection(s3EndpointConfig("s3", "https://127.0.0.1/some-bucket"))
            assert(err.contains(ssrfMarker), s"expected the SsrfGuard rejection, got: $err")
            assert(err.contains("127.0.0.1"), s"message must name the resolved address, got: $err")
        }
    }

    test("provider=s3 with a link-local (cloud metadata) endpoint is rejected with the SsrfGuard message") {
        withPrivateEgress(enabled = false) {
            val err = ssrfRejection(s3EndpointConfig("s3", "https://169.254.169.254/latest/meta-data"))
            assert(err.contains(ssrfMarker), s"expected the SsrfGuard rejection, got: $err")
        }
    }

    test("provider=s3 with a loopback endpoint is accepted when datris.allowPrivateEgress=true") {
        withPrivateEgress(enabled = true) {
            val outcome = validationOutcome(s3EndpointConfig("s3", "https://127.0.0.1/some-bucket"))
            assertReachedExistingPipelineLookup(outcome, "allowPrivateEgress opt-in")
        }
    }

    test("provider=minio is unaffected by the S3 endpoint guard, whatever the endpoint") {
        withPrivateEgress(enabled = false) {
            // Note: provider=minio has no bucket-override requirement either, so
            // the only thing left between the objectStore rules and the lookup
            // is the guard — it must not fire for minio.
            val outcome = validationOutcome(objectStoreConfig(""""provider":"minio","endpoint":"https://127.0.0.1:9000""""))
            assertReachedExistingPipelineLookup(outcome, "provider=minio loopback endpoint")
            val outcome2 = validationOutcome(objectStoreConfig(""""provider":"minio","endpoint":"http://minio:9000""""))
            assertReachedExistingPipelineLookup(outcome2, "provider=minio http endpoint")
        }
    }

    test("provider=s3 with a normal AWS endpoint still validates") {
        // SsrfGuard resolves the host. Offline this would fail for the wrong
        // reason ("Could not resolve host"), so cancel rather than fail when
        // the machine cannot resolve it.
        val host = "s3.us-east-1.amazonaws.com"
        val resolvable =
            try { java.net.InetAddress.getAllByName(host).nonEmpty }
            catch { case _: Exception => false }
        assume(resolvable, s"$host does not resolve on this machine; cannot exercise the public-endpoint path")
        withPrivateEgress(enabled = false) {
            val outcome = validationOutcome(s3EndpointConfig("s3", s"https://$host"))
            assertReachedExistingPipelineLookup(outcome, "public AWS endpoint")
        }
    }

    test("provider=s3 https:// check still runs before the guard: http:// loopback is rejected for http, not SSRF") {
        withPrivateEgress(enabled = false) {
            val err = validationError(s3EndpointConfig("s3", "http://127.0.0.1/some-bucket"))
            assert(err.exists(_.contains("https://")), s"expected the existing https:// rule, got: $err")
            assert(!err.exists(_.contains(ssrfMarker)), s"https:// rule must fire first, got: $err")
        }
    }

    // --- MinIO bucket allowlist (story: objectstore-bucket-allowlist, B2) ------
    // Seam pinned: the allowlist is read from the `datris.objectStoreBucketAllowlist`
    // system property (runtime twin of DATRIS_OBJECTSTORE_BUCKET_ALLOWLIST), set
    // and cleared with try/finally like the SSRF cases above. The rule sits in
    // the objectStore block BEFORE the existing-pipeline lookup, so acceptance
    // is again "NPE at the lookup" and rejection is a DatrisException naming the
    // bucket and the variable. The `<env>-data` implicit allowance cannot be
    // exercised through validate() here: DatrisEnvironment.current is null in
    // unit tests and pinning a tenant env would send the lookup into Mongo. It
    // is pinned at the seam in ObjectStoreSparkSpec and covered by the e2e pass.

    private val allowlistVariable = "DATRIS_OBJECTSTORE_BUCKET_ALLOWLIST"

    private def withBucketAllowlist[A](value: Option[String])(body: => A): A = {
        val key = "datris.objectStoreBucketAllowlist"
        val previous = sys.props.get(key)
        value match {
            case Some(v) => sys.props(key) = v
            case None => sys.props -= key
        }
        try body
        finally previous match {
                case Some(v) => sys.props(key) = v
                case None => sys.props -= key
            }
    }

    private def minioOverrideConfig(bucket: String): PipelineConfig =
        objectStoreConfig(s""""provider":"minio","destinationBucketOverride":"$bucket"""")

    test("bucket allowlist unset: a provider=minio destinationBucketOverride validates (unchanged behaviour)") {
        withBucketAllowlist(None) {
            val outcome = validationOutcome(minioOverrideConfig("shared-bucket"))
            assertReachedExistingPipelineLookup(outcome, "allowlist unset, minio override")
        }
    }

    test("bucket allowlist set and the minio override is listed: validates") {
        withBucketAllowlist(Some(" team-a , team-b ,, ")) {
            val outcome = validationOutcome(minioOverrideConfig("team-b"))
            assertReachedExistingPipelineLookup(outcome, "allowlist set, listed minio override")
        }
    }

    test("bucket allowlist set and the minio override is not listed: rejected naming the bucket and the variable") {
        withBucketAllowlist(Some("team-a,team-b")) {
            val err = validationOutcome(minioOverrideConfig("other-env-data")) match {
                case Some(e: DatrisException) => e.getMessage
                case Some(other) => fail(s"non-listed minio bucket was not rejected: validate reached the existing-pipeline lookup ($other)")
                case None => fail("non-listed minio bucket was not rejected: validate returned normally")
            }
            assert(err.contains("other-env-data"), s"message must name the bucket, got: $err")
            assert(err.contains(allowlistVariable), s"message must name $allowlistVariable, got: $err")
        }
    }

    test("bucket allowlist set: a provider=s3 destinationBucketOverride validates whether or not it is listed") {
        withBucketAllowlist(Some("team-a")) {
            val outcome = validationOutcome(objectStoreConfig(""""provider":"s3","destinationBucketOverride":"customer-owned-bucket""""))
            assertReachedExistingPipelineLookup(outcome, "allowlist set, provider=s3 override not listed")
        }
    }

    // --- scratch destination (story: scratch-destination-server) --------------
    // `destination.scratch: {}` lands the run as a JSON-lines object instead of
    // a database/table. Structured and semi-structured sources only, never in
    // combination with another destination, and the existing
    // `source.schemaProperties` requirement still applies. The rejection cases
    // use database/kafka companions (not objectStore) so they never reach the
    // existing-pipeline lookup regardless of where the scratch block sits.

    private def scratchConfig(source: String, schemaFields: String, extraDestination: String = "", schemaProperties: Boolean = true): PipelineConfig = {
        val schema = if (schemaProperties) s""","schemaProperties":{"fields":[$schemaFields]}""" else ""
        parse(
            s"""{"name":"p",
               |"source":{"fileAttributes":{$source}$schema},
               |"destination":{"scratch":{}$extraDestination}}""".stripMargin
        )
    }

    private val csvSource = """"csvAttributes":{}"""
    private val csvSchema = """{"name":"id","type":"string"},{"name":"name","type":"string"}"""

    test("scratch combined with another destination is rejected") {
        val withDb = scratchConfig(
            csvSource,
            csvSchema,
            extraDestination = ""","database":{"dbName":"datris","schema":"public","table":"t","usePostgres":true}"""
        )
        assert(withDb.destination.scratch != null, "fixture must parse the scratch destination")
        val err = validationError(withDb)
        assert(err.exists(m => m.contains("scratch") && m.toLowerCase.contains("combined")), s"expected the scratch-alone rule, got: $err")

        val withKafka = scratchConfig(csvSource, csvSchema, extraDestination = ""","kafka":{"topic":"events"}""")
        val err2 = validationError(withKafka)
        assert(err2.exists(m => m.contains("scratch") && m.toLowerCase.contains("combined")), s"expected the scratch-alone rule, got: $err2")
        // Story live-read-naming: the message names Live Read and keeps "scratch".
        assert(err.exists(_.contains("Live Read (scratch)")), s"combined-destination message must say Live Read (scratch), got: $err")
        assert(err2.exists(_.contains("Live Read (scratch)")), s"combined-destination message must say Live Read (scratch), got: $err2")
    }

    test("scratch with an unstructured source is rejected") {
        val cfg = scratchConfig(""""unstructuredAttributes":{"fileExtension":"pdf"}""", csvSchema)
        assert(cfg.destination.scratch != null, "fixture must parse the scratch destination")
        val err = validationError(cfg)
        assert(err.exists(_.contains("scratch")), s"unstructured + scratch must be rejected by a message naming scratch, got: $err")
    }

    test("scratch without source.schemaProperties is rejected") {
        val cfg = scratchConfig(csvSource, csvSchema, schemaProperties = false)
        assert(cfg.destination.scratch != null, "fixture must parse the scratch destination")
        assert(cfg.source.schemaProperties == null)
        val err = validationError(cfg)
        assert(err.exists(_.contains("source.schemaProperties")), s"expected the schemaProperties requirement, got: $err")
    }

    test("modify preserves destination.scratch") {
        // The REST create path runs modify before persisting; a positional
        // Destination rebuild there once dropped scratch, so the stored config
        // was `"destination":{}` and the run dispatched no loader.
        val cfg = scratchConfig(csvSource, csvSchema)
        val out = PipelineValidatorUtil.modify(cfg)
        assert(out.destination.scratch != null, "modify must carry destination.scratch through")
        assert(out.destination.database == null && out.destination.objectStore == null)
        assert(out.source.schemaProperties.fields.asScala.map(_.name).toList == List("id", "name"))
        assert(new Gson().toJson(out.destination).contains("\"scratch\""), "persisted JSON must keep the scratch key")
    }

    test("modify preserves destination.authoritative") {
        val cfg = parse(
            """{"name":"p",
              |"source":{"fileAttributes":{"csvAttributes":{}},"schemaProperties":{"fields":[{"name":"id","type":"string"}]}},
              |"destination":{"authoritative":"postgres",
              |"database":{"dbName":"datris","schema":"public","table":"t","usePostgres":true},
              |"kafka":{"topic":"events"}}}""".stripMargin
        )
        val out = PipelineValidatorUtil.modify(cfg)
        assert(out.destination.authoritative == "postgres", "modify must carry destination.authoritative through")
        assert(out.destination.kafka != null && out.destination.database != null)
    }

    test("scratch alone on a CSV/JSON/XML source validates") {
        val csv = scratchConfig(csvSource, csvSchema)
        assert(csv.destination.scratch != null, "fixture must parse the scratch destination")
        assert(validationError(csv).isEmpty, s"CSV + scratch must validate, got: ${validationError(csv)}")

        val json = scratchConfig(""""jsonAttributes":{}""", """{"name":"_json","type":"string"}""")
        assert(validationError(json).isEmpty, s"JSON + scratch must validate, got: ${validationError(json)}")

        val xml = scratchConfig(""""xmlAttributes":{}""", """{"name":"_xml","type":"string"}""")
        assert(validationError(xml).isEmpty, s"XML + scratch must validate, got: ${validationError(xml)}")
    }

    // --- Unity Catalog opt-in (story: unity-catalog-1-metadata-push) ----------
    // `unityCatalog.enabled` is accepted for a Databricks destination and (story
    // unity-catalog-4-iceberg-register) an objectStore Iceberg destination with
    // credentialsSecret + catalog; any other destination (postgres, mongo,
    // snowflake, scratch, vector) is rejected with the Databricks-only message. The database branch of
    // validate never reaches the existing-pipeline lookup, so a Databricks
    // config with credentialsSecret + warehouse validates cleanly offline.
    // The fixture asserts parse the new field so the rule, not Gson dropping
    // an unknown key, is what these cases exercise.

    private val ucDatabricksOnly = "'unityCatalog.enabled' is only supported for a Databricks destination (destination.database.useDatabricks=true)"

    private def ucConfig(database: String, unityCatalog: String = """{"enabled":true}"""): PipelineConfig =
        parse(
            s"""{"name":"p",
               |"source":{"fileAttributes":{"csvAttributes":{}},"schemaProperties":{"fields":[{"name":"id","type":"string"}]}},
               |"destination":{"database":{"dbName":"datris","schema":"default","table":"t",$database}},
               |"unityCatalog":$unityCatalog}""".stripMargin
        )

    private val databricksDb = """"useDatabricks":true,"credentialsSecret":"dbx","warehouse":"abc123""""

    test("unityCatalog.enabled on a non-Databricks destination is rejected with the Databricks-only message") {
        val pg = ucConfig(""""usePostgres":true""")
        assert(pg.unityCatalog != null && pg.unityCatalog.enabled, "fixture must parse unityCatalog.enabled=true")
        val err = validationError(pg)
        assert(err.exists(_.contains(ucDatabricksOnly)), s"postgres + unityCatalog must be rejected with the Databricks-only message, got: $err")

        val sf = ucConfig(""""useSnowflake":true,"credentialsSecret":"sf","warehouse":"WH"""")
        val err2 = validationError(sf)
        assert(err2.exists(_.contains(ucDatabricksOnly)), s"snowflake + unityCatalog must be rejected, got: $err2")

        val scratch = parse(
            """{"name":"p",
              |"source":{"fileAttributes":{"csvAttributes":{}},"schemaProperties":{"fields":[{"name":"id","type":"string"}]}},
              |"destination":{"scratch":{}},
              |"unityCatalog":{"enabled":true}}""".stripMargin
        )
        val err3 = validationError(scratch)
        assert(err3.exists(_.contains(ucDatabricksOnly)), s"scratch + unityCatalog must be rejected, got: $err3")
    }

    test("unityCatalog.enabled on an unstructured (vector) pipeline is rejected with the Databricks-only message") {
        val cfg = parse(
            """{"name":"p",
              |"source":{"fileAttributes":{"unstructuredAttributes":{"fileExtension":"pdf"}}},
              |"destination":{"pgvector":{"tableName":"t"}},
              |"unityCatalog":{"enabled":true}}""".stripMargin
        )
        val err = validationError(cfg)
        assert(err.exists(_.contains(ucDatabricksOnly)), s"unstructured + unityCatalog must be rejected, got: $err")
    }

    test("unityCatalog.enabled on useDatabricks passes") {
        val cfg = ucConfig(databricksDb)
        assert(cfg.unityCatalog != null && cfg.unityCatalog.enabled, "fixture must parse unityCatalog.enabled=true")
        assert(validationError(cfg).isEmpty, s"databricks + unityCatalog must validate, got: ${validationError(cfg)}")
    }

    test("Databricks with credentialsSecret and no warehouse is accepted (resolved from the secret at connection time)") {
        Seq(
            """"useDatabricks":true,"credentialsSecret":"dbx"""",
            """"useDatabricks":true,"credentialsSecret":"dbx","warehouse":""""",
            """"useDatabricks":true,"credentialsSecret":"dbx","warehouse":"  """"
        ).foreach { db =>
            val cfg = ucConfig(db, unityCatalog = """{"enabled":false}""")
            assert(validationError(cfg).isEmpty, s"$db must validate, got: ${validationError(cfg)}")
        }
    }

    test("Databricks without credentialsSecret is still rejected, with or without a warehouse") {
        Seq(""""useDatabricks":true""", """"useDatabricks":true,"warehouse":"abc123"""").foreach { db =>
            val err = validationError(ucConfig(db, unityCatalog = """{"enabled":false}"""))
            assert(err.exists(_.contains("'credentialsSecret' is required")), s"$db must be rejected, got: $err")
        }
    }

    test("unityCatalog.enabled=false on a non-Databricks destination passes") {
        val cfg = ucConfig(""""usePostgres":true""", unityCatalog = """{"enabled":false}""")
        assert(cfg.unityCatalog != null && !cfg.unityCatalog.enabled)
        assert(validationError(cfg).isEmpty, s"enabled=false must not trip the rule, got: ${validationError(cfg)}")
    }

    test("unityCatalog knobs default to true when only enabled is sent (Gson and Jackson)") {
        val g = ucConfig(databricksDb).unityCatalog
        assert(g.enabled && g.commentsOn && g.tagsOn && g.propertiesOn, s"Gson: $g")
        // Spring Boot's @RequestBody mapper: ParameterNamesModule, no
        // DefaultScalaModule, so Scala default arguments are NOT applied.
        val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val j = mapper.readValue("""{"enabled":true}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(j.enabled && j.commentsOn && j.tagsOn && j.propertiesOn, s"Jackson: $j")
        val off = mapper.readValue("""{"enabled":true,"tags":false}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(off.commentsOn && !off.tagsOn && off.propertiesOn, s"Jackson explicit false: $off")
        // A full PipelineConfig body through the same mapper keeps the knobs on.
        val cfg = mapper.readValue("""{"name":"p","unityCatalog":{"enabled":true}}""", classOf[PipelineConfig])
        assert(cfg.unityCatalog.enabled && cfg.unityCatalog.commentsOn && cfg.unityCatalog.tagsOn && cfg.unityCatalog.propertiesOn, s"$cfg")
    }

    test("absent unityCatalog parses as null") {
        assert(parse("""{"name":"p"}""").unityCatalog == null)
    }

    // Story: Unity Catalog 3: lineage publish (plans/stories/unity-catalog-3-lineage-publish.md), Step 1.
    test("unityCatalog.lineage defaults on under Jackson (ParameterNamesModule) and Gson; lineage:false turns it off") {
        val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val j = mapper.readValue("""{"enabled":true}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(j.lineage == null && j.lineageOn, s"Jackson absent lineage: $j")
        val off = mapper.readValue("""{"enabled":true,"lineage":false}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(!off.lineageOn && off.commentsOn && off.tagsOn && off.propertiesOn, s"Jackson lineage=false: $off")
        val on = mapper.readValue("""{"enabled":true,"lineage":true}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(on.lineageOn, s"$on")
        val cfg = mapper.readValue("""{"name":"p","unityCatalog":{"enabled":true,"lineage":false}}""", classOf[PipelineConfig])
        assert(cfg.unityCatalog.enabled && !cfg.unityCatalog.lineageOn, s"$cfg")

        val g = ucConfig(databricksDb).unityCatalog
        assert(g.lineageOn, s"Gson absent lineage: $g")
        val gOff = ucConfig(databricksDb, unityCatalog = """{"enabled":true,"lineage":false}""").unityCatalog
        assert(!gOff.lineageOn, s"Gson lineage=false: $gOff")
        assert(new ai.datris.model.UnityCatalogSync().lineageOn, "no-arg constructor leaves lineage on")
        // Round trip keeps the explicit false (config DB write/read).
        assert(!gson.fromJson(gson.toJson(gOff), classOf[ai.datris.model.UnityCatalogSync]).lineageOn)
    }

    // --- Story: Unity Catalog 4: Iceberg register spike ------------------------
    // (plans/stories/unity-catalog-4-iceberg-register.md), Step 2 / Acceptance
    // bullet 3. validateUnityCatalog runs before the structured rules, so an
    // accepted s3 config reaching the s3 bucket sentinel proves the UC rule let
    // it through. MinIO has no deterministic sentinel before the existing-
    // pipeline lookup (needs a config DB), so the MinIO case only asserts that
    // whatever stops validation is not a unityCatalog rule.

    private def ucObjectStore(objectStore: String, unityCatalog: String): PipelineConfig =
        parse(
            s"""{"name":"p",
               |"source":{"fileAttributes":{"csvAttributes":{}},"schemaProperties":{"fields":[{"name":"id","type":"string"}]}},
               |"destination":{"objectStore":{"prefixKey":"p",$objectStore}},
               |"unityCatalog":$unityCatalog}""".stripMargin
        )

    private val ucRegister = """{"enabled":true,"credentialsSecret":"uc_fixture","catalog":"unity","schema":"default"}"""

    private def anyError(cfg: PipelineConfig): Option[String] =
        try { PipelineValidatorUtil.validate(cfg); None }
        catch { case scala.util.control.NonFatal(e) => Some(String.valueOf(e.getMessage)) }

    test("unityCatalog on an objectStore iceberg destination with credentialsSecret and catalog is accepted (s3)") {
        val cfg = ucObjectStore(""""fileFormat":"iceberg","provider":"s3"""", ucRegister)
        assert(
            cfg.unityCatalog.credentialsSecret == "uc_fixture" && cfg.unityCatalog.catalog == "unity",
            s"fixture must parse the new fields: ${cfg.unityCatalog}"
        )
        val err = validationError(cfg)
        assert(err.isDefined && err.get.contains(s3BucketSentinel), s"expected the s3 bucket sentinel (UC rule passed), got: $err")
        assert(!err.get.contains("unityCatalog"), err.get)
        // fileFormat is case-insensitive for this rule.
        val upper = validationError(ucObjectStore(""""fileFormat":"ICEBERG","provider":"s3"""", ucRegister))
        assert(!upper.exists(_.contains("unityCatalog")), s"ICEBERG must pass the UC rule, got: $upper")
    }

    test("unityCatalog on an objectStore iceberg destination with credentialsSecret and catalog is accepted (minio)") {
        val cfg = ucObjectStore(""""fileFormat":"iceberg","provider":"minio"""", ucRegister)
        val err = anyError(cfg)
        assert(!err.exists(_.contains("unityCatalog")), s"minio + iceberg + unityCatalog must pass the UC rule, got: $err")
        val noProvider = anyError(ucObjectStore(""""fileFormat":"iceberg"""", ucRegister))
        assert(!noProvider.exists(_.contains("unityCatalog")), s"default provider (minio) must pass the UC rule, got: $noProvider")
    }

    test("unityCatalog on an objectStore iceberg destination without catalog or credentialsSecret is rejected") {
        val noCatalog = validationError(ucObjectStore(""""fileFormat":"iceberg","provider":"s3"""", """{"enabled":true,"credentialsSecret":"uc_fixture"}"""))
        assert(noCatalog.exists(_.contains("'unityCatalog.catalog'")), s"got: $noCatalog")
        assert(noCatalog.exists(_.contains("required")), s"got: $noCatalog")

        val blankCatalog = validationError(ucObjectStore(""""fileFormat":"iceberg"""", """{"enabled":true,"credentialsSecret":"uc_fixture","catalog":"  "}"""))
        assert(blankCatalog.exists(_.contains("'unityCatalog.catalog'")), s"blank catalog must be rejected, got: $blankCatalog")

        val noSecret = validationError(ucObjectStore(""""fileFormat":"iceberg"""", """{"enabled":true,"catalog":"unity"}"""))
        assert(noSecret.exists(_.contains("'unityCatalog.credentialsSecret'")), s"got: $noSecret")
    }

    test("unityCatalog on a parquet (or orc / default-format) objectStore destination is rejected") {
        Seq(""""fileFormat":"parquet"""", """"fileFormat":"orc"""", """"provider":"minio"""").foreach { os =>
            val err = validationError(ucObjectStore(os, ucRegister))
            assert(err.exists(m => m.contains("'unityCatalog.enabled'") && m.contains("iceberg")), s"$os: got $err")
            assert(!err.exists(_.contains(ucDatabricksOnly)), s"$os must get the object-store message, not the Databricks-only one: $err")
        }
    }

    test("Databricks with unityCatalog is still accepted and ignores the object-store fields") {
        assert(validationError(ucConfig(databricksDb)).isEmpty)
        val withExtras =
            ucConfig(databricksDb, unityCatalog = """{"enabled":true,"catalog":"other","credentialsSecret":"other_secret","schema":"x","register":false}""")
        assert(withExtras.unityCatalog.catalog == "other", s"fixture must parse the new fields: ${withExtras.unityCatalog}")
        assert(validationError(withExtras).isEmpty, s"got: ${validationError(withExtras)}")
        // Without the object-store fields at all.
        val bare = ucConfig(databricksDb, unityCatalog = """{"enabled":true}""")
        assert(bare.unityCatalog.credentialsSecret == null && bare.unityCatalog.catalog == null)
        assert(validationError(bare).isEmpty)
    }

    test("postgres with unityCatalog is still rejected, and the message no longer says object-store Iceberg is planned") {
        val err = validationError(ucConfig(""""usePostgres":true""", unityCatalog = ucRegister))
        assert(err.exists(_.contains(ucDatabricksOnly)), s"got: $err")
        assert(!err.exists(_.contains("planned")), s"got: $err")
    }

    test("unityCatalog register knob defaults on and schema defaults to 'default' under Jackson (ParameterNamesModule) and Gson") {
        val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val j = mapper.readValue("""{"enabled":true,"credentialsSecret":"uc_fixture","catalog":"unity"}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(j.register == null && j.registerOn, s"Jackson absent register: $j")
        assert(j.credentialsSecret == "uc_fixture" && j.catalog == "unity", s"$j")
        assert(j.schemaOrDefault == "default", s"Jackson absent schema: $j")
        assert(j.commentsOn && j.tagsOn && j.propertiesOn && j.lineageOn, s"$j")
        val off = mapper.readValue("""{"enabled":true,"register":false,"schema":"sales"}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(!off.registerOn && off.schemaOrDefault == "sales", s"Jackson register=false: $off")
        val cfg = mapper.readValue(s"""{"name":"p","unityCatalog":$ucRegister}""", classOf[PipelineConfig])
        assert(cfg.unityCatalog.registerOn && cfg.unityCatalog.catalog == "unity" && cfg.unityCatalog.schemaOrDefault == "default", s"$cfg")

        val g = gson.fromJson("""{"enabled":true,"credentialsSecret":"uc_fixture","catalog":"unity"}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(g.registerOn && g.schemaOrDefault == "default", s"Gson absent register/schema: $g")
        val gOff = gson.fromJson("""{"enabled":true,"register":false}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(!gOff.registerOn, s"Gson register=false: $gOff")
        assert(!gson.fromJson(gson.toJson(gOff), classOf[ai.datris.model.UnityCatalogSync]).registerOn, "round trip keeps register=false")
        val none = new ai.datris.model.UnityCatalogSync()
        assert(none.registerOn && none.schemaOrDefault == "default" && none.catalog == null && none.credentialsSecret == null, s"no-arg: $none")
    }

    // --- Story: Unity Catalog 5: Iceberg via RESTCatalog -----------------------
    // (plans/stories/unity-catalog-5-iceberg-restcatalog.md), Step 2 / Acceptance
    // bullet 4. `unityCatalog.catalogMode` is a nullable String: null ⇒ register
    // (story-4 behaviour), `register` or `rest` case-insensitively, anything
    // else rejected; Databricks rejects any non-null value; `rest` needs the
    // register knob on. Accepted s3 configs reach the s3 bucket sentinel.

    private val ucModeRegister = """"enabled":true,"credentialsSecret":"uc_fixture","catalog":"unity","schema":"default""""
    private def ucMode(mode: String, extra: String = ""): String = s"""{$ucModeRegister,"catalogMode":"$mode"$extra}"""
    private val icebergS3 = """"fileFormat":"iceberg","provider":"s3""""

    private def passesUcRule(cfg: PipelineConfig): Unit = {
        val err = validationError(cfg)
        assert(err.isDefined && err.get.contains(s3BucketSentinel), s"expected the s3 bucket sentinel (UC rule passed), got: $err")
        assert(!err.get.contains("unityCatalog"), err.get)
    }

    test("catalogMode absent (null) on an objectStore iceberg destination is accepted") {
        val cfg = ucObjectStore(icebergS3, ucRegister)
        assert(cfg.unityCatalog.catalogMode == null, s"absent catalogMode must parse as null: ${cfg.unityCatalog}")
        assert(cfg.unityCatalog.catalogModeOrDefault == "register" && !cfg.unityCatalog.restMode, s"${cfg.unityCatalog}")
        passesUcRule(cfg)
    }

    test("catalogMode register on an objectStore iceberg destination is accepted") {
        val cfg = ucObjectStore(icebergS3, ucMode("register"))
        assert(cfg.unityCatalog.catalogMode == "register", s"fixture must parse catalogMode: ${cfg.unityCatalog}")
        passesUcRule(cfg)
        passesUcRule(ucObjectStore(icebergS3, ucMode("Register")))
    }

    test("catalogMode rest with credentialsSecret and catalog is accepted; without them it is rejected") {
        val cfg = ucObjectStore(icebergS3, ucMode("rest"))
        assert(cfg.unityCatalog.restMode, s"${cfg.unityCatalog}")
        passesUcRule(cfg)
        passesUcRule(ucObjectStore(icebergS3, ucMode("REST")))

        val noCatalog = validationError(ucObjectStore(icebergS3, """{"enabled":true,"credentialsSecret":"uc_fixture","catalogMode":"rest"}"""))
        assert(noCatalog.exists(_.contains("'unityCatalog.catalog'")), s"got: $noCatalog")
        val noSecret = validationError(ucObjectStore(icebergS3, """{"enabled":true,"catalog":"unity","catalogMode":"rest"}"""))
        assert(noSecret.exists(_.contains("'unityCatalog.credentialsSecret'")), s"got: $noSecret")
    }

    test("catalogMode bogus is rejected with the register-rest-or-managed message") {
        // Unity Catalog 7 adds the third value to the message.
        val cfg = ucObjectStore(icebergS3, ucMode("bogus"))
        assert(cfg.unityCatalog.catalogMode == "bogus", s"${cfg.unityCatalog}")
        val err = validationError(cfg)
        assert(err.exists(_.contains("'unityCatalog.catalogMode' must be 'register', 'rest' or 'managed'")), s"got: $err")
    }

    test("catalogMode rest with register:false is rejected") {
        val err = validationError(ucObjectStore(icebergS3, ucMode("rest", extra = ""","register":false""")))
        assert(err.exists(_.contains("'unityCatalog.catalogMode: rest' requires the register knob on")), s"got: $err")
        // register:false without rest is still fine (story 4).
        passesUcRule(ucObjectStore(icebergS3, s"""{$ucModeRegister,"register":false}"""))
    }

    test("Databricks destination with any catalogMode is rejected") {
        Seq("rest", "register", "REST").foreach { mode =>
            val cfg = ucConfig(databricksDb, unityCatalog = s"""{"enabled":true,"catalogMode":"$mode"}""")
            assert(cfg.unityCatalog.catalogMode == mode, s"${cfg.unityCatalog}")
            val err = validationError(cfg)
            assert(err.exists(_.contains("'unityCatalog.catalogMode' applies to object-store Iceberg destinations only")), s"$mode: got $err")
        }
        // An invalid value on Databricks is rejected by either rule.
        val bogus = validationError(ucConfig(databricksDb, unityCatalog = """{"enabled":true,"catalogMode":"bogus"}"""))
        assert(bogus.exists(m => m.contains("'unityCatalog.catalogMode'")), s"bogus: got $bogus")
        // Without catalogMode Databricks still validates.
        assert(validationError(ucConfig(databricksDb)).isEmpty)
    }

    test("catalogMode defaults to register under Jackson (ParameterNamesModule) and Gson; REST reads as rest") {
        val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val j = mapper.readValue("""{"enabled":true,"credentialsSecret":"uc_fixture","catalog":"unity"}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(j.catalogMode == null && j.catalogModeOrDefault == "register" && !j.restMode, s"Jackson absent catalogMode: $j")
        assert(j.registerOn && j.catalog == "unity", s"$j")
        val jr = mapper.readValue("""{"enabled":true,"catalogMode":"REST"}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(jr.catalogModeOrDefault == "rest" && jr.restMode, s"Jackson REST: $jr")
        val js = mapper.readValue("""{"enabled":true,"catalogMode":"  Register "}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(js.catalogModeOrDefault == "register" && !js.restMode, s"Jackson padded Register: $js")
        val blank = mapper.readValue("""{"enabled":true,"catalogMode":"  "}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(blank.catalogModeOrDefault == "register", s"blank catalogMode: $blank")
        val cfg = mapper.readValue(s"""{"name":"p","unityCatalog":${ucMode("rest")}}""", classOf[PipelineConfig])
        assert(cfg.unityCatalog.restMode && cfg.unityCatalog.registerOn && cfg.unityCatalog.catalog == "unity", s"$cfg")

        val g = gson.fromJson("""{"enabled":true,"credentialsSecret":"uc_fixture","catalog":"unity"}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(g.catalogMode == null && g.catalogModeOrDefault == "register" && !g.restMode, s"Gson absent catalogMode: $g")
        val gr = gson.fromJson("""{"enabled":true,"catalogMode":"REST"}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(gr.restMode, s"Gson REST: $gr")
        assert(gson.fromJson(gson.toJson(gr), classOf[ai.datris.model.UnityCatalogSync]).restMode, "round trip keeps catalogMode")
        val none = new ai.datris.model.UnityCatalogSync()
        assert(none.catalogMode == null && none.catalogModeOrDefault == "register" && !none.restMode && none.registerOn, s"no-arg: $none")
    }

    // --- Story: Unity Catalog 7: Databricks-managed Iceberg mode ---------------
    // (plans/stories/unity-catalog-7-managed-iceberg.md), Step 1 / Acceptance
    // bullet 1. `managed` is a third value of the nullable catalogMode String
    // (`managedMode`, and `throughCatalog` = rest or managed). Any provider is
    // accepted at save time (the provider rule needs the secret, so it is a
    // run-time refusal); the register knob must stay on; deleteBeforeWrite,
    // Databricks destinations and non-Iceberg formats are rejected.

    test("catalogMode managed on an s3 iceberg destination is accepted") {
        val cfg = ucObjectStore(icebergS3, ucMode("managed"))
        assert(cfg.unityCatalog.catalogMode == "managed", s"${cfg.unityCatalog}")
        assert(cfg.unityCatalog.managedMode && cfg.unityCatalog.throughCatalog && !cfg.unityCatalog.restMode, s"${cfg.unityCatalog}")
        passesUcRule(cfg)
        passesUcRule(ucObjectStore(icebergS3, ucMode("MANAGED")))
        // rest is still through the catalog, register is not.
        val rest = ucObjectStore(icebergS3, ucMode("rest")).unityCatalog
        assert(rest.throughCatalog && !rest.managedMode, s"$rest")
        val register = ucObjectStore(icebergS3, ucRegister).unityCatalog
        assert(!register.throughCatalog && !register.managedMode, s"$register")
    }

    test("catalogMode managed on a minio iceberg destination is accepted (provider is a run-time rule)") {
        val err = anyError(ucObjectStore(""""fileFormat":"iceberg","provider":"minio"""", ucMode("managed")))
        assert(!err.exists(m => m.contains("unityCatalog") || m.contains("catalogMode")), s"minio + managed must pass the UC rule, got: $err")
        val noProvider = anyError(ucObjectStore(""""fileFormat":"iceberg"""", ucMode("managed")))
        assert(!noProvider.exists(m => m.contains("unityCatalog") || m.contains("catalogMode")), s"default provider must pass the UC rule, got: $noProvider")
    }

    test("managed is rejected with deleteBeforeWrite, with register:false, on a Databricks destination, and on parquet") {
        val withDelete = validationError(ucObjectStore(icebergS3 + ""","deleteBeforeWrite":true""", ucMode("managed")))
        assert(
            withDelete.exists(_.contains("'deleteBeforeWrite' cannot be combined with catalogMode 'managed': the catalog owns the table's files")),
            s"got: $withDelete"
        )
        // deleteBeforeWrite with rest is not a validation error (the run-time guard handles a committed table).
        passesUcRule(ucObjectStore(icebergS3 + ""","deleteBeforeWrite":true""", ucMode("rest")))

        val registerOff = validationError(ucObjectStore(icebergS3, ucMode("managed", extra = ""","register":false""")))
        assert(registerOff.exists(m => m.contains("requires the register knob on") && m.contains("managed")), s"got: $registerOff")

        val dbx = ucConfig(databricksDb, unityCatalog = """{"enabled":true,"catalogMode":"managed"}""")
        val dbxErr = validationError(dbx)
        assert(dbxErr.exists(_.contains("'unityCatalog.catalogMode' applies to object-store Iceberg destinations only")), s"got: $dbxErr")

        val parquet = validationError(ucObjectStore(""""fileFormat":"parquet","provider":"s3"""", ucMode("managed")))
        assert(parquet.exists(m => m.contains("'unityCatalog.enabled'") && m.contains("iceberg")), s"got: $parquet")
    }

    test("catalogMode managed parses under Jackson (ParameterNamesModule) and Gson and round-trips") {
        val mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val j = mapper.readValue("""{"enabled":true,"catalogMode":" Managed "}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(j.managedMode && j.throughCatalog && !j.restMode && j.catalogModeOrDefault == "managed", s"$j")
        val g = gson.fromJson("""{"enabled":true,"catalogMode":"managed"}""", classOf[ai.datris.model.UnityCatalogSync])
        assert(g.managedMode && g.throughCatalog, s"$g")
        assert(gson.fromJson(gson.toJson(g), classOf[ai.datris.model.UnityCatalogSync]).managedMode, "round trip keeps managed")
        val none = new ai.datris.model.UnityCatalogSync()
        assert(!none.managedMode && !none.throughCatalog, s"no-arg: $none")
    }

    // --- Field protection (story: field-protection-1-stage) ------------------
    // Configs are parsed with Gson, which drops an unknown `protect` key today;
    // `parsesProtect` proves the fixture carries the policy so the rule, not
    // Gson ignoring the key, is what each case exercises. A postgres
    // destination validates offline (no existing-pipeline lookup).

    private def protectConfig(
        sourceFields: String,
        destFields: String = null,
        fileAttributes: String = """"csvAttributes":{"header":true}""",
        database: String = """"usePostgres":true""",
        keyFields: String = null
    ): PipelineConfig = {
        val destSchema = if (destFields == null) "" else s""","schemaProperties":{"fields":$destFields}"""
        val keys = if (keyFields == null) "" else s""","keyFields":$keyFields"""
        parse(
            s"""{"name":"fp",
               |"source":{"fileAttributes":{$fileAttributes},"schemaProperties":{"fields":$sourceFields}},
               |"destination":{"database":{"dbName":"datris","schema":"public","table":"fp",$database$keys}$destSchema}}""".stripMargin
        )
    }

    private def parsesProtect(cfg: PipelineConfig): Unit =
        assert(gson.toJson(cfg.source.schemaProperties).contains("\"protect\""), "fixture must parse SchemaField.protect")

    test("unknown protect method is rejected") {
        val cfg = protectConfig("""[{"name":"mrn","type":"string","protect":{"method":"scramble"}}]""")
        parsesProtect(cfg)
        val err = validationError(cfg)
        assert(err.contains("Field 'mrn': unknown protect.method 'scramble' (hmac, mask, redact, drop, encrypt)"), s"got: $err")
    }

    // Story 5 (field-protection-5-encrypt-reveal): encrypt is accepted; fpe and tokenize stay reserved.
    test("fpe and tokenize are still rejected as not yet supported") {
        Seq("fpe", "tokenize").foreach { m =>
            val cfg = protectConfig(s"""[{"name":"mrn","type":"string","protect":{"method":"$m"}}]""")
            parsesProtect(cfg)
            val err = validationError(cfg)
            assert(err.exists(e => e.contains("'mrn'") && e.contains(s"'$m' is not yet supported")), s"$m: got $err")
        }
    }

    test("preserve on hmac is rejected") {
        val cfg = protectConfig("""[{"name":"mrn","type":"string","protect":{"method":"hmac","preserve":"last4"}}]""")
        parsesProtect(cfg)
        val err = validationError(cfg)
        assert(err.exists(e => e.contains("mrn") && e.contains("preserve")), s"got: $err")
        // preserve outside last4/domain/year is rejected on mask too.
        val bad = protectConfig("""[{"name":"phone","type":"string","protect":{"method":"mask","preserve":"first3"}}]""")
        val err2 = validationError(bad)
        assert(err2.exists(e => e.contains("phone") && e.contains("preserve")), s"got: $err2")
    }

    test("hmac on an int source field is rejected") {
        val cfg = protectConfig("""[{"name":"mrn","type":"int","protect":{"method":"hmac"}}]""")
        parsesProtect(cfg)
        val err = validationError(cfg)
        assert(err.exists(e => e.contains("mrn") && e.contains("string")), s"got: $err")
    }

    test("hmac on a field whose destination type is int is rejected") {
        val cfg = protectConfig(
            """[{"name":"id","type":"string"},{"name":"mrn","type":"string","protect":{"method":"hmac"}}]""",
            destFields = """[{"name":"id","type":"string"},{"name":"MRN","type":"int"}]"""
        )
        parsesProtect(cfg)
        val err = validationError(cfg)
        assert(err.exists(e => e.toLowerCase.contains("mrn") && e.contains("string")), s"destination matched case-insensitively; got: $err")
    }

    test("drop of a keyFields column is rejected") {
        val cfg = protectConfig(
            """[{"name":"id","type":"string"},{"name":"ssn","type":"string","protect":{"method":"drop"}}]""",
            keyFields = """["ssn"]"""
        )
        parsesProtect(cfg)
        val err = validationError(cfg)
        assert(err.exists(e => e.contains("ssn") && e.toLowerCase.contains("drop") && !e.contains("Key field: ")), s"got: $err")
    }

    test("mask or redact on a keyFields column is rejected; hmac is accepted") {
        Seq("""{"method":"mask","preserve":"last4"}""", """{"method":"redact"}""").foreach { policy =>
            val cfg = protectConfig(
                s"""[{"name":"id","type":"string"},{"name":"Account_No","type":"string","protect":$policy}]""",
                keyFields = """["account_no"]"""
            )
            val err = validationError(cfg)
            assert(err.exists(e => e.contains("Account_No") && e.contains("only hmac keeps rows distinct")), s"$policy got: $err")
        }
        val ok = protectConfig(
            """[{"name":"id","type":"string"},{"name":"account_no","type":"string","protect":{"method":"hmac"}}]""",
            keyFields = """["account_no"]"""
        )
        assert(validationError(ok).isEmpty, s"got: ${validationError(ok)}")
    }

    test("protect on an XML source is rejected") {
        val cfg = parse(
            """{"name":"fp",
              |"source":{"fileAttributes":{"xmlAttributes":{}},"schemaProperties":{"fields":[{"name":"_xml","type":"string","protect":{"method":"redact"}}]}},
              |"destination":{"scratch":{}}}""".stripMargin
        )
        parsesProtect(cfg)
        val err = validationError(cfg)
        assert(err.exists(_.contains("Field protection needs a delimited or JSON source")), s"got: $err")
    }

    test("a valid hmac/mask/redact/drop set passes") {
        val src =
            """[{"name":"id","type":"string"},
              |{"name":"mrn","type":"string","protect":{"method":"hmac"}},
              |{"name":"email","type":"string","protect":{"method":"mask","preserve":"domain"}},
              |{"name":"phone","type":"string","protect":{"method":"mask","preserve":"last4"}},
              |{"name":"dob","type":"string","protect":{"method":"mask","preserve":"year"}},
              |{"name":"code","type":"string","protect":{"method":"mask"}},
              |{"name":"notes","type":"string","protect":{"method":"redact"}},
              |{"name":"age","type":"int","protect":{"method":"drop"}}]""".stripMargin
        val dst =
            """[{"name":"id","type":"string"},{"name":"MRN","type":"string"},{"name":"email","type":"string"},
              |{"name":"phone","type":"string"},{"name":"dob","type":"string"},{"name":"code","type":"string"},
              |{"name":"notes","type":"string"}]""".stripMargin
        val cfg = protectConfig(src, destFields = dst, keyFields = """["id"]""")
        parsesProtect(cfg)
        assert(validationError(cfg).isEmpty, s"got: ${validationError(cfg)}")
        // A JSON source keeps its single `_json` field and names the protected
        // top-level keys beside it (FieldProtection rewrites top-level keys only).
        val json = protectConfig(
            """[{"name":"_json","type":"string"},
              |{"name":"mrn","type":"string","protect":{"method":"hmac"}},
              |{"name":"ssn","type":"string","protect":{"method":"drop"}}]""".stripMargin,
            fileAttributes = """"jsonAttributes":{}"""
        )
        assert(validationError(json).isEmpty, s"JSON source: ${validationError(json)}")
    }

    // --- Field protection 5: encrypt (plans/stories/field-protection-5-encrypt-reveal.md) ---
    // Calls only PipelineValidatorUtil (through `validationError`); encrypt keeps
    // story 1's string-in/string-out rule.

    test("encrypt on a string field passes") {
        val cfg = protectConfig(
            """[{"name":"id","type":"string"},{"name":"email","type":"string","protect":{"method":"encrypt"}}]""",
            destFields = """[{"name":"id","type":"string"},{"name":"email","type":"string"}]""",
            keyFields = """["id"]"""
        )
        parsesProtect(cfg)
        assert(validationError(cfg).isEmpty, s"got: ${validationError(cfg)}")
        val json = protectConfig(
            """[{"name":"_json","type":"string"},{"name":"email","type":"string","protect":{"method":"encrypt"}}]""",
            fileAttributes = """"jsonAttributes":{}"""
        )
        assert(validationError(json).isEmpty, s"JSON source: ${validationError(json)}")
    }

    test("encrypt on an int field is rejected") {
        val src = protectConfig("""[{"name":"ssn","type":"int","protect":{"method":"encrypt"}}]""")
        parsesProtect(src)
        val err = validationError(src)
        assert(err.exists(e => e.contains("'ssn'") && e.contains("'encrypt'") && e.contains("string") && !e.contains("not yet supported")), s"got: $err")
        val dst = protectConfig(
            """[{"name":"id","type":"string"},{"name":"ssn","type":"string","protect":{"method":"encrypt"}}]""",
            destFields = """[{"name":"id","type":"string"},{"name":"ssn","type":"int"}]"""
        )
        val err2 = validationError(dst)
        assert(err2.exists(e => e.contains("ssn") && e.contains("destination field type must be 'string'")), s"got: $err2")
    }

    test("encrypt on a keyFields column is rejected (a fresh IV per value would split one key into many)") {
        val cfg = protectConfig(
            """[{"name":"id","type":"string"},{"name":"account_no","type":"string","protect":{"method":"encrypt"}}]""",
            keyFields = """["account_no"]"""
        )
        val err = validationError(cfg)
        assert(err.exists(e => e.contains("account_no") && e.contains("only hmac keeps rows distinct")), s"got: $err")
    }
}
