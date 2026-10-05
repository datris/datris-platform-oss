package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{CsvAttributes, PipelineConfig, PipelineMetadata, DatrisException, SchemaField, StagedFormat, StagedPayload}
import ai.datris.model.Data
import org.slf4j.{Logger, LoggerFactory}

import org.apache.commons.csv.{CSVFormat, CSVParser}

import java.io.{BufferedReader, InputStream, InputStreamReader, StringReader, Writer}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.collection.JavaConverters._

object DataUtil {
    private val logger: Logger = LoggerFactory.getLogger(DataUtil.getClass)

    /**
     * Merge newly detected columns into a destination field list. Names match
     * case-insensitively after trimming; an existing entry (and its declared
     * type) is kept untouched; new names are appended in order; a duplicate
     * already inside `existing` is dropped (first occurrence wins).
     */
    private[datris] def mergeDestFields(existing: List[SchemaField], added: List[SchemaField]): List[SchemaField] = {
        def key(f: SchemaField): String = Option(f.name).map(_.trim.toLowerCase).getOrElse("")
        val seen = scala.collection.mutable.LinkedHashSet[String]()
        val out = scala.collection.mutable.ListBuffer[SchemaField]()
        (existing ++ added).foreach { f =>
            if (seen.add(key(f))) out += f
        }
        out.toList
    }

    /** The destination schema with duplicate names removed (first entry
      * wins), and whether anything was removed. A status line names the
      * removed columns. */
    private def repairDestDuplicates(config: PipelineConfig, statusUtil: StatusUtil): (PipelineConfig, Boolean) = {
        if (config.destination == null || config.destination.schemaProperties == null || config.destination.schemaProperties.fields == null)
            return (config, false)
        val existing = config.destination.schemaProperties.fields.asScala.toList
        val deduped = mergeDestFields(existing, Nil)
        if (deduped.size == existing.size) return (config, false)
        val removed = existing.diff(deduped).map(_.name).distinct
        logger.info("Schema evolution: removed duplicate destination column(s) [" + removed.mkString(", ") + "] from pipeline " + config.name)
        statusUtil.info("processing", "Schema evolution: removed duplicate destination column(s) [" + removed.mkString(", ") + "]; the first entry is kept")
        val schema = config.destination.schemaProperties.copy(fields = new java.util.ArrayList[SchemaField](deduped.asJava))
        (config.copy(destination = config.destination.copy(schemaProperties = schema)), true)
    }

    /** Columns present in both lists (trimmed, case-insensitive), each in its
      * own list's order; Some((source, destination)) when the relative orders
      * differ, None when they match. */
    private[datris] def objectStoreOrderMismatch(sourceOrder: List[String], destOrder: List[String]): Option[(List[String], List[String])] = {
        def key(n: String): String = Option(n).map(_.trim.toLowerCase).getOrElse("")
        val destKeys = destOrder.map(key).toSet
        val srcKeys = sourceOrder.map(key).toSet
        val src = sourceOrder.filter(n => destKeys.contains(key(n)))
        val dest = destOrder.filter(n => srcKeys.contains(key(n)))
        if (src.map(key) == dest.map(key)) None else Some((src, dest))
    }

