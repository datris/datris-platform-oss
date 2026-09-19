package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayInputStream, StringWriter}
import java.nio.charset.StandardCharsets

class CSVReaderSpec extends AnyFunSuite {

    private def stream(s: String) = new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8))

    private val reader = new CSVReader

    test("filters to the requested columns in filter order") {
        val out = reader.readFromStream(
            stream("a,b,c\n1,2,3\n4,5,6"),
            header = true,
            delimiter = ",",
            columnList = List("a", "b", "c"),
            columnFilter = List("a", "c")
        )
        assert(out == "a,c\n1,3\n4,6")
    }

    test("removeHeader drops the first row when header is present") {
        val out = reader.readFromStream(
            stream("a,b\n1,2"),
            header = true,
            delimiter = ",",
            columnList = List("a", "b"),
            columnFilter = List("a", "b"),
            removeHeader = true
        )
        assert(out == "1,2")
    }

    test("column filter matching is case-insensitive") {
        val out =
            reader.readFromStream(stream("Name,Age\nbob,7"), header = true, delimiter = ",", columnList = List("Name", "Age"), columnFilter = List("name"))
        assert(out == "Name\nbob")
    }

    test("values containing the delimiter are re-quoted on output") {
        val out = reader.readFromStream(
            stream("a,b\n\"x,y\",2"),
            header = true,
            delimiter = ",",
            columnList = List("a", "b"),
            columnFilter = List("a", "b"),
            removeHeader = true
        )
        assert(out == "\"x,y\",2")
    }

    test("embedded quotes are doubled per RFC 4180 on output") {
        val out = reader.readFromStream(
            stream("a\n\"say \"\"hi\"\"\""),
            header = true,
            delimiter = ",",
            columnList = List("a"),
            columnFilter = List("a"),
            removeHeader = true
        )
        assert(out == "\"say \"\"hi\"\"\"")
    }

    test("trimColumns strips surrounding whitespace") {
        val out = reader.readFromStream(
            stream("a,b\n 1 , 2 "),
            header = true,
            delimiter = ",",
            columnList = List("a", "b"),
            columnFilter = List("a", "b"),
            trimColumns = true,
            removeHeader = true
        )
        assert(out == "1,2")
    }

    test("empty lines are ignored") {
        val out =
            reader.readFromStream(stream("a\n1\n\n2"), header = true, delimiter = ",", columnList = List("a"), columnFilter = List("a"), removeHeader = true)
        assert(out == "1\n2")
    }

    test("readFromStream closes the stream it was given (takes ownership)") {
        var closed = false
        val tracking = new ByteArrayInputStream("a\n1".getBytes(StandardCharsets.UTF_8)) {
            override def close(): Unit = { closed = true; super.close() }
        }
        reader.readFromStream(tracking, header = true, delimiter = ",", columnList = List("a"), columnFilter = List("a"))
        assert(closed)
    }

    test("alternate delimiter is honored for parsing and output") {
        val out = reader.readFromStream(
            stream("a|b\n1|2"),
            header = true,
            delimiter = "|",
            columnList = List("a", "b"),
            columnFilter = List("b"),
            removeHeader = true
        )
        assert(out == "2")
    }

    // plans/stories/streaming-pipeline.md, Phase 1: `readToWriter(..., out: Writer): Long`
    // is the streaming form of readFromStream — same parsing, same output bytes,
    // written to `out` instead of returned; returns the number of rows written.
    // Cases with header=true & removeHeader=false only check byte identity, since
    // whether the header line counts as a "row" is not what the story pins down.
    private val parityCases: Seq[(String, String, String, List[String], List[String], Boolean, Boolean)] = Seq(
        // (label, input, delimiter, columnList, columnFilter, trimColumns, removeHeader)
        ("column filter in filter order", "a,b,c\n1,2,3\n4,5,6", ",", List("a", "b", "c"), List("a", "c"), false, false),
        ("header removed", "a,b\n1,2\n3,4", ",", List("a", "b"), List("a", "b"), false, true),
        ("case-insensitive filter", "Name,Age\nbob,7", ",", List("Name", "Age"), List("name"), false, true),
        ("delimiter in value re-quoted", "a,b\n\"x,y\",2", ",", List("a", "b"), List("a", "b"), false, true),
        ("embedded quotes doubled", "a\n\"say \"\"hi\"\"\"", ",", List("a"), List("a"), false, true),
        ("trimColumns", "a,b\n 1 , 2 ", ",", List("a", "b"), List("a", "b"), true, true),
        ("empty lines ignored", "a\n1\n\n2", ",", List("a"), List("a"), false, true),
        ("alternate delimiter", "a|b\n1|2", "|", List("a", "b"), List("b"), false, true),
        ("header only, nothing left", "a,b\n", ",", List("a", "b"), List("a", "b"), false, true)
    )

    test("readToWriter returns the row count and writes byte-identical output to readFromStream") {
        parityCases.foreach { case (label, input, delimiter, columnList, columnFilter, trimColumns, removeHeader) =>
            val expected = reader.readFromStream(
                stream(input),
                header = true,
                delimiter = delimiter,
                columnList = columnList,
                columnFilter = columnFilter,
                trimColumns = trimColumns,
                removeHeader = removeHeader
            )
            val sw = new StringWriter
            val count = reader.readToWriter(
                stream(input),
                header = true,
                delimiter = delimiter,
                columnList = columnList,
                columnFilter = columnFilter,
                trimColumns = trimColumns,
                removeHeader = removeHeader,
                out = sw
            )
            assert(sw.toString == expected, s"$label: readToWriter output differs from readFromStream")
            if (removeHeader) {
                val expectedRows = if (expected.isEmpty) 0L else expected.split("\n").length.toLong
                assert(count == expectedRows, s"$label: expected $expectedRows rows written, got $count")
            }
        }
    }

    test("readToWriter without a header counts every data row") {
        val sw = new StringWriter
        val count = reader.readToWriter(
            stream("1,2\n3,4\n5,6"),
            header = false,
            delimiter = ",",
            columnList = List("a", "b"),
            columnFilter = List("b", "a"),
            out = sw
        )
        assert(count == 3L)
        assert(sw.toString == "2,1\n4,3\n6,5")
    }

    test("readToWriter closes the stream it was given, like readFromStream") {
        var closed = false
        val tracking = new ByteArrayInputStream("a\n1".getBytes(StandardCharsets.UTF_8)) {
            override def close(): Unit = { closed = true; super.close() }
        }
        reader.readToWriter(tracking, header = true, delimiter = ",", columnList = List("a"), columnFilter = List("a"), out = new StringWriter)
        assert(closed)
    }
}
