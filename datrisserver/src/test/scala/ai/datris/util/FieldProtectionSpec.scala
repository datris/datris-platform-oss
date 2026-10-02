package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{JsonObject, JsonParser}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.io.{BufferedReader, ByteArrayInputStream, InputStream}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/** Field protection stage (plans/stories/field-protection-1-stage.md).
  *
  * Seams this spec relies on (all on `object FieldProtection`, `null` = the
  * production default):
  *
  * {{{
  * object FieldProtection {
  *     def apply(ctx: JobContext): JobContext
  *     private[datris] def protectValue(policy: ProtectionPolicy, value: String, key: Array[Byte]): String
  *     // fixed key instead of FieldProtectionKey.ensure() (no Vault in unit tests)
  *     @volatile private[datris] var keyOverride: Array[Byte] = null
  *     // object store used by purgeRaw for the bulk-upload listing and the deletes
  *     // (null → the ObjectStoreUtil singleton, which MinIOUtility.build() needs a live MinIO for)
  *     @volatile private[datris] var objectStoreOverride: ObjectStoreUtility = null
  *     // (category, action, resourceType, resourceName, metadata, outcome, errorMessage)
  *     // (null → AuditLog.system, a no-op in unit tests)
  *     @volatile private[datris] var auditOverride: (String, String, String, String, JsonObject, String, String) => Unit = null
  * }
  * }}}
  *
  * `hmac` is lowercase hex of HMAC-SHA256 keyed with the bytes `ensure()` (or
  * `keyOverride`) returns, over the UTF-8 value.
  *
  * Story 5 (plans/stories/field-protection-5-encrypt-reveal.md) adds `encrypt`
  * and one more seam on `object FieldProtection`:
  *
  * {{{
  *     // fixed (version, key) instead of FieldProtectionKey.encryptionKey() (no Vault in unit tests)
  *     @volatile private[datris] var encryptionKeyOverride: (Int, Array[Byte]) = null
  * }}}
  *
  * and checks the column with `FieldCipher.decrypt(keyLookup, pipeline, field, token)`
  * (see FieldCipherSpec), bound to `ctx.config.name` and the source field name.
  */
class FieldProtectionSpec extends AnyFunSuite with BeforeAndAfterEach {

