package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.ObjectStore
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

class ObjectStoreSparkSpec extends AnyFunSuite with BeforeAndAfterEach {

    // The applied-fingerprint map is JVM-global; clear it so cases cannot leak into each other.
    override def beforeEach(): Unit = ObjectStoreSpark.resetAppliedFingerprints()
    override def afterEach(): Unit = ObjectStoreSpark.resetAppliedFingerprints()

    // All fixtures pin destinationBucketOverride so resolveBucket never falls
    // back to DatrisEnvironment.current (not initialized in unit tests).
    private def dest(prefix: String, bucket: String = "bucket-a"): ObjectStore =
        ObjectStore(prefixKey = prefix, destinationBucketOverride = bucket)

    test("normalizePrefix strips leading/trailing slashes and whitespace, null-safe") {
        assert(ObjectStoreSpark.normalizePrefix(null) == "")
        assert(ObjectStoreSpark.normalizePrefix("") == "")
        assert(ObjectStoreSpark.normalizePrefix(" /city-forecasts/ ") == "city-forecasts")
        assert(ObjectStoreSpark.normalizePrefix("a/b/c") == "a/b/c")
    }

    test("equal prefixes in the same bucket overlap") {
        assert(ObjectStoreSpark.destinationsOverlap(dest("city-forecasts"), dest("city-forecasts")))
        assert(ObjectStoreSpark.destinationsOverlap(dest("city-forecasts/"), dest("/city-forecasts")))
    }

    test("nested prefixes overlap in both directions") {
        assert(ObjectStoreSpark.destinationsOverlap(dest("city"), dest("city/2026")))
        assert(ObjectStoreSpark.destinationsOverlap(dest("city/2026"), dest("city")))
    }

    test("sibling prefixes sharing a string prefix do not overlap") {
        assert(!ObjectStoreSpark.destinationsOverlap(dest("city"), dest("city-forecasts")))
        assert(!ObjectStoreSpark.destinationsOverlap(dest("city-forecasts"), dest("city-forecasts-v2")))
    }

    test("same prefix in different buckets does not overlap") {
        assert(!ObjectStoreSpark.destinationsOverlap(dest("city-forecasts", "bucket-a"), dest("city-forecasts", "bucket-b")))
    }

    test("an empty prefix spans the bucket and overlaps everything in it") {
        assert(ObjectStoreSpark.destinationsOverlap(dest(""), dest("city-forecasts")))
        assert(ObjectStoreSpark.destinationsOverlap(dest("city-forecasts"), dest(null)))
        assert(!ObjectStoreSpark.destinationsOverlap(dest("", "bucket-a"), dest("city", "bucket-b")))
    }

    // ---- S3 credential fingerprint / cached-filesystem eviction decision (no S3, no SparkSession) ----

    private val fakeAccessKey = "AKIAFAKEACCESSKEY0001"
    private val fakeSecretKey = "fakeSecretKeyMaterial/ShouldNeverAppearInDigest"
    private val fakeSessionToken = "fakeSessionTokenValue"
    private val simpleProvider = "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
    private val temporaryProvider = "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider"

    private def fingerprint(
        accessKey: Option[String] = Some(fakeAccessKey),
        secretKey: Option[String] = Some(fakeSecretKey),
        sessionToken: Option[String] = None,
        endpoint: String = "https://s3.us-east-1.amazonaws.com",
        region: Option[String] = Some("us-east-1"),
        providerClass: String = simpleProvider
    ): String =
        ObjectStoreSpark.fingerprintOf(accessKey, secretKey, sessionToken, endpoint, region, providerClass)

    test("first apply for a bucket records a fingerprint and does not evict") {
        assert(!ObjectStoreSpark.credentialsChanged("bucket-a", fingerprint()))
    }

    test("re-applying identical S3 settings does not evict") {
        val first = fingerprint()
        val again = fingerprint()
        assert(first == again)
        assert(!ObjectStoreSpark.credentialsChanged("bucket-a", first))
        assert(!ObjectStoreSpark.credentialsChanged("bucket-a", again))
    }

    test("a changed access key, secret key, session token, endpoint, region, or credentials-provider class each evict") {
        val baseline = fingerprint()
        val changed = Seq(
            "access key" -> fingerprint(accessKey = Some("AKIAFAKEACCESSKEY0002")),
            "secret key" -> fingerprint(secretKey = Some("rotatedSecretKeyMaterial")),
            "session token" -> fingerprint(sessionToken = Some(fakeSessionToken)),
            "endpoint" -> fingerprint(endpoint = "https://s3.eu-west-1.amazonaws.com"),
            "region" -> fingerprint(region = Some("eu-west-1")),
            "provider class" -> fingerprint(providerClass = temporaryProvider)
        )
        changed.foreach { case (label, fp) =>
            ObjectStoreSpark.resetAppliedFingerprints()
            assert(!ObjectStoreSpark.credentialsChanged("bucket-a", baseline), s"$label: baseline should be first sighting")
            assert(fp != baseline, s"$label: fingerprint should differ from baseline")
            assert(ObjectStoreSpark.credentialsChanged("bucket-a", fp), s"$label: change should evict")
        }
    }

    test("fingerprints are per bucket") {
        val original = fingerprint()
        val rotated = fingerprint(accessKey = Some("AKIAFAKEACCESSKEY0002"), secretKey = Some("rotatedSecretKeyMaterial"))
        assert(!ObjectStoreSpark.credentialsChanged("bucket-a", original))
        assert(!ObjectStoreSpark.credentialsChanged("bucket-b", original))
        assert(ObjectStoreSpark.credentialsChanged("bucket-a", rotated))
        // bucket-b was not touched by bucket-a's change: same settings again is still "unchanged"
        assert(!ObjectStoreSpark.credentialsChanged("bucket-b", original))
    }

    test("fingerprint does not contain the secret material") {
        val fp = fingerprint(sessionToken = Some(fakeSessionToken), providerClass = temporaryProvider)
        assert(!fp.contains(fakeAccessKey))
        assert(!fp.contains(fakeSecretKey))
        assert(!fp.contains(fakeSessionToken))
        assert(fp.matches("[0-9a-fA-F]{64}"), s"expected hex SHA-256 digest, got: $fp")
    }

    test("resetAppliedFingerprints clears recorded fingerprints so a bucket is a first sighting again") {
        val original = fingerprint()
        val rotated = fingerprint(accessKey = Some("AKIAFAKEACCESSKEY0002"))
        assert(!ObjectStoreSpark.credentialsChanged("bucket-a", original))
        ObjectStoreSpark.resetAppliedFingerprints()
        assert(!ObjectStoreSpark.credentialsChanged("bucket-a", rotated))
    }
}
