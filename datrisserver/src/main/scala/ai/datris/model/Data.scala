package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.{CloseableIterator, PayloadStager, StagedRows, StagingArea}
import org.slf4j.{Logger, LoggerFactory}

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** A pipeline run's payload. The bytes live in a staged file on local disk
  * (see [[ai.datris.util.StagingArea]]); this class carries the path, the
  * format and the record count. Consumers should read through [[rowIterator]]
  * / [[recordIterator]] / [[openStream]]. The deprecated [[rows]] and
  * [[rawData]] accessors materialize the whole payload in heap — they log a
  * warning naming the caller and refuse above `PIPELINE_MATERIALIZE_MAX_MB`.
  */
case class Data(
    size: Long,
    header: List[String], // Contains the header names, only used to validate the header of delimited data
    headerWithSchema: List[SchemaField], // Header SchemaFields, this can evolve as the data moves through the pipeline
    staged: StagedPayload, // Where the payload lives on disk and what it looks like
    rawBytes: Array[Byte] // Raw bytes, for unstructured files (PDF, etc.)
) {
    import Data._

    private def payload: StagedPayload = if (staged == null) StagedPayload.empty else staged

    /** Records in the staged payload: delimited rows, NDJSON lines, 1 for a non-empty document. */
    def rowCount: Long = payload.rowCount

    // Format predicates, so stage dispatch reads the staged shape instead of
    // null-checking the deprecated accessors (which materialize the payload).

    /** Delimited rows (possibly zero of them) — what `rows != null` used to mean. */
    def isDelimited: Boolean = payload.format match {
        case StagedFormat.Delimited(_) => true
        case _ => false
    }

    /** One JSON value per line (a JSON array, object or NDJSON payload at ingest). */
    def isNdJson: Boolean = payload.format == StagedFormat.NdJson

    /** A non-empty XML or opaque text document. */
    def isVerbatimDocument: Boolean = payload.format match {
        case StagedFormat.Xml | StagedFormat.Text => !payload.isEmpty
        case _ => false
    }

    /** JSON, XML or text payload — what `rawData != null` used to mean. */
    def isDocument: Boolean = isNdJson || isVerbatimDocument

    /** The delimiter of a delimited payload; `,` for any other format. */
    def delimiter: String = payload.format match {
        case StagedFormat.Delimited(d) => d
        case _ => ","
    }

    /** Stream the staged records without holding them in memory: delimited
      * rows (record-aware — a quoted value holding a line terminator stays
      * inside its row) or NDJSON lines. Closes itself when exhausted; callers
      * that stop early must call `close()`. */
    def rowIterator(): CloseableIterator[String] = openRecords()

    /** One JSON record per element; only meaningful for NDJSON payloads. */
    def recordIterator(): CloseableIterator[String] = openRecords()

    // Shared by both public iterators so a subclass overriding one (a
    // counting spy in the specs) is not invoked twice for the other.
    private def openRecords(): CloseableIterator[String] = {
        val p = payload
        p.format match {
            case StagedFormat.Delimited(delimiter) if !p.isEmpty => StagedRows.delimited(p.path, delimiter)
            case StagedFormat.NdJson if !p.isEmpty => StagedRows.lines(p.path)
            case _ => CloseableIterator.empty
        }
    }

    /** Raw bytes of the staged file. Caller closes. Empty stream for an empty payload. */
    def openStream(): InputStream =
        if (payload.isEmpty) new java.io.ByteArrayInputStream(Array.empty[Byte])
        else new java.io.BufferedInputStream(Files.newInputStream(Paths.get(payload.path)))

    /** Whole-payload rows for delimited data; null for every other format.
      * Reads the staged file into heap — see the class note. */
    @deprecated("materializes the whole payload; use rowIterator()", "1.34.0")
    def rows: List[String] = {
        val p = payload
        p.format match {
            case StagedFormat.Delimited(_) =>
                if (p.isEmpty) List.empty
                else {
                    checkMaterialize(p)
                    openRecords().toList
                }
            case _ => null
        }
    }

    /** Whole-payload text for JSON / XML / text data; null for delimited and
      * binary payloads. Reads the staged file into heap — see the class note. */
    @deprecated("materializes the whole payload; use recordIterator() or openStream()", "1.34.0")
    def rawData: String = {
        val p = payload
        p.format match {
            case StagedFormat.NdJson =>
                checkMaterialize(p)
                val lines = StagedRows.lines(p.path)
                if (p.arraySource) lines.mkString("[", ",", "]") else lines.mkString("\n")
            case StagedFormat.Xml | StagedFormat.Text =>
                if (p.isEmpty) null
                else {
                    checkMaterialize(p)
                    new String(Files.readAllBytes(Paths.get(p.path)), StandardCharsets.UTF_8)
                }
            case _ => null
        }
    }

    /** Swap in a payload a streaming stage already wrote (or adopted) into the
      * staging area. Header, schema and size carry over; the previous file
      * stays on disk until the run directory is removed. */
    def withStaged(newStaged: StagedPayload): Data = copy(staged = newStaged)

    /** Replace the delimited rows: writes a NEW staged file and leaves the
      * previous one on disk (the run directory is removed as a whole when the
      * job ends, never mid-run). */
    def withRows(newRows: List[String]): Data =
        copy(staged = PayloadStager.stageRows("rows", newRows, delimiter))

    /** Replace the raw JSON / XML / text payload: writes a NEW staged file. */
    def withRawData(newRawData: String): Data = {
        val newStaged =
            if (newRawData == null) StagedPayload.empty
            else payload.format match {
                case StagedFormat.NdJson => PayloadStager.stageRawString("raw", newRawData)
                case StagedFormat.Xml => PayloadStager.stageText("raw", StagedFormat.Xml, newRawData)
                case StagedFormat.Text => PayloadStager.stageText("raw", StagedFormat.Text, newRawData)
                case _ => PayloadStager.stageRawString("raw", newRawData)
            }
        copy(staged = newStaged)
    }

    /** Gate for a feature that still reads the whole payload into heap
      * (deduplication, JavaScript row functions, schema validation, the
      * single-call REST preprocessor). Below `PIPELINE_MATERIALIZE_MAX_MB` it
      * is silent and the caller goes on to use [[rows]] / [[rawData]] as
      * before; above it the run fails with an error that names the feature —
      * not a stack frame — and tells the operator what to do. */
    def materializeFor(feature: String): Unit = {
        val p = payload
        val capMB = StagingArea.materializeMaxMB
        if (p.bytes > capMB.toLong * 1024L * 1024L)
            throw new DatrisException(
                feature + " reads the whole payload into memory (" + p.bytes + " bytes) and " + StagingArea.MaterializeCapEnvVar + " is " +
                    capMB + " MB. Raise it for this install, or use a CodeGen rule/transformation, which streams."
            )
    }

    private def checkMaterialize(p: StagedPayload): Unit = {
        val caller = callerFrame()
        val capMB = StagingArea.materializeMaxMB
        if (p.bytes > capMB.toLong * 1024L * 1024L)
            throw new DatrisException(
                caller + " read the whole payload (" + p.bytes + " bytes, " + p.format + ") but " + StagingArea.MaterializeCapEnvVar +
                    "=" + capMB + " MB; raise " + StagingArea.MaterializeCapEnvVar + " or wait for this stage's streaming rewrite"
            )
        logger.warn("staged payload materialized by " + caller + " (" + p.format + ", " + p.bytes + " bytes, " + p.rowCount + " rows)")
    }
}

