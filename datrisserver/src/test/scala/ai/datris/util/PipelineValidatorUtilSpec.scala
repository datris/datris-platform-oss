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
}
