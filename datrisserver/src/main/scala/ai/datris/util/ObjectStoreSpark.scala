package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, ObjectStore}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Shared object-store + Spark/S3A plumbing used by both the writer
  *  (SparkObjectStoreLoader) and the reader (ObjectStoreQueryUtil). Keeping
  *  this in one place ensures read and write paths apply the same per-bucket
  *  config — drift between the two is the kind of bug that produces "writes
  *  fine, reads hang forever". */
object ObjectStoreSpark {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** bucket -> SHA-256 hex digest of the S3 settings last applied for it in
      *  this JVM. Holds digests only — never the raw key material. Hadoop's
      *  FileSystem cache keys on scheme+authority and keeps the credentials
      *  provider built during the first initialize of s3a://<bucket>, so
      *  re-setting the per-bucket keys on hadoopConfiguration is not enough
      *  once a secret is corrected or rotated: the cached filesystem must be
      *  evicted so the next access re-initialises with the new settings. */
    private val appliedFingerprints = new ConcurrentHashMap[String, String]()

    /** Guards the change-detection + eviction pair so two threads that both
      *  observe a change do not double-close the same cached filesystem. */
    private val evictionLock = new Object

    /** Resolve the effective bucket for an objectStore destination — explicit
      *  override if set, otherwise the environment default. */
    def resolveBucket(objectStore: ObjectStore): String = {
        if (objectStore.destinationBucketOverride != null)
            objectStore.destinationBucketOverride
        else
            DatrisEnvironment.current.environment + "-data"
    }

    /** Normalize a prefix key for path comparison and deletion: null-safe,
      *  trimmed, leading/trailing slashes stripped. */
    def normalizePrefix(prefixKey: String): String =
        Option(prefixKey).getOrElse("").trim.stripPrefix("/").stripSuffix("/")

    /** True when recursively deleting one destination's prefix would also hit
      *  the other's data — same effective bucket, and one normalized prefix
      *  equals or contains the other on a path-segment boundary. "city" vs
      *  "city-forecasts" do NOT overlap; "city" vs "city/2026" do. An empty
      *  prefix spans the whole bucket, so it overlaps everything in it. */
    def destinationsOverlap(a: ObjectStore, b: ObjectStore): Boolean = {
        if (resolveBucket(a) != resolveBucket(b)) return false
        val pa = normalizePrefix(a.prefixKey)
        val pb = normalizePrefix(b.prefixKey)
        if (pa.isEmpty || pb.isEmpty) return true
        pa == pb || pa.startsWith(pb + "/") || pb.startsWith(pa + "/")
    }

    /** Recursively delete an objectStore destination's data at
      *  s3a://<bucket>/<prefixKey>. Routed through the Hadoop FileSystem with
      *  the per-bucket config applied — same rationale as deleteBeforeWrite in
      *  SparkObjectStoreLoader: the global MinIO SDK client only reaches the
      *  built-in MinIO, which is wrong for provider=s3 or
      *  destinationBucketOverride buckets. */
    def deleteDestinationData(objectStore: ObjectStore): Unit = {
        val bucket = resolveBucket(objectStore)
        val prefix = normalizePrefix(objectStore.prefixKey)
        if (prefix.isEmpty)
            throw new IllegalStateException(
                "objectStore prefixKey is empty — refusing to delete the entire bucket s3a://" + bucket + "/"
            )
        val spark = SparkSessionManager.getOrCreate()
        applyPerBucketConfig(spark, bucket, objectStore)
        val path = new org.apache.hadoop.fs.Path("s3a://" + bucket + "/" + prefix)
        val fs = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
        if (fs.exists(path)) {
            fs.delete(path, true)
            logger.info("Deleted object store destination data at: s3a://" + bucket + "/" + prefix)
        } else {
            logger.info("No object store destination data to delete at: s3a://" + bucket + "/" + prefix)
        }
    }