    /**
     * Schema evolution: detect new/missing columns and update config.
     * Returns (updatedConfig, updatedSchemaColumns, presentColumns, missingColumns).
     */
    def evolveSchema(
        sourceColumns: List[String],
        storedConfig: PipelineConfig,
        statusUtil: StatusUtil,
        persist: PipelineConfig => Unit = PipelineConfigIO.write
    ): (PipelineConfig, List[String], List[String], List[String]) = {
        // Repair a destination schema that already lists a column twice (written
        // by an earlier run before the duplicate check existed): first entry wins.
        val (config, repaired) = repairDestDuplicates(storedConfig, statusUtil)
        var updatedConfig = config
        var schemaColumns = config.source.schemaProperties.fields.asScala.map(_.name).toList

        // Detect new columns in the CSV that are not in the schema (additive schema evolution)
        val newColumns = sourceColumns.filterNot(col =>
            schemaColumns.exists(_.equalsIgnoreCase(col))
        )

        if (newColumns.nonEmpty) {
            logger.info("Schema evolved: added fields [" + newColumns.mkString(", ") + "] to pipeline " + config.name)
            statusUtil.info("processing", "Schema evolution: new columns detected [" + newColumns.mkString(", ") + "], adding to pipeline schema")

            val newFields = newColumns.map(col => SchemaField(col, "string"))
            // Field protection preset (field-protection-10): a new column the
            // preset recognises joins the SOURCE schema with its (clamped)
            // policy, so FieldProtection protects it in this same run (it reads
            // the config returned here) and the persisted config carries it.
            val newSourceFields = ProtectionPreset.supportedPresetOf(config) match {
                case Some(preset) =>
                    val (keys, destTypes) = FieldProtectionAdvisor.constraintsOf(config)
                    val exempt = ProtectionPreset.exemptOf(config)
                    newFields.map { f =>
                        if (exempt.contains(f.name.trim.toLowerCase)) {
                            statusUtil.info(
                                "processing",
                                "Preset " + preset + ": new column '" + f.name + "' is listed under protection.presetExempt and is not protected"
                            )
                            f
                        } else ProtectionPreset.classify(f.name) match {
                            case Some((klass, policy)) =>
                                FieldProtectionAdvisor.constrain(f.name, f.`type`, policy, keys, destTypes) match {
                                    case Right(p) =>
                                        statusUtil.info(
                                            "processing",
                                            "Preset " + preset + ": new column '" + f.name + "' protected as " + klass + " (" + p.label + ")"
                                        )
                                        f.copy(protect = p)
                                    case Left(why) =>
                                        statusUtil.warn(
                                            "processing",
                                            "Preset " + preset + ": new column '" + f.name + "' looks like " + klass + " but is not protected (" + why +
                                                "); it lands as is, and the next save of this pipeline will be refused until it is protected or listed under protection.presetExempt"
                                        )
                                        f
                                }
                            case None =>
                                statusUtil.warn(
                                    "processing",
                                    "Preset " + preset + ": new column '" + f.name + "' is not recognised by the preset and is not protected"
                                )
                                f
                        }
                    }
                case None => newFields
            }
            val updatedFields = new java.util.ArrayList[SchemaField](config.source.schemaProperties.fields)
            newSourceFields.foreach(f => updatedFields.add(f))

            val newVersion = config.source.schemaProperties.schemaVersion + 1
            val updatedSourceSchema = config.source.schemaProperties.copy(fields = updatedFields, schemaVersion = newVersion)
            val updatedSource = config.source.copy(schemaProperties = updatedSourceSchema)

            // Update destination schema if it exists
            val updatedDest = if (config.destination != null && config.destination.schemaProperties != null) {
                val existingDest = Option(config.destination.schemaProperties.fields).map(_.asScala.toList).getOrElse(Nil)
                newFields.foreach { f =>
                    existingDest.find(d => d.name != null && d.name.trim.equalsIgnoreCase(f.name.trim)).foreach { d =>
                        statusUtil.info(
                            "processing",
                            "Schema evolution: column '" + f.name + "' is already in the destination schema (" + d.`type` + "); kept as declared"
                        )
                    }
                }
                // Object store loads read the staged file by position against the
                // destination schema, so a declared column joining the source in a
                // different relative order would land in the wrong column.
                val declaredNew = newFields.filter(f => existingDest.exists(d => d.name != null && d.name.trim.equalsIgnoreCase(f.name.trim)))
                if (declaredNew.nonEmpty && config.destination.objectStore != null) {
                    val sourceOrder = updatedSourceSchema.fields.asScala.map(_.name).toList
                    objectStoreOrderMismatch(sourceOrder, existingDest.map(_.name)).foreach {
                        case (src, dest) =>
                            throw new DatrisException(
                                "Object store pipelines map columns by position: the file's column order (" + src.mkString(", ") +
                                    ") differs from the destination schema (" + dest.mkString(", ") + "). Reorder the destination schema or the file"
                            )
                    }
                }
                val destFields = new java.util.ArrayList[SchemaField](mergeDestFields(existingDest, newFields).asJava)
                val updatedDestSchema = config.destination.schemaProperties.copy(fields = destFields, schemaVersion = newVersion)
                config.destination.copy(schemaProperties = updatedDestSchema)
            } else config.destination

            updatedConfig = config.copy(source = updatedSource, destination = updatedDest)
            // Gson write of the whole config: `protect` and `protection` survive.
            persist(updatedConfig)

            schemaColumns = updatedSourceSchema.fields.asScala.map(_.name).toList
        } else if (repaired) {
            persist(config)
        }

        // Detect missing schema columns in the CSV header
        val missingColumns = schemaColumns.filterNot(col =>
            sourceColumns.exists(_.equalsIgnoreCase(col))
        )

        if (missingColumns.nonEmpty) {
            // Fail if any missing column is a key field
            val keyFields = Option(config.destination)
                .flatMap(d => Option(d.database))
                .flatMap(db => Option(db.keyFields))
                .map(_.asScala.map(_.toLowerCase).toSet)
                .getOrElse(Set.empty)

            val missingKeyFields = missingColumns.filter(col => keyFields.contains(col.toLowerCase))
            if (missingKeyFields.nonEmpty) {
                throw new DatrisException(
                    "CSV is missing required key field(s): " + missingKeyFields.mkString(", ") +
                        ". CSV columns: " + sourceColumns.mkString(", ") +
                        ". Expected columns: " + schemaColumns.mkString(", ")
                )
            }

            statusUtil.info("processing", "CSV is missing columns (will be NULL in destination): " + missingColumns.mkString(", "))
        }

        // Only columns that exist in the CSV
        val presentColumns = schemaColumns.filter(col =>
            sourceColumns.exists(_.equalsIgnoreCase(col))
        )

        (updatedConfig, schemaColumns, presentColumns, missingColumns)
    }

