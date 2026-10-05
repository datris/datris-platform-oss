package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.DatrisException
import org.scalatest.funsuite.AnyFunSuite

/** plans/stories/run-status-message-not-stacktrace.md, Acceptance bullet 1.
  *
  * {{{
  * object ErrorText {
  *   def messageChain(e: Throwable, maxChars: Int = 2000): String
  * }
  * }}}
  *
  * Rules pinned here:
  *  - the throwable's message, or its simple class name (e.g. "IllegalStateException")
  *    when the message is null or blank;
  *  - each distinct cause appended as "\ncaused by: <message>" (same null/blank rule);
  *  - a cause whose text equals its parent's text is skipped;
  *  - a cause cycle terminates;
  *  - each message is reduced to its first line, unless the throwable is a
  *    DatrisException, whose multi-line message is kept whole;
  *  - the total is capped at maxChars, ending with "…";
  *  - the result never contains a "\tat " stack-frame line.
  */
class ErrorTextSpec extends AnyFunSuite {

    private def noFrames(s: String): Unit = {
        assert(!s.contains("\tat "), s"stack frame in: $s")
        assert(!s.linesIterator.exists(_.trim.startsWith("at ai.datris")), s"stack frame in: $s")
        assert(!s.linesIterator.exists(_.trim.startsWith("at java.")), s"stack frame in: $s")
    }

    test("a message is returned as is") {
        val out = ErrorText.messageChain(new RuntimeException("connection refused: db:5432"))
        assert(out == "connection refused: db:5432")
        noFrames(out)
    }

    test("a null message becomes the class name") {
        assert(ErrorText.messageChain(new IllegalStateException()) == "IllegalStateException")
        assert(ErrorText.messageChain(new IllegalStateException("   ")) == "IllegalStateException")
    }

    test("causes are appended one per line") {
        val root = new java.io.IOException("disk full")
        val mid = new IllegalStateException("could not write part file", root)
        val top = new RuntimeException("schema evolution failed", mid)
        val out = ErrorText.messageChain(top)
        assert(out == "schema evolution failed\ncaused by: could not write part file\ncaused by: disk full")
        noFrames(out)
    }

    test("a cause with no message is named by its class") {
        val out = ErrorText.messageChain(new RuntimeException("load failed", new NullPointerException()))
        assert(out == "load failed\ncaused by: NullPointerException")
    }

    test("a cause repeating the parent text is skipped") {
        val cause = new RuntimeException("relation \"orders\" does not exist")
        val top = new RuntimeException("relation \"orders\" does not exist", cause)
        assert(ErrorText.messageChain(top) == "relation \"orders\" does not exist")
    }

    test("a cause cycle terminates") {
        val a = new RuntimeException("a failed")
        val b = new IllegalStateException("b failed")
        a.initCause(b)
        b.initCause(a)
        val out = ErrorText.messageChain(a)
        assert(out == "a failed\ncaused by: b failed")
    }

    test("the result is capped") {
        val long = new RuntimeException("x" * 5000)
        val capped = ErrorText.messageChain(long, 100)
        assert(capped.length <= 100, s"length ${capped.length}")
        assert(capped.endsWith("…"))
        assert(capped.startsWith("xxxx"))

        val default = ErrorText.messageChain(long)
        assert(default.length <= 2000, s"default cap is 2000, got ${default.length}")
        assert(default.endsWith("…"))

        val short = ErrorText.messageChain(new RuntimeException("short"), 100)
        assert(short == "short", "no ellipsis when under the cap")
    }

    test("a multi-line DatrisException message is kept") {
        val msg = "Aborting processing this pipeline, 2 error(s):\nrow 1: amount is negative\nrow 2: id is missing"
        assert(ErrorText.messageChain(new DatrisException(msg)) == msg)
    }

    test("a multi-line message from any other exception is reduced to its first line") {
        val e = new RuntimeException("first line\n\tat ai.datris.util.DataUtil$.evolveSchema(DataUtil.scala:175)\nmore")
        val out = ErrorText.messageChain(e)
        assert(out == "first line")
        noFrames(out)
    }

    test("a wrapper whose message is the JDK's cause.toString contributes nothing") {
        val root = new IllegalStateException("column order differs")
        val wrapper = new RuntimeException(root) // message == "java.lang.IllegalStateException: column order differs"
        assert(wrapper.getMessage == root.toString)
        assert(ErrorText.messageChain(wrapper) == "column order differs")

        val top = new RuntimeException("load failed", new RuntimeException(new java.io.IOException("disk full")))
        assert(ErrorText.messageChain(top) == "load failed\ncaused by: disk full")
    }

    test("a DatrisException that embeds a stack trace keeps its message lines but no frames") {
        val msg = "Pipeline error: java.lang.IllegalStateException: schema mismatch\n\tat ai.datris.util.DataUtil$.evolveSchema(DataUtil.scala:175)\n" +
            "\tat java.base/java.lang.Thread.run(Thread.java:840)\n\t... 12 more\nrow 2: id is missing"
        val out = ErrorText.messageChain(new DatrisException(msg))
        assert(out == "Pipeline error: java.lang.IllegalStateException: schema mismatch\nrow 2: id is missing")
        noFrames(out)
        assert(!out.contains("more"))
        assert(ErrorText.messageChain(new DatrisException("\tat ai.datris.X.y(X.scala:1)")) == "DatrisException")
    }

    test("classChain lists the exception classes along the cause chain, class names only") {
        val e = new IllegalStateException("schema mismatch", new RuntimeException("column amount missing"))
        assert(ErrorText.classChain(e) == "java.lang.IllegalStateException <- java.lang.RuntimeException")
        assert(ErrorText.classChain(new ai.datris.model.DatrisException("secret value 123-45-6789")) == "ai.datris.model.DatrisException")
        assert(!ErrorText.classChain(e).contains("schema mismatch"))
    }

    test("classChain names a class outside the known package roots by its simple name, and ends on a cycle") {
        val outside = new errortextfixture.CustomFailure("boom")
        assert(ErrorText.classChain(outside) == "CustomFailure")
        val a = new RuntimeException("a")
        val b = new IllegalStateException("b")
        a.initCause(b)
        b.initCause(a)
        assert(ErrorText.classChain(a) == "java.lang.RuntimeException <- java.lang.IllegalStateException")
        assert(ErrorText.classChain(null) == "")
    }
}
