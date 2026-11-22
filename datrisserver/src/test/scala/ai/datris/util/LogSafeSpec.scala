package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import org.scalatest.funsuite.AnyFunSuite

class LogSafeSpec extends AnyFunSuite {

    test("line breaks and other control characters are written as escapes; everything else is kept") {
        assert(LogSafe.text("/api/v1/pipelines/Sales Orders") == "/api/v1/pipelines/Sales Orders")
        assert(LogSafe.text("a\nb\rc\td\u0000e\u007ff g\u0085h") == "a\\u000ab\\u000dc\\u0009d\\u0000e\\u007ff\\u2028g\\u0085h")
        assert(!LogSafe.text("x\r\ny").exists(c => c == '\n' || c == '\r'))
        assert(LogSafe.text(null) == "null")
        assert(LogSafe.text("") == "")
        assert(LogSafe.text("café %0A") == "café %0A")
    }
}
