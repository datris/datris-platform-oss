package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, StringReader}
import java.nio.charset.StandardCharsets

/** `StagedRows.chunks` + `TextChunkInputStream` (review finding 3): a
  *  supplementary character that straddles a 64K-char read boundary must
  *  round-trip through the per-chunk UTF-8 encoding, not become `??`. */
class StagedRowsSpec extends AnyFunSuite {

    private def roundTrip(text: String): Array[Byte] = {
        val in = new StagedRows.TextChunkInputStream(StagedRows.chunks(new StringReader(text)))
        val out = new ByteArrayOutputStream()
        val buf = new Array[Byte](8192)
        try {
            var n = in.read(buf)
            while (n >= 0) {
                out.write(buf, 0, n)
                n = in.read(buf)
            }
        } finally in.close()
        out.toByteArray
    }

    test("a supplementary character straddling the chunk boundary survives TextChunkInputStream") {
        val emoji = new String(Character.toChars(0x1f600)) // two UTF-16 chars
        val chunk = 64 * 1024
        // High surrogate is the last char of the first 64K read, low surrogate the first of the next.
        val text = ("x" * (chunk - 1)) + emoji + ("y" * 10)
        val bytes = roundTrip(text)
        assert(bytes.sameElements(text.getBytes(StandardCharsets.UTF_8)))
        assert(!new String(bytes, StandardCharsets.UTF_8).contains("?"))
    }

    test("chunks never end on a high surrogate and concatenate to the input") {
        val emoji = new String(Character.toChars(0x2000b)) // CJK Ext-B
        val chunk = 64 * 1024
        val text = ("a" * (chunk - 1)) + emoji + ("b" * (chunk - 1)) + emoji + emoji
        val it = StagedRows.chunks(new StringReader(text))
        val pieces =
            try it.toList
            finally it.close()
        assert(pieces.size >= 3)
        pieces.foreach(p => assert(!Character.isHighSurrogate(p.charAt(p.length - 1)), "chunk ends on a high surrogate"))
        assert(pieces.mkString == text)
    }

    test("a trailing high surrogate at EOF is still emitted") {
        val text = ("z" * (64 * 1024 - 1)) + "\uD83D"
        val it = StagedRows.chunks(new StringReader(text))
        val joined =
            try it.mkString
            finally it.close()
        assert(joined == text)
    }
}
