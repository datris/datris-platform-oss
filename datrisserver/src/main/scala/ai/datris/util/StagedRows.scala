package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import java.io.{InputStream, Reader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Readers over a staged payload file (plans/streaming-pipeline.md, Phase 2).
  *
  *  [[delimited]] is record-aware: a CSV value that `CSVReader.readToWriter`
  *  quoted because it holds a line terminator (`\n`, a lone `\r` or `\r\n`) or
  *  a quote is read back as part of one record, not split on the embedded
  *  terminator. Quoting follows RFC 4180 as commons-csv writes it: a quote
  *  opens a quoted value only at the start of a field, `""` inside a quoted
  *  value is an escaped quote, and a quote anywhere else is literal data (so a
  *  stray mid-field quote in tap output can never swallow the rest of the file).
  *
  *  [[lines]] is the plain line-per-record reader used for NDJSON.
  *
  *  Both close the file when exhausted, on a read failure, or on `close()`.
  */
object StagedRows {

    private val BufferSize = 64 * 1024

    /** Record-aware reader over a delimited staged file. */
    def delimited(path: String, delimiter: String): CloseableIterator[String] =
        new RecordIterator(Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8), delimiter)

    /** Line-per-record reader (NDJSON). `\n`, `\r` and `\r\n` all terminate a line. */
    def lines(path: String): CloseableIterator[String] =
        new RecordIterator(Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8), null)

    /** The staged file as UTF-8 text chunks (for verbatim payloads). */
    def chunks(path: Path): CloseableIterator[String] = chunks(Files.newBufferedReader(path, StandardCharsets.UTF_8))

    /** Text chunks from `reader`. Each chunk is a self-contained String: a
      * chunk never ends on a high surrogate (the char is carried into the
      * next one), so encoding chunks independently with UTF-8 — as
      * [[TextChunkInputStream]] does — cannot turn a supplementary character
      * that straddles a buffer boundary into `??`. Closes the reader at EOF. */
    private[util] def chunks(reader: Reader): CloseableIterator[String] = {
        val buf = new Array[Char](BufferSize)
        var pending: String = null
        var done = false
        var carry: Char = ' '
        var hasCarry = false
        def fill(): Unit =
            if (pending == null && !done) {
                val n = reader.read(buf)
                if (n < 0) {
                    done = true
                    reader.close()
                    if (hasCarry) { pending = carry.toString; hasCarry = false }
                } else {
                    val sb = new java.lang.StringBuilder(n + 1)
                    if (hasCarry) { sb.append(carry); hasCarry = false }
                    var take = n
                    if (n > 0 && Character.isHighSurrogate(buf(n - 1))) {
                        carry = buf(n - 1)
                        hasCarry = true
                        take = n - 1
                    }
                    sb.append(buf, 0, take)
                    if (sb.length > 0) pending = sb.toString else fill()
                }
            }
        new CloseableIterator[String] {
            override def hasNext: Boolean = { fill(); pending != null }
            override def next(): String = {
                fill()
                if (pending == null) throw new NoSuchElementException("staged payload exhausted")
                val s = pending
                pending = null
                s
            }
            override def close(): Unit = if (!done) { done = true; pending = null; reader.close() }
        }
    }

    /** An `InputStream` over UTF-8 text chunks, so a bulk loader (Postgres
      * COPY) can consume projected rows without a temp object or a full
      * String. A failure thrown by the chunk source is remembered in
      * [[failure]] and surfaced to the stream reader as an IOException, so the
      * caller can report the real cause instead of a truncated-copy error.
      * Closing the stream closes the chunk source. */
    class TextChunkInputStream(chunks: CloseableIterator[String]) extends InputStream {
        private var current: Array[Byte] = Array.empty
        private var pos = 0
        @volatile var failure: Throwable = null

        private def advance(): Boolean = {
            while (pos >= current.length) {
                val more =
                    try chunks.hasNext
                    catch { case t: Throwable => failure = t; throw new java.io.IOException(t.getMessage, t) }
                if (!more) return false
                current =
                    try chunks.next().getBytes(StandardCharsets.UTF_8)
                    catch { case t: Throwable => failure = t; throw new java.io.IOException(t.getMessage, t) }
                pos = 0
            }
            true
        }

        override def read(): Int =
            if (!advance()) -1
            else {
                val b = current(pos) & 0xff
                pos += 1
                b
            }

        override def read(b: Array[Byte], off: Int, len: Int): Int = {
            if (len == 0) return 0
            if (!advance()) return -1
            val n = math.min(len, current.length - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            n
        }

        override def close(): Unit = chunks.close()
    }

    /** Buffered char scanner. `delimiter == null` means plain line mode. */
    private class RecordIterator(reader: Reader, delimiter: String) extends CloseableIterator[String] {
        private val buf = new Array[Char](BufferSize)
        private var len = 0
        private var pos = 0
        private var eof = false
        private var closed = false
        private var pendingRecord: String = null
        private val sb = new java.lang.StringBuilder(256)
        private val quoteAware = delimiter != null && delimiter.nonEmpty
        private val delimLast: Char = if (quoteAware) delimiter.charAt(delimiter.length - 1) else ' '

        /** Refill the buffer; false at EOF. */
        private def refill(): Boolean = {
            if (eof) return false
            val n =
                try reader.read(buf)
                catch {
                    case e: Exception =>
                        close()
                        throw e
                }
            if (n < 0) {
                eof = true
                false
            } else {
                len = n
                pos = 0
                true
            }
        }

        private def peekChar(): Int = {
            if (pos >= len && !refill()) return -1
            buf(pos)
        }

        private def readChar(): Int = {
            if (pos >= len && !refill()) return -1
            val c = buf(pos)
            pos += 1
            c
        }

        /** True when the buffer content ends with the delimiter (multi-char aware). */
        private def endsWithDelimiter(): Boolean = {
            val n = delimiter.length
            if (sb.length < n) return false
            var i = 0
            while (i < n) {
                if (sb.charAt(sb.length - n + i) != delimiter.charAt(i)) return false
                i += 1
            }
            true
        }

        /** Read one record into `pendingRecord`; false at EOF with nothing read. */
        private def readRecord(): Boolean = {
            if (closed) return false
            sb.setLength(0)
            var inQuotes = false
            var fieldStart = true
            var consumed = false
            var done = false
            while (!done) {
                val c = readChar()
                if (c < 0) {
                    done = true
                } else {
                    consumed = true
                    val ch = c.toChar
                    if (inQuotes) {
                        if (ch == '"') {
                            if (peekChar() == '"') {
                                readChar()
                                sb.append("\"\"")
                            } else {
                                inQuotes = false
                                sb.append(ch)
                            }
                        } else sb.append(ch)
                    } else if (ch == '\n') {
                        done = true
                    } else if (ch == '\r') {
                        if (peekChar() == '\n') readChar()
                        done = true
                    } else if (quoteAware && ch == '"' && fieldStart) {
                        inQuotes = true
                        fieldStart = false
                        sb.append(ch)
                    } else {
                        sb.append(ch)
                        fieldStart = quoteAware && ch == delimLast && endsWithDelimiter()
                    }
                }
            }
            if (!consumed) {
                close()
                false
            } else {
                pendingRecord = sb.toString
                true
            }
        }

        override def hasNext: Boolean = {
            if (pendingRecord == null && !closed) readRecord()
            pendingRecord != null
        }

        override def next(): String = {
            if (!hasNext) throw new NoSuchElementException("staged payload exhausted")
            val r = pendingRecord
            pendingRecord = null
            r
        }

        override def close(): Unit =
            if (!closed) {
                closed = true
                pendingRecord = null
                try reader.close()
                catch { case _: Exception => () }
            }
    }
}
