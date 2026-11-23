package ai.datris.util

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import ai.datris.config.{CapabilityInterceptor, TenantInterceptor}
import ai.datris.model.{AIConfig, DatrisException}
import ai.datris.util.AIUtil.{AIContentBlock, AIStreamEvent, AIToolResponse}
import ai.datris.util.AgentLoop.LoopEvent
import com.google.gson.JsonObject
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ListBuffer

/** Story: A chat stops when its user can no longer be verified, and the tool
  * list is never wider than what the server said.
  *
  * `AgentLoop.run` with a scripted model (`callModel`) and a scripted tool
  * executor (`execute`, which fails the way `MCPClient.callTool` does: a
  * `DatrisException("MCP tool '<name>' returned error: <body>")`):
  *   - a tool failing because the acting user could not be verified (the 401
  *     `acting_user_unverified` body) ends the run at once: no further tool
  *     call, no further model call, and a plain final message;
  *   - the user store being unavailable (the 503 `user_store_unavailable`
  *     body) is tolerated once; two such failures in a row end the run; a
  *     success in between resets the count;
  *   - every other tool failure behaves as today: it is fed back to the model
  *     and the run continues; it does not count toward the two;
  *   - a run whose user stays valid, the iteration cap and cancellation are
  *     unchanged. */
class AgentLoopFatalToolErrorSpec extends AnyFunSuite {

    private val UnverifiedSentence =
        "This chat was stopped: the user it was acting for could not be verified. Sign in again to continue."

    private val IterationCapPrefix = "I've used "

    private val cfg = AIConfig("openai", "https://example.invalid/v1/chat/completions", "gpt-test", "key")

    private def toolUse(id: String, name: String): AIContentBlock = AIContentBlock.ToolUseBlock(id, name, new JsonObject)

    private def wantsTools(blocks: AIContentBlock*): (AIStreamEvent => Unit) => AIToolResponse =
        _ => AIToolResponse(blocks.toList, wantsToolUse = true, stopReason = "tool_use")

    private def answers(text: String): (AIStreamEvent => Unit) => AIToolResponse = s => {
        s(AIStreamEvent.TextDelta(text))
        AIToolResponse(List(AIContentBlock.TextBlock(text)), wantsToolUse = false, stopReason = "end_turn")
    }

    /** What a scripted tool call does: answer data, or fail with a REST body. */
    private sealed trait Outcome
    private case class Ok(body: String) extends Outcome
    private case class Fails(body: String) extends Outcome
    private case class Throws(e: Exception) extends Outcome

    private val okBody = """{"count":1,"pipelines":[{"name":"orders"}]}"""

    private class Harness(
        model: List[(AIStreamEvent => Unit) => AIToolResponse],
        tools: List[Outcome],
        maxIterations: Int = 10,
        cancelAfterTool: Int = -1
    ) {
        val events = ListBuffer.empty[LoopEvent]
        val toolCalls = ListBuffer.empty[String]
        var modelCalls = 0

        def run(): List[LoopEvent] = {
            AgentLoop.run(
                aiConfig = cfg,
                system = "",
                userMessages = Seq("user" -> "go"),
                toolDefs = Nil,
                apiKey = "",
                enableThinking = false,
                maxIterations = maxIterations,
                maxTokensPerCall = 1000,
                cancelled = () => cancelAfterTool >= 0 && toolCalls.size > cancelAfterTool,
                sink = (e: LoopEvent) => events += e,
                execute = (name: String, _: JsonObject) => {
                    val o = tools(math.min(toolCalls.size, tools.size - 1))
                    toolCalls += name
                    o match {
                        case Ok(b) => b
                        case Fails(b) => throw new DatrisException("MCP tool '" + name + "' returned error: " + b)
                        case Throws(e) => throw e
                    }
                },
                callModel = (_: List[(String, List[AIContentBlock])], s: AIStreamEvent => Unit) => {
                    val f = model(math.min(modelCalls, model.size - 1))
                    modelCalls += 1
                    f(s)
                }
            )
            events.toList
        }
    }

