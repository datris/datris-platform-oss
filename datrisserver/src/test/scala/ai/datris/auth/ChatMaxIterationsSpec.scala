package ai.datris.auth

/*
Datris
Copyright (C) 2026 Datris (https://datris.ai)
 */

import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

/** plans/stories/chat-session-role-authority.md (lead ruling g): every chat
  * that reads `maxIterations` from the request body clamps it to the
  * server's default; a caller may lower it, never raise it. */
class ChatMaxIterationsSpec extends AnyFunSuite {

    private def of(json: String): Int = ChatCaller.maxIterations(JsonParser.parseString(json).getAsJsonObject)

    test("the default applies when the field is absent or null, and is the ceiling") {
        assert(ChatCaller.MaxIterations == 50)
        assert(of("{}") == 50)
        assert(of("""{"maxIterations":null}""") == 50)
        assert(ChatCaller.maxIterations(null) == 50)
    }

    test("a request may lower the budget but never raise it") {
        assert(of("""{"maxIterations":5}""") == 5)
        assert(of("""{"maxIterations":50}""") == 50)
        assert(of("""{"maxIterations":51}""") == 50)
        assert(of("""{"maxIterations":100000}""") == 50)
        assert(of(s"""{"maxIterations":${Int.MaxValue}}""") == 50)
    }

    test("every chat controller reads the budget through the clamp") {
        val dir = new java.io.File("datrisserver/src/main/scala/ai/datris/api")
        val base = if (dir.isDirectory) dir else new java.io.File("src/main/scala/ai/datris/api")
        for (
            name <- Seq(
                "AssistantAPIController",
                "OpsChatAPIController",
                "CatalogChatAPIController",
                "SearchChatAPIController",
                "ConfigChatAPIController"
            )
        ) {
            val src = scala.io.Source.fromFile(new java.io.File(base, name + ".scala"))
            val text =
                try src.mkString
                finally src.close()
            assert(text.contains("ChatCaller.maxIterations(req)"), name)
            assert(!text.contains("""req.get("maxIterations").getAsInt"""), name)
        }
    }
}