    private val Key: Array[Byte] = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
    private val OtherKey: Array[Byte] = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8)

    private val EncKey: Array[Byte] = Array.tabulate[Byte](32)(i => (i * 7 + 3).toByte)

    private val Bucket = "datris-raw"

    // ---- seams -----------------------------------------------------------------

    private val audits = new ListBuffer[(String, String, String, String, JsonObject, String, String)]()

    /** In-memory object store: records deletes, lists a fixed set of keys, optionally fails deletes. */
    private class FakeStore(listing: List[String] = Nil, failDeletes: Boolean = false) extends ObjectStoreUtility {
        val deleted = new ListBuffer[(String, String)]()
        override def getBucket(url: String): String = new URI(url).getHost
        override def getKey(url: String): String = { val p = new URI(url).getPath; if (p.startsWith("/")) p.substring(1) else p }
        override def getURI(path: String): URI = new URI(path)
        override def getObjectMetadata(bucketName: String, key: String): StoredObjectMetadata = StoredObjectMetadata(0L, "text/csv")
        override def readBucketObject(bucketName: String, key: String): Option[String] = None
        override def readBucketObjectFirstRow(bucketName: String, key: String): Option[String] = None
        override def getBufferedReader(bucketName: String, key: String): BufferedReader = throw new UnsupportedOperationException
        override def getInputStream(bucketName: String, key: String): InputStream = throw new UnsupportedOperationException
        override def copyBucketObject(sb: String, sk: String, db: String, dk: String): Unit = throw new UnsupportedOperationException
        override def writeBucketObject(bucketName: String, key: String, content: String): Unit = throw new UnsupportedOperationException
        override def writeBucketObjectFromStream(b: String, k: String, s: ByteArrayInputStream, l: Long): Unit = throw new UnsupportedOperationException
        override def deleteFolder(bucketName: String, key: String): Unit = throw new UnsupportedOperationException("purge must not delete folders")
        override def deleteBucketObject(bucketName: String, key: String): Unit = {
            if (failDeletes) throw new RuntimeException("Access Denied")
            deleted += ((bucketName, key))
        }
        override def listObjects(bucketName: String, key: String): List[String] = listing.filter(_.startsWith(key))
        override def listSummaries(bucketName: String, key: String): List[StoredObjectSummary] = listObjects(bucketName, key).map(StoredObjectSummary(_, 1L))
        override def keyExists(bucketName: String, key: String): Boolean = listing.contains(key)
    }

    private var store: FakeStore = _

    override def beforeEach(): Unit = {
        audits.clear()
        store = new FakeStore()
        FieldProtection.keyOverride = Key
        FieldProtection.encryptionKeyOverride = (1, EncKey)
        FieldProtection.objectStoreOverride = store
        FieldProtection.auditOverride = (c, a, rt, rn, md, o, e) => audits += ((c, a, rt, rn, md, o, e))
    }

    override def afterEach(): Unit = {
        FieldProtection.keyOverride = null
        FieldProtection.encryptionKeyOverride = null
        FieldProtection.objectStoreOverride = null
        FieldProtection.auditOverride = null
    }

    // ---- fixtures (helpers after ProvenanceStamperSpec) --------------------------

    private class RecordingStatusUtil extends StatusUtil {
        val messages = new ListBuffer[(String, String, String)]()
        override def overrideProcessName(processName: String): Unit = ()
        override def info(state: String, description: String): Unit = messages += (("info", state, description))
        override def warn(state: String, description: String): Unit = messages += (("warning", state, description))
        override def error(state: String, description: String): Unit = messages += (("error", state, description))
        override def scratchResult(result: ScratchResult): Unit = ()
        def descriptions: List[String] = messages.map(_._3).toList
    }

    /** Rows of a staged payload, read through the streaming iterator (never `Data.rows`). */
    private def rowsOf(data: Data): List[String] = {
        val it = data.rowIterator()
        try it.toList
        finally it.close()
    }

    private def hmacHex(key: Array[Byte], value: String): String = {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(new SecretKeySpec(key, "HmacSHA256"))
        mac.doFinal(value.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
    }

    private def policy(method: String, preserve: String = null): ProtectionPolicy = ProtectionPolicy(method, preserve, null)

    private def field(name: String, protect: ProtectionPolicy = null, tpe: String = "string"): SchemaField = SchemaField(name, tpe, protect)

    private def jlist[T](xs: T*): java.util.List[T] = new java.util.ArrayList[T](xs.asJava)

    private val Header = List("id", "name", "mrn", "email", "ssn")

    /** id, name unprotected; mrn=hmac, email=mask:domain, ssn=drop. */
    private def protectedSourceFields: java.util.List[SchemaField] = jlist(
        field("id"),
        field("name"),
        field("mrn", policy("hmac")),
        field("email", policy("mask", "domain")),
        field("ssn", policy("drop"))
    )

    private def plainFields(names: String*): java.util.List[SchemaField] = jlist(names.map(n => SchemaField(n, "string")): _*)

    private def csvConfig(srcFields: java.util.List[SchemaField], protection: ProtectionConfig = null): PipelineConfig =
        PipelineConfig(
            name = "patients",
            source = Source(schemaProperties = SchemaProperties("db", srcFields), fileAttributes = FileAttributes(csvAttributes = CsvAttributes())),
            destination = Destination(schemaProperties = SchemaProperties("db", plainFields(Header: _*))),
            protection = protection
        )

    private val Rows = List(
        "1,\"Smith, Jane\",MRN-001,jane@example.com,123-45-6789",
        "2,Bob,MRN-002,bob@example.org,987-65-4321",
        "3,\"Lee, Ann\",MRN-001,,"
    )

    private val RawValues = List("MRN-001", "MRN-002", "jane@example.com", "bob@example.org", "123-45-6789", "987-65-4321", "jane", "bob@")

    private def delimitedCtx(cfg: PipelineConfig, metadata: PipelineMetadata = null, status: StatusUtil = new RecordingStatusUtil): JobContext =
        JobContext(
            "run-fp-1",
            metadata,
            Data(100L, Header, Header.map(SchemaField(_, "string")), Rows, null),
            cfg,
            null,
            INITIALIZED,
            null,
            status
        )

    private def jsonConfig(srcFields: java.util.List[SchemaField]): PipelineConfig =
        PipelineConfig(
            name = "patients_json",
            source = Source(schemaProperties = SchemaProperties("db", srcFields), fileAttributes = FileAttributes(jsonAttributes = JsonAttributes())),
            destination = Destination(schemaProperties = SchemaProperties("db", plainFields("_json")))
        )

    /** NDJSON staged file written line for line (so a blank line is really in the file). */
    private def ndjsonCtx(cfg: PipelineConfig, lines: List[String], status: StatusUtil = new RecordingStatusUtil): JobContext = {
        val (path, writer) = StagingArea.newWriter("data", StagedFormat.NdJson)
        try writer.write(lines.mkString("\n"))
        finally writer.close()
        val bytes = Files.size(path)
        val staged = StagedPayload(path.toString, StagedFormat.NdJson, lines.size.toLong, bytes)
        JobContext("run-fp-2", null, new Data(bytes, null, null, staged, null), cfg, null, INITIALIZED, null, status)
    }

    private val uploadMetadata = PipelineMetadata("patients", "patients.pub-1.a.pipeline.csv", "s3://" + Bucket + "/uploads/", "pub-1", bulkUpload = false)

    // ==========================================================================
    // protectValue
    // ==========================================================================

    test("hmac is deterministic, 64 lowercase hex chars, and changes with the key") {
        val a = FieldProtection.protectValue(policy("hmac"), "MRN-001", Key)
        val b = FieldProtection.protectValue(policy("hmac"), "MRN-001", Key)
        assert(a == b, "same key, same value → same token")
        assert(a.matches("[0-9a-f]{64}"), s"64 lowercase hex chars, got $a")
        assert(a == hmacHex(Key, "MRN-001"), "HMAC-SHA256(key, UTF-8 value), lowercase hex")
        assert(FieldProtection.protectValue(policy("hmac"), "MRN-002", Key) != a, "different value → different token")
        assert(FieldProtection.protectValue(policy("hmac"), "MRN-001", OtherKey) != a, "different key → different token")
    }

    test("mask default masks every char") {
        assert(FieldProtection.protectValue(policy("mask"), "secret", Key) == "******")
        assert(FieldProtection.protectValue(policy("mask"), "a b-c", Key) == "*****")
        assert(FieldProtection.protectValue(policy("mask"), "x", Key) == "*")
    }

    test("mask last4 keeps the last four and masks short values entirely") {
        assert(FieldProtection.protectValue(policy("mask", "last4"), "123-45-6789", Key) == "*******6789")
        assert(FieldProtection.protectValue(policy("mask", "last4"), "12345", Key) == "*2345")
        assert(FieldProtection.protectValue(policy("mask", "last4"), "1234", Key) == "****", "fewer than 5 chars → all masked")
        assert(FieldProtection.protectValue(policy("mask", "last4"), "12", Key) == "**")
    }

    test("mask domain keeps the part after @") {
        assert(FieldProtection.protectValue(policy("mask", "domain"), "jane@example.com", Key) == "***@example.com")
        assert(FieldProtection.protectValue(policy("mask", "domain"), "a@b.org", Key) == "***@b.org")
        assert(FieldProtection.protectValue(policy("mask", "domain"), "no-at-sign", Key) == "**********", "no @ → all masked")
    }

    test("mask year keeps a leading 4-digit year") {
        assert(FieldProtection.protectValue(policy("mask", "year"), "1987-06-05", Key) == "1987-**-**")
        assert(FieldProtection.protectValue(policy("mask", "year"), "June 1987", Key) == "*********", "no leading year → all masked")
        assert(FieldProtection.protectValue(policy("mask", "year"), "87-06-05", Key) == "********", "two-digit year is not a leading 4-digit year")
    }

    test("redact replaces the value with [REDACTED]") {
        assert(FieldProtection.protectValue(policy("redact"), "free text about a patient", Key) == "[REDACTED]")
        assert(FieldProtection.protectValue(policy("redact"), "x", Key) == "[REDACTED]")
    }

    test("empty values are unchanged for every method") {
        val all = List(policy("hmac"), policy("mask"), policy("mask", "last4"), policy("mask", "domain"), policy("mask", "year"), policy("redact"))
        all.foreach { p =>
            assert(FieldProtection.protectValue(p, "", Key) == "", s"$p on empty")
            assert(FieldProtection.protectValue(p, null, Key) == null, s"$p on null")
        }
    }

    test("reserved methods throw DatrisException") {
        // Story 5: encrypt leaves the reserved list.
        (List("fpe", "tokenize") ++ List("rot13")).foreach { m =>
            val e = intercept[DatrisException](FieldProtection.protectValue(policy(m), "MRN-001", Key))
            assert(!e.getMessage.contains("MRN-001"), s"the message must not carry the value, got: ${e.getMessage}")
        }
        assert(ProtectionPolicy.Reserved == Set("fpe", "tokenize"))
        assert(ProtectionPolicy.Methods == Set("hmac", "mask", "redact", "drop", "encrypt"))
        assert(ProtectionPolicy.Preserves == Set("last4", "domain", "year"))
    }

    // ==========================================================================
    // Delimited stage
    // ==========================================================================

    test("delimited rows are rewritten through a new staged file and quoted delimiters survive") {
        val cfg =
            csvConfig(jlist(field("id"), field("name"), field("mrn", policy("hmac")), field("email", policy("mask", "domain")), field("ssn", policy("redact"))))
        val ctx = delimitedCtx(cfg)
        val before = ctx.data.staged.path
        val out = FieldProtection.apply(ctx)

        assert(out.data.staged.path != before, "a new staged file")
        assert(out.data.staged.format == StagedFormat.Delimited(","))
        assert(out.data.rowCount == 3)
        assert(out.data.header == Header)
        val rows = rowsOf(out.data).map(r => CodeGenTransformationEvaluator.splitLine(r, ","))
        assert(rows.forall(_.size == 5), s"every row keeps five columns: $rows")
        assert(rows.map(_(1)) == List("Smith, Jane", "Bob", "Lee, Ann"), "an unprotected quoted value with the delimiter survives")
        assert(rows.map(_(0)) == List("1", "2", "3"))
        assert(rows(0)(2) == hmacHex(Key, "MRN-001"))
        assert(rows(1)(2) == hmacHex(Key, "MRN-002"))
        assert(rows(2)(2) == rows(0)(2), "equal inputs, equal tokens (joins still work)")
        assert(rows(0)(3) == "***@example.com" && rows(1)(3) == "***@example.org")
        assert(rows(2)(3) == "" && rows(2)(4) == "", "empty stays empty")
        assert(rows(0)(4) == "[REDACTED]")
        val text = rowsOf(out.data).mkString("\n")
        RawValues.filterNot(_ == "bob@").foreach(v => assert(!text.contains(v), s"raw value $v must not survive"))
    }

    test("drop removes the column from header, rows, headerWithSchema and both in-memory schemas") {
        val cfg = csvConfig(protectedSourceFields)
        val out = FieldProtection.apply(delimitedCtx(cfg))
        val kept = List("id", "name", "mrn", "email")
        assert(out.data.header == kept)
        assert(out.data.headerWithSchema.map(_.name) == kept)
        assert(out.config.source.schemaProperties.fields.asScala.map(_.name).toList == kept)
        assert(out.config.destination.schemaProperties.fields.asScala.map(_.name).toList == kept)
        val rows = rowsOf(out.data).map(r => CodeGenTransformationEvaluator.splitLine(r, ","))
        assert(rows.forall(_.size == 4), s"ssn removed from every row: $rows")
        assert(!rowsOf(out.data).mkString("\n").contains("6789"))
        // The stored config is untouched (copies, never mutates).
        assert(cfg.source.schemaProperties.fields.asScala.map(_.name).toList == Header)
        assert(cfg.destination.schemaProperties.fields.asScala.map(_.name).toList == Header)
    }

    test("ndjson top-level keys are rewritten and dropped") {
        val cfg = jsonConfig(jlist(field("_json"), field("mrn", policy("hmac")), field("email", policy("mask", "domain")), field("ssn", policy("drop"))))
        val lines = List(
            """{"id":1,"mrn":"MRN-001","email":"jane@example.com","ssn":"123-45-6789","nested":{"mrn":"keep-nested"}}""",
            "",
            """{"id":2,"mrn":"MRN-001","email":"","note":null}""",
            "42",
            """["MRN-002"]"""
        )
        val out = FieldProtection.apply(ndjsonCtx(cfg, lines))
        val got = {
            val it = out.data.recordIterator()
            try it.toList
            finally it.close()
        }
        assert(got.size == 5, s"every line kept, blank and non-object included: $got")
        val first = JsonParser.parseString(got(0)).getAsJsonObject
        assert(first.get("mrn").getAsString == hmacHex(Key, "MRN-001"))
        assert(first.get("email").getAsString == "***@example.com")
        assert(!first.has("ssn"), "drop removes the key")
        assert(first.get("id").getAsInt == 1)
        assert(first.getAsJsonObject("nested").get("mrn").getAsString == "keep-nested", "nested paths are out of scope")
        assert(got(1).trim.isEmpty, "blank line kept")
        val second = JsonParser.parseString(got(2)).getAsJsonObject
        assert(second.get("mrn").getAsString == first.get("mrn").getAsString)
        assert(second.get("email").getAsString == "", "empty stays empty")
        assert(second.has("note") && second.get("note").isJsonNull)
        assert(got(3) == "42", "non-object lines pass through")
        assert(got(4) == """["MRN-002"]""", "non-object lines pass through")
    }

    test("no protect policy returns the same context") {
        val cfg = csvConfig(plainFields(Header: _*))
        val ctx = delimitedCtx(cfg, uploadMetadata)
        val out = FieldProtection.apply(ctx)
        assert(out eq ctx)
        assert(out.data eq ctx.data)
        assert(Files.exists(Paths.get(ctx.data.staged.path)), "nothing purged without protect")
        assert(store.deleted.isEmpty, "no ingest object deleted for a pipeline without protect")
        assert(audits.isEmpty)
    }

    test("status line names fields and methods and contains no value") {
        val status = new RecordingStatusUtil
        FieldProtection.apply(delimitedCtx(csvConfig(protectedSourceFields), status = status))
        val lines = status.descriptions.filter(_.startsWith("Protected "))
        assert(lines == List("Protected 3 fields: mrn=hmac, email=mask:domain, ssn=drop"), status.descriptions.mkString("\n"))
        // StatusUtil.send only accepts begin / processing / end; any other state throws in production.
        val protectLine = status.messages.find(_._3.startsWith("Protected ")).get
        assert(Set("begin", "processing", "end").contains(protectLine._2), s"state '${protectLine._2}' is rejected by StatusUtil.send")
        val all = status.descriptions.mkString("\n")
        RawValues.foreach(v => assert(!all.contains(v), s"status lines must never carry a value ($v): $all"))
    }

    // ==========================================================================
    // Purge
    // ==========================================================================

    test("the raw staged file is deleted once the protected file exists") {
        val ctx = delimitedCtx(csvConfig(protectedSourceFields))
        val raw = Paths.get(ctx.data.staged.path)
        assert(Files.exists(raw))
        val out = FieldProtection.apply(ctx)
        assert(Files.exists(Paths.get(out.data.staged.path)), "protected file exists")
        assert(!Files.exists(raw), "raw staged file deleted right after protection")
    }

    test("ingest objects of the run are deleted after protection") {
        val status = new RecordingStatusUtil
        FieldProtection.apply(delimitedCtx(csvConfig(protectedSourceFields), uploadMetadata, status))
        assert(store.deleted.toList == List((Bucket, "uploads/patients.pub-1.a.pipeline.csv")))
        assert(
            status.descriptions.contains("Purged raw source: " + Bucket + "/uploads/patients.pub-1.a.pipeline.csv"),
            status.descriptions.mkString("\n")
        )
        val ok = audits.filter(a => a._1 == "pipeline" && a._2 == "purge-source")
        assert(ok.size == 1, s"one purge-source audit entry, got $audits")
        assert(ok.head._3 == "pipeline" && ok.head._4 == "patients" && ok.head._6 == "success")
        assert(ok.head._5 != null && ok.head._5.toString.contains("uploads/patients.pub-1.a.pipeline.csv"), "keys in the audit metadata")
    }

    test("bulk upload runs delete every listed object but not the metadata file") {
        store = new FakeStore(listing =
            List(
                "bulk/run-9/",
                "bulk/run-9/part-1.csv",
                "bulk/run-9/part-2.csv",
                "bulk/run-9/patients.metadata.json"
            )
        )
        FieldProtection.objectStoreOverride = store
        val bulk = PipelineMetadata("patients", null, "s3://" + Bucket + "/bulk/run-9/", "pub-1", bulkUpload = true)
        FieldProtection.apply(delimitedCtx(csvConfig(protectedSourceFields), bulk))
        assert(store.deleted.toSet == Set((Bucket, "bulk/run-9/part-1.csv"), (Bucket, "bulk/run-9/part-2.csv")))
        assert(!store.deleted.exists(_._2.endsWith(".metadata.json")), "the .metadata.json companion is left alone")
        assert(!store.deleted.exists(_._2.endsWith("/")), "folder markers are not objects to purge")
    }

    test("purgeSource false keeps the ingest objects") {
        val off = ProtectionConfig(purgeSource = java.lang.Boolean.FALSE)
        assert(!ProtectionConfig.purgeSourceOn(csvConfig(protectedSourceFields, off)))
        assert(ProtectionConfig.purgeSourceOn(csvConfig(protectedSourceFields, null)), "absent → on")
        assert(ProtectionConfig.purgeSourceOn(csvConfig(protectedSourceFields, ProtectionConfig(purgeSource = null))), "null → on")

        val ctx = delimitedCtx(csvConfig(protectedSourceFields, off), uploadMetadata)
        val raw = Paths.get(ctx.data.staged.path)
        FieldProtection.apply(ctx)
        assert(store.deleted.isEmpty, "ingest objects kept")
        assert(!Files.exists(raw), "the raw staged file is still removed")
    }

    test("tap or stream metadata without an ingest object purges only the staged file") {
        val noPath = PipelineMetadata("patients", "patients.csv", null, "tok", bulkUpload = false)
        val noName = PipelineMetadata("patients", null, "s3://" + Bucket + "/uploads/", "tok", bulkUpload = false)
        List(noPath, noName).foreach { md =>
            val status = new RecordingStatusUtil
            val ctx = delimitedCtx(csvConfig(protectedSourceFields), md, status)
            val raw = Paths.get(ctx.data.staged.path)
            FieldProtection.apply(ctx)
            assert(!Files.exists(raw), "the raw staged file is still removed")
            assert(store.deleted.isEmpty, s"no object delete for $md")
            assert(!status.messages.exists(_._1 == "warning"), status.messages.mkString("\n"))
            assert(!status.descriptions.exists(_.toLowerCase.contains("purge")), status.descriptions.mkString("\n"))
            assert(audits.isEmpty, s"no audit entry for $md: $audits")
        }
    }

    test("without a preprocessor the raw payload passed as rawStaged is purged once, with no warning") {
        val status = new RecordingStatusUtil
        val ctx = delimitedCtx(csvConfig(protectedSourceFields), status = status)
        val raw = Paths.get(ctx.data.staged.path)
        val out = FieldProtection.apply(ctx, rawStaged = ctx.data.staged)
        assert(Files.exists(Paths.get(out.data.staged.path)), "protected file exists")
        assert(!Files.exists(raw), "raw staged file deleted")
        assert(!status.messages.exists(_._1 == "warning"), status.messages.mkString("\n"))
    }

    test("the pre-preprocessor staged file is purged with the preprocessor output") {
        val raw = delimitedCtx(csvConfig(protectedSourceFields)) // the notifier's payload
        val preprocessed = delimitedCtx(csvConfig(protectedSourceFields)) // a preprocessor's output
        val rawPath = Paths.get(raw.data.staged.path)
        val prePath = Paths.get(preprocessed.data.staged.path)
        val out = FieldProtection.apply(preprocessed, rawStaged = raw.data.staged)
        assert(Files.exists(Paths.get(out.data.staged.path)), "protected file exists")
        assert(!Files.exists(prePath), "preprocessor output deleted")
        assert(!Files.exists(rawPath), "pre-preprocessor raw payload deleted")
    }

    test("archive drops purge the original archive object with the extracted files") {
        store = new FakeStore(listing = List("temp/u1/a.tmp", "temp/u1/b.tmp"))
        FieldProtection.objectStoreOverride = store
        val status = new RecordingStatusUtil
        val md = PipelineMetadata(
            "patients",
            null,
            "s3://" + Bucket + "/temp/u1/",
            "pub-1",
            bulkUpload = true,
            sourceObject = "s3://" + Bucket + "/drops/patients.pub-1.x.pipeline.zip"
        )
        FieldProtection.apply(delimitedCtx(csvConfig(protectedSourceFields), md, status))
        assert(
            store.deleted.toSet == Set((Bucket, "temp/u1/a.tmp"), (Bucket, "temp/u1/b.tmp"), (Bucket, "drops/patients.pub-1.x.pipeline.zip")),
            s"${store.deleted}"
        )
        assert(status.descriptions.exists(d => d.startsWith("Purged raw source: ") && d.contains("drops/patients.pub-1.x.pipeline.zip")))
    }

    test("the status line names only protected fields present in the data") {
        val status = new RecordingStatusUtil
        val fields = protectedSourceFields
        fields.add(field("phone", policy("mask", "last4")))
        FieldProtection.apply(delimitedCtx(csvConfig(fields), status = status))
        val lines = status.descriptions.filter(_.startsWith("Protected "))
        assert(lines == List("Protected 3 fields: mrn=hmac, email=mask:domain, ssn=drop"), status.descriptions.mkString("\n"))
    }

    test("a failed object delete is a warning line and an audit entry, and the run continues") {
        store = new FakeStore(failDeletes = true)
        FieldProtection.objectStoreOverride = store
        val status = new RecordingStatusUtil
        val out = FieldProtection.apply(delimitedCtx(csvConfig(protectedSourceFields), uploadMetadata, status))
        assert(out.data.header == List("id", "name", "mrn", "email"), "the protected context is still returned")
        val warns = status.messages.filter(_._1 == "warning").map(_._3)
        assert(
            warns.exists(w => w.startsWith("Could not purge raw source " + Bucket + "/uploads/patients.pub-1.a.pipeline.csv") && w.contains("Access Denied")),
            status.messages.mkString("\n")
        )
        assert(!status.messages.exists(_._1 == "error"), "a purge failure never errors the run")
        val warnAudit = audits.filter(a => a._1 == "pipeline" && a._2 == "purge-source" && a._6 == "warning")
        assert(warnAudit.size == 1, s"one warning audit entry, got $audits")
        assert(warnAudit.head._7 != null && warnAudit.head._7.contains("Access Denied"))
    }

    test("a failing stage purges nothing") {
        // `fpe` is refused at save; at run time protectValue is the second line of defence.
        val cfg = csvConfig(jlist(field("id"), field("name"), field("mrn", policy("fpe")), field("email"), field("ssn", policy("drop"))))
        val status = new RecordingStatusUtil
        val ctx = delimitedCtx(cfg, uploadMetadata, status)
        val raw = Paths.get(ctx.data.staged.path)
        val e = intercept[DatrisException](FieldProtection.apply(ctx))
        assert(e.getMessage.contains("Field protection failed on field 'mrn' (fpe)"), e.getMessage)
        RawValues.foreach(v => assert(!e.getMessage.contains(v), s"no value in the failure message: ${e.getMessage}"))
        assert(Files.exists(raw), "the raw staged file stays when the stage fails")
        assert(store.deleted.isEmpty, "no ingest object deleted when the stage fails")
        assert(!status.descriptions.exists(_.startsWith("Purged raw source")))
        assert(audits.isEmpty)
    }

    // ==========================================================================
    // Story 5: encrypt (plans/stories/field-protection-5-encrypt-reveal.md)
    // ==========================================================================

    /** id, name unprotected; email=encrypt; mrn=hmac; ssn=drop. */
    private def encryptSourceFields: java.util.List[SchemaField] = jlist(
        field("id"),
        field("name"),
        field("mrn", policy("hmac")),
        field("email", policy("encrypt")),
        field("ssn", policy("drop"))
    )

    private def reveal(pipeline: String, fieldName: String, token: String): String =
        FieldCipher.decrypt(v => if (v == 1) EncKey else null, pipeline, fieldName, token)

    test("encrypt rewrites the column with enc: tokens and the purge still runs") {
        val status = new RecordingStatusUtil
        val ctx = delimitedCtx(csvConfig(encryptSourceFields), uploadMetadata, status)
        val raw = Paths.get(ctx.data.staged.path)
        val out = FieldProtection.apply(ctx)

        assert(out.data.header == List("id", "name", "mrn", "email"))
        val rows = rowsOf(out.data).map(r => CodeGenTransformationEvaluator.splitLine(r, ","))
        assert(rows.forall(_.size == 4), s"$rows")
        val emails = rows.map(_(3))
        assert(emails(0).startsWith("enc:v1:") && emails(1).startsWith("enc:v1:"), s"$emails")
        assert(emails(2) == "", "empty stays empty")
        // Bound to the pipeline name and the source field name.
        assert(reveal("patients", "email", emails(0)) == "jane@example.com")
        assert(reveal("patients", "email", emails(1)) == "bob@example.org")
        intercept[DatrisException](reveal("patients", "mrn", emails(0)))
        intercept[DatrisException](reveal("other", "email", emails(0)))
        // Other methods on the same run are unchanged.
        assert(rows(0)(2) == hmacHex(Key, "MRN-001"))
        val text = rowsOf(out.data).mkString("\n")
        RawValues.foreach(v => assert(!text.contains(v), s"raw value $v must not survive"))

        // The purge from story 1 still runs.
        assert(!Files.exists(raw), "raw staged file deleted")
        assert(store.deleted.toList == List((Bucket, "uploads/patients.pub-1.a.pipeline.csv")))
        assert(audits.exists(a => a._2 == "purge-source" && a._6 == "success"), s"$audits")
    }

    test("encrypt rewrites a JSON top-level key with enc: tokens") {
        val cfg = jsonConfig(jlist(field("_json"), field("email", policy("encrypt"))))
        val lines = List("""{"id":1,"email":"jane@example.com"}""", """{"id":2,"email":""}""")
        val out = FieldProtection.apply(ndjsonCtx(cfg, lines))
        val got = {
            val it = out.data.recordIterator()
            try it.toList
            finally it.close()
        }
        val first = JsonParser.parseString(got(0)).getAsJsonObject.get("email").getAsString
        assert(first.startsWith("enc:v1:"), first)
        assert(reveal("patients_json", "email", first) == "jane@example.com")
        assert(JsonParser.parseString(got(1)).getAsJsonObject.get("email").getAsString == "")
    }

    test("status line shows field=encrypt and no value") {
        val status = new RecordingStatusUtil
        FieldProtection.apply(delimitedCtx(csvConfig(encryptSourceFields), status = status))
        val lines = status.descriptions.filter(_.startsWith("Protected "))
        assert(lines == List("Protected 3 fields: mrn=hmac, email=encrypt, ssn=drop"), status.descriptions.mkString("\n"))
        val all = status.descriptions.mkString("\n")
        RawValues.foreach(v => assert(!all.contains(v), s"status lines must never carry a value ($v): $all"))
        assert(!all.contains("enc:v"), s"status lines never carry a ciphertext either: $all")
    }
}
