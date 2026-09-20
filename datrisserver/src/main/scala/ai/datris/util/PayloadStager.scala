package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{StagedFormat, StagedPayload}
import com.google.gson.stream.{JsonReader, JsonToken}
import com.google.gson.{Gson, GsonBuilder, JsonParser}

import java.io.{InputStream, InputStreamReader, Reader, StringReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

/** Writes payloads into the [[StagingArea]] and describes the result as a
  * [[StagedPayload]]. Shared by `Data`'s in-memory constructors and
  * `StreamNotifier.stageData`.
  */
object PayloadStager {
    // Element-at-a-time re-serialization must not alter the data: keep nulls,
    // keep `<`/`>`/`&` unescaped (Gson's default HTML-escaping would rewrite them).
    // Shared with ProvenanceStamper so a stamped record keeps exactly these bytes.
    val gson: Gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    /** Delimited rows, one per line, no trailing newline (the exact bytes
      * `CSVReader.readFromStream` produced before staging existed). */
    def stageRows(stage: String, rows: List[String], delimiter: String): StagedPayload =
        stageRowIterator(stage, rows.iterator, delimiter)

    /** Same layout as [[stageRows]], fed from an iterator: rows are written and
      * counted as they are produced, so a stage that maps `Data.rowIterator()`
      * never holds the payload in heap. The iterator is consumed, not closed. */
    def stageRowIterator(stage: String, rows: Iterator[String], delimiter: String): StagedPayload = {
        val format = StagedFormat.Delimited(delimiter)
        val (path, writer) = StagingArea.newWriter(stage, format)
        var count = 0L
        try {
            while (rows.hasNext) {
                val row = rows.next()
                if (count > 0) writer.write("\n")
                writer.write(row)
                count += 1
            }
        } finally writer.close()
        StagedPayload(path.toString, format, count, Files.size(path))
    }

    /** Adopt a file written outside the staging area (a CodeGen script's
      * output) as a staged payload: the file is MOVED into the current run's
      * directory — so `JobRunner`'s cleanup reclaims it — and its records are
      * counted with [[StagedRows]] (quote-aware for delimited data, so a value
      * holding an embedded newline is one record). Verbatim formats count 1 for
      * a non-empty file. `arraySource` is false; JSON output that should be
      * exploded into records goes through [[stageJson]] instead. */
    def adopt(stage: String, path: Path, format: StagedFormat): StagedPayload = {
        val target = StagingArea.newFile(stage, format)
        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
        val bytes = Files.size(target)
        val count = format match {
            case StagedFormat.Delimited(delimiter) => countRecords(StagedRows.delimited(target.toString, delimiter))
            case StagedFormat.NdJson => countRecords(StagedRows.lines(target.toString))
            case _ => if (bytes > 0) 1L else 0L
        }
        StagedPayload(target.toString, format, count, bytes)
    }

    private def countRecords(it: CloseableIterator[String]): Long = {
        var n = 0L
        try while (it.hasNext) { it.next(); n += 1 }
        finally it.close()
        n
    }

    /** JSON from a stream: one compact JSON value per output line. A top-level
      * array is exploded into its elements (rowCount = element count and
      * `arraySource = true`); a single value is one line; NDJSON — several
      * top-level values — is one line per value. Closes `source`. */
    def stageJson(stage: String, source: InputStream): StagedPayload =
        stageJson(stage, new InputStreamReader(source, StandardCharsets.UTF_8))

    /** With `budgeted` the bytes written are held to the per-run payload budget
      * (`StagingArea.newBudgetedWriter`); the ingest lanes use it. */
    def stageJson(stage: String, source: Reader, budgeted: Boolean = false): StagedPayload = {
        val format = StagedFormat.NdJson
        val (path, writer) = if (budgeted) StagingArea.newBudgetedWriter(stage, format) else StagingArea.newWriter(stage, format)
        var count = 0L
        var arraySource = false
        try {
            val reader = new JsonReader(source)
            reader.setLenient(true)
            def emit(): Unit = {
                val element = JsonParser.parseReader(reader)
                if (count > 0) writer.write("\n")
                writer.write(gson.toJson(element))
                count += 1
            }
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                arraySource = true
                reader.beginArray()
                while (reader.hasNext) emit()
                reader.endArray()
            }
            while (reader.peek() != JsonToken.END_DOCUMENT) emit()
        } finally {
            writer.close()
            source.close()
        }
        StagedPayload(path.toString, format, count, Files.size(path), arraySource)
    }

    /** Verbatim text payload. rowCount is 1 for a non-empty payload, 0 otherwise. */
    def stageText(stage: String, format: StagedFormat, text: String): StagedPayload = {
        val (path, bytes) = StagingArea.writeText(stage, format, text)
        StagedPayload(path.toString, format, if (bytes > 0) 1L else 0L, bytes)
    }

    /** Verbatim stream copy (XML / text). rowCount is 1 for a non-empty payload.
      * With `budgeted` the copy is held to the per-run payload budget. */
    def stageStream(stage: String, format: StagedFormat, source: InputStream, budgeted: Boolean = false): StagedPayload = {
        val (path, bytes) = StagingArea.copyStream(stage, format, source, budgeted)
        StagedPayload(path.toString, format, if (bytes > 0) 1L else 0L, bytes)
    }

    /** Verbatim bytes (unstructured documents). rowCount 1 for non-empty. */
    def stageBytes(stage: String, bytes: Array[Byte]): StagedPayload = {
        val (path, size) = StagingArea.writeBytes(stage, StagedFormat.Binary, bytes)
        StagedPayload(path.toString, StagedFormat.Binary, if (size > 0) 1L else 0L, size)
    }

    /** An in-memory `rawData` string of unknown shape (JSON, XML or opaque
      * text). JSON is staged as NDJSON when it parses; anything else — or JSON
      * that fails to parse — is staged verbatim so the deprecated `rawData`
      * accessor returns exactly what was given. */
    def stageRawString(stage: String, raw: String): StagedPayload = {
        val head = firstNonBlank(raw)
        if (head == '[' || head == '{') {
            try stageJson(stage, new StringReader(raw))
            catch {
                case _: Exception => stageText(stage, StagedFormat.Text, raw)
            }
        } else if (head == '<')
            stageText(stage, StagedFormat.Xml, raw)
        else
            stageText(stage, StagedFormat.Text, raw)
    }

    private def firstNonBlank(s: String): Char = {
        var i = 0
        while (i < s.length && Character.isWhitespace(s.charAt(i))) i += 1
        if (i < s.length) s.charAt(i) else '\u0000'
    }
}
