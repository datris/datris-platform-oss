package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

class CodeGenTransformationEvaluatorSpec extends AnyFunSuite {

    private val in = List("id", "first_name", "last_name", "email", "salary")

    test("script emitted a new header: it becomes the header, rows follow") {
        val lines = List("id,first_name,last_name,salary,full_name", "1,Ada,Lovelace,1200.5,Ada Lovelace", "2,Alan,Turing,1300,Alan Turing")
        val r = CodeGenTransformationEvaluator.splitHeader(lines, in, 2, ",")
        assert(r.headerFromScript)
        assert(r.header == List("id", "first_name", "last_name", "salary", "full_name"))
        assert(r.rows.size == 2)
    }

    test("script wrote data only: first row is kept as data and the input header stays") {
        val lines = List("1,Ada,Lovelace,ada@example.com,1200.5", "2,Alan,Turing,alan@example.com,1300")
        val r = CodeGenTransformationEvaluator.splitHeader(lines, in, 2, ",")
        assert(!r.headerFromScript)
        assert(r.header == in)
        assert(r.rows.size == 2)
    }

    test("all-text data with a filter (fewer rows) is not mistaken for a header when the first line recurs") {
        val lines = List("Ada,Lovelace", "Ada,Lovelace", "Grace,Hopper")
        val r = CodeGenTransformationEvaluator.splitHeader(lines, List("first_name", "last_name"), 5, ",")
        assert(!r.headerFromScript)
        assert(r.rows.size == 3)
    }

    test("unchanged header echoed back is recognised even when the row count changed") {
        val lines = List("id,first_name,last_name,email,salary", "1,Ada,Lovelace,ada@example.com,1200.5")
        val r = CodeGenTransformationEvaluator.splitHeader(lines, in, 3, ",")
        assert(r.headerFromScript && r.header == in && r.rows.size == 1)
    }

    test("empty output keeps the input header") {
        val r = CodeGenTransformationEvaluator.splitHeader(Nil, in, 3, ",")
        assert(r.header == in && r.rows.isEmpty)
    }

    test("splitLine honours quotes, doubled quotes and custom delimiters") {
        assert(CodeGenTransformationEvaluator.splitLine("a,\"b,c\",\"d\"\"e\"", ",") == List("a", "b,c", "d\"e"))
        assert(CodeGenTransformationEvaluator.splitLine("x|y|", "|") == List("x", "y", ""))
    }
}
