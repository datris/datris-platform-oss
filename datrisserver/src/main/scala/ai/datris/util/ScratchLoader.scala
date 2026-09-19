package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model._
import com.google.gson.{Gson, GsonBuilder, JsonArray, JsonParser}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.slf4j.{Logger, LoggerFactory}

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}
import java.util.regex.Pattern
import scala.collection.JavaConverters._

/** Object keys for scratch results. Everything a scratch pipeline writes lives
  * under `_scratch/<pipeline>/` in the `<env>-data` bucket, so delete-with-data
  * is a recursive delete of that prefix — which is exactly why the prefix is
  * guarded here: a blank or path-traversing pipeline segment must never turn
  * into a delete outside `_scratch/`.
  */
object ScratchPaths {
    val Root = "_scratch/"

    /** "_scratch/<pipeline>/". Throws rather than returning anything that could
      * reach outside the scratch root. */
    def prefix(pipeline: String): String = {
        val segment = Option(pipeline).map(_.trim).getOrElse("")
        if (segment.isEmpty)
            throw new DatrisException("scratch prefix requires a non-empty pipeline name; refusing to compute a " + Root + " prefix")
        if (segment == "." || segment == ".." || segment.contains("/") || segment.contains("\\"))
            throw new DatrisException("pipeline name '" + pipeline + "' would place the computed prefix outside " + Root + "; refusing")
        val computed = Root + segment + "/"
        if (!computed.startsWith(Root) || computed.contains("/../") || computed.contains("//"))
            throw new IllegalStateException("computed scratch prefix '" + computed + "' is outside " + Root + "; refusing")
        computed
    }

    /** "_scratch/<pipeline>/<pipelineToken>.jsonl" */
    def key(pipeline: String, pipelineToken: String): String = {
        val token = Option(pipelineToken).map(_.trim).getOrElse("")
        if (token.isEmpty || token.contains("/") || token.contains("\\") || token == "." || token == "..")
            throw new DatrisException("scratch object key requires a plain pipeline token, got '" + pipelineToken + "'")
        prefix(pipeline) + token + ".jsonl"
    }
}

object ScratchLoader {
    private val logger: Logger = LoggerFactory.getLogger(classOf[ScratchLoader])

    /** Where `_scratch/...` keys are written plus the two DatrisEnvironment
      * knobs. Production resolves `s3a://<env>-data`; specs pass a `file://`
      * directory, which a plain Hadoop Configuration resolves without Spark. */
    case class Settings(rootUri: String, inlineRows: Int, retentionHours: Int, maxPreviewBytes: Int = DefaultMaxPreviewBytes)

    /** Byte ceiling on the inline preview so a wide row set cannot bloat the
      * status document (Mongo caps documents at 16 MB; keep well under). */
    val DefaultMaxPreviewBytes: Int = 1024 * 1024

    def settingsFromEnvironment(): Settings = {
        val env = DatrisEnvironment.current
        Settings("s3a://" + env.environment + "-data", env.scratchInlineRows, env.scratchRetentionHours)
    }

    /** Hadoop configuration able to resolve `rootUri` without a SparkSession.
      * s3a gets the same six global `fs.s3a.*` values SparkSessionManager
      * applies for the built-in MinIO (`<env>-data` is always the platform's
      * own bucket, so the per-bucket / provider=s3 settings ObjectStoreSpark
      * layers on for user destinations do not apply here). Anything else
      * (file://) is served by the default local FileSystem. Kept Spark-free so
      * the hourly ScratchSweeper and `/pipeline/result` never boot a
      * `local[*]` driver on installs that have no other reason to. */
    private[util] def hadoopConfiguration(rootUri: String): Configuration = {
        val conf = new Configuration()
        if (rootUri.startsWith("s3a://")) {
            val minIOConfig = DatrisEnvironment.current.minIOConfig
            if (minIOConfig != null) {
                conf.set("fs.s3a.endpoint", minIOConfig.endpoint)
                conf.set("fs.s3a.access.key", minIOConfig.accessKey)
                conf.set("fs.s3a.secret.key", minIOConfig.secretKey)
                conf.set("fs.s3a.path.style.access", "true")
                conf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
                conf.set("fs.s3a.connection.ssl.enabled", "false")
            }
        }
        conf
    }

