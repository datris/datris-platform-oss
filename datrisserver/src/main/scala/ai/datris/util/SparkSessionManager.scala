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
            val minIOConfig = DatrisEnvironment.current.minIOConfig
            val builder = SparkSession.builder()
                .master("local[*]")
                .appName("pipeline-oss")
                .config("spark.ui.enabled", "false")

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