    private def errors(evs: List[LoopEvent]): List[String] = evs.collect { case LoopEvent.Error(m) => m }

    private def text(evs: List[LoopEvent]): String = evs.collect { case LoopEvent.TextDelta(t) => t }.mkString

    private def assertEndsWithErrorResultThen(evs: List[LoopEvent], toolName: String, message: String): Unit = {
        assert(evs.size >= 2, evs)
        evs.takeRight(2) match {
            case List(LoopEvent.ToolResult(_, n, _, isError), LoopEvent.Error(m)) =>
                assert(n == toolName, evs)
                assert(isError, evs)
                assert(m == message, evs)
            case other => fail("expected the tool's error result then one Error, got: " + other)
        }
        assert(errors(evs).size == 1, evs)
        assert(!evs.contains(LoopEvent.Done), evs)
    }

    // ---------------------------------------------------------------------
    // The acting user can no longer be verified
    // ---------------------------------------------------------------------

    test("a tool failing because the acting user could not be verified ends the run after one model call") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "list_pipelines")), answers("should not be reached")),
            tools = List(Fails(TenantInterceptor.UnverifiedUserBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 1)
        assert(h.toolCalls.toList == List("list_pipelines"))
        assertEndsWithErrorResultThen(evs, "list_pipelines", UnverifiedSentence)
        assert(text(evs).isEmpty)
    }

    test("a user deleted mid-run: the run ends at the first unverified failure, the model is not called again") {
        val h = new Harness(
            model = List(
                wantsTools(toolUse("t1", "list_pipelines")),
                wantsTools(toolUse("t2", "get_pipeline")),
                answers("should not be reached")
            ),
            tools = List(Ok(okBody), Fails(TenantInterceptor.UnverifiedUserBody), Ok(okBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 2)
        assert(h.toolCalls.toList == List("list_pipelines", "get_pipeline"))
        assertEndsWithErrorResultThen(evs, "get_pipeline", UnverifiedSentence)
    }

    test("after an unverified failure no further tool call in the same batch is made") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "list_pipelines"), toolUse("t2", "get_pipeline"), toolUse("t3", "list_taps"))),
            tools = List(Fails(TenantInterceptor.UnverifiedUserBody), Ok(okBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 1)
        assert(h.toolCalls.toList == List("list_pipelines"))
        assert(errors(evs) == List(UnverifiedSentence))
        assert(evs.last == LoopEvent.Error(UnverifiedSentence))
    }

    test("the final message for an unverified user is plain text, not the REST body") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "list_pipelines"))),
            tools = List(Fails(TenantInterceptor.UnverifiedUserBody))
        )
        val last = h.run().last
        assert(last == LoopEvent.Error(UnverifiedSentence))
        val m = errors(List(last)).head
        assert(!m.contains("{") && !m.contains("errorKind") && !m.contains("acting_user_unverified"))
    }

    // ---------------------------------------------------------------------
    // The user store is unavailable (D1: two in a row end the run)
    // ---------------------------------------------------------------------

    private def assertUserStoreStop(evs: List[LoopEvent], toolName: String): Unit = {
        assert(evs.size >= 2, evs)
        evs.takeRight(2) match {
            case List(LoopEvent.ToolResult(_, n, _, true), LoopEvent.Error(m)) =>
                assert(n == toolName, evs)
                val l = m.toLowerCase
                assert(l.contains("could not") && l.contains("check") && l.contains("user"), m)
                assert(!m.contains("{") && !m.contains("errorKind"), m)
                assert(m != UnverifiedSentence, m)
            case other => fail("expected the tool's error result then one Error, got: " + other)
        }
        assert(errors(evs).size == 1, evs)
        assert(!evs.contains(LoopEvent.Done), evs)
    }

    test("one tool call failing because the user store is unavailable does not end the run") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "list_pipelines")), answers("Here is what I found.")),
            tools = List(Fails(TenantInterceptor.UserStoreUnavailableBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 2)
        assert(errors(evs).isEmpty, evs)
        assert(evs.last == LoopEvent.Done)
        assert(text(evs) == "Here is what I found.")
    }

    test("two consecutive tool calls failing because the user store is unavailable end the run") {
        val h = new Harness(
            model = List(
                wantsTools(toolUse("t1", "list_pipelines")),
                wantsTools(toolUse("t2", "list_pipelines")),
                answers("should not be reached")
            ),
            tools = List(Fails(TenantInterceptor.UserStoreUnavailableBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 2)
        assert(h.toolCalls.size == 2)
        assertUserStoreStop(evs, "list_pipelines")
    }

    test("a success between two user-store failures resets the count") {
        val h = new Harness(
            model = List(
                wantsTools(toolUse("t1", "list_pipelines")),
                wantsTools(toolUse("t2", "get_pipeline")),
                wantsTools(toolUse("t3", "list_taps")),
                answers("Done.")
            ),
            tools = List(Fails(TenantInterceptor.UserStoreUnavailableBody), Ok(okBody), Fails(TenantInterceptor.UserStoreUnavailableBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 4)
        assert(h.toolCalls.size == 3)
        assert(errors(evs).isEmpty, evs)
        assert(evs.last == LoopEvent.Done)
        assert(text(evs) == "Done.")
    }

    // ---------------------------------------------------------------------
    // Unchanged: any other tool failure is fed back and the run continues
    // ---------------------------------------------------------------------

    private val otherFailures: List[(String, Outcome)] = List(
        "403 outside the caller's access" -> Fails(CapabilityInterceptor.deniedBody("k", "pipeline", "write", "POST", "/api/v1/pipeline", None)),
        "403 capability denied (literal body)" -> Fails("""{"error":"capability denied","errorKind":"capability_denied","key":"k"}"""),
        "403 policy denied" -> Fails("""{"error":"Refused by the agent policy","errorKind":"policy_denied"}"""),
        "404 not found" -> Fails("""{"error":"Pipeline not found"}"""),
        "500 from a destination" -> Fails("""{"error":"Destination write failed: connection to the warehouse was closed"}"""),
        "timeout" -> Fails("""{"error":"HTTPConnectionPool(host='datris', port=8080): Read timed out. (read timeout=300)"}"""),
        "401 rejected key (key caller with no user)" -> Fails(CapabilityInterceptor.RejectedKeyBody),
        "503 key store unavailable" -> Fails(CapabilityInterceptor.MetadataUnavailableBody),
        "approval requester gone" -> Fails(TenantInterceptor.RequesterUserGoneBody),
        "approval requester unchecked" -> Fails(TenantInterceptor.RequesterUncheckedBody),
        "MCP transport 500" -> Throws(new DatrisException("MCP server returned 500 for method=tools/call, body: Internal Server Error")),
        "unexpected exception" -> Throws(new RuntimeException("boom"))
    )

    otherFailures.foreach {
        case (label, outcome) =>
            test(s"a tool failure that is not about the acting user does not end the run: $label") {
                val h = new Harness(
                    model = List(wantsTools(toolUse("t1", "list_pipelines")), answers("It failed; here is why.")),
                    tools = List(outcome)
                )
                val evs = h.run()
                assert(h.modelCalls == 2)
                assert(h.toolCalls.toList == List("list_pipelines"))
                assert(errors(evs).isEmpty, evs)
                assert(evs.last == LoopEvent.Done)
                assert(evs.exists { case LoopEvent.ToolResult(_, "list_pipelines", _, true) => true; case _ => false }, evs)
            }
    }

    test("other tool failures do not count toward the two user-store failures") {
        val failures = otherFailures.map(_._2)
        val h = new Harness(
            model = (1 to failures.size + 1).map(i => wantsTools(toolUse("t" + i, "list_pipelines"))).toList :+ answers("Done."),
            tools = failures :+ Fails(TenantInterceptor.UserStoreUnavailableBody) :+ Ok(okBody),
            maxIterations = failures.size + 5
        )
        val evs = h.run()
        assert(h.modelCalls == failures.size + 2)
        assert(errors(evs).isEmpty, evs)
        assert(evs.last == LoopEvent.Done)
    }

    test("several user-store failures in one batch of tool calls count once") {
        val h = new Harness(
            model = List(
                wantsTools(toolUse("t1", "list_pipelines"), toolUse("t2", "get_pipeline"), toolUse("t3", "list_taps")),
                answers("Done.")
            ),
            tools = List(Fails(TenantInterceptor.UserStoreUnavailableBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 2)
        assert(h.toolCalls.toList == List("list_pipelines", "get_pipeline", "list_taps"))
        assert(errors(evs).isEmpty, evs)
        assert(evs.last == LoopEvent.Done)
    }

    test("a user-store failure in the next batch after a batch of them ends the run at that tool call") {
        val h = new Harness(
            model = List(
                wantsTools(toolUse("t1", "list_pipelines"), toolUse("t2", "get_pipeline")),
                wantsTools(toolUse("t3", "list_taps"), toolUse("t4", "get_tap")),
                answers("should not be reached")
            ),
            tools = List(Fails(TenantInterceptor.UserStoreUnavailableBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 2)
        assert(h.toolCalls.toList == List("list_pipelines", "get_pipeline", "list_taps"))
        assertUserStoreStop(evs, "list_taps")
    }

    test("a failure of another kind between two user-store failures neither counts nor resets: the second ends the run") {
        val h = new Harness(
            model = List(
                wantsTools(toolUse("t1", "list_pipelines")),
                wantsTools(toolUse("t2", "get_pipeline")),
                wantsTools(toolUse("t3", "list_taps")),
                answers("should not be reached")
            ),
            tools = List(
                Fails(TenantInterceptor.UserStoreUnavailableBody),
                Fails("""{"error":"Pipeline not found"}"""),
                Fails(TenantInterceptor.UserStoreUnavailableBody)
            )
        )
        val evs = h.run()
        assert(h.modelCalls == 3)
        assert(h.toolCalls.toList == List("list_pipelines", "get_pipeline", "list_taps"))
        assertUserStoreStop(evs, "list_taps")
    }

    test("a successful tool call in the same batch resets the user-store count") {
        val h = new Harness(
            model = List(
                wantsTools(toolUse("t1", "list_pipelines")),
                wantsTools(toolUse("t2", "get_pipeline"), toolUse("t3", "list_taps")),
                wantsTools(toolUse("t4", "get_tap")),
                answers("Done.")
            ),
            tools = List(
                Fails(TenantInterceptor.UserStoreUnavailableBody),
                Ok(okBody),
                Fails(TenantInterceptor.UserStoreUnavailableBody),
                Fails(TenantInterceptor.UserStoreUnavailableBody)
            )
        )
        val evs = h.run()
        // 1: failure (count 1); 2: success resets, failure (count 1); 3: failure (count 2) ends.
        assert(h.modelCalls == 3)
        assert(h.toolCalls.size == 4)
        assertUserStoreStop(evs, "get_tap")
    }

    test("an unverified failure after a user-store failure ends the run with the unverified message") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "list_pipelines")), wantsTools(toolUse("t2", "get_pipeline"))),
            tools = List(Fails(TenantInterceptor.UserStoreUnavailableBody), Fails(TenantInterceptor.UnverifiedUserBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 2)
        assertEndsWithErrorResultThen(evs, "get_pipeline", UnverifiedSentence)
    }

    test("an unverified body re-serialised with other spacing is still recognised") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "list_pipelines")), answers("should not be reached")),
            tools = List(Fails("""{"error": "The user this request acts for could not be verified", "errorKind": "acting_user_unverified"}"""))
        )
        val evs = h.run()
        assert(h.modelCalls == 1)
        assertEndsWithErrorResultThen(evs, "list_pipelines", UnverifiedSentence)
    }

    test("an unverified body inside a different tool's failure text, or not as the failure body, does not end the run") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "list_pipelines")), answers("It failed.")),
            tools = List(Throws(new DatrisException("Tool proxy failed: " + TenantInterceptor.UnverifiedUserBody)))
        )
        val evs = h.run()
        assert(h.modelCalls == 2)
        assert(errors(evs).isEmpty, evs)
        assert(evs.last == LoopEvent.Done)
    }

    test("FatalToolErrors.kindOf reads only the failure's own errorKind") {
        def ex(name: String, body: String) = new DatrisException("MCP tool '" + name + "' returned error: " + body)
        assert(FatalToolErrors.kindOf("a", ex("a", TenantInterceptor.UnverifiedUserBody)).contains(FatalToolErrors.UnverifiedUser))
        assert(FatalToolErrors.kindOf("a", ex("a", TenantInterceptor.UserStoreUnavailableBody)).contains(FatalToolErrors.UserStoreUnavailable))
        assert(FatalToolErrors.kindOf("b", ex("a", TenantInterceptor.UnverifiedUserBody)).isEmpty)
        assert(FatalToolErrors.kindOf("a", ex("a", "(no detail)")).isEmpty)
        assert(FatalToolErrors.kindOf("a", ex("a", """{"errorKind":"acting_user_unverified"}""")).isEmpty)
        assert(FatalToolErrors.kindOf("a", ex("a", """{"error":"x","errorKind":"capability_denied"}""")).isEmpty)
        assert(FatalToolErrors.kindOf("a", new RuntimeException("MCP tool 'a' returned error: " + TenantInterceptor.UnverifiedUserBody)).isEmpty)
    }

    test("a successful result whose data mentions the unverified errorKind does not end the run") {
        val auditPage =
            """{"entries":[{"actor":"ui","status":401,"body":{"error":"The user this request acts for could not be verified","errorKind":"acting_user_unverified"}}]}"""
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "get_audit_log")), answers("One refused request.")),
            tools = List(Ok(auditPage))
        )
        val evs = h.run()
        assert(h.modelCalls == 2)
        assert(errors(evs).isEmpty, evs)
        assert(evs.last == LoopEvent.Done)
    }

    // ---------------------------------------------------------------------
    // Unchanged: a run whose user stays valid, the cap, cancellation
    // ---------------------------------------------------------------------

    test("a run whose user stays valid emits exactly the events it does today") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "list_pipelines")), answers("You have one pipeline.")),
            tools = List(Ok(okBody))
        )
        val evs = h.run()
        assert(h.modelCalls == 2)
        assert(h.toolCalls.toList == List("list_pipelines"))
        assert(
            evs == List(
                LoopEvent.IterationStart,
                LoopEvent.ToolResult("t1", "list_pipelines", okBody, isError = false),
                LoopEvent.IterationStart,
                LoopEvent.TextDelta("You have one pipeline."),
                LoopEvent.Done
            )
        )
    }

    test("the iteration cap still ends a run whose tools keep failing for other reasons") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t", "list_pipelines"))),
            tools = List(Fails("""{"error":"Internal error"}""")),
            maxIterations = 3
        )
        val evs = h.run()
        assert(h.modelCalls == 3)
        assert(h.toolCalls.size == 3)
        val errs = errors(evs)
        assert(errs.size == 1 && errs.head.startsWith(IterationCapPrefix + "3 iterations"), errs)
        assert(evs.last == LoopEvent.Done)
    }

    test("cancellation still ends the run as today") {
        val h = new Harness(
            model = List(wantsTools(toolUse("t1", "list_pipelines")), answers("should not be reached")),
            tools = List(Ok(okBody)),
            cancelAfterTool = 0
        )
        val evs = h.run()
        assert(h.modelCalls == 1)
        assert(evs.last == LoopEvent.Error("Cancelled by user"))
    }
}