    /** Apply per-bucket S3A settings on top of the global SparkSession config.
      *  Per-bucket keys (`fs.s3a.bucket.<bucket>.*`) override globals only for
      *  that bucket, so MinIO writes elsewhere keep using the global config set
      *  in SparkSessionManager.
      *
      *  Critical for provider=s3: ALWAYS set the per-bucket endpoint, even when
      *  the user didn't specify one. Otherwise the global fs.s3a.endpoint =
      *  http://minio:9000 (set by SparkSessionManager for the built-in MinIO)
      *  leaks into the S3 path and S3A tries to talk to MinIO with an AWS
      *  bucket name — hangs on connect / SSL until the request times out. */
    def applyPerBucketConfig(spark: SparkSession, bucket: String, objectStore: ObjectStore): Unit = {
        val hadoopConf = spark.sparkContext.hadoopConfiguration
        val creds = CredentialResolver.resolve(objectStore)

        creds.accessKey.foreach(k => hadoopConf.set(s"fs.s3a.bucket.$bucket.access.key", k))
        creds.secretKey.foreach(k => hadoopConf.set(s"fs.s3a.bucket.$bucket.secret.key", k))
        creds.sessionToken.foreach(t => hadoopConf.set(s"fs.s3a.bucket.$bucket.session.token", t))

        val provider = Option(objectStore.provider).getOrElse("minio").toLowerCase
        if (provider == "s3") {
            hadoopConf.set(s"fs.s3a.bucket.$bucket.path.style.access", "false")
            hadoopConf.set(s"fs.s3a.bucket.$bucket.connection.ssl.enabled", "true")

            val effectiveEndpoint = Option(objectStore.endpoint).filter(_.nonEmpty).getOrElse {
                creds.region.map(r => s"https://s3.$r.amazonaws.com").getOrElse("https://s3.amazonaws.com")
            }
            // Refuse loopback / private / link-local endpoints here as well as
            // at validation, so a config saved before the guard existed is
            // still stopped at run, query and delete time. The region-derived
            // AWS default above is public and passes.
            SsrfGuard.assertAllowed(effectiveEndpoint)
            hadoopConf.set(s"fs.s3a.bucket.$bucket.endpoint", effectiveEndpoint)

            creds.region.foreach { r =>
                hadoopConf.set(s"fs.s3a.bucket.$bucket.endpoint.region", r)
            }

            // Pin the credentials provider to Simple/Temporary so S3A does NOT
            // fall through to the IAM-Instance provider, which tries to hit
            // IMDS (169.254.169.254) with a long timeout when the host isn't
            // on EC2 — another quiet hang vector for dev/self-hosted deploys.
            val providerClass = if (creds.sessionToken.isDefined)
                "org.apache.hadoop.fs.s3a.TemporaryAWSCredentialsProvider"
            else
                "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider"
            hadoopConf.set(s"fs.s3a.bucket.$bucket.aws.credentials.provider", providerClass)

            logger.info(
                s"S3A per-bucket config for s3a://$bucket/: endpoint=$effectiveEndpoint, region=${creds.region.getOrElse("<unset>")}, provider=$providerClass"
            )

            // Spark runs local[*] (SparkSessionManager), so the driver's
            // FileSystem.CACHE is the only cache involved. The per-output-path
            // write lock in SparkObjectStoreLoader.withPipelineWriteLock does
            // not cover query threads, so eviction is guarded by its own lock
            // and is best-effort rather than assumed exclusive: a read already
            // in flight against this bucket may see "Filesystem closed" and
            // must be re-run — today's alternative is a container restart.
            val fingerprint = fingerprintOf(
                creds.accessKey,
                creds.secretKey,
                creds.sessionToken,
                effectiveEndpoint,
                creds.region,
                providerClass
            )
            evictionLock.synchronized {
                if (credentialsChanged(bucket, fingerprint)) evictCachedFileSystem(bucket, hadoopConf)
            }
        }
        // provider=minio: uses the global fs.s3a.* config; the global s3a://
        // filesystem is shared by other pipelines and must never be closed here.
    }

    /** Hex SHA-256 over the effective S3 settings for a bucket. Only the digest
      *  is ever stored or compared; raw key material never leaves this method. */
    private[util] def fingerprintOf(
        accessKey: Option[String],
        secretKey: Option[String],
        sessionToken: Option[String],
        endpoint: String,
        region: Option[String],
        providerClass: String
    ): String = {
        val joined = Seq(
            accessKey.getOrElse(""),
            secretKey.getOrElse(""),
            sessionToken.getOrElse(""),
            endpoint,
            region.getOrElse(""),
            providerClass
        ).mkString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8))
        digest.map("%02x".format(_)).mkString
    }

    /** Record the fingerprint for a bucket. Returns true only when a previous,
      *  different fingerprint was recorded; false on first sighting (the cached
      *  filesystem, if any, was built from this same config) and on repeats. */
    private[util] def credentialsChanged(bucket: String, fingerprint: String): Boolean = {
        val previous = appliedFingerprints.put(bucket, fingerprint)
        previous != null && previous != fingerprint
    }

    /** Test seam: forget every recorded fingerprint. */
    private[util] def resetAppliedFingerprints(): Unit = appliedFingerprints.clear()

    /** Close (and thereby remove from Hadoop's FileSystem cache) the cached
      *  s3a://<bucket> filesystem. Best-effort: a failed eviction must never
      *  fail the run, so failures are logged at WARN and swallowed. */
    private def evictCachedFileSystem(bucket: String, hadoopConf: Configuration): Unit = {
        logger.info(
            s"S3A settings changed for s3a://$bucket/ — evicting cached filesystem so the next access re-initialises"
        )
        try {
            FileSystem.get(new URI("s3a://" + bucket), hadoopConf).close()
        } catch {
            case e: Exception =>
                logger.warn(s"Could not evict cached filesystem for s3a://$bucket/: ${e.getMessage}")
        }
    }
}
