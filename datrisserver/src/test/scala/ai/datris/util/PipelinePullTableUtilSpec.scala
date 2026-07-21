package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

import java.text.SimpleDateFormat
import java.util.Date

class PipelinePullTableUtilSpec extends AnyFunSuite {

    test("formatDate output is byte-identical to the legacy SimpleDateFormat pattern") {
        val legacy = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        // Fixed instants spanning midnight, noon, single-digit fields, and millis padding.
        val samples = List(0L, 1234L, 1700000000123L, 1699999999999L, 946684800000L)
        for (millis <- samples) {
            val date = new Date(millis)
            assert(PipelinePullTableUtil.formatDate(date) == legacy.format(date))
        }
    }

    test("parseDate round-trips formatDate to the same instant") {
        val date = new Date(1700000000123L)
        assert(PipelinePullTableUtil.parseDate(PipelinePullTableUtil.formatDate(date)) == date)
    }

    test("parseDate matches what the legacy formatter parsed") {
        val legacy = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
        val stored = "2026-07-21 08:30:15.042"
        assert(PipelinePullTableUtil.parseDate(stored) == legacy.parse(stored))
    }
}