object Data {
    private val logger: Logger = LoggerFactory.getLogger(classOf[Data])

    /** Old positional shape, kept so existing construction sites compile: the
      * in-memory rows / rawData are staged to disk on construction. */
    def apply(
        size: Long,
        header: List[String],
        headerWithSchema: List[SchemaField],
        rows: List[String],
        rawData: String,
        rawBytes: Array[Byte] = null,
        delimiter: String = ","
    ): Data = {
        val staged =
            if (rows != null) PayloadStager.stageRows("data", rows, delimiter)
            else if (rawData != null) PayloadStager.stageRawString("data", rawData)
            else if (rawBytes != null) PayloadStager.stageBytes("data", rawBytes)
            else StagedPayload.empty
        new Data(size, header, headerWithSchema, staged, rawBytes)
    }

    /** First stack frame outside this class, as `Class.method:line`. */
    private def callerFrame(): String = {
        val skip = Set(classOf[Data].getName, Data.getClass.getName, "java.lang.Thread")
        Thread.currentThread.getStackTrace.iterator
            .drop(1)
            .find(f => !skip.exists(s => f.getClassName.startsWith(s)))
            .map(f => f.getClassName + "." + f.getMethodName + ":" + f.getLineNumber)
            .getOrElse("unknown caller")
    }
}
