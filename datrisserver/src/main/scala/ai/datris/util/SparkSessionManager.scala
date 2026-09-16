package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisEnvironment
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}

object SparkSessionManager {
    private val logger: Logger = LoggerFactory.getLogger(getClass)
    private var session: SparkSession = _

    def getOrCreate(): SparkSession = synchronized {
        if (session == null || session.sparkContext.isStopped) {
            logger.info("Creating new SparkSession")
            val env = DatrisEnvironment.current
            val minIOConfig = env.minIOConfig
            val builder = SparkSession.builder()
                .master("local[*]")
                .appName("pipeline-oss")
                .config("spark.ui.enabled", "false")
                // Apache Iceberg: the extension enables row-level SQL (MERGE INTO,
                // UPDATE, DELETE) on Iceberg tables; the `datris` hadoop catalog
                // gives SQL a catalog to address Iceberg tables through. Both are
                // inert for parquet and orc paths. Phase 1 tables are path-based
                // (IcebergWriter), so the warehouse is a placeholder the catalog
                // requires at init but never resolves against.
                .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .config("spark.sql.catalog.datris", "org.apache.iceberg.spark.SparkCatalog")
                .config("spark.sql.catalog.datris.type", "hadoop")
                .config("spark.sql.catalog.datris.warehouse", "s3a://" + env.environment + "-data/")
                // `default_iceberg` is the catalog Iceberg's DataFrame source
                // (format("iceberg") by path) resolves through. Left undefined,
                // Iceberg registers it as type=hive on first use and fails
                // eagerly without Hive metastore classes on the classpath.
                // `datris_cache` lets SQL (MERGE INTO) address a path-based
                // table IcebergWriter has loaded. See IcebergWriter.ensureCatalogs.
                .config("spark.sql.catalog." + IcebergWriter.DefaultCatalog, "org.apache.iceberg.spark.SparkCatalog")
                .config("spark.sql.catalog." + IcebergWriter.DefaultCatalog + ".type", "hadoop")
                .config("spark.sql.catalog." + IcebergWriter.DefaultCatalog + ".warehouse", "s3a://" + env.environment + "-data/")
                .config("spark.sql.catalog." + IcebergWriter.DefaultCatalog + ".cache-enabled", "false")
                .config("spark.sql.catalog." + IcebergWriter.CacheCatalog, "org.apache.iceberg.spark.SparkCachedTableCatalog")

            if (minIOConfig != null) {
                builder
                    .config("spark.hadoop.fs.s3a.endpoint", minIOConfig.endpoint)
                    .config("spark.hadoop.fs.s3a.access.key", minIOConfig.accessKey)
                    .config("spark.hadoop.fs.s3a.secret.key", minIOConfig.secretKey)
                    .config("spark.hadoop.fs.s3a.path.style.access", "true")
                    .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
                    .config("spark.hadoop.fs.s3a.connection.ssl.enabled", "false")
            }

            session = builder.getOrCreate()
        }
        session
    }
}
