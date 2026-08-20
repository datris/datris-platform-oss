package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisEnvironment, ObjectStore}
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}

/** Shared object-store + Spark/S3A plumbing used by both the writer
  *  (SparkObjectStoreLoader) and the reader (ObjectStoreQueryUtil). Keeping
  *  this in one place ensures read and write paths apply the same per-bucket
  *  config — drift between the two is the kind of bug that produces "writes
  *  fine, reads hang forever". */
object ObjectStoreSpark {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

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
        }
    }
}
