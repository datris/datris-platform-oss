package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import com.google.gson.Gson
import ai.datris.model.{Notification, DatrisEnvironment, SchemaField}
import ai.datris.model.JobContext
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Row, SaveMode}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

class SparkObjectStoreLoader(jobContext: JobContext) {
    private val logger: Logger = LoggerFactory.getLogger(classOf[SparkObjectStoreLoader])
    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)
        statusUtil.info("begin", "Writing data to object store")

        val spark = SparkSessionManager.getOrCreate()

        val objectStore = config.destination.objectStore
        val bucket = ObjectStoreSpark.resolveBucket(objectStore)
        val prefixKey = objectStore.prefixKey
        val outputPath = "s3a://" + bucket + "/" + prefixKey

        ObjectStoreSpark.applyPerBucketConfig(spark, bucket, objectStore)

        // Delete existing data if requested. Route through the Hadoop FileSystem
        // (S3A) rather than the MinIO Java SDK, so it honors the per-bucket config
        // we just applied and works for both MinIO and AWS S3. Using the MinIO SDK
        // here would always hit the global MinIO endpoint — wrong for S3 buckets.
        if (objectStore.deleteBeforeWrite) {
            statusUtil.info("processing", "Deleting existing data at: " + outputPath)
            try {
                val path = new org.apache.hadoop.fs.Path(outputPath)
                val fs = path.getFileSystem(spark.sparkContext.hadoopConfiguration)
                if (fs.exists(path)) fs.delete(path, true)
            } catch {
                case e: Exception => logger.info("No existing data to delete at: " + outputPath + " (" + e.getMessage + ")")
            }
        }

        // Build schema from pipeline config
        val schemaFields = config.destination.schemaProperties.fields.asScala.toList
        val sparkSchema = buildSchema(schemaFields)

        // Convert data rows to Spark Rows
        val delimiter = {
            if (config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null
                && config.source.fileAttributes.csvAttributes.delimiter != null)
                config.source.fileAttributes.csvAttributes.delimiter
            else
                ","
        }

        val rows = jobContext.data.rows.map(row => {
            val values = row.split(delimiter, -1)
            Row.fromSeq(values.indices.map(i => {
                val value = if (i < values.length) values(i).trim else ""
                if (value.isEmpty) null
                else castValue(value, schemaFields(i).`type`)
            }))
        })

        val rdd = spark.sparkContext.parallelize(rows)
        val df = spark.createDataFrame(rdd, sparkSchema)

        // Determine file format (default parquet)
        val fileFormat = {
            if (config.destination.objectStore.fileFormat != null)
                config.destination.objectStore.fileFormat
            else
                "parquet"
        }

        // Determine write mode (default append)
        val writeMode = {
            if (config.destination.objectStore.writeMode != null)
                config.destination.objectStore.writeMode.toLowerCase match {
                    case "overwrite" => SaveMode.Overwrite
                    case "ignore" => SaveMode.Ignore
                    case "errorifexists" => SaveMode.ErrorIfExists
                    case _ => SaveMode.Append
                }
            else
                SaveMode.Append
        }

        // Write with optional partitioning
        val writer = df.write.mode(writeMode)
        val partitionedWriter = {
            if (config.destination.objectStore.partitionBy != null) {
                val partitions = config.destination.objectStore.partitionBy.asScala.toList
                statusUtil.info("processing", "Partitioning by: " + partitions.mkString(", "))
                writer.partitionBy(partitions: _*)
            } else {
                writer
            }
        }

        statusUtil.info("processing", "Writing " + fileFormat + " to: " + outputPath)
        partitionedWriter.format(fileFormat).save(outputPath)

        sendNotification(outputPath)
        statusUtil.info("end", "Process completed, wrote " + jobContext.data.rows.size + " rows to " + outputPath)
    }

    private def buildSchema(fields: List[SchemaField]): StructType = {
        StructType(fields.map(field => {
            val dataType = field.`type`.toLowerCase match {
                case "boolean" => BooleanType
                case "int" | "integer" => IntegerType
                case "tinyint" => ByteType
                case "smallint" => ShortType
                case "bigint" => LongType
                case "float" => FloatType
                case "double" => DoubleType
                case t if t.startsWith("decimal(") => {
                    val params = t.stripPrefix("decimal(").stripSuffix(")").split(",").map(_.trim.toInt)
                    DecimalType(params(0), if (params.length > 1) params(1) else 0)
                }
                case "date" => DateType
                case "timestamp" => TimestampType
                case _ => StringType
            }
            StructField(field.name, dataType, nullable = true)
        }))
    }

    private def castValue(value: String, fieldType: String): Any = {
        fieldType.toLowerCase match {
            case "boolean" => value.toBoolean
            case "int" | "integer" => value.toInt
            case "tinyint" => value.toByte
            case "smallint" => value.toShort
            case "bigint" => value.toLong
            case "float" => value.toFloat
            case "double" => value.toDouble
            case t if t.startsWith("decimal(") => new java.math.BigDecimal(value)
            case "date" => java.sql.Date.valueOf(value)
            case "timestamp" => java.sql.Timestamp.valueOf(value)
            case _ => value
        }
    }

    private def sendNotification(outputPath: String): Unit = {
        val notification = Notification(
            config.name,
            jobContext.metadata.publisherToken,
            jobContext.pipelineToken,
            "objectStore",
            config.destination.objectStore.prefixKey,
            outputPath,
            null,
            null,
            null,
            null,
            null
        )
        val gson = new Gson
        val jsonNotification = gson.toJson(notification)

        val attributes = new java.util.HashMap[String, String]
        attributes.put("pipeline", config.name)
        attributes.put("destination", "objectStore")
        attributes.put("prefixKey", config.destination.objectStore.prefixKey)

        NotificationUtil.add(DatrisEnvironment.current.pipelineTopic, jsonNotification, attributes.asScala.toMap)
        statusUtil.info("processing", "notification sent: " + jsonNotification)
    }
}
