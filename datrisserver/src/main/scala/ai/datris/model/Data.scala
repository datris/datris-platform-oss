package ai.datris.model

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.util.{PayloadStager, StagingArea}
import org.slf4j.{Logger, LoggerFactory}

import java.io.{BufferedReader, InputStream}
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

    /** Stream the staged lines (delimited rows or NDJSON records) without
      * holding them in memory. The reader closes itself when exhausted. */
    def rowIterator(): Iterator[String] = {
        val p = payload
        p.format match {
            case StagedFormat.Delimited(_) | StagedFormat.NdJson if !p.isEmpty => lineIterator(p.path)
            case _ => Iterator.empty
        }
    }

    /** One JSON record per element; only meaningful for NDJSON payloads. */
    def recordIterator(): Iterator[String] = rowIterator()

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
                    lineIterator(p.path).toList
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
                val lines = lineIterator(p.path)
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

    /** Replace the delimited rows: writes a NEW staged file and leaves the
      * previous one on disk (the run directory is removed as a whole when the
      * job ends, never mid-run). */
    def withRows(newRows: List[String]): Data = {
        val delimiter = payload.format match {
            case StagedFormat.Delimited(d) => d
            case _ => ","
        }
        copy(staged = PayloadStager.stageRows("rows", newRows, delimiter))
    }

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

    private def lineIterator(path: String): Iterator[String] = {
        val reader = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)
        new LineIterator(reader)
    }

    /** Reads lines lazily; closes the reader at EOF (or on a read failure). */
    private class LineIterator(reader: BufferedReader) extends Iterator[String] {
        private var nextLine: String = _
        private var closed = false

        private def fill(): Unit =
            if (nextLine == null && !closed) {
                try nextLine = reader.readLine()
                catch {
                    case e: Exception =>
                        close()
                        throw e
                }
                if (nextLine == null) close()
            }

        private def close(): Unit = {
            closed = true
            try reader.close()
            catch { case _: Exception => () }
        }

        override def hasNext: Boolean = {
            fill()
            nextLine != null
        }

        override def next(): String = {
            fill()
            if (nextLine == null) throw new NoSuchElementException("staged payload exhausted")
            val line = nextLine
            nextLine = null
            line
        }
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