    /** Delete-with-data for a scratch pipeline: recursively remove
      * `s3a://<env>-data/_scratch/<pipeline>/` and nothing else. The prefix is
      * built by ScratchPaths.prefix (which already refuses blank / traversing
      * segments) and re-asserted here in the same spirit as the empty-prefixKey
      * guard in ObjectStoreSpark.deleteDestinationData. */
    def deleteScratchData(pipeline: String): Unit = {
        val prefix = ScratchPaths.prefix(pipeline)
        if (!prefix.startsWith(ScratchPaths.Root) || prefix.length <= ScratchPaths.Root.length + 1)
            throw new IllegalStateException("refusing to delete scratch data: computed prefix '" + prefix + "' is not under " + ScratchPaths.Root)
        val rootUri = "s3a://" + DatrisEnvironment.current.environment + "-data"
        val path = new Path(rootUri + "/" + prefix.stripSuffix("/"))
        val fs = path.getFileSystem(hadoopConfiguration(rootUri))
        if (fs.exists(path)) {
            fs.delete(path, true)
            logger.info("Deleted scratch data at: " + path)
        } else
            logger.info("No scratch data to delete at: " + path)
    }
}

/** Scratch destination: the post-transformation `Data` lands as one JSON-lines
  * object at `_scratch/<pipeline>/<pipelineToken>.jsonl` in `<env>-data`, and a
  * ScratchResult (pointer + first page of rows) goes on the status stream so
  * the caller reads the answer off the run status it already polls. Streams
  * one line per record straight into the object — no Spark DataFrame, no temp
  * copy.
  */
class ScratchLoader(jobContext: JobContext, settings: ScratchLoader.Settings) {
    def this(jobContext: JobContext) = this(jobContext, ScratchLoader.settingsFromEnvironment())

    private val config = jobContext.config
    private val statusUtil = jobContext.statusUtil
    // serializeNulls: a JSON record with an explicit null member must land
    // unchanged; Gson's default drops null members even from a JsonElement.
    private val gson: Gson = new GsonBuilder().serializeNulls().create()

    def process(): Unit = {
        statusUtil.overrideProcessName(this.getClass.getSimpleName)

        val key = ScratchPaths.key(config.name, jobContext.pipelineToken)
        val uri = settings.rootUri.stripSuffix("/") + "/" + key
        statusUtil.info("begin", "Writing scratch result to: " + uri)

        val path = new Path(uri)
        val fs = path.getFileSystem(ScratchLoader.hadoopConfiguration(settings.rootUri))
        fs.setWriteChecksum(false)

        // The preview is always a strict prefix of the file: it closes at the
        // first record that would exceed the byte cap and never resumes, so a
        // caller can page on from preview.size() with no hidden gap.
        val preview = new JsonArray()
        var previewBytes = 0L
        var previewOpen = true
        var count = 0L
        val writer = new BufferedWriter(new OutputStreamWriter(fs.create(path, true), StandardCharsets.UTF_8))
        val records = this.records()
        try {
            records.foreach { record =>
                writer.write(record)
                writer.write('\n')
                count += 1
                if (previewOpen && preview.size() < settings.inlineRows) {
                    val bytes = record.getBytes(StandardCharsets.UTF_8).length
                    if (previewBytes + bytes <= settings.maxPreviewBytes) {
                        preview.add(JsonParser.parseString(record))
                        previewBytes += bytes
                    } else
                        previewOpen = false
                }
            }
        } finally {
            try records.close()
            finally writer.close()
        }

        val expiresAt = Instant.now().plus(Duration.ofHours(settings.retentionHours.toLong)).toString
        statusUtil.info("processing", "Wrote " + count + " record(s) to scratch result: " + uri)
        statusUtil.scratchResult(
            ScratchResult(
                resultUri = uri,
                resultRowCount = count,
                resultExpiresAt = expiresAt,
                resultPreview = preview,
                resultTruncated = count > preview.size()
            )
        )
        statusUtil.info("end", "Process completed")
    }

