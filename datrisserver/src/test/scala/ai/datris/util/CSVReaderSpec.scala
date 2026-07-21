package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
*/

import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class CSVReaderSpec extends AnyFunSuite {

    private def stream(s: String) = new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8))

    private val reader = new CSVReader

    test("filters to the requested columns in filter order") {
        val out = reader.readFromStream(stream("a,b,c\n1,2,3\n4,5,6"),
            header = true, delimiter = ",", columnList = List("a", "b", "c"), columnFilter = List("a", "c"))
        assert(out == "a,c\n1,3\n4,6")
    }

    test("removeHeader drops the first row when header is present") {
        val out = reader.readFromStream(stream("a,b\n1,2"),
            header = true, delimiter = ",", columnList = List("a", "b"), columnFilter = List("a", "b"), removeHeader = true)
        assert(out == "1,2")
    }

    test("column filter matching is case-insensitive") {
        val out = reader.readFromStream(stream("Name,Age\nbob,7"),
            header = true, delimiter = ",", columnList = List("Name", "Age"), columnFilter = List("name"))
        assert(out == "Name\nbob")
    }

    test("values containing the delimiter are re-quoted on output") {
        val out = reader.readFromStream(stream("a,b\n\"x,y\",2"),
            header = true, delimiter = ",", columnList = List("a", "b"), columnFilter = List("a", "b"), removeHeader = true)
        assert(out == "\"x,y\",2")
    }

    test("embedded quotes are doubled per RFC 4180 on output") {
        val out = reader.readFromStream(stream("a\n\"say \"\"hi\"\"\""),
            header = true, delimiter = ",", columnList = List("a"), columnFilter = List("a"), removeHeader = true)
        assert(out == "\"say \"\"hi\"\"\"")
    }

    test("trimColumns strips surrounding whitespace") {
        val out = reader.readFromStream(stream("a,b\n 1 , 2 "),
            header = true, delimiter = ",", columnList = List("a", "b"), columnFilter = List("a", "b"),
            trimColumns = true, removeHeader = true)
        assert(out == "1,2")
    }

    test("empty lines are ignored") {
        val out = reader.readFromStream(stream("a\n1\n\n2"),
            header = true, delimiter = ",", columnList = List("a"), columnFilter = List("a"), removeHeader = true)
        assert(out == "1\n2")
    }

    test("alternate delimiter is honored for parsing and output") {
        val out = reader.readFromStream(stream("a|b\n1|2"),
            header = true, delimiter = "|", columnList = List("a", "b"), columnFilter = List("b"), removeHeader = true)
        assert(out == "2")
    }
}