    /** The CSV header line, validated with commons-csv BEFORE `evolveSchema` sees
      * it: exactly one record, at least one column, every column non-blank
      * after trim. A payload whose line 1 is not CSV (a JSON array, a JSON
      * object, binary junk) used to be split on the delimiter, evolve the
      * schema with garbage columns — writing the config — and only then fail in
      * the parser. Now the parser's own message is thrown first and nothing is
      * written. Returns the lower-cased column names, as the callers always did. */
    def validatedCsvHeader(headerLine: String, rawDelimiter: String, pipelineName: String): List[String] = {
        val delimiter = CsvAttributes.resolveDelimiter(rawDelimiter)
        val format = CSVFormat.RFC4180.builder().setDelimiter(delimiter).build()
        val records =
            try {
                val parser = new CSVParser(new StringReader(headerLine), format)
                try parser.getRecords.asScala.toList
                finally parser.close()
            } catch {
                case e: DatrisException => throw e
                case e: Exception =>
                    throw new DatrisException(
                        "Invalid CSV header line for pipeline " + pipelineName + ": " + e.getMessage +
                            ". The first line of the file must be a delimited header row."
                    )
            }
        if (records.size != 1)
            throw new DatrisException(
                "Invalid CSV header line for pipeline " + pipelineName + ": expected one header record, parsed " + records.size
            )
        // Trailing empties (`id,amount,` — Excel / Sheets exports) are dropped, as
        // the previous String.split did; an interior blank (`id,,amount`) or an
        // all-blank header is still rejected.
        val columns = records.head.iterator().asScala.map(_.toLowerCase).toList.reverse.dropWhile(_.trim.isEmpty).reverse
        if (columns.isEmpty || columns.exists(_.trim.isEmpty))
            throw new DatrisException(
                "Invalid CSV header line for pipeline " + pipelineName + ": every column must have a name, got [" +
                    columns.mkString(delimiter) + "]"
            )
        columns
    }

    def read(bucket: String, key: String, config: PipelineConfig, metadata: PipelineMetadata, statusUtil: StatusUtil): (Data, PipelineConfig) = {
        val files = new PipelineMetadataUtil(statusUtil).getFiles(metadata)
        val size = getSize(bucket, key, metadata)
        read(files, url => ObjectStoreUtil.getInputStream(ObjectStoreUtil.getBucket(url), ObjectStoreUtil.getKey(url)), size, config, statusUtil)
    }