    /** One compact JSON object per record, in source order. Delimited rows
      * and NDJSON records stream from the staged file; XML (and any text that
      * did not stage as JSON) still goes through the materialize-gated
      * `rawData` accessor. Caller closes. */
    private def records(): CloseableIterator[String] = {
        val data = jobContext.data
        val staged = data.staged
        val fileAttributes = if (config.source != null) config.source.fileAttributes else null
        if (staged == null || staged.isEmpty)
            throw new DatrisException("No data available to write to the scratch destination")
        staged.format match {
            case StagedFormat.Delimited(_) if data.header != null => csvRecords(data)
            case StagedFormat.NdJson if fileAttributes == null || fileAttributes.xmlAttributes == null => jsonRecords(data)
            case StagedFormat.NdJson | StagedFormat.Xml | StagedFormat.Text =>
                val rawData = data.rawData
                if (rawData == null || rawData.trim.isEmpty)
                    throw new DatrisException("No data available to write to the scratch destination")
                val it =
                    if (fileAttributes != null && fileAttributes.xmlAttributes != null) xmlRecords(rawData)
                    else rawJsonRecords(rawData, fileAttributes)
                CloseableIterator(it, () => ())
            case _ =>
                throw new DatrisException("No data available to write to the scratch destination")
        }
    }

    /** CSV: one object per row keyed by header, splitting on the configured
      * delimiter (literal, so "|" works) and padding short rows with "". */
    private def csvRecords(data: Data): CloseableIterator[String] = {
        val header = data.header
        val delimiter = {
            if (
                config.source != null
                && config.source.fileAttributes != null
                && config.source.fileAttributes.csvAttributes != null
                && config.source.fileAttributes.csvAttributes.delimiter != null
            )
                config.source.fileAttributes.csvAttributes.delimiter
            else
                ","
        }
        val splitter = Pattern.quote(delimiter)
        val rows = data.rowIterator()
        CloseableIterator(
            rows.map { row =>
                val fields = row.split(splitter, -1)
                val record = new java.util.LinkedHashMap[String, String]()
                header.indices.foreach { i =>
                    record.put(header(i), if (i < fields.length) fields(i) else "")
                }
                gson.toJson(record)
            },
            () => rows.close()
        )
    }

    /** JSON: one staged NDJSON record per element (the Phase 1 stager already
      * split an array into elements and NDJSON into lines), re-serialized
      * compactly with nulls kept. */
    private def jsonRecords(data: Data): CloseableIterator[String] = {
        val records = data.recordIterator()
        CloseableIterator(
            records.map(_.trim).filter(_.nonEmpty).map(line => gson.toJson(JsonParser.parseString(line))),
            () => records.close()
        )
    }

    /** JSON from an in-memory string (text that did not stage as NDJSON): the
      * same blob-splitting as the pre-streaming MongoDBLoader — newline-
      * delimited when everyRowContainsObject, otherwise an array's elements or
      * the single object itself. */
    private def rawJsonRecords(rawData: String, fileAttributes: FileAttributes): Iterator[String] = {
        val everyRowContainsObject =
            if (fileAttributes != null && fileAttributes.jsonAttributes != null) fileAttributes.jsonAttributes.everyRowContainsObject
            else true
        if (everyRowContainsObject)
            rawData.split("\n").iterator.map(_.trim).filter(_.nonEmpty).map(line => gson.toJson(JsonParser.parseString(line)))
        else {
            val parsed = JsonParser.parseString(rawData.trim)
            if (parsed.isJsonArray) parsed.getAsJsonArray.asScala.iterator.map(element => gson.toJson(element))
            else Iterator(gson.toJson(parsed))
        }
    }

    /** XML: one `{"_xml": ...}` record — the shape the validator enforces and
      * PostgresLoader stores. */
    private def xmlRecords(rawData: String): Iterator[String] = {
        val record = new java.util.LinkedHashMap[String, String]()
        record.put("_xml", rawData)
        Iterator(gson.toJson(record))
    }
}
