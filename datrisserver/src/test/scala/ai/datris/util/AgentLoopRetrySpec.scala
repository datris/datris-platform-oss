package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.model.{AIConfig, DatrisException}
import ai.datris.util.AIUtil.{AIContentBlock, AIStreamEvent, AIToolResponse}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** `AgentLoop.callWithRetry`: overload retries (unchanged) plus retries of
  * transient transport / provider-side errors, only while nothing has
  * streamed for the call. */
class AgentLoopRetrySpec extends AnyFunSuite {

    // OpenAI: no Sonnet fallback, so exhausted overloads surface the error.
    private val cfg = AIConfig("openai", "https://example.invalid/v1/chat/completions", "gpt-test", "key")
    private val ok = AIToolResponse(List(AIContentBlock.TextBlock("done")), wantsToolUse = false, stopReason = "end_turn")

    private class Harness(outcomes: List[(AIStreamEvent => Unit) => AIToolResponse]) {
        val events = ListBuffer.empty[AIStreamEvent]
        val sleeps = ListBuffer.empty[Long]
        var calls = 0
        def run(): AIToolResponse =
            AgentLoop.callWithRetry(
                call = (_: AIConfig, s: AIStreamEvent => Unit) => {
                    val f = outcomes(math.min(calls, outcomes.size - 1))
                    calls += 1
                    f(s)
                },
                aiConfig = cfg,
                sink = (e: AIStreamEvent) => events += e,
                cancelled = () => false,
                notice = (_: String) => (),
                sleep = (ms: Long) => sleeps += ms
            )
    }

    private def failsWith(e: Exception): (AIStreamEvent => Unit) => AIToolResponse = _ => throw e
    private val succeeds: (AIStreamEvent => Unit) => AIToolResponse = s => { s(AIStreamEvent.TextDelta("hi")); ok }

    test("an SSLException before any output is retried, then the call succeeds") {
        val h = new Harness(List(failsWith(new javax.net.ssl.SSLException("(bad_record_mac) Received fatal alert: bad_record_mac")), succeeds))
        assert(h.run() == ok)
        assert(h.calls == 2)
        assert(h.sleeps.toList == List(1000L))
        assert(h.events.toList == List(AIStreamEvent.TextDelta("hi")), "only the successful attempt's output reaches the sink")
    }

    test("a wrapped connection reset and a provider 502 are transient too") {
        val wrapped = new RuntimeException("I/O error", new java.net.SocketException("Connection reset"))
        val h = new Harness(List(failsWith(wrapped), failsWith(new DatrisException("AI API returned error status: 502, body: bad gateway")), succeeds))
        assert(h.run() == ok)
        assert(h.calls == 3)
    }

    test("an SSLException after output has streamed is not retried") {
        val partial: (AIStreamEvent => Unit) => AIToolResponse = s => {
            s(AIStreamEvent.TextDelta("half an ans"))
            throw new javax.net.ssl.SSLException("bad_record_mac")
        }
        val h = new Harness(List(partial, succeeds))
        intercept[javax.net.ssl.SSLException](h.run())
        assert(h.calls == 1)
        assert(h.sleeps.isEmpty)
    }

    test("a 401-style error is not retried") {
        val h = new Harness(List(
            failsWith(new DatrisException("The AI provider rejected the credentials (status 401) for model 'gpt-test'.")),
            succeeds
        ))
        intercept[DatrisException](h.run())
        assert(h.calls == 1)
        val h400 = new Harness(List(failsWith(new DatrisException("AI API returned error status: 400, body: bad")), succeeds))
        intercept[DatrisException](h400.run())
        assert(h400.calls == 1)
        val h429 = new Harness(List(failsWith(new DatrisException("AI API returned error status: 429, body: rate")), succeeds))
        intercept[DatrisException](h429.run())
        assert(h429.calls == 1)
    }

    test("cancellation is not treated as transient") {
        assert(!AgentLoop.isTransientTransportError(new DatrisException("Cancelled by user")))
    }

    test("transient retries exhausted propagates the last error") {
        val last = new java.net.SocketTimeoutException("Read timed out (3)")
        val h = new Harness(List(
            failsWith(new javax.net.ssl.SSLException("one")),
            failsWith(new java.net.ConnectException("two")),
            failsWith(last),
            succeeds
        ))
        val thrown = intercept[java.net.SocketTimeoutException](h.run())
        assert(thrown eq last)
        assert(h.calls == 3)
        assert(h.sleeps.toList == List(1000L, 3000L))
    }

    test("overloaded_error keeps its retry-then-surface behaviour") {
        val over = new DatrisException("""AI API returned error status: 529, body: {"type":"overloaded_error"}""")
        val h = new Harness(List(failsWith(over)))
        intercept[DatrisException](h.run())
        assert(h.calls == 3)
        assert(h.events.exists { case AIStreamEvent.Error(_) => true; case _ => false })
    }
}