    /** The FileNotifier read (plans/stories/streaming-pipeline-phase5.md, Step 5),
      * with the object-store opener injected so the stream-level body is testable.
      * `files` are read in list order; `open` returns a fresh stream for one of
      * them (closed here).
      *  - CSV: the header comes off file 1 (`header = true`), `evolveSchema`
      *    runs exactly as before, then every file streams through
      *    `CSVReader.readToWriter` into ONE `Delimited` staged file — the
      *    header line of files 2+ dropped — and zero rows raise the existing
      *    "No data rows found" error.
      *  - JSON: `files.head` staged as NDJSON (`PayloadStager.stageJson`, values
      *    verbatim); a file that does not parse is staged verbatim as text, the
      *    fallback this lane has always had.
      *  - XML and unstructured: `files.head` copied verbatim (unstructured also
      *    fills `rawBytes`, as the loaders expect). */
    def read(files: List[String], open: String => InputStream, size: Long, config: PipelineConfig, statusUtil: StatusUtil): (Data, PipelineConfig) = {
        if (config.source.fileAttributes.csvAttributes != null) {
            val csvAttributes = config.source.fileAttributes.csvAttributes
            val trimColumns = config.transformation != null && config.transformation.trimColumnWhitespace
            val delimiter = csvAttributes.effectiveDelimiter

            // Read the actual header from the first file if header=true
            val sourceColumns = {
                if (csvAttributes.header) {
                    val reader = new BufferedReader(new InputStreamReader(open(files.head), StandardCharsets.UTF_8))
                    try {
                        val headerLine = Option(reader.readLine()).getOrElse("")
                        validatedCsvHeader(headerLine, delimiter, config.name)
                    } finally reader.close()
                } else
                    config.source.schemaProperties.fields.asScala.map(_.name).toList
            }

            // Schema evolution: detect new/missing columns, update config
            val (resolvedConfig, schemaColumns, presentColumns, missingColumns) = evolveSchema(sourceColumns, config, statusUtil)

            val format = StagedFormat.Delimited(delimiter)
            // Budgeted like the upload lane: PIPELINE_MAX_PAYLOAD_MB bounds what
            // one pickup may stage on disk; FileNotifier drops the run dir on failure.
            val (path, writer) = StagingArea.newBudgetedWriter("notifier", format)
            var rowCount = 0L
            try
                files.foreach { fileUrl =>
                    // readToWriter separates its own rows with "\n" but writes the
                    // first one bare; between files the separator is ours, and only
                    // once the next file proves it has a row.
                    val out = new FileSeparatorWriter(writer, rowCount > 0)
                    rowCount += new CSVReader().readToWriter(
                        open(fileUrl),
                        csvAttributes.header,
                        delimiter,
                        sourceColumns, // actual CSV column order
                        presentColumns, // only columns present in CSV
                        trimColumns = trimColumns,
                        removeHeader = true,
                        out = out
                    )
                }
            finally writer.close()

            if (rowCount == 0)
                throw new DatrisException(
                    "No data rows found in uploaded file for pipeline: " + config.name + ". The file may be empty or contain only a header row."
                )

            // Return only present columns when some are missing — PostgresLoader
            // will COPY only these, and Postgres will default missing columns to NULL
            val header = if (missingColumns.isEmpty) schemaColumns else presentColumns
            val headerWithSchema = resolvedConfig.source.schemaProperties.fields.asScala.toList
            val staged = StagedPayload(path.toString, format, rowCount, Files.size(path))
            (new Data(size, header, headerWithSchema, staged, null), resolvedConfig)
        } else if (config.source.fileAttributes.jsonAttributes != null) {
            val fileUrl = files.head
            val staged =
                try PayloadStager.stageJson("notifier", new InputStreamReader(open(fileUrl), StandardCharsets.UTF_8), budgeted = true)
                catch {
                    case e: DatrisException => throw e // the disk budget, not a parse failure
                    case e: Exception =>
                        logger.warn("Source file " + fileUrl + " is not valid JSON (" + e.getMessage + "); staging it verbatim")
                        PayloadStager.stageStream("notifier", StagedFormat.Text, open(fileUrl), budgeted = true)
                }
            (new Data(size, null, null, staged, null), config)
        } else if (config.source.fileAttributes.xmlAttributes != null) {
            (new Data(size, null, null, PayloadStager.stageStream("notifier", StagedFormat.Xml, open(files.head), budgeted = true), null), config)
        } else if (config.source.fileAttributes.unstructuredAttributes != null) {
            val inputStream = open(files.head)
            val rawBytes =
                try inputStream.readAllBytes()
                finally inputStream.close()
            (new Data(size, null, null, PayloadStager.stageBytes("notifier", rawBytes), rawBytes), config)
        } else
            throw new DatrisException("Unsupported file type in pipeline config for pipeline: " + config.name)
    }

    /** Writes a "\n" ahead of the first write when `separate` is set, so a
      * second file's rows join the first file's without a blank line and a
      * file with no rows adds nothing. `out` is not closed. */
    private class FileSeparatorWriter(out: Writer, separate: Boolean) extends Writer {
        private var pending = separate
        override def write(cbuf: Array[Char], off: Int, len: Int): Unit = {
            if (pending) { out.write("\n"); pending = false }
            out.write(cbuf, off, len)
        }
        override def flush(): Unit = out.flush()
        override def close(): Unit = out.flush()
    }

    private def getSize(bucket: String, key: String, metadata: PipelineMetadata): Long = {
        // Get the file size
        val objectMetadata = ObjectStoreUtil.getObjectMetadata(bucket, key)
        val objectSize = {
            // Bulk file ingestion?
            if (metadata.dataFilePath != null) {
                val summaries = ObjectStoreUtil.listSummaries(ObjectStoreUtil.getBucket(metadata.dataFilePath), ObjectStoreUtil.getKey(metadata.dataFilePath))
                summaries.map(_.size).sum
            } else
                objectMetadata.contentLength
        }

        objectSize
    }
}
