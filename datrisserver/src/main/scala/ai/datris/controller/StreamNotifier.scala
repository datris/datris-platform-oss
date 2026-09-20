package ai.datris.controller

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.common.base.Throwables
import com.google.gson.Gson
import ai.datris.model._
import ai.datris.util._
import ai.datris.model.{Data, INITIALIZED, JobContext}
import ai.datris.util.CSVReader
import org.slf4j.{Logger, LoggerFactory}

import java.io.{BufferedInputStream, ByteArrayInputStream, ByteArrayOutputStream, InputStream, SequenceInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.util.UUID
import scala.collection.JavaConverters._

class StreamNotifier {
    private val logger: Logger = LoggerFactory.getLogger(classOf[FileNotifier])
    private val statusUtil = new StatusUtil().init(DatrisEnvironment.current.pipelineStatusTableName, this.getClass.getSimpleName)

    def process(byteArray: Array[Byte], filename: String, pipeline: String, publisherToken: String, tapFeed: TapFeedInfo = null): JobContext =
        process(new ByteArrayInputStream(byteArray), byteArray.length.toLong, filename, pipeline, publisherToken, tapFeed)

    /** Stage `source` for one pipeline run. `sizeHint` is the payload size in
      * bytes when the caller knows it (upload Content-Length, tap output size);
      * it is recorded as `Data.size` and in the status stream. The stream is
      * consumed and closed here. */
    def process(source: InputStream, sizeHint: Long, filename: String, pipeline: String, publisherToken: String, tapFeed: TapFeedInfo): JobContext =
        processWith(sizeHint, filename, pipeline, publisherToken, tapFeed, config => stageData(source, sizeHint, config))

    /** Hand an already-staged, already-counted payload (a tap run's NDJSON /
      * XML / text file, plans/stories/streaming-pipeline-phase4.md) to one
      * pipeline run. The file is ADOPTED into the run's token directory — no
      * re-parse, no re-count, `arraySource` and `rowCount` preserved — so the
      * JobRunner cleanup reclaims it with the rest of the run. CSV and
      * unstructured pipelines still go through the `InputStream` overload's
      * parsing (schema evolution / rawBytes), fed from the staged file. */
    def process(staged: StagedPayload, sizeHint: Long, filename: String, pipeline: String, publisherToken: String, tapFeed: TapFeedInfo): JobContext =
        processWith(sizeHint, filename, pipeline, publisherToken, tapFeed, config => stageData(staged, sizeHint, config))

    private def processWith(
        sizeHint: Long,
        filename: String,
        pipeline: String,
        publisherToken: String,
        tapFeed: TapFeedInfo,
        stage: PipelineConfig => (Data, PipelineConfig)
    ): JobContext = {
        logger.info("StreamNotifier processing pipeline: " + pipeline + ", filename: " + filename)
        statusUtil.setFilename("stream: " + pipeline)
        val pipelineToken = UUID.randomUUID().toString

        try {
            statusUtil.setPipelineToken(pipelineToken)
            statusUtil.setPublisherToken(Option(publisherToken).getOrElse(pipelineToken))

            val config = PipelineConfigIO.read(DatrisEnvironment.current.pipelineTableName, pipeline)
            if (config == null)
                throw new DatrisException("Pipeline: " + pipeline + " is not configured in the NoSQL database")

            val metadata = PipelineMetadata(
                pipeline,
                filename,
                null,
                Option(publisherToken).getOrElse(pipelineToken),
                bulkUpload = false,
                tapName = if (tapFeed != null) tapFeed.tapName else null,
                tapRunTime = if (tapFeed != null) tapFeed.runTime else null,
                tapScriptSha = if (tapFeed != null) tapFeed.scriptCommitSha else null,
                tapSource = if (tapFeed != null) tapFeed.source else null
            )
            val gson = new Gson
            // Must persist metadata before any statusUtil calls so getPipelineName can resolve the pipeline token
            NoSQLDbUtil.setItemNameValue(
                DatrisEnvironment.current.archivedMetadataTableName,
                "pipeline_token",
                pipelineToken,
                "metadata",
                gson.toJson(metadata)
            )

            statusUtil.info("begin", "Stream data received, pipeline: " + pipeline + ", filename: " + filename)
            statusUtil.info("processing", "Total data size: " + sizeHint.toString)

            val (dataObj, resolvedConfig) = StagingArea.withToken(pipelineToken)(stage(config))

            statusUtil.info("end", "Process completed successfully")

            JobContext(pipelineToken, metadata, dataObj, resolvedConfig, null, INITIALIZED, null, statusUtil, DatrisEnvironment.current)
        } catch {
            case e: Exception =>
                // No JobContext exists yet, so no JobRunner finally will reclaim
                // whatever stageData wrote before failing.
                StagingArea.delete(pipelineToken)
                statusUtil.error("end", "Process completed, error: " + Throwables.getStackTraceAsString(e))
                throw new DatrisException("StreamNotifier error: " + Throwables.getStackTraceAsString(e))
        }
    }

    /** Write the incoming payload to a staged file and describe it as `Data`.
      * CSV: the header line is read off the stream, `DataUtil.evolveSchema`
      * runs exactly as before, and the rows are projected to schema order
      * straight into a `Delimited` file. JSON: streamed to NDJSON (array →
      * one line per element, single object → one line). XML stages verbatim.
      * Unstructured stages verbatim and still fills `rawBytes`. */
    private[controller] def stageData(source: InputStream, sizeHint: Long, originalConfig: PipelineConfig): (Data, PipelineConfig) = {
        var config = originalConfig
        val size = sizeHint

        if (config.source.fileAttributes.csvAttributes != null) {
            val csvAttributes = config.source.fileAttributes.csvAttributes
            val trimColumns = config.transformation != null && config.transformation.trimColumnWhitespace
            val delimiter = csvAttributes.delimiter

            // Read the header line off the stream, then hand the parser the
            // header bytes followed by the rest of the stream so it sees exactly
            // the record sequence it did when the whole payload was in memory.
            val buffered = new BufferedInputStream(source)
            val (sourceColumns, csvStream) = {
                if (csvAttributes.header) {
                    val headerBytes = readLineBytes(buffered)
                    // A 0-byte payload has no header to evolve against; fail here
                    // so schema evolution never sees "" as a new column.
                    if (headerBytes.isEmpty)
                        throw new DatrisException(
                            "No data rows found in uploaded file for pipeline: " + config.name + ". The file may be empty or contain only a header row."
                        )
                    // Validate the header with commons-csv BEFORE schema evolution
                    // (guard-before-evolve, like the 0-byte guard above): a line 1
                    // that is not CSV must never become pipeline columns.
                    val headerLine = new String(headerBytes, StandardCharsets.UTF_8).stripLineEnd
                    val columns = DataUtil.validatedCsvHeader(headerLine, delimiter, config.name)
                    (columns, new SequenceInputStream(new ByteArrayInputStream(headerBytes), buffered): InputStream)
                } else
                    (config.source.schemaProperties.fields.asScala.map(_.name).toList, buffered: InputStream)
            }

            // Schema evolution: detect new/missing columns, update config
            val (resolvedConfig, schemaColumns, presentColumns, missingColumns) = DataUtil.evolveSchema(sourceColumns, config, statusUtil)
            config = resolvedConfig

            val format = StagedFormat.Delimited(delimiter)
            // Budgeted: the upload lane honours PIPELINE_MAX_PAYLOAD_MB on the
            // bytes written (plans/stories/streaming-pipeline-phase5.md, Step 3).
            val (path, writer) = StagingArea.newBudgetedWriter("notifier", format)
            val rowCount =
                try
                    new CSVReader().readToWriter(
                        csvStream,
                        csvAttributes.header,
                        delimiter,
                        sourceColumns,
                        presentColumns,
                        trimColumns = trimColumns,
                        removeHeader = true,
                        out = writer
                    )
                finally writer.close()

            // Return only present columns when some are missing — PostgresLoader
            // will COPY only these, and Postgres will default missing columns to NULL
            val header = if (missingColumns.isEmpty) schemaColumns else presentColumns

            if (rowCount == 0)
                throw new DatrisException(
                    "No data rows found in uploaded file for pipeline: " + config.name + ". The file may be empty or contain only a header row."
                )

            val staged = StagedPayload(path.toString, format, rowCount, Files.size(path))
            (new Data(size, header, config.source.schemaProperties.fields.asScala.toList, staged, null), config)
        } else if (config.source.fileAttributes.jsonAttributes != null) {
            val reader = new java.io.InputStreamReader(source, StandardCharsets.UTF_8)
            (new Data(size, null, null, PayloadStager.stageJson("notifier", reader, budgeted = true), null), config)
        } else if (config.source.fileAttributes.xmlAttributes != null) {
            (new Data(size, null, null, PayloadStager.stageStream("notifier", StagedFormat.Xml, source, budgeted = true), null), config)
        } else if (config.source.fileAttributes.unstructuredAttributes != null) {
            val bytes =
                try source.readAllBytes()
                finally source.close()
            if (StagingArea.overBudget(bytes.length.toLong)) throw new DatrisException(StagingArea.budgetExceededMessage(bytes.length.toLong))
            (new Data(size, null, null, PayloadStager.stageBytes("notifier", bytes), bytes), config)
        } else
            throw new DatrisException("StreamNotifier: unsupported file type in pipeline config for pipeline: " + config.name)
    }

    /** Adopt a staged tap payload into the current run's token directory. The
      * adopted file keeps its format, `rowCount` and `arraySource`; `Data.size`
      * is `sizeHint`, header and rawBytes are null, and the config is returned
      * as-is (no schema evolution on the JSON / XML path). A payload whose
      * format does not match what the pipeline expects (CSV, unstructured, or a
      * mismatch such as XML into a JSON pipeline) is fed through the
      * `InputStream` overload instead, which parses it exactly as an upload. */
    private[controller] def stageData(staged: StagedPayload, sizeHint: Long, config: PipelineConfig): (Data, PipelineConfig) = {
        if (staged == null || staged.isEmpty)
            throw new DatrisException("StreamNotifier: no staged payload to feed for pipeline: " + config.name)
        val attrs = config.source.fileAttributes
        val adoptable =
            (attrs.jsonAttributes != null && staged.format == StagedFormat.NdJson) ||
                (attrs.xmlAttributes != null && (staged.format == StagedFormat.Xml || staged.format == StagedFormat.Text))
        if (!adoptable) {
            val source = new BufferedInputStream(Files.newInputStream(Paths.get(staged.path)))
            return stageData(source, sizeHint, config)
        }
        val target = StagingArea.newFile("notifier", staged.format)
        Files.move(Paths.get(staged.path), target, StandardCopyOption.REPLACE_EXISTING)
        val adopted = staged.copy(path = target.toString)
        (new Data(sizeHint, null, null, adopted, null), config)
    }

    /** Bytes up to and including the first '\n' (or EOF), without reading past it. */
    private def readLineBytes(in: InputStream): Array[Byte] = {
        val buf = new ByteArrayOutputStream()
        var b = in.read()
        while (b != -1) {
            buf.write(b)
            if (b == '\n') return buf.toByteArray
            b = in.read()
        }
        buf.toByteArray
    }

    def process(config: PipelineConfig, data: String): JobContext = {
        val pipeline = config.name
        logger.info("Processing stream message for pipeline: " + pipeline)
        statusUtil.setFilename("stream: " + pipeline)
        // Generate a UUID to track the pipeline through the pipeline
        val pipelineToken = UUID.randomUUID().toString

        try {
            statusUtil.setPipelineToken(pipelineToken)
            statusUtil.setPublisherToken(pipelineToken)
            statusUtil.info("begin", "Stream received for pipeline: " + pipeline)
            statusUtil.info("processing", "Total data size: " + data.length.toString)

            // Save the metadata in NoSQL
            val metadata = PipelineMetadata(pipeline, null, null, pipelineToken, bulkUpload = false)
            val gson = new Gson
            val jsonMetadata = gson.toJson(metadata)
            NoSQLDbUtil.setItemNameValue(DatrisEnvironment.current.archivedMetadataTableName, "pipeline_token", pipelineToken, "metadata", jsonMetadata)

            val dataObj = StagingArea.withToken(pipelineToken)(Data(data.length, null, null, null, data))

            statusUtil.info("end", "Process completed successfully")

            JobContext(pipelineToken, metadata, dataObj, config, null, INITIALIZED, null, statusUtil)
        } catch {
            case e: Exception =>
                StagingArea.delete(pipelineToken)
                statusUtil.error("end", "Process completed, error: " + Throwables.getStackTraceAsString(e))
                throw new DatrisException("FileNotifier error: " + Throwables.getStackTraceAsString(e))
        }
    }
}
