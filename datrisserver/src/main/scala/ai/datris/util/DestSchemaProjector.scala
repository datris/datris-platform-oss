package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{DatrisException, JobContext, StagedFormat}
import org.slf4j.{Logger, LoggerFactory}

import java.util.regex.Pattern
import scala.collection.JavaConverters._

/** Source→destination column projection for the SQL bulk loaders (Postgres,
  * Snowflake, Databricks), lifted to a lazy `Iterator[String] => Iterator[String]`
  * (plans/stories/streaming-pipeline-phase2.md, step 3). Column selection,
  * the "Dropped columns" status message and delimiter resolution are exactly
  * what each loader's `projectRowsToDestSchema` did; the only difference is
  * that the delimiter is split literally (`Pattern.quote`) so `|` and `.`
  * behave as delimiters rather than regexes.
  */
object DestSchemaProjector {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** Delimiter from the source csvAttributes, else ",". */
    def delimiter(jobContext: JobContext): String = {
        val config = jobContext.config
        if (config.source.fileAttributes != null && config.source.fileAttributes.csvAttributes != null)
            config.source.fileAttributes.csvAttributes.delimiter
        else
            ","
    }

    /** Reorder / drop source columns to match the destination schema. When the
      * schemas already match, `rows` is returned as is. The "Dropped columns"
      * message is sent when the projection is set up, before any row is read. */
    def project(jobContext: JobContext, rows: Iterator[String]): Iterator[String] = {
        val config = jobContext.config
        val statusUtil = jobContext.statusUtil
        val sourceFields = config.source.schemaProperties.fields.asScala.toList
        val destFields = config.destination.schemaProperties.fields.asScala.toList
        val delim = delimiter(jobContext)

        logger.info("projectRowsToDestSchema: source fields=" + sourceFields.map(_.name).mkString(",") +
            " dest fields=" + destFields.map(_.name).mkString(","))

        // If source and destination schemas have the same fields in the same order, no projection needed
        if (sourceFields.size == destFields.size && sourceFields.map(_.name.toLowerCase) == destFields.map(_.name.toLowerCase)) {
            logger.info("projectRowsToDestSchema: schemas match, no projection needed")
            return rows
        }

        // Build a map from source field name (lowercase) to its position index
        val sourceIndex: Map[String, Int] = {
            if (jobContext.data.header != null && jobContext.data.header.nonEmpty)
                jobContext.data.header.zipWithIndex.map { case (name, idx) => name.toLowerCase -> idx }.toMap
            else
                sourceFields.zipWithIndex.map { case (f, idx) => f.name.toLowerCase -> idx }.toMap
        }

        // For each destination field that exists in source, find its index
        // Missing columns (dropped from CSV) are skipped — the destination defaults them to NULL
        val projectedDest = destFields.filter(f => sourceIndex.contains(f.name.toLowerCase))
        val destColumnIndices = projectedDest.map(f => sourceIndex(f.name.toLowerCase))

        if (projectedDest.size < destFields.size) {
            val missing = destFields.filterNot(f => sourceIndex.contains(f.name.toLowerCase)).map(_.name)
            statusUtil.info("processing", "Dropped columns (will be NULL in destination): " + missing.mkString(", "))
        }

        statusUtil.info(
            "processing",
            "Projecting " + sourceFields.size + " source columns to " + projectedDest.size + " destination columns: " + projectedDest.map(_.name).mkString(", ")
        )

        val splitter = Pattern.quote(delim)
        rows.map { row =>
            val columns = row.split(splitter, -1)
            destColumnIndices.map(idx => if (idx < columns.length) columns(idx) else "").mkString(delim)
        }
    }

    /** The CSV body a SQL bulk loader feeds to its COPY / PUT, as a stream of
      * text pieces: projected rows joined by "\n" (no trailing newline) for a
      * delimited payload, or one quoted CSV value holding the whole document
      * (quotes doubled) for JSON / XML / text — the same bytes the loaders
      * built in memory before Phase 2. Throws the loaders' "No data to load"
      * error for an empty payload. The caller must close the iterator. */
    /** True when [[csvBody]] has something to load: a non-empty delimited
      * payload or a JSON / XML / text document. Loaders check this BEFORE any
      * DDL or TRUNCATE so an empty payload fails without touching the table. */
    def hasCsvBody(jobContext: JobContext): Boolean = {
        val staged = jobContext.data.staged
        if (staged == null || staged.isEmpty) false
        else staged.format match {
            case StagedFormat.Delimited(_) => staged.rowCount > 0L
            case StagedFormat.Binary => false
            case _ => true
        }
    }

    /** Throw the loaders' "No data to load" error unless [[hasCsvBody]]. */
    def requireCsvBody(jobContext: JobContext): Unit =
        if (!hasCsvBody(jobContext)) throw new DatrisException("No data to load — both rows and rawData are empty")

    def csvBody(jobContext: JobContext): CloseableIterator[String] = {
        val data = jobContext.data
        val staged = data.staged
        requireCsvBody(jobContext)
        staged.format match {
            case StagedFormat.Delimited(_) =>
                val source = data.rowIterator()
                val projected =
                    try project(jobContext, source)
                    catch {
                        case e: Throwable =>
                            source.close()
                            throw e
                    }
                var first = true
                CloseableIterator(
                    projected.map { row =>
                        if (first) { first = false; row }
                        else "\n" + row
                    },
                    () => source.close()
                )
            case StagedFormat.NdJson =>
                // The deprecated rawData shape: NDJSON lines joined by "\n", or
                // re-wrapped as a JSON array when the payload arrived as one.
                val source = data.recordIterator()
                var index = 0L
                val escaped = source.map { line =>
                    val piece = line.replace("\"", "\"\"")
                    val sep = if (index == 0L) "" else if (staged.arraySource) "," else "\n"
                    index += 1
                    sep + piece
                }
                val body =
                    if (staged.arraySource) Iterator("\"[") ++ escaped ++ Iterator("]\"")
                    else Iterator("\"") ++ escaped ++ Iterator("\"")
                CloseableIterator(body, () => source.close())
            case StagedFormat.Xml | StagedFormat.Text =>
                val source = StagedRows.chunks(java.nio.file.Paths.get(staged.path))
                val body = Iterator("\"") ++ source.map(_.replace("\"", "\"\"")) ++ Iterator("\"")
                CloseableIterator(body, () => source.close())
            case _ =>
                throw new DatrisException("No data to load — both rows and rawData are empty")
        }
    }
}
