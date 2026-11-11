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
  * transient transport / provider-side errors, also after partial output;
  * `retrying` fires before every retry so the UI can drop the failed
  * attempt's output. */
class AgentLoopRetrySpec extends AnyFunSuite {

    // OpenAI: no Sonnet fallback, so exhausted overloads surface the error.
    private val cfg = AIConfig("openai", "https://example.invalid/v1/chat/completions", "gpt-test", "key")
    private val ok = AIToolResponse(List(AIContentBlock.TextBlock("done")), wantsToolUse = false, stopReason = "end_turn")

    private class Harness(outcomes: List[(AIStreamEvent => Unit) => AIToolResponse]) {

        /** Stream events and `Retrying(attempt, max, reason)` markers, in order. */
        val events = ListBuffer.empty[Any]
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
                retrying = (a: Int, m: Int, r: String) => events += AgentLoop.LoopEvent.Retrying(a, m, r),
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
        assert(h.events.toList == List(AgentLoop.LoopEvent.Retrying(1, 2, "connection problem"), AIStreamEvent.TextDelta("hi")))
    }

    test("a wrapped connection reset and a provider 502 are transient too") {
        val wrapped = new RuntimeException("I/O error", new java.net.SocketException("Connection reset"))
        val h = new Harness(List(failsWith(wrapped), failsWith(new DatrisException("AI API returned error status: 502, body: bad gateway")), succeeds))
        assert(h.run() == ok)
        assert(h.calls == 3)
    }

    test("an SSLException after output has streamed IS retried, with Retrying between the attempts") {
        val partial: (AIStreamEvent => Unit) => AIToolResponse = s => {
            s(AIStreamEvent.TextDelta("half an ans"))
            throw new javax.net.ssl.SSLException("bad_record_mac")
        }
        val h = new Harness(List(partial, succeeds))
        assert(h.run() == ok)
        assert(h.calls == 2)
        assert(h.sleeps.toList == List(1000L))
        assert(h.events.toList == List(
            AIStreamEvent.TextDelta("half an ans"),
            AgentLoop.LoopEvent.Retrying(1, 2, "connection problem"),
            AIStreamEvent.TextDelta("hi")
        ))
    }

    test("a transient fault does not use up the overload retry budget") {
        val over = new DatrisException("""AI API returned error status: 529, body: {"type":"overloaded_error"}""")
        val h = new Harness(List(
            failsWith(new javax.net.ssl.SSLException("bad_record_mac")),
            failsWith(over),
            failsWith(over),
            succeeds
        ))
        assert(h.run() == ok, "two overload retries are still available after an SSL retry")
        assert(h.calls == 4)
        assert(h.sleeps.toList == List(1000L, 1000L, 3000L))
        val retries = h.events.collect { case r: AgentLoop.LoopEvent.Retrying => r }.toList
        assert(retries == List(
            AgentLoop.LoopEvent.Retrying(1, 2, "connection problem"),
            AgentLoop.LoopEvent.Retrying(1, 2, "provider overloaded"),
            AgentLoop.LoopEvent.Retrying(2, 2, "provider overloaded")
        ))
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
        assert(h.events.count(_.isInstanceOf[AgentLoop.LoopEvent.Retrying]) == 2, "no Retrying after the last failure")
    }

    test("overloaded_error keeps its retry-then-surface behaviour") {
        val over = new DatrisException("""AI API returned error status: 529, body: {"type":"overloaded_error"}""")
        val h = new Harness(List(failsWith(over)))
        intercept[DatrisException](h.run())
        assert(h.calls == 3)
        assert(h.events.exists { case AIStreamEvent.Error(_) => true; case _ => false })
    }

    test("Retrying goes onto the SSE wire as a retrying event") {
        import scala.collection.JavaConverters._
        val frames = ListBuffer.empty[String]
        val em = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
            override def send(b: org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder): Unit =
                frames += b.build().asScala.map(_.getData.toString).mkString
        }
        assert(ai.datris.api.AssistantSseSupport.emitLoopEvent(em, AgentLoop.LoopEvent.Retrying(1, 2, "connection problem")))
        val frame = frames.mkString
        assert(frame.contains("event:retrying"), frame)
        val o = com.google.gson.JsonParser.parseString(frame.substring(frame.indexOf('{'), frame.lastIndexOf('}') + 1)).getAsJsonObject
        assert(o.get("type").getAsString == "retrying")
        assert(o.get("attempt").getAsInt == 1)
        assert(o.get("max").getAsInt == 2)
        assert(o.get("reason").getAsString == "connection problem")
    }
}
