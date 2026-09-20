package ai.datris.api

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import ai.datris.util.StagingArea
import org.apache.commons.compress.archivers.{ArchiveEntry, ArchiveInputStream, ArchiveStreamFactory}
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.slf4j.{Logger, LoggerFactory}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer

/** One file staged from an upload part: the name the run is filed under
  * (the entry's basename for an archive entry, the part's filename minus
  * `.gz` for gzip, the part's filename otherwise), the staged path — under
  * the CURRENT `StagingArea` token directory — and its size. `batchedEntries`
  * is how many archive entries were concatenated into it (1 for a plain
  * file), so the controller can tell a CSV batch from a single-entry archive. */
private[api] case class StagedUpload(name: String, path: Path, bytes: Long, batchedEntries: Int = 1)

/** Streams an upload part into staged files without ever holding the payload
  * in heap (plans/stories/streaming-pipeline-phase5.md, Step 1). The archive
  * walk and the CSV batch concatenation that lived inline in
  * `FileUploadAPIController.uploadRawFile` moved here so they work on an
  * `InputStream` and are unit-testable without `spring-test`.
  */
private[api] object UploadStager {
    private val logger: Logger = LoggerFactory.getLogger(getClass)

    /** `.zip`, `.gz`, `.tar` and `.jar` parts are unpacked; anything else is one file. */
    def isArchive(filename: String): Boolean = {
        val lower = Option(filename).getOrElse("").toLowerCase
        lower.endsWith(".zip") || lower.endsWith(".gz") || lower.endsWith(".tar") || lower.endsWith(".jar")
    }

    private def usable(entry: ArchiveEntry): Boolean =
        !entry.isDirectory &&
            !entry.getName.startsWith("__MAC") &&
            !entry.getName.startsWith("META-INF") &&
            !entry.getName.startsWith("./._")

    /** Stage one upload part into staged files, one buffered copy at a time:
      *  - plain file: one file, bytes verbatim;
      *  - `.gz`: one file, the decompressed bytes;
      *  - archive: entries staged one at a time in entry order, skipping
      *    directories and `__MAC` / `META-INF` / `./._` names; no usable entry
      *    raises "No files found in archive";
      *  - archive on a CSV pipeline with more than one usable entry: ONE file
      *    named after entry 1 — entry 1 whole, each later entry minus its
      *    header line when `csvHeader`, a newline inserted only when the
      *    output so far does not end with one.
      *  Every byte written counts toward `PIPELINE_MAX_PAYLOAD_MB`; over budget
      *  the upload fails with `StagingArea.budgetExceededMessage` and nothing
      *  it staged is left behind. Closes `source`. */
    def stage(source: InputStream, filename: String, csvPipeline: Boolean, csvHeader: Boolean): List[StagedUpload] = {
        val staged = ListBuffer[StagedUpload]()
        var current: Path = null
        try {
            if (!isArchive(filename)) {
                current = StagingArea.newFile("upload", extensionOf(filename))
                val bytes = copyTo(current, source, 0L)
                staged += StagedUpload(filename, current, bytes)
            } else if (filename.toLowerCase.endsWith(".gz")) {
                val name = filename.replaceAll("(?i)\\.gz$", "")
                current = StagingArea.newFile("upload", extensionOf(name))
                val gzIn = new GzipCompressorInputStream(new BufferedInputStream(source))
                val bytes = copyTo(current, gzIn, 0L)
                staged += StagedUpload(name, current, bytes)
            } else {
                val archiveIn: ArchiveInputStream[_ <: ArchiveEntry] =
                    new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(source))
                var written = 0L
                var entry = archiveIn.getNextEntry
                while (entry != null) {
                    if (usable(entry)) {
                        val name = entry.getName.split("/").last
                        current = StagingArea.newFile("upload", extensionOf(name))
                        val bytes = copyTo(current, new EntryStream(archiveIn), written)
                        written += bytes
                        staged += StagedUpload(name, current, bytes)
                        current = null
                    }
                    entry = archiveIn.getNextEntry
                }
                if (staged.isEmpty) throw new DatrisException("No files found in archive: " + filename)
                if (csvPipeline && staged.size > 1) {
                    // CSV batch mode: concatenate every entry into one payload (skip headers on entries 2+)
                    logger.info("CSV batch mode: " + staged.size + " files extracted from " + filename)
                    val entries = staged.toList
                    val combined = StagingArea.newFile("upload", extensionOf(entries.head.name))
                    current = combined
                    // The entries already passed the budget one by one and are
                    // removed as they are folded in, so the combined file is not
                    // counted a second time.
                    val out = new java.io.BufferedOutputStream(Files.newOutputStream(combined))
                    var lastByte: Int = -1
                    try
                        entries.zipWithIndex.foreach { case (e, index) =>
                            logger.info("  Batch file: " + e.name + " (" + e.bytes + " bytes)")
                            val in = new BufferedInputStream(Files.newInputStream(e.path))
                            try {
                                if (index == 0) lastByte = pipe(in, out, lastByte)
                                else {
                                    if (lastByte != '\n') { out.write('\n'); lastByte = '\n' }
                                    if (csvHeader) {
                                        var toSkip = headerLength(e.path)
                                        while (toSkip > 0) {
                                            val skipped = in.skip(toSkip)
                                            if (skipped <= 0) toSkip = 0 else toSkip -= skipped
                                        }
                                    }
                                    lastByte = pipe(in, out, lastByte)
                                }
                            } finally in.close()
                            Files.deleteIfExists(e.path)
                        }
                    finally out.close()
                    val bytes = Files.size(combined)
                    logger.info("CSV batch combined size: " + bytes + " bytes from " + entries.size + " files")
                    staged.clear()
                    staged += StagedUpload(entries.head.name, combined, bytes, entries.size)
                    current = null
                }
            }
            staged.toList
        } catch {
            case e: Exception =>
                // Nothing this part staged may survive a failure: the partial
                // file and every earlier entry go with it.
                if (current != null) Files.deleteIfExists(current)
                staged.foreach(s => Files.deleteIfExists(s.path))
                throw e
        } finally source.close()
    }

    private def extensionOf(name: String): String = {
        val base = Option(name).getOrElse("").split("/").last
        val dot = base.lastIndexOf('.')
        val ext = if (dot > 0 && dot < base.length - 1) base.substring(dot + 1).toLowerCase.replaceAll("[^a-z0-9]", "") else ""
        if (ext.isEmpty) "bin" else ext
    }

    /** Copy `in` into a new file at `path` under the budget; `offset` is what
      * the part has already written. Returns the bytes written to `path`. */
    private def copyTo(path: Path, in: InputStream, offset: Long): Long = {
        val out = new StagingArea.BudgetedOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(path)), offset)
        try in.transferTo(out)
        finally out.close()
    }

    /** Buffered copy that reports the last byte written (-1 if none). */
    private def pipe(in: InputStream, out: OutputStream, lastByte: Int): Int = {
        val buf = new Array[Byte](8192)
        var last = lastByte
        var len = in.read(buf)
        while (len != -1) {
            if (len > 0) {
                out.write(buf, 0, len)
                last = buf(len - 1) & 0xff
            }
            len = in.read(buf)
        }
        last
    }

    /** Bytes up to and including the first '\n' of the file at `path`, or 0
      * when it has no newline at all — such an entry is written whole, as the
      * old `split("\n", 2)` concatenation did. */
    private def headerLength(path: Path): Long = {
        val in = new BufferedInputStream(Files.newInputStream(path))
        try {
            var n = 0L
            var b = in.read()
            while (b != -1) {
                n += 1
                if (b == '\n') return n
                b = in.read()
            }
            0L
        } finally in.close()
    }

    /** One archive entry as a stream; closing it does not close the archive. */
    private class EntryStream(archive: ArchiveInputStream[_ <: ArchiveEntry]) extends InputStream {
        override def read(): Int = archive.read()
        override def read(b: Array[Byte], off: Int, len: Int): Int = archive.read(b, off, len)
        override def close(): Unit = ()
    }
}
