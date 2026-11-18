package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{Destination, ObjectStore, PipelineConfig}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, FileSystem, Path}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.io.{IOException, InterruptedIOException}
import java.net.URI
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Story: Catalog-scoped user access: one restricted user cannot use up the
  * new-destination check for everyone else. The object-store probe opens a
  * private FileSystem from a copy of the Hadoop configuration that holds
  * `ProbeS3ASettings`; the shared configuration, the fingerprint map and the
  * cached FileSystem are never touched. `open` is injected through the
  * `objectStoreWith` seam; the FileSystem is a recording mock. */
class DestinationProbeObjectStoreIsolationSpec extends AnyFunSuite with BeforeAndAfterEach {

    override def beforeEach(): Unit = ObjectStoreSpark.resetAppliedFingerprints()
    override def afterEach(): Unit = ObjectStoreSpark.resetAppliedFingerprints()

    private val Bucket = "cap-dead"
    // A public TEST-NET-1 literal: SsrfGuard lets it through without DNS.
    private val Endpoint = "https://192.0.2.1:9000"

    private def candidate(endpoint: String = Endpoint): PipelineConfig =
        PipelineConfig(
            name = "cap_a",
            catalog = "Sales",
            destination = Destination(objectStore =
                ObjectStore(prefixKey = "p1", fileFormat = "parquet", provider = "s3", endpoint = endpoint, destinationBucketOverride = Bucket)
            )
        )

    /** A FileSystem mock whose globStatus runs `glob`, counting closes. */
    private final class Recording(glob: Path => Array[FileStatus]) {
        val opened = new AtomicInteger(0)
        val closed = new AtomicInteger(0)
        val closedLatch = new CountDownLatch(1)
        val uri = new AtomicReference[URI]()
        val conf = new AtomicReference[Configuration]()
        val fs: FileSystem = mock(classOf[FileSystem])
        when(fs.globStatus(any(classOf[Path]))).thenAnswer(inv => glob(inv.getArgument[Path](0)))
        when(fs.close()).thenAnswer { _ =>
            closed.incrementAndGet()
            closedLatch.countDown()
            null
        }
        val open: (URI, Configuration) => FileSystem = (u, c) => {
            opened.incrementAndGet()
            uri.set(u)
            conf.set(c)
            fs
        }
    }

    private def bucketKeys(conf: Configuration): Map[String, String] =
        conf.iterator().asScala.map(e => e.getKey -> e.getValue).filter(_._1.startsWith("fs.s3a.bucket." + Bucket + ".")).toMap

    test(
        "the probe opens its FileSystem from a copy that holds ProbeS3ASettings and the candidate's endpoint; the shared Configuration holds neither afterwards"
    ) {
        val shared = new Configuration(false)
        shared.set("fs.s3a.endpoint", "http://minio:9000")
        val rec = new Recording(_ => Array.empty[FileStatus])
        assert(!DestinationProbe.objectStoreWith(candidate(), shared, rec.open))
        assert(rec.opened.get == 1)
        val copy = rec.conf.get
        assert(copy != null && !(copy eq shared), "the probe opened its FileSystem from the shared Configuration")
        assert(rec.uri.get.getScheme == "s3a" && rec.uri.get.getHost == Bucket, rec.uri.get)
        DestinationProbe.ProbeS3ASettings(Bucket).foreach { case (k, v) => assert(copy.get(k) == v, k) }
        assert(copy.get("fs.s3a.bucket." + Bucket + ".endpoint") == Endpoint)
        // the global settings are still visible on the copy
        assert(copy.get("fs.s3a.endpoint") == "http://minio:9000")
        // the shared configuration is untouched
        assert(bucketKeys(shared).isEmpty, bucketKeys(shared))
        assert(shared.get("fs.s3a.endpoint") == "http://minio:9000")
        DestinationProbe.ProbeS3ASettings(Bucket).foreach { case (k, _) => assert(shared.get(k) == null, k) }
    }

    test("the probe records no fingerprint and evicts no cached FileSystem") {
        val shared = new Configuration(false)
        val rec = new Recording(_ => Array.empty[FileStatus])
        DestinationProbe.objectStoreWith(candidate(), shared, rec.open)
        DestinationProbe.objectStoreWith(candidate("https://192.0.2.2:9000"), shared, rec.open)
        // nothing was recorded for the bucket: any fingerprint a loader applies next is a first
        // sighting (had the probe recorded one, a different one here would report a change and evict)
        val fp = ObjectStoreSpark.fingerprintOf(Some("AKIALOADER"), Some("s"), None, "https://s3.amazonaws.com", None, "x")
        assert(!ObjectStoreSpark.credentialsChanged(Bucket, fp))
        assert(rec.opened.get == 2)
    }

    test("the probe's FileSystem is closed after an answer, after a failure and after an interrupt") {
        val shared = new Configuration(false)
        // an answer
        val answered = new Recording(_ => Array.empty[FileStatus])
        assert(!DestinationProbe.objectStoreWith(candidate(), shared, answered.open))
        assert(answered.closed.get == 1)
        // a failure (not a "missing" one): rethrown, still closed
        val failing = new Recording(_ => throw new IOException("storage down"))
        intercept[IOException](DestinationProbe.objectStoreWith(candidate(), shared, failing.open))
        assert(failing.closed.get == 1)
        // an interrupt at the deadline: closed on the worker
        val entered = new CountDownLatch(1)
        val blocking = new Recording(_ => {
            entered.countDown()
            try { Thread.sleep(60000); Array.empty[FileStatus] }
            catch { case _: InterruptedException => throw new InterruptedIOException("interrupted") }
        })
        intercept[DestinationProbe.DeadlineExceeded] {
            DestinationProbe.withDeadline(300.millis, "object-store probe")(DestinationProbe.objectStoreWith(candidate(), shared, blocking.open))
        }
        assert(entered.await(5, TimeUnit.SECONDS))
        assert(blocking.closedLatch.await(10, TimeUnit.SECONDS), "the probe's FileSystem was not closed after the interrupt")
        assert(blocking.closed.get == 1)
    }

    test("ProbeS3ASettings bounds connect at 5 s and read at 10 s with no retries, as per-bucket keys") {
        val s = DestinationProbe.ProbeS3ASettings(Bucket)
        val m = s.toMap
        val p = "fs.s3a.bucket." + Bucket + "."
        assert(m.get(p + "connection.establish.timeout").contains("5000"), m)
        assert(m.get(p + "connection.timeout").contains("10000"), m)
        assert(m.get(p + "attempts.maximum").contains("0"), m)
        assert(m.get(p + "retry.limit").contains("0"), m)
        assert(s.forall(_._1.startsWith(p)), s)
        // no global key, and per bucket
        assert(DestinationProbe.ProbeS3ASettings("other-b").forall(_._1.startsWith("fs.s3a.bucket.other-b.")))
    }

    test("a private or loopback endpoint is still refused before anything is opened") {
        for (ep <- Seq("http://127.0.0.1:9000", "http://10.1.2.3:9000", "http://169.254.169.254")) {
            val shared = new Configuration(false)
            val rec = new Recording(_ => Array.empty[FileStatus])
            intercept[Exception](DestinationProbe.objectStoreWith(candidate(ep), shared, rec.open))
            assert(rec.opened.get == 0, ep)
            assert(bucketKeys(shared).isEmpty, ep)
        }
    }
}
